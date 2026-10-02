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
import kotlin.math.abs
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture

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

    WaterfallPreview(
        settings = preview,
        liveBins = liveBins,
        // The preview's own gestures move the draft like a slider does, and
        // save once on release.
        onAdjust = { draft = it },
        onAdjustDone = { onChange(it) },
    )

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

    Text(
        "Frame rate",
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.settingsAnchor("Waterfall frame rate").padding(top = 8.dp, bottom = 2.dp),
    )
    Text(
        "How often the waterfall is drawn. Lower saves GPU and battery; Max is every refresh " +
            "the display gives.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp),
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        (listOf(SpectrumWaterfallSettings.FPS_DISPLAY) + SpectrumWaterfallSettings.FPS_CHOICES).forEach { fps ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = if (fps == SpectrumWaterfallSettings.FPS_DISPLAY) "Max" else "$fps",
                selected = draft.targetFps == fps,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onChange(draft.copy(targetFps = fps)) },
                modifier = Modifier.width(if (fps == SpectrumWaterfallSettings.FPS_DISPLAY) 64.dp else 52.dp),
                description = if (fps == SpectrumWaterfallSettings.FPS_DISPLAY) {
                    "Every display refresh"
                } else {
                    "$fps frames a second"
                },
            )
        }
    }
    SettingSwitchItem(
        title = "Vsync",
        subtitle = if (draft.vsync) {
            "Snap the frame rate to an even step of the display's refresh: smooth, evenly spaced frames"
        } else {
            "Hold the exact frame rate by the clock: the number you picked, spaced slightly unevenly"
        },
        checked = draft.vsync,
        onCheckedChange = { onChange(draft.copy(vsync = it)) },
    )

    if (settings != SpectrumWaterfallSettings.DEFAULT) {
        TextButton(onClick = { onChange(SpectrumWaterfallSettings.DEFAULT) }) {
            Text("Reset to default")
        }
    }
}

@Composable
private fun WaterfallPreview(
    settings: SpectrumWaterfallSettings,
    liveBins: (() -> FloatArray)?,
    onAdjust: (SpectrumWaterfallSettings) -> Unit,
    onAdjustDone: (SpectrumWaterfallSettings) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    val live by rememberUpdatedState(liveBins)
    // The gesture loop below is started once and outlives recompositions, so
    // it reads these through State rather than capturing their first values.
    val current by rememberUpdatedState(settings)
    val adjust by rememberUpdatedState(onAdjust)
    val adjustDone by rememberUpdatedState(onAdjustDone)
    val (running, reportVisibility) = previewGate("waterfall")

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
            .then(reportVisibility)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF07090D))
            .pointerInput(Unit) {
                // Touch control. One finger: up and down tilt the angle, left
                // and right set the depth. Two: pinch for line weight. A
                // finger that lands on the dashed fade line drags that line.
                // Moves are consumed, so the page does not scroll under a
                // drag that started here.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val grab = 28.dp.toPx()
                    val onFadeLine = abs(down.position.y - guides[1]) < grab
                    var s = current
                    var changed = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        val next = when {
                            pressed.size >= 2 -> {
                                val zoom = event.calculateZoom()
                                s.copy(
                                    lineWidthDp = (s.lineWidthDp * zoom).coerceIn(
                                        SpectrumWaterfallSettings.MIN_LINE_WIDTH_DP,
                                        SpectrumWaterfallSettings.MAX_LINE_WIDTH_DP,
                                    ),
                                )
                            }
                            onFadeLine -> s.copy(
                                fadeStart = WaterfallNative.nativeDepthAt(
                                    pressed[0].position.y, size.width.toFloat(), size.height.toFloat(), s.angleDeg,
                                ).coerceIn(0f, SpectrumWaterfallSettings.MAX_FADE_START),
                            )
                            else -> {
                                val pan = event.calculatePan()
                                s.copy(
                                    // A full-height drag sweeps most of the
                                    // angle range; a full-width one, most of
                                    // the depth.
                                    angleDeg = (s.angleDeg - pan.y / size.height * 90f).coerceIn(
                                        SpectrumWaterfallSettings.MIN_ANGLE_DEG,
                                        SpectrumWaterfallSettings.MAX_ANGLE_DEG,
                                    ),
                                    depthSeconds = (s.depthSeconds + pan.x / size.width * 6f).coerceIn(
                                        SpectrumWaterfallSettings.MIN_DEPTH_SECONDS,
                                        SpectrumWaterfallSettings.MAX_DEPTH_SECONDS,
                                    ),
                                )
                            }
                        }
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
            SpectrumOverlay(
                bins = provider,
                color = accent,
                modifier = Modifier.fillMaxSize(),
                height = 220.dp,
                waterfall = settings,
            )
        } else {
            PreviewPaused(Modifier.align(Alignment.Center))
        }
        // The annotations, from the same projection the lines are drawn with.
        Canvas(Modifier.fillMaxSize()) {
            WaterfallNative.nativeGuides(size.width, size.height, settings.fadeStart, settings.angleDeg, guides)
            drawGuides(textMeasurer, settings, guides, accent)
        }
        Text(
            "↕ angle · ↔ depth · pinch weight · drag the fade line",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
        )
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
                "%s, %.1f dp, %s. %d lines, one every %d ms. Full strength for %.1f s, then " +
                    "fading over %.1f s; gone %.1f s after it was heard. Rising at %d°.",
                settings.style.label,
                settings.lineWidthDp,
                when {
                    settings.targetFps == SpectrumWaterfallSettings.FPS_DISPLAY -> "every refresh"
                    settings.vsync -> "${settings.targetFps} fps, vsync"
                    else -> "${settings.targetFps} fps by the clock"
                },
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

/** What a preview shows while the other one on the screen is the one running. */
@Composable
internal fun PreviewPaused(modifier: Modifier = Modifier) {
    Text(
        "Preview paused while the other one is on screen",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = modifier.padding(16.dp),
    )
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
