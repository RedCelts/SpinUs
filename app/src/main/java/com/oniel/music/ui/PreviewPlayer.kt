package com.oniel.music.ui

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.oniel.music.data.DeezerClient
import com.oniel.music.data.DzTrack
import com.oniel.music.net.NetClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One Deezer client for screens that browse the catalog (Search, genre pages). */
val sharedDeezer: DeezerClient by lazy { DeezerClient(NetClients.http) }

/**
 * Plays Deezer 30-second previews for songs that aren't in the library,
 * one at a time. Tap the same song again to stop. An expired preview URL
 * is refetched once before giving up.
 */
class PreviewPlayer(
    private val deezer: DeezerClient,
    private val scope: CoroutineScope
) {
    /** Deezer id of the song playing / loading right now, if any. */
    var playingId by mutableStateOf<Long?>(null)
        private set
    var loadingId by mutableStateOf<Long?>(null)
        private set
    /** Last song whose preview couldn't play (shown as "no preview"). */
    var failedId by mutableStateOf<Long?>(null)
        private set

    private var media: MediaPlayer? = null
    private var generation = 0

    fun isActive(id: Long) = playingId == id || loadingId == id

    /** Start [track]'s preview, or stop it if it's already the one playing. */
    fun toggle(track: DzTrack, beforeStart: () -> Unit) {
        if (isActive(track.id)) {
            stop()
            return
        }
        stop()
        beforeStart()
        failedId = null
        loadingId = track.id
        val gen = ++generation
        val url = track.previewUrl
        if (url != null) start(track.id, url, gen, retry = true) else refetch(track.id, gen)
    }

    fun stop() {
        generation++
        media?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        media = null
        playingId = null
        loadingId = null
    }

    private fun refetch(id: Long, gen: Int) {
        scope.launch {
            val fresh = try { deezer.trackPreview(id) } catch (e: Exception) { null }
            if (gen != generation) return@launch
            if (fresh == null) fail(id) else start(id, fresh, gen, retry = false)
        }
    }

    private fun fail(id: Long) {
        stop()
        failedId = id
    }

    private fun start(id: Long, url: String, gen: Int, retry: Boolean) {
        val mp = MediaPlayer()
        media = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener {
                if (gen != generation) return@setOnPreparedListener
                it.start()
                loadingId = null
                playingId = id
            }
            mp.setOnCompletionListener { if (gen == generation) stop() }
            mp.setOnErrorListener { player, _, _ ->
                if (gen == generation) {
                    player.release()
                    media = null
                    if (retry) refetch(id, gen) else fail(id)
                }
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            mp.release()
            media = null
            if (retry) refetch(id, gen) else fail(id)
        }
    }
}

/** A preview player tied to the screen: it stops when the screen goes away. */
@Composable
fun rememberPreviewPlayer(deezer: DeezerClient = sharedDeezer): PreviewPlayer {
    val scope = rememberCoroutineScope()
    val preview = remember { PreviewPlayer(deezer, scope) }
    DisposableEffect(preview) { onDispose { preview.stop() } }
    return preview
}
