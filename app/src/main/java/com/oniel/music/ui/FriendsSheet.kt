package com.oniel.music.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oniel.music.R
import com.oniel.music.data.FriendSession
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Playlist
import com.oniel.music.data.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The six reactions that can be sent to a listener. */
val FriendEmojis = listOf("\u2764\uFE0F", "\uD83D\uDC4D", "\uD83D\uDE0A", "\uD83D\uDD25", "\uD83C\uDFB5", "\uD83D\uDC4F")

/**
 * Bottom sheet of other users on the server listening to music right now.
 * Refreshed every 10 seconds. Tapping a row opens the emoji picker, which
 * sends one reaction to that user's session(s).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsSheet(
    client: JellyfinClient,
    myUserId: String,
    onDismiss: () -> Unit
) {
    var sessions by remember { mutableStateOf<List<FriendSession>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var emojiTarget by remember { mutableStateOf<FriendSession?>(null) }
    var sentNote by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        while (true) {
            try {
                sessions = client.getSessions().filter { it.userId != myUserId }
            } catch (e: Exception) {
                // Presence is best-effort; keep the last list.
            }
            loading = false
            delay(10_000)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.friends_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.friends_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else if (sessions.isEmpty()) {
                Text(
                    text = stringResource(R.string.friends_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(sessions) { session ->
                        FriendRow(
                            session = session,
                            imageUrl = session.albumId?.let {
                                client.imageUrl(it, session.imageTag, 200)
                            },
                            onClick = { emojiTarget = session }
                        )
                    }
                }
            }

            if (sentNote != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sentNote!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
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
                        sentNote = "Sent $emoji"
                    } catch (e: Exception) {
                        sentNote = "Could not send that reaction."
                    }
                }
                emojiTarget = null
            },
            onDismiss = { emojiTarget = null }
        )
    }
}

@Composable
private fun FriendRow(
    session: FriendSession,
    imageUrl: String?,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = imageUrl,
            modifier = Modifier.size(56.dp),
            cornerDp = 8.dp,
            contentDescription = session.trackTitle
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MarqueeText(
                text = session.trackTitle,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = session.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(6.dp))
            val total = ticksToMs(session.runTimeTicks).coerceAtLeast(1L)
            val progress = (ticksToMs(session.positionTicks).toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = progress,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

/** Six-emoji picker used by both the Friends sheet and the player. */
@Composable
fun EmojiPickerDialog(
    userName: String,
    onEmojiPicked: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.emoji_title, userName)) },
        text = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                FriendEmojis.forEach { emoji ->
                    Text(
                        text = emoji,
                        fontSize = 30.sp,
                        modifier = Modifier.clickable { onEmojiPicked(emoji) }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Pick a playlist to add the current track to.
 *
 * Honest limit in v1: the data layer has no call to add a track to a
 * playlist (or to fetch a playlist's tracks), so this dialog lists the
 * playlists and says adding is not available yet, instead of pretending
 * a tap worked. Noted in the build report for the data layer to add.
 */
@Composable
fun AddToPlaylistDialog(
    client: JellyfinClient,
    track: Track,
    onDismiss: () -> Unit
) {
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }

    LaunchedEffect(Unit) {
        try {
            playlists = client.getPlaylists()
        } catch (e: Exception) {
            playlists = emptyList()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                when {
                    playlists == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    playlists!!.isEmpty() -> Text(stringResource(R.string.add_to_playlist_empty))
                    else -> {
                        playlists!!.forEach { playlist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = playlist.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = stringResource(
                                        R.string.playlist_count,
                                        playlist.itemCount
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.add_to_playlist_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) { Text(stringResource(R.string.done)) }
        }
    )
}
