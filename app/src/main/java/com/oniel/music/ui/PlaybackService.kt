package com.oniel.music.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.oniel.music.net.NetClients
import com.oniel.music.net.Tailscale
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.oniel.music.data.AuthStore
import com.oniel.music.data.Crossfade
import com.oniel.music.data.SettingsStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground playback service. Declared explicitly in AndroidManifest.xml.
 * The UI talks to it through a MediaController (see PlayerController).
 *
 * 1. Auth on audio requests. Jellyfin 12.2.0's universal stream endpoint
 *    needs the `Authorization: MediaBrowser Token="..."` header. Both
 *    players share one [OkHttpDataSource.Factory] that carries it (built on
 *    NetClients.http, so built-in Tailscale routing applies to streams).
 *
 * 2. Crossfade — two players that take turns (v1.0.3 rewrite).
 *
 *    The session always points at the ACTIVE player. When the active
 *    player is N seconds from the end of its track (N = saved crossfade
 *    length, 0-12 s) and there is a next track, the SPARE player is given
 *    the same queue, positioned at the next track, and started at volume 0.
 *    Over N seconds the active player fades 1 -> 0 and the spare 0 -> 1.
 *    Then the players SWAP: the session is pointed at the spare (which is
 *    already playing the next track), and the old one is stopped and
 *    becomes the new spare.
 *
 *    Why the rewrite: v1.0.2 kept one main player and, at the end of the
 *    fade, SEEKED it to where the second player was. Seeking needs a
 *    seekable stream, and the old audio URL transcoded every non-MP3 file
 *    into an unseekable stream with no known duration — so the overlap
 *    never even started (duration unknown) and the hand-off could not
 *    have worked anyway. Swapping players needs no seek at all.
 *
 *    The track's length comes from the player when the stream reports
 *    one, and otherwise from Jellyfin's RunTimeTicks (sent in the
 *    MediaItem extras by PlayerController), so a transcoded stream still
 *    crossfades.
 *
 *    The user skipping, seeking or pausing during the fade cancels it: the
 *    spare stops and the active player goes back to full volume. Repeat-one
 *    never crossfades (it would fade a song into itself).
 *
 * 3. Smart crossfade (v1.0.4). Each player has a [LevelMeter] in its audio
 *    chain. In the last 15 s of a track, if the outgoing audio goes quiet
 *    (fade-out / trailing silence) the overlap starts right away instead
 *    of waiting for the timer. The incoming player skips leading silence
 *    for the length of the fade. The fade curve is equal-power. Two
 *    consecutive tracks from the same album are never crossfaded.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    companion object {
        /** args: [EXTRA_AUTH_HEADER] = full `Authorization` header value. */
        const val ACTION_SET_AUTH_HEADER = "com.oniel.music.SET_AUTH_HEADER"
        const val EXTRA_AUTH_HEADER = "auth_header"

        /** args: [EXTRA_CROSSFADE_SECONDS] = Int seconds, 0-12. */
        const val ACTION_SET_CROSSFADE_SECONDS = "com.oniel.music.SET_CROSSFADE_SECONDS"
        const val EXTRA_CROSSFADE_SECONDS = "crossfade_seconds"

        /** MediaMetadata extras key: track length from Jellyfin, in ms. */
        const val EXTRA_RUNTIME_MS = "runtime_ms"
        /** MediaMetadata extras key: the track's album id (same album = no crossfade). */
        const val EXTRA_ALBUM_ID = "album_id"

        private const val CHECK_INTERVAL_MS = 200L
        private const val RAMP_TICK_MS = 50L
    }

    /** The player the session (and so the UI and notification) points at. */
    private var player: ExoPlayer? = null
    /** The other player: idle, or fading in the next track. */
    private var spare: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    /** Each player's level meter (smart crossfade). */
    private val meters = HashMap<ExoPlayer, LevelMeter>()

    /** Shared by both players: every audio request carries the header. */
    private var httpFactory: OkHttpDataSource.Factory? = null

    private var crossfadeSeconds: Int = Crossfade.DEFAULT_SECONDS

    private val handler = Handler(Looper.getMainLooper())

    // Fade state. fadeOut is the outgoing player until the swap, then null;
    // fadeIn is the incoming player for the whole fade.
    private var fading = false
    private var fadeOut: ExoPlayer? = null
    private var fadeIn: ExoPlayer? = null
    private var fadeStartMs = 0L
    private var fadeTotalMs = 0L

    private val sessionCallback = object : MediaSession.Callback {
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_SET_AUTH_HEADER -> {
                    val header = args.getString(EXTRA_AUTH_HEADER)
                    if (!header.isNullOrEmpty()) {
                        httpFactory?.setDefaultRequestProperties(
                            mapOf("Authorization" to header)
                        )
                    }
                }
                ACTION_SET_CROSSFADE_SECONDS -> {
                    crossfadeSeconds = Crossfade.clampSeconds(
                        args.getInt(EXTRA_CROSSFADE_SECONDS, Crossfade.DEFAULT_SECONDS)
                    )
                    if (crossfadeSeconds == 0) cancelCrossfade()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    /** Attached to whichever player is active. */
    private val activeListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // A new track on the active player: its loudness starts over.
            // (During a fade the active player is the outgoing one, whose
            // meter no longer matters.)
            if (!fading) player?.let { meters[it]?.requestTrackReset() }
            if (!fading) return
            if (fadeOut != null && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // The outgoing track ended a little before our estimate:
                // swap now, and the incoming one keeps ramping up.
                swapPlayers()
            } else if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // The user jumped to another track.
                cancelCrossfade()
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) cancelCrossfade()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // A pause (not a buffering stall) during the fade cancels it.
            if (!playWhenReady) cancelCrossfade()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED && fading && fadeOut != null) swapPlayers()
        }
    }

    /** Attached to the spare: if the next track fails to load, drop the fade. */
    private val spareListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            if (fading && fadeOut != null) cancelCrossfade()
        }
    }

    /** Polls for the moment a crossfade is due (last N seconds of a track). */
    private val checkRunnable = object : Runnable {
        override fun run() {
            maybeStartCrossfade()
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    /** Steps the volume ramp while a fade runs. */
    private val rampRunnable = object : Runnable {
        override fun run() {
            if (!fading) return
            val elapsed = SystemClock.uptimeMillis() - fadeStartMs
            val (outVolume, inVolume) = Crossfade.volumes(elapsed, fadeTotalMs)
            fadeOut?.volume = outVolume
            fadeIn?.volume = inVolume
            if (elapsed >= fadeTotalMs) {
                if (fadeOut != null) swapPlayers()
                endFade()
            } else {
                handler.postDelayed(this, RAMP_TICK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Start built-in Tailscale (if on) before any stream is opened.
        Tailscale.startIfEnabled(this)
        // Streams go through the app's shared OkHttp client, so tailnet
        // hosts use built-in Tailscale like every other request.
        val factory = OkHttpDataSource.Factory(NetClients.http)
        httpFactory = factory
        // Read the saved login and crossfade length now, so the first
        // track already carries the auth header and the saved overlap.
        // Guarded: a slow or corrupt store must never stall the service.
        runBlocking {
            val token = withTimeoutOrNull(2000L) {
                try {
                    AuthStore(this@PlaybackService).load()?.token
                } catch (e: Exception) {
                    null
                }
            }
            if (!token.isNullOrEmpty()) {
                factory.setDefaultRequestProperties(
                    mapOf("Authorization" to "MediaBrowser Token=\"$token\"")
                )
            }
            val seconds = withTimeoutOrNull(2000L) {
                try {
                    SettingsStore(this@PlaybackService).loadCrossfadeSeconds()
                } catch (e: Exception) {
                    null
                }
            }
            if (seconds != null) crossfadeSeconds = seconds
        }
        val first = buildPlayer()
        first.addListener(activeListener)
        player = first
        val second = buildPlayer()
        second.addListener(spareListener)
        spare = second
        mediaSession = MediaSession.Builder(this, first)
            .setCallback(sessionCallback)
            .build()
        handler.post(checkRunnable)
    }

    private fun buildPlayer(): ExoPlayer {
        val factory = httpFactory ?: OkHttpDataSource.Factory(NetClients.http)
        val meter = LevelMeter()
        // Same audio sink as ExoPlayer's default, plus the level meter.
        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(arrayOf(meter))
                .build()
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Neither player takes audio focus: if the incoming player took it,
        // Android would tell the outgoing one to pause mid-fade.
        val built = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
            .setAudioAttributes(attributes, false)
            .setHandleAudioBecomingNoisy(true)
            .build()
        meters[built] = meter
        return built
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(rampRunnable)
        mediaSession?.release()
        mediaSession = null
        player?.release()
        spare?.release()
        player = null
        spare = null
        meters.clear()
        super.onDestroy()
    }

    // --- Crossfade -----------------------------------------------------------

    /** Track length: from the stream when known, else Jellyfin's runtime. */
    private fun durationOf(p: ExoPlayer): Long {
        val fromMetadata = p.currentMediaItem?.mediaMetadata?.extras
            ?.getLong(EXTRA_RUNTIME_MS, 0L) ?: 0L
        return Crossfade.effectiveDurationMs(p.duration, fromMetadata)
    }

    private fun albumIdOf(item: MediaItem?): String? =
        item?.mediaMetadata?.extras?.getString(EXTRA_ALBUM_ID)

    private fun maybeStartCrossfade() {
        val main = player ?: return
        if (fading || !main.isPlaying) return
        if (crossfadeSeconds <= 0) return
        if (main.repeatMode == Player.REPEAT_MODE_ONE) return
        val nextIndex = main.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return
        // Same album: play straight through (gapless), no fade.
        val nextItem = try { main.getMediaItemAt(nextIndex) } catch (e: Exception) { return }
        if (Crossfade.sameAlbum(albumIdOf(main.currentMediaItem), albumIdOf(nextItem))) return
        val duration = durationOf(main)
        // Never fade longer than half the track (short tracks, intros).
        val crossfadeMs = minOf(crossfadeSeconds * 1000L, duration / 2)
        val position = main.currentPosition
        val timed = Crossfade.shouldStart(position, duration, crossfadeMs, true)
        val meter = meters[main]
        val quietTail = meter != null && Crossfade.shouldStartOnQuietTail(
            position, duration, meter.quietForMs, true, meter.loudnessKnown
        )
        if (!timed && !quietTail) return
        val fadeMs = Crossfade.fadeLengthMs(crossfadeMs, position, duration)
        if (fadeMs <= 0L) return
        startCrossfade(main, nextIndex, fadeMs)
    }

    private fun startCrossfade(main: ExoPlayer, nextIndex: Int, crossfadeMs: Long) {
        val incoming = spare ?: return
        val items = (0 until main.mediaItemCount).map { main.getMediaItemAt(it) }
        if (nextIndex !in items.indices) return
        fading = true
        fadeOut = main
        fadeIn = incoming
        fadeTotalMs = crossfadeMs
        fadeStartMs = SystemClock.uptimeMillis()
        incoming.volume = 0f
        meters[incoming]?.requestTrackReset()
        // Skip the incoming track's leading silence while it fades in
        // (switched off again when the fade ends).
        incoming.skipSilenceEnabled = true
        incoming.repeatMode = main.repeatMode
        incoming.setMediaItems(items, nextIndex, 0L)
        incoming.shuffleModeEnabled = main.shuffleModeEnabled
        incoming.prepare()
        incoming.playWhenReady = true
        handler.post(rampRunnable)
    }

    /**
     * The incoming player becomes the active one: the session (UI, lock
     * screen, notification) now follows it; the outgoing player stops and
     * becomes the spare.
     */
    private fun swapPlayers() {
        val outgoing = fadeOut ?: return
        val incoming = fadeIn ?: return
        fadeOut = null
        outgoing.removeListener(activeListener)
        incoming.removeListener(spareListener)
        incoming.addListener(activeListener)
        outgoing.addListener(spareListener)
        player = incoming
        spare = outgoing
        mediaSession?.player = incoming
        outgoing.stop()
        outgoing.clearMediaItems()
        outgoing.volume = 1f
        // The ramp keeps running on the incoming player until fadeTotalMs.
    }

    /** Fade finished: everything at full volume, nothing pending. */
    private fun endFade() {
        fading = false
        handler.removeCallbacks(rampRunnable)
        fadeIn?.volume = 1f
        fadeIn?.skipSilenceEnabled = false
        fadeOut = null
        fadeIn = null
    }

    /** User skipped/seeked/paused, or the next track failed to load. */
    private fun cancelCrossfade() {
        if (!fading) return
        handler.removeCallbacks(rampRunnable)
        val outgoing = fadeOut
        if (outgoing != null) {
            // Before the swap: drop the incoming track, restore the outgoing.
            spare?.stop()
            spare?.clearMediaItems()
            spare?.volume = 1f
            spare?.skipSilenceEnabled = false
            outgoing.volume = 1f
        } else {
            // After the swap: just finish the fade-in at full volume.
            player?.volume = 1f
            player?.skipSilenceEnabled = false
        }
        fading = false
        fadeOut = null
        fadeIn = null
    }
}
