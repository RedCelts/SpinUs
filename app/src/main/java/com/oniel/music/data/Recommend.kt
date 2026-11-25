package com.oniel.music.data

import java.text.Normalizer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * Home-screen recommendations. Pure logic (no Android, no network) so it
 * is unit-tested on the JVM; the network calls live in JellyfinClient
 * (similar albums) and DeezerClient (music outside the library).
 *
 * Taste = which artists this person actually listens to:
 * - every logged play (PlayLog) counts, with a 30-day half-life, so this
 *   month's listening matters most;
 * - Jellyfin play counts add a log-damped "long-term" weight;
 * - favorites add a bonus.
 */

/** Text key for matching names across Jellyfin and Deezer: no accents,
 *  case, punctuation, "feat." parts or "(Live)" style suffixes. */
fun matchKey(text: String): String {
    var s = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    s = s.lowercase()
    s = s.replace(Regex("\\s*[\\(\\[].*?[\\)\\]]"), " ")          // (feat. X), [Live], (Remastered)
    s = s.replace(Regex("\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$"), " ")
    s = s.replace(Regex("\\s+-\\s+.*$"), " ")                       // "Song - Live at ..."
    s = s.replace("&", " y ").replace(Regex("[^a-z0-9 ]"), " ")
    return s.replace(Regex("\\s+"), " ").trim()
}

data class TasteArtist(val name: String, val key: String, val weight: Double)

class TasteProfile(val artists: List<TasteArtist>) {
    private val byKey = artists.associateBy { it.key }
    fun top(n: Int): List<TasteArtist> = artists.take(n)
    fun weightOf(name: String): Double = byKey[matchKey(name)]?.weight ?: 0.0
    fun rankOf(name: String): Int = artists.indexOfFirst { it.key == matchKey(name) }
    val isEmpty: Boolean get() = artists.isEmpty()
}

object Taste {
    private const val DAY_MS = 86_400_000L
    private const val HALF_LIFE_DAYS = 30.0

    fun build(plays: List<PlayLog.Entry>, tracks: List<Track>, nowMs: Long): TasteProfile {
        val weights = HashMap<String, Double>()
        val names = HashMap<String, String>()
        fun add(name: String, w: Double) {
            if (name.isBlank() || w <= 0.0) return
            val key = matchKey(name)
            if (key.isEmpty()) return
            weights[key] = (weights[key] ?: 0.0) + w
            names.putIfAbsent(key, name.trim())
        }
        for (p in plays) {
            val days = ((nowMs - p.timeMs).coerceAtLeast(0L)).toDouble() / DAY_MS
            add(p.artist, exp(-ln(2.0) * days / HALF_LIFE_DAYS))
        }
        for (t in tracks) {
            val artist = t.artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: t.albumArtist
            if (t.playCount > 0) add(artist, 0.25 * ln(1.0 + t.playCount))
            if (t.isFavorite) add(artist, 0.5)
        }
        val list = weights.entries
            .map { TasteArtist(names[it.key] ?: it.key, it.key, it.value) }
            .sortedByDescending { it.weight }
        return TasteProfile(list)
    }
}

// ---- "You might enjoy" (from the library) ----------------------------------

sealed class Pick {
    abstract val reason: String
    data class AlbumPick(val album: Album, override val reason: String) : Pick()
    data class ArtistPick(val name: String, val coverAlbum: Album?, override val reason: String) : Pick()
    data class TrackPick(val track: Track, override val reason: String) : Pick()
}

object YouMightEnjoy {
    private const val DAY_MS = 86_400_000L
    const val MAX_PICKS = 14

