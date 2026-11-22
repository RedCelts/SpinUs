package com.oniel.music.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.PlayLog
import com.oniel.music.data.Track
import com.oniel.music.data.WeekStats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Listening stats: this week (minutes, top artist, top song), top artists
 * and songs for the week, past weeks and the day streak.
 *
 * This week comes from Jellyfin: a track counts once, from its
 * last-played date, and minutes are estimated from track lengths. Past
 * weeks and the streak come from the on-device play log, which only
 * records plays from the day the app was installed. The honest note at
 * the bottom of the screen says so.
 */
@Composable
fun StatsScreen(
    client: JellyfinClient,
    player: PlayerController,
    onBack: () -> Unit
) {
    var loading by remember { mutableStateOf(true) }
    var weekTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var pastWeeks by remember { mutableStateOf<List<WeekStats>>(emptyList()) }
    var streakDays by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) {
        val nowMs = System.currentTimeMillis()
        val start = weekStartMs(nowMs)
        val end = start + 7L * 24 * 60 * 60 * 1000
        try {
            val tracks = client.getTracks()
            weekTracks = tracks.filter { track ->
                val played = parseIsoToMs(track.lastPlayedDate) ?: return@filter false
                played in start until end
            }
        } catch (e: Exception) {
            weekTracks = emptyList()
        }
        val weeks = player.playLogWeeks()
        pastWeeks = weeks.filter { it.weekStartMs < start }
        streakDays = streakDaysFrom(player.playLogEntries())
        loading = false
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back)
                    )
                }
                Text(
                    text = stringResource(R.string.stats_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (loading) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        } else {
            val weekStart = weekStartMs(System.currentTimeMillis())
            val minutes = weekTracks.sumOf { ticksToMs(it.runTimeTicks) } / 60_000
            val topArtist = weekTracks
                .flatMap { it.artists.ifEmpty { listOf(it.albumArtist) } }
                .groupingBy { it }
                .eachCount()
                .maxByOrNull { it.value }?.key
            val topSong = weekTracks.maxWithOrNull(
                compareBy<Track> { it.playCount }
                    .thenBy { parseIsoToMs(it.lastPlayedDate) ?: 0L }
            )
            val topArtists = weekTracks
                .flatMap { it.artists.ifEmpty { listOf(it.albumArtist) } }
                .groupingBy { it }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(5)
            val topSongs = weekTracks
                .sortedWith(
                    compareByDescending<Track> { it.playCount }
                        .thenByDescending { parseIsoToMs(it.lastPlayedDate) ?: 0L }
                )
                .take(5)

            item {
                Text(
                    text = stringResource(
                        R.string.stats_week_label,
                        weekLabel(weekStart)
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    StatCard(
                        label = stringResource(R.string.stats_minutes),
                        value = minutes.toString(),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    StatCard(
                        label = stringResource(R.string.stats_top_artist),
                        value = topArtist ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    StatCard(
                        label = stringResource(R.string.stats_top_song),
                        value = topSong?.title ?: "—",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            if (weekTracks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.stats_no_week),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }

            if (topArtists.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.stats_top_artists),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)
                    )
                }
                itemsIndexed(topArtists) { index, entry ->
                    RankedRow(
                        rank = index + 1,
                        title = entry.key,
                        subtitle = stringResource(R.string.stats_plays, entry.value)
                    )
                }
            }

            if (topSongs.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.stats_top_songs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                    )
                }
                itemsIndexed(topSongs) { index, track ->
                    RankedRow(
                        rank = index + 1,
                        title = track.title,
                        subtitle = track.artists.joinToString(", ")
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.stats_past_weeks),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
                )
            }
            if (pastWeeks.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.stats_no_past_weeks),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            } else {
                items(pastWeeks) { week ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = week.label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = week.topSong ?: week.topArtist ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = stringResource(R.string.stats_minutes_short, week.minutes),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            if (streakDays > 0) {
                item {
                    Text(
                        text = stringResource(R.string.stats_streak, streakDays),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp)
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.stats_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RankedRow(rank: Int, title: String, subtitle: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.width(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Epoch ms of the Monday of the week containing [nowMs]. */
fun weekStartMs(nowMs: Long): Long {
    val date = Instant.ofEpochMilli(nowMs)
        .atZone(ZoneId.systemDefault())
        .toLocalDate()
    val monday = date.minusDays((date.dayOfWeek.value - 1).toLong())
    return monday.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

/** "Nov 17 – Nov 23" label for the week starting at [startMs]. */
fun weekLabel(startMs: Long): String {
    val zone = ZoneId.systemDefault()
    val fmt = DateTimeFormatter.ofPattern("MMM d")
    val start = Instant.ofEpochMilli(startMs).atZone(zone).toLocalDate()
    val end = start.plusDays(6)
    return "${start.format(fmt)} – ${end.format(fmt)}"
}

/**
 * Run of days in a row with at least one logged play, ending today (or
 * yesterday when nothing has played yet today).
 */
fun streakDaysFrom(entries: List<PlayLog.Entry>): Int {
    if (entries.isEmpty()) return 0
    val zone = ZoneId.systemDefault()
    val days = entries
        .map { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }
        .toSet()
    var day = LocalDate.now(zone)
    if (!days.contains(day)) day = day.minusDays(1)
    var streak = 0
    while (days.contains(day)) {
        streak++
        day = day.minusDays(1)
    }
    return streak
}
