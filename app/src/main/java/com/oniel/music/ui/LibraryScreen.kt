package com.oniel.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.oniel.music.data.ArtistHit
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import com.oniel.music.data.SpinUsClient
import com.oniel.music.data.Playlist
import com.oniel.music.data.Track

/**
 * Library: Artists, Albums, Songs, Playlists and Liked songs.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    client: SpinUsClient,
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = initialTab.coerceIn(tabs.indices),
        pageCount = { tabs.size }
    )
    // Start from whatever is already in memory (Home's load or the last
    // Library visit): no spinner when there's anything to show.
    val start = LibraryCache.best()
    var loading by remember { mutableStateOf(start == null) }
    var albums by remember { mutableStateOf(start?.albums.orEmpty()) }
    var tracks by remember { mutableStateOf(start?.tracks.orEmpty()) }
    var playlists by remember { mutableStateOf(LibraryCache.playlists) }
    var artistPics by remember { mutableStateOf(LibraryCache.artistPictures) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch {
                playlists = try { client.getPlaylists() } catch (e: Exception) { playlists }
                LibraryCache.playlists = playlists
            }
            launch {
                if (artistPics.isEmpty()) {
                    artistPics = HomeDiskCache.loadArtistPictures(context)
                }
                if (!LibraryCache.picturesFresh()) {
                    try {
                        val fresh = client.getArtistPictures()
                        artistPics = fresh
                        LibraryCache.storePictures(fresh)
                        HomeDiskCache.saveArtistPictures(context, fresh)
                    } catch (e: Exception) { }
                }
            }
            // Nothing in memory: the copy saved on the phone, if any.
            if (LibraryCache.best() == null) {
                HomeDiskCache.load(context)?.let { d ->
                    if (HomeCache.data == null) HomeCache.restore(d)
                    albums = d.libraryAlbums
                    tracks = d.allTracks
                    loading = false
                }
            }
            // Refresh from the server only when what we have is old.
            if (LibraryCache.needsRefresh()) {
                try {
                    val a = async { client.getAlbums() }
                    val t = async { client.getTracks() }
                    albums = a.await()
                    tracks = t.await()
                    LibraryCache.store(albums, tracks)
                } catch (e: Exception) {
                    // Keep what's shown; tabs still render.
                }
            }
            loading = false
        }
    }

    // Singles only: songs with no album, or from a release of 1-3 tracks
    // (a single, maybe with a remix) - not songs from full albums.
    val singles = remember(tracks) {
        // Release size = how many of its songs are in the library.
        val albumSize = tracks.groupingBy { it.albumId }.eachCount()
        tracks.filter { t ->
            val id = t.albumId
            id == null || (albumSize[id] ?: 0) <= 3
        }
    }
    val liked = remember(tracks) {
        tracks.filter { client.favoriteOverride(it.id) ?: it.isFavorite }
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
        TabRow(selectedTabIndex = pagerState.currentPage) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
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
        // Swipe left / right between the tabs.
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            when (page) {
                0 -> ArtistsSection(
                    tracks = tracks,
                    pictures = artistPics,
                    client = client,
                    // Opens the artist page (Play / Shuffle live there now).
                    onArtistClick = onArtistClick
                )
                1 -> AlbumsSection(albums = albums, client = client, onAlbumClick = onAlbumClick)
                2 -> SongsSection(tracks = singles, player = player, onTrackPlayed = onTrackPlayed)
                3 -> PlaylistsSection(playlists = playlists)
                else -> LikedSection(
                    tracks = liked,
                    player = player,
                    onTrackPlayed = onTrackPlayed
                )
            }
        }
    }
}

/**
 * Library data kept while the app runs. Home's load counts too (same
 * songs and albums), whichever is newer wins; older than 5 min → a quiet
 * background refresh on the next visit. Artist photos refresh hourly.
 */
object LibraryCache {
    private const val MAX_AGE_MS = 5 * 60_000L
    private const val PICS_MAX_AGE_MS = 60 * 60_000L

    data class Snapshot(val albums: List<Album>, val tracks: List<Track>)

    @Volatile private var own: Snapshot? = null
    @Volatile private var ownAt = 0L
    @Volatile var playlists: List<Playlist> = emptyList()
    @Volatile var artistPictures: Map<String, ArtistHit> = emptyMap()
        private set
    @Volatile private var picturesAt = 0L

    /** The newest library data in memory, or null. */
    fun best(): Snapshot? {
        val home = HomeCache.data?.let { Snapshot(it.libraryAlbums, it.allTracks) }
        return when {
            own != null && (home == null || ownAt >= HomeCache.loadedAtMs()) -> own
            home != null && home.tracks.isNotEmpty() -> home
            else -> own
        }
    }

    fun needsRefresh(): Boolean {
        val newest = maxOf(ownAt, HomeCache.loadedAtMs())
        return best() == null || System.currentTimeMillis() - newest > MAX_AGE_MS
    }

    fun store(albums: List<Album>, tracks: List<Track>) {
        own = Snapshot(albums, tracks)
        ownAt = System.currentTimeMillis()
    }

    fun picturesFresh() =
        artistPictures.isNotEmpty() && System.currentTimeMillis() - picturesAt < PICS_MAX_AGE_MS

    fun storePictures(pics: Map<String, ArtistHit>) {
        artistPictures = pics
        picturesAt = System.currentTimeMillis()
    }

    fun clear() {
        own = null
        ownAt = 0L
        playlists = emptyList()
        artistPictures = emptyMap()
        picturesAt = 0L
    }
}

@Composable
private fun ArtistsSection(
    tracks: List<Track>,
    pictures: Map<String, ArtistHit>,
    client: SpinUsClient,
    onArtistClick: (String) -> Unit
) {
    val artists = remember(tracks) {
        tracks
            .flatMap { it.artists.ifEmpty { listOf(it.albumArtist) } }
            .filter { it.isNotBlank() }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .map { it.key }
    }
    LazyColumn(contentPadding = PaddingValues(bottom = bottomChromePadding())) {
        items(artists, key = { it }) { artist ->
            val pic = pictures[artist.lowercase()]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onArtistClick(artist) }
                    .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // The artist's photo (round); initials when there's none.
                if (pic != null) {
                    CoverImage(
                        url = try { client.imageUrl(pic.id, pic.imageTag, 160) } catch (e: Exception) { null },
                        modifier = Modifier.size(48.dp),
                        cornerDp = 24.dp,
                        contentDescription = artist
                    )
                } else {
                    ListenerAvatar(userName = artist, sizeDp = 48.dp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                ItemMoreButton(target = ActionTarget.ArtistItem(pic?.id, artist))
            }
        }
    }
}

@Composable
private fun AlbumsSection(
    albums: List<Album>,
    client: SpinUsClient,
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
    if (tracks.isEmpty()) {
        Text(
            text = stringResource(R.string.library_no_singles),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp)
        )
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
