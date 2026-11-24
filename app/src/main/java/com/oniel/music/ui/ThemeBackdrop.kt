package com.oniel.music.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.oniel.music.ui.theme.BackdropStyle
import com.oniel.music.ui.theme.ThemeSpec
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The theme's scenery behind every screen: stars for Space, scanlines for
 * Arcade, a halo for Angelic, wood grain for Rustic… Kept faint so text
 * stays readable; drawn once per size (no animation, no battery cost).
 */
@Composable
fun ThemeBackdrop(spec: ThemeSpec, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val bg = colors.background
    val primary = colors.primary
    val secondary = colors.secondary
    val tertiary = colors.tertiary
    // Fixed seed: the same stars / leaves every time.
    val seeds = remember(spec.backdrop) {
        val r = Random(42)
        List(160) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    Canvas(modifier = modifier.fillMaxSize().background(bg)) {
        when (spec.backdrop) {
            BackdropStyle.PLAIN -> Unit
            BackdropStyle.SOFT_BLOB -> {
                drawCircle(
                    Brush.radialGradient(
                        listOf(secondary.copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.9f, size.height * 0.08f),
                        radius = size.width * 0.8f
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width * 0.9f, size.height * 0.08f)
                )
            }
            BackdropStyle.SCANLINES -> {
                floorGrid(secondary.copy(alpha = 0.22f), horizon = 0.72f)
                var y = 0f
                while (y < size.height) {
                    drawRect(Color.Black.copy(alpha = 0.18f), Offset(0f, y), Size(size.width, 2f))
                    y += 6f
                }
            }
            BackdropStyle.STARFIELD -> {
                drawRect(
                    Brush.verticalGradient(
                        listOf(bg, lerpColor(bg, primary, 0.18f), lerpColor(bg, secondary, 0.10f))
                    )
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.22f), Color.Transparent),
                        center = Offset(size.width * 0.2f, size.height * 0.35f),
                        radius = size.width * 0.7f
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.2f, size.height * 0.35f)
                )
                seeds.forEach { (x, y, s) ->
                    drawCircle(
                        Color.White.copy(alpha = 0.25f + s * 0.6f),
                        radius = (0.6f + s * 1.8f) * density,
                        center = Offset(x * size.width, y * size.height)
                    )
                }
                // A planet peeking in at the bottom right.
                val c = Offset(size.width * 1.05f, size.height * 0.95f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(secondary.copy(alpha = 0.45f), primary.copy(alpha = 0.15f), Color.Transparent),
                        center = c, radius = size.width * 0.45f
                    ),
                    radius = size.width * 0.45f, center = c
                )
            }
            BackdropStyle.TERMINAL_GRID -> {
                val step = 24f * density
                var x = 0f
                while (x < size.width) {
                    drawLine(primary.copy(alpha = 0.06f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(primary.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
            }
            BackdropStyle.DEPTH_FLOOR -> {
                drawRect(Brush.verticalGradient(listOf(lerpColor(bg, Color.White, 0.6f), bg, lerpColor(bg, primary, 0.12f))))
                floorGrid(primary.copy(alpha = 0.16f), horizon = 0.62f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(tertiary.copy(alpha = 0.14f), Color.Transparent),
                        center = Offset(size.width * 0.5f, size.height * 0.62f),
                        radius = size.width * 0.7f
                    ),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.5f, size.height * 0.62f)
                )
            }
            BackdropStyle.NEON_GLOW -> {
                glow(primary, Offset(size.width * 0.05f, size.height * 0.15f), size.width * 0.9f, 0.28f)
                glow(secondary, Offset(size.width * 0.95f, size.height * 0.85f), size.width * 0.9f, 0.22f)
                floorGrid(primary.copy(alpha = 0.12f), horizon = 0.78f)
            }
            BackdropStyle.FLOWER_POWER -> {
                val petals = listOf(primary, secondary, tertiary, Color(0xFF2EAD6B))
                seeds.take(7).forEachIndexed { i, (x, y, s) ->
                    flower(
                        center = Offset(x * size.width, y * size.height),
                        radius = (40f + s * 70f) * density,
                        color = petals[i % petals.size].copy(alpha = 0.10f)
                    )
                }
                // Groovy concentric rings from the corner.
                for (i in 1..6) {
                    drawCircle(
                        petals[i % petals.size].copy(alpha = 0.07f),
                        radius = size.width * 0.18f * i,
                        center = Offset(0f, size.height),
                        style = Stroke(width = 18f * density)
                    )
                }
            }
            BackdropStyle.GOTH_VIGNETTE -> {
                drawRect(
                    Brush.radialGradient(
                        listOf(lerpColor(bg, primary, 0.22f), bg, Color.Black),
                        center = Offset(size.width / 2f, size.height * 0.3f),
                        radius = size.maxDimension * 0.9f
                    )
                )
                // Thin gothic arches at the top.
                val arch = Path().apply {
                    moveTo(size.width * 0.1f, size.height * 0.28f)
                    quadraticBezierTo(size.width * 0.5f, -size.height * 0.08f, size.width * 0.9f, size.height * 0.28f)
                }
                drawPath(arch, primary.copy(alpha = 0.12f), style = Stroke(width = 1.5f * density))
            }
            BackdropStyle.HALO -> {
                val top = Offset(size.width / 2f, 0f)
                for (i in 0 until 14) {
                    val a = PI.toFloat() * (0.08f + 0.84f * i / 13f)
                    drawLine(
                        primary.copy(alpha = 0.05f),
                        top,
                        Offset(top.x + cos(a) * size.maxDimension, sin(a) * size.maxDimension),
                        strokeWidth = 18f * density
                    )
                }
                drawCircle(
                    Brush.radialGradient(
                        listOf(primary.copy(alpha = 0.20f), Color.White.copy(alpha = 0.0f)),
                        center = top, radius = size.width * 0.9f
                    ),
                    radius = size.width * 0.9f, center = top
                )
                drawCircle(
                    primary.copy(alpha = 0.25f),
                    radius = size.width * 0.22f,
                    center = Offset(size.width / 2f, size.height * 0.05f),
                    style = Stroke(width = 2.5f * density)
                )
            }
            BackdropStyle.WOOD_GRAIN -> {
                drawRect(Brush.verticalGradient(listOf(bg, lerpColor(bg, primary, 0.10f))))
                var y = 0f
                var n = 0
                while (y < size.height) {
                    val p = Path().apply {
                        moveTo(0f, y)
                        var x = 0f
                        while (x <= size.width) {
                            lineTo(x, y + sin((x / size.width) * 6f + n) * 6f * density)
                            x += 12f
                        }
                    }
                    drawPath(p, primary.copy(alpha = 0.05f + (n % 3) * 0.015f), style = Stroke(width = 1.2f * density))
                    y += (14f + (n % 4) * 6f) * density
                    n++
                }
            }
            BackdropStyle.LEAVES -> {
                drawRect(Brush.verticalGradient(listOf(bg, lerpColor(bg, primary, 0.12f))))
                seeds.take(18).forEachIndexed { i, (x, y, s) ->
                    leaf(
                        center = Offset(x * size.width, (0.35f + y * 0.65f) * size.height),
                        length = (26f + s * 40f) * density,
                        angle = s * 360f,
                        color = (if (i % 3 == 0) secondary else primary).copy(alpha = 0.09f)
                    )
                }
            }
            BackdropStyle.WAVES -> {
                drawRect(Brush.verticalGradient(listOf(bg, lerpColor(bg, primary, 0.16f))))
                for (i in 0 until 5) {
                    val base = size.height * (0.6f + i * 0.09f)
                    val p = Path().apply {
                        moveTo(0f, base)
                        var x = 0f
                        while (x <= size.width) {
                            lineTo(x, base + sin((x / size.width) * 2f * PI.toFloat() * 1.5f + i) * 14f * density)
                            x += 10f
                        }
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(p, secondary.copy(alpha = 0.05f))
                }
            }
            BackdropStyle.SUN -> {
                drawRect(
                    Brush.verticalGradient(
                        listOf(lerpColor(bg, primary, 0.22f), lerpColor(bg, tertiary, 0.12f), bg),
                        endY = size.height * 0.7f
                    )
                )
                val c = Offset(size.width * 0.75f, size.height * 0.12f)
                glow(primary, c, size.width * 0.6f, 0.30f)
                drawCircle(primary.copy(alpha = 0.35f), radius = size.width * 0.12f, center = c)
            }
        }
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

private fun DrawScope.glow(color: Color, center: Offset, radius: Float, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), Color.Transparent), center = center, radius = radius),
        radius = radius,
        center = center
    )
}

