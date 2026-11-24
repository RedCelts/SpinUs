package com.oniel.music.ui

import androidx.compose.foundation.clickable
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
    var results by remember { mutableStateOf<List<Track>?>(null) }

    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            searching = false
            return@LaunchedEffect
        }
        searching = true
        delay(400)
        try {
            results = client.search(query.trim())
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
        } else if (found == null) {
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
            val artists = found
                .flatMap { it.artists.ifEmpty { listOf(it.albumArtist) } }
                .filter { it.isNotBlank() }
                .distinct()
            val albums = found
                .filter { it.albumId != null }
                .distinctBy { it.albumId }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomChromePadding())
            ) {
                if (artists.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.search_artists),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(artists) { artist ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onArtistClick(artist) }
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ListenerAvatar(userName = artist, sizeDp = 44.dp)
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
                if (albums.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.search_albums),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    items(albums) { track ->
                        val albumId = track.albumId ?: return@items
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAlbumClick(albumId) }
                                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverImage(
                                url = client.imageUrl(albumId, track.imageTag, 200),
                                modifier = Modifier
                                    .padding(end = 12.dp)
                                    .width(48.dp)
                                    .height(48.dp),
                                cornerDp = 6.dp
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.album,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.albumArtist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            ItemMoreButton(target = ActionTarget.AlbumItem(albumId, track.album))
                        }
                    }
                }
                if (found.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.search_tracks),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
                        )
                    }
                    itemsIndexed(found) { index, track ->
                        TrackRow(
                            number = null,
                            track = track,
                            isCurrent = player.currentTrack?.id == track.id,
                            onClick = {
                                player.play(found, index)
                                onTrackPlayed()
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
