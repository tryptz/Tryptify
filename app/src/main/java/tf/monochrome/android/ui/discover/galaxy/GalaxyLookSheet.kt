package tf.monochrome.android.ui.discover.galaxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.GalaxyAmount
import tf.monochrome.android.domain.model.GalaxyVisualSettings
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import kotlin.math.roundToInt

/**
 * The galaxy's look, tuned on the map itself so every change shows as it is
 * made: the sky, the core, the motion, the music, and the stars.
 *
 * A glass sheet in the dock's place, the UI panels material like every pane.
 * Scrolls inside [maxHeight] so it never covers the map it is tuning. With
 * low-performance mode on it says that the heavy effects stay off, rather than
 * offering switches that seem to do nothing.
 */
@Composable
internal fun GalaxyLookSheet(
    visuals: GalaxyVisualSettings,
    onChange: (GalaxyVisualSettings) -> Unit,
    lowPower: Boolean,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    GlassPanel(hazeState = hazeState, glass = glass, modifier = modifier, avoidNavigationBar = false) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.galaxy_look),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onChange(GalaxyVisualSettings.DEFAULT) },
                    enabled = visuals != GalaxyVisualSettings.DEFAULT,
                ) { Text(stringResource(R.string.galaxy_look_reset)) }
            }
            if (lowPower) {
                Text(
                    text = stringResource(R.string.gv_low_power),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp, bottom = 4.dp),
                )
            }
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(end = 8.dp),
            ) {
                Section(R.string.gv_sky)
                SwitchRow(R.string.gv_deep_sky, visuals.deepSky, enabled = !lowPower) { onChange(visuals.copy(deepSky = it)) }
                SliderRow(R.string.gv_nebulae, visuals.nebulae, GalaxyVisualSettings.NEBULAE_RANGE) {
                    onChange(visuals.copy(nebulae = it))
                }
                AmountRow(R.string.gv_dust, visuals.dust, enabled = !lowPower) { onChange(visuals.copy(dust = it)) }

                Section(R.string.gv_core)
                SwitchRow(R.string.gv_black_hole, visuals.blackHole) { onChange(visuals.copy(blackHole = it)) }
                SwitchRow(R.string.gv_god_rays, visuals.godRays, enabled = !lowPower) { onChange(visuals.copy(godRays = it)) }
                SliderRow(
                    R.string.gv_ray_strength, visuals.rayStrength, GalaxyVisualSettings.RAY_RANGE,
                    enabled = visuals.godRays && !lowPower,
                ) { onChange(visuals.copy(rayStrength = it)) }

                Section(R.string.gv_motion)
                SliderRow(
                    R.string.gv_spin, visuals.spin, GalaxyVisualSettings.SPIN_RANGE,
                    enabled = !lowPower,
                    zeroLabel = stringResource(R.string.gv_still),
                ) { onChange(visuals.copy(spin = it)) }
                SwitchRow(R.string.gv_twinkle, visuals.twinkle, enabled = !lowPower) { onChange(visuals.copy(twinkle = it)) }
                SwitchRow(R.string.gv_travel_blur, visuals.travelBlur, enabled = !lowPower) { onChange(visuals.copy(travelBlur = it)) }

                Section(R.string.gv_music)
                SwitchRow(R.string.gv_smoke, visuals.smoke, enabled = !lowPower) { onChange(visuals.copy(smoke = it)) }
                SliderRow(
                    R.string.gv_smoke_amount, visuals.smokeAmount, GalaxyVisualSettings.SMOKE_RANGE,
                    enabled = visuals.smoke && !lowPower,
                ) { onChange(visuals.copy(smokeAmount = it)) }
                SwitchRow(
                    R.string.gv_music_reactive, visuals.musicReactive,
                    enabled = visuals.smoke && !lowPower,
                ) { onChange(visuals.copy(musicReactive = it)) }
                SliderRow(
                    R.string.gv_reactivity, visuals.reactivity, GalaxyVisualSettings.REACTIVITY_RANGE,
                    enabled = visuals.smoke && visuals.musicReactive && !lowPower,
                ) { onChange(visuals.copy(reactivity = it)) }

                Section(R.string.gv_stars)
                SliderRow(R.string.gv_star_size, visuals.starSize, GalaxyVisualSettings.STAR_SIZE_RANGE) {
                    onChange(visuals.copy(starSize = it))
                }
                AmountRow(R.string.gv_labels, visuals.labels) { onChange(visuals.copy(labels = it)) }
                SwitchRow(R.string.gv_planets, visuals.planets) { onChange(visuals.copy(planets = it)) }
            }
        }
    }
}

@Composable
private fun Section(label: Int) {
    Text(
        text = stringResource(label),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, enabled: Boolean = true, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked && enabled, onCheckedChange = onChecked, enabled = enabled)
    }
}

/**
 * A share-of-the-usual slider, labelled with its value as a percentage of the
 * shipped look (100 % is as it ships) — or [zeroLabel] at nothing.
 */
@Composable
private fun SliderRow(
    label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    zeroLabel: String? = null,
    onValue: (Float) -> Unit,
) {
    val dim = if (enabled) 1f else 0.45f
    Column(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = dim),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (zeroLabel != null && value <= 0.001f) zeroLabel else "${(value * 100).roundToInt()} %",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = dim),
            )
        }
        Slider(
            value = value,
            onValueChange = onValue,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.height(36.dp),
        )
    }
}

@Composable
private fun AmountRow(label: Int, value: GalaxyAmount, enabled: Boolean = true, onChoose: (GalaxyAmount) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
            modifier = Modifier.weight(1f),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                GalaxyAmount.LESS to R.string.gv_less,
                GalaxyAmount.NORMAL to R.string.gv_normal,
                GalaxyAmount.MORE to R.string.gv_more,
            ).forEach { (amount, name) ->
                GlassChoiceChip(
                    label = stringResource(name),
                    selected = amount == value,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { onChoose(amount) },
                    enabled = enabled,
                    height = 32.dp,
                )
            }
        }
        Spacer(Modifier.width(2.dp))
    }
}
