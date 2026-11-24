package com.oniel.music.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oniel.music.ui.theme.AppTheme
import com.oniel.music.ui.theme.ThemeState

/**
 * Settings → Theme: every theme as a live mini preview (its own scenery,
 * colours and fonts). Tap one to switch the whole app right away.
 */
@Composable
fun ThemePicker(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val selected = ThemeState.current
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AppTheme.values().toList().chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { theme ->
                    ThemeSwatch(
                        theme = theme,
                        selected = theme == selected,
                        onClick = { ThemeState.select(context, theme) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ThemeSwatch(
    theme: AppTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spec = theme.spec
    val shape = RoundedCornerShape(spec.corner.coerceAtMost(18.dp))
    // Draw the preview in the theme's own colours.
    MaterialTheme(colorScheme = spec.colors) {
        val c = MaterialTheme.colorScheme
        Box(
            modifier = modifier
                .height(112.dp)
                .clip(shape)
                .border(
                    BorderStroke(if (selected) 3.dp else 1.dp, if (selected) c.primary else c.outline),
                    shape
                )
                .clickable(onClick = onClick)
        ) {
            ThemeBackdrop(spec = spec, modifier = Modifier.matchParentSize())
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = theme.label,
                    style = TextStyle(
                        fontFamily = spec.headingFont,
                        fontWeight = spec.headingWeight,
                        fontSize = 16.sp,
                        letterSpacing = spec.headingSpacing
                    ),
                    color = c.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = theme.tagline,
                    style = TextStyle(fontFamily = spec.bodyFont, fontSize = 10.sp, lineHeight = 12.sp),
                    color = c.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(c.primary, c.secondary, c.tertiary).forEach { dot ->
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(dot)
                    )
                }
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(c.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = c.onPrimary,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
