package com.oniel.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Oniel Music palette: warm amber accent (matches the launcher icon's amber
// note) on a plain white background with dark text. Light theme only, by
// design — no dark variant in v1.
private val AmberWarm = Color(0xFFF2A93B)
private val AmberDeep = Color(0xFFC77F0A)
private val TealDeep = Color(0xFF0E4F52)
private val Ink = Color(0xFF1C1B1F)
private val Muted = Color(0xFF6B6862)
private val CardWarm = Color(0xFFF7F3EA)
private val Divider = Color(0xFFE8E2D5)

private val LightColors = lightColorScheme(
    primary = AmberWarm,
    onPrimary = Color(0xFF2A1E00),
    primaryContainer = Color(0xFFFFE9BE),
    onPrimaryContainer = Color(0xFF2A1E00),
    secondary = TealDeep,
    onSecondary = Color.White,
    tertiary = AmberDeep,
    background = Color.White,
    surface = Color.White,
    surfaceVariant = CardWarm,
    onSurfaceVariant = Muted,
    onBackground = Ink,
    onSurface = Ink,
    outline = Divider
)

@Composable
fun OnielMusicTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content
    )
}
