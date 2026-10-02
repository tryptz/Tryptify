package tf.monochrome.android.ui.settings

import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import tf.monochrome.android.domain.model.SpectrumAnalysisType
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
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

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

    SettingsGroupHeader(stringResource(R.string.settings_spectrum_waterfall))
    Text(
        stringResource(R.string.waterfall_the_spectrum_on_the_album_art_the_bright_line_at),
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
        stringResource(R.string.waterfall_style),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.settingsAnchor("Waterfall style").padding(top = 8.dp, bottom = 6.dp),
    )
    // Two rows of three: six chips in one row are too narrow for "Ridgeline"
    // on a phone.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WaterfallStyle.entries.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { style ->
                    tf.monochrome.android.ui.mixer.GlassChoiceChip(
                        label = stringResource(waterfallStyleLabel(style)),
                        selected = draft.style == style,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { onChange(draft.copy(style = style)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    Text(
        stringResource(waterfallStyleDescription(draft.style)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )

    WaterfallAnalysisControls(
        draft = draft,
        onDraft = { draft = it },
        onChange = onChange,
    )

    // Depth, fade, angle and weight shape the history; a single-line style
    // has none, so they would be sliders that change nothing.
    if (!draft.style.singleLine) {
        WaterfallSlider(
            title = stringResource(R.string.waterfall_depth),
            subtitle = stringResource(R.string.waterfall_how_long_a_line_takes_to_travel_back_and),
            value = draft.depthSeconds,
            range = SpectrumWaterfallSettings.MIN_DEPTH_SECONDS..SpectrumWaterfallSettings.MAX_DEPTH_SECONDS,
            format = { String.format(Locale.US, "%.1f s", it) },
            onDrag = { draft = draft.copy(depthSeconds = it) },
            onCommit = { onChange(draft) },
        )
        WaterfallSlider(
            title = stringResource(R.string.waterfall_fade_start),
            subtitle = stringResource(R.string.waterfall_how_far_back_a_line_stays_at_full_strength),
            value = draft.fadeStart,
            range = 0f..SpectrumWaterfallSettings.MAX_FADE_START,
            format = {
                String.format(Locale.US, "%d%% · %.1f s", (it * 100).toInt(), it * draft.depthSeconds)
            },
            onDrag = { draft = draft.copy(fadeStart = it) },
            onCommit = { onChange(draft) },
        )
        WaterfallSlider(
            title = stringResource(R.string.waterfall_angle),
            subtitle = stringResource(R.string.waterfall_low_a_near_flat_horizon_high_looking_down_on_the),
            value = draft.angleDeg,
            range = SpectrumWaterfallSettings.MIN_ANGLE_DEG..SpectrumWaterfallSettings.MAX_ANGLE_DEG,
            format = { "${it.toInt()}°" },
            onDrag = { draft = draft.copy(angleDeg = it) },
            onCommit = { onChange(draft) },
        )
        WaterfallSlider(
            title = stringResource(R.string.waterfall_line_weight),
            subtitle = stringResource(R.string.waterfall_thick_bands_or_fine_hairlines_like_a_radio_sweep),
            value = draft.lineWidthDp,
            range = SpectrumWaterfallSettings.MIN_LINE_WIDTH_DP..SpectrumWaterfallSettings.MAX_LINE_WIDTH_DP,
            format = { String.format(Locale.US, "%.1f dp", it) },
            onDrag = { draft = draft.copy(lineWidthDp = it) },
            onCommit = { onChange(draft) },
        )
    }

    Text(
        stringResource(R.string.waterfall_frame_rate),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.settingsAnchor("Waterfall frame rate").padding(top = 8.dp, bottom = 2.dp),
    )
    Text(
        stringResource(R.string.waterfall_how_often_the_waterfall_is_drawn_lower_saves_gpu),
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
                label = if (fps == SpectrumWaterfallSettings.FPS_DISPLAY) stringResource(R.string.waterfall_max) else "$fps",
                selected = draft.targetFps == fps,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onChange(draft.copy(targetFps = fps)) },
                modifier = Modifier.width(if (fps == SpectrumWaterfallSettings.FPS_DISPLAY) 64.dp else 52.dp),
                description = if (fps == SpectrumWaterfallSettings.FPS_DISPLAY) {
                    stringResource(R.string.waterfall_every_refresh_desc)
                } else {
                    stringResource(R.string.waterfall_rate_clock, fps)
                },
            )
        }
    }
    SettingSwitchItem(
        title = stringResource(R.string.waterfall_vsync),
        subtitle = if (draft.vsync) {
            stringResource(R.string.waterfall_vsync_on)
        } else {
            stringResource(R.string.waterfall_vsync_off)
        },
        checked = draft.vsync,
        onCheckedChange = { onChange(draft.copy(vsync = it)) },
    )

    if (settings != SpectrumWaterfallSettings.DEFAULT) {
        TextButton(onClick = { onChange(SpectrumWaterfallSettings.DEFAULT) }) {
            Text(stringResource(R.string.settings_reset_to_default))
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
        // Worded here, in composition, where the reader's language is known;
        // the canvas only measures and draws them.
        val guideLabels = GuideLabels(
            now = stringResource(R.string.waterfall_guide_now),
            fadeBegins = stringResource(R.string.waterfall_guide_fade, settings.solidSeconds),
            gone = stringResource(R.string.waterfall_guide_gone, settings.depthSeconds),
        )
        Canvas(Modifier.fillMaxSize()) {
            WaterfallNative.nativeGuides(size.width, size.height, settings.fadeStart, settings.angleDeg, guides)
            drawGuides(textMeasurer, settings, guides, accent, guideLabels)
        }
        Text(
            stringResource(R.string.waterfall_angle_depth_pinch_weight_drag_the_fade_line),
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
            if (showingLive) stringResource(R.string.waterfall_live) else stringResource(R.string.waterfall_demo),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (showingLive) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(
                R.string.waterfall_summary,
                stringResource(waterfallStyleLabel(settings.style)),
                settings.lineWidthDp,
                when {
                    settings.targetFps == SpectrumWaterfallSettings.FPS_DISPLAY -> stringResource(R.string.waterfall_rate_every_refresh)
                    settings.vsync -> stringResource(R.string.waterfall_rate_vsync, settings.targetFps)
                    else -> stringResource(R.string.waterfall_rate_clock, settings.targetFps)
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
    labels: GuideLabels,
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

    guide(guides[0], labels.now, accent.copy(alpha = 0.8f))
    if (settings.fadeStart > 0.02f) {
        guide(guides[1], labels.fadeBegins, ink)
    }
    guide(guides[2], labels.gone, ink)

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
        stringResource(R.string.waterfall_preview_paused_while_the_other_one_is_on_screen),
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

/** The canvas guides' words, resolved in composition and handed to the draw pass. */
private class GuideLabels(val now: String, val fadeBegins: String, val gone: String)

/** A waterfall style's chip text in the reader's language. */
@androidx.annotation.StringRes
private fun waterfallStyleLabel(style: WaterfallStyle): Int = when (style) {
    WaterfallStyle.LINES -> R.string.waterfall_style_lines
    WaterfallStyle.RIDGELINE -> R.string.waterfall_style_ridgeline
    WaterfallStyle.HEAT -> R.string.waterfall_style_heat
    WaterfallStyle.NEON -> R.string.waterfall_style_neon
    WaterfallStyle.GLASS -> R.string.waterfall_style_glass
    WaterfallStyle.LEGACY -> R.string.waterfall_style_legacy
}

/** What a waterfall style looks like, in the reader's language. */
@androidx.annotation.StringRes
private fun waterfallStyleDescription(style: WaterfallStyle): Int = when (style) {
    WaterfallStyle.LINES -> R.string.waterfall_style_lines_desc
    WaterfallStyle.RIDGELINE -> R.string.waterfall_style_ridgeline_desc
    WaterfallStyle.HEAT -> R.string.waterfall_style_heat_desc
    WaterfallStyle.NEON -> R.string.waterfall_style_neon_desc
    WaterfallStyle.GLASS -> R.string.waterfall_style_glass_desc
    WaterfallStyle.LEGACY -> R.string.waterfall_style_legacy_desc
}

/**
 * The analysis behind the lines, laid out like a spectrum analyzer's own
 * panel (Voxengo SPAN's "Spectrum Mode Editor"): the type as chips, overlap
 * and averaging time as knobs, and the line colour.
 *
 * The knobs report every step of a drag. Writing the settings store that often
 * would stutter the drag, so a turn moves [draft] (the preview follows it
 * live) and is saved once the knob has been still for a moment.
 */
@Composable
private fun WaterfallAnalysisControls(
    draft: SpectrumWaterfallSettings,
    onDraft: (SpectrumWaterfallSettings) -> Unit,
    onChange: (SpectrumWaterfallSettings) -> Unit,
) {
    var turning by remember { mutableStateOf<SpectrumWaterfallSettings?>(null) }
    androidx.compose.runtime.LaunchedEffect(turning) {
        val pending = turning ?: return@LaunchedEffect
        kotlinx.coroutines.delay(KNOB_SAVE_DELAY_MS)
        onChange(pending)
        turning = null
    }
    val turn = { next: SpectrumWaterfallSettings ->
        onDraft(next)
        turning = next
    }

    Text(
        stringResource(R.string.waterfall_analysis),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
    // Two by two: four chips across are too narrow for the longer languages.
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SpectrumAnalysisType.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { type ->
                    tf.monochrome.android.ui.mixer.GlassChoiceChip(
                        label = stringResource(analysisTypeLabel(type)),
                        selected = draft.analysis == type,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { onChange(draft.copy(analysis = type)) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
    Text(
        stringResource(analysisTypeDescription(draft.analysis)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    // The averaging time drives the two real-time types only: a long-term
    // average or max has no time constant, so the knob is shown, dimmed, and
    // says so rather than turning to no effect.
    val timed = draft.analysis == SpectrumAnalysisType.RT_AVG || draft.analysis == SpectrumAnalysisType.RT_MAX
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tf.monochrome.android.ui.mixer.FLKnobControl(
            label = stringResource(R.string.waterfall_overlap),
            value = draft.overlapPct,
            min = SpectrumWaterfallSettings.MIN_OVERLAP_PCT,
            max = SpectrumWaterfallSettings.MAX_OVERLAP_PCT,
            unit = "%",
            color = tf.monochrome.android.ui.mixer.FLPluginColors.knobOrange,
            onValueChange = { turn(draft.copy(overlapPct = it)) },
            default = SpectrumWaterfallSettings.DEFAULT_OVERLAP_PCT,
        )
        Box(Modifier.alpha(if (timed) 1f else 0.38f)) {
            tf.monochrome.android.ui.mixer.FLKnobControl(
                label = stringResource(
                    if (draft.analysis == SpectrumAnalysisType.RT_MAX) R.string.waterfall_hold_time
                    else R.string.waterfall_avg_time,
                ),
                value = draft.avgTimeMs,
                min = SpectrumWaterfallSettings.MIN_AVG_TIME_MS,
                max = SpectrumWaterfallSettings.MAX_AVG_TIME_MS,
                unit = "ms",
                color = tf.monochrome.android.ui.mixer.FLPluginColors.knobPink,
                onValueChange = { turn(draft.copy(avgTimeMs = it)) },
                default = SpectrumWaterfallSettings.DEFAULT_AVG_TIME_MS,
            )
        }
    }
    Text(
        stringResource(
            if (timed) R.string.waterfall_knobs_hint else R.string.waterfall_avg_time_unused,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
    )

    // Line colour: the album's, or one picked here.
    var picking by remember { mutableStateOf(false) }
    val accent = MaterialTheme.colorScheme.primary
    Text(
        stringResource(R.string.waterfall_line_colour),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tf.monochrome.android.ui.mixer.GlassChoiceChip(
            label = stringResource(R.string.waterfall_album_colour),
            selected = draft.colorArgb == null,
            accent = accent,
            onClick = { onChange(draft.copy(colorArgb = null)) },
            modifier = Modifier.weight(1f),
        )
        tf.monochrome.android.ui.mixer.GlassChoiceChip(
            label = stringResource(R.string.waterfall_custom_colour),
            selected = draft.colorArgb != null,
            accent = draft.colorArgb?.let { Color(it) } ?: accent,
            onClick = { picking = true },
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(draft.colorArgb?.let { Color(it) } ?: accent)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable { picking = true },
        )
    }
    Text(
        stringResource(R.string.waterfall_colour_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
    if (picking) {
        tf.monochrome.android.ui.components.ColorPickerDialog(
            initial = draft.colorArgb?.let { Color(it) } ?: accent,
            title = stringResource(R.string.waterfall_line_colour),
            onDismiss = { picking = false },
            onConfirm = {
                picking = false
                onChange(draft.copy(colorArgb = it.toArgb()))
            },
        )
    }
}

/** How long a knob has to rest before its value is saved. */
private const val KNOB_SAVE_DELAY_MS = 350L

@androidx.annotation.StringRes
private fun analysisTypeLabel(type: SpectrumAnalysisType): Int = when (type) {
    SpectrumAnalysisType.RT_AVG -> R.string.waterfall_type_rt_avg
    SpectrumAnalysisType.RT_MAX -> R.string.waterfall_type_rt_max
    SpectrumAnalysisType.AVG -> R.string.waterfall_type_avg
    SpectrumAnalysisType.MAX -> R.string.waterfall_type_max
}

@androidx.annotation.StringRes
private fun analysisTypeDescription(type: SpectrumAnalysisType): Int = when (type) {
    SpectrumAnalysisType.RT_AVG -> R.string.waterfall_type_rt_avg_desc
    SpectrumAnalysisType.RT_MAX -> R.string.waterfall_type_rt_max_desc
    SpectrumAnalysisType.AVG -> R.string.waterfall_type_avg_desc
    SpectrumAnalysisType.MAX -> R.string.waterfall_type_max_desc
}
