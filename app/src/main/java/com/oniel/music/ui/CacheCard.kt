package com.oniel.music.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.oniel.music.R
import com.oniel.music.net.MediaCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings card: song + artwork cache size, pre-loading, and clearing. */
@Composable
fun CacheCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var songBytes by remember { mutableLongStateOf(0L) }
    var artBytes by remember { mutableLongStateOf(0L) }
    var maxMb by remember { mutableIntStateOf(MediaCache.maxMb(ctx)) }
    var preload by remember { mutableStateOf(MediaCache.preloadEnabled(ctx)) }
    var clearing by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        withContext(Dispatchers.IO) {
            songBytes = MediaCache.songBytes(ctx)
            artBytes = MediaCache.artworkBytes(ctx)
        }
    }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.cache_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.cache_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.cache_used, formatBytes(songBytes), formatMb(maxMb), formatBytes(artBytes)),
            style = MaterialTheme.typography.bodyLarge
        )
        Spacer(Modifier.height(8.dp))
        Text(text = stringResource(R.string.cache_size_label), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MediaCache.SIZE_CHOICES_MB.forEach { mb ->
                FilterChip(
                    selected = maxMb == mb,
                    onClick = {
                        maxMb = mb
                        MediaCache.setMaxMb(ctx, mb)
                    },
                    label = { Text(formatMb(mb)) }
                )
            }
        }
        if (MediaCache.openedWithMb != 0 && maxMb != MediaCache.openedWithMb) {
            Text(
                text = stringResource(R.string.cache_size_restart),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = stringResource(R.string.cache_preload), style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = stringResource(R.string.cache_preload_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = preload,
                onCheckedChange = {
                    preload = it
                    MediaCache.setPreloadEnabled(ctx, it)
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                clearing = true
                scope.launch {
                    withContext(Dispatchers.IO) { MediaCache.clear(ctx) }
                    clearing = false
                    refresh++
                }
            },
            enabled = !clearing,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(if (clearing) R.string.cache_clearing else R.string.cache_clear))
        }
    }
}

private fun formatMb(mb: Int): String = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "${bytes / (1024 * 1024)} MB"
    else -> "0 MB"
}
