package com.oniel.music.ui

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.oniel.music.data.Crossfade
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Pass-through audio processor that measures how loud a player's output
 * is, for the smart crossfade. It changes no samples.
 *
 * Runs on the playback thread; PlaybackService reads [quietForMs] and
 * [loudnessKnown] from the main thread (volatile fields). Levels are RMS
 * on a 0..1 scale. Measured audio is slightly ahead of what is heard
 * (by the output buffer, a few hundred ms), which only makes the
 * quiet-tail detection react a touch earlier.
 *
 * - Short-term level: exponential average with a ~300 ms time constant.
 * - Track average: mean power since the track started ([requestTrackReset]
 *   is called on every track change).
 * - [quietForMs]: how long (in audio time) the short-term level has stayed
 *   quiet relative to the track average ([Crossfade.isQuiet]).
 */
@UnstableApi
class LevelMeter : BaseAudioProcessor() {

    @Volatile var quietForMs: Long = 0L
        private set
    @Volatile var loudnessKnown: Boolean = false
        private set

    @Volatile private var resetRequested = false

    // Playback-thread state.
    private var shortLevel = -1f
    private var trackSumSquares = 0.0
    private var trackSamples = 0L
    private var heardMs = 0.0
    private var quietMsAcc = 0.0

    /** Call on a track change: the next audio belongs to a new track. */
    fun requestTrackReset() {
        resetRequested = true
        quietForMs = 0L
        loudnessKnown = false
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            else -> AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        measure(inputBuffer)
        // Pass the audio through untouched.
        val out = replaceOutputBuffer(size)
        out.put(inputBuffer)
        out.flip()
    }

    private fun measure(buffer: ByteBuffer) {
        if (resetRequested) {
            resetRequested = false
            resetTrack()
        }
        val format = inputAudioFormat
        if (format.sampleRate <= 0 || format.channelCount <= 0) return
        val view = buffer.duplicate().order(ByteOrder.nativeOrder())
        var sumSquares = 0.0
        var samples = 0
        if (format.encoding == C.ENCODING_PCM_FLOAT) {
            while (view.remaining() >= 4) {
                val s = view.float.toDouble()
                sumSquares += s * s
                samples++
            }
        } else {
            while (view.remaining() >= 2) {
                val s = view.short / 32768.0
                sumSquares += s * s
                samples++
            }
        }
        if (samples == 0) return
        val chunkMs = samples.toDouble() / format.channelCount / format.sampleRate * 1000.0
        val chunkLevel = sqrt(sumSquares / samples).toFloat()

        shortLevel = if (shortLevel < 0f) {
            chunkLevel
        } else {
            val alpha = (chunkMs / (SHORT_TIME_CONSTANT_MS + chunkMs)).toFloat()
            shortLevel + alpha * (chunkLevel - shortLevel)
        }

        trackSumSquares += sumSquares
        trackSamples += samples
        heardMs += chunkMs
        val average = sqrt(trackSumSquares / trackSamples).toFloat()

        quietMsAcc = if (Crossfade.isQuiet(shortLevel, average)) quietMsAcc + chunkMs else 0.0
        quietForMs = quietMsAcc.toLong()
        loudnessKnown = heardMs >= Crossfade.MIN_HEARD_MS
    }

    private fun resetTrack() {
        shortLevel = -1f
        trackSumSquares = 0.0
        trackSamples = 0L
        heardMs = 0.0
        quietMsAcc = 0.0
        quietForMs = 0L
        loudnessKnown = false
    }

    /** After a seek the short-term level restarts; the track average is kept. */
    override fun onFlush() {
        shortLevel = -1f
        quietMsAcc = 0.0
        quietForMs = 0L
    }

    override fun onReset() {
        resetTrack()
    }

    private companion object {
        const val SHORT_TIME_CONSTANT_MS = 300.0
    }
}
