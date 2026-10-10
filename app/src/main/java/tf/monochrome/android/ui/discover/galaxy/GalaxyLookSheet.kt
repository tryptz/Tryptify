package tf.monochrome.android.ui.discover.galaxy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import tf.monochrome.android.ui.settings.FxSlider
import tf.monochrome.android.ui.settings.FxToggle
import kotlin.math.roundToInt

/**
 * The galaxy's look, tuned on the map itself so every change shows as it is
 * made: the sky, the core, the motion, the music, and the stars.
 *
 * Laid out the way the Player Visuals Studio is: a tab per group, and the
 * Studio's own compact rows ([FxSlider], [FxToggle]), so one group is a few
 * rows and never a scroll. It was every group in one scrolling column, which
 * filled half the screen and still hid most of itself.
 *
 * A glass sheet in the dock's place, the UI panels material like every pane.
 * Kept inside [maxHeight] so it never covers the map it is tuning. With
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
    var tab by rememberSaveable { mutableIntStateOf(0) }
    GlassPanel(hazeState = hazeState, glass = glass, modifier = modifier, avoidNavigationBar = false) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.galaxy_look),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onChange(GalaxyVisualSettings.DEFAULT) },
                    enabled = visuals != GalaxyVisualSettings.DEFAULT,
                ) { Text(stringResource(R.string.galaxy_look_reset)) }
            }
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                GROUPS.forEachIndexed { k, name ->
                    // Five tabs share a phone's width, so a long name ("Bewegung",
                    // "Mouvement") shrinks to fit rather than being cut to "Mo…";
                    // the Tab's own text slot pads 16 dp a side, which is most of
                    // a tab on a small phone.
                    Tab(selected = tab == k, onClick = { tab = k }, modifier = Modifier.heightIn(min = 44.dp)) {
                        BasicText(
                            stringResource(name),
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = LocalContentColor.current,
                                textAlign = TextAlign.Center,
                            ),
                            maxLines = 1,
                            autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = 12.sp),
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                }
            }
            if (lowPower) {
                Text(
                    text = stringResource(R.string.gv_low_power),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Column(
                modifier = Modifier
                    .heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 2.dp),
            ) {
                when (tab) {
                    0 -> SkyGroup(visuals, lowPower, onChange)
                    1 -> RaysGroup(visuals, lowPower, onChange)
                    2 -> MotionGroup(visuals, lowPower, onChange)
                    3 -> MusicGroup(visuals, lowPower, onChange)
                    else -> StarsGroup(visuals, onChange)
                }
            }
        }
    }
}

private val GROUPS = listOf(R.string.gv_sky, R.string.gv_rays, R.string.gv_motion, R.string.gv_music, R.string.gv_stars)

@Composable
private fun SkyGroup(v: GalaxyVisualSettings, lowPower: Boolean, onChange: (GalaxyVisualSettings) -> Unit) {
    Toggle(R.string.gv_deep_sky, v.deepSky, enabled = !lowPower) { onChange(v.copy(deepSky = it)) }
    Share(R.string.gv_nebulae, v.nebulae, GalaxyVisualSettings.NEBULAE_RANGE) { onChange(v.copy(nebulae = it)) }
    Amount(R.string.gv_dust, v.dust, enabled = !lowPower) { onChange(v.copy(dust = it)) }
    Toggle(R.string.gv_black_hole, v.blackHole) { onChange(v.copy(blackHole = it)) }
}

/**
 * The god rays: whether there are any, how many of the other stars shine
 * their own, and the light itself — how bright, how dark its shadows, how much
 * its glow blooms, and how far it runs.
 */
@Composable
private fun RaysGroup(v: GalaxyVisualSettings, lowPower: Boolean, onChange: (GalaxyVisualSettings) -> Unit) {
    Toggle(R.string.gv_god_rays, v.godRays, enabled = !lowPower) { onChange(v.copy(godRays = it)) }
    val rays = v.godRays && !lowPower
    Count(R.string.gv_star_rays, v.starRays, GalaxyVisualSettings.STAR_RAYS_RANGE, enabled = rays) {
        onChange(v.copy(starRays = it))
    }
    Share(R.string.gv_ray_strength, v.rayStrength, GalaxyVisualSettings.RAY_RANGE, enabled = rays) {
        onChange(v.copy(rayStrength = it))
    }
    Share(R.string.gv_ray_shade, v.rayShade, GalaxyVisualSettings.SHADE_RANGE, enabled = rays, zero = R.string.gv_off) {
        onChange(v.copy(rayShade = it))
    }
    Share(R.string.gv_bloom, v.bloom, GalaxyVisualSettings.BLOOM_RANGE, enabled = rays, zero = R.string.gv_off) {
        onChange(v.copy(bloom = it))
    }
    Share(R.string.gv_ray_length, v.rayLength, GalaxyVisualSettings.RAY_LENGTH_RANGE, enabled = rays) {
        onChange(v.copy(rayLength = it))
    }
}

