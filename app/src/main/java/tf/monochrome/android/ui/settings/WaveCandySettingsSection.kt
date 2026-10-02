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
        "The oscilloscope on the album art, and the cover punching in on the kick. " +
            "Switch to it with the waveform button on the art.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (preview) {
        tf.monochrome.android.ui.player.WaveCandyOverlay(
            settings = settings,
            accent = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.35f)),
        )
    }
    SettingSwitchItem(
        title = "Stereo waveform",
        subtitle = if (settings.stereo) "Left across the top, right across the bottom" else "One line, both channels summed",
        checked = settings.stereo,
        onCheckedChange = { onChange(settings.copy(stereo = it)) },
    )
    SettingSwitchItem(
        title = "Album colour",
        subtitle = "Draw the waveform in the album's accent instead of white",
        checked = settings.albumColor,
        onCheckedChange = { onChange(settings.copy(albumColor = it)) },
    )
    Text("Glow", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.settingsAnchor("Glow").padding(top = 8.dp, bottom = 6.dp))
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
    WaveSlider("Time window", settings.windowMs, WaveCandySettings.MIN_WINDOW_MS..WaveCandySettings.MAX_WINDOW_MS,
        { "${it.toInt()} ms" }) { onChange(settings.copy(windowMs = it)) }
    WaveSlider("Height", settings.gain, 0.25f..3f, { String.format(Locale.US, "%.2fx", it) }) {
        onChange(settings.copy(gain = it))
    }
    WaveSlider("Thickness", settings.thicknessDp, 0.5f..4f, { String.format(Locale.US, "%.1f dp", it) }) {
        onChange(settings.copy(thicknessDp = it))
    }
    SettingSwitchItem(
        title = "Kick punch",
        subtitle = "The cover punches in on each kick drum",
        checked = settings.kickEnabled,
        onCheckedChange = { onChange(settings.copy(kickEnabled = it)) },
    )
    if (settings.kickEnabled) {
        WaveSlider("Punch strength", settings.kickZoom, 0f..0.12f, { "${(it * 100).toInt()}%" }) {
            onChange(settings.copy(kickZoom = it))
        }
        WaveSlider("Kick sensitivity", settings.kickSensitivity, 0f..1f, { "${(it * 100).toInt()}%" }) {
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
