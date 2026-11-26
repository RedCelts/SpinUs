package com.oniel.music.ui

import androidx.compose.foundation.background
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
import kotlinx.coroutines.launch
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
import com.oniel.music.data.SpinUsClient
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
    client: SpinUsClient,
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
    var stations by remember { mutableStateOf(HomeCache.stations) }
    var mixes by remember { mutableStateOf(HomeCache.mixes) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var additions by remember { mutableStateOf(HomeCache.additions) }
    var additionsRefreshing by remember { mutableStateOf(false) }
    var additionsError by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }
    var openAddition by remember { mutableStateOf<com.oniel.music.data.Addition?>(null) }

    LaunchedEffect(Unit) {
        if (additions.isEmpty()) {
            additions = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { recs.cachedAdditions() }
            HomeCache.additions = additions
        }
    }

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
            // The library reads at the same time, not one after another.
            // Albums already carry their tracks' play data, so one album
            // list serves both uses (it used to be downloaded twice).
            val (albums, albumsWithPlays, tracks, added) = coroutineScope {
                val a = async { client.getAlbums() }
                val t = async { client.getTracks() }
                val n = async { client.getRecentlyAddedAlbums(30) }
                val all = a.await()
                HomeLoad(all, all, t.await(), n.await())
            }
            // The shelf maths runs off the main thread, so the screen
            // never freezes while a big library is scored.
            val fresh = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val fTiles = Scoring.scoreTiles(tracks, nowMs)
            val fNew = Scoring.newReleases(albums, nowMs)
            val fRediscover = Scoring.rediscoverAlbums(albumsWithPlays, nowMs)
            // Recently played: albums carry their tracks' last-played dates.
            val fRecent = albumsWithPlays
                .filter { it.lastPlayedDate != null }
                .sortedByDescending { it.lastPlayedDate }
                .take(20)
            // Rule 6: albums newest first, only ones with scanned songs.
            val fAdded = com.oniel.music.data.RecentlyAddedRule.apply(added, tracks)
            // Recommended stations: the artists with the most played albums
            // (play counts summed from their tracks).
            val fTop = albumsWithPlays
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
            HomeCache.Data(fTiles, fNew, fRediscover, fRecent, fAdded, fTop, tracks, albums)
            }
            tiles = fresh.tiles
            newReleases = fresh.newReleases
            rediscover = fresh.rediscover
            recentlyPlayed = fresh.recentlyPlayed
            recentlyAdded = fresh.recentlyAdded
            topArtists = fresh.topArtists
            allTracks = fresh.allTracks
            libraryAlbums = fresh.libraryAlbums
            HomeCache.store(fresh)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { HomeDiskCache.save(context, fresh) }
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
        // (Even when they came out empty: recomputing on every return to
        // Home is what made going back slow.)
        if (refreshTick == 0 && HomeCache.picksFor === libraryAlbums) return@LaunchedEffect
        val nowMs = System.currentTimeMillis()
        val plays = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { player.playLogEntries() }
        val signals = try { player.signalSnapshot() } catch (e: Exception) { com.oniel.music.data.SignalSnapshot.EMPTY }
        val (profile, ctx) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.oniel.music.data.Taste.build(plays, allTracks, nowMs) to
                com.oniel.music.data.RuleContext(allTracks, plays, signals, nowMs)
        }
        picks = try {
            recs.youMightEnjoy(client, profile, libraryAlbums, allTracks, plays, nowMs)
                // Guardrails 2 + 3 on single-song picks.
                .filter { p -> p !is com.oniel.music.data.Pick.TrackPick || ctx.recommendable(p.track) }
        } catch (e: Exception) {
            emptyList()
        }
        HomeCache.picks = picks
        // Rule 7: stations seeded by your top artists of the last 60 days,
        // minus the artists already in the first 3 "You might enjoy" cards.
        val top3 = picks.take(3).mapNotNull { p ->
            when (p) {
                is com.oniel.music.data.Pick.AlbumPick -> p.album.artist
                is com.oniel.music.data.Pick.ArtistPick -> p.name
                is com.oniel.music.data.Pick.TrackPick -> ctx.artistOf(p.track)
            }
        }.map { com.oniel.music.data.matchKey(it) }.toSet()
        val seeds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            com.oniel.music.data.StationRules.seeds(ctx, top3)
        }
        if (seeds.size >= 1) {
            stations = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { seeds.map { (name, plays) ->
                val key = com.oniel.music.data.matchKey(name)
                ArtistStation(
                    name = name,
                    plays = plays,
                    coverAlbum = libraryAlbums.filter { com.oniel.music.data.matchKey(it.artist) == key }
                        .maxByOrNull { it.playCount }
                        ?: libraryAlbums.firstOrNull { a ->
                            allTracks.any { t -> t.albumId == a.id && ctx.artistsOf(t).any { com.oniel.music.data.matchKey(it) == key } }
                        }
                )
            } }
            HomeCache.stations = stations
        }
        // Rule 8: Made for you (artist, decade and time-of-day mixes; daily).
        mixes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try { com.oniel.music.data.MixRules.build(ctx) } catch (e: Exception) { emptyList() }
        }
        HomeCache.mixes = mixes
        HomeCache.picksFor = libraryAlbums
        additionsRefreshing = true
        try {
            additions = recs.additions(profile, allTracks, libraryAlbums, nowMs, force = refreshTick > 0)
            HomeCache.additions = additions
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

    // Guardrail 1: an album appears on at most 2 shelves per Home load
    // (shelves in screen order; later shelves give up the repeats).
    val guarded = remember(tiles, picks, newReleases, rediscover, recentlyPlayed, recentlyAdded) {
        com.oniel.music.data.limitRepeats(
            listOf<List<Any>>(tiles, picks, newReleases, rediscover, recentlyPlayed, recentlyAdded),
            albumIdOf = { item ->
                when (item) {
                    is Track -> item.albumId
                    is Album -> item.id
                    is com.oniel.music.data.Pick.AlbumPick -> item.album.id
                    is com.oniel.music.data.Pick.TrackPick -> item.track.albumId
                    else -> null
                }
            }
        )
    }
    @Suppress("UNCHECKED_CAST") val shownTiles = guarded[0] as List<Track>
    @Suppress("UNCHECKED_CAST") val shownPicks = guarded[1] as List<com.oniel.music.data.Pick>
    @Suppress("UNCHECKED_CAST") val shownNew = guarded[2] as List<Album>
    @Suppress("UNCHECKED_CAST") val shownRediscover = guarded[3] as List<Album>
    @Suppress("UNCHECKED_CAST") val shownRecentPlayed = guarded[4] as List<Album>
    @Suppress("UNCHECKED_CAST") val shownRecentAdded = guarded[5] as List<Album>

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
                        // Badge: recommendations from friends not seen yet.
                        androidx.compose.material3.BadgedBox(
                            badge = {
                                if (Inbox.unread > 0) {
                                    androidx.compose.material3.Badge {
                                        Text(if (Inbox.unread > 99) "99+" else Inbox.unread.toString())
                                    }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AccountCircle,
                                contentDescription = "Profile",
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.menu_friends)) },
                            trailingIcon = {
                                if (Inbox.unread > 0) {
                                    androidx.compose.material3.Badge { Text(Inbox.unread.toString()) }
                                }
                            },
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
            if (shownTiles.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                        shownTiles.chunked(2).forEach { row ->
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
            if (shownPicks.isNotEmpty()) {
                item {
                    YouMightEnjoyRow(
                        picks = shownPicks,
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
            if (shownNew.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_new_releases)) { onOpenLibrary(1) } }
                item {
                    AlbumShelf(
                        albums = shownNew,
                        client = client,
                        onAlbumClick = onAlbumClick
                    )
                }
            }

            // (3) Rediscover albums.
            if (shownRediscover.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_rediscover)) { onOpenLibrary(1) } }
                item {
                    AlbumShelf(
                        albums = shownRediscover,
                        client = client,
                        onAlbumClick = onAlbumClick
                    )
                }
            }

            // (4) Recently played.
            if (shownRecentPlayed.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_recently_played)) { onOpenLibrary(1) } }
                item {
                    AlbumShelf(
                        albums = shownRecentPlayed,
                        client = client,
                        onAlbumClick = onAlbumClick
                    )
                }
            }

            // (5) Recently added.
            if (shownRecentAdded.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_recently_added)) { onOpenLibrary(1) } }
                item {
                    AlbumShelf(
                        albums = shownRecentAdded,
                        client = client,
                        onAlbumClick = onAlbumClick
                    )
                }
            }

            // (6) Recommended stations: rule 7 (falls back to the most-played
            // artists until the play log has 60 days of plays to go on).
            val stationList = stations.ifEmpty { topArtists }
            if (stationList.isNotEmpty()) {
                item { ShelfHeader(title = stringResource(R.string.shelf_stations)) { onOpenLibrary(0) } }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(stationList) { station ->
                            StationCard(
                                station = station,
                                imageUrl = station.coverAlbum?.artistId?.let {
                                    client.artistImageUrl(it, 300)
                                },
                                fallbackUrl = station.coverAlbum?.let {
                                    client.albumArtUrl(it, 300)
                                },
                                onClick = {
                                    scope.launch {
                                        val queue = buildStation(station, client, player, allTracks)
                                        if (queue.isNotEmpty()) player.play(queue, 0)
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // (7) Made for you: rule 8 mixes (4–6 cards, new each day).
            if (mixes.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    ShelfHeader(title = stringResource(R.string.shelf_made_for_you))
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(mixes, key = { it.id }) { mix ->
                            MixCard(
                                mix = mix,
                                client = client,
                                onClick = { player.play(mix.tracks, 0) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Builds a station when tapped (rule 7): your library + the server's
 * instant mix for the artist, mixed 25 / 50 / 25 with artist spacing.
 */
private suspend fun buildStation(
    station: ArtistStation,
    client: SpinUsClient,
    player: PlayerController,
    library: List<Track>
): List<Track> {
    val artistId = station.coverAlbum?.artistId
    val instant = if (artistId != null) {
        try { client.getInstantMix(artistId) } catch (e: Exception) { emptyList() }
    } else emptyList()
    val signals = try { player.signalSnapshot() } catch (e: Exception) { com.oniel.music.data.SignalSnapshot.EMPTY }
    val ctx = com.oniel.music.data.RuleContext(library, player.playLogEntries(), signals)
    return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        com.oniel.music.data.StationRules.build(station.name, ctx, instant)
    }
}

/** A mix card: 2×2 collage of the mix's first covers, title, song count. */
@Composable
private fun MixCard(mix: com.oniel.music.data.Mix, client: SpinUsClient, onClick: () -> Unit) {
    val covers = remember(mix.id, mix.tracks) {
        mix.tracks.distinctBy { it.albumId ?: it.artistId ?: it.id }.take(4)
    }
    Column(
        modifier = Modifier
            .width(150.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column {
                covers.chunked(2).forEach { row ->
                    Row {
                        row.forEach { t ->
                            CoverImage(
                                url = try { client.trackArtUrl(t, 150) } catch (e: Exception) { null },
                                modifier = Modifier.size(75.dp),
                                cornerDp = 0.dp
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0.55f to androidx.compose.ui.graphics.Color.Transparent,
                            1f to androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)
                        )
                    )
            )
            Text(
                text = mix.title,
                color = androidx.compose.ui.graphics.Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = mix.subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stringResource(R.string.mix_song_count, mix.tracks.size),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    client: SpinUsClient,
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
    @Volatile var stations: List<ArtistStation> = emptyList()
    @Volatile var mixes: List<com.oniel.music.data.Mix> = emptyList()
    /** "Recommended additions" last shown (saves a file read on every return to Home). */
    @Volatile var additions: List<com.oniel.music.data.Addition> = emptyList()

    fun isStale() = System.currentTimeMillis() - loadedAt > MAX_AGE_MS

    /** When the shown data was loaded from the server (0 = from the phone). */
    fun loadedAtMs() = loadedAt

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
        LibraryCache.clear()
        data = null
        loadedAt = 0L
        picks = emptyList()
        picksFor = null
        stations = emptyList()
        mixes = emptyList()
        additions = emptyList()
    }
}
