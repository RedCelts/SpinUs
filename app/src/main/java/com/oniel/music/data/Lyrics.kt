package com.oniel.music.data

/**
 * Pure lyric handling. The the server lyrics endpoint returns no "is
 * synced" flag, so synced-ness is derived from the timestamps: a song
 * is synced when at least two lines carry distinct start times.
 *
 * Contract (mirrors [SpinUsClient.getLyrics]):
 * - null  -> the server has no lyrics for the track (HTTP 404).
 * - empty -> the track has lyrics, but only plain text (no usable
 *            timestamps); the scrolling views treat this as "no lyrics".
 * - non-empty -> synced lines, sorted by start time.
 */

/** One raw lyric line as parsed from the server JSON. */
data class RawLyricLine(
    val text: String,
    /** Start time in 100-ns ticks, or null when the line has no timestamp. */
    val startTicks: Long?
)

object Lyrics {

    private const val TICKS_PER_MS = 10_000L

    /**
     * Classify raw lyric lines into synced lines, or an empty list for
     * plain-text-only lyrics. Blank lines (including the trailing empty
     * line the server appends to plain lyrics) are dropped first.
     */
    fun classify(rawLines: List<RawLyricLine>): List<LyricLine> {
        val lines = rawLines.filter { it.text.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val distinctStarts = lines.mapNotNull { it.startTicks }.distinct()
        if (distinctStarts.size < 2) return emptyList()
        return lines.filter { it.startTicks != null }
            .sortedBy { it.startTicks }
            .map { LyricLine(startMs = it.startTicks!! / TICKS_PER_MS, text = it.text) }
    }
}
