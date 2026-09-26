package tf.monochrome.android.ui.mixer.fxchain

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.mixer.getParamDefs

/**
 * The effect's five presets as a row of chips: the ones safe on a finished
 * master first, then — past a thin divider — its creative settings.
 *
 * Every chip is the same glass pill ([GlassChoiceChip]): one height, centred
 * single-line label. The chip that matches the effect's current settings is lit. Nothing is
 * stored for that: move one knob or handle and it no longer matches, so it
 * goes out on its own.
 */
@Composable
internal fun FxPresetRow(
    plugin: PluginInstance,
    accent: Color,
    onApply: (FxPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val type = plugin.type ?: return
    val presets = remember(type) { FxPresets.forType(type) }
    val defs = remember(type) { getParamDefs(type) }
    val cs = MaterialTheme.colorScheme

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        presets.forEachIndexed { i, preset ->
            if (i > 0 && presets[i - 1].mastering && !preset.mastering) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .width(1.dp)
                        .height(18.dp)
                        .background(cs.outline.copy(alpha = 0.35f))
                )
            }
            GlassChoiceChip(
                label = preset.name,
                selected = preset.matches(defs, plugin.parameters, plugin.dryWet),
                accent = accent,
                onClick = { onApply(preset) },
                description = "${preset.name} preset" + if (preset.mastering) "" else ", creative",
            )
        }
    }
}
