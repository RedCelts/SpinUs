package com.oniel.music.data

/**
 * Crossfade between tracks: pure timing/volume maths, no Android types,
 * so it is unit-tested on the JVM (see DataLayerTest).
 *
 * How it works in the app: the queue always keeps playing (it never
 * stops at the end of a track). When the current track is [seconds]
 * from its end and there is a next track, a second player starts the
 * next track at volume 0 while the first fades out and the second fades
 * in over those seconds; when the first track ends, the main player
 * auto-advances to the next track and is seeked to where the second
 * player already is. 0 seconds means no crossfade: tracks play back
 * to back with no silence (ExoPlayer's native gapless behaviour on a
 * concatenated queue).
 */
object Crossfade {
    const val MIN_SECONDS = 0
    const val MAX_SECONDS = 12
    const val DEFAULT_SECONDS = 3

    fun clampSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /**
     * Should the overlap start now? True only when a crossfade is
     * configured, there is a next track, the current track has a known
     * duration, and the playhead has reached the last [crossfadeMs] of
     * the track (but the track has not ended yet).
     */
    fun shouldStart(
        positionMs: Long,
        durationMs: Long,
        crossfadeMs: Long,
        hasNext: Boolean
    ): Boolean {
        if (!hasNext || crossfadeMs <= 0L || durationMs <= 0L) return false
        if (positionMs < 0L || positionMs >= durationMs) return false
        return positionMs >= durationMs - crossfadeMs
    }

    /**
     * The track length to time the fade against: the player's own
     * duration when the stream reports one (> 0), otherwise Jellyfin's
     * runtime for the track (a transcoded stream reports no length —
     * ExoPlayer returns C.TIME_UNSET, a large negative number). 0 when
     * neither is known, which disables the fade for that track.
     */
    fun effectiveDurationMs(playerDurationMs: Long, runtimeMs: Long): Long = when {
        playerDurationMs > 0L -> playerDurationMs
        runtimeMs > 0L -> runtimeMs
        else -> 0L
    }

    /**
     * Linear ramp volumes for [elapsedMs] into a [totalMs] crossfade:
     * first is the outgoing track's volume (1 -> 0), second is the
     * incoming track's volume (0 -> 1). Clamped at both ends, so calling
     * it late still yields the finished volumes (0, 1).
     */
    fun volumes(elapsedMs: Long, totalMs: Long): Pair<Float, Float> {
        if (totalMs <= 0L) return 1f to 1f
        val t = (elapsedMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
        return (1f - t) to t
    }
}
