package tf.monochrome.android.ui.mixer.fxchain

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.mixer.getParamDefs
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The effect's five presets as a row of chips: the ones safe on a finished
 * master first, then — past a thin divider — its creative settings.
 *
 * The chip that matches the effect's current settings is lit. Nothing is
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
        horizontalArrangement = Arrangement.spacedBy(6.dp)
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
            val selected = preset.matches(defs, plugin.parameters, plugin.dryWet)
            Box(
                modifier = Modifier
                    .clip(MonoDimens.shapePill)
                    .liquidGlass(shape = MonoDimens.shapePill, tintAlpha = if (selected) 0.24f else 0.08f)
                    .clickable { onApply(preset) }
                    .semantics {
                        contentDescription = "${preset.name} preset" +
                            if (preset.mastering) "" else ", creative"
                        this.selected = selected
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = preset.name,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        selected -> accent
                        preset.mastering -> cs.onSurface
                        else -> cs.onSurfaceVariant
                    },
                    maxLines = 1,
                )
            }
        }
    }
}
