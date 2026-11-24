package com.oniel.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import com.oniel.music.data.Album
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Playlist
import com.oniel.music.data.Track

/**
 * Library: Artists, Albums, Songs, Playlists and Liked songs.
 */
@Composable
fun LibraryScreen(
    client: JellyfinClient,
    player: PlayerController,
    initialTab: Int,
    onAlbumClick: (String) -> Unit,
    onTrackPlayed: () -> Unit,
    onArtistClick: (String) -> Unit = {}
) {
    val tabs = listOf(
        stringResource(R.string.library_artists),
        stringResource(R.string.library_albums),
        stringResource(R.string.library_songs),
        stringResource(R.string.library_playlists),
        stringResource(R.string.library_liked)
    )
    var selectedTab by remember { mutableStateOf(initialTab.coerceIn(tabs.indices)) }
    var loading by remember { mutableStateOf(true) }
    var albums by remember { mutableStateOf<List<Album>>(emptyList()) }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<Playlist>>(emptyList()) }

    LaunchedEffect(Unit) {
        try {
            albums = client.getAlbums()
            tracks = client.getTracks()
            playlists = client.getPlaylists()
        } catch (e: Exception) {
            // Sections stay empty; the tabs still render.
        } finally {
            loading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Text(
            text = stringResource(R.string.library_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title, maxLines = 1) }
                )
            }
        }
        if (loading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            return@Column
        }
        when (selectedTab) {
            0 -> ArtistsSection(
                tracks = tracks,
                // Opens the artist page (Play / Shuffle live there now).
                onArtistClick = onArtistClick
            )
            1 -> AlbumsSection(albums = albums, client = client, onAlbumClick = onAlbumClick)
            2 -> SongsSection(tracks = tracks, player = player, onTrackPlayed = onTrackPlayed)
            3 -> PlaylistsSection(playlists = playlists)
            4 -> LikedSection(
                tracks = tracks.filter { it.isFavorite },
                player = player,
                onTrackPlayed = onTrackPlayed
            )
        }
    }
}

@Composable
private fun ArtistsSection(
    tracks: List<Track>,
    onArtistClick: (String) -> Unit
) {
    val artists = tracks
        .flatMap { it.artists.ifEmpty { listOf(it.albumArtist) } }
        .filter { it.isNotBlank() }
        .groupingBy { it }
        .eachCount()
        .entries
        .sortedByDescending { it.value }
        .map { it.key }
    LazyColumn(contentPadding = PaddingValues(bottom = bottomChromePadding())) {
        items(artists) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(artist) }
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ListenerAvatar(userName = artist, sizeDp = 48.dp)
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ItemMoreButton(target = ActionTarget.ArtistItem(null, artist))
            }
        }
    }
}

@Composable
private fun AlbumsSection(
    albums: List<Album>,
    client: JellyfinClient,
    onAlbumClick: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomChromePadding()),
        modifier = Modifier.fillMaxSize()
    ) {
        items(albums) { album ->
            AlbumCard(
                title = album.name,
                subtitle = album.artist,
                imageUrl = client.albumArtUrl(album, 300),
                onClick = { onAlbumClick(album.id) },
                modifier = Modifier.padding(6.dp)
            )
        }
    }
}

@Composable
private fun SongsSection(
    tracks: List<Track>,
    player: PlayerController,
    onTrackPlayed: () -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = bottomChromePadding())) {
        itemsIndexed(tracks) { index, track ->
            TrackRow(
                number = index + 1,
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

/**
 * Playlists are shown read-only in v1: the data layer has no call to
 * fetch a playlist's tracks (or to add to one), so a playlist cannot be
 * played or opened yet. That is noted in the build report for the data
 * layer to add.
 */
@Composable
private fun PlaylistsSection(
    playlists: List<Playlist>
) {
    LazyColumn(contentPadding = PaddingValues(bottom = bottomChromePadding())) {
        items(playlists) { playlist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = playlist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.playlist_count, playlist.itemCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun LikedSection(
    tracks: List<Track>,
    player: PlayerController,
    onTrackPlayed: () -> Unit
) {
    if (tracks.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))
            Icon(
                imageVector = Icons.Filled.Favorite,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.liked_empty),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.liked_empty_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = bottomChromePadding())) {
        itemsIndexed(tracks) { index, track ->
            TrackRow(
                number = index + 1,
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
