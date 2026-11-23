package com.oniel.music.data

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * On-device play log, stored as a plain JSON file (one object per line is
 * avoided — the file is a single JSON array). No Room, no database: the
 * log only ever needs "append a play" and "summarise by week".
 *
 * Entry shape (kept short on purpose): {"t":<epoch ms>,"id":"<track id>",
 * "n":"<title>","a":"<artist>","al":"<album>","d":<duration ms>}.
 *
 * - At most [MAX_ENTRIES] entries are kept on the phone (oldest dropped first).
 * - Entries older than [RETENTION_DAYS] days are pruned from the phone on
 *   every write. The copy in the user's Jellyfin account (HistorySync)
 *   is never pruned.
 * - A week is Monday–Sunday. Weeks are built from this log only — the
 *   server keeps no dated play history, so nothing older than the first
 *   logged play can ever appear here.
 */
class PlayLog(private val dir: File) {

    companion object {
        const val MAX_ENTRIES = 10_000
        const val RETENTION_DAYS = 365L
        private const val FILE_NAME = "playlog.json"
        private const val DAY_MS = 86_400_000L

        /** Same JSON as the on-device file; used for the account copy too. */
        private val codec by lazy { PlayLog(File(".")) }
        fun encodeJson(entries: List<Entry>): String = codec.toJson(entries)
        fun decodeJson(text: String): List<Entry> = codec.parseEntries(text)
    }

    /** One logged play. */
    data class Entry(
        val timeMs: Long,
        val trackId: String,
        val title: String,
        val artist: String,
        val album: String,
        val durationMs: Long
    )

    private val file: File get() = File(dir, FILE_NAME)

    /** Record one play. [timeMs] defaults to now; pass an explicit time in tests. */
    @Synchronized
    fun logPlay(
        trackId: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        timeMs: Long = System.currentTimeMillis()
    ) {
        val entries = readAll().toMutableList()
        entries.add(Entry(timeMs, trackId, title, artist, album, durationMs))
        val cutoff = timeMs - RETENTION_DAYS * DAY_MS
        val kept = entries.filter { it.timeMs >= cutoff }
        val trimmed = if (kept.size > MAX_ENTRIES) kept.takeLast(MAX_ENTRIES) else kept
        writeAll(trimmed)
    }

    /**
     * Merge plays from elsewhere (the user's account) into the phone's log.
     * Duplicates (same time + track) are dropped; phone retention applies.
     * Returns how many plays were new to the phone.
     */
    @Synchronized
    fun mergeIn(other: List<Entry>, nowMs: Long = System.currentTimeMillis()): Int {
        val local = readAll()
        val merged = HistorySync.merge(local, other)
        val cutoff = nowMs - RETENTION_DAYS * DAY_MS
        val kept = merged.filter { it.timeMs >= cutoff }
        val trimmed = if (kept.size > MAX_ENTRIES) kept.takeLast(MAX_ENTRIES) else kept
        val added = trimmed.size - local.size
        if (trimmed != local) writeAll(trimmed)
        return added.coerceAtLeast(0)
    }

    /** Replace the phone's log (used when another account signs in on this phone). */
    @Synchronized
    fun replaceAll(entries: List<Entry>) = writeAll(HistorySync.merge(entries, emptyList()))

    /** All logged entries, oldest first. */
    @Synchronized
    fun getEntries(): List<Entry> = readAll()

    /**
     * Weekly summaries, newest week first. A week with no plays does not
     * appear. Minutes are estimated from track durations.
     */
    @Synchronized
    fun getWeeks(): List<WeekStats> {
        val entries = readAll()
        if (entries.isEmpty()) return emptyList()
        val zone = ZoneId.systemDefault()
        val grouped = entries.groupBy { weekStartMs(it.timeMs, zone) }
        val labelFmt = DateTimeFormatter.ofPattern("MMM d", Locale.US)
        return grouped.entries.sortedByDescending { it.key }.map { (weekStart, weekEntries) ->
            val startDate = Instant.ofEpochMilli(weekStart).atZone(zone).toLocalDate()
            val endDate = startDate.plusDays(6)
            val label = "${labelFmt.format(startDate)} – ${labelFmt.format(endDate)}"
            val minutes = (weekEntries.sumOf { it.durationMs } / 60_000L).toInt()
            val topSong = weekEntries.groupingBy { it.title }.eachCount()
                .maxByOrNull { it.value }?.key
            val topArtist = weekEntries.groupingBy { it.artist }.eachCount()
                .maxByOrNull { it.value }?.key
            WeekStats(
                weekStartMs = weekStart,
                label = label,
                minutes = minutes,
                plays = weekEntries.size,
                topSong = topSong,
                topArtist = topArtist
            )
        }
    }