    /**
     * [similarBySeed]: Jellyfin's similar albums for a few seed albums
     * (the albums played most lately), keyed by the seed album.
     */
    fun pick(
        profile: TasteProfile,
        albums: List<Album>,
        tracks: List<Track>,
        similarBySeed: Map<Album, List<Album>>,
        nowMs: Long
    ): List<Pick> {
        if (profile.isEmpty || albums.isEmpty()) return emptyList()
        // Changes daily, stable within a day.
        val random = Random(nowMs / DAY_MS)
        val recentCutoff = nowMs - 21 * DAY_MS
        fun recentlyPlayed(a: Album) = (Scoring.parseDateMs(a.lastPlayedDate) ?: 0L) >= recentCutoff
        val used = HashSet<String>()
        val top = profile.top(6)

        // A) Barely-played albums by artists you love.
        val fromFavorites = top.mapNotNull { artist ->
            albums.filter { matchKey(it.artist) == artist.key && it.playCount <= 1 && !recentlyPlayed(it) }
                .shuffled(random).firstOrNull()
                ?.let { Pick.AlbumPick(it, "More from ${artist.name}") }
        }.take(4)
        fromFavorites.forEach { used += it.album.id }

        // B) Albums Jellyfin finds similar to what you've been playing,
        //    by other artists (variety), not heard lately.
        val topKeys = top.take(3).map { it.key }.toSet()
        val similar = similarBySeed.flatMap { (seed, list) ->
            list.filter {
                it.id != seed.id && it.id !in used && !recentlyPlayed(it) &&
                    matchKey(it.artist) !in topKeys
            }.take(3).map { Pick.AlbumPick(it, "Similar to ${seed.name}") }
        }.distinctBy { it.album.id }.take(4)
        similar.forEach { used += it.album.id }

        // C) Artists you have but rarely play, found next to your taste
        //    (in the similar-albums results).
        val pickedArtists = similar.map { matchKey(it.album.artist) }.toSet()
        val artistPicks = similarBySeed.values.flatten()
            .filter { it.id !in used && matchKey(it.artist) !in pickedArtists }
            .filter { it.artist.isNotBlank() && profile.rankOf(it.artist).let { r -> r < 0 || r >= 10 } }
            .groupBy { matchKey(it.artist) }
            .values
            .map { group -> group.first() }
            .filter { it.id !in used }
            .take(3)
            .map { album ->
                val seedArtist = similarBySeed.entries.firstOrNull { (_, l) -> album in l }?.key?.artist
                Pick.ArtistPick(album.artist, album,
                    if (seedArtist != null) "If you like $seedArtist" else "In your library")
            }

        // D) Songs you've never played by your top artists.
        val trackPicks = top.flatMap { artist ->
            tracks.filter { t ->
                t.playCount == 0 && (t.artists.any { matchKey(it) == artist.key } || matchKey(t.albumArtist) == artist.key)
            }.shuffled(random).take(2).map { Pick.TrackPick(it, "Unplayed · ${artist.name}") }
        }.distinctBy { it.track.id }.take(4)

        return interleave(listOf(fromFavorites, trackPicks, similar, artistPicks)).take(MAX_PICKS)
    }

    private fun interleave(lists: List<List<Pick>>): List<Pick> {
        val out = mutableListOf<Pick>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { l -> l.getOrNull(i)?.let(out::add) }
        return out
    }
}

// ---- "Recommended additions" (outside the library, from Deezer) ------------

data class DzArtist(val id: Long, val name: String, val pictureUrl: String?, val fans: Long = 0)
data class DzTrack(
    val id: Long, val title: String, val artist: String, val album: String,
    val coverUrl: String?, val link: String?, val durationSec: Int = 0,
    /** 30-second preview (expires after a few hours; refetch on failure). */
    val previewUrl: String? = null,
    /** Large (1000 px) album cover, for backdrops. */
    val bigCoverUrl: String? = null,
    /** Large artist photo, for the genre backdrop. */
    val artistPictureUrl: String? = null,
    /** Deezer popularity (higher = more played right now). */
    val rank: Int = 0
)

/** One of Deezer's main genres (Pop, Latin, Christian, …). */
data class DzGenre(val id: Long, val name: String, val pictureUrl: String?)
data class DzAlbum(
    val id: Long, val title: String, val artist: String, val coverUrl: String?,
    val link: String?, val releaseDate: String?, val recordType: String?
)

data class Addition(
    val kind: Kind,
    val deezerId: Long,
    val title: String,
    val artist: String,
    val imageUrl: String?,
    val link: String?,
    val reason: String
) {
    enum class Kind { SONG, ALBUM }
}

object Additions {
    private const val DAY_MS = 86_400_000L
    const val NEW_RELEASE_DAYS = 180L
    const val MAX_SONGS = 12
    const val MAX_ALBUMS = 6

