package com.oniel.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.rememberCoroutineScope
import com.oniel.music.data.Playlist
import com.oniel.music.data.SearchResults
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Track
import kotlinx.coroutines.delay

/**
 * Search across artists, albums and tracks. The server's search returns
 * audio tracks only, so the Artists and Albums sections are grouped out
 * of those track hits. Results appear as you type (a short pause after
 * the last keystroke before asking the server).
 */
@Composable
fun SearchScreen(
    client: JellyfinClient,
    player: PlayerController,
    onAlbumClick: (String) -> Unit,
    onTrackPlayed: () -> Unit,
    onArtistClick: (String) -> Unit = {},
    /** A Browse tile: a Deezer main genre (dzId) or, offline, a library genre (jfId). */
    onGenreClick: (jfId: String?, dzId: Long?, name: String) -> Unit = { _, _, _ -> }
) {
    var query by rememberSaveable { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    var genres by remember { mutableStateOf<List<BrowseGenre>?>(null) }

    // Genres to browse before typing (loaded once per visit).
    LaunchedEffect(Unit) {
        // Main categories only (Deezer's ~25 genres); the library's own
        // genre tags are the fallback when Deezer can't be reached.
        val main = try { sharedDeezer.mainGenres() } catch (e: Exception) { emptyList() }
        genres = if (main.isNotEmpty()) {
            main.map { BrowseGenre(it.name, it.pictureUrl, dzId = it.id, jfId = null) }
        } else {
            try {
                client.getMusicGenres().map { g ->
                    BrowseGenre(
                        g.name,
                        g.imageTag?.let { tag ->
                            try { client.imageUrl(g.id, tag, 200) } catch (e: Exception) { null }
                        },
                        dzId = null,
                        jfId = g.id
                    )
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<SearchResults?>(null) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400)
        try {
            results = client.searchAll(query.trim())
        } catch (e: Exception) {
            results = null
        } finally {
            searching = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Text(
            text = stringResource(R.string.search_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = {
                Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.search_clear)
                        )
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Spacer(modifier = Modifier.height(8.dp))

        val found = results
        if (searching) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.width(16.dp))
                CircularProgressIndicator(modifier = Modifier.padding(end = 12.dp))
                Text(stringResource(R.string.searching))
            }
        } else if (found == null || (found.isEmpty && query.isNotBlank())) {
            if (query.isBlank()) {
                GenreBrowser(
                    genres = genres,
                    client = client,
                    onGenreClick = onGenreClick
                )
            } else {
                Text(
                    text = stringResource(R.string.search_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            val tracks = found.tracks
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomChromePadding())
            ) {
                if (found.artists.isNotEmpty()) {
                    item { SearchSection(stringResource(R.string.search_artists)) }
                    items(found.artists, key = { "ar${it.id}" }) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArtistClick(artist.name) }
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (artist.imageTag != null) {
                                CoverImage(
                                    url = try { client.imageUrl(artist.id, artist.imageTag, 200) } catch (e: Exception) { null },
                                    modifier = Modifier.size(44.dp),
                                    cornerDp = 22.dp,
                                    contentDescription = artist.name
                                )
                            } else {
                                ListenerAvatar(userName = artist.name, sizeDp = 44.dp)
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            ItemMoreButton(target = ActionTarget.ArtistItem(artist.id, artist.name))
                        }
                    }
                }
                if (found.albums.isNotEmpty()) {
                    item { SearchSection(stringResource(R.string.search_albums)) }
                    items(found.albums, key = { "al${it.id}" }) { album ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAlbumClick(album.id) }
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverImage(
                                url = try { client.albumArtUrl(album, 200) } catch (e: Exception) { null },
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .size(48.dp),
                                cornerDp = 6.dp,
                                fallbackUrl = try { client.albumArtFallback(album, 200) } catch (e: Exception) { null }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = album.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = album.artist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            ItemMoreButton(target = ActionTarget.AlbumItem(album.id, album.name))
                        }
                    }
                }
                if (tracks.isNotEmpty()) {
                    item { SearchSection(stringResource(R.string.search_tracks)) }
                    itemsIndexed(tracks, key = { _, t -> "tr${t.id}" }) { index, track ->
                        TrackRow(
                            number = null,
                            track = track,
                            isCurrent = player.currentTrack?.id == track.id,
                            onClick = {
                                player.play(tracks, index)
                                onTrackPlayed()
                            }
                        )
                    }
                }
                if (found.playlists.isNotEmpty()) {
                    item { SearchSection(stringResource(R.string.search_playlists)) }
                    items(found.playlists, key = { "pl${it.id}" }) { playlist ->
                        PlaylistHitRow(
                            playlist = playlist,
                            onPlay = {
                                scope.launch {
                                    val songs = try { client.getPlaylistTracks(playlist.id) } catch (e: Exception) { emptyList() }
                                    if (songs.isNotEmpty()) {
                                        player.play(songs, 0)
                                        onTrackPlayed()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shown under the search bar before anything is typed: every genre in
 * the library as a coloured tile (with the genre's picture tucked in the
 * corner when the server has one). Tap one to browse it.
 */
@Composable
private fun GenreBrowser(
    genres: List<BrowseGenre>?,
    client: JellyfinClient,
    onGenreClick: (jfId: String?, dzId: Long?, name: String) -> Unit
) {
    when {
        genres == null -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator() }

        genres.isEmpty() -> Text(
            text = stringResource(R.string.search_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )

        else -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomChromePadding()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = stringResource(R.string.browse_genres),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                )
            }
            items(genres, key = { "${it.dzId}|${it.jfId}|${it.name}" }) { genre ->
                GenreTile(
                    name = genre.name,
                    imageUrl = genre.imageUrl,
                    onClick = { onGenreClick(genre.jfId, genre.dzId, genre.name) }
                )
            }
        }
    }
}

/** A tile in Browse all. */
data class BrowseGenre(
    val name: String,
    val imageUrl: String?,
    val dzId: Long?,
    val jfId: String?
)

@Composable
fun GenreTile(
    name: String,
    imageUrl: String?,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(genreColorFor(name))
            .clickable(onClick = onClick)
    ) {
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 14.dp, y = 8.dp)
                    .size(68.dp)
                    .rotate(22f)
                    .clip(RoundedCornerShape(6.dp))
            )
        }
        Text(
            text = name,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        )
    }
}

/** Fixed, warm-to-cool palette; a genre keeps its colour every visit. */
private val GenrePalette = listOf(
    Color(0xFFC77F0A), Color(0xFF0E4F52), Color(0xFF7A4A9E), Color(0xFF2E6E4E),
    Color(0xFF9E3D4E), Color(0xFF3D6E9E), Color(0xFFB4532A), Color(0xFF5C6B1F),
    Color(0xFF8A3E7A), Color(0xFF1F5C8A)
)

fun genreColorFor(name: String): Color =
    GenrePalette[(name.lowercase().hashCode() and Int.MAX_VALUE) % GenrePalette.size]

@Composable
private fun SearchSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

/** A matching playlist: tap to play it from the start. */
@Composable
private fun PlaylistHitRow(playlist: Playlist, onPlay: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.List,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.playlist_count, playlist.itemCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary
        )
    }
}
