package com.oniel.music.ui

import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.Addition
import com.oniel.music.data.DeezerClient
import com.oniel.music.data.SpinUsClient
import com.oniel.music.data.Pick
import kotlinx.coroutines.launch

/** Section title with an optional trailing action (e.g. refresh). */
@Composable
fun RecHeader(title: String, subtitle: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        action?.invoke()
    }
}

/** One card: square image, title, second line, reason. */
@Composable
private fun RecCard(
    imageUrl: String?,
    title: String,
    line2: String,
    reason: String,
    round: Boolean = false,
    fallbackUrl: String? = null,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        CoverImage(
            url = imageUrl,
            modifier = Modifier.size(140.dp),
            cornerDp = if (round) 70.dp else 8.dp,
            contentDescription = title,
            fallbackUrl = fallbackUrl
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (line2.isNotBlank()) {
            Text(
                line2,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** "You might enjoy" — albums, artists and songs from the library. */
@Composable
fun YouMightEnjoyRow(
    picks: List<Pick>,
    client: SpinUsClient,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlayTrack: (com.oniel.music.data.Track) -> Unit
) {
    RecHeader(stringResource(R.string.rec_enjoy_title), stringResource(R.string.rec_enjoy_subtitle))
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(picks) { pick ->
            when (pick) {
                is Pick.AlbumPick -> RecCard(
                    imageUrl = client.albumArtUrl(pick.album, 300),
                    title = pick.album.name,
                    line2 = pick.album.artist,
                    reason = pick.reason,
                    onClick = { onAlbumClick(pick.album.id) }
                )
                is Pick.ArtistPick -> RecCard(
                    // Artists are always round, with their own picture
                    // (album cover only if the artist has none).
                    imageUrl = pick.coverAlbum?.artistId?.let { client.artistImageUrl(it, 300) },
                    fallbackUrl = pick.coverAlbum?.let { client.albumArtUrl(it, 300) },
                    title = pick.name,
                    line2 = stringResource(R.string.rec_artist),
                    reason = pick.reason,
                    round = true,
                    onClick = { onArtistClick(pick.name) }
                )
                is Pick.TrackPick -> RecCard(
                    imageUrl = client.trackArtUrl(pick.track, 300),
                    title = pick.track.title,
                    line2 = pick.track.artists.joinToString(", ").ifEmpty { pick.track.albumArtist },
                    reason = pick.reason,
                    onClick = { onPlayTrack(pick.track) }
                )
            }
        }
    }
}

/** "Recommended additions" — music not in the library, from Deezer. */
@Composable
fun AdditionsRow(
    items: List<Addition>,
    refreshing: Boolean,
    error: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Addition) -> Unit
) {
    RecHeader(
        stringResource(R.string.rec_add_title),
        stringResource(R.string.rec_add_subtitle)
    ) {
        if (refreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(20.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.rec_refresh))
            }
        }
    }
    if (items.isEmpty()) {
        Text(
            text = stringResource(
                when {
                    refreshing -> R.string.rec_add_loading
                    error -> R.string.rec_add_error
                    else -> R.string.rec_add_empty
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        return
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { a ->
            RecCard(
                imageUrl = a.imageUrl,
                title = a.title,
                line2 = a.artist,
                reason = a.reason,
                onClick = { onOpen(a) }
            )
        }
    }
}

/**
 * Details for a suggestion outside the library: 30-second preview
 * (pauses your music while it plays).
 */
@Composable
fun AdditionDialog(
    addition: Addition,
    deezer: DeezerClient,
    player: PlayerController,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var previewState by remember { mutableStateOf(PreviewState.IDLE) }
    val mediaPlayer = remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPreview() {
        mediaPlayer.value?.let {
            try { it.stop() } catch (e: Exception) { }
            it.release()
        }
        mediaPlayer.value = null
        previewState = PreviewState.IDLE
    }

    DisposableEffect(Unit) { onDispose { stopPreview() } }

    AlertDialog(
        onDismissRequest = { stopPreview(); onDismiss() },
        title = null,
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                CoverImage(
                    url = addition.imageUrl,
                    modifier = Modifier.size(220.dp),
                    cornerDp = 12.dp,
                    contentDescription = addition.title
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    addition.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(addition.artist, style = MaterialTheme.typography.titleSmall)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    addition.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.rec_not_in_library),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = {
                        if (previewState == PreviewState.PLAYING || previewState == PreviewState.LOADING) {
                            stopPreview()
                            return@Button
                        }
                        previewState = PreviewState.LOADING
                        if (player.isPlaying) player.togglePlayPause()
                        scope.launch {
                            val url = try { deezer.previewUrl(addition) } catch (e: Exception) { null }
                            if (url == null) {
                                previewState = PreviewState.UNAVAILABLE
                                return@launch
                            }
                            val mp = MediaPlayer()
                            mediaPlayer.value = mp
                            try {
                                mp.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build()
                                )
                                mp.setDataSource(url)
                                mp.setOnPreparedListener { it.start(); previewState = PreviewState.PLAYING }
                                mp.setOnCompletionListener { stopPreview() }
                                mp.setOnErrorListener { _, _, _ -> stopPreview(); previewState = PreviewState.UNAVAILABLE; true }
                                mp.prepareAsync()
                            } catch (e: Exception) {
                                stopPreview()
                                previewState = PreviewState.UNAVAILABLE
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        stringResource(
                            when (previewState) {
                                PreviewState.PLAYING -> R.string.rec_stop_preview
                                PreviewState.LOADING -> R.string.rec_loading_preview
                                PreviewState.UNAVAILABLE -> R.string.rec_no_preview
                                PreviewState.IDLE -> R.string.rec_play_preview
                            }
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { stopPreview(); onDismiss() }) { Text(stringResource(R.string.rec_close)) }
        }
    )
}

private enum class PreviewState { IDLE, LOADING, PLAYING, UNAVAILABLE }
