package com.oniel.music.ui

import android.content.Context
import com.oniel.music.data.Addition
import com.oniel.music.data.Additions
import com.oniel.music.data.Album
import com.oniel.music.data.DeezerClient
import com.oniel.music.data.DzAlbum
import com.oniel.music.data.DzArtist
import com.oniel.music.data.DzTrack
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Pick
import com.oniel.music.data.PlayLog
import com.oniel.music.data.TasteArtist
import com.oniel.music.data.TasteProfile
import com.oniel.music.data.Track
import com.oniel.music.data.YouMightEnjoy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Fetches what the home-screen recommendation rows need and caches the
 * Deezer part ("Recommended additions") for 12 hours, or until your top
 * artists change, so Home opens instantly and Deezer isn't hit every time.
 */
class Recommendations(context: Context, private val deezer: DeezerClient) {

    private val cacheFile = File(context.applicationContext.filesDir, "recs/additions.json")

    /** "You might enjoy": library picks (needs Jellyfin's similar albums for a few seeds). */
    suspend fun youMightEnjoy(
        client: JellyfinClient,
        profile: TasteProfile,
        albums: List<Album>,
        tracks: List<Track>,
        plays: List<PlayLog.Entry>,
        nowMs: Long
    ): List<Pick> {
        // Seeds: the albums played most in the last 30 days.
        val albumOfTrack = tracks.associate { it.id to it.albumId }
        val albumsById = albums.associateBy { it.id }
        val since = nowMs - 30L * 86_400_000L
        val seeds = plays.filter { it.timeMs >= since }
            .mapNotNull { albumOfTrack[it.trackId] }
            .groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }
            .mapNotNull { albumsById[it.key] }
            .take(3)
            .ifEmpty { albums.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.take(3) }
        val similar = LinkedHashMap<Album, List<Album>>()
        for (seed in seeds) {
            try {
                similar[seed] = client.getSimilarAlbums(seed.id, 10)
            } catch (e: Exception) {
                // One seed failing doesn't stop the others.
            }
        }
        return YouMightEnjoy.pick(profile, albums, tracks, similar, nowMs)
    }

    /** Cached additions (any age), for showing something immediately. */
    fun cachedAdditions(): List<Addition> = readCache()?.second.orEmpty()

    /**
     * "Recommended additions": from cache when fresh, else from Deezer.
     * [force] ignores the cache (pull-to-refresh / Refresh button).
     */
    suspend fun additions(
        profile: TasteProfile,
        tracks: List<Track>,
        albums: List<Album>,
        nowMs: Long,
        force: Boolean = false
    ): List<Addition> = withContext(Dispatchers.IO) {
        val seeds = profile.top(SEED_COUNT)
        if (seeds.isEmpty()) return@withContext emptyList()
        val seedKeys = seeds.joinToString(",") { it.key }
        val cached = readCache()
        if (!force && cached != null && cached.first == seedKeys &&
            nowMs - cacheTime() < CACHE_MS && cached.second.isNotEmpty()
        ) return@withContext cached.second

        val relatedBySeed = LinkedHashMap<TasteArtist, List<DzArtist>>()
        val seedAlbums = LinkedHashMap<TasteArtist, List<DzAlbum>>()
        var lastError: Exception? = null
        for (seed in seeds) {
            try {
                val dz = deezer.findArtist(seed.name) ?: continue
                relatedBySeed[seed] = deezer.relatedArtists(dz.id, 15)
                seedAlbums[seed] = deezer.albums(dz.id, seed.name)
            } catch (e: Exception) {
                lastError = e // skip this seed
            }
        }
        // Nothing reachable at all: keep showing the cache, else report it.
        if (relatedBySeed.isEmpty() && seedAlbums.isEmpty() && lastError != null) {
            cached?.second?.takeIf { it.isNotEmpty() }?.let { return@withContext it }
            throw lastError
        }
        // Top tracks only for the related artists that will rank highest.
        val scores = HashMap<Long, Double>()
        val byId = HashMap<Long, DzArtist>()
        for ((seed, related) in relatedBySeed) related.forEachIndexed { rank, r ->
            if (profile.rankOf(r.name) in 0 until 5) return@forEachIndexed
            scores[r.id] = (scores[r.id] ?: 0.0) + seed.weight * (1.0 - rank / 25.0)
            byId[r.id] = r
        }
        val topTracks = HashMap<Long, List<DzTrack>>()
        for (id in scores.entries.sortedByDescending { it.value }.take(TOP_TRACK_ARTISTS).map { it.key }) {
            try {
                topTracks[id] = deezer.topTracks(id, 5)
            } catch (e: Exception) {
                // Skip this artist.
            }
        }
        val result = Additions.assemble(profile, relatedBySeed, topTracks, seedAlbums, tracks, albums, nowMs)
        if (result.isNotEmpty()) writeCache(seedKeys, result)
        result.ifEmpty { cached?.second.orEmpty() }
    }

    // ---- cache ----

    private fun cacheTime(): Long = if (cacheFile.exists()) cacheFile.lastModified() else 0L

    private fun readCache(): Pair<String, List<Addition>>? = try {
        if (!cacheFile.exists()) null else {
            val json = JSONObject(cacheFile.readText())
            val items = json.optJSONArray("items") ?: JSONArray()
            json.optString("seeds") to (0 until items.length()).mapNotNull { i ->
                val o = items.optJSONObject(i) ?: return@mapNotNull null
                Addition(
                    kind = if (o.optString("kind") == "ALBUM") Addition.Kind.ALBUM else Addition.Kind.SONG,
                    deezerId = o.optLong("id"),
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    imageUrl = o.optString("image").ifBlank { null },
                    link = o.optString("link").ifBlank { null },
                    reason = o.optString("reason")
                )
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun writeCache(seeds: String, items: List<Addition>) {
        try {
            cacheFile.parentFile?.mkdirs()
            val arr = JSONArray()
            items.forEach {
                arr.put(JSONObject()
                    .put("kind", it.kind.name).put("id", it.deezerId).put("title", it.title)
                    .put("artist", it.artist).put("image", it.imageUrl ?: "")
                    .put("link", it.link ?: "").put("reason", it.reason))
            }
            cacheFile.writeText(JSONObject().put("seeds", seeds).put("items", arr).toString())
        } catch (e: Exception) {
            // Cache is optional.
        }
    }

    companion object {
        const val SEED_COUNT = 5
        const val TOP_TRACK_ARTISTS = 10
        const val CACHE_MS = 12L * 60 * 60 * 1000
    }
}
