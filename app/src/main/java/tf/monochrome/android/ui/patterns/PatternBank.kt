// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.patterns.PatternLimits
import tf.monochrome.android.domain.patterns.PatternSummary
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The pattern bank.
 *
 * Three states have to be distinguishable at a glance while a loop is running,
 * and they are given three different visual mechanisms rather than three
 * shades of the same one:
 *
 * * **Playing** — filled with the accent. This is what you hear.
 * * **Queued** — outlined and pulsing, with a NEXT badge. This is what you
 *   will hear at the end of the bar. Pulsing rather than filled, because a
 *   queued pattern that looks identical to a playing one is the single most
 *   confusing thing a bank can do.
 * * **Editing** — a thin ring. This is what the grid above is showing, which
 *   is deliberately allowed to differ from what is sounding: choosing a
 *   pattern puts it on screen immediately even though the switch waits for
 *   the boundary.
 */
@Composable
fun PatternBank(
    bank: List<PatternSummary>,
    playingSlot: Int,
    queuedSlot: Int,
    editingSlot: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    columns: Int = 4,
    onSelect: (Int) -> Unit,
    onCreate: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val slots = bank.sortedBy { it.bankSlot }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PanelLabel("Pattern bank")
            if (bank.size < PatternLimits.MAX_PATTERNS) {
                Text(
                    text = "+ NEW",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent,
                    modifier = Modifier
                        .clip(MonoDimens.shapeSm)
                        .clickable {
                            PatternHaptics.patternChange(haptics)
                            onCreate()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))

        slots.chunked(columns).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { summary ->
                    BankKey(
                        summary = summary,
                        playing = summary.bankSlot == playingSlot,
                        queued = summary.bankSlot == queuedSlot,
                        editing = summary.bankSlot == editingSlot,
                        accent = accent,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            PatternHaptics.patternChange(haptics)
                            onSelect(summary.bankSlot)
                        },
                    )
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun BankKey(
    summary: PatternSummary,
    playing: Boolean,
    queued: Boolean,
    editing: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "queuePulse")
        .animateFloat(
            initialValue = 0.25f,
            targetValue = 0.85f,
            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                animation = tween(560),
                repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "queuePulseValue",
        )
    val lit by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = tween(130),
        label = "bankLit",
    )

    val fill = when {
        playing -> lerp(lerp(surface, Color.Black, 0.20f), accent, lit)
        queued -> lerp(lerp(surface, Color.Black, 0.20f), accent, pulse * 0.5f)
        else -> lerp(surface, Color.Black, 0.24f)
    }
    val content = if (playing && fill.luminance() < 0.5f) {
        Color.White
    } else if (playing) {
        Color.Black
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Box(
        modifier = modifier
            .height(52.dp)
            .clip(MonoDimens.shapeSm)
            .background(fill)
            .border(
                width = if (editing || queued) 1.dp else 0.5.dp,
                color = when {
                    queued -> accent.copy(alpha = 0.4f + pulse * 0.6f)
                    editing -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.09f)
                },
                shape = MonoDimens.shapeSm,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = summary.slotLabel,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = content,
            )
            Text(
                // The slot code is the identity, so the name only appears
                // under it when the user has given the pattern one — echoing
                // "A01" twice would just be noise.
                text = if (summary.name == summary.slotLabel) {
                    "${summary.lengthSteps}"
                } else {
                    summary.name
                },
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                color = content.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        if (queued) {
            Text(
                text = "NEXT",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = accent,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 3.dp, end = 4.dp),
            )
        }
    }
}
