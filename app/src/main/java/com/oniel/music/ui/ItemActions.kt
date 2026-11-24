package com.oniel.music.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.JellyfinClient
import com.oniel.music.data.Playlist
import com.oniel.music.data.Track
import kotlinx.coroutines.launch

/**
 * The signed-in client, available anywhere under the app shell, so rows
 * (TrackRow and friends) can offer their ⋮ menu without every screen
 * passing the client down.
 */
val LocalJellyfin = staticCompositionLocalOf<JellyfinClient?> { null }

/**
 * Height of the frosted bottom chrome (mini player + tab bar + system nav
 * bar). Screens scroll under it, so lists pad their end by this much.
 */
val LocalBottomChrome = staticCompositionLocalOf { 0.dp }

/** Bottom content padding for a scrolling screen: clears the bottom chrome. */
@Composable
fun bottomChromePadding(): androidx.compose.ui.unit.Dp = LocalBottomChrome.current + 16.dp

/** What a ⋮ menu acts on. */
sealed class ActionTarget {
    abstract val title: String

    data class Song(val track: Track) : ActionTarget() {
        override val title: String get() = track.title
    }

    data class AlbumItem(val albumId: String, val name: String) : ActionTarget() {
        override val title: String get() = name
    }

    /** [artistId] may be null (library/search only know names); resolved on demand. */
    data class ArtistItem(val artistId: String?, val name: String) : ActionTarget() {
        override val title: String get() = name
    }
}

/** The item id favorites are stored against (artist looked up by name if needed). */
private suspend fun ActionTarget.favoriteId(client: JellyfinClient): String? = when (this) {
    is ActionTarget.Song -> track.id
    is ActionTarget.AlbumItem -> albumId
    is ActionTarget.ArtistItem -> artistId ?: client.getArtistByName(name)?.id
}

/** Every song the target stands for, as playlist item ids. */
private suspend fun ActionTarget.trackIds(client: JellyfinClient): List<String> = when (this) {
    is ActionTarget.Song -> listOf(track.id)
    is ActionTarget.AlbumItem -> client.getAlbumTracks(albumId).map { it.id }
    is ActionTarget.ArtistItem -> {
        val id = artistId ?: client.getArtistByName(name)?.id
        if (id == null) emptyList() else client.getArtistTracks(id).map { it.id }
    }
}

/**
 * ⋮ button for a song, album or artist row: Add to / Remove from
 * favorites, Add to playlist, New playlist.
 */
@Composable
fun ItemMoreButton(target: ActionTarget, modifier: Modifier = Modifier) {
    val client = LocalJellyfin.current ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var favorite by remember(target) { mutableStateOf<Boolean?>(null) }
    var playlistDialog by remember { mutableStateOf(false) }
    var startWithNew by remember { mutableStateOf(false) }

    // Look up the favorite state when the menu opens (fast for songs
    // from library lists: their flag is already known).
    LaunchedEffect(open, target) {
        if (!open) return@LaunchedEffect
        favorite = try {
            val id = target.favoriteId(client)
            if (id == null) {
                false
            } else {
                client.isFavorite(
                    id,
                    known = (target as? ActionTarget.Song)?.track?.takeIf { it.isFavorite }?.isFavorite
                )
            }
        } catch (e: Exception) {
            false
        }
    }

    Box(modifier = modifier) {
        IconButton(onClick = { open = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.more_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val isFav = favorite == true
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (isFav) R.string.action_unfavorite else R.string.action_favorite
                        )
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary
                    )
                },
                enabled = favorite != null,
                onClick = {
                    open = false
                    val want = !isFav
                    scope.launch {
                        try {
                            val id = target.favoriteId(client)
                                ?: throw IllegalStateException("not found")
                            client.setFavorite(id, want)
                            favorite = want
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (want) R.string.favorite_added else R.string.favorite_removed,
                                    target.title
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_to_playlist)) },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { open = false; startWithNew = false; playlistDialog = true }
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_new_playlist)) },
                leadingIcon = { Icon(Icons.Filled.Create, contentDescription = null) },
                onClick = { open = false; startWithNew = true; playlistDialog = true }
            )
        }
    }

    if (playlistDialog) {
        AddToPlaylistDialog(
            client = client,
            title = target.title,
            resolveTrackIds = { target.trackIds(client) },
            startWithNew = startWithNew,
            onDismiss = { playlistDialog = false }
        )
    }
}

/**
 * Add songs to a playlist: tap an existing playlist, or make a new one
 * (name it, Create). [resolveTrackIds] is only called on confirm, so
 * opening the dialog for a whole artist costs nothing.
 */
@Composable
fun AddToPlaylistDialog(
    client: JellyfinClient,
    title: String,
    resolveTrackIds: suspend () -> List<String>,
    onDismiss: () -> Unit,
    startWithNew: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var playlists by remember { mutableStateOf<List<Playlist>?>(null) }
    var creating by remember { mutableStateOf(startWithNew) }
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        playlists = try { client.getPlaylists() } catch (e: Exception) { emptyList() }
    }

    fun submit(action: suspend (List<String>) -> String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val ids = resolveTrackIds()
                if (ids.isEmpty()) throw IllegalStateException("no songs")
                val playlistName = action(ids)
                Toast.makeText(
                    context,
                    context.getString(R.string.playlist_added_to, playlistName),
                    Toast.LENGTH_SHORT
                ).show()
                onDismiss()
            } catch (e: Exception) {
                Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show()
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                stringResource(
                    if (creating) R.string.action_new_playlist else R.string.add_to_playlist_title
                )
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                } else if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text(stringResource(R.string.playlist_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { creating = true }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.action_new_playlist),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                    val list = playlists
                    when {
                        list == null -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        list.isEmpty() -> Text(
                            stringResource(R.string.add_to_playlist_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(list) { playlist ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            submit { ids ->
                                                client.addToPlaylist(playlist.id, ids)
                                                playlist.name
                                            }
                                        }
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.List,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = playlist.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
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
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    enabled = newName.isNotBlank() && !busy,
                    onClick = {
                        val name = newName.trim()
                        submit { ids ->
                            client.createPlaylist(name, ids)
                            name
                        }
                    }
                ) { Text(stringResource(R.string.playlist_create)) }
            }
        },
        dismissButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (creating && !startWithNew) {
                        creating = false
                    } else {
                        onDismiss()
                    }
                }
            ) { Text(stringResource(android.R.string.cancel)) }
        }
    )
}
