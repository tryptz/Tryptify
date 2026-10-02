package tf.monochrome.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.WaveCandySettings
import java.util.Locale
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

/**
 * Settings › Wave Candy: the scope over the artwork and its kick punch, with
 * a live preview of the scope itself. Lives under the spectrum settings, which
 * own the analyzer it draws from — the preview runs while those are on.
 */
@Composable
internal fun WaveCandySettingsSection(
    settings: WaveCandySettings,
    onChange: (WaveCandySettings) -> Unit,
    preview: Boolean,
) {
    SettingsGroupHeader("Wave Candy")
    Text(
        stringResource(R.string.wavecandy_the_oscilloscope_on_the_album_art_and_the_cover),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (preview) {
        // Runs only while it is the preview most on screen: the waterfall's
        // sits a scroll above it on the same tab, and two live previews at
        // once was two full-rate frame loops for one pair of eyes.
        val (running, reportVisibility) = previewGate("waveCandy")
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .then(reportVisibility)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.35f)),
        ) {
            if (running) {
                tf.monochrome.android.ui.player.WaveCandyOverlay(
                    settings = settings,
                    accent = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                PreviewPaused(Modifier.align(androidx.compose.ui.Alignment.Center))
            }
        }
    }
    SettingSwitchItem(
        title = stringResource(R.string.wavecandy_stereo_waveform),
        subtitle = if (settings.stereo) stringResource(R.string.wavecandy_stereo_on) else stringResource(R.string.wavecandy_stereo_off),
        checked = settings.stereo,
        onCheckedChange = { onChange(settings.copy(stereo = it)) },
    )
    SettingSwitchItem(
        title = stringResource(R.string.wavecandy_album_colour),
        subtitle = stringResource(R.string.wavecandy_draw_the_waveform_in_the_album_s_accent_instead),
        checked = settings.albumColor,
        onCheckedChange = { onChange(settings.copy(albumColor = it)) },
    )
    Text(stringResource(R.string.fx_glow), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.settingsAnchor(stringResource(R.string.fx_glow)).padding(top = 8.dp, bottom = 6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
        tf.monochrome.android.domain.model.WaveGlow.entries.forEach { g ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = g.label,
                selected = settings.glow == g,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onChange(settings.copy(glow = g)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    WaveSlider(stringResource(R.string.wavecandy_time_window), settings.windowMs, WaveCandySettings.MIN_WINDOW_MS..WaveCandySettings.MAX_WINDOW_MS,
        { "${it.toInt()} ms" }) { onChange(settings.copy(windowMs = it)) }
    WaveSlider(stringResource(R.string.wavecandy_height), settings.gain, 0.25f..3f, { String.format(Locale.US, "%.2fx", it) }) {
        onChange(settings.copy(gain = it))
    }
    WaveSlider(stringResource(R.string.wavecandy_thickness), settings.thicknessDp, 0.5f..4f, { String.format(Locale.US, "%.1f dp", it) }) {
        onChange(settings.copy(thicknessDp = it))
    }
    SettingSwitchItem(
        title = stringResource(R.string.wavecandy_kick_punch),
        subtitle = stringResource(R.string.wavecandy_the_cover_punches_in_on_each_kick_drum),
        checked = settings.kickEnabled,
        onCheckedChange = { onChange(settings.copy(kickEnabled = it)) },
    )
    if (settings.kickEnabled) {
        WaveSlider(stringResource(R.string.wavecandy_punch_strength), settings.kickZoom, 0f..0.12f, { "${(it * 100).toInt()}%" }) {
            onChange(settings.copy(kickZoom = it))
        }
        WaveSlider(stringResource(R.string.wavecandy_kick_sensitivity), settings.kickSensitivity, 0f..1f, { "${(it * 100).toInt()}%" }) {
            onChange(settings.copy(kickSensitivity = it))
        }
    }
}

/**
 * One labelled slider. Follows the finger locally and saves on release —
 * writing the settings store on every drag event would stutter the drag.
 */
@Composable
private fun WaveSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onCommit: (Float) -> Unit,
) {
    var dragging by remember { mutableFloatStateOf(Float.NaN) }
    val shown = if (dragging.isNaN()) value else dragging
    Column(Modifier.fillMaxWidth().settingsAnchor(title).padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(shown), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = shown,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                if (!dragging.isNaN()) onCommit(dragging)
                dragging = Float.NaN
            },
            valueRange = range,
        )
    }
}
