package com.oniel.music.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crossfade between tracks: pure timing/volume maths, no Android types,
 * so it is unit-tested on the JVM (see DataLayerTest).
 *
 * v1.0.4 "smart crossfade":
 * - Equal-power curve: the outgoing track follows cos, the incoming sin,
 *   so the combined loudness stays steady through the overlap (a linear
 *   fade dips about 3 dB in the middle).
 * - Fade on the music, not on silence: PlaybackService measures the
 *   outgoing track's level as it plays. If, in the last
 *   [TAIL_WINDOW_MS] of the track, it stays quiet (fade-out or trailing
 *   silence) for [MIN_QUIET_MS], the crossfade starts right then instead
 *   of waiting for the timer. The incoming track skips its leading
 *   silence during the fade.
 * - Same album plays straight through: consecutive tracks from one album
 *   (live albums, medleys) are never crossfaded.
 */
object Crossfade {
    const val MIN_SECONDS = 0
    const val MAX_SECONDS = 12
    const val DEFAULT_SECONDS = 3

    /** Only the last 15 s of a track can trigger an early (quiet-tail) start. */
    const val TAIL_WINDOW_MS = 15_000L
    /** The tail must stay quiet this long before the fade starts early. */
    const val MIN_QUIET_MS = 800L
    /** Shortest fade, used when the quiet tail is found very near the end. */
    const val MIN_FADE_MS = 1_000L
    /** Track loudness is trusted only after this much of it has been heard. */
    const val MIN_HEARD_MS = 10_000L
    /** "Quiet" = 20 dB below the track's own average level... */
    const val QUIET_RELATIVE = 0.1f
    /** ...or below about -50 dBFS, whatever the track. */
    const val QUIET_ABSOLUTE = 0.003f

    fun clampSeconds(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    /**
     * Timed start: true when the playhead has reached the last
     * [crossfadeMs] of the track (but the track has not ended yet), a
     * crossfade is configured, there is a next track and the duration is known.
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
     * Early start on a quiet tail: true when the playhead is inside the
     * last [TAIL_WINDOW_MS] of the track and the outgoing audio has been
     * quiet for at least [MIN_QUIET_MS] (and enough of the track has been
     * heard to know what "quiet" means for it).
     */
    fun shouldStartOnQuietTail(
        positionMs: Long,
        durationMs: Long,
        quietForMs: Long,
        hasNext: Boolean,
        loudnessKnown: Boolean
    ): Boolean {
        if (!hasNext || !loudnessKnown || durationMs <= 0L) return false
        if (positionMs < 0L || positionMs >= durationMs) return false
        if (positionMs < durationMs - TAIL_WINDOW_MS) return false
        return quietForMs >= MIN_QUIET_MS
    }

    /** Is [level] (RMS, 0..1) quiet for a track whose average level is [trackAverage]? */
    fun isQuiet(level: Float, trackAverage: Float): Boolean =
        level < QUIET_ABSOLUTE || (trackAverage > 0f && level < trackAverage * QUIET_RELATIVE)

    /**
     * Fade length: the configured length, shortened when less of the
     * track is left, but never under [MIN_FADE_MS] (if the outgoing track
     * ends first, the incoming one simply finishes its fade-in).
     */
    fun fadeLengthMs(crossfadeMs: Long, positionMs: Long, durationMs: Long): Long {
        if (crossfadeMs <= 0L) return 0L
        val remaining = (durationMs - positionMs).coerceAtLeast(0L)
        return minOf(crossfadeMs, maxOf(MIN_FADE_MS, remaining))
    }

    /** Setting: crossfade between songs of the same album too (default on). */
    fun fadeWithinAlbums(ctx: android.content.Context): Boolean =
        ctx.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)
            .getBoolean("fade_within_albums", true)

    fun setFadeWithinAlbums(ctx: android.content.Context, on: Boolean) =
        ctx.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("fade_within_albums", on).apply()

    /** Two tracks from the same album (played straight through unless [fadeWithinAlbums]). */
    fun sameAlbum(albumA: String?, albumB: String?): Boolean =
        !albumA.isNullOrEmpty() && albumA == albumB

    /**
     * The track length to time the fade against: the player's own
     * duration when the stream reports one (> 0), otherwise the server's
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
     * Equal-power volumes for [elapsedMs] into a [totalMs] crossfade:
     * first is the outgoing track's volume (1 -> 0, cosine), second is the
     * incoming track's volume (0 -> 1, sine). out² + in² = 1 throughout,
     * so loudness holds steady. Clamped at both ends, so calling it late
     * still yields the finished volumes (0, 1).
     */
    fun volumes(elapsedMs: Long, totalMs: Long): Pair<Float, Float> {
        if (totalMs <= 0L) return 1f to 1f
        val t = (elapsedMs.toDouble() / totalMs.toDouble()).coerceIn(0.0, 1.0)
        if (t <= 0.0) return 1f to 0f
        if (t >= 1.0) return 0f to 1f
        val angle = t * PI / 2.0
        return cos(angle).toFloat() to sin(angle).toFloat()
    }
}
