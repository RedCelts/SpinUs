package com.oniel.music.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.oniel.music.data.Crossfade
import com.oniel.music.data.HistorySync
import com.oniel.music.data.SpinUsClient
import com.oniel.music.data.LyricLine
import com.oniel.music.data.PlayLog
import com.oniel.music.data.SettingsStore
import com.oniel.music.data.Track
import com.oniel.music.data.WeekStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant

/**
 * Owns playback state for the whole app. Talks to PlaybackService through a
 * MediaController; the service owns the ExoPlayer. Reports progress to
 * the server (Playing / Played / Stopped) so play counts and dates update,
 * and records finished plays to the on-device PlayLog at >= 60% heard or
 * at the end of the track.
 *
 * MainActivity creates one instance (viewModel()) and passes it to every
 * screen that plays music. Call attachClient() once a client is available.
 */
/** Minimum real listening time before a play counts. */
private const val MIN_PLAY_MS = 30_000L

class PlayerController(application: Application) : AndroidViewModel(application) {

    private var client: SpinUsClient? = null
    private var controller: MediaController? = null
    private var pendingPlay: (() -> Unit)? = null

    private val playLog = PlayLog(File(application.filesDir, "playlog"))

    // ---- Listening-history sync with the SpinUs account ----

    /** Last successful history sync (epoch ms), 0 = not yet this session. */
    var historySyncedAtMs by mutableStateOf(0L)
        private set
    /** Last sync error, null when the last attempt worked. */
    var historySyncError by mutableStateOf<String?>(null)
        private set
    var historySyncing by mutableStateOf(false)
        private set
    /** Bumped whenever plays arrive from the account, so Stats reloads. */
    var historyVersion by mutableStateOf(0)
        private set
    private val historyMutex = Mutex()
    private var pendingHistoryUpload: Job? = null

    /** The queue the user is playing through, and where we are in it. */
    var queue by mutableStateOf<List<Track>>(emptyList())
        private set
    var currentIndex by mutableStateOf(-1)
        private set

    val currentTrack: Track?
        get() = queue.getOrNull(currentIndex)

    var isPlaying by mutableStateOf(false)
        private set
    var positionMs by mutableStateOf(0L)
        private set
    var durationMs by mutableStateOf(0L)
        private set
    var shuffleEnabled by mutableStateOf(false)
        private set
    /** 0 = off, 1 = repeat all, 2 = repeat one. */
    var repeatMode by mutableStateOf(0)
        private set
    /**
     * Crossfade length in seconds (0-12, default 3), persisted in
     * SettingsStore. 0 = no crossfade, tracks play back to back. The
     * queue always keeps playing either way; this only sets how the
     * end of one track overlaps the start of the next.
     */
    var crossfadeSeconds by mutableStateOf(Crossfade.DEFAULT_SECONDS)
        private set

    private val settingsStore = SettingsStore(application)

    /**
     * Synced lyrics for the current track, or null when there are none.
     * Only a non-empty (synced) list ever shows: a null result means no
     * lyrics and an empty list means plain-text only, and both stay hidden.
     */
    var syncedLyrics by mutableStateOf<List<LyricLine>?>(null)
        private set

    /** The lyric line under the playhead, or null. */
    val currentLyricLine: LyricLine?
        get() {
            val lines = syncedLyrics ?: return null
            return lines.lastOrNull { it.startMs <= positionMs }
        }

    private var loggedTrackId: String? = null
    /** Milliseconds actually listened to [listenedTrackId] (for the 30 s rule). */
    private var listenedMs = 0L
    private var listenedTrackId: String? = null
    private var lastTickAt = 0L
    private var listenedDurationMs = 0L
    /** How plays end (skips < 20 s, completion) — feeds the Home rules. */
    private val listenSignals = com.oniel.music.data.ListenSignals(File(application.filesDir, "signals"))

