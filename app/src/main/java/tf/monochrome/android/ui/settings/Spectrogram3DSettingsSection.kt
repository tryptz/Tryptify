package tf.monochrome.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.domain.model.Spectrogram3DSettings
import tf.monochrome.android.ui.player.SPECTROGRAM_3D_SUPPORTED
import tf.monochrome.android.ui.player.Spectrogram3DOverlay
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Settings › 3D spectrogram: the cover's third view, with a preview that can
 * be switched off and is steered by touch like the waterfall's (one finger: up
 * and down the camera angle, left and right the speed; two: pinch the peak
 * height). The preview and every slider move a draft and save on release, as
 * the waterfall's do: writing the store on every drag event stutters the drag.
 */
@Composable
internal fun Spectrogram3DSettingsSection(
    settings: Spectrogram3DSettings,
    onChange: (Spectrogram3DSettings) -> Unit,
    /** The analyzer's bins, or null when the analyzer is switched off. */
    liveBins: (() -> FloatArray)?,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    val shown = draft.clamped()
    fun drag(next: Spectrogram3DSettings) { draft = next.clamped() }
    fun commit() { onChange(draft.clamped()) }

    SettingsGroupHeader(stringResource(R.string.settings_spectrogram_3d))
    Text(
        stringResource(R.string.spectrogram3d_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
    if (!SPECTROGRAM_3D_SUPPORTED) {
        Text(
            stringResource(R.string.spectrogram3d_needs_android_10),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        return
    }

    SettingSwitchItem(
        title = stringResource(R.string.spectrogram3d_preview),
        subtitle = stringResource(R.string.spectrogram3d_preview_desc),
        checked = settings.preview,
        onCheckedChange = { onChange(settings.copy(preview = it)) },
    )
    if (settings.preview) {
        Spectrogram3DPreview(
            settings = shown,
            liveBins = liveBins,
            onAdjust = { drag(it) },
            onAdjustDone = { onChange(it.clamped()) },
        )
    }

    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }
    SpectroSlider(
        stringResource(R.string.spectrogram3d_speed), stringResource(R.string.spectrogram3d_speed_desc),
        shown.secondsAcross, Spectrogram3DSettings.MIN_SECONDS..Spectrogram3DSettings.MAX_SECONDS,
        { String.format(Locale.US, "%.1f s", it) }, { drag(shown.copy(secondsAcross = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_angle), stringResource(R.string.spectrogram3d_angle_desc),
        shown.angleDeg, Spectrogram3DSettings.MIN_ANGLE_DEG..Spectrogram3DSettings.MAX_ANGLE_DEG,
        { "${it.roundToInt()}°" }, { drag(shown.copy(angleDeg = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_peak), stringResource(R.string.spectrogram3d_peak_desc),
        shown.peakHeight, Spectrogram3DSettings.MIN_PEAK..Spectrogram3DSettings.MAX_PEAK,
        percent, { drag(shown.copy(peakHeight = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_gloss), stringResource(R.string.spectrogram3d_gloss_desc),
        shown.gloss, 0f..1f, percent, { drag(shown.copy(gloss = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_subsurface), stringResource(R.string.spectrogram3d_subsurface_desc),
        shown.subsurface, 0f..1f, percent, { drag(shown.copy(subsurface = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_smoothness), stringResource(R.string.spectrogram3d_smoothness_desc),
        shown.smoothness, 0f..1f, percent, { drag(shown.copy(smoothness = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_haze), stringResource(R.string.spectrogram3d_haze_desc),
        shown.haze, 0f..1f, percent, { drag(shown.copy(haze = it)) }, ::commit,
    )
    // Pitch on a log scale: an octave is the same distance anywhere on the slider.
    SpectroSlider(
        stringResource(R.string.spectrogram3d_low), stringResource(R.string.spectrogram3d_low_desc),
        ln(shown.minHz), ln(Spectrogram3DSettings.MIN_HZ)..ln(Spectrogram3DSettings.MAX_LOW_HZ),
        { hz(exp(it)) }, { drag(shown.copy(minHz = exp(it))) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_high), stringResource(R.string.spectrogram3d_high_desc),
        ln(shown.maxHz), ln(Spectrogram3DSettings.MIN_HIGH_HZ)..ln(Spectrogram3DSettings.MAX_HZ),
        { hz(exp(it)) }, { drag(shown.copy(maxHz = exp(it))) }, ::commit,
    )
    val qualityNames = listOf(
        stringResource(R.string.spectrogram3d_quality_low),
        stringResource(R.string.spectrogram3d_quality_medium),
        stringResource(R.string.spectrogram3d_quality_high),
        stringResource(R.string.spectrogram3d_quality_ultra),
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_quality), stringResource(R.string.spectrogram3d_quality_desc),
        shown.quality.toFloat(),
        Spectrogram3DSettings.MIN_QUALITY.toFloat()..Spectrogram3DSettings.MAX_QUALITY.toFloat(),
        { qualityNames[(it.roundToInt() - 1).coerceIn(0, 3)] },
        { drag(shown.copy(quality = it.roundToInt())) }, ::commit,
        steps = Spectrogram3DSettings.MAX_QUALITY - Spectrogram3DSettings.MIN_QUALITY - 1,
    )
}

/** A pitch as the slider shows it: Hz below a thousand, kHz above. */
private fun hz(value: Float): String =
    if (value >= 1000f) String.format(Locale.US, "%.1f kHz", value / 1000f) else "${value.roundToInt()} Hz"

@Composable
private fun Spectrogram3DPreview(
    settings: Spectrogram3DSettings,
    liveBins: (() -> FloatArray)?,
    onAdjust: (Spectrogram3DSettings) -> Unit,
    onAdjustDone: (Spectrogram3DSettings) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val glow = MaterialTheme.colorScheme.tertiary
    val live by rememberUpdatedState(liveBins)
    // The gesture loop is started once and outlives recompositions, so it
    // reads these through State rather than capturing their first values.
    val current by rememberUpdatedState(settings)
    val adjust by rememberUpdatedState(onAdjust)
    val adjustDone by rememberUpdatedState(onAdjustDone)
    val (running, reportVisibility) = previewGate("spectrogram3d")

    // The live analyzer while audio flows, the waterfall's demo signal
    // otherwise. The feed hands out a new array each analysis and is read
    // here, not collected, so "audio flows" is "the array changed lately".
    val lastLive = remember { longArrayOf(0L) }
    val lastSeen = remember { arrayOfNulls<FloatArray>(1) }
    val demo = remember { FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) }
    val provider: () -> FloatArray = remember {
        {
            val bins = live?.invoke()
            if (bins != null && bins !== lastSeen[0]) {
                // The array there at the start is not an analysis.
                if (lastSeen[0] != null) lastLive[0] = System.nanoTime()
                lastSeen[0] = bins
            }
            if (bins != null && System.nanoTime() - lastLive[0] < LIVE_TIMEOUT_NANOS) {
                bins
            } else {
                fillDemoSpectrum(demo, System.nanoTime() / 1e9)
                demo
            }
        }
    }
    val showingLive by produceState(initialValue = false, liveBins) {
        while (true) {
            value = liveBins != null && System.nanoTime() - lastLive[0] < LIVE_TIMEOUT_NANOS
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .then(reportVisibility)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF07090D))
            .pointerInput(Unit) {
                // Touch control. One finger: up and down tilt the camera, left
                // and right set the speed (right is faster). Two: pinch for
                // peak height. Moves are consumed, so the page does not scroll
                // under a drag that started here.
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var s = current
                    var changed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val moved = if (pressed.size >= 2) {
                            s.copy(peakHeight = s.peakHeight * event.calculateZoom())
                        } else {
                            val pan = event.calculatePan()
                            s.copy(
                                angleDeg = s.angleDeg - pan.y / size.height * 60f,
                                secondsAcross = s.secondsAcross - pan.x / size.width * 12f,
                            )
                        }
                        val next = moved.clamped()
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        if (next != s) {
                            s = next
                            changed = true
                            adjust(s)
                        }
                    }
                    if (changed) adjustDone(s)
                }
            },
    ) {
        if (running) {
            Spectrogram3DOverlay(
                bins = provider,
                settings = settings,
                color = accent,
                glow = glow,
                isPlaying = true,
                modifier = Modifier.fillMaxSize(),
                // The demo and the live sound are levels apart: a change
                // between them starts the landscape afresh.
                resetKey = showingLive,
            )
        } else {
            PreviewPaused(Modifier.align(Alignment.Center))
        }
        Text(
            if (showingLive) stringResource(R.string.waterfall_live) else stringResource(R.string.waterfall_demo),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
        )
    }
}

/** Analyzer silence for this long and the preview switches to the demo, as the waterfall's does. */
private const val LIVE_TIMEOUT_NANOS = 1_500_000_000L

/** One labelled slider: [onDrag] while the finger moves, [onCommit] on release. */
@Composable
private fun SpectroSlider(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onDrag: (Float) -> Unit,
    onCommit: () -> Unit,
    steps: Int = 0,
) {
    Column(Modifier.fillMaxWidth().settingsAnchor(title).padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                format(value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onDrag,
            onValueChangeFinished = onCommit,
            valueRange = range,
            steps = steps,
        )
    }
}
