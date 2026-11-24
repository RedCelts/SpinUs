package com.oniel.music.ui.theme

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** The scenery drawn behind every screen (see ThemeBackdrop). */
enum class BackdropStyle {
    PLAIN, SOFT_BLOB, SCANLINES, STARFIELD, TERMINAL_GRID, DEPTH_FLOOR, NEON_GLOW,
    FLOWER_POWER, GOTH_VIGNETTE, HALO, WOOD_GRAIN, LEAVES, WAVES, SUN
}

/**
 * Everything that makes a theme: colours, fonts (Android's built-in
 * families, so nothing to download), corner roundness, heading letter
 * spacing and the backdrop scenery.
 */
data class ThemeSpec(
    val colors: ColorScheme,
    val dark: Boolean,
    val headingFont: FontFamily,
    val bodyFont: FontFamily,
    val corner: Dp,
    val headingSpacing: TextUnit = 0.sp,
    val headingWeight: FontWeight = FontWeight.Bold,
    val backdrop: BackdropStyle = BackdropStyle.PLAIN
)

/** The themes offered in Settings → Theme, in picker order. */
enum class AppTheme(val label: String, val tagline: String) {
    CLASSIC("Classic", "Warm amber on white"),
    MODERN("Modern", "Clean, bold, minimal"),
    ARCADE("Arcade", "Dated 8-bit cabinet"),
    SPACE("Space", "Stars and deep nebula"),
    GEEK("Geek", "Green-screen terminal"),
    THREE_D("3D", "Depth, shadows, floor grid"),
    NEON("Neon", "Glowing pink and cyan"),
    HIPPY("Hippy", "Groovy flower power"),
    GOTH("Goth", "Black, crimson, gothic serif"),
    ANGELIC("Angelic", "Soft white and gold halo"),
    RUSTIC("Rustic", "Wood, leather, parchment"),
    NATURE("Nature", "Forest greens and leaves"),
    OCEAN("Ocean", "Deep blue and waves"),
    SUNSET("Sunset", "Orange-pink evening sky"),
    MIDNIGHT("Midnight", "Plain dark mode");

    val spec: ThemeSpec by lazy { specFor(this) }
}

// ---- Fonts: Android system families -------------------------------------

private fun family(name: String, style: Int = Typeface.NORMAL): FontFamily =
    FontFamily(Typeface.create(name, style))

private val SansBlack by lazy { family("sans-serif-black") }
private val SansMedium by lazy { family("sans-serif-medium") }
private val SansLight by lazy { family("sans-serif-light") }
private val SansCondensed by lazy { family("sans-serif-condensed") }
private val CondensedBold by lazy { family("sans-serif-condensed", Typeface.BOLD) }
private val Casual by lazy { family("casual") }
private val Cursive by lazy { family("cursive") }
private val SerifMono by lazy { family("serif-monospace") }

// ---- Colours ------------------------------------------------------------

private fun contrast(c: Color) = if (c.luminance() > 0.45f) Color(0xFF111111) else Color.White

/** A full Material scheme from a handful of brand colours. */
private fun scheme(
    dark: Boolean,
    primary: Color,
    secondary: Color,
    tertiary: Color,
    background: Color,
    onBackground: Color,
    card: Color,
    muted: Color,
    outline: Color
): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = contrast(primary),
        primaryContainer = lerp(background, primary, 0.28f),
        onPrimaryContainer = onBackground,
        inversePrimary = primary,
        secondary = secondary,
        onSecondary = contrast(secondary),
        secondaryContainer = lerp(background, secondary, 0.24f),
        onSecondaryContainer = onBackground,
        tertiary = tertiary,
        onTertiary = contrast(tertiary),
        tertiaryContainer = lerp(background, tertiary, 0.22f),
        onTertiaryContainer = onBackground,
        background = background,
        onBackground = onBackground,
        surface = background,
        onSurface = onBackground,
        surfaceVariant = card,
        onSurfaceVariant = muted,
        surfaceTint = primary,
        outline = outline,
        outlineVariant = outline,
        surfaceBright = lerp(background, card, 0.5f),
        surfaceDim = background,
        surfaceContainerLowest = background,
        surfaceContainerLow = lerp(background, card, 0.5f),
        surfaceContainer = card,
        surfaceContainerHigh = lerp(card, onBackground, 0.04f),
        surfaceContainerHighest = lerp(card, onBackground, 0.08f)
    )
}

