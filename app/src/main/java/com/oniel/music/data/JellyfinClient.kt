package com.oniel.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.concurrent.TimeUnit

/**
 * Talks to a Jellyfin server (verified against 12.2.0) over its public
 * HTTP API using OkHttp.
 *
 * Auth rule that matters most: the token only works inside the
 * `Authorization: MediaBrowser Token="…"` header. The `X-MediaBrowser-Token`
 * header and the `?api_key=` query parameter both return 401 on
 * authenticated routes in Jellyfin 12. Images and the audio stream URL
 * still carry `api_key=` as well, because those endpoints accept it and
 * some hardened servers require it.
 *
 * Playback reporting ([reportPlaying]/[reportStopped]) and presence
 * ([getSessions]) only work when the app holds an open WebSocket and has
 * reported its capabilities — call [connectPresence] once after login
 * and keep the socket open while the app runs. A non-admin account also
 * needs the server-side "Allow remote control of other users" permission
 * before it can see anyone else or send them a message.
 *
 * This class holds no Android types and does no disk I/O of its own, so
 * it can be constructed in tests; only the actual HTTP calls need a
 * server.
 */
class JellyfinClient(
    private val http: OkHttpClient = defaultHttpClient(),
    /** Stable per-install device id; the session (and therefore presence) is keyed to it. */
    private val deviceId: String = UUID.randomUUID().toString()
) {
    companion object {
        const val CLIENT_NAME = "Oniel Music"
        const val CLIENT_VERSION = "1.0.5"
        /**
         * Containers sent as-is (no transcode) by [audioUrl]: all play in
         * ExoPlayer on Android 8+. "m4a%7Caac" is Jellyfin's container|codec
         * syntax with the pipe URL-encoded. ALAC is left out on purpose
         * (no built-in Android decoder), so ALAC files get transcoded.
         */
        const val DIRECT_CONTAINERS =
            "mp3,flac,m4a%7Caac,m4b%7Caac,mp4%7Caac,aac,ogg%7Cvorbis,ogg%7Copus,oga,opus,webma,webm%7Copus,wav"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** Base URL as passed to [login] or [restoreSession], without a trailing slash. */
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

    /** This device's session id, from the login response. */
    var sessionId: String? = null
        private set

    private var presenceSocket: WebSocket? = null

    /**
     * Id of the current play session, generated fresh for each new track
     * in [reportPlaying] and reused by [reportStopped]. Jellyfin keys
     * progress/stop updates to the Playing report by this id: a progress
     * or stop report without the matching id is accepted (204) but its
     * position is ignored. A future progress call must send this same id.
     */
    private var currentPlaySessionId: String? = null
    private var currentPlayTrackId: String? = null

    val isLoggedIn: Boolean get() = baseUrl != null && token != null && userId != null

    // ---- Auth ----

    /**
     * Log in with username + password. [serverUrl] may be a bare host/IP
     * ("192.168.1.10:8096"), a host without a scheme, or a full URL;
     * "http://" is assumed when no scheme is given and a trailing slash
     * is stripped. Throws [IOException] on network errors and on a
     * non-200 response (401 = wrong password).
     */
    suspend fun login(serverUrl: String, username: String, password: String): AuthResult {
        val base = normalizeBaseUrl(serverUrl)
        val body = JSONObject().apply {
            put("Username", username)
            put("Pw", password)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$base/Users/AuthenticateByName")
            .header("Authorization", loginAuthHeader())
            .post(body)
            .build()
        val json = executeJson(request, "login")
        val user = json.getJSONObject("User")
        val accessToken = json.getString("AccessToken")
        val id = user.getString("Id")
        val name = user.getString("Name")
        val admin = user.optJSONObject("Policy")?.optBoolean("IsAdministrator", false) ?: false
        baseUrl = base
        token = accessToken
        userId = id
        userName = name
        isAdmin = admin
        sessionId = json.optJSONObject("SessionInfo")?.optStringOrNull("Id")
        return AuthResult(accessToken, id, name, admin)
    }

    /**
     * Restore a previously saved login (e.g. from [AuthStore]) without
     * calling the server. The token is used as-is on the next request.
     */
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

    /** All albums in the library. */
    suspend fun getAlbums(): List<Album> {
        val json = getItems(
            "IncludeItemTypes=MusicAlbum&Recursive=true" +
                "&Fields=DateCreated,PremiereDate,UserData"
        )
        return json.optJSONArray("Items")?.toAlbumList().orEmpty()
    }

    /**
     * All albums, with play data filled in from their tracks.
     *
     * Verified against the server: an album's own UserData does NOT move
     * when one of its tracks is played (PlayCount stays 0, no
     * LastPlayedDate), so [getAlbums] alone cannot feed
     * [Scoring.rediscoverAlbums] or "recently played" ordering. This
     * variant merges each album's tracks (one extra list call) so
     * [Album.lastPlayedDate] is the latest track play and
     * [Album.playCount] is the sum of the tracks' play counts.
     * Prefer this whenever the UI needs play-based album shelves.
     */
    suspend fun getAlbumsWithTrackPlayData(): List<Album> {
        val albums = getAlbums()
        val tracksByAlbum = getTracks().groupBy { it.albumId }
        return albums.map { album ->
            val albumTracks = tracksByAlbum[album.id].orEmpty()
            if (albumTracks.isEmpty()) {
                album
            } else {
                album.copy(
                    // ISO-8601 UTC strings order chronologically, so the
                    // lexicographic max is the most recent play.
                    lastPlayedDate = albumTracks.mapNotNull { it.lastPlayedDate }.maxOrNull(),
                    playCount = albumTracks.sumOf { it.playCount }
                )
            }
        }
    }

    /** All audio tracks in the library. */
    suspend fun getTracks(): List<Track> {
        val json = getItems(
            "IncludeItemTypes=Audio&Recursive=true" +
                "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,PremiereDate"
        )
        return json.optJSONArray("Items")?.toTrackList().orEmpty()
    }

    /** Albums ordered by most recently played first. */
    suspend fun getRecentlyPlayedAlbums(limit: Int = 20): List<Album> {
        val json = getItems(
            "IncludeItemTypes=MusicAlbum&Recursive=true" +
                "&SortBy=DatePlayed&SortOrder=Descending&Limit=$limit" +
                "&Fields=DateCreated,PremiereDate,UserData"
        )
        return json.optJSONArray("Items")?.toAlbumList().orEmpty()
    }

    /** Albums ordered by most recently added (scanned) first. */
    suspend fun getRecentlyAddedAlbums(limit: Int = 30): List<Album> {
        val json = getItems(
            "IncludeItemTypes=MusicAlbum&Recursive=true" +
                "&SortBy=DateCreated&SortOrder=Descending&Limit=$limit" +
                "&Fields=DateCreated,PremiereDate,UserData"
        )
        return json.optJSONArray("Items")?.toAlbumList().orEmpty()
    }

    /** All playlists in the library. */
    suspend fun getPlaylists(): List<Playlist> {
        val json = getItems("IncludeItemTypes=Playlist&Recursive=true")
        val items = json.optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            Playlist(
                id = item.getString("Id"),
                name = item.optString("Name", ""),
                itemCount = item.optInt("ChildCount", 0)
            )
        }
    }

    // ---- Album / artist metadata (v1.0.3) ----

    /** Single-item reads are cached for the session: metadata rarely changes. */
    private val detailsCache = java.util.concurrent.ConcurrentHashMap<String, ItemDetails>()

    /**
     * Full metadata for one album or artist: overview/biography, genres,
     * label, year, images and backdrops. Cached per item id.
     */
    suspend fun getItemDetails(itemId: String): ItemDetails {
        detailsCache[itemId]?.let { return it }
        val uid = requireUserId()
        val json = getJson("/Users/$uid/Items/$itemId", "item details")
        return json.toItemDetails().also { detailsCache[itemId] = it }
    }

    /**
     * Artist metadata looked up by name (the library and search screens
     * only know artist names). Null when the server has no such artist.
     */
    suspend fun getArtistByName(name: String): ItemDetails? {
        val uid = requireUserId()
        val encoded = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val request = authedRequest("/Artists/$encoded?userId=$uid").get().build()
        val body = executeRaw(request, "artist") ?: return null
        val details = JSONObject(body).toItemDetails()
        detailsCache[details.id] = details
        return details
    }

    /** One album's tracks, in disc / track-number order. */
    suspend fun getAlbumTracks(albumId: String): List<Track> {
        val json = getItems(
            "ParentId=$albumId&IncludeItemTypes=Audio&Recursive=true" +
                "&SortBy=ParentIndexNumber,IndexNumber,SortName" +
                "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,PremiereDate"
        )
        return json.optJSONArray("Items")?.toTrackList().orEmpty()
    }

    /** Albums an artist appears on, newest first. */
    suspend fun getArtistAlbums(artistId: String): List<Album> {
        val json = getItems(
            "IncludeItemTypes=MusicAlbum&Recursive=true&ArtistIds=$artistId" +
                "&SortBy=ProductionYear,PremiereDate,SortName&SortOrder=Descending" +
                "&Fields=DateCreated,PremiereDate,UserData"
        )
        return json.optJSONArray("Items")?.toAlbumList().orEmpty()
    }

    /** All tracks by an artist (for Play / Shuffle on the artist page). */
    suspend fun getArtistTracks(artistId: String): List<Track> {
        val json = getItems(
            "IncludeItemTypes=Audio&Recursive=true&ArtistIds=$artistId" +
                "&SortBy=Album,ParentIndexNumber,IndexNumber" +
                "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,PremiereDate"
        )
        return json.optJSONArray("Items")?.toTrackList().orEmpty()
    }

    /**
     * Search the library. Only audio tracks are returned; the other hit
     * types (albums, artists) are filtered out. A hint carries no
     * play-count data, so [Track.playCount] is 0 and [Track.lastPlayedDate]
     * is null on search results.
     */
    suspend fun search(query: String): List<Track> {
        val uid = requireUserId()
        val encoded = URLEncoder.encode(query, "UTF-8")
        val json = getJson(
            "/Search/Hints?SearchTerm=$encoded&UserId=$uid&Limit=25",
            "search"
        )
        val hints = json.optJSONArray("SearchHints") ?: return emptyList()
        return (0 until hints.length()).mapNotNull { i ->
            val hint = hints.getJSONObject(i)
            if (hint.optString("Type") != "Audio") return@mapNotNull null
            Track(
                id = hint.getString("Id"),
                title = hint.optString("Name", ""),
                artists = hint.optJSONArray("Artists")?.toStringList().orEmpty(),
                album = hint.optString("Album", ""),
                albumId = hint.optStringOrNull("AlbumId"),
                albumArtist = hint.optString("AlbumArtist", ""),
                runTimeTicks = hint.optLong("RunTimeTicks", 0L),
                imageTag = hint.optStringOrNull("AlbumPrimaryImageTag")
            )
        }
    }

    // ---- Lyrics ----

    /**
     * Lyrics for a track.
     * - null: the server has no lyrics (HTTP 404).
     * - empty list: plain-text lyrics only (no usable timestamps).
     * - non-empty: synced lines, sorted by start time.
     */
    suspend fun getLyrics(trackId: String): List<LyricLine>? {
        val request = authedRequest("/Audio/$trackId/Lyrics").get().build()
        val body = executeRaw(request, "lyrics") ?: return null // 404 -> no lyrics
        val json = JSONObject(body)
        val raw = json.optJSONArray("Lyrics") ?: return emptyList()
        val lines = (0 until raw.length()).map { i ->
            val line = raw.getJSONObject(i)
            RawLyricLine(
                text = line.optString("Text", ""),
                startTicks = if (line.has("Start")) line.optLong("Start") else null
            )
        }
        return Lyrics.classify(lines)
    }

    // ---- Presence & messaging ----

    /**
     * Open the WebSocket to the server and report this client's playback
     * capabilities. Required for presence to work at all: without the
     * socket (and the capabilities report) the server does not mark this
     * session controllable, and a non-admin account sees nobody in
     * [getSessions]. Call once after login and keep the socket open while
     * the app runs; call [closePresence] when the app goes away.
     * Safe to call again — an already-open socket is left alone.
     */
    suspend fun connectPresence() {
        val base = baseUrl ?: throw IOException("not logged in")
        val tok = token ?: throw IOException("not logged in")
        if (presenceSocket != null) return
        val wsUrl = base.replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") +
            "/socket?ApiKey=$tok&deviceId=$deviceId"
        val request = Request.Builder().url(wsUrl).build()
        presenceSocket = http.newWebSocket(request, object : WebSocketListener() {})
        // Capabilities must be reported while the socket is open; the
        // query-param form of this call does not make the session
        // controllable, only the JSON /Full form does.
        val body = JSONObject().apply {
            put("PlayableMediaTypes", JSONArray().apply {
                put("Audio")
                put("Video")
            })
            put("SupportedCommands", JSONArray())
            put("SupportsMediaControl", true)
            put("SupportsPersistentIdentifier", true)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val capabilities = authedRequest("/Sessions/Capabilities/Full")
            .post(body).build()
        executeRaw(capabilities, "capabilities")
    }

    /** Close the presence WebSocket opened by [connectPresence]. */
    fun closePresence() {
        presenceSocket?.close(1000, "closing")
        presenceSocket = null
    }

    /**
     * Other users on the server who are listening to music right now.
     * Admins use the plain sessions call; non-admins use the
     * ControllableByUserId call, which only returns others once
     * [connectPresence] has run and the account has the server-side
     * "Allow remote control of other users" permission. Only sessions
     * with a track actually playing (not paused) are returned, grouped
     * by user. The local user is never included.
     */
    suspend fun getSessions(): List<FriendSession> {
        val uid = requireUserId()
        val path = if (isAdmin) "/Sessions" else "/Sessions?ControllableByUserId=$uid"
        // /Sessions returns a bare JSON array, not an envelope object.
        val request = authedRequest(path).get().build()
        val body = executeRaw(request, "sessions")
            ?: throw IOException("no response body for sessions")
        val array = JSONArray(body)
        val byUser = LinkedHashMap<String, MutableList<JSONObject>>()
        for (i in 0 until array.length()) {
            val session = array.getJSONObject(i)
            val sessionUserId = session.optString("UserId", "")
            if (sessionUserId.isEmpty() || sessionUserId == uid) continue
            val nowPlaying = session.optJSONObject("NowPlayingItem") ?: continue
            val mediaType = nowPlaying.optString("MediaType", nowPlaying.optString("Type", ""))
            if (mediaType != "Audio") continue
            val playState = session.optJSONObject("PlayState")
            if (playState?.optBoolean("IsPaused", false) == true) continue
            byUser.getOrPut(sessionUserId) { mutableListOf() }.add(session)
        }
        return byUser.values.map { userSessions ->
            val first = userSessions.first()
            val nowPlaying = first.getJSONObject("NowPlayingItem")
            val playState = first.optJSONObject("PlayState")
            FriendSession(
                userId = first.getString("UserId"),
                userName = first.optString("UserName", ""),
                trackTitle = nowPlaying.optString("Name", ""),
                artist = nowPlaying.optJSONArray("Artists")?.toStringList()
                    ?.joinToString(", ").orEmpty(),
                albumId = nowPlaying.optStringOrNull("AlbumId"),
                imageTag = nowPlaying.optStringOrNull("AlbumPrimaryImageTag"),
                positionTicks = playState?.optLong("PositionTicks", 0L) ?: 0L,
                runTimeTicks = nowPlaying.optLong("RunTimeTicks", 0L),
                sessionIds = userSessions.map { it.getString("Id") }
            )
        }
    }

    /**
     * Send a one-off message (an emoji) to another user's session.
     * The receiving client shows it as a toast labelled "Oniel Music".
     * Returns normally on HTTP 204; throws [IOException] otherwise
     * (403 = the sender lacks the remote-control permission).
     */
    suspend fun sendMessage(sessionId: String, text: String) {
        val body = JSONObject().apply {
            put("Text", text)
            put("Header", CLIENT_NAME)
            put("TimeoutMs", 5000)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = authedRequest("/Sessions/$sessionId/Message").post(body).build()
        executeRaw(request, "send message")
    }

    // ---- Play history ----

    /**
     * Mark a track played on a given date (ISO-8601, e.g.
     * "2026-01-15T12:00:00.0000000Z"). PlayCount increments by 1 per
     * call and LastPlayedDate is set to exactly the date sent, so
     * re-running the same call would double-count — callers must keep
     * their own checkpoint.
     */
    suspend fun markPlayed(trackId: String, datePlayedIso: String) {
        val uid = requireUserId()
        val encoded = URLEncoder.encode(datePlayedIso, "UTF-8")
        val request = authedRequest(
            "/UserPlayedItems/$trackId?userId=$uid&datePlayed=$encoded"
        ).post("".toRequestBody(null)).build()
        executeRaw(request, "mark played")
    }

    // ---- URLs for the player / image loader ----

    /**
     * Audio URL for a track: the universal endpoint, told every container
     * ExoPlayer plays natively, so Jellyfin sends the ORIGINAL file
     * (FLAC, M4A/AAC, OGG/Opus, WAV, MP3...) byte-for-byte. Direct
     * streams carry Content-Length and Accept-Ranges, which is what gives
     * the player a real duration and working seeks — and crossfade needs
     * the duration to know when a track is ending.
     *
     * v1.0.2 sent Container=mp3 only, which forced every non-MP3 file
     * through a live MP3 transcode: no length (duration unknown), no
     * seeking, and therefore no crossfade. Only formats outside the list
     * below are transcoded now (to MP3 over plain HTTP).
     *
     * The token travels as `api_key=` in the URL AND as the
     * `Authorization: MediaBrowser Token="..."` header (see
     * [authHeaderValue]): on Jellyfin 12.2.0 the universal endpoint
     * returns 401 with `api_key` alone, so the player must send the
     * header too — PlaybackService attaches it to ExoPlayer's HTTP
     * data source.
     */
    fun audioUrl(trackId: String): String {
        val base = requireBaseUrl()
        val uid = requireUserId()
        val tok = token ?: throw IOException("not logged in")
        return "$base/Audio/$trackId/universal?UserId=$uid&DeviceId=$deviceId" +
            "&Container=$DIRECT_CONTAINERS" +
            "&TranscodingContainer=mp3&TranscodingProtocol=http&AudioCodec=mp3" +
            "&MaxStreamingBitrate=140000000&api_key=$tok"
    }

    /**
     * Backdrop (wide background) image URL, or null when the item has none.
     * [index] picks one of several backdrops.
     */
    fun backdropUrl(itemId: String, imageTag: String?, width: Int = 1280, index: Int = 0): String? {
        if (imageTag.isNullOrEmpty()) return null
        val base = requireBaseUrl()
        val tok = token ?: ""
        return "$base/Items/$itemId/Images/Backdrop/$index?fillWidth=$width&quality=80&tag=$imageTag&api_key=$tok"
    }

    /** Primary image URL for an item (album art). [width] is the resized width in px. */
    fun imageUrl(itemId: String, imageTag: String?, width: Int = 300): String {
        val base = requireBaseUrl()
        val tok = token ?: ""
        val tag = if (!imageTag.isNullOrEmpty()) "&tag=$imageTag" else ""
        return "$base/Items/$itemId/Images/Primary?fillWidth=$width&quality=80&api_key=$tok$tag"
    }

    // ---- Playback reporting ----

    /**
     * Tell the server this device started playing a track, so other
     * users can see it in presence. Call on track start and on resume.
     * A fresh play-session id is generated per track and reused by
     * [reportStopped] so the server can match the two reports.
     */
    suspend fun reportPlaying(trackId: String, positionTicks: Long) {
        if (trackId != currentPlayTrackId) {
            currentPlaySessionId = UUID.randomUUID().toString()
            currentPlayTrackId = trackId
        }
        val body = JSONObject().apply {
            put("ItemId", trackId)
            put("PositionTicks", positionTicks)
            put("IsPaused", false)
            put("PlayMethod", "DirectStream")
            put("PlaySessionId", currentPlaySessionId)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = authedRequest("/Sessions/Playing").post(body).build()
        executeRaw(request, "report playing")
    }

    /**
     * Tell the server playback stopped. [playedToCompletion] is reported
     * with the stop event. Call on pause-to-stop, track end, and when
     * the player is released. Sends the same play-session id as the
     * matching [reportPlaying] call.
     */
    suspend fun reportStopped(trackId: String, positionTicks: Long, playedToCompletion: Boolean) {
        val body = JSONObject().apply {
            put("ItemId", trackId)
            put("PositionTicks", positionTicks)
            put("Failed", false)
            put("PlayedToCompletion", playedToCompletion)
            currentPlaySessionId?.let { put("PlaySessionId", it) }
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = authedRequest("/Sessions/Playing/Stopped").post(body).build()
        executeRaw(request, "report stopped")
        if (trackId == currentPlayTrackId) {
            currentPlaySessionId = null
            currentPlayTrackId = null
        }
    }

    // ---- Internals ----

    private fun normalizeBaseUrl(serverUrl: String): String {
        var url = serverUrl.trim().trimEnd('/')
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://$url"
        }
        return url
    }

    private fun loginAuthHeader(): String =
        "MediaBrowser Client=\"$CLIENT_NAME\", Device=\"$CLIENT_NAME\", " +
            "DeviceId=\"$deviceId\", Version=\"$CLIENT_VERSION\""

    /**
     * The full `Authorization` header value for this login, as sent on
     * every API call. PlaybackService sends this exact value on
     * ExoPlayer's audio requests (the universal stream endpoint 401s
     * without it). Empty token when not logged in.
     */
    fun authHeaderValue(): String = authHeader()

    private fun authHeader(): String =
        "MediaBrowser Token=\"${token ?: ""}\", Client=\"$CLIENT_NAME\", " +
            "Device=\"$CLIENT_NAME\", DeviceId=\"$deviceId\", Version=\"$CLIENT_VERSION\""

    private fun authedRequest(path: String): Request.Builder {
        val base = requireBaseUrl()
        return Request.Builder()
            .url("$base$path")
            .header("Authorization", authHeader())
    }

    private fun requireBaseUrl(): String = baseUrl ?: throw IOException("not logged in")

    private fun requireUserId(): String = userId ?: throw IOException("not logged in")

    private suspend fun getItems(query: String): JSONObject {
        val uid = requireUserId()
        return getJson("/Users/$uid/Items?$query", "library")
    }

    private suspend fun getJson(path: String, what: String): JSONObject {
        val request = authedRequest(path).get().build()
        val body = executeRaw(request, what)
            ?: throw IOException("no response body for $what")
        return JSONObject(body)
    }

    private suspend fun executeJson(request: Request, what: String): JSONObject {
        val body = executeRaw(request, what) ?: throw IOException("no response body for $what")
        return JSONObject(body)
    }

    /**
     * Execute a request, returning the response body as a string, or
     * null when the server answered 404 (used by [getLyrics]: "no lyrics"
     * is a normal outcome, not an error). Any other non-2xx status throws.
     */
    private suspend fun executeRaw(request: Request, what: String): String? =
        withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { response ->
                if (response.code == 404) return@withContext null
                if (!response.isSuccessful) {
                    throw IOException("$what failed: HTTP ${response.code}")
                }
                response.body?.string()
            }
        }

    private fun JSONArray.toAlbumList(): List<Album> =
        (0 until length()).map { i ->
            val item = getJSONObject(i)
            val userData = item.optJSONObject("UserData")
            Album(
                id = item.getString("Id"),
                name = item.optString("Name", ""),
                artist = item.optString("AlbumArtist", "")
                    .ifEmpty { item.optJSONArray("Artists")?.optString(0, "").orEmpty() },
                year = if (item.has("ProductionYear")) item.optInt("ProductionYear") else null,
                premiereDate = item.optStringOrNull("PremiereDate"),
                dateCreated = item.optStringOrNull("DateCreated"),
                imageTag = item.optJSONObject("ImageTags")?.optStringOrNull("Primary"),
                childCount = item.optInt("ChildCount", 0),
                lastPlayedDate = userData?.optStringOrNull("LastPlayedDate"),
                playCount = userData?.optInt("PlayCount", 0) ?: 0,
                artistId = item.firstIdIn("AlbumArtists") ?: item.firstIdIn("ArtistItems")
            )
        }

    private fun JSONArray.toTrackList(): List<Track> =
        (0 until length()).map { i ->
            val item = getJSONObject(i)
            val userData = item.optJSONObject("UserData")
            Track(
                id = item.getString("Id"),
                title = item.optString("Name", ""),
                artists = item.optJSONArray("Artists")?.toStringList().orEmpty(),
                album = item.optString("Album", ""),
                albumId = item.optStringOrNull("AlbumId"),
                albumArtist = item.optString("AlbumArtist", ""),
                runTimeTicks = item.optLong("RunTimeTicks", 0L),
                imageTag = item.optStringOrNull("AlbumPrimaryImageTag"),
                playCount = userData?.optInt("PlayCount", 0) ?: 0,
                lastPlayedDate = userData?.optStringOrNull("LastPlayedDate"),
                isFavorite = userData?.optBoolean("IsFavorite", false) ?: false,
                artistId = item.firstIdIn("ArtistItems") ?: item.firstIdIn("AlbumArtists"),
                indexNumber = if (item.has("IndexNumber") && !item.isNull("IndexNumber"))
                    item.optInt("IndexNumber") else null,
                discNumber = if (item.has("ParentIndexNumber") && !item.isNull("ParentIndexNumber"))
                    item.optInt("ParentIndexNumber") else null
            )
        }

    private fun JSONObject.toItemDetails(): ItemDetails {
        val userData = optJSONObject("UserData")
        return ItemDetails(
            id = getString("Id"),
            name = optString("Name", ""),
            type = optString("Type", ""),
            overview = optStringOrNull("Overview")?.let(::cleanOverview)?.ifBlank { null },
            genres = optJSONArray("Genres")?.toStringList().orEmpty(),
            studios = optJSONArray("Studios")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optStringOrNull("Name") }
            }.orEmpty(),
            year = if (has("ProductionYear") && !isNull("ProductionYear")) optInt("ProductionYear") else null,
            runTimeTicks = optLong("RunTimeTicks", 0L),
            childCount = optInt("ChildCount", 0),
            primaryImageTag = optJSONObject("ImageTags")?.optStringOrNull("Primary"),
            backdropTags = optJSONArray("BackdropImageTags")?.toStringList().orEmpty(),
            parentBackdropItemId = optStringOrNull("ParentBackdropItemId"),
            parentBackdropTags = optJSONArray("ParentBackdropImageTags")?.toStringList().orEmpty(),
            artistName = optStringOrNull("AlbumArtist"),
            artistId = firstIdIn("AlbumArtists") ?: firstIdIn("ArtistItems"),
            isFavorite = userData?.optBoolean("IsFavorite", false) ?: false
        )
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { i -> optStringOrNull(i) }
}

/** Id of the first {Name, Id} entry in an array field, or null. */
private fun JSONObject.firstIdIn(key: String): String? =
    optJSONArray(key)?.optJSONObject(0)?.optStringOrNull("Id")

/**
 * Jellyfin overviews often hold HTML from metadata providers (<br>, <a>,
 * entities). Turn that into readable plain text.
 */
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

/** Null-safe string read: null when the key is absent or JSON null (avoids passing a null fallback). */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

private fun JSONArray.optStringOrNull(index: Int): String? =
    if (!isNull(index)) optString(index) else null
