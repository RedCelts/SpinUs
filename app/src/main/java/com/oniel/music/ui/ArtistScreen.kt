package com.oniel.music.ui

import androidx.compose.foundation.background
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.abs
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
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

    val listState = rememberLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
    // Pinned header picture; the list scrolls over it.
    FixedBackdrop(
        backdropUrl = client.backdropFor(current),
        fallbackUrl = photoUrl ?: albums.firstOrNull()?.let {
            try { client.albumArtUrl(it, 600) } catch (e: Exception) { null }
        },
        listState = listState,
        height = 320.dp
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomChromePadding())
    ) {
        item {
            BackdropHeader(
                height = 320.dp
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
                        .padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { player.play(tracks, 0); onTrackPlayed() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.play)) }
                    OutlinedButton(
                        onClick = { player.play(tracks.shuffled(), 0); onTrackPlayed() },
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.shuffle)) }
                    ItemMoreButton(target = ActionTarget.ArtistItem(current.id, current.name))
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
            item {
                AlbumCarousel(
                    albums = albums,
                    client = client,
                    onAlbumClick = onAlbumClick
                )
            }
        }

        // Songs with no album: this artist's singles (artist picture as cover).
        val singles = tracks.filter { it.albumId == null }
        if (singles.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.artist_singles)) }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(singles, key = { _, t -> t.id }) { index, single ->
                        AlbumCard(
                            title = single.title,
                            subtitle = stringResource(R.string.artist_singles_label),
                            imageUrl = try { client.trackArtUrl(single, 300) } catch (e: Exception) { null },
                            onClick = { player.play(singles, index); onTrackPlayed() },
                            cardWidth = 140.dp
                        )
                    }
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

/**
 * An artist's albums as a cover-flow carousel: the centred cover is full
 * size, its neighbours shrink, fade, dip and turn away as they slide
 * ("swoop" in and out while scrolling). Tapping a side cover brings it to
 * the centre; tapping the centred one zooms it up and opens the album
 * (the album page itself scales in — see AppNav).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumCarousel(
    albums: List<Album>,
    client: JellyfinClient,
    onAlbumClick: (String) -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { albums.size })
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf<String?>(null) }
    val zoom = remember { Animatable(1f) }

    // Tilt with the swipe: swiping left turns the covers 20°, swiping
    // right -20°; they ease back flat when the carousel stops.
    var tiltTarget by remember { mutableStateOf(0f) }
    LaunchedEffect(pagerState) {
        var last = pagerState.currentPage + pagerState.currentPageOffsetFraction
        snapshotFlow { pagerState.currentPage + pagerState.currentPageOffsetFraction }
            .collect { pos ->
                val moved = pos - last
                last = pos
                if (abs(moved) > 0.0005f) tiltTarget = if (moved > 0f) 20f else -20f
            }
    }
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) tiltTarget = 0f
    }
    val tilt by animateFloatAsState(
        targetValue = tiltTarget,
        animationSpec = tween(durationMillis = 220),
        label = "carouselTilt"
    )

    HorizontalPager(
        state = pagerState,
        contentPadding = PaddingValues(horizontal = 76.dp),
        pageSpacing = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) { page ->
        val album = albums[page]
        // Signed distance from the centre: 0 centred, ±1 one page away.
        val offset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
        val distance = abs(offset).coerceIn(0f, 1f)
        val isOpening = opening == album.id
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .zIndex(if (isOpening) 2f else 1f - distance)
                .graphicsLayer {
                    val scale = mix(1f, 0.76f, distance) * (if (isOpening) zoom.value else 1f)
                    scaleX = scale
                    scaleY = scale
                    alpha = mix(1f, 0.5f, distance)
                    rotationY = tilt
                    translationY = distance * 28.dp.toPx()
                    cameraDistance = 14f * density
                }
                .clickable {
                    if (opening != null) {
                        // Already opening one.
                    } else if (page != pagerState.currentPage) {
                        scope.launch { pagerState.animateScrollToPage(page) }
                    } else {
                        opening = album.id
                        scope.launch {
                            zoom.snapTo(1f)
                            zoom.animateTo(1.3f, tween(durationMillis = 240, easing = FastOutSlowInEasing))
                            onAlbumClick(album.id)
                            // Back on this page later: start from rest.
                            kotlinx.coroutines.delay(500)
                            zoom.snapTo(1f)
                            opening = null
                        }
                    }
                }
        ) {
            CoverImage(
                url = try { client.albumArtUrl(album, 600) } catch (e: Exception) { null },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadow(elevation = 8.dp, shape = RoundedCornerShape(16.dp)),
                cornerDp = 16.dp,
                contentDescription = album.name
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = album.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = album.year?.toString() ?: album.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
    // Page dots.
    if (albums.size > 1) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            albums.indices.take(12).forEach { i ->
                val selected = i == pagerState.currentPage.coerceAtMost(11)
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (selected) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.tertiary
                            else MaterialTheme.colorScheme.outline
                        )
                )
            }
        }
    }
}

private fun mix(start: Float, stop: Float, fraction: Float): Float = start + (stop - start) * fraction