    /** Epoch millis of the Monday (00:00 local) of the week containing [timeMs]. */
    fun weekStartMs(timeMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val date = Instant.ofEpochMilli(timeMs).atZone(zone).toLocalDate()
        // DayOfWeek: Monday = 1 .. Sunday = 7.
        val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
        return monday.atStartOfDay(zone).toInstant().toEpochMilli()
    }

    // ---- File I/O (tiny hand-rolled JSON for the fixed entry shape) ----

    private fun readAll(): List<Entry> {
        val f = file
        if (!f.exists()) return emptyList()
        val text = try {
            f.readText()
        } catch (_: Exception) {
            return emptyList()
        }
        return parseEntries(text)
    }

    private fun writeAll(entries: List<Entry>) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(toJson(entries))
            tmp.renameTo(file)
        } catch (_: Exception) {
            // A failed write must never crash playback; the log is best-effort.
        }
    }

    internal fun toJson(entries: List<Entry>): String = buildString {
        append('[')
        entries.forEachIndexed { i, e ->
            if (i > 0) append(',')
            append("""{"t":${e.timeMs},"id":${jsonString(e.trackId)},"n":${jsonString(e.title)},"a":${jsonString(e.artist)},"al":${jsonString(e.album)},"d":${e.durationMs}}""")
        }
        append(']')
    }

    private fun jsonString(value: String): String {
        val sb = StringBuilder("\"")
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) sb.append(String.format("\\u%04x", ch.code))
                    else sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Minimal parser for the exact array-of-objects shape written above. */
    internal fun parseEntries(text: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var i = 0
        val n = text.length
        fun skipWs() {
            while (i < n && text[i].isWhitespace()) i++
        }
        fun parseString(): String? {
            if (i >= n || text[i] != '"') return null
            i++
            val sb = StringBuilder()
            while (i < n) {
                val c = text[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= n) return null
                        when (text[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > n) return null
                                sb.append(text.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: return null)
                                i += 4
                            }
                            else -> return null
                        }
                    }
                    else -> sb.append(c)
                }
            }
            return null
        }
        fun parseLong(): Long? {
            val start = i
            while (i < n && (text[i].isDigit() || text[i] == '-')) i++
            if (start == i) return null
            return text.substring(start, i).toLongOrNull()
        }
        skipWs()
        if (i >= n || text[i] != '[') return emptyList()
        i++
        while (true) {
            skipWs()
            if (i >= n) return out
            if (text[i] == ']') return out
            if (text[i] == ',') {
                i++
                continue
            }
            if (text[i] != '{') return out
            i++
            var t: Long? = null
            var id: String? = null
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            var d: Long? = null
            while (true) {
                skipWs()
                if (i >= n) return out
                if (text[i] == '}') {
                    i++
                    break
                }
                if (text[i] == ',') {
                    i++
                    continue
                }
                val key = parseString() ?: return out
                skipWs()
                if (i >= n || text[i] != ':') return out
                i++
                skipWs()
                when (key) {
                    "t" -> t = parseLong()
                    "d" -> d = parseLong()
                    "id" -> id = parseString()
                    "n" -> title = parseString()
                    "a" -> artist = parseString()
                    "al" -> album = parseString()
                    else -> {
                        // Unknown key: skip a string or number value.
                        if (i < n && text[i] == '"') parseString() else parseLong()
                    }
                }
            }
            if (t != null && id != null && title != null && artist != null && album != null && d != null) {
                out.add(Entry(t, id, title, artist, album, d))
            }
        }
    }
}
