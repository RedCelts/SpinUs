package com.oniel.music.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.data.Friend
import com.oniel.music.data.Recommendation
import com.oniel.music.data.SpinUsClient
import kotlinx.coroutines.launch

/** Server's reason from an error message like "x failed: HTTP 403 (the current password is wrong)". */
private fun reason(e: Exception): String? = Regex("""\(([^)]*)\)\s*$""").find(e.message.orEmpty())?.groupValues?.get(1)

/**
 * Set a new password. With [knownCurrent] (first sign-in with a temporary
 * password) only the new one is asked; [forced] hides Cancel.
 */
@Composable
fun ChangePasswordDialog(
    client: SpinUsClient,
    knownCurrent: String? = null,
    forced: Boolean = false,
    onDone: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!forced && !busy) onDismiss() },
        title = { Text(stringResource(if (forced) R.string.pw_set_title else R.string.pw_change_title)) },
        text = {
            Column {
                if (forced) {
                    Text(
                        stringResource(R.string.pw_forced_note),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (knownCurrent == null) {
                    OutlinedTextField(
                        value = current, onValueChange = { current = it; error = null },
                        label = { Text(stringResource(R.string.pw_current)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = new, onValueChange = { new = it; error = null },
                    label = { Text(stringResource(R.string.pw_new)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = again, onValueChange = { again = it; error = null },
                    label = { Text(stringResource(R.string.pw_again)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && new.isNotEmpty(),
                onClick = {
                    when {
                        new.length < 6 -> error = context.getString(R.string.pw_too_short)
                        new != again -> error = context.getString(R.string.pw_mismatch)
                        else -> {
                            busy = true
                            scope.launch {
                                try {
                                    client.changePassword(knownCurrent ?: current, new)
                                    Toast.makeText(context, R.string.pw_changed, Toast.LENGTH_SHORT).show()
                                    onDone()
                                } catch (e: Exception) {
                                    error = reason(e) ?: context.getString(R.string.action_failed)
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    }
                }
            ) { Text(stringResource(R.string.pw_save)) }
        },
        dismissButton = {
            if (!forced) TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/** The server's (type, id) for a ⋮ menu target. */
private suspend fun ActionTarget.recommendRef(client: SpinUsClient): Pair<String, String>? = when (this) {
    is ActionTarget.Song -> "track" to track.id
    is ActionTarget.AlbumItem -> "album" to albumId
    is ActionTarget.ArtistItem -> (artistId ?: client.getArtistByName(name)?.id)?.let { "artist" to it }
}

/** Pick friends (and add a note) to recommend a song, album or artist to. */
@Composable
fun RecommendDialog(client: SpinUsClient, target: ActionTarget, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var friends by remember { mutableStateOf<List<Friend>?>(null) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        friends = try { client.getFriends() } catch (e: Exception) { emptyList() }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.rec_friend_title)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    target.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                val list = friends
                when {
                    list == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    list.isEmpty() -> Text(
                        stringResource(R.string.rec_friend_none), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> {
                        LazyColumn(Modifier.heightIn(max = 260.dp)) {
                            items(list, key = { it.id }) { f ->
                                val on = f.id in picked
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { picked = if (on) picked - f.id else picked + f.id },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = on, onCheckedChange = { picked = if (on) picked - f.id else picked + f.id })
                                    ListenerAvatar(userName = f.name, sizeDp = 32.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Text(f.name, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = note, onValueChange = { if (it.length <= 300) note = it },
                            label = { Text(stringResource(R.string.rec_friend_note)) },
                            modifier = Modifier.fillMaxWidth(), maxLines = 3
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked.isNotEmpty() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val (type, id) = target.recommendRef(client) ?: throw IllegalStateException("not found")
                            client.recommend(picked.toList(), type, id, note.trim())
                            Toast.makeText(context, R.string.rec_friend_sent, Toast.LENGTH_SHORT).show()
                            onDismiss()
                        } catch (e: Exception) {
                            Toast.makeText(context, R.string.action_failed, Toast.LENGTH_SHORT).show()
                            busy = false
                        }
                    }
                }
            ) { Text(stringResource(R.string.rec_friend_send)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } }
    )
}

/** One recommendation in the Friends sheet. */
@Composable
fun RecommendationRow(
    rec: Recommendation,
    imageUrl: String?,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverImage(
            url = imageUrl,
            modifier = Modifier.size(56.dp),
            cornerDp = if (rec.itemType == "artist") 28.dp else 8.dp,
            contentDescription = rec.title
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    when (rec.itemType) {
                        "album" -> R.string.rec_from_album
                        "artist" -> R.string.rec_from_artist
                        else -> R.string.rec_from_song
                    },
                    rec.from
                ),
                style = MaterialTheme.typography.labelMedium,
                color = if (rec.read) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.tertiary,
                fontWeight = if (rec.read) FontWeight.Normal else FontWeight.Bold
            )
            Text(rec.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            rec.subtitle?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            rec.message?.let {
                Text(
                    "“$it”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.rec_dismiss),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Find people by username and send friend requests. */
@Composable
fun AddFriendDialog(client: SpinUsClient, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<com.oniel.music.data.PersonHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (query.trim().length < 2) { hits = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(300) // wait for typing to pause
        searching = true
        hits = try { client.searchPeople(query.trim()) } catch (e: Exception) { emptyList() }
        searching = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friend_add)) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text(stringResource(R.string.friend_search_hint)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                if (searching) CircularProgressIndicator(Modifier.size(24.dp))
                else if (query.trim().length >= 2 && hits.isEmpty()) {
                    Text(stringResource(R.string.friend_search_none), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(hits, key = { it.id }) { p ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ListenerAvatar(userName = p.name, sizeDp = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            when (p.relation) {
                                "friends" -> Text(stringResource(R.string.friend_status_friends),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                "requested" -> Text(stringResource(R.string.friend_status_requested),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> TextButton(onClick = {
                                    scope.launch {
                                        val rel = try { client.requestFriend(p.id) } catch (e: Exception) { p.relation }
                                        hits = hits.map { if (it.id == p.id) it.copy(relation = rel) else it }
                                    }
                                }) {
                                    Text(stringResource(if (p.relation == "incoming") R.string.friend_accept else R.string.friend_request))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } }
    )
}

/** Your friend QR code. Anyone who scans it becomes your friend, so "New code" retires the old one. */
@Composable
fun MyQrDialog(client: SpinUsClient, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf<Pair<String, String>?>(null) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        code = try { client.myFriendCode() } catch (e: Exception) { error = true; null }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.friend_my_qr)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                val c = code
                when {
                    c != null -> {
                        val bitmap = remember(c.second) { qrBitmap(c.second, 640) }
                        androidx.compose.foundation.Image(
                            bitmap = bitmap.asImageBitmap(), contentDescription = c.first,
                            modifier = Modifier.size(240.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(c.first, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp))
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(R.string.friend_qr_note), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    error -> Text(stringResource(R.string.action_failed))
                    else -> CircularProgressIndicator(Modifier.size(32.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) } },
        dismissButton = {
            TextButton(onClick = {
                scope.launch { code = try { client.myFriendCode(reset = true) } catch (e: Exception) { code } }
            }) { Text(stringResource(R.string.friend_qr_reset)) }
        }
    )
}

/** Black-on-white QR code for [text]. */
fun qrBitmap(text: String, size: Int): android.graphics.Bitmap {
    val hints = mapOf(com.google.zxing.EncodeHintType.MARGIN to 1)
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) {
        pixels[y * size + x] = if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return android.graphics.Bitmap.createBitmap(pixels, size, size, android.graphics.Bitmap.Config.ARGB_8888)
}

/**
 * Returns a function that opens Google's QR scanner (no camera permission
 * needed) and connects with whoever's code is scanned.
 */
@Composable
fun rememberFriendScanner(client: SpinUsClient, onAdded: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return {
        val options = com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE)
            .build()
        com.google.mlkit.vision.codescanner.GmsBarcodeScanning.getClient(context, options)
            .startScan()
            .addOnSuccessListener { barcode ->
                val raw = barcode.rawValue.orEmpty()
                if (!raw.startsWith("spinus://friend/")) {
                    Toast.makeText(context, R.string.friend_code_bad, Toast.LENGTH_LONG).show()
                    return@addOnSuccessListener
                }
                scope.launch {
                    val msg = try {
                        context.getString(R.string.friend_added, client.useFriendCode(raw))
                    } catch (e: Exception) {
                        context.getString(R.string.friend_code_bad)
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    onAdded()
                }
            }
            .addOnFailureListener {
                Toast.makeText(context, R.string.friend_scan_unavailable, Toast.LENGTH_LONG).show()
            }
    }
}