/** Synthwave-style perspective floor below [horizon] (fraction of height). */
private fun DrawScope.floorGrid(color: Color, horizon: Float) {
    val hy = size.height * horizon
    val cx = size.width / 2f
    for (i in -10..10) {
        val bottomX = cx + i * size.width * 0.18f
        drawLine(color, Offset(cx + i * size.width * 0.02f, hy), Offset(bottomX, size.height), 1.2f * density)
    }
    var t = 0.02f
    while (t < 1f) {
        val y = hy + (size.height - hy) * t * t
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1.2f * density)
        t += 0.09f
    }
}

private fun DrawScope.flower(center: Offset, radius: Float, color: Color) {
    for (i in 0 until 6) {
        val a = i * PI.toFloat() / 3f
        drawCircle(
            color,
            radius = radius * 0.45f,
            center = Offset(center.x + cos(a) * radius * 0.5f, center.y + sin(a) * radius * 0.5f)
        )
    }
    drawCircle(color.copy(alpha = color.alpha * 1.6f), radius = radius * 0.3f, center = center)
}

private fun DrawScope.leaf(center: Offset, length: Float, angle: Float, color: Color) {
    rotate(degrees = angle, pivot = center) {
        val p = Path().apply {
            moveTo(center.x - length / 2f, center.y)
            quadraticBezierTo(center.x, center.y - length * 0.45f, center.x + length / 2f, center.y)
            quadraticBezierTo(center.x, center.y + length * 0.45f, center.x - length / 2f, center.y)
            close()
        }
        drawPath(p, color)
    }
}