    /**
     * Builds the list from what DeezerClient fetched:
     * - [relatedBySeed]: Deezer's related artists for each top artist (seed);
     * - [topTracks]: popular tracks per related artist (by Deezer artist id);
     * - [seedAlbums]: albums of each seed artist (for new releases).
     * Anything already in the library (same song + artist, same album
     * title + artist, or artists you already have) is skipped.
     */
    fun assemble(
        profile: TasteProfile,
        relatedBySeed: Map<TasteArtist, List<DzArtist>>,
        topTracks: Map<Long, List<DzTrack>>,
        seedAlbums: Map<TasteArtist, List<DzAlbum>>,
        libraryTracks: List<Track>,
        libraryAlbums: List<Album>,
        nowMs: Long
    ): List<Addition> {
        val ownedSongs = HashSet<String>()
        for (t in libraryTracks) {
            val artists = (t.artists + t.albumArtist).filter { it.isNotBlank() }
            for (a in artists) ownedSongs += matchKey(t.title) + "|" + matchKey(a)
        }
        val ownedAlbums = libraryAlbums.map { matchKey(it.name) + "|" + matchKey(it.artist) }.toSet() +
            libraryTracks.map { matchKey(it.album) + "|" + matchKey(it.albumArtist) }

        // New albums from artists you love.
        val cutoff = nowMs - NEW_RELEASE_DAYS * DAY_MS
        val newAlbums = seedAlbums.flatMap { (seed, list) ->
            list.filter { a ->
                val released = parseDay(a.releaseDate)
                released != null && released >= cutoff && released <= nowMs + DAY_MS &&
                    matchKey(a.title) + "|" + seed.key !in ownedAlbums
            }.map { it to seed }
        }.sortedByDescending { parseDay(it.first.releaseDate) }
            .distinctBy { matchKey(it.first.title) + "|" + it.second.key }
            .take(MAX_ALBUMS)
            .map { (a, seed) ->
                val kind = when (a.recordType?.lowercase()) {
                    "single" -> "New single"
                    "ep" -> "New EP"
                    else -> "New album"
                }
                Addition(Addition.Kind.ALBUM, a.id, a.title, seed.name, a.coverUrl, a.link,
                    "$kind · ${monthDay(a.releaseDate)}")
            }

        // Artists similar to several of your favorites rank first.
        data class Candidate(val artist: DzArtist, var score: Double, val because: MutableList<String>)
        val candidates = LinkedHashMap<String, Candidate>()
        for ((seed, related) in relatedBySeed) {
            related.forEachIndexed { rank, r ->
                val key = matchKey(r.name)
                // Skip your own top artists (their news comes as new
                // releases); artists you merely own a little of are fine,
                // their songs you don't have still count.
                val rank = profile.rankOf(r.name)
                if (key.isEmpty() || rank in 0 until 5) return@forEachIndexed
                val c = candidates.getOrPut(key) { Candidate(r, 0.0, mutableListOf()) }
                c.score += seed.weight * (1.0 - rank / 25.0)
                if (seed.name !in c.because) c.because += seed.name
            }
        }
        val ranked = candidates.values.sortedByDescending { it.score }
        val songs = mutableListOf<Addition>()
        for (c in ranked) {
            if (songs.size >= MAX_SONGS) break
            val because = c.because.take(2).joinToString(" & ")
            topTracks[c.artist.id].orEmpty()
                .filter { matchKey(it.title) + "|" + matchKey(it.artist) !in ownedSongs }
                .take(if (c.because.size > 1) 2 else 1)
                .forEach {
                    songs += Addition(Addition.Kind.SONG, it.id, it.title, it.artist,
                        it.coverUrl ?: c.artist.pictureUrl, it.link, "Because you like $because")
                }
        }
        // New releases lead, then songs; capped.
        return (newAlbums + songs.take(MAX_SONGS)).distinctBy { it.kind to it.deezerId }
    }

    /** "2026-11-14" -> epoch ms (UTC midnight), null when missing/bad. */
    fun parseDay(date: String?): Long? = try {
        if (date.isNullOrBlank() || date.startsWith("0000")) null
        else java.time.LocalDate.parse(date.take(10)).toEpochDay() * DAY_MS
    } catch (e: Exception) {
        null
    }

    private fun monthDay(date: String?): String = try {
        val d = java.time.LocalDate.parse(date!!.take(10))
        d.month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) + " " + d.dayOfMonth
    } catch (e: Exception) {
        ""
    }
}
