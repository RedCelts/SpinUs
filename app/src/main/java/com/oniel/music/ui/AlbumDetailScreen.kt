package com.oniel.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.ItemDetails
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Track

/**
 * One album: backdrop header with the cover, title and artist (tap the
 * artist for their page), year / tracks / length, label, genres, the
 * album notes, then its tracks in disc / track-number order. Tapping a
 * track plays the album from there and opens the full player.
 */
@Composable
fun AlbumDetailScreen(
    albumId: String,
    client: JellyfinClient,
    player: PlayerController,
    onBack: () -> Unit,
    onTrackPlayed: () -> Unit,
    onArtistClick: (artistId: String?, artistName: String) -> Unit = { _, _ -> }
) {
    var details by remember { mutableStateOf<ItemDetails?>(null) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(albumId) {
        try {
            details = client.getItemDetails(albumId)
            tracks = client.getAlbumTracks(albumId).sortedWith(
                compareBy<Track>({ it.discNumber ?: 1 }, { it.indexNumber ?: Int.MAX_VALUE }, { it.title })
            )
        } catch (e: Exception) {
            // Leave the screen empty; the back button still works.
        } finally {
            loading = false
        }
    }

    val current = details
    val coverUrl = try { client.imageUrl(albumId, current?.primaryImageTag, 600) } catch (e: Exception) { null }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomChromePadding())
    ) {
        item {
            BackdropHeader(
                backdropUrl = client.backdropFor(current),
                fallbackUrl = coverUrl,
                height = 300.dp
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                CoverImage(
                    url = coverUrl,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .size(200.dp),
                    cornerDp = 12.dp,
                    contentDescription = current?.name
                )
            }
        }

        if (loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        } else if (current != null) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = current.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    val artistName = current.artistName
                        ?: tracks.firstOrNull()?.albumArtist.orEmpty()
                    if (artistName.isNotEmpty()) {
                        Text(
                            text = artistName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .clickable { onArtistClick(current.artistId, artistName) }
                        )
                    }
                    val totalMs = if (current.runTimeTicks > 0) ticksToMs(current.runTimeTicks)
                        else tracks.sumOf { ticksToMs(it.runTimeTicks) }
                    val count = if (tracks.isNotEmpty()) tracks.size else current.childCount
                    DetailLine(
                        listOf(
                            current.year?.toString(),
                            stringResource(R.string.track_count, count),
                            if (totalMs > 0) formatLongDuration(totalMs) else null
                        ),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    DetailLine(current.studios.take(2), modifier = Modifier.padding(top = 2.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    if (tracks.isNotEmpty()) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(onClick = { player.play(tracks, 0); onTrackPlayed() }) {
                                Text(stringResource(R.string.play))
                            }
                            OutlinedButton(onClick = { player.play(tracks.shuffled(), 0); onTrackPlayed() }) {
                                Text(stringResource(R.string.shuffle))
                            }
                            ItemMoreButton(target = ActionTarget.AlbumItem(albumId, current.name))
                        }
                    }
                }
            }
            if (current.genres.isNotEmpty()) {
                item { GenreChips(current.genres, modifier = Modifier.padding(top = 12.dp)) }
            }
            val notes = current.overview
            if (notes != null) {
                item { SectionTitle(stringResource(R.string.about_album)) }
                item {
                    ExpandableText(text = notes, modifier = Modifier.padding(horizontal = 24.dp))
                }
            }
            item { Spacer(modifier = Modifier.height(12.dp)) }
            val multiDisc = tracks.mapNotNull { it.discNumber }.distinct().size > 1
            var lastDisc: Int? = null
            tracks.forEachIndexed { index, track ->
                if (multiDisc && track.discNumber != lastDisc) {
                    lastDisc = track.discNumber
                    val disc = track.discNumber ?: 1
                    item(key = "disc-$disc") { SectionTitle(stringResource(R.string.disc_number, disc)) }
                }
                item(key = track.id) {
                    TrackRow(
                        number = track.indexNumber ?: (index + 1),
                        track = track,
                        isCurrent = player.currentTrack?.id == track.id,
                        onClick = {
                            player.play(tracks, index)
                            onTrackPlayed()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TrackRow(
    number: Int?,
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (number != null) {
            Text(
                text = number.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artists.joinToString(", "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = formatDuration(ticksToMs(track.runTimeTicks)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // ⋮ Favorite / Add to playlist / New playlist.
        ItemMoreButton(target = ActionTarget.Song(track))
    }
}
