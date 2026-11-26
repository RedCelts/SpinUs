package com.oniel.music.data

import java.time.Instant
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Pure home-screen scoring logic. No Android dependencies, no I/O.
 *
 * The idea, in plain words: surface what this person actually listens to.
 * A track counts only if it was played in the last 90 days. How often it
 * was played (damped with a logarithm, so 20 plays is not 20x better than
 * 1 play) is weighed equally against how recently it was played (a
 * 1/(1 + days/7) falloff, so a song from yesterday beats one from two
 * months ago). A small bonus applies when the song is usually heard
 * around this hour of day. Nothing is pinned: there is no special slot
 * for liked songs — they only appear if their own listening history
 * earns them a place.
 */

object Scoring {

    private const val DAY_MS = 86_400_000L
    private const val POOL_DAYS = 90L
    private const val TILE_COUNT = 8
    private const val REDISCOVER_COUNT = 20

    /** Parse an ISO-8601 date string to epoch millis, or null when missing/unparseable. */
    fun parseDateMs(dateStr: String?): Long? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            Instant.parse(dateStr).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    /** Whole days between [dateStr] and [nowMs]; null when the date is missing. Never negative. */
    fun daysAgo(dateStr: String?, nowMs: Long): Long? {
        val t = parseDateMs(dateStr) ?: return null
        return max(0L, (nowMs - t) / DAY_MS)
    }

    /**
     * Frequency component: log-damped play count, 0.0 for never played,
     * 1.0 once the count reaches 20.
     */
    fun frequencyScore(playCount: Int): Double =
        min(1.0, ln(1.0 + max(0, playCount)) / ln(21.0))

    /** Recency component: 1.0 when played today, falling to ~0.07 after 90 days. */
    fun recencyScore(daysAgo: Long): Double = 1.0 / (1.0 + daysAgo / 7.0)

    /**
     * Hour-of-day bonus: 0.15 when [lastPlayedMs] falls within +/- 2 hours
     * of the hour of [nowMs] (wrapping around midnight), 0.0 otherwise.
     */
    fun hourBoost(lastPlayedMs: Long?, nowMs: Long): Double {
        if (lastPlayedMs == null) return 0.0
        val lastHour = hourOfDayUtc(lastPlayedMs)
        val nowHour = hourOfDayUtc(nowMs)
        val diff = abs(lastHour - nowHour)
        return if (min(diff, 24 - diff) <= 2) 0.15 else 0.0
    }

    private fun hourOfDayUtc(epochMs: Long): Int =
        ((epochMs / 3_600_000L) % 24L).toInt()

    /** Combined score for one track: half frequency, half recency, plus the hour bonus. */
    fun trackScore(track: Track, nowMs: Long): Double {
        val days = daysAgo(track.lastPlayedDate, nowMs)
        if (days == null) {
            // Never played: only a small floor from frequency/favorite.
            return 0.2 * frequencyScore(track.playCount) + if (track.isFavorite) 0.1 else 0.0
        }
        if (days > POOL_DAYS) return -1.0
        val score = 0.5 * frequencyScore(track.playCount) + 0.5 * recencyScore(days)
        return score + hourBoost(parseDateMs(track.lastPlayedDate), nowMs)
    }

    /**
     * Quick-grid tiles: the top [TILE_COUNT] albums from the 90-day pool,
     * represented by their best-scoring track.
     *
     * Album score is computed from all of its tracks together (plays summed,
     * most recent play wins, and the hour the album is most often heard).
     * Tracks played more than 90 days ago, and tracks never played, are
     * excluded from the pool. Liked songs get no pinned slot.
     */
    fun scoreTiles(tracks: List<Track>, nowMs: Long): List<Track> {
        val byAlbum = LinkedHashMap<String, MutableList<Track>>()
        for (track in tracks) {
            val days = daysAgo(track.lastPlayedDate, nowMs) ?: continue
            if (days > POOL_DAYS) continue
            val key = track.albumId ?: ("title:" + track.album)
            byAlbum.getOrPut(key) { mutableListOf() }.add(track)
        }
        data class AlbumScore(val representative: Track, val score: Double)
        val scored = byAlbum.values.mapNotNull { albumTracks ->
            val totalPlays = albumTracks.sumOf { it.playCount }
            val lastMs = albumTracks.mapNotNull { parseDateMs(it.lastPlayedDate) }.maxOrNull()
                ?: return@mapNotNull null
            val days = max(0L, (nowMs - lastMs) / DAY_MS)
            var score = 0.5 * frequencyScore(totalPlays) + 0.5 * recencyScore(days)
            // Hour the album is most often heard.
            val hourCounts = albumTracks.mapNotNull { parseDateMs(it.lastPlayedDate) }
                .groupingBy { hourOfDayUtc(it) }.eachCount()
            val bestHour = hourCounts.maxByOrNull { it.value }?.key
            if (bestHour != null) {
                val nowHour = hourOfDayUtc(nowMs)
                val diff = abs(bestHour - nowHour)
                if (min(diff, 24 - diff) <= 2) score += 0.15
            }
            val representative = albumTracks.maxByOrNull { trackScore(it, nowMs) }!!
            AlbumScore(representative, score)
        }
        return scored.sortedByDescending { it.score }
            .take(TILE_COUNT)
            .map { it.representative }
    }

    /**
     * "Rediscover" shelf: albums whose most recent play was more than 90
     * days ago, plus albums never played at all. Never-played albums come
     * first (they are the most forgotten), then the rest oldest last-play
     * first. Top [REDISCOVER_COUNT].
     */
    fun rediscoverAlbums(albums: List<Album>, nowMs: Long): List<Album> {
        val candidates = albums.filter { album ->
            val days = daysAgo(album.lastPlayedDate, nowMs)
            days == null || days > POOL_DAYS
        }
        val neverPlayed = candidates.filter { it.lastPlayedDate == null }
        val stale = candidates.filter { it.lastPlayedDate != null }
            .sortedBy { parseDateMs(it.lastPlayedDate) }
        return (neverPlayed + stale).take(REDISCOVER_COUNT)
    }

    /**
     * "New releases" shelf: albums whose release date ([premiereDate]) is
     * within the last 90 days, newest first. Albums with no known release
     * date cannot appear.
     *
     * Caveat: an album tagged only with a year (no month/day) counts as
     * released on 1 January of that year.
     */
    fun newReleases(albums: List<Album>, nowMs: Long): List<Album> {
        return albums.mapNotNull { album ->
            val releasedMs = parseDateMs(album.premiereDate) ?: return@mapNotNull null
            val days = (nowMs - releasedMs) / DAY_MS
            if (days in 0..POOL_DAYS) album to releasedMs else null
        }.sortedByDescending { it.second }
            .map { it.first }
    }
}
