package com.oniel.music.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Talks to a SpinUs server (the `server/` folder of this repo) over its
 * HTTP + WebSocket API. No Jellyfin anywhere.
 *
 * Auth: `POST /api/auth/login` returns a token, sent on every call as
 * `Authorization: Bearer <token>`. Image and stream URLs also carry
 * `?token=` so the image loader and ExoPlayer can fetch them directly;
 * PlaybackService still attaches [authHeaderValue] to audio requests.
 *
 * Presence: [connectPresence] opens `/api/socket`; while it is open the
 * server can deliver friends' messages to this phone. Playback reports
 * ([reportPlaying] / [reportStopped]) are what friends see in
 * [getSessions]. Everyone with an account on the server can see
 * everyone else — there is no extra permission to set up.
 *
 * Durations: the server speaks milliseconds; the app's models keep
 * 100-ns "ticks" ([TICKS_PER_MS]) so the rest of the code is unchanged.
 *
 * This class holds no Android types and does no disk I/O of its own, so
 * it can be constructed in tests; only the actual HTTP calls need a
 * server.
 */
class SpinUsClient(
    private val http: OkHttpClient = defaultHttpClient(),
    /** Stable per-install device id; one login (and one session) per device. */
    private val deviceId: String = UUID.randomUUID().toString()
) {
    companion object {
        const val CLIENT_NAME = "SpinUs"
        /** Earlier name: friends on older versions still count as the same app. */
        val APP_CLIENT_NAMES = setOf(CLIENT_NAME, "Oniel Music")
        /** Message headers for stats sharing between SpinUs apps. */
        const val STATS_REQUEST = "onielmusic:stats-request"
        const val STATS_REPLY = "onielmusic:stats-reply"
        const val CLIENT_VERSION = "2.0.0"
        const val DEFAULT_PORT = 8484
        const val TICKS_PER_MS = 10_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()

        /**
         * "192.168.1.10", "nas:8484", "http://host:8484/" -> "http://host:8484".
         * http:// is assumed when no scheme is given, and the SpinUs port
         * when no port is given.
         */
        fun normalizeBaseUrl(serverUrl: String): String {
            var url = serverUrl.trim().trimEnd('/')
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://$url"
            val scheme = url.substringBefore("://")
            val rest = url.substringAfter("://")
            val host = rest.substringBefore('/')
            val path = rest.substring(host.length)
            val hasPort = host.substringAfterLast(']').contains(':')
            // https (e.g. a Tailscale Serve / reverse-proxy name) means 443.
            return if (hasPort || scheme == "https") url else "$scheme://$host:$DEFAULT_PORT$path"
        }
    }

    var baseUrl: String? = null
        private set
    var token: String? = null
        private set
    var userId: String? = null
        private set
    var userName: String? = null
        private set
    var isAdmin: Boolean = false
        private set
    /** This device's session id (from login, or fetched on demand). */
    var sessionId: String? = null
        private set

    val isLoggedIn: Boolean get() = baseUrl != null && token != null && userId != null

    /** Called (on a background thread) when a friend sends this phone a message. */
    @Volatile var onMessage: ((fromUser: String, text: String) -> Unit)? = null

    private var presenceSocket: WebSocket? = null

    // ---- Auth ----

    /**
     * Log in with username + password. [serverUrl] may be a bare host/IP,
     * host:port, or a full URL. Throws [IOException] on network errors and
     * on a non-2xx response (401 = wrong password). The very first sign-in
     * on a brand-new server creates that account as the server's admin.
     */
    suspend fun login(serverUrl: String, username: String, password: String): AuthResult {
        val base = normalizeBaseUrl(serverUrl)
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            put("deviceId", deviceId)
            put("deviceName", (try { android.os.Build.MODEL } catch (e: Throwable) { null }) ?: CLIENT_NAME)
            put("client", CLIENT_NAME)
            put("version", CLIENT_VERSION)
        }
        val request = Request.Builder().url("$base/api/auth/login").post(body.json()).build()
        val json = try {
            JSONObject(executeRaw(request, "login") ?: throw IOException("login failed: not a SpinUs server"))
        } catch (e: org.json.JSONException) {
            throw IOException("login failed: not a SpinUs server")
        }
        val user = json.getJSONObject("user")
        baseUrl = base
        token = json.getString("token")
        userId = user.getString("id")
        userName = user.getString("name")
        isAdmin = user.optBoolean("isAdmin", false)
        sessionId = json.optStringOrNull("sessionId")
        return AuthResult(token!!, userId!!, userName!!, isAdmin, user.optBoolean("mustChangePassword", false))
    }

    /**
     * Change your password ([current] is the temporary one on a first
     * sign-in). Your other devices are signed out. Throws with the
     * server's reason ("the current password is wrong", ...) on failure.
     */
    suspend fun changePassword(current: String, new: String) {
        val body = JSONObject().put("currentPassword", current).put("password", new)
        executeRaw(authedRequest("/api/me/password").post(body.json()).build(), "change password")
    }

    // ---- Friends & recommendations ----

    private fun JSONArray?.toFriends(): List<Friend> = if (this == null) emptyList() else
        (0 until length()).map { i ->
            getJSONObject(i).let {
                Friend(it.getString("id"), it.optString("name"), it.optIntOrNull("level"), it.optStringOrNull("title"))
            }
        }

    /** Your listener level (XP from songs, minutes, albums, genres, days, likes, playlists...). */
    suspend fun getMyLevel(): Level {
        val j = getJson("/api/me/level", "level")
        val parts = j.optJSONArray("breakdown") ?: JSONArray()
        val next = j.optJSONObject("nextTitle")
        return Level(
            level = j.optInt("level", 1),
            title = j.optString("title"),
            xp = j.optInt("xp"),
            levelStartXp = j.optInt("levelStartXp"),
            nextLevelXp = j.optIntOrNull("nextLevelXp"),
            progress = j.optDouble("progress", 0.0).toFloat(),
            nextTitleLevel = next?.optInt("level"),
            nextTitle = next?.optString("title"),
            breakdown = (0 until parts.length()).map { i ->
                parts.getJSONObject(i).let {
                    LevelPart(it.optString("label"), it.optInt("count"), it.optInt("xpEach"), it.optInt("xp"))
                }
            }
        )
    }

    /** People you're friends with (both sides agreed, or a scanned QR code). */
    suspend fun getFriends(): List<Friend> = items("/api/friends", "friends").toFriends()

    suspend fun getFriendRequests(): FriendRequests {
        val json = getJson("/api/friends/requests", "friend requests")
        return FriendRequests(json.optJSONArray("incoming").toFriends(), json.optJSONArray("outgoing").toFriends())
    }

    /** Find people on the server by username (2+ letters). */
    suspend fun searchPeople(query: String): List<PersonHit> {
        val arr = items("/api/friends/search?q=${enc(query)}", "search people")
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            PersonHit(p.getString("id"), p.optString("name"), p.optStringOrNull("relation"))
        }
    }

    private suspend fun friendCall(path: String, userId: String): String? {
        val body = JSONObject().put("userId", userId)
        val res = executeRaw(authedRequest(path).post(body.json()).build(), "friends") ?: return null
        return if (res.isBlank()) null else JSONObject(res).optStringOrNull("relation")
    }

    /** Send a friend request (or accept theirs, if they already asked). Returns the new relation. */
    suspend fun requestFriend(userId: String): String? = friendCall("/api/friends/request", userId)
    suspend fun acceptFriend(userId: String) { friendCall("/api/friends/accept", userId) }
    /** Unfriend, cancel your request, or decline theirs. */
    suspend fun removeFriend(userId: String) { friendCall("/api/friends/remove", userId) }

    /** Your QR code (the code, and the spinus://friend/<code> link it encodes). */
    suspend fun myFriendCode(reset: Boolean = false): Pair<String, String> {
        val json = if (reset) {
            JSONObject(executeRaw(authedRequest("/api/friends/code/reset").post(JSONObject().json()).build(), "code")
                ?: throw IOException("code failed"))
        } else getJson("/api/friends/code", "code")
        return json.getString("code") to json.getString("link")
    }

    /** Scanned someone's code (or link): you're friends now. Returns their name. */
    suspend fun useFriendCode(codeOrLink: String): String {
        val body = JSONObject().put("code", codeOrLink)
        val res = executeRaw(authedRequest("/api/friends/code").post(body.json()).build(), "add friend")
            ?: throw IOException("add friend failed: HTTP 404 (that code isn't valid; ask them to show it again)")
        return JSONObject(res).optJSONObject("friend")?.optString("name").orEmpty()
    }

    /** Recommend a song ("track"), "album" or "artist" to [toUserIds]. */
    suspend fun recommend(toUserIds: List<String>, itemType: String, itemId: String, message: String? = null) {
        val body = JSONObject().apply {
            put("toUserIds", JSONArray(toUserIds))
            put("itemType", itemType)
            put("itemId", itemId)
            if (!message.isNullOrBlank()) put("message", message)
        }
        executeRaw(authedRequest("/api/recommendations").post(body.json()).build(), "recommend")
            ?: throw IOException("recommend failed: not in the library")
    }

    /** Recommendations sent to you, newest first. */
    suspend fun getRecommendations(): List<Recommendation> {
        val arr = items("/api/recommendations", "recommendations")
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Recommendation(
                id = r.getString("id"), from = r.optString("from"), itemType = r.optString("itemType"),
                itemId = r.optString("itemId"), title = r.optString("title"), subtitle = r.optStringOrNull("subtitle"),
                message = r.optStringOrNull("message"), imageId = r.optString("imageId"),
                imageTag = r.optStringOrNull("imageTag"), albumId = r.optStringOrNull("albumId"),
                artistId = r.optStringOrNull("artistId"), read = r.optBoolean("read"), sentAt = r.optString("sentAt")
            )
        }
    }

    suspend fun unreadRecommendations(): Int =
        getJson("/api/recommendations/unread", "unread").optInt("count", 0)

    suspend fun markRecommendationsRead() {
        executeRaw(authedRequest("/api/recommendations/read").post(JSONObject().json()).build(), "mark read")
    }

    suspend fun deleteRecommendation(id: String) {
        executeRaw(authedRequest("/api/recommendations/$id").delete().build(), "delete recommendation")
    }

    /** Songs by id (e.g. to play a recommended song). */
    suspend fun getTracksByIds(ids: List<String>): List<Track> =
        if (ids.isEmpty()) emptyList() else tracks("/api/tracks?ids=${ids.joinToString(",")}")

    /**
     * Called (on a background thread) when something arrives for the badge:
     * [kind] "recommendation", "friend-request" or "friend-accepted"; [unread]
     * is the new badge count.
     */
    @Volatile var onInbox: ((kind: String, from: String, unread: Int) -> Unit)? = null

    /** Restore a previously saved login (from [AuthStore]) without calling the server. */
    fun restoreSession(
        serverUrl: String,
        savedToken: String,
        savedUserId: String,
        savedUserName: String,
        savedIsAdmin: Boolean
    ) {
        baseUrl = normalizeBaseUrl(serverUrl)
        token = savedToken
        userId = savedUserId
        userName = savedUserName
        isAdmin = savedIsAdmin
        sessionId = null
    }

    // ---- Library reads ----

    /** All albums in the library, with play data summed over their tracks. */
    suspend fun getAlbums(): List<Album> = albums("/api/albums?sort=name")

    /** Same as [getAlbums]: the server already sums play data from tracks. */
    suspend fun getAlbumsWithTrackPlayData(): List<Album> = getAlbums()

    suspend fun getTracks(): List<Track> = tracks("/api/tracks?sort=album")

    suspend fun getRecentlyPlayedAlbums(limit: Int = 20): List<Album> =
        albums("/api/albums?sort=played&order=desc&limit=$limit")

    suspend fun getRecentlyAddedAlbums(limit: Int = 30): List<Album> =
        albums("/api/albums?sort=added&order=desc&limit=$limit")

    suspend fun getPlaylists(): List<Playlist> = playlists("/api/playlists")

    private val detailsCache = ConcurrentHashMap<String, ItemDetails>()

    /** Full metadata for one album or artist. Cached per item id. */
    suspend fun getItemDetails(itemId: String): ItemDetails {
        detailsCache[itemId]?.let { return it }
        return getJson("/api/items/$itemId", "item details").toItemDetails()
            .also { detailsCache[itemId] = it }
    }

    /** Artist metadata looked up by name. Null when the library has no such artist. */
    suspend fun getArtistByName(name: String): ItemDetails? {
        val body = executeRaw(authedRequest("/api/artists/by-name?name=${enc(name)}").get().build(), "artist")
            ?: return null
        return JSONObject(body).toItemDetails().also { detailsCache[it.id] = it }
    }

    /** One album's tracks, in disc / track-number order. */
    suspend fun getAlbumTracks(albumId: String): List<Track> = tracks("/api/tracks?albumId=$albumId")

    /** Albums an artist appears on, newest first. */
    suspend fun getArtistAlbums(artistId: String): List<Album> =
        albums("/api/albums?artistId=$artistId&sort=year&order=desc")

    /** Albums the server finds similar (shared genres, same artist, era). */
    suspend fun getSimilarAlbums(albumId: String, limit: Int = 8): List<Album> =
        albums("/api/albums/$albumId/similar?limit=$limit")

    /** All tracks by an artist (for Play / Shuffle on the artist page). */
    suspend fun getArtistTracks(artistId: String): List<Track> =
        tracks("/api/tracks?artistIds=$artistId&sort=album")

    /** Songs matching [query] (title, artist or album). */
    suspend fun search(query: String): List<Track> = searchSongs(query)

    private fun enc(q: String) = URLEncoder.encode(q, "UTF-8").replace("+", "%20")

    suspend fun searchArtists(query: String): List<ArtistHit> =
        artistHits("/api/artists?q=${enc(query)}&limit=15")

    suspend fun searchAlbums(query: String): List<Album> =
        albums("/api/albums?q=${enc(query)}&limit=20")

    suspend fun searchSongs(query: String): List<Track> =
        tracks("/api/tracks?q=${enc(query)}&limit=40")

    /** Most-played songs of these artists (so an artist search lists songs too). */
    suspend fun songsByArtists(artistIds: List<String>, limit: Int = 20): List<Track> {
        if (artistIds.isEmpty()) return emptyList()
        return tracks("/api/tracks?artistIds=${artistIds.joinToString(",")}&sort=playCount&order=desc&limit=$limit")
    }

    suspend fun searchPlaylists(query: String): List<Playlist> =
        playlists("/api/playlists?q=${enc(query)}&limit=15")

    /** Every song (for matching chart songs to the library). */
    suspend fun getTrackIndex(): List<Track> = tracks("/api/tracks")

    /** Every artist that has a photo, keyed by lower-case name. */
    suspend fun getArtistPictures(): Map<String, ArtistHit> {
        val map = HashMap<String, ArtistHit>()
        for (hit in artistHits("/api/artists?withImage=true")) {
            if (hit.imageTag != null) map.putIfAbsent(hit.name.lowercase(), hit)
        }
        return map
    }

    /** Favorite flag changed in this session, if any (lists may be older). */
    fun favoriteOverride(itemId: String): Boolean? = favoriteOverrides[itemId]

    /** A station: songs similar to an artist, album, song, genre or playlist. */
    suspend fun getInstantMix(itemId: String, limit: Int = 100): List<Track> =
        tracks("/api/items/$itemId/mix?limit=$limit")

    /** A playlist's songs, in playlist order. */
    suspend fun getPlaylistTracks(playlistId: String): List<Track> =
        tracks("/api/playlists/$playlistId/tracks")

    // ---- Genres ----

    @Volatile private var genresCache: List<Genre>? = null

    /** Every music genre in the library, A to Z. */
    suspend fun getMusicGenres(): List<Genre> {
        genresCache?.let { return it }
        val items = getJson("/api/genres", "genres").optJSONArray("items") ?: return emptyList()
        val list = (0 until items.length()).mapNotNull { i ->
            val g = items.optJSONObject(i) ?: return@mapNotNull null
            val name = g.optString("name", "")
            if (name.isBlank()) null else Genre(g.getString("id"), name, g.optStringOrNull("imageTag"))
        }
        genresCache = list
        return list
    }

    suspend fun getGenreAlbums(genreId: String): List<Album> =
        albums("/api/albums?genreId=$genreId&sort=name")

    suspend fun getGenreTracks(genreId: String): List<Track> =
        tracks("/api/tracks?genreId=$genreId&sort=album")

    // ---- Favorites ----

    private val favoriteOverrides = ConcurrentHashMap<String, Boolean>()

    /** Is [itemId] a favorite? Session changes win, then [known], else the server is asked. */
    suspend fun isFavorite(itemId: String, known: Boolean? = null): Boolean {
        favoriteOverrides[itemId]?.let { return it }
        if (known != null) return known
        return getJson("/api/items/$itemId", "favorite state").optBoolean("isFavorite", false)
    }

    /** Mark or unmark a song, album or artist as a favorite. */
    suspend fun setFavorite(itemId: String, favorite: Boolean) {
        val b = authedRequest("/api/favorites/$itemId")
        val request = if (favorite) b.put(ByteArray(0).toRequestBody(null)).build() else b.delete().build()
        executeRaw(request, "favorite") ?: if (favorite) throw IOException("favorite failed: item not found") else Unit
        favoriteOverrides[itemId] = favorite
        detailsCache.remove(itemId)
    }

    // ---- Playlists ----

    /** Create a playlist holding [trackIds]. Returns the new playlist id. */
    suspend fun createPlaylist(name: String, trackIds: List<String>): String {
        val body = JSONObject().apply {
            put("name", name)
            put("trackIds", JSONArray(trackIds))
        }
        val json = JSONObject(
            executeRaw(authedRequest("/api/playlists").post(body.json()).build(), "create playlist")
                ?: throw IOException("create playlist failed")
        )
        return json.optString("id").ifBlank { throw IOException("create playlist failed: no id returned") }
    }

    /** Append [trackIds] to an existing playlist. */
    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        val body = JSONObject().put("trackIds", JSONArray(trackIds))
        executeRaw(authedRequest("/api/playlists/$playlistId/tracks").post(body.json()).build(), "add to playlist")
            ?: throw IOException("add to playlist failed: playlist not found")
    }

    // ---- Lyrics ----

    /**
     * Lyrics for a track.
     * - null: no lyrics (HTTP 404).
     * - empty list: plain-text lyrics only (no usable timestamps).
     * - non-empty: synced lines, sorted by start time.
     */
    suspend fun getLyrics(trackId: String): List<LyricLine>? {
        val body = executeRaw(authedRequest("/api/lyrics/$trackId").get().build(), "lyrics") ?: return null
        val raw = JSONObject(body).optJSONArray("lines") ?: return emptyList()
        val lines = (0 until raw.length()).map { i ->
            val line = raw.getJSONObject(i)
            RawLyricLine(
                text = line.optString("text", ""),
                startTicks = if (line.has("startMs")) line.optLong("startMs") * TICKS_PER_MS else null
            )
        }
        return Lyrics.classify(lines)
    }

    // ---- Presence & messaging ----

    /**
     * Open the WebSocket that lets friends' messages (emoji reactions,
     * stats requests) reach this phone. Safe to call again — an open
     * socket is left alone. OkHttp pings keep it alive through NAT and
     * Tailscale; the server drops it if the phone goes quiet.
     */
    suspend fun connectPresence() {
        val base = baseUrl ?: throw IOException("not logged in")
        val tok = token ?: throw IOException("not logged in")
        if (presenceSocket != null) return
        val wsUrl = base.replaceFirst("http://", "ws://").replaceFirst("https://", "wss://") +
            "/api/socket?token=${enc(tok)}"
        val wsHttp = http.newBuilder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
        presenceSocket = wsHttp.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handleSocketMessage(text)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (presenceSocket === webSocket) presenceSocket = null
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (presenceSocket === webSocket) presenceSocket = null
            }
        })
    }

    /** Builds this user's stats as JSON for a friend; null = not sharing. */
    @Volatile var statsResponder: (suspend () -> String?)? = null

    private val socketScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pendingStats = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    private fun handleSocketMessage(text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("type")) {
                "hello" -> msg.optStringOrNull("sessionId")?.let { sessionId = it }
                "recommendation", "friend-request", "friend-accepted" ->
                    onInbox?.invoke(msg.optString("type"), msg.optString("from"), msg.optInt("unread", 0))
                "message" -> {
                    val header = msg.optString("header")
                    val body = msg.optString("text")
                    when (header) {
                        STATS_REQUEST -> answerStatsRequest(body)
                        STATS_REPLY -> {
                            val reply = JSONObject(body)
                            pendingStats.remove(reply.optString("id"))?.complete(reply.optString("data"))
                        }
                        else -> {
                            val from = msg.optJSONObject("from")?.optString("userName").orEmpty()
                            onMessage?.invoke(from, body)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Unknown or malformed message: ignore.
        }
    }

    private fun answerStatsRequest(body: String) {
        socketScope.launch {
            try {
                val req = JSONObject(body)
                val session = req.optString("session").ifBlank { return@launch }
                val data = statsResponder?.invoke() ?: return@launch
                val reply = JSONObject().apply {
                    put("id", req.optString("id"))
                    put("data", data)
                }.toString()
                sendMessage(session, reply, header = STATS_REPLY, timeoutMs = 1000)
            } catch (e: Exception) {
                // Best-effort.
            }
        }
    }

    private suspend fun ownSessionId(): String? {
        sessionId?.let { return it }
        val body = executeRaw(authedRequest("/api/sessions/self").get().build(), "own session") ?: return null
        return JSONObject(body).optStringOrNull("sessionId")?.also { sessionId = it }
    }

    /**
     * Ask a friend's app for their listening stats. Returns the summary
     * JSON, or null if they don't answer within [timeoutMs] (app closed or
     * sharing off).
     */
    suspend fun requestFriendStats(friend: FriendSession, timeoutMs: Long = 8000): String? {
        if (presenceSocket == null) connectPresence()
        val mySession = ownSessionId() ?: return null
        if (friend.onielSessionIds.isEmpty()) return null
        val id = UUID.randomUUID().toString()
        val waiter = CompletableDeferred<String?>()
        pendingStats[id] = waiter
        try {
            val req = JSONObject().apply {
                put("id", id)
                put("session", mySession)
            }.toString()
            friend.onielSessionIds.forEach { sid ->
                try { sendMessage(sid, req, header = STATS_REQUEST, timeoutMs = 1000) } catch (e: Exception) { }
            }
            return withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            pendingStats.remove(id)
        }
    }

    fun closePresence() {
        presenceSocket?.close(1000, "closing")
        presenceSocket = null
    }

    /**
     * Other people on the server who are listening right now (not paused),
     * one entry per person. The local user is never included.
     */
    suspend fun getSessions(): List<FriendSession> {
        val items = getJson("/api/sessions", "sessions").optJSONArray("items") ?: return emptyList()
        val byUser = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until items.length()) {
            val s = items.getJSONObject(i)
            byUser.getOrPut(s.getString("userId")) { mutableListOf() }.add(s)
        }
        return byUser.values.map { sessions ->
            val first = sessions.first()
            val np = first.getJSONObject("nowPlaying")
            FriendSession(
                userId = first.getString("userId"),
                userName = first.optString("userName", ""),
                trackTitle = np.optString("title", ""),
                artist = np.optJSONArray("artists")?.toStringList()?.joinToString(", ").orEmpty(),
                albumId = np.optStringOrNull("albumId"),
                imageTag = np.optStringOrNull("imageTag"),
                positionTicks = first.optLong("positionMs", 0L) * TICKS_PER_MS,
                runTimeTicks = np.optLong("durationMs", 0L) * TICKS_PER_MS,
                sessionIds = sessions.map { it.getString("sessionId") },
                onielSessionIds = sessions
                    .filter { it.optString("client") in APP_CLIENT_NAMES && it.optBoolean("online", true) }
                    .map { it.getString("sessionId") }
            )
        }
    }

    /**
     * Send a message (an emoji) to another phone. Delivered only while that
     * phone's app is open; silently dropped otherwise.
     */
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        header: String = CLIENT_NAME,
        timeoutMs: Int = 5000
    ) {
        val body = JSONObject().apply {
            put("text", text)
            put("header", header)
            put("timeoutMs", timeoutMs)
        }
        executeRaw(authedRequest("/api/sessions/$sessionId/message").post(body.json()).build(), "send message")
    }

    // ---- Play history ----

    /** Count one play of [trackId] at [datePlayedIso] (ISO-8601). Not idempotent. */
    suspend fun markPlayed(trackId: String, datePlayedIso: String) {
        val body = JSONObject().put("datePlayed", datePlayedIso)
        executeRaw(authedRequest("/api/plays/$trackId").post(body.json()).build(), "mark played")
    }

    // ---- Per-user app storage (listening-history sync) ----

    /** A JSON document this app keeps in the user's account ({} the first time). */
    suspend fun getAppPrefs(prefsId: String, client: String): JSONObject =
        getJson("/api/prefs/${enc("$client.$prefsId")}", "history (read)")

    /** Replace that JSON document. */
    suspend fun saveAppPrefs(prefsId: String, client: String, dto: JSONObject) {
        val request = authedRequest("/api/prefs/${enc("$client.$prefsId")}").put(dto.json()).build()
        executeRaw(request, "history (save)")
    }

    // ---- URLs for the player / image loader ----

    /**
     * Audio URL for a track. The server sends the original file with
     * Content-Length and byte ranges (real duration, seeking, crossfade);
     * only formats ExoPlayer can't decode (ALAC, WMA, AIFF, APE) are
     * converted to MP3 on the fly.
     */
    fun audioUrl(trackId: String): String {
        val base = requireBaseUrl()
        val tok = token ?: throw IOException("not logged in")
        return "$base/api/stream/$trackId?token=${enc(tok)}"
    }

    /** Backdrop (wide background) image URL, or null when the item has none. */
    fun backdropUrl(itemId: String, imageTag: String?, width: Int = 1280, index: Int = 0): String? {
        if (imageTag.isNullOrEmpty()) return null
        return "${requireBaseUrl()}/api/images/$itemId?type=backdrop&index=$index&w=$width&tag=$imageTag&token=${enc(token ?: "")}"
    }

    /** Primary image URL for an item (album cover / artist photo). */
    fun imageUrl(itemId: String, imageTag: String?, width: Int = 300): String {
        val tag = if (!imageTag.isNullOrEmpty()) "&tag=$imageTag" else ""
        return "${requireBaseUrl()}/api/images/$itemId?w=$width$tag&token=${enc(token ?: "")}"
    }

    /** Artwork for a track: its album's cover, or the artist's picture for a loose song. */
    fun trackArtUrl(track: Track, width: Int = 300): String {
        val albumId = track.albumId
        val artistId = track.artistId
        if (albumId != null && track.imageTag != null) return imageUrl(albumId, track.imageTag, width)
        if (albumId == null) return imageUrl(track.id, track.imageTag, width) // embedded or downloaded cover
        if (artistId != null) return imageUrl(artistId, null, width)
        return imageUrl(albumId, null, width)
    }

    /** An album's cover, or the album artist's picture when it has none. */
    fun albumArtUrl(album: Album, width: Int = 300): String {
        val artistId = album.artistId
        return if (album.imageTag == null && artistId != null) imageUrl(artistId, null, width)
        else imageUrl(album.id, album.imageTag, width)
    }

    fun albumArtFallback(album: Album, width: Int = 300): String? =
        album.artistId?.let { imageUrl(it, null, width) }

    /** An artist's own picture (404s, and so falls back, when there is none). */
    fun artistImageUrl(artistId: String, width: Int = 300): String = imageUrl(artistId, null, width)

    // ---- Playback reporting (what friends see) ----

    suspend fun reportPlaying(trackId: String, positionTicks: Long) {
        val body = JSONObject().apply {
            put("trackId", trackId)
            put("positionMs", positionTicks / TICKS_PER_MS)
            put("paused", false)
        }
        executeRaw(authedRequest("/api/sessions/playing").post(body.json()).build(), "report playing")
    }

    suspend fun reportStopped(trackId: String, positionTicks: Long, playedToCompletion: Boolean) {
        val body = JSONObject().apply {
            put("trackId", trackId)
            put("positionMs", positionTicks / TICKS_PER_MS)
            put("playedToCompletion", playedToCompletion)
        }
        executeRaw(authedRequest("/api/sessions/stopped").post(body.json()).build(), "report stopped")
    }

    // ---- Internals ----

    /** The `Authorization` header value; PlaybackService sends it on audio requests. */
    fun authHeaderValue(): String = "Bearer ${token ?: ""}"

    private fun authedRequest(path: String): Request.Builder =
        Request.Builder().url("${requireBaseUrl()}$path").header("Authorization", authHeaderValue())

    private fun requireBaseUrl(): String = baseUrl ?: throw IOException("not logged in")

    private fun JSONObject.json() = toString().toRequestBody(JSON_MEDIA_TYPE)

    private suspend fun getJson(path: String, what: String): JSONObject {
        val body = executeRaw(authedRequest(path).get().build(), what)
            ?: throw IOException("$what failed: not found")
        return JSONObject(body)
    }

    private suspend fun items(path: String, what: String): JSONArray =
        getJson(path, what).optJSONArray("items") ?: JSONArray()

    private suspend fun albums(path: String) = items(path, "library").toAlbumList()
    private suspend fun tracks(path: String) = items(path, "library").toTrackList()
    private suspend fun playlists(path: String): List<Playlist> {
        val arr = items(path, "playlists")
        return (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Playlist(p.getString("id"), p.optString("name", ""), p.optInt("trackCount", 0))
        }
    }
    private suspend fun artistHits(path: String): List<ArtistHit> {
        val arr = items(path, "artists")
        return (0 until arr.length()).mapNotNull { i ->
            val a = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = a.optString("name", "")
            if (name.isBlank()) null else ArtistHit(a.getString("id"), name, a.optStringOrNull("imageTag"))
        }
    }

    /**
     * Execute a request, returning the body, or null on 404 (a normal "not
     * there" outcome, e.g. no lyrics). 401 means the login was revoked or
     * the server was reset. Any other non-2xx status throws.
     */
    private suspend fun executeRaw(request: Request, what: String): String? =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) {
                    val msg = try { JSONObject(response.body?.string().orEmpty()).optString("error") } catch (e: Exception) { "" }
                    throw IOException("$what failed: HTTP ${response.code}${if (msg.isNotBlank()) " ($msg)" else ""}")
                }
                response.body?.string()
            }
        }

    private fun JSONArray.toAlbumList(): List<Album> =
        (0 until length()).map { i ->
            val a = getJSONObject(i)
            val ud = a.optJSONObject("userData")
            Album(
                id = a.getString("id"),
                name = a.optString("name", ""),
                artist = a.optString("artist", ""),
                year = a.optIntOrNull("year"),
                premiereDate = a.optStringOrNull("date")?.let(::isoDate),
                dateCreated = a.optStringOrNull("dateAdded"),
                imageTag = a.optStringOrNull("imageTag"),
                childCount = a.optInt("trackCount", 0),
                lastPlayedDate = ud?.optStringOrNull("lastPlayed"),
                playCount = ud?.optInt("playCount", 0) ?: 0,
                artistId = a.optStringOrNull("artistId")
            )
        }

    private fun JSONArray.toTrackList(): List<Track> =
        (0 until length()).map { i ->
            val t = getJSONObject(i)
            val ud = t.optJSONObject("userData")
            Track(
                id = t.getString("id"),
                title = t.optString("title", ""),
                artists = t.optJSONArray("artists")?.toStringList().orEmpty(),
                album = t.optString("album", ""),
                albumId = t.optStringOrNull("albumId"),
                albumArtist = t.optString("albumArtist", ""),
                runTimeTicks = t.optLong("durationMs", 0L) * TICKS_PER_MS,
                imageTag = t.optStringOrNull("imageTag"),
                playCount = ud?.optInt("playCount", 0) ?: 0,
                lastPlayedDate = ud?.optStringOrNull("lastPlayed"),
                isFavorite = ud?.optBoolean("isFavorite", false) ?: false,
                artistId = t.optJSONArray("artistIds")?.optStringOrNull(0) ?: t.optStringOrNull("albumArtistId"),
                indexNumber = t.optIntOrNull("trackNumber"),
                discNumber = t.optIntOrNull("discNumber"),
                genres = t.optJSONArray("genres")?.toStringList().orEmpty(),
                year = t.optIntOrNull("year")
            )
        }

    private fun JSONObject.toItemDetails(): ItemDetails = ItemDetails(
        id = getString("id"),
        name = optString("name", ""),
        type = optString("type", ""),
        overview = optStringOrNull("overview")?.let(::cleanOverview)?.ifBlank { null },
        genres = optJSONArray("genres")?.toStringList().orEmpty(),
        studios = optJSONArray("studios")?.toStringList().orEmpty(),
        year = optIntOrNull("year"),
        runTimeTicks = optLong("durationMs", 0L) * TICKS_PER_MS,
        childCount = optInt("childCount", 0),
        primaryImageTag = optStringOrNull("imageTag"),
        backdropTags = optJSONArray("backdropTags")?.toStringList().orEmpty(),
        parentBackdropItemId = optStringOrNull("parentBackdropItemId"),
        parentBackdropTags = optJSONArray("parentBackdropTags")?.toStringList().orEmpty(),
        artistName = optStringOrNull("artistName"),
        artistId = optStringOrNull("artistId"),
        isFavorite = optBoolean("isFavorite", false)
    )
}

/**
 * Tag dates come as "2021", "2021-05" or "2021-05-02"; the app's release
 * logic expects a full ISO timestamp, as Jellyfin used to send.
 */
internal fun isoDate(raw: String): String? {
    val m = Regex("""^(\d{4})(?:-(\d{2}))?(?:-(\d{2}))?""").find(raw.trim()) ?: return null
    val (y, mo, d) = m.destructured
    return "$y-${mo.ifEmpty { "01" }}-${d.ifEmpty { "01" }}T00:00:00.0000000Z"
}

/** Overviews may hold HTML from metadata sources (<br>, <a>, entities). Turn that into plain text. */
internal fun cleanOverview(raw: String): String =
    raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</?p(\\s[^>]*)?>"), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .lines().joinToString("\n") { it.trim() }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

private fun JSONArray.optStringOrNull(index: Int): String? =
    if (index < length() && !isNull(index)) optString(index) else null

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { i -> optStringOrNull(i) }
