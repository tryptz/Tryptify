package tf.monochrome.android.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.audio.eq.AutoEqEngine
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FrequencyPoint
import kotlin.math.abs

private const val GRID_POINTS = 192
private val FREQ_LABELS = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)

/**
 * The EQ Studio's graph: the measurement, the target, the result of the EQ on
 * the measurement, and each filter as its own coloured curve with a handle on
 * its peak — the layout peqdb's studio made familiar, drawn from the app's own
 * filter model ([AutoEqEngine.ResponseGrid]), so what it shows is what plays.
 *
 * Touch:
 * * **Drag a handle** to move that filter: across for frequency, up and down
 *   for gain. Touching a handle also selects it.
 * * **Pinch** with two fingers to change the selected filter's Q. Fingers
 *   apart widen it.
 * * **Touch anywhere else** for a crosshair that reads the curves out at that
 *   frequency, held for as long as the finger is down.
 *
 * The result is drawn without the preamp. The preamp is a level change, not a
 * tonal one, and with it the result sat a few dB under the target everywhere,
 * which reads as a bad fit when it is a perfect one.
 */
@Composable
internal fun EqStudioGraph(
    measurement: List<FrequencyPoint>,
    target: List<FrequencyPoint>,
    bands: List<EqBand>,
    selectedId: Int,
    onSelect: (Int) -> Unit,
    onMove: (id: Int, freq: Float, gain: Float) -> Unit,
    onQ: (id: Int, q: Float) -> Unit,
    measurementColor: Color,
    targetColor: Color,
    description: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val resultColor = cs.onSurface
    val gridColor = cs.onSurface
    val labelColor = cs.onSurfaceVariant
    val panelColor = cs.surfaceContainerLow
    val textMeasurer = rememberTextMeasurer()
    val haptics = LocalHapticFeedback.current

    val freqs = remember { StudioAxis.logGrid(GRID_POINTS) }
    val grid = remember(freqs) { AutoEqEngine.ResponseGrid(freqs) }

    // The level the window is centred on: the measurement's midband when there
    // is one, the target's otherwise, and 0 dB for a bare EQ.
    val zero = remember(measurement, target) {
        when {
            measurement.isNotEmpty() -> StudioAxis.normalizationOffset(measurement)
            target.isNotEmpty() -> StudioAxis.normalizationOffset(target)
            else -> 0f
        }
    }
    val measCurve = remember(measurement, freqs) { StudioAxis.resample(measurement, freqs) }
    val targetCurve = remember(target, freqs, zero) {
        val shift = if (target.isEmpty()) 0f else zero - StudioAxis.normalizationOffset(target)
        StudioAxis.resample(target, freqs).also { for (i in it.indices) it[i] += shift }
    }
    // One response per filter, designed once per edit. A disabled filter's
    // response is all zeros, so it drops out of the sum without a special case.
    val bandCurves = remember(bands, grid) { bands.map { it to grid.response(it) } }
    val eqSum = remember(bandCurves) {
        FloatArray(freqs.size).also { out ->
            for ((_, r) in bandCurves) for (i in out.indices) out[i] += r[i]
        }
    }
    val resultCurve = remember(measCurve, eqSum, zero) {
        FloatArray(freqs.size) { i -> (if (measCurve.isEmpty()) zero else measCurve[i]) + eqSum[i] }
    }

    // Read through updated state so the gesture coroutine, started once, sees
    // every edit; restarting it on each change would drop a drag mid-gesture.
    val latestBands by rememberUpdatedState(bands)
    val latestSelected by rememberUpdatedState(selectedId)
    val latestZero by rememberUpdatedState(zero)
    val latestOnSelect by rememberUpdatedState(onSelect)
    val latestOnMove by rememberUpdatedState(onMove)
    val latestOnQ by rememberUpdatedState(onQ)
    val latestMeas by rememberUpdatedState(measCurve)
    val latestTarget by rememberUpdatedState(targetCurve)
    val latestResult by rememberUpdatedState(resultCurve)

    var inspectX by remember { mutableStateOf<Float?>(null) }

    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                val grabRadius = 28.dp.toPx()
                awaitEachGesture {
                    // Measured per gesture: the graph changes size on rotation
                    // and when the window is resized, and this block outlives that.
                    val plot = PlotBox(size.width.toFloat(), size.height.toFloat(), density)
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val handles = latestBands.map { b ->
                        Triple(b.id, plot.x(b.freq), plot.y(latestZero + b.gain, latestZero))
                    }
                    var dragging = StudioBands.nearestHandle(handles, down.position.x, down.position.y, grabRadius)
                    // Where on the handle the finger landed, kept for the whole
                    // drag: without it the handle jumped to centre under the
                    // finger on the first move.
                    var grab = Offset.Zero
                    if (dragging != null) {
                        handles.first { it.first == dragging }.let { (_, hx, hy) ->
                            grab = Offset(hx - down.position.x, hy - down.position.y)
                        }
                        latestOnSelect(dragging)
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        down.consume()
                    } else {
                        inspectX = down.position.x
                    }
                    var pinchStartSpan = 0f
                    var pinchStartQ = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (pressed.size >= 2) {
                            // Two fingers: Q of the selected filter, whatever
                            // the first finger was doing.
                            inspectX = null
                            dragging = null
                            val span = abs(pressed[0].position.x - pressed[1].position.x)
                                .coerceAtLeast(24.dp.toPx())
                            val band = latestBands.firstOrNull { it.id == latestSelected }
                            if (band != null) {
                                if (pinchStartSpan == 0f) {
                                    pinchStartSpan = span
                                    pinchStartQ = band.q
                                }
                                latestOnQ(band.id, StudioBands.pinchQ(pinchStartQ, pinchStartSpan, span))
                            }
                            pressed.forEach { it.consume() }
                            continue
                        }
                        val change = pressed[0]
                        val id = dragging
                        if (id != null) {
                            if (change.positionChange() != Offset.Zero) {
                                val at = change.position + grab
                                latestOnMove(
                                    id,
                                    StudioBands.roundFreq(plot.freq(at.x)),
                                    StudioBands.clampGain(plot.gain(at.y, latestZero) - latestZero),
                                )
                            }
                            change.consume()
                        } else if (pinchStartSpan == 0f) {
                            inspectX = change.position.x
                        }
                    }
                    inspectX = null
                }
            },
    ) {
        val plot = PlotBox(size.width, size.height, density)
        drawRoundRect(panelColor, cornerRadius = CornerRadius(20.dp.toPx()))
        drawGrid(plot, zero, gridColor, labelColor, textMeasurer)

        val selected = bands.firstOrNull { it.id == selectedId }

        // The filters, quietly, behind everything else. The selected one is
        // drawn last and filled, so it reads as the one being worked on.
        for ((band, response) in bandCurves) {
            if (!band.enabled || band.id == selectedId) continue
            drawCurve(plot, freqs, response, zero, zero, studioFilterColor(band.id).copy(alpha = 0.45f), 1.5.dp.toPx())
        }
        if (selected != null && selected.enabled) {
            val response = bandCurves.firstOrNull { it.first.id == selected.id }?.second
            if (response != null) {
                val color = studioFilterColor(selected.id)
                drawFill(plot, freqs, response, zero, color.copy(alpha = 0.14f))
                drawCurve(plot, freqs, response, zero, zero, color.copy(alpha = 0.9f), 2.dp.toPx())
            }
        }

        if (targetCurve.isNotEmpty()) {
            drawCurve(plot, freqs, targetCurve, 0f, zero, targetColor, 2.5.dp.toPx())
        }
        if (measCurve.isNotEmpty()) {
            drawCurve(plot, freqs, measCurve, 0f, zero, measurementColor.copy(alpha = 0.85f), 2.dp.toPx())
        }
        if (measCurve.isNotEmpty() || bands.isNotEmpty()) {
            drawCurve(plot, freqs, resultCurve, 0f, zero, resultColor, 2.5.dp.toPx())
        }

        // Handles on top of every curve, so one is never hidden by a line.
        val ring = 2.dp.toPx()
        for (band in bands) {
            val c = Offset(plot.x(band.freq), plot.y(zero + band.gain, zero))
            val color = studioFilterColor(band.id)
            val isSelected = band.id == selectedId
            val r = if (isSelected) 9.dp.toPx() else 6.5.dp.toPx()
            if (isSelected) drawCircle(color.copy(alpha = 0.25f), radius = r * 2f, center = c)
            drawCircle(panelColor, radius = r + ring, center = c)
            if (band.enabled) {
                drawCircle(color, radius = r, center = c)
            } else {
                drawCircle(color, radius = r - ring / 2f, center = c, style = Stroke(ring))
            }
        }

        inspectX?.let { x -> drawInspector(plot, x, freqs, measCurve, targetCurve, resultCurve, zero, labelColor, measurementColor, targetColor, resultColor, panelColor, textMeasurer) }
    }
}

