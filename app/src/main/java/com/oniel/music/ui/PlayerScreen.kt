package com.oniel.music.ui

import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oniel.music.R
import com.oniel.music.data.FriendSession
import com.oniel.music.data.ItemDetails
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.LyricLine
import com.oniel.music.data.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Full player: cover, current lyric line, title/artist, seek bar, transport
 * controls, the listener icon above the "+" add-to-playlist button, and the
 * queue. Drag down from the top to minimize: past about a third of the
 * screen it closes, sooner it snaps back.
 *
 * Lyrics show ONLY when the server returns a non-empty (synced) list —
 * plain-text lyrics are treated as no lyrics.
 */
@Composable
fun PlayerScreen(
    client: JellyfinClient,
    player: PlayerController,
    myUserId: String,
    onMinimize: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onArtistClick: (artistId: String?, artistName: String) -> Unit = { _, _ -> }
) {
    val track = player.currentTrack
    var artistDetails by remember { mutableStateOf<ItemDetails?>(null) }
    var albumDetails by remember { mutableStateOf<ItemDetails?>(null) }

    // Artist + album metadata for the backdrop, the details line and the
    // "About the artist" card. Cached in the client, so skipping back and
    // forth inside one album costs nothing.
    LaunchedEffect(track?.id) {
        val current = track ?: return@LaunchedEffect
        albumDetails = current.albumId?.let {
            try { client.getItemDetails(it) } catch (e: Exception) { null }
        }
        val artistId = current.artistId ?: albumDetails?.artistId
        val artistName = current.artists.firstOrNull() ?: current.albumArtist
        artistDetails = try {
            when {
                artistId != null -> client.getItemDetails(artistId)
                artistName.isNotBlank() -> client.getArtistByName(artistName)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
    var listeners by remember { mutableStateOf<List<FriendSession>>(emptyList()) }
    var showFullLyrics by remember { mutableStateOf(false) }
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var emojiTarget by remember { mutableStateOf<FriendSession?>(null) }

    // Presence: who else is on this same song right now, refreshed every 5s.
    LaunchedEffect(track?.id) {
        while (true) {
            val current = track
            if (current != null) {
                try {
                    listeners = client.getSessions().filter {
                        it.userId != myUserId &&
                            it.trackTitle.equals(current.title, ignoreCase = true)
                    }
                } catch (e: Exception) {
                    // Presence is best-effort; the player keeps working.
                }
            }
            delay(5_000)
        }
    }

    if (track == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.player_nothing_playing))
        }
        return
    }

    if (showFullLyrics) {
        FullLyricsScreen(
            lines = player.syncedLyrics ?: emptyList(),
            currentLine = player.currentLyricLine,
            onClose = { showFullLyrics = false }
        )
        return
    }

    // How far the whole player has been pulled down (closes past a quarter).
    var sheetOffset by remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeightPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }

    fun settleSheet(velocityY: Float = 0f) {
        scope.launch {
            if (sheetOffset > screenHeightPx / 4f || velocityY > 2500f) {
                onMinimize()
            } else {
                androidx.compose.animation.core.animate(sheetOffset, 0f) { value, _ -> sheetOffset = value }
            }
        }
    }
    // Swipe down anywhere: once the list is at the top, the rest of a
    // downward drag pulls the player down instead.
    val dismissScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (sheetOffset > 0f && available.y < 0f) {
                    val next = (sheetOffset + available.y).coerceAtLeast(0f)
                    val used = next - sheetOffset
                    sheetOffset = next
                    return Offset(0f, used)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f && source == NestedScrollSource.Drag) {
                    sheetOffset += available.y
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (sheetOffset > 0f) {
                    settleSheet(available.y)
                    return available
                }
                return Velocity.Zero
            }
        }
    }
    val listState = rememberLazyListState()
    // The controls row is item 3; once it scrolls away, a compact
    // controls card drops down at the top.
    val showStickyControls by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 3 ||
                (listState.firstVisibleItemIndex == 3 && listState.firstVisibleItemScrollOffset > 40)
        }
    }

    // Liked (favorite) state of the current song, for the heart.
    var liked by remember(track.id) { mutableStateOf(client.favoriteOverride(track.id) ?: track.isFavorite) }
    LaunchedEffect(track.id) {
        liked = try { client.isFavorite(track.id) } catch (e: Exception) { liked }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val toggleLike: () -> Unit = {
        val want = !liked
        liked = want
        scope.launch {
            try {
                client.setFavorite(track.id, want)
            } catch (e: Exception) {
                liked = !want
                android.widget.Toast.makeText(context, R.string.action_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    val coverUrl = client.trackArtUrl(track, 600)
    // Background: the artist's backdrop, else the album's, else the cover blurred.
    val backdropUrl = client.backdropFor(artistDetails) ?: client.backdropFor(albumDetails)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, sheetOffset.roundToInt()) }
    ) {
    ScreenBackdrop(backdropUrl = backdropUrl, fallbackUrl = coverUrl)
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(dismissScroll),
        contentPadding = PaddingValues(bottom = bottomChromePadding())
    ) {
        item {
            // Top area: drag handle + cover. Dragging down here moves the
            // whole player; past a third of the screen it minimizes.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onVerticalDrag = { _, dragAmount ->
                                sheetOffset = (sheetOffset + dragAmount).coerceAtLeast(0f)
                            },
                            onDragEnd = { settleSheet() }
                        )
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMinimize) {
                        Icon(
                            imageVector = Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.player_minimize)
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Spacer(modifier = Modifier.size(48.dp))
                }
                CoverImage(
                    url = coverUrl,
                    modifier = Modifier
                        .size(300.dp)
                        .padding(top = 8.dp),
                    cornerDp = 12.dp,
                    contentDescription = track.album
                )
                // Current lyric line, in a fixed two-line slot so the cover
                // never jumps when the line wraps. Tap for fullscreen lyrics.
                val lines = player.syncedLyrics
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 32.dp)
                        .clickable(enabled = lines != null) { showFullLyrics = true },
                    contentAlignment = Alignment.Center
                ) {
                    val current = player.currentLyricLine
                    if (current != null) {
                        Text(
                            text = current.text,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.Top
            ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                // Tapping the artist opens the artist page.
                val artistLine = track.artists.joinToString(", ").ifEmpty { track.albumArtist }
                Text(
                    text = artistLine,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = artistLine.isNotBlank()) {
                        onArtistClick(
                            artistDetails?.id ?: track.artistId,
                            track.artists.firstOrNull() ?: track.albumArtist
                        )
                    }
                )
                // Tapping the album name opens the album page.
                val albumId = track.albumId
                Text(
                    text = track.album,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .then(
                            if (albumId != null) {
                                Modifier.clickable { onAlbumClick(albumId) }
                            } else {
                                Modifier
                            }
                        )
                )
                val genres = albumDetails?.genres.orEmpty().ifEmpty { artistDetails?.genres.orEmpty() }
                DetailLine(
                    listOf(albumDetails?.year?.toString(), genres.firstOrNull()),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            // Heart: add to / remove from Liked.
            LikeButton(liked = liked, onClick = toggleLike)
            }
        }

        item {
            SeekBar(player = player)
        }

        item {
            // Transport controls.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { player.toggleShuffle() }) {
                    ShuffleGlyph(
                        modifier = Modifier.size(28.dp),
                        active = player.shuffleEnabled
                    )
                }
                IconButton(onClick = { player.previous() }) {
                    PrevGlyph(modifier = Modifier.size(32.dp))
                }
                IconButton(
                    onClick = { player.togglePlayPause() },
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    if (player.isPlaying) {
                        PauseGlyph(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        PlayGlyph(
                            modifier = Modifier.size(32.dp),
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                IconButton(onClick = { player.next() }) {
                    NextGlyph(modifier = Modifier.size(32.dp))
                }
                IconButton(onClick = { player.cycleRepeat() }) {
                    RepeatGlyph(
                        modifier = Modifier.size(28.dp),
                        active = player.repeatMode != 0,
                        repeatOne = player.repeatMode == 2
                    )
                }
            }
        }

        item {
            // Listener icon directly above the "+" add-to-playlist button.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val first = listeners.firstOrNull()
                    if (first != null) {
                        Box(
                            modifier = Modifier.clickable { emojiTarget = first }
                        ) {
                            ListenerAvatar(userName = first.userName, sizeDp = 40.dp)
                            if (listeners.size > 1) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = listeners.size.toString(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    }
                    IconButton(onClick = { showAddToPlaylist = true }) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.player_add_to_playlist)
                        )
                    }
                }
            }
        }

        // About the artist.
        val artist = artistDetails
        if (artist != null && (artist.overview != null || artist.primaryImageTag != null || artist.backdrop != null)) {
            item {
                ArtistInfoCard(
                    client = client,
                    artist = artist,
                    onClick = { onArtistClick(artist.id, artist.name) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }

        // Queue ("Up next").
        if (player.queue.size > 1) {
            item {
                Text(
                    text = stringResource(R.string.player_up_next),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp)
                )
            }
            itemsIndexed(player.queue) { index, queueTrack ->
                TrackRow(
                    number = index + 1,
                    track = queueTrack,
                    isCurrent = index == player.currentIndex,
                    onClick = { player.playAt(index) }
                )
            }
        }
    }

    // Compact controls, anchored to the top once the big ones scroll away.
    AnimatedVisibility(
        visible = showStickyControls,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter)
    ) {
        StickyControls(
            track = track,
            coverUrl = client.trackArtUrl(track, 200),
            player = player,
            liked = liked,
            onLike = toggleLike,
            onTap = { scope.launch { listState.animateScrollToItem(0) } }
        )
    }
    }

    if (showAddToPlaylist) {
        val playlistTrackId = track.id
        AddToPlaylistDialog(
            client = client,
            title = track.title,
            resolveTrackIds = { listOf(playlistTrackId) },
            onDismiss = { showAddToPlaylist = false }
        )
    }

    val target = emojiTarget
    if (target != null) {
        EmojiPickerDialog(
            userName = target.userName,
            onEmojiPicked = { emoji ->
                scope.launch {
                    try {
                        for (sessionId in target.sessionIds) {
                            client.sendMessage(sessionId, emoji)
                        }
                    } catch (e: Exception) {
                        // Sending is best-effort.
                    }
                }
                emojiTarget = null
            },
            onDismiss = { emojiTarget = null }
        )
    }
}