private fun specFor(theme: AppTheme): ThemeSpec = when (theme) {
    AppTheme.CLASSIC -> ThemeSpec(
        colors = scheme(
            dark = false, primary = Color(0xFFF2A93B), secondary = Color(0xFF0E4F52),
            tertiary = Color(0xFFC77F0A), background = Color.White, onBackground = Color(0xFF1C1B1F),
            card = Color(0xFFF7F3EA), muted = Color(0xFF6B6862), outline = Color(0xFFE8E2D5)
        ),
        dark = false, headingFont = FontFamily.Default, bodyFont = FontFamily.Default, corner = 12.dp
    )
    AppTheme.MODERN -> ThemeSpec(
        colors = scheme(
            dark = false, primary = Color(0xFF111111), secondary = Color(0xFF3D5AFE),
            tertiary = Color(0xFF3D5AFE), background = Color(0xFFFAFAFA), onBackground = Color(0xFF0A0A0A),
            card = Color(0xFFF0F0F3), muted = Color(0xFF70707A), outline = Color(0xFFE2E2E8)
        ),
        dark = false, headingFont = SansBlack, bodyFont = FontFamily.SansSerif, corner = 18.dp,
        headingSpacing = (-0.5).sp, headingWeight = FontWeight.Black, backdrop = BackdropStyle.SOFT_BLOB
    )
    AppTheme.ARCADE -> ThemeSpec(
        colors = scheme(
            dark = true, primary = Color(0xFFFFD400), secondary = Color(0xFFFF2E63),
            tertiary = Color(0xFF00E5FF), background = Color(0xFF0B0B14), onBackground = Color(0xFFF5F5F5),
            card = Color(0xFF1A1A2E), muted = Color(0xFFA0A0C0), outline = Color(0xFF3A3A5C)
        ),
        dark = true, headingFont = FontFamily.Monospace, bodyFont = FontFamily.Monospace, corner = 0.dp,
        headingSpacing = 2.sp, headingWeight = FontWeight.Black, backdrop = BackdropStyle.SCANLINES
    )
    AppTheme.SPACE -> ThemeSpec(
        colors = scheme(
            dark = true, primary = Color(0xFF8C7CFF), secondary = Color(0xFF4FC3F7),
            tertiary = Color(0xFFB39DFF), background = Color(0xFF070A1C), onBackground = Color(0xFFE8E9FF),
            card = Color(0xFF141A36), muted = Color(0xFF9AA0C8), outline = Color(0xFF2A3160)
        ),
        dark = true, headingFont = SansLight, bodyFont = FontFamily.SansSerif, corner = 16.dp,
        headingSpacing = 3.sp, headingWeight = FontWeight.Light, backdrop = BackdropStyle.STARFIELD
    )
    AppTheme.GEEK -> ThemeSpec(
        colors = scheme(
            dark = true, primary = Color(0xFF39FF7A), secondary = Color(0xFF2BD9C4),
            tertiary = Color(0xFF39FF7A), background = Color(0xFF050B07), onBackground = Color(0xFFB8FFCC),
            card = Color(0xFF0D1A11), muted = Color(0xFF5FA874), outline = Color(0xFF1E3A26)
        ),
        dark = true, headingFont = FontFamily.Monospace, bodyFont = FontFamily.Monospace, corner = 4.dp,
        headingSpacing = 1.sp, backdrop = BackdropStyle.TERMINAL_GRID
    )
    AppTheme.THREE_D -> ThemeSpec(
        colors = scheme(
            dark = false, primary = Color(0xFF5B5BF7), secondary = Color(0xFF00B3A4),
            tertiary = Color(0xFF7A3CF0), background = Color(0xFFE9ECF5), onBackground = Color(0xFF1B1F33),
            card = Color(0xFFDDE2EF), muted = Color(0xFF5E6480), outline = Color(0xFFC6CCDE)
        ),
        dark = false, headingFont = SansBlack, bodyFont = SansMedium, corner = 24.dp,
        headingWeight = FontWeight.Black, backdrop = BackdropStyle.DEPTH_FLOOR
    )
    AppTheme.NEON -> ThemeSpec(
        colors = scheme(
            dark = true, primary = Color(0xFFFF2BD6), secondary = Color(0xFF00F0FF),
            tertiary = Color(0xFF00F0FF), background = Color(0xFF0A0012), onBackground = Color(0xFFFBEAFF),
            card = Color(0xFF1A0A26), muted = Color(0xFFC59BD6), outline = Color(0xFF4A1F63)
        ),
        dark = true, headingFont = CondensedBold, bodyFont = SansCondensed, corner = 14.dp,
        headingSpacing = 1.5.sp, backdrop = BackdropStyle.NEON_GLOW
    )
    AppTheme.HIPPY -> ThemeSpec(
        colors = scheme(
            dark = false, primary = Color(0xFFFF7A1A), secondary = Color(0xFF8E44AD),
            tertiary = Color(0xFFD6336C), background = Color(0xFFFFF4DC), onBackground = Color(0xFF3B2412),
            card = Color(0xFFFFE6B8), muted = Color(0xFF8A5A33), outline = Color(0xFFF1CF94)
        ),
        dark = false, headingFont = Casual, bodyFont = Casual, corner = 28.dp,
        backdrop = BackdropStyle.FLOWER_POWER
    )
    AppTheme.GOTH -> ThemeSpec(
        colors = scheme(
            dark = true, primary = Color(0xFFB0132B), secondary = Color(0xFF7D5BA6),
            tertiary = Color(0xFFE0354F), background = Color(0xFF0A0708), onBackground = Color(0xFFE9DFE1),
            card = Color(0xFF1C1215), muted = Color(0xFF9C8B8F), outline = Color(0xFF3A2228)
        ),
        dark = true, headingFont = FontFamily.Serif, bodyFont = FontFamily.Serif, corner = 2.dp,
        headingSpacing = 2.sp, backdrop = BackdropStyle.GOTH_VIGNETTE
    )
    AppTheme.ANGELIC -> ThemeSpec(
        colors = scheme(
            dark = false, primary = Color(0xFFD4A537), secondary = Color(0xFF8FB7E0),
            tertiary = Color(0xFFB8862B), background = Color(0xFFFFFDF7), onBackground = Color(0xFF3D3628),
            card = Color(0xFFFBF4E2), muted = Color(0xFF8C8270), outline = Color(0xFFEFE4C8)
        ),
        dark = false, headingFont = Cursive, bodyFont = SansLight, corner = 22.dp,
        headingWeight = FontWeight.Normal, backdrop = BackdropStyle.HALO
    )
    AppTheme.RUSTIC -> ThemeSpec(
        colors = scheme(
            dark = false, primary = Color(0xFF8B4A1E), secondary = Color(0xFF4E6B3A),
            tertiary = Color(0xFFA0581F), background = Color(0xFFF3E6CF), onBackground = Color(0xFF2E1E10),
            card = Color(0xFFE8D4B0), muted = Color(0xFF7A5E40), outline = Color(0xFFD9BF93)
        ),
        dark = false, headingFont = FontFamily.Serif, bodyFont = SerifMono, corner = 6.dp,
        backdrop = BackdropStyle.WOOD_GRAIN
    )
    AppTheme.NATURE -> ThemeSpec(
        colors = scheme(
            dark = false, primary = Color(0xFF2E7D32), secondary = Color(0xFF8D6E3F),
            tertiary = Color(0xFF388E3C), background = Color(0xFFF1F7EC), onBackground = Color(0xFF1B2A1C),
            card = Color(0xFFE1EED8), muted = Color(0xFF5B715C), outline = Color(0xFFCADBC0)
        ),
        dark = false, headingFont = SansMedium, bodyFont = FontFamily.SansSerif, corner = 20.dp,
        backdrop = BackdropStyle.LEAVES
    )
    AppTheme.OCEAN -> ThemeSpec(
        colors = scheme(
            dark = true, primary = Color(0xFF29B6F6), secondary = Color(0xFF26C6DA),
            tertiary = Color(0xFF4DD0E1), background = Color(0xFF041A2B), onBackground = Color(0xFFE0F4FF),
            card = Color(0xFF0B2C44), muted = Color(0xFF86AFC8), outline = Color(0xFF16405E)
        ),
        dark = true, headingFont = SansMedium, bodyFont = FontFamily.SansSerif, corner = 18.dp,
        backdrop = BackdropStyle.WAVES
    )
    AppTheme.SUNSET -> ThemeSpec(
        colors = scheme(
            dark = false, primary = Color(0xFFFF6B4A), secondary = Color(0xFF7B3FA0),
            tertiary = Color(0xFFE04F7E), background = Color(0xFFFFF1EA), onBackground = Color(0xFF2E1622),
            card = Color(0xFFFFE0D3), muted = Color(0xFF8A5A66), outline = Color(0xFFF5C9B9)
        ),
        dark = false, headingFont = SansBlack, bodyFont = FontFamily.SansSerif, corner = 16.dp,
        backdrop = BackdropStyle.SUN
    )
    AppTheme.MIDNIGHT -> ThemeSpec(
        colors = scheme(
            dark = true, primary = Color(0xFFF2A93B), secondary = Color(0xFF4DB6AC),
            tertiary = Color(0xFFF2A93B), background = Color(0xFF121212), onBackground = Color(0xFFECECEC),
            card = Color(0xFF1E1E1E), muted = Color(0xFFA3A3A3), outline = Color(0xFF333333)
        ),
        dark = true, headingFont = FontFamily.Default, bodyFont = FontFamily.Default, corner = 12.dp
    )
}

