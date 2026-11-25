package com.oniel.music.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One ranked artist or song. [imageId]: artist id (artists) or album/artist id (songs). */
data class StatItem(
    val name: String,
    val sub: String,
    val plays: Int,
    val imageId: String?
)

/** Totals and top lists for one period. */
data class PeriodStats(
    val minutes: Int,
    val plays: Int,
    val topArtists: List<StatItem>,
    val topSongs: List<StatItem>
)

/** Minutes listened in one calendar week (for the bar chart). */
data class WeekBar(val label: String, val minutes: Int)

/**
 * Everything the stats screen shows, for you or a friend. Built from the
 * play log (plays of 30 s or more) and small enough to send to a friend
 * as one message.
 */
data class ListeningSummary(
    val userName: String,
    val generatedAtMs: Long,
    val streakDays: Int,
    val week: PeriodStats,
    val month: PeriodStats,
    val allTime: PeriodStats,
    val weeks: List<WeekBar>
)

object ListeningStats {
    private const val DAY_MS = 86_400_000L
    private const val TOP = 8

    /**
     * Build the summary. [tracksById] (the library's songs) supplies artist
     * and album ids so the screen can show photos and covers; plays of
     * songs no longer in the library still count, just without a picture.
     */
    fun build(
        userName: String,
        entries: List<PlayLog.Entry>,
        tracksById: Map<String, Track>,
        nowMs: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): ListeningSummary {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val weekStart = monday.atStartOfDay(zone).toInstant().toEpochMilli()
        val monthStart = nowMs - 30 * DAY_MS

        fun period(list: List<PlayLog.Entry>): PeriodStats {
            val artists = list.groupBy { it.artist }
                .filterKeys { it.isNotBlank() }
                .map { (artist, plays) ->
                    val id = plays.asSequence()
                        .mapNotNull { tracksById[it.trackId] }
                        .firstOrNull { t -> t.artistId != null && (t.artists.firstOrNull() ?: t.albumArtist) == artist }
                        ?.artistId
                    StatItem(artist, "", plays.size, id)
                }
                .sortedByDescending { it.plays }
                .take(TOP)
            val songs = list.groupBy { it.trackId }
                .map { (trackId, plays) ->
                    val first = plays.last()
                    val t = tracksById[trackId]
                    StatItem(first.title, first.artist, plays.size, t?.albumId ?: t?.artistId)
                }
                .sortedByDescending { it.plays }
                .take(TOP)
            return PeriodStats(
                minutes = (list.sumOf { it.durationMs } / 60_000L).toInt(),
                plays = list.size,
                topArtists = artists,
                topSongs = songs
            )
        }

        // Day streak: consecutive days with a play, ending today (or
        // yesterday, so it doesn't reset before you've listened today).
        val days = entries.map { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }.toSet()
        var cursor: LocalDate = if (today in days) today else today.minusDays(1)
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }

        val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        val weeks = (7 downTo 0).map { back ->
            val start = monday.minusWeeks(back.toLong())
            val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
            val endMs = start.plusWeeks(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val minutes = entries.filter { it.timeMs in startMs until endMs }.sumOf { it.durationMs } / 60_000L
            WeekBar(fmt.format(start), minutes.toInt())
        }

        return ListeningSummary(
            userName = userName,
            generatedAtMs = nowMs,
            streakDays = streak,
            week = period(entries.filter { it.timeMs >= weekStart }),
            month = period(entries.filter { it.timeMs >= monthStart }),
            allTime = period(entries),
            weeks = weeks
        )
    }

    // ---- JSON (to send to / receive from friends) ----

    fun toJson(s: ListeningSummary): String = JSONObject().apply {
        put("u", s.userName)
        put("t", s.generatedAtMs)
        put("s", s.streakDays)
        put("w", s.week.toJson())
        put("m", s.month.toJson())
        put("a", s.allTime.toJson())
        put("b", JSONArray().apply {
            s.weeks.forEach { put(JSONArray().apply { put(it.label); put(it.minutes) }) }
        })
    }.toString()

    fun fromJson(text: String): ListeningSummary? = try {
        val o = JSONObject(text)
        ListeningSummary(
            userName = o.optString("u"),
            generatedAtMs = o.optLong("t"),
            streakDays = o.optInt("s"),
            week = o.getJSONObject("w").toPeriod(),
            month = o.getJSONObject("m").toPeriod(),
            allTime = o.getJSONObject("a").toPeriod(),
            weeks = o.optJSONArray("b")?.let { a ->
                (0 until a.length()).map { i ->
                    val bar = a.getJSONArray(i)
                    WeekBar(bar.optString(0), bar.optInt(1))
                }
            }.orEmpty()
        )
    } catch (e: Exception) {
        null
    }

    private fun PeriodStats.toJson() = JSONObject().apply {
        put("min", minutes)
        put("p", plays)
        put("ar", topArtists.toJson())
        put("so", topSongs.toJson())
    }

    private fun List<StatItem>.toJson() = JSONArray().apply {
        forEach { item ->
            put(JSONArray().apply {
                put(item.name); put(item.sub); put(item.plays); put(item.imageId ?: "")
            })
        }
    }

    private fun JSONObject.toPeriod() = PeriodStats(
        minutes = optInt("min"),
        plays = optInt("p"),
        topArtists = optJSONArray("ar").toItems(),
        topSongs = optJSONArray("so").toItems()
    )

    private fun JSONArray?.toItems(): List<StatItem> {
        if (this == null) return emptyList()
        return (0 until length()).map { i ->
            val a = getJSONArray(i)
            StatItem(a.optString(0), a.optString(1), a.optInt(2), a.optString(3).ifBlank { null })
        }
    }
}
