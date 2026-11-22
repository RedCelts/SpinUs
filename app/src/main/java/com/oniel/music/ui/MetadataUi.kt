package com.oniel.music.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.oniel.music.R
import com.oniel.music.data.ItemDetails
import com.oniel.music.data.JellyfinClient

/** Backdrop URL for an item: its own, else its parent's (album -> artist). */
fun JellyfinClient.backdropFor(details: ItemDetails?, width: Int = 1280): String? {
    val (itemId, tag) = details?.backdrop ?: return null
    return try {
        backdropUrl(itemId, tag, width)
    } catch (e: Exception) {
        null
    }
}

/**
 * Wide header image that fades into the page. Shows [backdropUrl] when
 * there is one; underneath it, a blurred, zoomed copy of [fallbackUrl]
 * (usually the cover) so the header is never empty. [content] is drawn
 * on top, anchored to the bottom.
 */
@Composable
fun BackdropHeader(
    backdropUrl: String?,
    fallbackUrl: String?,
    height: Dp = 280.dp,
    content: @Composable BoxScope.() -> Unit = {}
) {
    val background = MaterialTheme.colorScheme.background
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        if (!fallbackUrl.isNullOrBlank()) {
            AsyncImage(
                model = fallbackUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur(28.dp)
            )
        }
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = backdropUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.25f),
                        0.55f to background.copy(alpha = 0.45f),
                        1f to background
                    )
                )
        )
        content()
    }
}

/**
 * Full-screen background for the player: the artist / album backdrop,
 * heavily dimmed into the theme background so text stays readable in
 * light and dark mode.
 */
@Composable
fun ScreenBackdrop(backdropUrl: String?, fallbackUrl: String?) {
    val background = MaterialTheme.colorScheme.background
    Box(modifier = Modifier.fillMaxSize()) {
        val url = backdropUrl ?: fallbackUrl
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (backdropUrl == null) Modifier.blur(40.dp) else Modifier)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to background.copy(alpha = 0.55f),
                        0.45f to background.copy(alpha = 0.78f),
                        1f to background.copy(alpha = 0.96f)
                    )
                )
        )
    }
}

/** Genres as a scrolling row of small chips. */
@Composable
fun GenreChips(genres: List<String>, modifier: Modifier = Modifier) {
    if (genres.isEmpty()) return
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 24.dp)
    ) {
        items(genres) { genre ->
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text(
                    text = genre,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
    }
}

/** Long text (bio, album notes) clipped to a few lines; tap to expand. */
@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 4
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .animateContentSize()
            .clickable(enabled = overflows || expanded) { expanded = !expanded }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow }
        )
        if (overflows || expanded) {
            Text(
                text = stringResource(if (expanded) R.string.show_less else R.string.show_more),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/** Small "Label · Genre" style detail line; hidden when empty. */
@Composable
fun DetailLine(parts: List<String?>, modifier: Modifier = Modifier) {
    val text = parts.filterNot { it.isNullOrBlank() }.joinToString(" · ")
    if (text.isEmpty()) return
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/**
 * "About the artist" card for the player: backdrop strip, round photo,
 * name, genres and the start of the bio. Tap opens the artist page.
 */
@Composable
fun ArtistInfoCard(
    client: JellyfinClient,
    artist: ItemDetails,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val photoUrl = artist.primaryImageTag?.let {
        try { client.imageUrl(artist.id, it, 300) } catch (e: Exception) { null }
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
    ) {
        Column {
            val backdrop = client.backdropFor(artist, 900)
            if (backdrop != null) {
                AsyncImage(
                    model = backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                )
            }
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.about_artist),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (photoUrl != null) {
                        AsyncImage(
                            model = photoUrl,
                            contentDescription = artist.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                        )
                    } else {
                        ListenerAvatar(userName = artist.name, sizeDp = 48.dp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = artist.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (artist.genres.isNotEmpty()) {
                            Text(
                                text = artist.genres.take(3).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                val bio = artist.overview
                if (bio != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