/** The plot area inside the canvas: room on the left for dB, below for Hz. */
private class PlotBox(width: Float, height: Float, density: Float) {
    val left = 30f * density
    val right = width - 8f * density
    val top = 10f * density
    val bottom = height - 20f * density
    val w = (right - left).coerceAtLeast(1f)
    val h = (bottom - top).coerceAtLeast(1f)

    fun x(freq: Float): Float = left + StudioAxis.fractionOf(freq) * w
    fun freq(x: Float): Float = StudioAxis.freqAt((x - left) / w)

    fun y(gain: Float, zero: Float): Float {
        val min = zero - StudioAxis.DB_RANGE / 2f
        return bottom - ((gain - min) / StudioAxis.DB_RANGE) * h
    }

    fun gain(y: Float, zero: Float): Float {
        val min = zero - StudioAxis.DB_RANGE / 2f
        return min + ((bottom - y) / h) * StudioAxis.DB_RANGE
    }
}

private fun DrawScope.drawGrid(
    plot: PlotBox,
    zero: Float,
    lineColor: Color,
    labelColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val minor = lineColor.copy(alpha = 0.06f)
    val major = lineColor.copy(alpha = 0.14f)
    val hair = 1.dp.toPx()
    // Every 1-9 of each decade, the decades stronger.
    var decade = 10f
    while (decade < StudioAxis.MAX_FREQ) {
        for (m in 1..9) {
            val f = decade * m
            if (f < StudioAxis.MIN_FREQ || f > StudioAxis.MAX_FREQ) continue
            val x = plot.x(f)
            drawLine(if (m == 1) major else minor, Offset(x, plot.top), Offset(x, plot.bottom), hair)
        }
        decade *= 10f
    }
    val labelStyle = TextStyle(color = labelColor.copy(alpha = 0.75f), fontSize = 9.sp)
    for (f in FREQ_LABELS) {
        val text = if (f >= 1000f) "${(f / 1000f).toInt()}k" else f.toInt().toString()
        val layout = textMeasurer.measure(text, labelStyle)
        val x = (plot.x(f) - layout.size.width / 2f).coerceIn(plot.left, plot.right - layout.size.width)
        drawText(layout, topLeft = Offset(x, plot.bottom + 4.dp.toPx()))
    }
    // dB lines every 5, labelled every 10, on whole numbers of the real level.
    val min = zero - StudioAxis.DB_RANGE / 2f
    val max = zero + StudioAxis.DB_RANGE / 2f
    var db = kotlin.math.ceil(min / 5f) * 5f
    while (db <= max) {
        val y = plot.y(db, zero)
        val labelled = (db.toInt() % 10 == 0)
        drawLine(if (labelled) major else minor, Offset(plot.left, y), Offset(plot.right, y), hair)
        if (labelled) {
            val layout = textMeasurer.measure(db.toInt().toString(), labelStyle)
            drawText(layout, topLeft = Offset(plot.left - layout.size.width - 4.dp.toPx(), y - layout.size.height / 2f))
        }
        db += 5f
    }
}

