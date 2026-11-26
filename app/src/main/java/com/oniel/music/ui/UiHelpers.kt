package com.oniel.music.ui

/** Ticks (100 ns, the unit the models use) to milliseconds. */
fun ticksToMs(ticks: Long): Long = ticks / 10_000L

/** Milliseconds to ticks. */
fun msToTicks(ms: Long): Long = ms * 10_000L

/** "3:24" style duration. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:%02d".format(seconds)
}

/** "1 hr 12 min" / "38 min" style length for whole albums. */
fun formatLongDuration(ms: Long): String {
    val totalMinutes = (ms / 60_000).coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours hr $minutes min" else "$minutes min"
}

/** Parse an ISO-8601 date string to epoch milliseconds, or null. */
fun parseIsoToMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            java.time.Instant.parse(iso).toEpochMilli()
        } catch (e2: Exception) {
            null
        }
    }
}
