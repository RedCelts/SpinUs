package com.oniel.music.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Deezer's public catalog API (no account, no key): used for
 * "Recommended additions" (music NOT in the Jellyfin library) and for the
 * main genres + their top songs in Search's "Browse all".
 * https://api.deezer.com — search, related artists, top tracks, albums.
 * Deezer allows ~50 requests / 5 s; calls here are sequential and spaced.
 */
class DeezerClient(private val http: OkHttpClient) {

    private suspend fun get(path: String): JSONObject = withContext(Dispatchers.IO) {
        delay(SPACING_MS)
        val request = Request.Builder().url("$BASE$path").get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Deezer HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "{}")
            // Deezer reports errors inside a 200 response.
            json.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message")}") }
            json
        }
    }

    /** The Deezer artist with exactly this name (accents/case ignored), or null. */
    suspend fun findArtist(name: String): DzArtist? {
        val q = URLEncoder.encode(name, "UTF-8")
        val data = get("/search/artist?q=$q&limit=10").optJSONArray("data") ?: return null
        val key = matchKey(name)
        return data.objects().map { it.toArtist() }.firstOrNull { matchKey(it.name) == key }
    }

    suspend fun relatedArtists(artistId: Long, limit: Int = 20): List<DzArtist> =
        get("/artist/$artistId/related?limit=$limit").optJSONArray("data")
            ?.objects()?.map { it.toArtist() }.orEmpty()

    suspend fun topTracks(artistId: Long, limit: Int = 5): List<DzTrack> =
        get("/artist/$artistId/top?limit=$limit").optJSONArray("data")
            ?.objects()?.map { it.toTrack() }.orEmpty()

    suspend fun albums(artistId: Long, artistName: String, limit: Int = 50): List<DzAlbum> =
        get("/artist/$artistId/albums?limit=$limit").optJSONArray("data")
            ?.objects()?.map { a ->
                DzAlbum(
                    id = a.optLong("id"),
                    title = a.optString("title"),
                    artist = artistName,
                    coverUrl = a.optString("cover_medium").ifBlank { null },
                    link = a.optString("link").ifBlank { null },
                    releaseDate = a.optString("release_date").ifBlank { null },
                    recordType = a.optString("record_type").ifBlank { null }
                )
            }.orEmpty()

    /**
     * A fresh 30-second preview URL (Deezer's preview links expire, so
     * they are fetched when tapped, not cached). For an album: its first
     * track's preview.
     */
    suspend fun previewUrl(addition: Addition): String? = when (addition.kind) {
        Addition.Kind.SONG -> get("/track/${addition.deezerId}").optString("preview").ifBlank { null }
        Addition.Kind.ALBUM -> get("/album/${addition.deezerId}/tracks?limit=1").optJSONArray("data")
            ?.optJSONObject(0)?.optString("preview")?.ifBlank { null }
    }

    /**
     * Deezer's main genres (about two dozen), without "All". Kept for the
     * app's lifetime: they almost never change.
     */
    suspend fun mainGenres(): List<DzGenre> {
        cachedGenres?.let { return it }
        val list = get("/genre").optJSONArray("data")?.objects()?.mapNotNull { g ->
            val id = g.optLong("id")
            val name = g.optString("name")
            if (id == 0L || name.isBlank()) null
            else DzGenre(id, name, g.optString("picture_medium").ifBlank { null })
        }.orEmpty()
        if (list.isNotEmpty()) cachedGenres = list
        return list
    }

    /** The genre's chart: its top songs right now, most popular first. */
    suspend fun genreTopTracks(genreId: Long, limit: Int = 50): List<DzTrack> =
        get("/chart/$genreId/tracks?limit=$limit").optJSONArray("data")
            ?.objects()?.map { it.toTrack() }.orEmpty()

    /**
     * Top songs for a style Deezer has no genre for (Dancehall, Bachata,
     * Merengue…): its biggest "<style> hits" playlist, songs ordered by
     * Deezer's current popularity.
     */
    suspend fun styleTopTracks(style: String, limit: Int = 50): List<DzTrack> {
        val q = URLEncoder.encode("$style hits", "UTF-8")
        val playlists = get("/search/playlist?q=$q&limit=10").optJSONArray("data")?.objects().orEmpty()
        val best = playlists
            .filter { it.optInt("nb_tracks") >= 20 }
            .firstOrNull { it.optString("title").contains(style, ignoreCase = true) }
            ?: playlists.firstOrNull()
            ?: return emptyList()
        return get("/playlist/${best.optLong("id")}/tracks?limit=100").optJSONArray("data")
            ?.objects()?.map { it.toTrack() }.orEmpty()
            .distinctBy { it.id }
            .sortedByDescending { it.rank }
            .take(limit)
    }

    /** A fresh preview URL for one Deezer track (old ones expire). */
    suspend fun trackPreview(trackId: Long): String? =
        get("/track/$trackId").optString("preview").ifBlank { null }

    private fun JSONObject.toArtist() = DzArtist(
        id = optLong("id"),
        name = optString("name"),
        pictureUrl = optString("picture_medium").ifBlank { null },
        fans = optLong("nb_fan")
    )

    private fun JSONObject.toTrack() = DzTrack(
        id = optLong("id"),
        title = optString("title"),
        artist = optJSONObject("artist")?.optString("name").orEmpty(),
        album = optJSONObject("album")?.optString("title").orEmpty(),
        coverUrl = optJSONObject("album")?.optString("cover_medium")?.ifBlank { null },
        link = optString("link").ifBlank { null },
        durationSec = optInt("duration"),
        previewUrl = optString("preview").ifBlank { null },
        bigCoverUrl = optJSONObject("album")?.optString("cover_xl")?.ifBlank { null },
        artistPictureUrl = optJSONObject("artist")?.let {
            it.optString("picture_xl").ifBlank { it.optString("picture_big") }
                // No photo: Deezer returns a placeholder with an empty hash.
                .takeUnless { url -> url.isBlank() || url.contains("/artist//") }
        },
        rank = optInt("rank")
    )

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private const val BASE = "https://api.deezer.com"
        private const val SPACING_MS = 120L

        @Volatile
        private var cachedGenres: List<DzGenre>? = null
    }
}