@Composable
private fun SeekBar(player: PlayerController) {
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val duration = player.durationMs.coerceAtLeast(1L)
    val shown = dragPosition ?: player.positionMs.toFloat()
    val density = LocalDensity.current
    val thumbHalfPx = with(density) { 10.dp.toPx() }
    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
        Box {
            // The slider only draws; a layer on top takes the touches.
            Slider(
                value = shown.coerceIn(0f, duration.toFloat()),
                onValueChange = { },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary
                )
            )
            // Seeking happens only on a left/right drag. A tap, or a swipe
            // up/down that starts on the bar (scrolling, closing the
            // player), never moves the song.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .pointerInput(duration) {
                        fun valueAt(x: Float): Float {
                            val track = (size.width - 2 * thumbHalfPx).coerceAtLeast(1f)
                            val fraction = ((x - thumbHalfPx) / track).coerceIn(0f, 1f)
                            return fraction * duration
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> dragPosition = valueAt(offset.x) },
                            onHorizontalDrag = { change, _ ->
                                change.consume()
                                dragPosition = valueAt(change.position.x)
                            },
                            onDragEnd = {
                                dragPosition?.let { player.seekTo(it.toLong()) }
                                dragPosition = null
                            },
                            onDragCancel = { dragPosition = null }
                        )
                    }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(shown.toLong()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Fullscreen synced lyrics. The current line is highlighted and the list
 * follows it as the song plays. Only ever shown with synced lyrics.
 */
@Composable
fun FullLyricsScreen(
    lines: List<LyricLine>,
    currentLine: LyricLine?,
    onClose: () -> Unit
) {
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val currentIndex = lines.indexOf(currentLine).coerceAtLeast(0)

    LaunchedEffect(currentIndex) {
        if (lines.isNotEmpty()) {
            listState.animateScrollToItem((currentIndex - 2).coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(top = 8.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.player_minimize)
                )
            }
            Text(
                text = stringResource(R.string.lyrics_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(lines) { line ->
                val isCurrent = line == currentLine
                Text(
                    text = line.text,
                    fontSize = if (isCurrent) 22.sp else 18.sp,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                    color = if (isCurrent) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun LikeButton(liked: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = stringResource(if (liked) R.string.action_unfavorite else R.string.action_favorite),
            tint = if (liked) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp)
        )
    }
}

/** Small card at the top of the player: cover, song, heart, prev / play / next, progress. */
@Composable
private fun StickyControls(
    track: Track,
    coverUrl: String?,
    player: PlayerController,
    liked: Boolean,
    onLike: () -> Unit,
    onTap: () -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onTap),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.96f),
        shadowElevation = 8.dp
    ) {
        Column {
            Row(
                modifier = Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CoverImage(url = coverUrl, modifier = Modifier.size(44.dp), cornerDp = 8.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artists.joinToString(", ").ifEmpty { track.albumArtist },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                LikeButton(liked = liked, onClick = onLike)
                IconButton(onClick = { player.previous() }) {
                    PrevGlyph(modifier = Modifier.size(22.dp))
                }
                IconButton(
                    onClick = { player.togglePlayPause() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                ) {
                    if (player.isPlaying) {
                        PauseGlyph(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        PlayGlyph(modifier = Modifier.size(20.dp), color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
                IconButton(onClick = { player.next() }) {
                    NextGlyph(modifier = Modifier.size(22.dp))
                }
            }
            val total = player.durationMs.coerceAtLeast(1L)
            LinearProgressIndicator(
                progress = (player.positionMs.toFloat() / total).coerceIn(0f, 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        }
    }
}
