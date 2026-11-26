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
import com.oniel.music.data.SpinUsClient
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
    client: SpinUsClient,
    myUserId: String,
    onDismiss: () -> Unit,
    onOpenAlbum: (String) -> Unit = {},
    onOpenArtist: (String?, String) -> Unit = { _, _ -> },
    onPlayTrack: (com.oniel.music.data.Track) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var sessions by remember { mutableStateOf<List<FriendSession>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var emojiTarget by remember { mutableStateOf<FriendSession?>(null) }
    var statsTarget by remember { mutableStateOf<FriendSession?>(null) }
    var sentNote by remember { mutableStateOf<String?>(null) }
    var recs by remember { mutableStateOf<List<com.oniel.music.data.Recommendation>>(emptyList()) }
    var requests by remember { mutableStateOf(com.oniel.music.data.FriendRequests(emptyList(), emptyList())) }
    var friends by remember { mutableStateOf<List<com.oniel.music.data.Friend>>(emptyList()) }
    var showAdd by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }
    var removeTarget by remember { mutableStateOf<com.oniel.music.data.Friend?>(null) }
    var reload by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    // Friends, requests and recommendations; reloaded when something arrives.
    LaunchedEffect(reload, Inbox.version) {
        try {
            requests = client.getFriendRequests()
            friends = client.getFriends()
            recs = client.getRecommendations()
            // Opening Friends counts as seeing the recommendations.
            if (recs.any { !it.read }) client.markRecommendationsRead()
            Inbox.unread = requests.incoming.size
        } catch (e: Exception) {
            // Keep what's shown.
        }
    }
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

    fun openRec(rec: com.oniel.music.data.Recommendation) {
        when (rec.itemType) {
            "album" -> onOpenAlbum(rec.itemId)
            "artist" -> onOpenArtist(rec.itemId, rec.title)
            else -> scope.launch {
                try {
                    client.getTracksByIds(listOf(rec.itemId)).firstOrNull()?.let(onPlayTrack)
                } catch (e: Exception) {
                    android.widget.Toast.makeText(context, R.string.action_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun friendAction(block: suspend () -> Unit) {
        scope.launch {
            try { block() } catch (e: Exception) {
                android.widget.Toast.makeText(context, R.string.action_failed, android.widget.Toast.LENGTH_SHORT).show()
            }
            reload++
        }
    }

    val scanner = rememberFriendScanner(client) { reload++ }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Text(
                    text = stringResource(R.string.friends_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.FilledTonalButton(onClick = { showAdd = true }) {
                        Text(stringResource(R.string.friend_add))
                    }
                    androidx.compose.material3.OutlinedButton(onClick = { showQr = true }) {
                        Text(stringResource(R.string.friend_my_qr))
                    }
                    androidx.compose.material3.OutlinedButton(onClick = scanner) {
                        Text(stringResource(R.string.friend_scan))
                    }
                }
            }

            if (requests.incoming.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.friend_requests)) }
                items(requests.incoming, key = { "in-" + it.id }) { f ->
                    PersonRow(f.name) {
                        TextButton(onClick = { friendAction { client.removeFriend(f.id) } }) {
                            Text(stringResource(R.string.friend_decline))
                        }
                        androidx.compose.material3.Button(onClick = { friendAction { client.acceptFriend(f.id) } }) {
                            Text(stringResource(R.string.friend_accept))
                        }
                    }
                }
            }

            if (recs.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.rec_inbox_title)) }
                items(recs, key = { "rec-" + it.id }) { rec ->
                    RecommendationRow(
                        rec = rec,
                        imageUrl = try { client.imageUrl(rec.imageId, rec.imageTag, 200) } catch (e: Exception) { null },
                        onOpen = { openRec(rec) },
                        onDelete = {
                            recs = recs - rec
                            scope.launch { try { client.deleteRecommendation(rec.id) } catch (e: Exception) { } }
                        }
                    )
                }
            }

            item {
                SectionTitle(stringResource(R.string.friends_listening))
                Text(
                    text = stringResource(R.string.friends_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                loading -> item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }
                }
                sessions.isEmpty() -> item {
                    Text(
                        text = stringResource(R.string.friends_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                else -> items(sessions, key = { "s-" + it.userId }) { session ->
                    FriendRow(
                        session = session,
                        imageUrl = session.albumId?.let { client.imageUrl(it, session.imageTag, 200) },
                        onClick = { emojiTarget = session },
                        onStats = if (session.onielSessionIds.isNotEmpty()) {
                            { statsTarget = session }
                        } else null
                    )
                }
            }

            item { SectionTitle(stringResource(R.string.friends_yours, friends.size)) }
            if (friends.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.friends_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(friends, key = { "f-" + it.id }) { f ->
                PersonRow(f.name) {
                    TextButton(onClick = { removeTarget = f }) { Text(stringResource(R.string.friend_remove)) }
                }
            }
            if (requests.outgoing.isNotEmpty()) {
                item { SectionTitle(stringResource(R.string.friend_requested)) }
                items(requests.outgoing, key = { "out-" + it.id }) { f ->
                    PersonRow(f.name) {
                        TextButton(onClick = { friendAction { client.removeFriend(f.id) } }) {
                            Text(stringResource(R.string.friend_cancel_request))
                        }
                    }
                }
            }

            item {
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
    }

    if (showAdd) AddFriendDialog(client = client, onDismiss = { showAdd = false; reload++ })
    if (showQr) MyQrDialog(client = client, onDismiss = { showQr = false })
    removeTarget?.let { f ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text(stringResource(R.string.friend_remove_title, f.name)) },
            text = { Text(stringResource(R.string.friend_remove_note)) },
            confirmButton = {
                TextButton(onClick = { removeTarget = null; friendAction { client.removeFriend(f.id) } }) {
                    Text(stringResource(R.string.friend_remove))
                }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }

    statsTarget?.let { friend ->
        FriendStatsDialog(
            friend = friend,
            client = client,
            onDismiss = { statsTarget = null }
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

@Composable
private fun PersonRow(name: String, actions: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ListenerAvatar(userName = name, sizeDp = 40.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun FriendRow(
    session: FriendSession,
    imageUrl: String?,
    onClick: () -> Unit,
    onStats: (() -> Unit)? = null
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
        if (onStats != null) {
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = onStats) {
                Text(stringResource(R.string.friend_stats_button))
            }
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

