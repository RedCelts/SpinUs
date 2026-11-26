package com.oniel.music.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.oniel.music.R
import com.oniel.music.data.FriendSession
import com.oniel.music.data.SpinUsClient
import com.oniel.music.data.ListeningStats
import com.oniel.music.data.ListeningSummary
import com.oniel.music.data.PeriodStats
import com.oniel.music.data.StatItem
import com.oniel.music.data.WeekBar

/**
 * Your listening stats, from the play log (a play counts after 30 s of
 * real listening). Reloads when plays arrive from the account sync.
 */
@Composable
fun StatsScreen(
    client: SpinUsClient,
    player: PlayerController,
    onBack: () -> Unit
) {
    var summary by remember { mutableStateOf<ListeningSummary?>(null) }
    LaunchedEffect(player.historyVersion) {
        summary = try { player.buildListeningSummary() } catch (e: Exception) { null }
    }
    StatsContent(
        title = stringResource(R.string.stats_title),
        summary = summary,
        client = client,
        onBack = onBack,
        modifier = Modifier.statusBarsPadding()
    )
}

/**
 * A friend's stats, asked for from their app (they must have Oniel Music
 * open and sharing on). Full-screen, same look as your own.
 */
@Composable
fun FriendStatsDialog(
    friend: FriendSession,
    client: SpinUsClient,
    onDismiss: () -> Unit
) {
    var summary by remember { mutableStateOf<ListeningSummary?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    LaunchedEffect(attempt) {
        failed = false
        val json = try { client.requestFriendStats(friend) } catch (e: Exception) { null }
        summary = json?.let { ListeningStats.fromJson(it) }
        failed = summary == null
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (failed) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.friend_stats_failed, friend.userName),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Row {
                        TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.retry)) }
                        TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
                    }
                }
            } else {
                StatsContent(
                    title = stringResource(R.string.friend_stats_title, friend.userName),
                    summary = summary,
                    client = client,
                    onBack = onDismiss,
                    loadingText = stringResource(R.string.friend_stats_asking, friend.userName),
                    modifier = Modifier.statusBarsPadding()
                )
            }
        }
    }
}

private enum class StatsPeriod { WEEK, MONTH, ALL }

/** The stats layout: period chips, hero card, #1 artist, top artists, top songs, weekly chart. */
@Composable
fun StatsContent(
    title: String,
    summary: ListeningSummary?,
    client: SpinUsClient,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    loadingText: String? = null
) {
    var period by rememberSaveable { mutableStateOf(StatsPeriod.WEEK) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (summary == null) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                if (loadingText != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(loadingText, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }
        val stats = when (period) {
            StatsPeriod.WEEK -> summary.week
            StatsPeriod.MONTH -> summary.month
            StatsPeriod.ALL -> summary.allTime
        }
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottomChromePadding()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        StatsPeriod.WEEK to R.string.stats_period_week,
                        StatsPeriod.MONTH to R.string.stats_period_month,
                        StatsPeriod.ALL to R.string.stats_period_all
                    ).forEach { (p, label) ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(stringResource(label)) }
                        )
                    }
                }
            }
            item { HeroCard(stats, summary.streakDays) }
            stats.topArtists.firstOrNull()?.let { top ->
                item { TopArtistCard(top, client) }
            }
            if (stats.topArtists.size > 1) {
                item {
                    StatsCard(title = stringResource(R.string.stats_top_artists)) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) {
                            itemsIndexed(stats.topArtists) { index, artist ->
                                ArtistTile(rank = index + 1, artist = artist, client = client)
                            }
                        }
                    }
                }
            }
            if (stats.topSongs.isNotEmpty()) {
                item {
                    StatsCard(title = stringResource(R.string.stats_top_songs)) {
                        Column {
                            stats.topSongs.forEachIndexed { index, song ->
                                SongStatRow(rank = index + 1, song = song, client = client)
                            }
                        }
                    }
                }
            }
            if (stats.plays == 0) {
                item {
                    Text(
                        text = stringResource(R.string.stats_nothing_yet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (summary.weeks.any { it.minutes > 0 }) {
                item {
                    StatsCard(title = stringResource(R.string.stats_weekly_minutes)) {
                        WeekChart(summary.weeks)
                    }
                }
            }
            item {
                Text(
                    text = stringResource(R.string.stats_note_30s),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ---- Cards ----

@Composable
private fun StatsCard(title: String, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(vertical = 14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)
            )
            content()
        }
    }
}

/** Big gradient card: minutes, plays, day streak. */
@Composable
private fun HeroCard(stats: PeriodStats, streakDays: Int) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(c.primary, c.tertiary)))
            .padding(18.dp)
    ) {
        Column {
            Text(
                text = stats.minutes.toString(),
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                color = c.onPrimary
            )
            Text(
                text = stringResource(R.string.stats_minutes_listened),
                style = MaterialTheme.typography.titleSmall,
                color = c.onPrimary.copy(alpha = 0.85f)
            )
            Spacer(modifier = Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HeroPill(stringResource(R.string.stats_plays_pill, stats.plays))
                HeroPill(stringResource(R.string.stats_artists_pill, stats.topArtists.size))
                if (streakDays > 0) HeroPill(stringResource(R.string.stats_streak_pill, streakDays))
            }
        }
    }
}

