package com.oniel.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.oniel.music.R
import com.oniel.music.data.Album
import com.oniel.music.data.ItemDetails
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Track

/**
 * Artist page: backdrop header with photo and name, Play / Shuffle,
 * genres, biography, most-played songs and albums. Opened from the
 * library, search, the player and album pages. Works from an artist id
 * when one is known, otherwise looks the artist up by name.
 */
@Composable
fun ArtistScreen(
    artistId: String?,
    artistName: String?,
    client: JellyfinClient,
    player: PlayerController,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackPlayed: () -> Unit
) {
    var artist by remember { mutableStateOf<ItemDetails?>(null) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(artistId, artistName) {
        try {
            val details = when {
                !artistId.isNullOrBlank() -> client.getItemDetails(artistId)
                !artistName.isNullOrBlank() -> client.getArtistByName(artistName)
                else -> null
            }
            artist = details
            if (details != null) {
                albums = try { client.getArtistAlbums(details.id) } catch (e: Exception) { emptyList() }
                tracks = try { client.getArtistTracks(details.id) } catch (e: Exception) { emptyList() }
            }
        } catch (e: Exception) {
            // Leave the page with what we have; back still works.
        } finally {
            loading = false
        }
    }

    val current = artist
    val name = current?.name ?: artistName.orEmpty()
    val photoUrl = current?.primaryImageTag?.let {
        try { client.imageUrl(current.id, it, 400) } catch (e: Exception) { null }
    }
    val topSongs = tracks.filter { it.playCount > 0 }
        .sortedByDescending { it.playCount }
        .take(5)
        .ifEmpty { tracks.take(5) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp)
    ) {
        item {
            BackdropHeader(
                backdropUrl = client.backdropFor(current),
                fallbackUrl = photoUrl ?: albums.firstOrNull()?.let {
                    try { client.imageUrl(it.id, it.imageTag, 600) } catch (e: Exception) { null }
                },
                height = 320.dp
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (photoUrl != null) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        ListenerAvatar(userName = name.ifEmpty { "?" }, sizeDp = 96.dp)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        DetailLine(
                            listOf(
                                if (albums.isNotEmpty()) stringResource(R.string.album_count, albums.size) else null,
                                if (tracks.isNotEmpty()) stringResource(R.string.track_count, tracks.size) else null
                            )
                        )
                    }
                }
            }
        }

        if (loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
            return@LazyColumn
        }

        if (current == null) {
            item {
                Text(
                    text = stringResource(R.string.artist_not_found),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
            return@LazyColumn
        }

        if (tracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { player.play(tracks, 0); onTrackPlayed() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.play)) }
                    OutlinedButton(
                        onClick = { player.play(tracks.shuffled(), 0); onTrackPlayed() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.shuffle)) }
                }
            }
        }

        if (current.genres.isNotEmpty()) {
            item { GenreChips(current.genres, modifier = Modifier.padding(vertical = 8.dp)) }
        }

        val bio = current.overview
        if (bio != null) {
            item { SectionTitle(stringResource(R.string.about)) }
            item {
                ExpandableText(
                    text = bio,
                    modifier = Modifier.padding(horizontal = 24.dp),
                    collapsedLines = 5
                )
            }
        }

        if (topSongs.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.top_songs)) }
            itemsIndexed(topSongs) { index, track ->
                TrackRow(
                    number = index + 1,
                    track = track,
                    isCurrent = player.currentTrack?.id == track.id,
                    onClick = { player.play(topSongs, index); onTrackPlayed() }
                )
            }
        }

        if (albums.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.library_albums)) }
            items(albums.chunked(2)) { pair ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    pair.forEach { album ->
                        AlbumCard(
                            title = album.name,
                            subtitle = album.year?.toString() ?: album.artist,
                            imageUrl = try { client.imageUrl(album.id, album.imageTag, 400) } catch (e: Exception) { null },
                            onClick = { onAlbumClick(album.id) },
                            cardWidth = 156.dp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)
    )
}
