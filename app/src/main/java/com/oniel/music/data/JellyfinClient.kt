package com.oniel.music.data

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
        const val CLIENT_NAME = "SpinUs"
        /** Earlier name: friends on older versions still count as the same app. */
        val APP_CLIENT_NAMES = setOf(CLIENT_NAME, "Oniel Music")
        /** Session-message headers for stats sharing between Oniel Music apps. */
        const val STATS_REQUEST = "onielmusic:stats-request"
        const val STATS_REPLY = "onielmusic:stats-reply"
        const val CLIENT_VERSION = "1.6.0"
        /** Track ids per playlist request (keeps the URL short). */
        private const val PLAYLIST_CHUNK = 100
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
                "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,PremiereDate,Genres,ProductionYear"
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

    /** Albums Jellyfin considers similar to [albumId] (genres, artists, tags). */
    suspend fun getSimilarAlbums(albumId: String, limit: Int = 8): List<Album> {
        val uid = requireUserId()
        val json = getJson(
            "/Items/$albumId/Similar?userId=$uid&limit=$limit&fields=DateCreated,PremiereDate,UserData",
            "similar albums"
        )
        val items = json.optJSONArray("Items") ?: return emptyList()
        val albumsOnly = JSONArray()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("Type") == "MusicAlbum") albumsOnly.put(item)
        }
        return albumsOnly.toAlbumList()
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

    // ---- Search (v1.3.5: one call per kind, so results can show as they arrive) ----

    private val searchTrackFields =
        "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,PremiereDate&EnableUserData=false"

    private fun enc(q: String) = URLEncoder.encode(q, "UTF-8")

    suspend fun searchArtists(query: String): List<ArtistHit> {
        val uid = requireUserId()
        val json = getJson(
            "/Artists?SearchTerm=${enc(query)}&UserId=$uid&Recursive=true&Limit=15&EnableUserData=false",
            "search artists"
        )
        val items = json.optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("Name", "")
            if (name.isBlank()) null
            else ArtistHit(
                id = item.getString("Id"),
                name = name,
                imageTag = item.optJSONObject("ImageTags")?.optStringOrNull("Primary")
            )
        }
    }

    suspend fun searchAlbums(query: String): List<Album> =
        getItems(
            "IncludeItemTypes=MusicAlbum&Recursive=true&SearchTerm=${enc(query)}&Limit=20" +
                "&Fields=PremiereDate&EnableUserData=false"
        ).optJSONArray("Items")?.toAlbumList().orEmpty()

    suspend fun searchSongs(query: String): List<Track> =
        getItems("IncludeItemTypes=Audio&Recursive=true&SearchTerm=${enc(query)}&Limit=40$searchTrackFields")
            .optJSONArray("Items")?.toTrackList().orEmpty()

    /** Most-played songs of these artists (so an artist search lists songs too). */
    suspend fun songsByArtists(artistIds: List<String>, limit: Int = 20): List<Track> {
        if (artistIds.isEmpty()) return emptyList()
        return getItems(
            "IncludeItemTypes=Audio&Recursive=true&ArtistIds=${artistIds.joinToString(",")}" +
                "&SortBy=PlayCount,SortName&SortOrder=Descending&Limit=$limit$searchTrackFields"
        ).optJSONArray("Items")?.toTrackList().orEmpty()
    }

    suspend fun searchPlaylists(query: String): List<Playlist> {
        val items = getItems("IncludeItemTypes=Playlist&Recursive=true&SearchTerm=${enc(query)}&Limit=15")
            .optJSONArray("Items") ?: return emptyList()
        return (0 until items.length()).map { i ->
            val item = items.getJSONObject(i)
            Playlist(
                id = item.getString("Id"),
                name = item.optString("Name", ""),
                itemCount = item.optInt("ChildCount", 0)
            )
        }
    }

    /**
     * Small read of every song (artist, title, album, length, art) for
     * matching chart songs to the library — no user data, no extras.
     */
    suspend fun getTrackIndex(): List<Track> =
        getItems(
            "IncludeItemTypes=Audio&Recursive=true" +
                "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks&EnableUserData=false"
        ).optJSONArray("Items")?.toTrackList().orEmpty()

    /**
     * Every artist that has a photo, keyed by lower-case name (for the
     * Library's artist list). Names without a photo are left out.
     */
    suspend fun getArtistPictures(): Map<String, ArtistHit> {
        val uid = requireUserId()
        val json = getJson(
            "/Artists?userId=$uid&Recursive=true&EnableUserData=false" +
                "&EnableImageTypes=Primary&ImageTypeLimit=1",
            "artist pictures"
        )
        val items = json.optJSONArray("Items") ?: return emptyMap()
        val map = HashMap<String, ArtistHit>()
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val tag = item.optJSONObject("ImageTags")?.optStringOrNull("Primary") ?: continue
            val name = item.optString("Name", "")
            if (name.isBlank()) continue
            map.putIfAbsent(name.lowercase(), ArtistHit(item.getString("Id"), name, tag))
        }
        return map
    }

    /** Favorite flag changed in this session, if any (lists may be older). */
    fun favoriteOverride(itemId: String): Boolean? = favoriteOverrides[itemId]

    /**
     * Jellyfin's instant mix for an item (artist, album or song): tracks
     * the server finds similar (shared genres). Used for stations.
     */
    suspend fun getInstantMix(itemId: String, limit: Int = 100): List<Track> {
        val uid = requireUserId()
        val json = getJson(
            "/Items/$itemId/InstantMix?userId=$uid&Limit=$limit" +
                "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,Genres,ProductionYear",
            "instant mix"
        )
        return json.optJSONArray("Items")?.toTrackList().orEmpty()
    }

    /** A playlist's songs, in playlist order. */
    suspend fun getPlaylistTracks(playlistId: String): List<Track> {
        val uid = requireUserId()
        val json = getJson(
            "/Playlists/$playlistId/Items?userId=$uid" +
                "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,PremiereDate",
            "playlist"
        )
        return json.optJSONArray("Items")?.toTrackList().orEmpty()
    }

    // ---- Genres (v1.1.0) ----

    /** Every music genre in the library, A to Z. */
    suspend fun getMusicGenres(): List<Genre> {
        genresCache?.let { return it }
        val uid = requireUserId()
        val json = getJson(
            "/MusicGenres?userId=$uid&Recursive=true&SortBy=SortName&SortOrder=Ascending",
            "genres"
        )
        val items = json.optJSONArray("Items") ?: return emptyList()
        val list = (0 until items.length()).mapNotNull { i ->
            val item = items.optJSONObject(i) ?: return@mapNotNull null
            val name = item.optString("Name", "")
            if (name.isBlank()) return@mapNotNull null
            Genre(
                id = item.getString("Id"),
                name = name,
                imageTag = item.optJSONObject("ImageTags")?.optStringOrNull("Primary")
            )
        }
        genresCache = list
        return list
    }

    /** Library genres rarely change: read once per session. */
    @Volatile private var genresCache: List<Genre>? = null

    /** Albums tagged with one genre, A to Z. */
    suspend fun getGenreAlbums(genreId: String): List<Album> {
        val json = getItems(
            "IncludeItemTypes=MusicAlbum&Recursive=true&GenreIds=$genreId" +
                "&SortBy=SortName&SortOrder=Ascending" +
                "&Fields=DateCreated,PremiereDate,UserData"
        )
        return json.optJSONArray("Items")?.toAlbumList().orEmpty()
    }

    /** Songs tagged with one genre (for Play / Shuffle on the genre page). */
    suspend fun getGenreTracks(genreId: String): List<Track> {
        val json = getItems(
            "IncludeItemTypes=Audio&Recursive=true&GenreIds=$genreId" +
                "&SortBy=Album,ParentIndexNumber,IndexNumber" +
                "&Fields=Album,AlbumArtist,Artists,AlbumId,RunTimeTicks,UserData,PremiereDate"
        )
        return json.optJSONArray("Items")?.toTrackList().orEmpty()
    }

    // ---- Favorites (v1.1.0) ----

    /** Favorite changes made in this session, so every screen agrees right away. */
    private val favoriteOverrides = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * Is [itemId] a favorite? Session changes win, then [known] (from a
     * list read), else the server is asked.
     */
    suspend fun isFavorite(itemId: String, known: Boolean? = null): Boolean {
        favoriteOverrides[itemId]?.let { return it }
        if (known != null) return known
        val uid = requireUserId()
        val json = getJson("/Users/$uid/Items/$itemId", "favorite state")
        return json.optJSONObject("UserData")?.optBoolean("IsFavorite", false) ?: false
    }

    /**
     * Mark or unmark a song, album or artist as a favorite. Uses the
     * current endpoint first and the older per-user one when the server
     * does not know it (404).
     */
    suspend fun setFavorite(itemId: String, favorite: Boolean) {
        val uid = requireUserId()
        fun build(path: String): Request {
            val b = authedRequest(path)
            return if (favorite) b.post("".toRequestBody(null)).build() else b.delete().build()
        }
        val result = executeRaw(build("/UserFavoriteItems/$itemId?userId=$uid"), "favorite")
        if (result == null) {
            executeRaw(build("/Users/$uid/FavoriteItems/$itemId"), "favorite")
                ?: throw IOException("favorite failed: not supported by this server")
        }
        favoriteOverrides[itemId] = favorite
        detailsCache.remove(itemId)
    }

    // ---- Playlists (v1.1.0) ----

    /** Create an audio playlist holding [trackIds]. Returns the new playlist id. */
    suspend fun createPlaylist(name: String, trackIds: List<String>): String {
        val uid = requireUserId()
        val body = JSONObject().apply {
            put("Name", name)
            put("Ids", JSONArray(trackIds.take(PLAYLIST_CHUNK)))
            put("UserId", uid)
            put("MediaType", "Audio")
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = authedRequest("/Playlists").post(body).build()
        val id = executeJson(request, "create playlist").optString("Id", "")
        if (id.isBlank()) throw IOException("create playlist failed: no id returned")
        // Long lists (a whole artist) go in after, in URL-sized chunks.
        if (trackIds.size > PLAYLIST_CHUNK) addToPlaylist(id, trackIds.drop(PLAYLIST_CHUNK))
        return id
    }

    /** Append [trackIds] to an existing playlist. */
    suspend fun addToPlaylist(playlistId: String, trackIds: List<String>) {
        val uid = requireUserId()
        trackIds.chunked(PLAYLIST_CHUNK).forEach { chunk ->
            val request = authedRequest(
                "/Playlists/$playlistId/Items?ids=${chunk.joinToString(",")}&userId=$uid"
            ).post("".toRequestBody(null)).build()
            executeRaw(request, "add to playlist")
                ?: throw IOException("add to playlist failed: playlist not found")
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
        presenceSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                handleSocketMessage(webSocket, text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (presenceSocket === webSocket) presenceSocket = null
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (presenceSocket === webSocket) presenceSocket = null
            }
        })
        // Capabilities must be reported while the socket is open; the
        // query-param form of this call does not make the session
        // controllable, only the JSON /Full form does.
        val body = JSONObject().apply {
            put("PlayableMediaTypes", JSONArray().apply {
                put("Audio")
                put("Video")
            })
            // DisplayMessage carries friends' stats requests / replies.
            put("SupportedCommands", JSONArray().apply { put("DisplayMessage") })
            put("SupportsMediaControl", true)
            put("SupportsPersistentIdentifier", true)
        }.toString().toRequestBody(JSON_MEDIA_TYPE)
        val capabilities = authedRequest("/Sessions/Capabilities/Full")
            .post(body).build()
        executeRaw(capabilities, "capabilities")
    }

    // ---- Stats sharing with friends (v1.4.0) ----
    //
    // Rides on session messages: a friend's app sends us a DisplayMessage
    // with header STATS_REQUEST and {id, session}; we answer the asking
    // session with header STATS_REPLY and {id, data: summary JSON}. Only
    // works while both apps are open (the Friends list shows only people
    // listening right now anyway).

    /** Builds this user's stats as JSON for a friend; null = not sharing. */
    @Volatile var statsResponder: (suspend () -> String?)? = null

    private val socketScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )
    private val pendingStats =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<String?>>()
    @Volatile private var keepAliveJob: kotlinx.coroutines.Job? = null

    private fun handleSocketMessage(socket: WebSocket, text: String) {
        try {
            val msg = JSONObject(text)
            when (msg.optString("MessageType")) {
                // The server drops sockets that stay quiet: answer its
                // keep-alive request on the interval it asks for.
                "ForceKeepAlive" -> {
                    val seconds = msg.optInt("Data", 60).coerceIn(10, 300)
                    keepAliveJob?.cancel()
                    keepAliveJob = socketScope.launch {
                        while (true) {
                            socket.send("{\"MessageType\":\"KeepAlive\"}")
                            kotlinx.coroutines.delay(seconds * 1000L / 2)
                        }
                    }
                }
                "GeneralCommand" -> {
                    val data = msg.optJSONObject("Data") ?: return
                    if (data.optString("Name") != "DisplayMessage") return
                    val args = data.optJSONObject("Arguments") ?: return
                    val header = args.optString("Header")
                    val body = args.optString("Text")
                    when (header) {
                        STATS_REQUEST -> answerStatsRequest(body)
                        STATS_REPLY -> {
                            val reply = JSONObject(body)
                            pendingStats.remove(reply.optString("id"))?.complete(reply.optString("data"))
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

    /** This app's own session id (for friends to answer to). */
    private suspend fun ownSessionId(): String? {
        val body = executeRaw(
            authedRequest("/Sessions?DeviceId=${URLEncoder.encode(deviceId, "UTF-8")}").get().build(),
            "own session"
        ) ?: return null
        val array = JSONArray(body)
        return if (array.length() > 0) array.getJSONObject(0).optString("Id").ifBlank { null } else null
    }

    /**
     * Ask a friend's app for their listening stats. Returns the summary
     * JSON, or null if they don't answer within [timeoutMs] (app closed,
     * sharing off, or no permission to message them).
     */
    suspend fun requestFriendStats(friend: FriendSession, timeoutMs: Long = 8000): String? {
        if (presenceSocket == null) connectPresence()
        val mySession = ownSessionId() ?: return null
        val id = UUID.randomUUID().toString()
        val waiter = kotlinx.coroutines.CompletableDeferred<String?>()
        pendingStats[id] = waiter
        try {
            val req = JSONObject().apply {
                put("id", id)
                put("session", mySession)
            }.toString()
            // Only Oniel Music sessions: other apps would show the raw text.
            if (friend.onielSessionIds.isEmpty()) return null
            friend.onielSessionIds.forEach { sid ->
                try { sendMessage(sid, req, header = STATS_REQUEST, timeoutMs = 1000) } catch (e: Exception) { }
            }
            return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { waiter.await() }
        } finally {
            pendingStats.remove(id)
        }
    }

    /** Close the presence WebSocket opened by [connectPresence]. */
    fun closePresence() {
        keepAliveJob?.cancel()
        keepAliveJob = null
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
                sessionIds = userSessions.map { it.getString("Id") },
                onielSessionIds = userSessions
                    .filter { it.optString("Client") in APP_CLIENT_NAMES }
                    .map { it.getString("Id") }
            )
        }
    }

    /**
     * Send a one-off message (an emoji) to another user's session.
     * The receiving client shows it as a toast labelled "Oniel Music".
     * Returns normally on HTTP 204; throws [IOException] otherwise
     * (403 = the sender lacks the remote-control permission).
     */
    suspend fun sendMessage(
        sessionId: String,
        text: String,
        header: String = CLIENT_NAME,
        timeoutMs: Int = 5000
    ) {
        val body = JSONObject().apply {
            put("Text", text)
            put("Header", header)
            put("TimeoutMs", timeoutMs)
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

    // ---- Per-user app storage (listening-history sync) ----

    /**
     * This app's per-user storage on the server: Jellyfin "display
     * preferences" for [prefsId] and [client]. Jellyfin returns a default
     * (empty CustomPrefs) the first time. See HistorySync.
     */
    suspend fun getAppPrefs(prefsId: String, client: String): JSONObject {
        val uid = requireUserId()
        return getJson("/DisplayPreferences/$prefsId?userId=$uid&client=$client", "history (read)")
    }

    /** Save the whole display-preferences object back (CustomPrefs replaced as a set). */
    suspend fun saveAppPrefs(prefsId: String, client: String, dto: JSONObject) {
        val uid = requireUserId()
        val request = authedRequest("/DisplayPreferences/$prefsId?userId=$uid&client=$client")
            .post(dto.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        executeRaw(request, "history (save)")
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

    /**
     * Artwork for a track: its album's cover, or — for a loose song with
     * no album — the artist's picture.
     */
    fun trackArtUrl(track: Track, width: Int = 300): String {
        val albumId = track.albumId
        val artistId = track.artistId
        // Album has a cover → use it; no album or no cover → artist picture.
        if (albumId != null && track.imageTag != null) return imageUrl(albumId, track.imageTag, width)
        if (artistId != null) return imageUrl(artistId, null, width)
        if (albumId != null) return imageUrl(albumId, null, width)
        return imageUrl(track.id, track.imageTag, width)
    }

    /** An album's cover, or the album artist's picture when it has none. */
    fun albumArtUrl(album: Album, width: Int = 300): String {
        val artistId = album.artistId
        return if (album.imageTag == null && artistId != null) imageUrl(artistId, null, width)
        else imageUrl(album.id, album.imageTag, width)
    }

    /** Second try for an album cover that fails to load: the artist's picture. */
    fun albumArtFallback(album: Album, width: Int = 300): String? =
        album.artistId?.let { imageUrl(it, null, width) }

    /** An artist's own picture (404s, and so falls back, when there is none). */
    fun artistImageUrl(artistId: String, width: Int = 300): String = imageUrl(artistId, null, width)

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
                    item.optInt("ParentIndexNumber") else null,
                genres = item.optJSONArray("Genres")?.toStringList().orEmpty(),
                year = if (item.has("ProductionYear") && !item.isNull("ProductionYear"))
                    item.optInt("ProductionYear") else null
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