// ---- Typography / shapes ------------------------------------------------

private fun typographyFor(spec: ThemeSpec): Typography {
    val t = Typography()
    fun heading(s: TextStyle) = s.copy(
        fontFamily = spec.headingFont,
        fontWeight = spec.headingWeight,
        letterSpacing = if (spec.headingSpacing.value != 0f) spec.headingSpacing else s.letterSpacing
    )
    fun body(s: TextStyle) = s.copy(fontFamily = spec.bodyFont)
    return t.copy(
        displayLarge = heading(t.displayLarge),
        displayMedium = heading(t.displayMedium),
        displaySmall = heading(t.displaySmall),
        headlineLarge = heading(t.headlineLarge),
        headlineMedium = heading(t.headlineMedium),
        headlineSmall = heading(t.headlineSmall),
        titleLarge = heading(t.titleLarge),
        titleMedium = body(t.titleMedium),
        titleSmall = body(t.titleSmall),
        bodyLarge = body(t.bodyLarge),
        bodyMedium = body(t.bodyMedium),
        bodySmall = body(t.bodySmall),
        labelLarge = body(t.labelLarge),
        labelMedium = body(t.labelMedium),
        labelSmall = body(t.labelSmall)
    )
}

private fun shapesFor(spec: ThemeSpec): Shapes {
    val r = spec.corner
    return Shapes(
        extraSmall = RoundedCornerShape(r * 0.35f),
        small = RoundedCornerShape(r * 0.6f),
        medium = RoundedCornerShape(r),
        large = RoundedCornerShape(r * 1.3f),
        extraLarge = RoundedCornerShape(r * 1.7f)
    )
}

// ---- Chosen theme (saved, read synchronously so there's no flash) -------

object ThemeState {
    private const val PREFS = "oniel_theme"
    private const val KEY = "theme"

    var current by mutableStateOf(AppTheme.CLASSIC)
        private set

    fun load(context: Context) {
        val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        current = AppTheme.values().firstOrNull { it.name == name } ?: AppTheme.CLASSIC
    }

    fun select(context: Context, theme: AppTheme) {
        current = theme
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, theme.name).apply()
    }
}

@Composable
fun OnielMusicTheme(
    theme: AppTheme = ThemeState.current,
    content: @Composable () -> Unit
) {
    val spec = theme.spec
    // Status / navigation bar icons: light on dark themes, dark on light.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !spec.dark
                isAppearanceLightNavigationBars = !spec.dark
            }
        }
    }
    MaterialTheme(
        colorScheme = spec.colors,
        typography = typographyFor(spec),
        shapes = shapesFor(spec),
        content = content
    )
}

/** The active theme's spec, for drawing (backdrop, previews). */
val currentThemeSpec: ThemeSpec get() = ThemeState.current.spec
