package com.oniel.music.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.Album
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Track

/**
 * One genre, opened from the genre tiles in Search: a coloured header,
 * Play / Shuffle across every song in the genre, then its albums.
 */
@Composable
fun GenreScreen(
    genreId: String,
    genreName: String,
    client: JellyfinClient,
    player: PlayerController,
    onBack: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onTrackPlayed: () -> Unit
) {
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(genreId) {
        try {
            albums = try { client.getGenreAlbums(genreId) } catch (e: Exception) { emptyList() }
            tracks = try { client.getGenreTracks(genreId) } catch (e: Exception) { emptyList() }
        } finally {
            loading = false
        }
    }

    val color = genreColorFor(genreName)
    val background = MaterialTheme.colorScheme.background

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomChromePadding())
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(Brush.verticalGradient(listOf(color, color.copy(alpha = 0.55f), background)))
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .statusBarsPadding()
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.25f))
                ) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White
                    )
                }
                Text(
                    text = genreName,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                )
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

        item {
            DetailLine(
                listOf(
                    if (albums.isNotEmpty()) stringResource(R.string.album_count, albums.size) else null,
                    if (tracks.isNotEmpty()) stringResource(R.string.track_count, tracks.size) else null
                ),
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        if (tracks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
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

        if (albums.isEmpty() && tracks.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.genre_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
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
                            subtitle = album.artist,
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
