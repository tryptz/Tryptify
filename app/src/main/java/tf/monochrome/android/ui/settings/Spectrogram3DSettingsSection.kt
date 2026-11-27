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
import tf.monochrome.android.ui.player.SpectrogramRenderer
import tf.monochrome.android.ui.player.SPECTROGRAM_3D_SUPPORTED
import tf.monochrome.android.ui.player.Spectrogram3DOverlay
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import tf.monochrome.android.audio.eq.SpectrogramFeed

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
    /** The analyzer's spectra as they are made, for the preview of live sound; null with the analyzer off. */
    liveFeed: SpectrogramFeed? = null,
    /**
     * The colour the player gives the loudest tops and the light through the
     * swells (the playing album's, or the accent with its colours off), so
     * the preview is in the player's colours. Null takes the accent.
     */
    glow: Color? = null,
    /** Lyrics FX › Visualizer under the lyrics, shown here too: it is asked for here first. Null hides it. */
    underLyrics: Boolean? = null,
    onUnderLyrics: (Boolean) -> Unit = {},
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
            liveFeed = liveFeed,
            glow = glow,
            onAdjust = { drag(it) },
            onAdjustDone = { onChange(it.clamped()) },
        )
    }

    if (underLyrics != null) {
        SettingSwitchItem(
            title = stringResource(R.string.fx_visualizer_under_lyrics),
            subtitle = stringResource(R.string.fx_visualizer_under_lyrics_desc),
            checked = underLyrics,
            onCheckedChange = onUnderLyrics,
        )
    }

    SettingSwitchItem(
        title = stringResource(R.string.spectrogram3d_background),
        subtitle = stringResource(R.string.spectrogram3d_background_desc),
        checked = settings.background,
        onCheckedChange = { onChange(settings.copy(background = it)) },
    )

    val percent = { v: Float -> "${(v * 100).roundToInt()}%" }
    SpectroSlider(
        stringResource(R.string.spectrogram3d_speed), stringResource(R.string.spectrogram3d_speed_desc),
        shown.secondsAcross, Spectrogram3DSettings.MIN_SECONDS..Spectrogram3DSettings.MAX_SECONDS,
        { String.format(Locale.US, "%.1f s", it) }, { drag(shown.copy(secondsAcross = it)) }, ::commit,
    )
    // The FFT: its size by the step (five sizes), its overlap, and the
    // range of loudness the height spans.
    val sizes = Spectrogram3DSettings.FFT_SIZES
    SpectroSlider(
        stringResource(R.string.settings_fft_size), stringResource(R.string.spectrogram3d_fft_desc),
        sizes.indexOf(shown.fftSize).coerceAtLeast(0).toFloat(), 0f..(sizes.size - 1).toFloat(),
        { sizes[it.roundToInt().coerceIn(0, sizes.size - 1)].toString() },
        { drag(shown.copy(fftSize = sizes[it.roundToInt().coerceIn(0, sizes.size - 1)])) }, ::commit,
        steps = sizes.size - 2,
    )
    SpectroSlider(
        stringResource(R.string.waterfall_overlap), stringResource(R.string.spectrogram3d_overlap_desc),
        shown.overlapPct, Spectrogram3DSettings.MIN_OVERLAP_PCT..Spectrogram3DSettings.MAX_OVERLAP_PCT,
        { "${it.roundToInt()}%" }, { drag(shown.copy(overlapPct = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_range), stringResource(R.string.spectrogram3d_range_desc),
        shown.rangeDb, Spectrogram3DSettings.MIN_RANGE_DB..Spectrogram3DSettings.MAX_RANGE_DB,
        { "${it.roundToInt()} dB" }, { drag(shown.copy(rangeDb = it)) }, ::commit,
    )
    val ms = { v: Float -> "${v.roundToInt()} ms" }
    SpectroSlider(
        stringResource(R.string.spectrogram3d_attack), stringResource(R.string.spectrogram3d_attack_desc),
        shown.attackMs, 0f..Spectrogram3DSettings.MAX_ATTACK_MS,
        ms, { drag(shown.copy(attackMs = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_release), stringResource(R.string.spectrogram3d_release_desc),
        shown.releaseMs, 0f..Spectrogram3DSettings.MAX_RELEASE_MS,
        ms, { drag(shown.copy(releaseMs = it)) }, ::commit,
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
        stringResource(R.string.spectrogram3d_ripples), stringResource(R.string.spectrogram3d_ripples_desc),
        shown.ripples, 0f..1f, percent, { drag(shown.copy(ripples = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_shadows), stringResource(R.string.spectrogram3d_shadows_desc),
        shown.shadows, 0f..1f, percent, { drag(shown.copy(shadows = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_lyric_light), stringResource(R.string.spectrogram3d_lyric_light_desc),
        shown.lyricLight, 0f..1f, percent, { drag(shown.copy(lyricLight = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_smoothness), stringResource(R.string.spectrogram3d_smoothness_desc),
        shown.smoothness, 0f..1f, percent, { drag(shown.copy(smoothness = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.spectrogram3d_haze), stringResource(R.string.spectrogram3d_haze_desc),
        shown.haze, 0f..1f, percent, { drag(shown.copy(haze = it)) }, ::commit,
    )
    SpectroSlider(
        stringResource(R.string.fx_motion_blur), stringResource(R.string.spectrogram3d_motion_blur_desc),
        shown.motionBlur, 0f..1f, percent, { drag(shown.copy(motionBlur = it)) }, ::commit,
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
    // Which way this phone draws it, said here: the simple version looks
    // nothing like the lit one, and nothing else tells them apart.
    val how = remember(shown.quality) { SpectrogramRenderer.here(shown.quality) }
    val full = how == SpectrogramRenderer.SHADER
    Text(
        stringResource(
            when (how) {
                SpectrogramRenderer.SHADER -> R.string.spectrogram3d_drawn_full
                SpectrogramRenderer.MESH_QUALITY_LOW -> R.string.spectrogram3d_drawn_low
                SpectrogramRenderer.MESH_ANDROID_BELOW_13 -> R.string.spectrogram3d_drawn_old_android
                SpectrogramRenderer.MESH_SHADER_REFUSED -> R.string.spectrogram3d_drawn_refused
            },
        ),
        style = MaterialTheme.typography.bodySmall,
        color = if (full) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )
    // Back to the look as shipped, in one tap. Quality and the preview
    // switch are the listener's own and stay.
    androidx.compose.material3.TextButton(
        onClick = { onChange(Spectrogram3DSettings.DEFAULT.withPersonalFrom(settings)) },
        enabled = shown != Spectrogram3DSettings.DEFAULT.withPersonalFrom(shown),
    ) {
        Text(stringResource(R.string.settings_reset_to_default))
    }
}

/** A pitch as the slider shows it: Hz below a thousand, kHz above. */
private fun hz(value: Float): String =
    if (value >= 1000f) String.format(Locale.US, "%.1f kHz", value / 1000f) else "${value.roundToInt()} Hz"

@Composable
private fun Spectrogram3DPreview(
    settings: Spectrogram3DSettings,
    liveBins: (() -> FloatArray)?,
    liveFeed: SpectrogramFeed?,
    glow: Color?,
    onAdjust: (Spectrogram3DSettings) -> Unit,
    onAdjustDone: (Spectrogram3DSettings) -> Unit,
) {
    // The player's colours: the accent for the water, and for the tops what
    // the player uses (handed in). The theme's third colour was used here
    // once, and the preview's tops were pink over a cover whose own were
    // teal ("preview and non preview don't show the same colors", the owner).
    val accent = MaterialTheme.colorScheme.primary
    val tops = glow ?: accent
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
    // Looks at the analyzer's newest array and notes when it is a new one.
    val newest: () -> FloatArray? = remember {
        {
            val bins = live?.invoke()
            if (bins != null && bins !== lastSeen[0]) {
                // The array there at the start is not an analysis.
                if (lastSeen[0] != null) lastLive[0] = System.nanoTime()
                lastSeen[0] = bins
            }
            bins
        }
    }
    val provider: () -> FloatArray = remember {
        {
            val bins = newest()
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
            // Looked at here, not only when the picture asks for a spectrum:
            // live sound is drawn from the feed, which never asks, so "the
            // array changed lately" ran out after a second and a half, the
            // preview went to the demo and started afresh, saw live sound
            // again and started afresh once more, round and round ("the
            // preview keeps starting again", the owner).
            newest()
            value = liveBins != null && System.nanoTime() - lastLive[0] < LIVE_TIMEOUT_NANOS
            delay(250)
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
                                secondsAcross = s.secondsAcross - pan.x / size.width * 10f,
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
                glow = tops,
                isPlaying = true,
                modifier = Modifier.fillMaxSize(),
                // The demo and the live sound are levels apart: a change
                // between them starts the landscape afresh.
                resetKey = showingLive,
                // Live sound is played from the analyzer's spectra as they
                // are made; the demo signal is one spectrum a frame.
                feed = if (showingLive) liveFeed else null,
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
