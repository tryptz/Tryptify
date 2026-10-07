package tf.monochrome.android.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.ui.player.GodRayGeometry
import java.util.Locale
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Studio's God rays section: the source, the layer, the 3D light direction
 * (a dome to drag the light around, with sliders beside it for exact values
 * and for TalkBack), and the article's exposure / density / decay, plus the
 * motion the Shadertoy's wandering light suggested.
 */
@Composable
internal fun GodRaysControls(
    fx: LyricsFxSettings,
    onUpdate: ((LyricsFxSettings) -> LyricsFxSettings) -> Unit,
) {
    StudioSection(stringResource(R.string.fx_god_rays))
    FxToggle(
        stringResource(R.string.fx_god_rays), fx.godRays,
        description = stringResource(R.string.fx_god_rays_desc),
    ) { on -> onUpdate { it.copy(godRays = on) } }
    // Everything below tunes rays that are not drawn while they are off.
    if (!fx.godRays) return

    FxChoice(
        label = stringResource(R.string.fx_god_ray_source),
        options = listOf(
            stringResource(R.string.fx_god_ray_source_letters),
            stringResource(R.string.fx_god_ray_source_backlight),
        ),
        selected = fx.godRaySource,
        description = stringResource(R.string.fx_god_ray_source_desc),
    ) { i -> onUpdate { it.copy(godRaySource = i) } }
    FxChoice(
        label = stringResource(R.string.fx_god_ray_layer),
        options = listOf(
            stringResource(R.string.fx_god_ray_under),
            stringResource(R.string.fx_god_ray_on_top),
        ),
        selected = if (fx.godRaysOnTop) 1 else 0,
    ) { i -> onUpdate { it.copy(godRaysOnTop = i == 1) } }
    FxToggle(
        stringResource(R.string.fx_god_ray_all_lyrics), fx.godRaysAllLyrics,
        description = stringResource(R.string.fx_god_ray_all_lyrics_desc),
    ) { on -> onUpdate { it.copy(godRaysAllLyrics = on) } }
    // Every lyric shining leaves no single word to follow.
    if (!fx.godRaysAllLyrics) {
        FxToggle(
            stringResource(R.string.fx_god_ray_follow_word), fx.godRaysFollowWord,
            description = stringResource(R.string.fx_god_ray_follow_word_desc),
        ) { on -> onUpdate { it.copy(godRaysFollowWord = on) } }
    }

    FxSlider(
        stringResource(R.string.fx_god_ray_glass_catch), "${(fx.glassRayCatch * 100).roundToInt()}%",
        fx.glassRayCatch, 0f..1f,
        description = stringResource(R.string.fx_god_ray_glass_catch_desc),
    ) { v -> onUpdate { it.copy(glassRayCatch = v) } }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            text = stringResource(R.string.fx_god_ray_direction),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.fx_god_ray_direction_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
            LightDome(
                azimuthDeg = fx.godRayAzimuthDeg,
                elevationDeg = fx.godRayElevationDeg,
                onAim = { az, el -> onUpdate { it.copy(godRayAzimuthDeg = az, godRayElevationDeg = el) } },
            )
        }
        Text(
            text = stringResource(
                R.string.fx_god_ray_direction_value,
                fx.godRayAzimuthDeg.roundToInt(),
                fx.godRayElevationDeg.roundToInt(),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
    FxSlider(
        stringResource(R.string.fx_god_ray_azimuth), "${fx.godRayAzimuthDeg.roundToInt()}°",
        fx.godRayAzimuthDeg, 0f..360f,
        description = stringResource(R.string.fx_god_ray_azimuth_desc),
    ) { v -> onUpdate { it.copy(godRayAzimuthDeg = v) } }
    FxSlider(
        stringResource(R.string.fx_god_ray_elevation), "${fx.godRayElevationDeg.roundToInt()}°",
        fx.godRayElevationDeg, 0f..90f,
        description = stringResource(R.string.fx_god_ray_elevation_desc),
    ) { v -> onUpdate { it.copy(godRayElevationDeg = v) } }

    FxSlider(
        stringResource(R.string.fx_god_ray_exposure), "${(fx.godRayExposure * 100).roundToInt()}%",
        fx.godRayExposure, 0f..1.5f,
        description = stringResource(R.string.fx_god_ray_exposure_desc),
    ) { v -> onUpdate { it.copy(godRayExposure = v) } }
    FxSlider(
        stringResource(R.string.fx_god_ray_length), "${(fx.godRayDensity * 100).roundToInt()}%",
        fx.godRayDensity, 0.2f..1f,
        description = stringResource(R.string.fx_god_ray_length_desc),
    ) { v -> onUpdate { it.copy(godRayDensity = v) } }
    FxSlider(
        stringResource(R.string.fx_god_ray_decay), String.format(Locale.US, "%.3f", fx.godRayDecay),
        fx.godRayDecay, 0.85f..1f,
        description = stringResource(R.string.fx_god_ray_decay_desc),
    ) { v -> onUpdate { it.copy(godRayDecay = v) } }
    if (fx.godRaySource == LyricsFxSettings.GOD_RAYS_BACKLIGHT) {
        FxSlider(
            stringResource(R.string.fx_god_ray_sun_size), "${(fx.godRaySunSize * 100).roundToInt()}%",
            fx.godRaySunSize, 0.03f..0.3f,
        ) { v -> onUpdate { it.copy(godRaySunSize = v) } }
    }
    FxSlider(
        stringResource(R.string.fx_god_ray_dust), "${(fx.godRayShimmer * 100).roundToInt()}%",
        fx.godRayShimmer, 0f..1f,
        description = stringResource(R.string.fx_god_ray_dust_desc),
    ) { v -> onUpdate { it.copy(godRayShimmer = v) } }
    FxSlider(
        stringResource(R.string.fx_god_ray_beat), "${(fx.godRayBeat * 100).roundToInt()}%",
        fx.godRayBeat, 0f..1f,
        description = stringResource(R.string.fx_god_ray_beat_desc),
    ) { v -> onUpdate { it.copy(godRayBeat = v) } }
    FxSlider(
        stringResource(R.string.fx_god_ray_orbit), String.format(Locale.US, "%+.0f°/s", fx.godRaySpinDps),
        fx.godRaySpinDps, -45f..45f,
        description = stringResource(R.string.fx_god_ray_orbit_desc),
    ) { v -> onUpdate { it.copy(godRaySpinDps = v) } }
    FxSlider(
        stringResource(R.string.fx_god_ray_sway), "${(fx.godRaySway * 100).roundToInt()}%",
        fx.godRaySway, 0f..1f,
        description = stringResource(R.string.fx_god_ray_sway_desc),
    ) { v -> onUpdate { it.copy(godRaySway = v) } }
    FxSlider(
        stringResource(R.string.fx_tilt_reactivity), "${(fx.godRayTilt * 100).roundToInt()}%",
        fx.godRayTilt, 0f..1.5f,
        description = stringResource(R.string.fx_god_ray_tilt_desc),
    ) { v -> onUpdate { it.copy(godRayTilt = v) } }
    FxSlider(
        stringResource(R.string.fx_quality),
        stringResource(R.string.fx_god_ray_samples_value, GodRayGeometry.samplesFor(fx.godRayQuality)),
        fx.godRayQuality.toFloat(), 1f..4f, steps = 2,
        description = stringResource(R.string.fx_god_ray_quality_desc),
    ) { v -> onUpdate { it.copy(godRayQuality = v.roundToInt()) } }
}

/** A label with one chip per option, exactly one of them selected. */
@Composable
private fun FxChoice(
    label: String,
    options: List<String>,
    selected: Int,
    description: String? = null,
    onSelect: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEachIndexed { i, option ->
                FilterChip(
                    selected = i == selected,
                    onClick = { onSelect(i) },
                    label = { Text(option) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Share of the canvas the dome fills, leaving room for the light's glow at the rim. */
private const val DOME_SHARE = 0.86f

/**
 * The light's 3D direction, as a dome seen from straight above: the centre is
 * the top of the dome (the light right behind the sung line, 90°), the rim
 * its foot (the light lying flat, raking in from the side, 0°), and the angle
 * round it the azimuth, with 90° at the top as on the sliders.
 *
 * It is an orthographic view of a hemisphere, so a light at elevation `el`
 * sits `cos(el)` of the way out: the 30° and 60° rings land at 0.87 and 0.5
 * of the radius, and dragging near the rim moves the elevation faster than
 * dragging near the top, the way a real dome tilts away from you.
 */
@Composable
private fun LightDome(
    azimuthDeg: Float,
    elevationDeg: Float,
    onAim: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(
        R.string.fx_god_ray_direction_value,
        azimuthDeg.roundToInt(),
        elevationDeg.roundToInt(),
    )
    val aim by rememberUpdatedState(onAim)
    Canvas(
        modifier = modifier
            .size(184.dp)
            .semantics { contentDescription = label }
            .pointerInput(Unit) {
                fun aimAt(pos: Offset) {
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val r = min(size.width, size.height) / 2f * DOME_SHARE
                    val v = pos - c
                    val reach = (v.getDistance() / r).coerceIn(0f, 1f)
                    val el = Math.toDegrees(acos(reach).toDouble()).toFloat()
                    val az = ((Math.toDegrees(atan2(-v.y, v.x).toDouble()).toFloat()) + 360f) % 360f
                    aim(az, el)
                }
                // Consumed, so a drag on the dome aims the light instead of
                // scrolling the Studio's list under it.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    aimAt(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        aimAt(change.position)
                        change.consume()
                    }
                }
            },
    ) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = min(size.width, size.height) / 2f * DOME_SHARE

        // The dome: lit from where the light is, falling off to the far side.
        val az = Math.toRadians(azimuthDeg.toDouble()).toFloat()
        val reach = cos(Math.toRadians(elevationDeg.toDouble())).toFloat()
        val light = c + Offset(cos(az), -sin(az)) * (reach * r)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.30f), primary.copy(alpha = 0.08f), primary.copy(alpha = 0.02f)),
                center = light,
                radius = r * 1.6f,
            ),
            radius = r,
            center = c,
        )
        drawCircle(color = ink.copy(alpha = 0.55f), radius = r, center = c, style = Stroke(width = 1.5.dp.toPx()))

        // Elevation rings at 60° and 30°, and spokes every 45°.
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        listOf(60f, 30f).forEach { el ->
            drawCircle(
                color = ink.copy(alpha = 0.30f),
                radius = r * cos(Math.toRadians(el.toDouble())).toFloat(),
                center = c,
                style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
            )
        }
        for (k in 0 until 8) {
            val a = (k * 45f) * (Math.PI.toFloat() / 180f)
            val dir = Offset(cos(a), -sin(a))
            drawLine(
                color = ink.copy(alpha = if (k % 2 == 0) 0.30f else 0.15f),
                start = c + dir * (r * 0.12f),
                end = c + dir * r,
                strokeWidth = 1.dp.toPx(),
            )
        }

        // The sung line, at the top of the dome where the light faces it head-on.
        val bar = 14.dp.toPx()
        drawLine(
            color = ink,
            start = c - Offset(bar, 0f),
            end = c + Offset(bar, 0f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )

        // The light, with its ray down to the line.
        drawLine(
            color = primary.copy(alpha = 0.7f),
            start = light,
            end = c,
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.55f), Color.Transparent),
                center = light,
                radius = 20.dp.toPx(),
            ),
            radius = 20.dp.toPx(),
            center = light,
        )
        drawCircle(color = primary, radius = 8.dp.toPx(), center = light)
        drawCircle(color = Color.White, radius = 3.dp.toPx(), center = light)
    }
}
