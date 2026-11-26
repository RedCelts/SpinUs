package com.oniel.music.ui

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oniel.music.R
import com.oniel.music.data.Level
import java.text.NumberFormat

private fun fmt(n: Int): String = NumberFormat.getIntegerInstance().format(n)

/** Level card: badge, title, XP bar, next title, and (tap) where the XP came from. */
@Composable
fun LevelCard(level: Level, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .clickable { open = !open }
            .padding(16.dp)
            .animateContentSize()
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LevelBadge(level.level, sizeDp = 64)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.level_label, level.level),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(level.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.level_total_xp, fmt(level.xp)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = level.progress.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surface
        )
        Spacer(Modifier.height(6.dp))
        val next = level.nextLevelXp
        Text(
            if (next == null) stringResource(R.string.level_max)
            else stringResource(R.string.level_to_next, fmt(next - level.xp), level.level + 1),
            style = MaterialTheme.typography.bodySmall
        )
        if (level.nextTitle != null && level.nextTitleLevel != null) {
            Text(
                stringResource(R.string.level_next_title, level.nextTitle, level.nextTitleLevel),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (open) {
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.level_breakdown), fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            level.breakdown.forEach { part ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(part.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.level_part_detail, fmt(part.count), part.xpEach),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text("+${fmt(part.xp)}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        } else {
            Text(
                stringResource(R.string.level_tap_details),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** Round badge with the level number. */
@Composable
fun LevelBadge(level: Int, sizeDp: Int = 28) {
    Box(
        modifier = Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center
    ) {
        Text(
            level.toString(),
            color = MaterialTheme.colorScheme.onPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = (sizeDp * 0.42f).sp
        )
    }
}

/** Remembers the last level seen, to celebrate a level-up once. */
object LevelWatch {
    private const val PREFS = "level"

    /** Returns true when [level] is higher than the last one seen (and records it). */
    fun leveledUp(ctx: Context, userId: String, level: Int): Boolean {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "last_$userId"
        val last = p.getInt(key, -1)
        if (last != level) p.edit().putInt(key, level).apply()
        return last != -1 && level > last
    }
}
