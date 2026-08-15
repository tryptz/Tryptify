// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.sampler.stems.Stem
import tf.monochrome.android.audio.sampler.stems.StemQuality
import tf.monochrome.android.ui.patterns.PanelLabel
import tf.monochrome.android.ui.patterns.PatternPanel
import tf.monochrome.android.ui.patterns.PatternPill
import tf.monochrome.android.ui.patterns.TransportKey
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.roundToInt

/**
 * The Stem Studio.
 *
 * Three states in one panel rather than three screens, because they are three
 * moments of one action: choose a quality and start, watch it run, then mix
 * and keep what came out. Navigating away and back between them would lose the
 * result, which lives in memory until the user saves it.
 *
 * The backend's own description is shown rather than a claim written here.
 * What separation can and cannot do depends entirely on which implementation
 * is bound, and a UI that promises clean vocals regardless would be lying for
 * whichever backend is not that good.
 */
@Composable
fun StemStudioPanel(
    stems: SamplerViewModel.StemsState,
    description: String,
    accent: Color,
    playingStem: Boolean,
    modifier: Modifier = Modifier,
    onSeparate: (StemQuality) -> Unit,
    onCancel: () -> Unit,
    onLevel: (Stem, Float) -> Unit,
    onPlayStem: (Stem) -> Unit,
    onPlayMix: () -> Unit,
    onStopPreview: () -> Unit,
    onEditStem: (Stem) -> Unit,
    onSaveStem: (Stem) -> Unit,
    onSaveAll: () -> Unit,
) {
    PatternPanel(modifier = modifier) {
        PanelLabel("Stem studio")
        Spacer(Modifier.height(6.dp))

        when {
            stems.running -> SeparatingState(stems, accent, onCancel)
            stems.hasResults -> ResultsState(
                stems = stems,
                accent = accent,
                playing = playingStem,
                onLevel = onLevel,
                onPlayStem = onPlayStem,
                onPlayMix = onPlayMix,
                onStopPreview = onStopPreview,
                onEditStem = onEditStem,
                onSaveStem = onSaveStem,
                onSaveAll = onSaveAll,
                onSeparate = onSeparate,
            )

            else -> IdleState(stems.quality, description, accent, onSeparate)
        }
    }
}

@Composable
private fun IdleState(
    quality: StemQuality,
    description: String,
    accent: Color,
    onSeparate: (StemQuality) -> Unit,
) {
    Text(
        text = description,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(10.dp))

    PanelLabel("Quality")
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StemQuality.entries.forEach { option ->
            PatternPill(
                label = option.label,
                active = option == quality,
                accent = accent,
                modifier = Modifier.weight(1f),
                // Selecting a quality starts the run. A separate confirm step
                // for a choice of three is a tap nobody wants.
                onClick = { onSeparate(option) },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Higher quality takes longer. Vocals need a stereo source.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SeparatingState(
    stems: SamplerViewModel.StemsState,
    accent: Color,
    onCancel: () -> Unit,
) {
    // Animated so the bar moves smoothly between the separator's coarse
    // milestones rather than jumping in four visible steps.
    val fraction by animateFloatAsState(
        targetValue = stems.progress.fraction.coerceIn(0f, 1f),
        animationSpec = tween(220),
        label = "stemProgress",
    )

    Text(
        text = stems.progress.stage.ifBlank { "Working" },
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Spacer(Modifier.height(8.dp))

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(MonoDimens.shapeSm)
            .background(accent.copy(alpha = 0.16f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(8.dp)
                .clip(MonoDimens.shapeSm)
                .background(accent),
        )
    }

    Spacer(Modifier.height(6.dp))
    Text(
        text = "${(fraction * 100).roundToInt()}%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(10.dp))
    PatternPill(
        label = "Cancel",
        active = false,
        accent = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
        onClick = onCancel,
    )
}

@Composable
private fun ResultsState(
    stems: SamplerViewModel.StemsState,
    accent: Color,
    playing: Boolean,
    onLevel: (Stem, Float) -> Unit,
    onPlayStem: (Stem) -> Unit,
    onPlayMix: () -> Unit,
    onStopPreview: () -> Unit,
    onEditStem: (Stem) -> Unit,
    onSaveStem: (Stem) -> Unit,
    onSaveAll: () -> Unit,
    onSeparate: (StemQuality) -> Unit,
) {
    PanelLabel("Mixer")
    Spacer(Modifier.height(6.dp))

    stems.results.keys.forEach { stem ->
        val level = stems.levels[stem] ?: 1f
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stem.label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (level > 0.01f) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
                modifier = Modifier.width(64.dp),
            )
            Slider(
                value = level,
                onValueChange = { onLevel(stem, it) },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = accent,
                    activeTrackColor = accent,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier.weight(1f).height(32.dp),
            )
            Spacer(Modifier.width(6.dp))
            PatternPill(
                label = "▶",
                active = false,
                accent = accent,
                modifier = Modifier.width(44.dp),
                onClick = { onPlayStem(stem) },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.width(64.dp))
            PatternPill(
                label = "Edit",
                active = false,
                accent = accent,
                modifier = Modifier.weight(1f),
                // Sends the stem back through the editor, which is how a raw
                // drums stem becomes a one-bar loop worth putting on a channel.
                onClick = { onEditStem(stem) },
            )
            PatternPill(
                label = "Save",
                active = false,
                accent = accent,
                modifier = Modifier.weight(1f),
                onClick = { onSaveStem(stem) },
            )
        }
        Spacer(Modifier.height(8.dp))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TransportKey(
            label = if (playing) "Stop" else "Play mix",
            active = playing,
            accent = accent,
            modifier = Modifier.weight(2f),
            onClick = { if (playing) onStopPreview() else onPlayMix() },
        )
        PatternPill(
            label = "Mute all",
            active = false,
            accent = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
            onClick = { stems.results.keys.forEach { onLevel(it, 0f) } },
        )
    }

    Spacer(Modifier.height(8.dp))
    PatternPill(
        label = "Add all to library",
        active = true,
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
        onClick = onSaveAll,
    )
    Spacer(Modifier.height(6.dp))
    PatternPill(
        label = "Separate again",
        active = false,
        accent = accent,
        modifier = Modifier.fillMaxWidth(),
        onClick = { onSeparate(stems.quality) },
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = "Stems are held in memory until you save them.",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