/**
 * A curve across the plot. Filter curves are relative (0 dB is no change), so
 * they are lifted onto the window's centre by [lift] = [zero]; absolute curves
 * (measurement, target, result) pass a [lift] of 0.
 */
private fun DrawScope.drawCurve(
    plot: PlotBox,
    freqs: FloatArray,
    values: FloatArray,
    lift: Float,
    zero: Float,
    color: Color,
    width: Float,
) {
    val path = Path()
    var started = false
    for (i in freqs.indices) {
        val v = values[i] + lift
        if (!v.isFinite()) continue
        // Held a little past the plot so a curve leaving the window exits
        // through its edge rather than stopping at a far-off point.
        val y = plot.y(v, zero).coerceIn(plot.top - 40f, plot.bottom + 40f)
        if (!started) path.moveTo(plot.x(freqs[i]), y) else path.lineTo(plot.x(freqs[i]), y)
        started = true
    }
    clipPlot(plot) {
        drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun DrawScope.drawFill(plot: PlotBox, freqs: FloatArray, values: FloatArray, zero: Float, color: Color) {
    val path = Path()
    val baseY = plot.y(zero, zero)
    path.moveTo(plot.x(freqs.first()), baseY)
    for (i in freqs.indices) path.lineTo(plot.x(freqs[i]), plot.y(zero + values[i], zero))
    path.lineTo(plot.x(freqs.last()), baseY)
    path.close()
    clipPlot(plot) { drawPath(path, color) }
}

private inline fun DrawScope.clipPlot(plot: PlotBox, block: DrawScope.() -> Unit) {
    drawContext.canvas.save()
    drawContext.canvas.clipRect(plot.left, plot.top, plot.right, plot.bottom)
    block()
    drawContext.canvas.restore()
}

private fun DrawScope.drawInspector(
    plot: PlotBox,
    rawX: Float,
    freqs: FloatArray,
    meas: FloatArray,
    target: FloatArray,
    result: FloatArray,
    zero: Float,
    labelColor: Color,
    measurementColor: Color,
    targetColor: Color,
    resultColor: Color,
    panelColor: Color,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
) {
    val x = rawX.coerceIn(plot.left, plot.right)
    val freq = plot.freq(x)
    val i = kotlin.math.round(StudioAxis.fractionOf(freq) * (freqs.size - 1)).toInt().coerceIn(0, freqs.size - 1)
    drawLine(labelColor.copy(alpha = 0.6f), Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())

    fun level(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
    val lines = buildList {
        add(formatStudioFreq(freq) to labelColor)
        if (meas.isNotEmpty()) add(level(meas[i]) to measurementColor)
        if (target.isNotEmpty()) add(level(target[i]) to targetColor)
        add(level(result[i]) to resultColor)
        if (target.isNotEmpty()) add("Δ ${level(result[i] - target[i])}" to labelColor)
    }
    val style = TextStyle(fontSize = 10.sp)
    val layouts = lines.map { (text, color) -> textMeasurer.measure(text, style.copy(color = color)) }
    val pad = 6.dp.toPx()
    val boxW = layouts.maxOf { it.size.width } + pad * 2
    val boxH = layouts.sumOf { it.size.height } + pad * 2
    val left = if (x + 8.dp.toPx() + boxW < plot.right) x + 8.dp.toPx() else x - 8.dp.toPx() - boxW
    drawRoundRect(
        panelColor.copy(alpha = 0.92f),
        topLeft = Offset(left, plot.top + 4.dp.toPx()),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(8.dp.toPx()),
    )
    var y = plot.top + 4.dp.toPx() + pad
    for (layout in layouts) {
        drawText(layout, topLeft = Offset(left + pad, y))
        y += layout.size.height
    }
    // A dot where each curve crosses, so the numbers have a place on the graph.
    if (meas.isNotEmpty()) drawCircle(measurementColor, 3.dp.toPx(), Offset(x, plot.y(meas[i], zero)))
    if (target.isNotEmpty()) drawCircle(targetColor, 3.dp.toPx(), Offset(x, plot.y(target[i], zero)))
    drawCircle(resultColor, 3.dp.toPx(), Offset(x, plot.y(result[i], zero)))
}
