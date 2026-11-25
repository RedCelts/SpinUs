package com.oniel.music.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId
import kotlin.random.Random

/*
 * Home-screen recommendation rules (v1.5.0).
 *
 * Plays come from the play log, so the 30-second rule applies everywhere.
 * Jellyfin has no BPM / energy / valence / similarity scores, so:
 *  - "similar artists" = genre overlap (Jaccard of genre tags) >= 0.70,
 *    plus Jellyfin's instant mix (server-side genre similarity);
 *  - "mood / BPM cluster" = genre families (calm vs upbeat) per time of day.
 */

// ---------------------------------------------------------------------------
// Listening signals: skips and completion, per track (on the phone)
// ---------------------------------------------------------------------------

/**
 * How each song's recent plays ended: how long you listened, whether it
 * was a skip (< 20 s of a longer song). Kept per track (last 6 plays, 120
 * days) in one small JSON file.
 */
class ListenSignals(private val dir: File) {
    data class Outcome(val timeMs: Long, val listenedMs: Long, val durationMs: Long, val skipped: Boolean)

    private val file get() = File(dir, "signals.json")
    private var cache: MutableMap<String, MutableList<Outcome>>? = null

    @Synchronized
    fun record(trackId: String, listenedMs: Long, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        if (listenedMs <= 0L) return
        val map = load()
        val skipped = listenedMs < SKIP_MS && (durationMs <= 0L || durationMs > SKIP_MS)
        val list = map.getOrPut(trackId) { mutableListOf() }
        list += Outcome(nowMs, listenedMs, durationMs, skipped)
        while (list.size > 6) list.removeAt(0)
        val cutoff = nowMs - 120 * DAY_MS
        map.values.forEach { l -> l.removeAll { it.timeMs < cutoff } }
        map.entries.removeAll { it.value.isEmpty() }
        save(map)
    }

    @Synchronized
    fun snapshot(): SignalSnapshot = SignalSnapshot(load().mapValues { it.value.toList() })

    private fun load(): MutableMap<String, MutableList<Outcome>> {
        cache?.let { return it }
        val map = HashMap<String, MutableList<Outcome>>()
        try {
            if (file.exists()) {
                val o = JSONObject(file.readText())
                o.keys().forEach { id ->
                    val a = o.getJSONArray(id)
                    map[id] = (0 until a.length()).map { i ->
                        val e = a.getJSONArray(i)
                        Outcome(e.getLong(0), e.getLong(1), e.getLong(2), e.getInt(3) == 1)
                    }.toMutableList()
                }
            }
        } catch (e: Exception) {
            // Corrupt: start fresh.
        }
        cache = map
        return map
    }

    private fun save(map: Map<String, List<Outcome>>) {
        try {
            dir.mkdirs()
            val o = JSONObject()
            map.forEach { (id, list) ->
                o.put(id, JSONArray().apply {
                    list.forEach { put(JSONArray(listOf(it.timeMs, it.listenedMs, it.durationMs, if (it.skipped) 1 else 0))) }
                })
            }
            val tmp = File(dir, "signals.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        } catch (e: Exception) {
        }
    }

    companion object {
        const val SKIP_MS = 20_000L
        const val DAY_MS = 86_400_000L
    }
}

/** Read-only view of [ListenSignals] for the rules. */
class SignalSnapshot(private val data: Map<String, List<ListenSignals.Outcome>>) {
    fun skipCount(trackId: String): Int = data[trackId]?.count { it.skipped } ?: 0

    /** Skipped (< 20 s) the last 2 times in a row → hidden from recommendations for 30 days. */
    fun isSuppressed(trackId: String, nowMs: Long): Boolean {
        val list = data[trackId] ?: return false
        if (list.size < 2) return false
        val last = list[list.size - 1]
        val prev = list[list.size - 2]
        return last.skipped && prev.skipped && nowMs - last.timeMs < 30 * ListenSignals.DAY_MS
    }

    /** Average share of the song listened to, or null when there's no data. */
    fun completionRate(trackId: String): Double? {
        val list = data[trackId]?.filter { it.durationMs > 0 } ?: return null
        if (list.isEmpty()) return null
        return list.map { (it.listenedMs.toDouble() / it.durationMs).coerceIn(0.0, 1.0) }.average()
    }

