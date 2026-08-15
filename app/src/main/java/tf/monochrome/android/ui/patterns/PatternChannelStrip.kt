// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.patterns.PatternChannel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * One channel's header: name, assigned sample, and its mute / solo keys.
 *
 * The activity lamp on the left is driven by the engine's per-channel trigger
 * counter, not by anything the UI knows about the pattern. That distinction is
 * the point — the lamp shows what is actually *sounding*, so a channel that is
 * muted, soloed out, or pointing at a sample that failed to load stays dark
 * even though its trigs are lit in the grid. It is the fastest way to see why
 * a row you can see is a row you cannot hear.
 */
@Composable
fun PatternChannelStrip(
    index: Int,
    channel: PatternChannel,
    sampleName: String?,
    selected: Boolean,
    triggerCount: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onSelect: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val flash by animateFloatAsState(
        targetValue = if (triggerCount % 2 == 0) 0f else 1f,
        animationSpec = tween(70),
        label = "channelFlash",
    )
    val surface = MaterialTheme.colorScheme.surface
    val audible = !channel.muted && channel.enabled && channel.sampleId != null

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MonoDimens.shapeSm)
            .background(
                if (selected) {
                    lerp(surface, accent, 0.16f)
                } else {
                    lerp(surface, Color.Black, 0.16f)
                },
            )
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.08f),
                shape = MonoDimens.shapeSm,
            )
            .clickable {
                PatternHaptics.pageChange(haptics)
                onSelect()
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Activity lamp.
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(MonoDimens.shapeCircle)
                .background(
                    if (audible) {
                        lerp(accent.copy(alpha = 0.35f), accent, flash)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
                    },
                ),
        )
        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = channel.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    // An empty channel says so rather than showing a blank
                    // line: "no sample" is the actionable state, and it is the
                    // most common reason a new user's pattern is silent.
                    text = sampleName ?: "No sample — tap to assign",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = if (sampleName == null) {
                        MaterialTheme.colorScheme.error.copy(alpha = 0.75f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        MiniKey(
            label = "M",
            active = channel.muted,
            accent = MaterialTheme.colorScheme.error,
            onClick = {
                PatternHaptics.pageChange(haptics)
                onToggleMute()
            },
        )
        Spacer(Modifier.width(4.dp))
        MiniKey(
            label = "S",
            active = channel.soloed,
            accent = MaterialTheme.colorScheme.tertiary,
            onClick = {
                PatternHaptics.pageChange(haptics)
                onToggleSolo()
            },
        )
        Spacer(Modifier.width(4.dp))
        MiniKey(
            label = "•••",
            active = false,
            accent = accent,
            onClick = onOpenEditor,
        )
    }
}

/** The small square keys on a channel strip. */
@Composable
private fun MiniKey(
    label: String,
    active: Boolean,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier = modifier
            // 36 dp is under the 48 dp guideline and is a deliberate exception:
            // these sit inside a row that is itself a large touch target, and
            // a full-size key for each of three of them would push the sample
            // name off the strip entirely. Every one of them also has a
            // full-size equivalent in the channel editor sheet.
            .size(36.dp)
            .clip(MonoDimens.shapeSm)
            .background(if (active) accent else lerp(surface, Color.Black, 0.26f))
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), MonoDimens.shapeSm)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = if (active) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The row of momentary pads under the grid, one per channel. */
@Composable
fun ChannelPadRow(
    channels: List<PatternChannel>,
    triggers: IntArray,
    accent: Color,
    modifier: Modifier = Modifier,
    onTap: (Int) -> Unit,
) {
    if (channels.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        PanelLabel("Pads")
        Spacer(Modifier.height(6.dp))
        // Four to a row so a pad stays wide enough to read its label and to
        // hit while looking at the grid rather than at the pad.
        channels.chunked(4).forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEachIndexed { columnIndex, channel ->
                    val index = rowIndex * 4 + columnIndex
                    ChannelPad(
                        label = channel.name,
                        triggerCount = triggers.getOrElse(index) { 0 },
                        accent = accent,
                        assigned = channel.sampleId != null,
                        modifier = Modifier.weight(1f),
                        onTap = { onTap(index) },
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