@Composable
private fun HeroPill(text: String) {
    val c = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = c.onPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(c.onPrimary.copy(alpha = 0.18f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

/** #1 artist: blurred photo behind, big round photo in front. */
@Composable
private fun TopArtistCard(artist: StatItem, client: SpinUsClient) {
    val url = artist.imageId?.let { try { client.imageUrl(it, null, 600) } catch (e: Exception) { null } }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(24.dp)
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ArtistPhoto(name = artist.name, imageId = artist.imageId, client = client, size = 110)
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                val onImage = if (url != null) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                Text(
                    text = stringResource(R.string.stats_number_one),
                    style = MaterialTheme.typography.labelLarge,
                    color = onImage.copy(alpha = 0.8f)
                )
                Text(
                    text = artist.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = onImage,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.stats_plays, artist.plays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = onImage.copy(alpha = 0.85f)
                )
            }
        }
    }
}

@Composable
private fun ArtistTile(rank: Int, artist: StatItem, client: SpinUsClient) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
        Box {
            ArtistPhoto(name = artist.name, imageId = artist.imageId, client = client, size = 84)
            RankBadge(rank, Modifier.align(Alignment.TopStart).offset(x = (-2).dp, y = (-2).dp))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = artist.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.stats_plays, artist.plays),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SongStatRow(rank: Int, song: StatItem, client: SpinUsClient) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (rank <= 3) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(28.dp)
        )
        CoverImage(
            url = song.imageId?.let { try { client.imageUrl(it, null, 160) } catch (e: Exception) { null } },
            modifier = Modifier.size(48.dp),
            cornerDp = 6.dp,
            contentDescription = song.name
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = stringResource(R.string.stats_plays, song.plays),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RankBadge(rank: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onTertiary
        )
    }
}

/** Round artist photo; initials underneath show through if there's none. */
@Composable
fun ArtistPhoto(name: String, imageId: String?, client: SpinUsClient, size: Int) {
    Box(modifier = Modifier.size(size.dp).clip(CircleShape)) {
        ListenerAvatar(userName = name, sizeDp = size.dp)
        if (imageId != null) {
            AsyncImage(
                model = try { client.imageUrl(imageId, null, size * 3) } catch (e: Exception) { null },
                contentDescription = name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** Minutes per week, last 8 weeks; this week highlighted. */
@Composable
private fun WeekChart(weeks: List<WeekBar>) {
    val c = MaterialTheme.colorScheme
    val max = (weeks.maxOfOrNull { it.minutes } ?: 0).coerceAtLeast(1)
    Column(modifier = Modifier.padding(horizontal = 14.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
        ) {
            val n = weeks.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = slot * 0.6f
            weeks.forEachIndexed { i, w ->
                val h = (w.minutes.toFloat() / max) * size.height
                val color = if (i == weeks.lastIndex) c.tertiary else c.primary.copy(alpha = 0.55f)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * slot + (slot - barW) / 2f, size.height - h.coerceAtLeast(3f)),
                    size = Size(barW, h.coerceAtLeast(3f)),
                    cornerRadius = CornerRadius(6.dp.toPx())
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            weeks.forEach { w ->
                Text(
                    text = w.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = c.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    fontSize = 9.sp,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