    companion object {
        val EMPTY = SignalSnapshot(emptyMap())
    }
}

// ---------------------------------------------------------------------------
// Shared context + global guardrails
// ---------------------------------------------------------------------------

/** Everything the rules need, built once per Home load. */
class RuleContext(
    val tracks: List<Track>,
    val plays: List<PlayLog.Entry>,
    val signals: SignalSnapshot,
    val nowMs: Long = System.currentTimeMillis()
) {
    /** Plays per track from the play log (30-second rule). */
    val logCount: Map<String, Int> = plays.groupingBy { it.trackId }.eachCount()
    private val lastLogPlay: Map<String, Long> =
        plays.groupBy { it.trackId }.mapValues { (_, l) -> l.maxOf { it.timeMs } }

    /** Today's number: mixes and stations change once a day. */
    val day: Long = nowMs / ListenSignals.DAY_MS

    fun artistOf(t: Track): String = t.artists.firstOrNull()?.takeIf { it.isNotBlank() } ?: t.albumArtist
    fun artistsOf(t: Track): List<String> =
        (t.artists + t.albumArtist).filter { it.isNotBlank() }.distinctBy { matchKey(it) }

    fun daysSinceLastPlayed(t: Track): Long? {
        val log = lastLogPlay[t.id]
        val server = Scoring.parseDateMs(t.lastPlayedDate)
        val last = listOfNotNull(log, server).maxOrNull() ?: return null
        return (nowMs - last) / ListenSignals.DAY_MS
    }

    // ---- Guardrail 2: version hygiene ----
    private val primaryKeys: Set<String> = tracks.filter { !VersionHygiene.isAltVersion(it.title) }
        .map { "${matchKey(artistOf(it))}|${VersionHygiene.baseKey(it.title)}" }.toSet()

    /** An instrumental / a cappella / sped up / clean copy of a song you also have in the main version. */
    fun isRedundantVersion(t: Track): Boolean =
        VersionHygiene.isAltVersion(t.title) &&
            "${matchKey(artistOf(t))}|${VersionHygiene.baseKey(t.title)}" in primaryKeys

    /** Usable on any recommendation shelf: not skip-suppressed (guardrail 3), not a redundant version. */
    fun recommendable(t: Track): Boolean = !signals.isSuppressed(t.id, nowMs) && !isRedundantVersion(t)

    // ---- Genres ----
    private val artistGenres: Map<String, Set<String>> by lazy {
        val m = HashMap<String, MutableSet<String>>()
        tracks.forEach { t ->
            val g = t.genres.map { it.trim().lowercase() }.filter { it.isNotBlank() }
            if (g.isEmpty()) return@forEach
            artistsOf(t).forEach { a -> m.getOrPut(matchKey(a)) { HashSet() }.addAll(g) }
        }
        m
    }

    fun genresOfArtist(name: String): Set<String> = artistGenres[matchKey(name)].orEmpty()

    /** Genre-tag overlap between two artists (0..1). */
    fun similarity(a: String, b: String): Double {
        val ga = genresOfArtist(a)
        val gb = genresOfArtist(b)
        if (ga.isEmpty() || gb.isEmpty()) return 0.0
        return (ga intersect gb).size.toDouble() / (ga union gb).size
    }

    /** How much you play each genre, the top genre = 1.0. */
    val genreAffinity: Map<String, Double> by lazy {
        val byId = tracks.associateBy { it.id }
        val counts = HashMap<String, Int>()
        plays.forEach { p ->
            byId[p.trackId]?.genres?.forEach { g ->
                val k = g.trim().lowercase()
                if (k.isNotBlank()) counts[k] = (counts[k] ?: 0) + 1
            }
        }
        val max = counts.values.maxOrNull()?.toDouble() ?: return@lazy emptyMap()
        counts.mapValues { it.value / max }
    }

    /** Most-played artists in the last [days] days (30-second plays). */
    fun topArtists(days: Int, n: Int): List<Pair<String, Int>> {
        val since = nowMs - days * ListenSignals.DAY_MS
        return plays.filter { it.timeMs >= since && it.artist.isNotBlank() }
            .groupBy { matchKey(it.artist) }
            .map { (_, l) -> l.first().artist to l.size }
            .sortedByDescending { it.second }
            .take(n)
    }
}

object VersionHygiene {
    private val alt = Regex(
        """[(\[]\s*(instrumental|acapella|a cappella|a capella|sped up|sped-up|clean|clean version)\s*[)\]]|\s-\s(instrumental|acapella|a cappella|sped up|clean)\s*$""",
        RegexOption.IGNORE_CASE
    )