    /** Records how the song that was playing ended (skip / how much was heard). */
    private fun finishListen() {
        val id = listenedTrackId ?: return
        val heard = listenedMs
        val total = listenedDurationMs
        listenedTrackId = null
        if (heard <= 0L) return
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try { listenSignals.record(id, heard, total) } catch (e: Exception) { }
        }
    }

    /** Skip / completion data for the recommendation rules. */
    suspend fun signalSnapshot(): com.oniel.music.data.SignalSnapshot =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { listenSignals.snapshot() }
    private var lastProgressReportMs: Long = 0L

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val index = controller?.currentMediaItemIndex ?: -1
            if (index >= 0 && index != currentIndex) {
                val previous = currentTrack
                val previousPositionMs = positionMs
                currentIndex = index
                onTrackChanged(
                    previous = previous,
                    previousPositionMs = previousPositionMs,
                    previousFinished = reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )
            }
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) {
                onTrackEnded()
            }
        }

        // Playback started elsewhere (Android Auto): take over its queue so
        // the app shows the right song.
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            val c = controller ?: return
            val ids = (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId }
            if (ids.isEmpty() || ids == queue.map { it.id }) return
            val tracks = (0 until c.mediaItemCount).mapNotNull { c.getMediaItemAt(it).toTrackOrNull() }
            if (tracks.size != ids.size) return
            queue = tracks
            currentIndex = c.currentMediaItemIndex
        }
    }

    init {
        // Load the saved crossfade length (default 3s) and push it to the
        // service once it connects (see pushSettingsToService).
        viewModelScope.launch {
            try {
                crossfadeSeconds = settingsStore.loadCrossfadeSeconds()
            } catch (e: Exception) {
                // Corrupt settings must never break playback: keep the default.
            }
            pushSettingsToService()
        }
        // Playback (Media3) is NOT started here on purpose: creating the
        // MediaController binds to PlaybackService, and a failure there
        // must never take the whole app down at launch. The controller
        // connects lazily on the first play() instead (see ensureController).
        viewModelScope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                if (c.isPlaying) {
                    // Real listening time (not position: seeking ahead
                    // doesn't count toward the 30-second minimum).
                    val nowTick = android.os.SystemClock.elapsedRealtime()
                    val playingId = currentTrack?.id
                    if (playingId != listenedTrackId) {
                        finishListen()
                        listenedTrackId = playingId
                        listenedDurationMs = ticksToMs(currentTrack?.runTimeTicks ?: 0L)
                        listenedMs = 0L
                    } else if (lastTickAt > 0L) {
                        listenedMs += (nowTick - lastTickAt).coerceIn(0L, 2_000L)
                    }
                    lastTickAt = nowTick
                    positionMs = c.currentPosition
                    // A transcoded stream reports no length (TIME_UNSET):
                    // keep the server's runtime so the seek bar still works.
                    val streamDuration = c.duration
                    durationMs = if (streamDuration > 0) streamDuration
                        else ticksToMs(currentTrack?.runTimeTicks ?: 0L)
                    maybeLogPlay()
                    maybeReportProgress()
                } else {
                    lastTickAt = 0L
                }
            }
        }
    }

    fun attachClient(newClient: SpinUsClient) {
        client = newClient
        // Friends' apps ask for our stats over session messages.
        newClient.statsResponder = {
            if (StatsSharing.isEnabled(getApplication<Application>())) {
                com.oniel.music.data.ListeningStats.toJson(buildListeningSummary())
            } else null
        }
        pushSettingsToService()
    }

    /**
     * Pushes what only the UI knows into PlaybackService over custom
     * commands: the `Authorization` header for ExoPlayer's audio
     * requests (the stream URL carries the token too) and the
     * saved crossfade length. The service also reads both from storage
     * when it starts, so the first track is covered even before this
     * runs; this is the refresh path for sign-in and setting changes.
     * Safe to call before the controller connects (no-op until then).
     */
    private fun pushSettingsToService() {
        val c = controller ?: return
        val cl = client
        if (cl != null && cl.isLoggedIn) {
            val args = Bundle().apply {
                putString(PlaybackService.EXTRA_AUTH_HEADER, cl.authHeaderValue())
            }
            try {
                c.sendCustomCommand(
                    SessionCommand(PlaybackService.ACTION_SET_AUTH_HEADER, Bundle()),
                    args
                )
            } catch (e: Exception) {
                // Playback still works for the first track: the service
                // read the same token from storage at startup.
            }
        }
        val crossfadeArgs = Bundle().apply {
            putInt(PlaybackService.EXTRA_CROSSFADE_SECONDS, crossfadeSeconds)
        }
        try {
            c.sendCustomCommand(
                SessionCommand(PlaybackService.ACTION_SET_CROSSFADE_SECONDS, Bundle()),
                crossfadeArgs
            )
        } catch (e: Exception) {
            // The service read the same value from storage at startup.
        }
    }

    /** Crossfade length in seconds (0-12); persists and applies live. */
    fun setCrossfade(seconds: Int) {
        val value = Crossfade.clampSeconds(seconds)
        if (value == crossfadeSeconds) return
        crossfadeSeconds = value
        viewModelScope.launch {
            try {
                settingsStore.saveCrossfadeSeconds(value)
            } catch (e: Exception) {
                // Best-effort: the in-memory value still applies.
            }
        }
        pushSettingsToService()
    }

    /** Past weeks recorded on this device, newest first. */
    fun playLogWeeks(): List<WeekStats> = playLog.getWeeks()

    /** Every logged play, oldest first (used for the day streak). */
    fun playLogEntries(): List<PlayLog.Entry> = playLog.getEntries()

    /** Your stats from the play log, with library ids for photos and covers. */
    suspend fun buildListeningSummary(): com.oniel.music.data.ListeningSummary {
        val c = client
        val tracks = LibraryCache.best()?.tracks?.takeIf { it.isNotEmpty() }
            ?: try { c?.getTrackIndex() } catch (e: Exception) { null }
            ?: emptyList()
        return com.oniel.music.data.ListeningStats.build(
            userName = c?.userName.orEmpty(),
            entries = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { playLog.getEntries() },
            tracksById = tracks.associateBy { it.id }
        )
    }

    /** Stop playback and clear the queue (used on logout). */
    fun stopPlayback() {
        controller?.stop()
        queue = emptyList()
        currentIndex = -1
        isPlaying = false
        positionMs = 0L
        durationMs = 0L
        syncedLyrics = null
    }

    private var controllerConnecting = false

    /** Connects to PlaybackService on first use (not at launch). Safe to call repeatedly. */
    private fun ensureController() {
        if (controller != null || controllerConnecting) return
        controllerConnecting = true
        try {
            connectController()
        } catch (e: Exception) {
            // Playback is unavailable, but the app must keep working.
            controllerConnecting = false
        }
    }

    private fun connectController() {
        val context = getApplication<Application>()
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                controllerConnecting = false
                try {
                    controller = future.get()
                } catch (e: Exception) {
                    return@addListener
                }
                controller?.addListener(playerListener)
                pushSettingsToService()
                pendingPlay?.invoke()
                pendingPlay = null
            },
            ContextCompat.getMainExecutor(context)
        )
    }

    // --- Queue / transport ---------------------------------------------------

    /** Play [tracks] starting at [startIndex]; opens the queue in the player. */
    fun play(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        ensureController()
        if (controller == null) {
            pendingPlay = { doPlay(tracks, startIndex) }
        } else {
            doPlay(tracks, startIndex)
        }
    }

    private fun doPlay(tracks: List<Track>, startIndex: Int) {
        val c = controller ?: return
        val items = tracks.map { track -> track.toMediaItem() }
        queue = tracks
        currentIndex = startIndex.coerceIn(tracks.indices)
        loggedTrackId = null
        finishListen()
        c.setMediaItems(items, currentIndex, 0L)
        c.prepare()
        c.play()
        onTrackChanged()
    }

    /** Play a single track on its own. */
    fun playSingle(track: Track) = play(listOf(track), 0)

    fun playAt(index: Int) {
        val c = controller ?: return
        if (index !in queue.indices) return
        currentIndex = index
        loggedTrackId = null
        finishListen()
        c.seekTo(index, 0L)
        c.play()
        onTrackChanged()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            reportStopped(playedToCompletion = false)
        } else {
            c.play()
        }
    }

    fun next() {
        val c = controller ?: return
        if (c.hasNextMediaItem()) c.seekToNextMediaItem() else if (queue.isNotEmpty()) playAt(0)
    }

    fun previous() {
        val c = controller ?: return
        if (c.currentPosition > 3000L) {
            c.seekTo(0L)
        } else if (c.hasPreviousMediaItem()) {
            c.seekToPreviousMediaItem()
        }
    }

    fun seekTo(position: Long) {
        controller?.seekTo(position.coerceIn(0L, durationMs))
        positionMs = position.coerceIn(0L, durationMs)
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        controller?.shuffleModeEnabled = shuffleEnabled
    }

    fun cycleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        controller?.repeatMode = when (repeatMode) {
            1 -> Player.REPEAT_MODE_ALL
            2 -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // --- Server reporting + play log -------------------------------------------

    private fun onTrackChanged(
        previous: Track? = null,
        previousPositionMs: Long = 0L,
        previousFinished: Boolean = false
    ) {
        val track = currentTrack ?: return
        positionMs = 0L
        durationMs = ticksToMs(track.runTimeTicks)
        lastProgressReportMs = 0L
        viewModelScope.launch {
            val c = client ?: return@launch
            // Close the previous track's play session first (same coroutine,
            // so it is sent before the new Playing report replaces it).
            if (previous != null && previous.id != track.id) {
                try {
                    c.reportStopped(previous.id, msToTicks(previousPositionMs), previousFinished)
                } catch (e: Exception) {
                    // Best-effort.
                }
            }
            try {
                c.reportPlaying(track.id, 0L)
            } catch (e: Exception) {
                // Reporting is best-effort; playback must not fail over it.
            }
            try {
                val lines = c.getLyrics(track.id)
                syncedLyrics = if (lines.isNullOrEmpty()) null else lines
            } catch (e: Exception) {
                syncedLyrics = null
            }
        }
    }

    private fun onTrackEnded() {
        val track = currentTrack ?: return
        if (loggedTrackId != track.id && listenedEnough(track)) logPlayIfNeeded(track)
        reportStopped(playedToCompletion = true)
    }

    /**
     * A play counts (stats, history, server play count) once you've
     * actually listened 30 seconds — or nearly all of a shorter song.
     */
    private fun listenedEnough(track: Track): Boolean {
        if (listenedTrackId != track.id) return false
        val total = ticksToMs(track.runTimeTicks)
        val needed = if (total in 1 until MIN_PLAY_MS) total * 9 / 10 else MIN_PLAY_MS
        return listenedMs >= needed
    }

    private fun maybeLogPlay() {
        val track = currentTrack ?: return
        if (loggedTrackId == track.id) return
        if (listenedEnough(track)) logPlayIfNeeded(track)
    }

    /** Logs a play once per track (never twice, even at the end). */
    private fun logPlayIfNeeded(track: Track) {
        loggedTrackId = track.id
        viewModelScope.launch {
            try {
                playLog.logPlay(
                    trackId = track.id,
                    title = track.title,
                    artist = track.artists.firstOrNull() ?: track.albumArtist,
                    album = track.album,
                    durationMs = ticksToMs(track.runTimeTicks)
                )
                client?.markPlayed(track.id, Instant.now().toString())
            } catch (e: Exception) {
                // Best-effort.
            }
            scheduleHistoryUpload()
        }
    }

    /**
     * Sync the listening history with the account now: plays on this
     * phone go up, plays from other phones / earlier installs come down.
     * Called on sign-in, on app start and from Settings.
     */
    fun syncHistoryNow() {
        viewModelScope.launch { syncHistory() }
    }

    /** After a play: upload within 2 minutes (several songs share one upload). */
    private fun scheduleHistoryUpload() {
        if (pendingHistoryUpload?.isActive == true) return
        pendingHistoryUpload = viewModelScope.launch {
            delay(HISTORY_UPLOAD_DELAY_MS)
            syncHistory()
        }
    }

    private suspend fun syncHistory() {
        val c = client ?: return
        if (!c.isLoggedIn) return
        historyMutex.withLock {
            historySyncing = true
            try {
                val added = withContext(Dispatchers.IO) {
                    // The phone's history belongs to one account. If a
                    // different user signed in, don't upload the previous
                    // user's plays into this account: start from theirs.
                    val prefs = getApplication<Application>()
                        .getSharedPreferences("history_sync", android.content.Context.MODE_PRIVATE)
                    val owner = prefs.getString("owner_user_id", null)
                    val uid = c.userId
                    val local = if (owner != null && owner != uid) emptyList() else playLog.getEntries()
                    val result = HistorySync.sync(c, local)
                    if (owner != null && owner != uid) {
                        playLog.replaceAll(result.merged)
                    }
                    prefs.edit().putString("owner_user_id", uid).apply()
                    playLog.mergeIn(result.merged)
                }
                if (added > 0) historyVersion++
                historySyncedAtMs = System.currentTimeMillis()
                historySyncError = null
            } catch (e: Exception) {
                // The phone's copy is untouched; the next sync retries.
                historySyncError = e.message ?: e.javaClass.simpleName
            } finally {
                historySyncing = false
            }
        }
    }

    private fun maybeReportProgress() {
        val track = currentTrack ?: return
        val now = System.currentTimeMillis()
        if (now - lastProgressReportMs < 10_000L) return
        lastProgressReportMs = now
        viewModelScope.launch {
            try {
                client?.reportPlaying(track.id, msToTicks(positionMs))
            } catch (e: Exception) {
                // Best-effort.
            }
        }
    }

    private fun reportStopped(playedToCompletion: Boolean) {
        val track = currentTrack ?: return
        viewModelScope.launch {
            try {
                client?.reportStopped(track.id, msToTicks(positionMs), playedToCompletion)
            } catch (e: Exception) {
                // Best-effort.
            }
        }
    }

    private fun Track.toMediaItem(): MediaItem = toPlayableItem(client)

    override fun onCleared() {
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onCleared()
    }

    private companion object {
        const val HISTORY_UPLOAD_DELAY_MS = 120_000L
    }
}

/** "Share my stats with friends" (Settings). On by default. */
object StatsSharing {
    private const val PREFS = "oniel_sharing"
    private const val KEY = "share_stats"

    fun isEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: android.content.Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
