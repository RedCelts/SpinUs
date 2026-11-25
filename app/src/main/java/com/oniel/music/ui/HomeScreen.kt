package com.oniel.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.Album
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Scoring
import com.oniel.music.data.Track

/**
 * Home: quick grid first, then shelves in order — New releases, Rediscover
 * albums, Recently played, Recently added, Recommended stations, Made for
 * you. Album cards open the album page; tapping a tile plays it.
 *
 * Play-based shelves use the enriched album list (play data filled in
 * from the tracks): an album's own play counters never move when its
 * tracks play, so plain getAlbums() would feed Rediscover and Recently
 * played nothing but "never played". New releases and Recently added
 * order by release/added date, so they use the plain list.
 */
@Composable
fun HomeScreen(
    client: JellyfinClient,
    player: PlayerController,
    onAlbumClick: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenLibrary: (Int) -> Unit,
    onArtistClick: (String) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    // Returning to Home shows the last load instantly (no spinner); it
    // refreshes quietly in the background when that load is old.
    val cached = HomeCache.data
    var loading by remember { mutableStateOf(cached == null) }
    var loadError by remember { mutableStateOf(false) }
    var tiles by remember { mutableStateOf(cached?.tiles.orEmpty()) }
    var newReleases by remember { mutableStateOf(cached?.newReleases.orEmpty()) }
    var rediscover by remember { mutableStateOf(cached?.rediscover.orEmpty()) }
    var recentlyPlayed by remember { mutableStateOf(cached?.recentlyPlayed.orEmpty()) }
    var recentlyAdded by remember { mutableStateOf(cached?.recentlyAdded.orEmpty()) }
    var topArtists by remember { mutableStateOf(cached?.topArtists.orEmpty()) }
    var allTracks by remember { mutableStateOf(cached?.allTracks.orEmpty()) }
    var menuOpen by remember { mutableStateOf(false) }

    // Recommendations (load after the main shelves, never block Home).
    val context = androidx.compose.ui.platform.LocalContext.current
    val deezer = remember { com.oniel.music.data.DeezerClient(com.oniel.music.net.NetClients.http) }
    val recs = remember { Recommendations(context, deezer) }
    var libraryAlbums by remember { mutableStateOf(cached?.libraryAlbums.orEmpty()) }
    var picks by remember { mutableStateOf(HomeCache.picks) }
    var additions by remember { mutableStateOf(recs.cachedAdditions()) }
    var additionsRefreshing by remember { mutableStateOf(false) }
    var additionsError by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var openAddition by remember { mutableStateOf<com.oniel.music.data.Addition?>(null) }

    LaunchedEffect(Unit) {
        // Fresh app start: show last session's Home from the phone at once.
        if (cached == null) {
            HomeDiskCache.load(context)?.let { d ->
                if (HomeCache.data == null) HomeCache.restore(d)
                tiles = d.tiles
                newReleases = d.newReleases
                rediscover = d.rediscover
                recentlyPlayed = d.recentlyPlayed
                recentlyAdded = d.recentlyAdded
                topArtists = d.topArtists
                allTracks = d.allTracks
                libraryAlbums = d.libraryAlbums
                loading = false
            }
        }
        if (cached != null && !HomeCache.isStale()) return@LaunchedEffect
        try {
            val nowMs = System.currentTimeMillis()
            // The four library reads at the same time, not one after another.
            val (albums, albumsWithPlays, tracks, added) = coroutineScope {
                val a = async { client.getAlbums() }
                val p = async { client.getAlbumsWithTrackPlayData() }
                val t = async { client.getTracks() }
                val n = async { client.getRecentlyAddedAlbums(30) }
                HomeLoad(a.await(), p.await(), t.await(), n.await())
            }
            tiles = Scoring.scoreTiles(tracks, nowMs)
            newReleases = Scoring.newReleases(albums, nowMs)
            rediscover = Scoring.rediscoverAlbums(albumsWithPlays, nowMs)
            // Recently played from the enriched list: the server-side
            // DatePlayed sort on albums is fed by the same counters that
            // never move, so it would order nothing.
            recentlyPlayed = albumsWithPlays
                .filter { it.lastPlayedDate != null }
                .sortedByDescending { it.lastPlayedDate }
                .take(20)
            recentlyAdded = added
            allTracks = tracks
            libraryAlbums = albums
            // Recommended stations: the artists with the most played albums
            // (play counts summed from their tracks).
            topArtists = albumsWithPlays
                .groupBy { it.artist }
                .map { (artist, artistAlbums) ->
                    ArtistStation(
                        name = artist,
                        plays = artistAlbums.sumOf { it.playCount },
                        coverAlbum = artistAlbums.maxByOrNull { it.playCount }
                    )
                }
                .filter { it.name.isNotBlank() }
                .sortedByDescending { it.plays }
                .take(6)
            val fresh = HomeCache.Data(
                tiles, newReleases, rediscover, recentlyPlayed, recentlyAdded,
                topArtists, allTracks, libraryAlbums
            )
            HomeCache.store(fresh)
            HomeDiskCache.save(context, fresh)
        } catch (e: Exception) {
            // Keep showing the saved shelves if there are any.
            if (HomeCache.data == null) loadError = true
        } finally {
            loading = false
        }
    }

    // "You might enjoy" + "Recommended additions", once the library is loaded.
    LaunchedEffect(libraryAlbums, refreshTick) {
        if (libraryAlbums.isEmpty()) return@LaunchedEffect
        // Already worked out for this library load: keep it.
        if (refreshTick == 0 && HomeCache.picksFor === libraryAlbums && picks.isNotEmpty()) return@LaunchedEffect
        val nowMs = System.currentTimeMillis()
        val plays = player.playLogEntries()
        val profile = com.oniel.music.data.Taste.build(plays, allTracks, nowMs)
        picks = try {
            recs.youMightEnjoy(client, profile, libraryAlbums, allTracks, plays, nowMs)
        } catch (e: Exception) {
            emptyList()
        }
        HomeCache.picks = picks
        HomeCache.picksFor = libraryAlbums
        additionsRefreshing = true
        try {
            additions = recs.additions(profile, allTracks, libraryAlbums, nowMs, force = refreshTick > 0)
            additionsError = false
        } catch (e: Exception) {
            additionsError = true
        } finally {
            additionsRefreshing = false
        }
    }

    openAddition?.let { addition ->
        AdditionDialog(
            addition = addition,
            deezer = deezer,
            player = player,
            onDismiss = { openAddition = null }
        )
    }

    // Scrolls up under the frosted status-bar strip.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
            bottom = bottomChromePadding()
        )
    ) {
        item {
            // Top row: profile icon (menu: Friends, Listening stats, Settings).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 16.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            imageVector = Icons.Filled.AccountCircle,
                            contentDescription = "Profile",
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.secondary
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_friends)) },
                            onClick = { menuOpen = false; onOpenFriends() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_stats)) },
                            onClick = { menuOpen = false; onOpenStats() }
                        )
                        // Settings lives at the bottom of the profile menu.
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tab_settings)) },
                            leadingIcon = {
                                Icon(imageVector = Icons.Filled.Settings, contentDescription = null)
                            },
                            onClick = { menuOpen = false; onOpenSettings() }
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.home_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 64.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            }
        } else if (loadError) {
            item {
                Text(
                    text = stringResource(R.string.home_load_error),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
        } else {
            // (1) Quick grid — 8 scored tiles, 2 columns x 4 rows. Each
            // tile is the best-scoring track of one album.
            if (tiles.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        tiles.chunked(2).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                row.forEach { track ->
                                    QuickTile(
                                        track = track,
                                        imageUrl = client.trackArtUrl(track, 300),
                                        onClick = {
                                            val albumId = track.albumId
                                            if (albumId != null) {
                                                onAlbumClick(albumId)
                                            } else {
                                                player.playSingle(track)
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }
            }

            // You might enjoy (from the library) + Recommended additions (not in it).
            if (picks.isNotEmpty()) {
                item {
                    YouMightEnjoyRow(
                        picks = picks,
                        client = client,
                        onAlbumClick = onAlbumClick,
                        onArtistClick = onArtistClick,
                        onPlayTrack = { player.playSingle(it) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            item {
                AdditionsRow(
                    items = additions,
                    refreshing = additionsRefreshing,
                    error = additionsError,
                    onRefresh = { refreshTick++ },
                    onOpen = { openAddition = it }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // (2) New releases.
            if (newReleases.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_new_releases)) { onOpenLibrary(1) } }
                item {
                    AlbumShelf(
                        albums = newReleases,
                        client = client,
                        onAlbumClick = onAlbumClick
                    )
                }
            }

            // (3) Rediscover albums.
            if (rediscover.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_rediscover)) { onOpenLibrary(1) } }
                item {
                    AlbumShelf(
                        albums = rediscover,
                        client = client,
                        onAlbumClick = onAlbumClick
                    )
                }
            }

            // (4) Recently played.
            if (recentlyPlayed.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_recently_played)) { onOpenLibrary(1) } }
                item {
                    AlbumShelf(
                        albums = recentlyPlayed,
                        client = client,
                        onAlbumClick = onAlbumClick
                    )
                }
            }

            // (5) Recently added.
            if (recentlyAdded.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_recently_added)) { onOpenLibrary(1) } }
                item {
                    AlbumShelf(
                        albums = recentlyAdded,
                        client = client,
                        onAlbumClick = onAlbumClick
                    )
                }
            }

            // (6) Recommended stations (top artists).
            if (topArtists.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_stations)) { onOpenLibrary(0) } }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(topArtists) { station ->
                            StationCard(
                                station = station,
                                imageUrl = station.coverAlbum?.artistId?.let {
                                    client.artistImageUrl(it, 300)
                                },
                                fallbackUrl = station.coverAlbum?.let {
                                    client.albumArtUrl(it, 300)
                                },
                                onClick = {
                                    val artistTracks = allTracks.filter {
                                        it.artists.contains(station.name) ||
                                            it.albumArtist == station.name
                                    }.shuffled()
                                    if (artistTracks.isNotEmpty()) {
                                        player.play(artistTracks, 0)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // (7) Made for you (mixes by score: one mix per top artist).
            if (topArtists.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    ShelfHeader(title = stringResource(R.string.shelf_made_for_you))
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(topArtists) { station ->
                            AlbumCard(
                                title = stringResource(R.string.mix_title, station.name),
                                subtitle = stringResource(R.string.mix_subtitle),
                                imageUrl = station.coverAlbum?.let {
                                    client.albumArtUrl(it, 300)
                                },
                                onClick = {
                                    val artistTracks = allTracks.filter {
                                        it.artists.contains(station.name) ||
                                            it.albumArtist == station.name
                                    }.shuffled()
                                    if (artistTracks.isNotEmpty()) {
                                        player.play(artistTracks, 0)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

data class ArtistStation(
    val name: String,
    val plays: Int,
    val coverAlbum: Album?
)

@Composable
private fun QuickTile(
    track: Track,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Loose song (no album): the artist's picture, in a round frame.
            val loose = track.albumId == null
            CoverImage(
                url = imageUrl,
                modifier = Modifier
                    .padding(if (loose) 4.dp else 0.dp)
                    .size(if (loose) 48.dp else 56.dp),
                cornerDp = if (loose) 24.dp else 8.dp
            )
            Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                Text(
                    text = track.album.ifBlank { track.title },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = track.albumArtist.ifBlank { track.artists.joinToString(", ") },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AlbumShelf(
    albums: List<Album>,
    client: JellyfinClient,
    onAlbumClick: (String) -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(albums) { album ->
            AlbumCard(
                title = album.name,
                subtitle = album.artist,
                imageUrl = client.albumArtUrl(album, 300),
                onClick = { onAlbumClick(album.id) }
            )
        }
    }
}

@Composable
private fun StationCard(
    station: ArtistStation,
    imageUrl: String?,
    fallbackUrl: String?,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(120.dp)
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CoverImage(
            url = imageUrl,
            modifier = Modifier.size(120.dp),
            cornerDp = 60.dp,
            contentDescription = station.name,
            fallbackUrl = fallbackUrl
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = station.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.station_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private data class HomeLoad(
    val albums: List<Album>,
    val albumsWithPlays: List<Album>,
    val tracks: List<Track>,
    val added: List<Album>
)

/**
 * Home's last load, kept while the app runs so going back to Home is
 * instant. Older than [MAX_AGE_MS] → refreshed in the background on the
 * next visit (the old shelves stay up meanwhile). Also lends its song
 * list to the genre pages' "In library" matching.
 */
object HomeCache {
    private const val MAX_AGE_MS = 5 * 60_000L

    data class Data(
        val tiles: List<Track>,
        val newReleases: List<Album>,
        val rediscover: List<Album>,
        val recentlyPlayed: List<Album>,
        val recentlyAdded: List<Album>,
        val topArtists: List<ArtistStation>,
        val allTracks: List<Track>,
        val libraryAlbums: List<Album>
    )

    @Volatile var data: Data? = null
        private set
    @Volatile private var loadedAt = 0L
    @Volatile var picks: List<com.oniel.music.data.Pick> = emptyList()
    @Volatile var picksFor: List<Album>? = null

    fun isStale() = System.currentTimeMillis() - loadedAt > MAX_AGE_MS

    fun store(d: Data) {
        data = d
        loadedAt = System.currentTimeMillis()
    }

    /** Data read back from the phone: shown at once but treated as old. */
    fun restore(d: Data) {
        data = d
        loadedAt = 0L
    }

    /** Forget everything (log out / server change), including the saved copy. */
    fun clear(context: android.content.Context? = null) {
        context?.let { HomeDiskCache.delete(it) }
        data = null
        loadedAt = 0L
        picks = emptyList()
        picksFor = null
    }
}
