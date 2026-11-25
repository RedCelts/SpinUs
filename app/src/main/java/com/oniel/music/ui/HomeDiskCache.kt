package com.oniel.music.ui

import android.content.Context
import com.oniel.music.data.Album
import com.oniel.music.data.ArtistHit
import com.oniel.music.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Home's last load saved on the phone, so a fresh app start shows the
 * shelves immediately (then refreshes from the server in the background).
 * One JSON file in the app's private storage; deleted on log out.
 */
object HomeDiskCache {
    private const val FILE = "home_cache.json"
    private const val VERSION = 1

    private fun file(context: Context) = File(context.filesDir, FILE)

    suspend fun load(context: Context): HomeCache.Data? = withContext(Dispatchers.IO) {
        try {
            val f = file(context)
            if (!f.exists()) return@withContext null
            val o = JSONObject(f.readText())
            if (o.optInt("v") != VERSION) return@withContext null
            HomeCache.Data(
                tiles = o.tracks("tiles"),
                newReleases = o.albums("newReleases"),
                rediscover = o.albums("rediscover"),
                recentlyPlayed = o.albums("recentlyPlayed"),
                recentlyAdded = o.albums("recentlyAdded"),
                topArtists = o.optJSONArray("topArtists")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        val s = arr.getJSONObject(i)
                        ArtistStation(
                            name = s.optString("name"),
                            plays = s.optInt("plays"),
                            coverAlbum = s.optJSONObject("cover")?.toAlbum()
                        )
                    }
                }.orEmpty(),
                allTracks = o.tracks("allTracks"),
                libraryAlbums = o.albums("libraryAlbums")
            )
        } catch (e: Exception) {
            null // Corrupt or old file: just load from the server.
        }
    }

    suspend fun save(context: Context, d: HomeCache.Data) = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject().apply {
                put("v", VERSION)
                put("tiles", d.tiles.toJsonTracks())
                put("newReleases", d.newReleases.toJsonAlbums())
                put("rediscover", d.rediscover.toJsonAlbums())
                put("recentlyPlayed", d.recentlyPlayed.toJsonAlbums())
                put("recentlyAdded", d.recentlyAdded.toJsonAlbums())
                put("topArtists", JSONArray().apply {
                    d.topArtists.forEach { s ->
                        put(JSONObject().apply {
                            put("name", s.name)
                            put("plays", s.plays)
                            s.coverAlbum?.let { put("cover", it.toJson()) }
                        })
                    }
                })
                put("allTracks", d.allTracks.toJsonTracks())
                put("libraryAlbums", d.libraryAlbums.toJsonAlbums())
            }
            // Write then rename, so a crash mid-write never leaves half a file.
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file(context))
        } catch (e: Exception) {
            // Not saved: next start just loads from the server.
        }
    }

    fun delete(context: Context) {
        try { file(context).delete() } catch (e: Exception) { }
        try { File(context.filesDir, PICS_FILE).delete() } catch (e: Exception) { }
    }

    // ---- Artist photos for the Library list (name → id + image tag) ----

    private const val PICS_FILE = "artist_pics.json"

    suspend fun loadArtistPictures(context: Context): Map<String, ArtistHit> = withContext(Dispatchers.IO) {
        try {
            val f = File(context.filesDir, PICS_FILE)
            if (!f.exists()) return@withContext emptyMap()
            val o = JSONObject(f.readText())
            o.keys().asSequence().associateWith { key ->
                val a = o.getJSONArray(key)
                ArtistHit(a.getString(0), a.getString(1), a.getString(2))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun saveArtistPictures(context: Context, pics: Map<String, ArtistHit>) = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject()
            pics.forEach { (key, hit) -> o.put(key, JSONArray(listOf(hit.id, hit.name, hit.imageTag))) }
            val tmp = File(context.filesDir, "$PICS_FILE.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(File(context.filesDir, PICS_FILE))
        } catch (e: Exception) {
        }
    }

    // ---- JSON <-> models ----

    private fun JSONObject.str(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).ifBlank { null }

    private fun JSONObject.int(key: String): Int? = if (isNull(key) || !has(key)) null else optInt(key)

    private fun Track.toJson() = JSONObject().apply {
        put("id", id); put("t", title); put("ar", JSONArray(artists)); put("al", album)
        put("alId", albumId ?: JSONObject.NULL); put("aa", albumArtist); put("rt", runTimeTicks)
        put("img", imageTag ?: JSONObject.NULL); put("pc", playCount)
        put("lp", lastPlayedDate ?: JSONObject.NULL); put("fav", isFavorite)
        put("arId", artistId ?: JSONObject.NULL); put("n", indexNumber ?: JSONObject.NULL)
        put("d", discNumber ?: JSONObject.NULL)
        if (genres.isNotEmpty()) put("g", JSONArray(genres))
        put("yr", year ?: JSONObject.NULL)
    }

    private fun JSONObject.toTrack() = Track(
        id = getString("id"),
        title = optString("t"),
        artists = optJSONArray("ar")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        album = optString("al"),
        albumId = str("alId"),
        albumArtist = optString("aa"),
        runTimeTicks = optLong("rt"),
        imageTag = str("img"),
        playCount = optInt("pc"),
        lastPlayedDate = str("lp"),
        isFavorite = optBoolean("fav"),
        artistId = str("arId"),
        indexNumber = int("n"),
        discNumber = int("d"),
        genres = optJSONArray("g")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
        year = int("yr")
    )

    private fun Album.toJson() = JSONObject().apply {
        put("id", id); put("n", name); put("ar", artist); put("y", year ?: JSONObject.NULL)
        put("pd", premiereDate ?: JSONObject.NULL); put("dc", dateCreated ?: JSONObject.NULL)
        put("img", imageTag ?: JSONObject.NULL); put("cc", childCount)
        put("lp", lastPlayedDate ?: JSONObject.NULL); put("pc", playCount)
        put("arId", artistId ?: JSONObject.NULL)
    }

    private fun JSONObject.toAlbum() = Album(
        id = getString("id"),
        name = optString("n"),
        artist = optString("ar"),
        year = int("y"),
        premiereDate = str("pd"),
        dateCreated = str("dc"),
        imageTag = str("img"),
        childCount = optInt("cc"),
        lastPlayedDate = str("lp"),
        playCount = optInt("pc"),
        artistId = str("arId")
    )

    private fun List<Track>.toJsonTracks() = JSONArray().apply { forEach { put(it.toJson()) } }
    private fun List<Album>.toJsonAlbums() = JSONArray().apply { forEach { put(it.toJson()) } }

    private fun JSONObject.tracks(key: String) =
        optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it).toTrack() } }.orEmpty()

    private fun JSONObject.albums(key: String) =
        optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getJSONObject(it).toAlbum() } }.orEmpty()
}