    fun isAltVersion(title: String): Boolean = alt.containsMatchIn(title)

    /** Title without version tags, for matching the main version. */
    fun baseKey(title: String): String = matchKey(alt.replace(title, " "))
}

/**
 * Guardrail 1: the same album on at most [max] shelves per Home load.
 * Shelves are given in screen order; later shelves lose the repeats.
 */
fun <T> limitRepeats(shelves: List<List<T>>, albumIdOf: (T) -> String?, max: Int = 2): List<List<T>> {
    val seen = HashMap<String, Int>()
    return shelves.map { shelf ->
        shelf.filter { item ->
            val id = albumIdOf(item) ?: return@filter true
            val n = seen[id] ?: 0
            if (n >= max) false else {
                seen[id] = n + 1
                true
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 6. Recently added
// ---------------------------------------------------------------------------

object RecentlyAddedRule {
    /**
     * Albums newest first (DateCreated), only ones with at least one song
     * already scanned into the library. Loose songs aren't listed.
     */
    fun apply(added: List<Album>, tracks: List<Track>): List<Album> {
        if (tracks.isEmpty()) return added
        val counts = tracks.groupingBy { it.albumId }.eachCount()
        return added
            .filter { (counts[it.id] ?: 0) >= 1 && it.name.isNotBlank() }
            .sortedByDescending { Scoring.parseDateMs(it.dateCreated) ?: 0L }
    }
}

// ---------------------------------------------------------------------------
// 7. Recommended stations
// ---------------------------------------------------------------------------

object StationRules {
    /**
     * Seeds: your 6–8 most-played artists of the last 60 days, minus any
     * artist already in the first 3 "You might enjoy" cards.
     */
    fun seeds(ctx: RuleContext, youMightEnjoyTop3Artists: Set<String>): List<Pair<String, Int>> =
        ctx.topArtists(days = 60, n = 16)
            .filter { matchKey(it.first) !in youMightEnjoyTop3Artists }
            .take(8)

    /**
     * Build an endless-feeling station (60 songs) for [seed]:
     * 25% the seed artist (not skipped more than once), 50% similar artists
     * (genre overlap >= 0.70, or Jellyfin's instant mix), 25% deep cuts
     * (played at most once) from the seed's genres. At least 3 songs
     * between two songs by the same artist.
     */
    fun build(seed: String, ctx: RuleContext, instantMix: List<Track>, size: Int = 60): List<Track> {
        val random = Random(ctx.day * 31 + matchKey(seed).hashCode())
        val seedKey = matchKey(seed)
        fun bySeed(t: Track) = ctx.artistsOf(t).any { matchKey(it) == seedKey }
        val ok = ctx.tracks.filter { ctx.recommendable(it) }

        val core = ok.filter { bySeed(it) && ctx.signals.skipCount(it.id) <= 1 }.shuffled(random)

        val similarArtists = ok.asSequence().map { ctx.artistOf(it) }.distinctBy { matchKey(it) }
            .filter { matchKey(it) != seedKey && ctx.similarity(seed, it) >= 0.70 }
            .map { matchKey(it) }.toSet()
        val similar = (ok.filter { !bySeed(it) && matchKey(ctx.artistOf(it)) in similarArtists } +
            instantMix.filter { !bySeed(it) && ctx.recommendable(it) })
            .distinctBy { it.id }.shuffled(random)

        val seedGenres = ctx.genresOfArtist(seed)
        val deep = ok.filter { t ->
            !bySeed(t) && (ctx.logCount[t.id] ?: 0) <= 1 && t.playCount <= 1 &&
                t.genres.any { it.trim().lowercase() in seedGenres }
        }.shuffled(random)

        val want = listOf(size / 4, size / 2, size - size / 4 - size / 2)
        val used = HashSet<String>()
        fun take(list: List<Track>, n: Int) = list.filter { it.id !in used }.take(n).also { l -> l.forEach { used += it.id } }
        val a = take(core, want[0]).toMutableList()
        val b = take(similar, want[1]).toMutableList()
        val c = take(deep, want[2]).toMutableList()
        // Short somewhere (small library): fill from the other groups.
        val short = size - a.size - b.size - c.size
        if (short > 0) b += take(similar + deep + core, short)

        // Mix the groups: seed, similar, similar, deep, …
        val mixed = mutableListOf<Track>()
        val queues = listOf(a, b, b, c).map { it }
        var i = 0
        while (a.isNotEmpty() || b.isNotEmpty() || c.isNotEmpty()) {
            val q = queues[i % queues.size]
            if (q.isNotEmpty()) mixed += q.removeAt(0)
            i++
        }
        return separateArtists(mixed, gap = 3) { ctx.artistOf(it) }
    }
}

/**
 * Reorder so the same artist never appears within [gap] songs of itself;
 * songs that can't be placed that way are dropped.
 */
fun <T> separateArtists(list: List<T>, gap: Int, artistOf: (T) -> String): List<T> {
    val pending = list.toMutableList()
    val out = mutableListOf<T>()
    while (pending.isNotEmpty()) {
        val recent = out.takeLast(gap).map { matchKey(artistOf(it)) }.toSet()
        val idx = pending.indexOfFirst { matchKey(artistOf(it)) !in recent }
        if (idx < 0) break
        out += pending.removeAt(idx)
    }
    return out
}

// ---------------------------------------------------------------------------
// 8. Made for you
// ---------------------------------------------------------------------------

/** One "Made for you" card. */
data class Mix(
    val id: String,
    val title: String,
    val subtitle: String,
    val tracks: List<Track>
)

object MixRules {
    const val TARGET = 50
    const val MIN_SHOWN = 20
    private const val MAX_PER_ARTIST = 3
    private const val MIN_DURATION_MS = 90_000L

    private val calm = listOf(
        "ballad", "acoustic", "ambient", "chill", "lofi", "lo-fi", "jazz", "soul", "r&b", "rnb", "worship",
        "adoracion", "adoración", "gospel", "classical", "piano", "bolero", "folk", "soft", "instrumental", "bossa"
    )
    private val upbeat = listOf(
        "dance", "pop", "reggaeton", "latin", "urbano", "hip hop", "hip-hop", "rap", "edm", "electro", "house",
        "merengue", "salsa", "bachata", "rock", "trap", "cumbia", "dembow", "funk", "disco", "punk", "metal"
    )

    /** The 4–6 cards, rebuilt once a day. */
    fun build(ctx: RuleContext, zone: ZoneId = ZoneId.systemDefault()): List<Mix> {
        val pool = ctx.tracks.filter { ctx.recommendable(it) && ticksToMs(it.runTimeTicks) >= MIN_DURATION_MS }
        if (pool.isEmpty()) return emptyList()
        val mixes = mutableListOf<Mix>()
        mixes += artistMixes(ctx, pool, 3)
        mixes += eraMixes(ctx, pool, 2)
        moodMix(ctx, pool, zone)?.let { mixes += it }
        return mixes.filter { it.tracks.size >= MIN_SHOWN }.take(6)
    }

    /** "X mix": 40% X, 60% collaborators and genre peers not played in the last week. */
    private fun artistMixes(ctx: RuleContext, pool: List<Track>, n: Int): List<Mix> {
        val cores = ctx.topArtists(days = 60, n = n).map { it.first }
        return cores.map { core ->
            val random = Random(ctx.day * 17 + matchKey(core).hashCode())
            val coreKey = matchKey(core)
            fun byCore(t: Track) = ctx.artistsOf(t).any { matchKey(it) == coreKey }
            val coreTracks = pool.filter { byCore(it) }.shuffled(random).take(TARGET * 40 / 100)
            // Collaborators: artists who share songs with the core artist.
            val collaborators = pool.filter { byCore(it) }
                .flatMap { ctx.artistsOf(it) }.map { matchKey(it) }.filter { it != coreKey }.toSet()
            val peers = pool.filter { t ->
                !byCore(t) && (ctx.daysSinceLastPlayed(t) ?: Long.MAX_VALUE) > 7 &&
                    (matchKey(ctx.artistOf(t)) in collaborators || ctx.similarity(core, ctx.artistOf(t)) >= 0.5)
            }.shuffled(random)
            val peerPart = capPerArtist(peers, MAX_PER_ARTIST, ctx).take(TARGET - coreTracks.size)
            val tracks = interleave(coreTracks, peerPart)
            Mix("artist:$coreKey", "$core mix", "$core and similar artists", tracks)
        }
    }

    /** Decade mixes: your most-played decades, only genres you love (affinity > 0.6). */
    private fun eraMixes(ctx: RuleContext, pool: List<Track>, n: Int): List<Mix> {
        val byId = ctx.tracks.associateBy { it.id }
        val decades = ctx.plays.mapNotNull { byId[it.trackId]?.year }
            .filter { it > 1900 }
            .groupingBy { it / 10 * 10 }.eachCount()
            .entries.sortedByDescending { it.value }.map { it.key }.take(n)
        val loved = ctx.genreAffinity.filterValues { it > 0.6 }.keys
        return decades.map { decade ->
            val random = Random(ctx.day * 13 + decade)
            val inDecade = pool.filter { t ->
                val y = t.year ?: return@filter false
                y in decade..(decade + 9) && (loved.isEmpty() || t.genres.any { it.trim().lowercase() in loved })
            }.shuffled(random)
            val title = if (decade >= 2000) "${decade}s mix" else "'${(decade % 100).toString().padStart(2, '0')}s mix"
            Mix("era:$decade", title, "Your favorite sounds from the ${decade}s",
                capPerArtist(inDecade, MAX_PER_ARTIST, ctx).take(TARGET))
        }
    }

    /** A mix for right now: calm at night, upbeat by day; songs you usually finish. */
    private fun moodMix(ctx: RuleContext, pool: List<Track>, zone: ZoneId): Mix? {
        val hour = Instant.ofEpochMilli(ctx.nowMs).atZone(zone).hour
        val (title, subtitle, words) = when (hour) {
            in 5..10 -> Triple("Morning lift", "Upbeat songs to start the day", upbeat)
            in 11..16 -> Triple("Afternoon energy", "Keep the momentum going", upbeat)
            in 17..20 -> Triple("Evening wind-down", "Easing into the night", calm + listOf("pop", "latin", "r&b"))
            else -> Triple("Late night", "Calm songs for the late hours", calm)
        }
        val random = Random(ctx.day * 7 + hour / 6)
        val tracks = pool.filter { t ->
            val g = t.genres.joinToString(" ").lowercase()
            words.any { g.contains(it) } && finishesUsually(t, ctx)
        }.shuffled(random)
        if (tracks.isEmpty()) return null
        return Mix("mood:$title", title, subtitle, capPerArtist(tracks, MAX_PER_ARTIST, ctx).take(TARGET))
    }

    /** Completion rate >= 75% (or, with no data yet, never skipped). */
    private fun finishesUsually(t: Track, ctx: RuleContext): Boolean =
        ctx.signals.completionRate(t.id)?.let { it >= 0.75 } ?: (ctx.signals.skipCount(t.id) == 0)

    private fun capPerArtist(list: List<Track>, max: Int, ctx: RuleContext): List<Track> {
        val counts = HashMap<String, Int>()
        return list.filter { t ->
            val k = matchKey(ctx.artistOf(t))
            val n = counts[k] ?: 0
            if (n >= max) false else {
                counts[k] = n + 1
                true
            }
        }
    }

    private fun interleave(a: List<Track>, b: List<Track>): List<Track> {
        val out = mutableListOf<Track>()
        val ia = a.iterator()
        val ib = b.iterator()
        // Roughly 2 peers for every core song (40 / 60).
        while (ia.hasNext() || ib.hasNext()) {
            if (ia.hasNext()) out += ia.next()
            repeat(2) { if (ib.hasNext()) out += ib.next() }
        }
        return out
    }

    private fun ticksToMs(ticks: Long) = ticks / 10_000L
}