@Composable
private fun MotionGroup(v: GalaxyVisualSettings, lowPower: Boolean, onChange: (GalaxyVisualSettings) -> Unit) {
    Share(R.string.gv_spin, v.spin, GalaxyVisualSettings.SPIN_RANGE, enabled = !lowPower, zero = R.string.gv_still) {
        onChange(v.copy(spin = it))
    }
    Toggle(R.string.gv_twinkle, v.twinkle, enabled = !lowPower) { onChange(v.copy(twinkle = it)) }
    Toggle(R.string.gv_travel_blur, v.travelBlur, enabled = !lowPower) { onChange(v.copy(travelBlur = it)) }
}

@Composable
private fun MusicGroup(v: GalaxyVisualSettings, lowPower: Boolean, onChange: (GalaxyVisualSettings) -> Unit) {
    Toggle(R.string.gv_smoke, v.smoke, enabled = !lowPower) { onChange(v.copy(smoke = it)) }
    val gas = v.smoke && !lowPower
    Share(R.string.gv_smoke_amount, v.smokeAmount, GalaxyVisualSettings.SMOKE_RANGE, enabled = gas) {
        onChange(v.copy(smokeAmount = it))
    }
    Toggle(R.string.gv_music_reactive, v.musicReactive, enabled = gas) { onChange(v.copy(musicReactive = it)) }
    Share(R.string.gv_reactivity, v.reactivity, GalaxyVisualSettings.REACTIVITY_RANGE, enabled = gas && v.musicReactive) {
        onChange(v.copy(reactivity = it))
    }
}

@Composable
private fun StarsGroup(v: GalaxyVisualSettings, onChange: (GalaxyVisualSettings) -> Unit) {
    Share(R.string.gv_star_size, v.starSize, GalaxyVisualSettings.STAR_SIZE_RANGE) { onChange(v.copy(starSize = it)) }
    Amount(R.string.gv_labels, v.labels) { onChange(v.copy(labels = it)) }
    Toggle(R.string.gv_planets, v.planets) { onChange(v.copy(planets = it)) }
    Share(R.string.gv_body_size, v.bodySize, GalaxyVisualSettings.BODY_SIZE_RANGE, enabled = v.planets) {
        onChange(v.copy(bodySize = it))
    }
    // Here, on the map, so turning it shows at once on this very sheet's glass.
    Toggle(R.string.gv_light_glass, v.lightGlass, description = R.string.gv_light_glass_desc) {
        onChange(v.copy(lightGlass = it))
    }
}

/** The Studio's switch row; a disabled one shows off whatever is stored, so it never reads as on. */
@Composable
private fun Toggle(
    label: Int,
    checked: Boolean,
    enabled: Boolean = true,
    description: Int? = null,
    onChecked: (Boolean) -> Unit,
) {
    FxToggle(
        stringResource(label),
        checked && enabled,
        description = description?.let { stringResource(it) },
        enabled = enabled,
        onChange = onChecked,
    )
}

/**
 * A share-of-the-usual slider as the Studio draws one, its value a percentage
 * of the shipped look (100 % is as it ships), or [zero]'s word at nothing.
 */
@Composable
private fun Share(
    label: Int,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    zero: Int? = null,
    onValue: (Float) -> Unit,
) {
    val shown = if (zero != null && value <= 0.001f) stringResource(zero) else "${(value * 100).roundToInt()}%"
    FxSlider(stringResource(label), shown, value, range, enabled = enabled, onChange = onValue)
}

/** A whole number in [range] as a stepped slider: the number, or the zero word at nothing. */
@Composable
private fun Count(label: Int, value: Int, range: IntRange, enabled: Boolean = true, onValue: (Int) -> Unit) {
    val shown = if (value <= 0) stringResource(R.string.gv_off) else value.toString()
    FxSlider(
        stringResource(label), shown, value.toFloat(), range.first.toFloat()..range.last.toFloat(),
        steps = (range.last - range.first - 1).coerceAtLeast(0), enabled = enabled,
    ) { onValue(it.roundToInt().coerceIn(range)) }
}

/** Less, the usual or more, as a three-stop slider: one row, like every other control here. */
@Composable
private fun Amount(label: Int, value: GalaxyAmount, enabled: Boolean = true, onChoose: (GalaxyAmount) -> Unit) {
    val name = when (value) {
        GalaxyAmount.LESS -> R.string.gv_less
        GalaxyAmount.NORMAL -> R.string.gv_normal
        GalaxyAmount.MORE -> R.string.gv_more
    }
    FxSlider(stringResource(label), stringResource(name), value.ordinal.toFloat(), 0f..2f, steps = 1, enabled = enabled) {
        onChoose(GalaxyAmount.entries[it.roundToInt().coerceIn(0, 2)])
    }
}
