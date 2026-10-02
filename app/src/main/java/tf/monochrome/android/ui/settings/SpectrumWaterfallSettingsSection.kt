package tf.monochrome.android.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.audio.eq.WaterfallNative
import tf.monochrome.android.domain.model.SpectrumWaterfallSettings
import tf.monochrome.android.domain.model.WaterfallStyle
import tf.monochrome.android.ui.player.SpectrumOverlay
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Settings › Spectrum waterfall: how far back the lines run, where they start
 * to fade, and the angle they rise at — with a preview that says what each of
 * those is doing rather than only showing it.
 *
 * The preview follows a slider while it is being dragged and saves on release,
 * like Wave Candy's; writing the settings store on every drag event would
 * stutter the drag. It draws the live analyzer when audio is flowing and a
 * demo signal otherwise, and says which, so the settings can be tuned with
 * nothing playing.
 */
@Composable
internal fun SpectrumWaterfallSettingsSection(
    settings: SpectrumWaterfallSettings,
    onChange: (SpectrumWaterfallSettings) -> Unit,
    /** The analyzer's bins, or null when the analyzer is switched off. */
    liveBins: (() -> FloatArray)?,
) {
    // What the preview shows: the saved settings, or the one being dragged.
    var draft by remember(settings) { mutableStateOf(settings) }
    val preview = draft.clamped()

    SettingsGroupHeader("Spectrum waterfall")
    Text(
        "The spectrum on the album art. The bright line at the front is now; every " +
            "moment a copy of it falls back, rises and fades, so the last few seconds " +
            "stand behind it.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    WaterfallPreview(preview, liveBins)

    Text(
        "Style",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.settingsAnchor("Waterfall style").padding(top = 8.dp, bottom = 6.dp),
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WaterfallStyle.entries.forEach { style ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = style.label,
                selected = draft.style == style,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onChange(draft.copy(style = style)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
    Text(
        draft.style.description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )

    WaterfallSlider(
        title = "Depth",
        subtitle = "How long a line takes to travel back and disappear",
        value = draft.depthSeconds,
        range = SpectrumWaterfallSettings.MIN_DEPTH_SECONDS..SpectrumWaterfallSettings.MAX_DEPTH_SECONDS,
        format = { String.format(Locale.US, "%.1f s", it) },
        onDrag = { draft = draft.copy(depthSeconds = it) },
        onCommit = { onChange(draft) },
    )
    WaterfallSlider(
        title = "Fade start",
        subtitle = "How far back a line stays at full strength before fading",
        value = draft.fadeStart,
        range = 0f..SpectrumWaterfallSettings.MAX_FADE_START,
        format = {
            String.format(Locale.US, "%d%% · %.1f s", (it * 100).toInt(), it * draft.depthSeconds)
        },
        onDrag = { draft = draft.copy(fadeStart = it) },
        onCommit = { onChange(draft) },
    )
    WaterfallSlider(
        title = "Angle",
        subtitle = "Low: a near-flat horizon. High: looking down on the ridges",
        value = draft.angleDeg,
        range = SpectrumWaterfallSettings.MIN_ANGLE_DEG..SpectrumWaterfallSettings.MAX_ANGLE_DEG,
        format = { "${it.toInt()}°" },
        onDrag = { draft = draft.copy(angleDeg = it) },
        onCommit = { onChange(draft) },
    )
    WaterfallSlider(
        title = "Line weight",
        subtitle = "Thick bands, or fine hairlines like a radio sweep",
        value = draft.lineWidthDp,
        range = SpectrumWaterfallSettings.MIN_LINE_WIDTH_DP..SpectrumWaterfallSettings.MAX_LINE_WIDTH_DP,
        format = { String.format(Locale.US, "%.1f dp", it) },
        onDrag = { draft = draft.copy(lineWidthDp = it) },
        onCommit = { onChange(draft) },
    )
    if (settings != SpectrumWaterfallSettings.DEFAULT) {
        TextButton(onClick = { onChange(SpectrumWaterfallSettings.DEFAULT) }) {
            Text("Reset to default")
        }
    }
}

@Composable
private fun WaterfallPreview(settings: SpectrumWaterfallSettings, liveBins: (() -> FloatArray)?) {
    val accent = MaterialTheme.colorScheme.primary
    val live by rememberUpdatedState(liveBins)

    // When the last new array arrived from the analyzer. Not state: the
    // provider below reads it from the overlay's frame loop.
    val lastLive = remember { longArrayOf(0L) }
    LaunchedEffect(liveBins != null) {
        snapshotFlow { live?.invoke() }.collect { if (it != null) lastLive[0] = System.nanoTime() }
    }
    val demo = remember { FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) }
    val provider: () -> FloatArray = remember {
        {
            val bins = live?.invoke()
            if (bins != null && System.nanoTime() - lastLive[0] < LIVE_TIMEOUT_NANOS) {
                bins
            } else {
                fillDemoSpectrum(demo, System.nanoTime() / 1e9)
                demo
            }
        }
    }
    // For the caption only, so twice a second is plenty.
    val showingLive by produceState(initialValue = false, liveBins) {
        while (true) {
            value = liveBins != null && System.nanoTime() - lastLive[0] < LIVE_TIMEOUT_NANOS
            delay(500)
        }
    }

    val textMeasurer = rememberTextMeasurer()
    val guides = remember { FloatArray(3) }
    Box(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF07090D)),
    ) {
        SpectrumOverlay(
            bins = provider,
            color = accent,
            modifier = Modifier.fillMaxSize(),
            height = 220.dp,
            waterfall = settings,
        )
        // The annotations, from the same projection the lines are drawn with.
        Canvas(Modifier.fillMaxSize()) {
            WaterfallNative.nativeGuides(size.width, size.height, settings.fadeStart, settings.angleDeg, guides)
            drawGuides(textMeasurer, settings, guides, accent)
        }
    }

    // The settings in words, so each number says what it does to the picture.
    val period = settings.depthSeconds / SpectrumWaterfallSettings.LINES
    val fading = settings.depthSeconds - settings.solidSeconds
    Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
        Text(
            if (showingLive) "Live: what is playing now" else "Demo signal: play something to see your music",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (showingLive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            String.format(
                Locale.US,
                "%s, %.1f dp. %d lines, one every %d ms. Full strength for %.1f s, then fading " +
                    "over %.1f s; gone %.1f s after it was heard. Rising at %d°.",
                settings.style.label,
                settings.lineWidthDp,
                SpectrumWaterfallSettings.LINES,
                (period * 1000).toInt(),
                settings.solidSeconds,
                fading,
                settings.depthSeconds,
                settings.angleDeg.toInt(),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Dashed baselines for now, the start of the fade and the end, each labelled
 * with how long ago that line was heard, and the angle drawn as an arc.
 */
private fun DrawScope.drawGuides(
    measurer: TextMeasurer,
    settings: SpectrumWaterfallSettings,
    guides: FloatArray,
    accent: Color,
) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
    val ink = Color.White.copy(alpha = 0.55f)
    val style = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 10.sp)

    fun guide(y: Float, label: String, color: Color) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        val text = measurer.measure(label, style)
        val top = (y - text.size.height - 2.dp.toPx()).coerceIn(0f, size.height - text.size.height)
        drawRect(
            Color.Black.copy(alpha = 0.55f),
            topLeft = Offset(4.dp.toPx(), top),
            size = Size(text.size.width + 8.dp.toPx(), text.size.height.toFloat()),
        )
        drawText(text, topLeft = Offset(8.dp.toPx(), top))
    }

    guide(guides[0], "now", accent.copy(alpha = 0.8f))
    if (settings.fadeStart > 0.02f) {
        guide(guides[1], String.format(Locale.US, "fade begins · −%.1f s", settings.solidSeconds), ink)
    }
    guide(guides[2], String.format(Locale.US, "gone · −%.1f s", settings.depthSeconds), ink)

    // The angle, top right: the horizon, the rise and the arc between them.
    val r = 22.dp.toPx()
    val origin = Offset(size.width - r - 30.dp.toPx(), r + 14.dp.toPx())
    val rad = Math.toRadians(settings.angleDeg.toDouble())
    drawLine(ink, origin, Offset(origin.x + r, origin.y), strokeWidth = 1.dp.toPx())
    drawLine(
        accent,
        origin,
        Offset(origin.x + r * cos(rad).toFloat(), origin.y - r * sin(rad).toFloat()),
        strokeWidth = 1.5.dp.toPx(),
    )
    val arc = r * 0.6f
    drawArc(
        color = accent.copy(alpha = 0.8f),
        startAngle = -settings.angleDeg,
        sweepAngle = settings.angleDeg,
        useCenter = false,
        topLeft = Offset(origin.x - arc, origin.y - arc),
        size = Size(arc * 2, arc * 2),
        style = Stroke(width = 1.dp.toPx()),
    )
    val label = measurer.measure("${settings.angleDeg.toInt()}°", style)
    drawText(label, topLeft = Offset(origin.x + r + 3.dp.toPx(), origin.y - label.size.height / 2f))
}

/**
 * A plausible spectrum for when nothing is playing, in the analyzer's units
 * (dB around a 0 dB midband): a gentle downward tilt, three peaks drifting at
 * their own speeds, and a beat that swells them, so every setting has
 * something moving to act on.
 */
internal fun fillDemoSpectrum(out: FloatArray, t: Double) {
    val n = out.size
    if (n == 0) return
    val beat = Math.pow(0.5 + 0.5 * sin(t * 2 * Math.PI * 2.0), 6.0)
    val peaks = arrayOf(
        doubleArrayOf(0.18 + 0.06 * sin(t * 0.7), 22.0 + 10 * beat, 0.035),
        doubleArrayOf(0.45 + 0.12 * sin(t * 0.43 + 1.0), 18.0, 0.05),
        doubleArrayOf(0.72 + 0.08 * sin(t * 0.31 + 2.0), 14.0, 0.04),
    )
    for (i in 0 until n) {
        val x = i.toDouble() / (n - 1)
        var db = -4.0 - 14.0 * x + 2.5 * sin(x * 90 + t * 3) * sin(x * 37 - t)
        for (p in peaks) {
            val d = (x - p[0]) / p[2]
            db += p[1] * kotlin.math.exp(-0.5 * d * d)
        }
        out[i] = db.toFloat()
    }
}

/** Analyzer silence for this long and the preview switches to the demo. */
private const val LIVE_TIMEOUT_NANOS = 1_500_000_000L

/** One labelled slider: [onDrag] while the finger moves, [onCommit] on release. */
@Composable
private fun WaterfallSlider(
    title: String,
    subtitle: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    format: (Float) -> String,
    onDrag: (Float) -> Unit,
    onCommit: () -> Unit,
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
            value = value,
            onValueChange = onDrag,
            onValueChangeFinished = onCommit,
            valueRange = range,
        )
    }
}
