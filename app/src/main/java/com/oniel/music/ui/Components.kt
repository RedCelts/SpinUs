package com.oniel.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// --- Colours ----------------------------------------------------------------

/** Small fixed palette for the listener avatars (first letter, no name). */
private val AvatarPalette = listOf(
    Color(0xFF0E4F52),
    Color(0xFFC77F0A),
    Color(0xFF7A4A9E),
    Color(0xFF2E6E4E),
    Color(0xFF9E3D4E),
    Color(0xFF3D6E9E)
)

fun avatarColorFor(name: String): Color {
    val index = (name.hashCode() and Int.MAX_VALUE) % AvatarPalette.size
    return AvatarPalette[index]
}

// --- Cover art ---------------------------------------------------------------

@Composable
fun CoverImage(
    url: String?,
    modifier: Modifier = Modifier,
    cornerDp: Dp = 8.dp,
    contentDescription: String? = null,
    /** Tried when [url] fails to load (e.g. an artist with no picture). */
    fallbackUrl: String? = null
) {
    val candidates = remember(url, fallbackUrl) {
        listOfNotNull(url?.ifBlank { null }, fallbackUrl?.ifBlank { null }).distinct()
    }
    var attempt by remember(candidates) { mutableStateOf(0) }
    val model = candidates.getOrNull(attempt)
    if (model == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerDp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "\u266A",
                fontSize = 28.sp,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    } else {
        AsyncImage(
            model = model,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            onError = { attempt++ },
            modifier = modifier
                .clip(RoundedCornerShape(cornerDp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

// --- Album card + shelf ------------------------------------------------------

@Composable
fun AlbumCard(
    title: String,
    subtitle: String,
    imageUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cardWidth: Dp = 148.dp
) {
    Column(
        modifier = modifier
            .width(cardWidth)
            .clickable(onClick = onClick)
    ) {
        CoverImage(
            url = imageUrl,
            modifier = Modifier
                .fillMaxWidth()
                .height(cardWidth),
            cornerDp = 8.dp,
            contentDescription = title
        )
        Spacer(modifier = Modifier.height(6.dp))
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

@Composable
fun ShelfHeader(
    title: String,
    onSeeAll: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        if (onSeeAll != null) {
            IconButton(onClick = onSeeAll, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowRight,
                    contentDescription = "See all"
                )
            }
        } else {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// --- Listener avatar (icon only: first letter on a coloured circle) ----------

@Composable
fun ListenerAvatar(
    userName: String,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 40.dp
) {
    Box(
        modifier = modifier
            .size(sizeDp)
            .clip(CircleShape)
            .background(avatarColorFor(userName)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = userName.firstOrNull()?.uppercase() ?: "?",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

// --- Marquee (song name scrolls left when too long) --------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    fontWeight: FontWeight? = null
) {
    Text(
        text = text,
        style = style,
        fontWeight = fontWeight,
        maxLines = 1,
        modifier = modifier.basicMarquee()
    )
}

// --- Playback glyphs (drawn, not icons: the core icon set has no
//     pause / skip / shuffle / repeat glyphs) ---------------------------------

@Composable
fun PlayGlyph(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.18f)
            lineTo(size.width * 0.28f, size.height * 0.82f)
            lineTo(size.width * 0.84f, size.height * 0.5f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun PauseGlyph(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier = modifier) {
        val barW = size.width * 0.22f
        val top = size.height * 0.18f
        val height = size.height * 0.64f
        drawRect(color, topLeft = Offset(size.width * 0.22f, top), size = androidx.compose.ui.geometry.Size(barW, height))
        drawRect(color, topLeft = Offset(size.width * 0.56f, top), size = androidx.compose.ui.geometry.Size(barW, height))
    }
}

@Composable
fun NextGlyph(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier = modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.12f, size.height * 0.18f)
            lineTo(size.width * 0.12f, size.height * 0.82f)
            lineTo(size.width * 0.66f, size.height * 0.5f)
            close()
        }
        drawPath(path, color)
        drawRect(
            color,
            topLeft = Offset(size.width * 0.74f, size.height * 0.18f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.12f, size.height * 0.64f)
        )
    }
}

@Composable
fun PrevGlyph(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Canvas(modifier = modifier) {
        drawRect(
            color,
            topLeft = Offset(size.width * 0.14f, size.height * 0.18f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.12f, size.height * 0.64f)
        )
        val path = Path().apply {
            moveTo(size.width * 0.88f, size.height * 0.18f)
            lineTo(size.width * 0.88f, size.height * 0.82f)
            lineTo(size.width * 0.34f, size.height * 0.5f)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
fun ShuffleGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = false
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Canvas(modifier = modifier) {
        val c = if (active) accent else color
        val stroke = Stroke(width = size.minDimension * 0.09f)
        val w = size.width
        val h = size.height
        // Two crossing arrows, top-left to bottom-right and bottom-left to top-right.
        drawLine(c, Offset(w * 0.08f, h * 0.28f), Offset(w * 0.62f, h * 0.72f), strokeWidth = stroke.width)
        drawLine(c, Offset(w * 0.08f, h * 0.72f), Offset(w * 0.62f, h * 0.28f), strokeWidth = stroke.width)
        // Arrowheads on the right.
        val headTop = Path().apply {
            moveTo(w * 0.62f, h * 0.28f)
            lineTo(w * 0.88f, h * 0.28f)
            lineTo(w * 0.74f, h * 0.44f)
            close()
        }
        val headBottom = Path().apply {
            moveTo(w * 0.62f, h * 0.72f)
            lineTo(w * 0.88f, h * 0.72f)
            lineTo(w * 0.74f, h * 0.56f)
            close()
        }
        drawPath(headTop, c)
        drawPath(headBottom, c)
    }
}

@Composable
fun RepeatGlyph(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    active: Boolean = false,
    repeatOne: Boolean = false
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = if (active) accent else color
            val stroke = Stroke(width = size.minDimension * 0.10f)
            val w = size.width
            val h = size.height
            // Circular arrow: an almost-full arc with a head at the top right.
            drawArc(
                color = c,
                startAngle = 40f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(w * 0.10f, h * 0.18f),
                size = androidx.compose.ui.geometry.Size(w * 0.80f, h * 0.64f),
                style = stroke
            )
            val head = Path().apply {
                moveTo(w * 0.78f, h * 0.10f)
                lineTo(w * 0.92f, h * 0.30f)
                lineTo(w * 0.64f, h * 0.30f)
                close()
            }
            drawPath(head, c)
        }
        if (repeatOne) {
            Text(
                text = "1",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}
