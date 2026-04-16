package tf.monochrome.android.ui.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.FrequencyPoint
import tf.monochrome.android.audio.eq.AutoEqEngine
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

private const val MIN_FREQ = 20f
private const val MAX_FREQ = 20000f
private const val DB_RANGE = 40f // Fixed dB range matching SeapEngine reference
private const val GRAPH_PADDING_LEFT = 0f
private const val GRAPH_PADDING_RIGHT = 40f
private const val GRAPH_PADDING_TOP = 12f
private const val GRAPH_PADDING_BOTTOM = 20f

/**
 * Interactive frequency response graph matching SeapEngine's visual style.
 *
 * Uses normalization-based centering (250Hz-2500Hz average) and a fixed
 * dB range for consistent proportions that match the reference implementation.
 *
 * Shows three curves:
 * - Original measurement (primary color, semi-transparent)
 * - Target curve (primary color, dashed)
 * - Corrected curve (white, solid) with draggable EQ band dots
 */
@Composable
fun FrequencyResponseGraph(
    originalCurve: List<FrequencyPoint>,
    targetCurve: List<FrequencyPoint>,
    eqBands: List<EqBand>,
    modifier: Modifier = Modifier,
    preamp: Float = 0f,
    sampleRate: Float = 48000f,
    onBandDragged: ((bandId: Int, newFreq: Float, newGain: Float) -> Unit)? = null,
    spectrumBins: FloatArray = FloatArray(0),
    spectrumColor: Color? = null,
    centerOnZero: Boolean = false,
    showLegend: Boolean = true,
    // Absolute cap used when mapping a drag's Y-pixel back to a gain value.
    // Defaults to the AutoEQ cap; Parametric EQ callers pass EqLimits.PARAMETRIC_MAX_BAND_DB.
    maxAbsDragGain: Float = EqLimits.AUTOEQ_MAX_BAND_DB,
) {
    val primary = MaterialTheme.colorScheme.primary

    // Calculate normalization offset (average gain in 250-2500Hz midband)
    // This centers the graph around the measurement's midband level, matching SeapEngine.
    // For the Parametric EQ editor (no measurement data), centerOnZero forces 0 dB center.
    val zeroOffset = remember(originalCurve, targetCurve, centerOnZero) {
        when {
            centerOnZero -> 0f
            originalCurve.isNotEmpty() -> getNormalizationOffset(originalCurve)
            targetCurve.isNotEmpty() -> getNormalizationOffset(targetCurve)
            else -> 75f // Reasonable default for SPL data
        }
    }

    // Fixed dB range centered on the normalization point
    val minGain = zeroOffset - (DB_RANGE / 2f)
    val maxGain = zeroOffset + (DB_RANGE / 2f)

    // Normalize target to measurement's midband level
    val targetNormOffset = remember(originalCurve, targetCurve) {
        if (originalCurve.isNotEmpty() && targetCurve.isNotEmpty()) {
            val measNorm = getNormalizationOffset(originalCurve)
            val targetNorm = getNormalizationOffset(targetCurve)
            measNorm - targetNorm
        } else 0f
    }

    val normalizedTarget = remember(targetCurve, targetNormOffset) {
        if (targetNormOffset != 0f) {
            targetCurve.map { FrequencyPoint(it.freq, it.gain + targetNormOffset) }
        } else targetCurve
    }

    // Calculate corrected curve.
    //  - With measurement: measurement + EQ bands + preamp
    //  - Without measurement (parametric EQ mode): pure EQ response + preamp around the zero baseline
    val correctedCurve = remember(originalCurve, eqBands, preamp, sampleRate, zeroOffset) {
        if (originalCurve.isNotEmpty()) {
            originalCurve.map { point ->
                var correctedGain = point.gain + preamp
                eqBands.forEach { band ->
                    if (band.enabled) {
                        correctedGain += AutoEqEngine.calculateBiquadResponse(point.freq, band, sampleRate)
                    }
                }
                FrequencyPoint(point.freq, correctedGain)
            }.filter { it.gain.isFinite() }
        } else {
            // Synthesize a smooth EQ response curve across the log-frequency axis
            val samples = 256
            val logMin = log10(MIN_FREQ)
            val logMax = log10(MAX_FREQ)
            (0 until samples).map { i ->
                val freq = 10f.pow(logMin + i.toFloat() / (samples - 1) * (logMax - logMin))
                var g = preamp + zeroOffset
                eqBands.forEach { band ->
                    if (band.enabled) g += AutoEqEngine.calculateBiquadResponse(freq, band, sampleRate)
                }
                FrequencyPoint(freq, g)
            }.filter { it.gain.isFinite() }
        }
    }

    var selectedBandId by remember { mutableIntStateOf(-1) }
    var isDragging by remember { mutableStateOf(false) }

    val graphBackground = MaterialTheme.colorScheme.surface
    val legendBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    val legendLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(graphBackground)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
                .pointerInput(eqBands, minGain, maxGain) {
                    if (onBandDragged == null) return@pointerInput
                    detectTapGestures { offset ->
                        val tapped = findNearestBand(
                            offset, eqBands, size.width.toFloat(), size.height.toFloat(),
                            minGain, maxGain, zeroOffset
                        )
                        selectedBandId = if (tapped == selectedBandId) -1 else tapped
                    }
                }
                .pointerInput(eqBands, minGain, maxGain, selectedBandId) {
                    if (onBandDragged == null) return@pointerInput
                    detectDragGestures(
                        onDragStart = { offset ->
                            // If a band is already selected, drag that one
                            // Otherwise try to grab nearest band directly
                            if (selectedBandId < 0) {
                                selectedBandId = findNearestBand(
                                    offset, eqBands, size.width.toFloat(), size.height.toFloat(),
                                    minGain, maxGain, zeroOffset
                                )
                            }
                            isDragging = selectedBandId >= 0
                        },
                        onDrag = { change, _ ->
                            if (selectedBandId >= 0 && isDragging) {
                                change.consume()
                                val freq = xToFreq(change.position.x, size.width.toFloat())
                                val gain = yToGain(
                                    change.position.y, size.height.toFloat(),
                                    minGain, maxGain, maxAbsDragGain
                                )
                                onBandDragged(selectedBandId, freq, gain)
                            }
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false }
                    )
                }
        ) {
            val w = size.width
            val h = size.height

            // Grid
            drawGrid(w, h, minGain, maxGain, zeroOffset)

            // dB labels on right
            drawDbLabels(w, h, minGain, maxGain)

            // Frequency labels at bottom
            drawFreqLabels(w, h)

            // FFT spectrum behind curves — uses the active theme's primary color,
            // modulated per-bin by the EQ response (bright on boost, shadow on cut).
            if (spectrumBins.isNotEmpty() && spectrumColor != null) {
                drawSpectrum(
                    bins = spectrumBins,
                    color = spectrumColor,
                    width = w,
                    height = h,
                    minGain = minGain,
                    maxGain = maxGain,
                    zeroOffset = zeroOffset
                )
            }

            // Original measurement curve (bright blue for visibility)
            if (originalCurve.size > 1) {
                drawCurve(originalCurve, Color(0xFF4A9EFF), w, h, minGain, maxGain, 2.5f)
            }

            // Target curve (bright white dashed) — normalized to measurement
            if (normalizedTarget.size > 1) {
                drawDashedCurve(normalizedTarget, Color.White, w, h, minGain, maxGain, 2.5f)
            }

            // Corrected curve (bright red solid) with fabfilter pro-q 3 style fill
            if (correctedCurve.size > 1) {
                drawFilledCurve(correctedCurve, Color(0xFFFF4444), w, h, minGain, maxGain, zeroOffset, 2.5f)
            }

            // EQ band dots
            eqBands.forEach { band ->
                if (!band.enabled) return@forEach
                // Find normalized positions
                val dotX = freqToX(band.freq, w)
                val bandGain = if (correctedCurve.isNotEmpty()) {
                    interpolateGain(band.freq, correctedCurve)
                } else {
                    var gainAtFreq = preamp + zeroOffset
                    eqBands.forEach { b -> if (b.enabled) gainAtFreq += AutoEqEngine.calculateBiquadResponse(band.freq, b, sampleRate) }
                    gainAtFreq
                }
                val dotY = gainToY(bandGain, h, minGain, maxGain)

                val isSelected = selectedBandId == band.id

                // Individual band contribution curve (Pro-Q style highlight)
                if (isSelected) {
                    val contributionPoints = originalCurve.map { p ->
                        val biquad = AutoEqEngine.calculateBiquadResponse(p.freq, band, sampleRate)
                        FrequencyPoint(p.freq, zeroOffset + biquad)
                    }
                    drawCurve(contributionPoints, Color.White.copy(alpha = 0.2f), w, h, minGain, maxGain, 1.5f)

                    // Floating Tooltip
                    val infoText = "${band.freq.toInt()}Hz  ${"%.1f".format(band.gain)}dB"
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.WHITE
                        textSize = 28f
                        textAlign = android.graphics.Paint.Align.CENTER
                        isFakeBoldText = true
                    }
                    val textWidth = paint.measureText(infoText)
                    val tooltipPadding = 16f
                    val rectTop = (dotY - 70f).coerceAtLeast(GRAPH_PADDING_TOP)

                    drawContext.canvas.nativeCanvas.drawRoundRect(
                        dotX - textWidth/2 - tooltipPadding, rectTop - 35f,
                        dotX + textWidth/2 + tooltipPadding, rectTop + 10f,
                        12f, 12f,
                        android.graphics.Paint().apply { color = android.graphics.Color.argb(180, 20, 20, 20) }
                    )
                    drawContext.canvas.nativeCanvas.drawText(infoText, dotX, rectTop, paint)
                }

                // Dynamic color based on frequency (Rainbow/Spectrum)
                val hue = ((log10(band.freq) - log10(20f)) / (log10(20000f) - log10(20f)) * 360f)
                val bandColor = Color.hsl(hue, 0.7f, 0.6f)

                // Glow for selected/dragged band
                if (isSelected) {
                    drawCircle(
                        color = bandColor.copy(alpha = 0.4f),
                        radius = 32f,
                        center = Offset(dotX, dotY)
                    )
                }

                // Main dot shadow/border
                drawCircle(
                    color = Color.Black,
                    radius = if (isSelected) 17f else 15f,
                    center = Offset(dotX, dotY)
                )

                // Main dot
                drawCircle(
                    color = bandColor,
                    radius = if (isSelected) 15f else 13f,
                    center = Offset(dotX, dotY)
                )
                // White border
                drawCircle(
                    color = Color.White,
                    radius = if (isSelected) 15f else 13f,
                    center = Offset(dotX, dotY),
                    style = Stroke(width = if (isSelected) 3f else 2.5f)
                )
            }
        }

        // Legend overlay (hidden in parametric-only mode since there's no measurement/target curve)
        if (showLegend) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(legendBackground)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
            ) {
                LegendDot("Original", Color(0xFF4A9EFF), legendLabelColor)
                LegendDot("Target (Primary)", Color.White, legendLabelColor)
                LegendDot("Corrected", Color(0xFFFF4444), legendLabelColor)
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color, labelColor: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, RoundedCornerShape(4.dp))
        )
        Text(
            label,
            fontSize = 9.sp,
            color = labelColor
        )
    }
}

/**
 * Compact read-only mini graph showing per-band filter shapes for a profile card.
 * No axes, labels, or interaction — just the colored band fills on a dark background.
 */
@Composable
fun EqProfileMiniGraph(
    bands: List<EqBand>,
    modifier: Modifier = Modifier,
    preamp: Float = 0f,
    sampleRate: Float = 48000f,
    // ±dB display range. Defaults to the AutoEQ cap; Parametric profile previews pass
    // EqLimits.PARAMETRIC_MAX_BAND_DB so bigger boosts/cuts aren't clipped off visually.
    gainRange: Float = EqLimits.AUTOEQ_MAX_BAND_DB,
) {
    if (bands.isEmpty()) return

    val miniBackground = MaterialTheme.colorScheme.surface
    val zeroLineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(miniBackground)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val midY = h / 2f
            val yScale = midY / gainRange

            // Zero center line
            drawLine(
                color = zeroLineColor,
                start = Offset(0f, midY),
                end = Offset(w, midY),
                strokeWidth = 1f
            )

            // Draw each band as a filled shape from zero line
            val freqPoints = buildFreqSamples(MIN_FREQ, MAX_FREQ, 256)

            bands.forEach { band ->
                if (!band.enabled) return@forEach

                val hue = ((log10(band.freq) - log10(MIN_FREQ)) /
                        (log10(MAX_FREQ) - log10(MIN_FREQ)) * 300f).coerceIn(0f, 300f)
                val bandColor = Color.hsl(hue, 0.85f, 0.55f)

                val gainValues = freqPoints.map { freq ->
                    AutoEqEngine.calculateBiquadResponse(freq, band, sampleRate)
                }

                val fillPath = Path()
                val linePath = Path()
                var started = false
                freqPoints.forEachIndexed { i, freq ->
                    val x = freqToX(freq, w)
                    val g = gainValues[i].coerceIn(-gainRange, gainRange)
                    val y = midY - g * yScale
                    if (!started) {
                        fillPath.moveTo(x, midY)
                        fillPath.lineTo(x, y)
                        linePath.moveTo(x, y)
                        started = true
                    } else {
                        fillPath.lineTo(x, y)
                        linePath.lineTo(x, y)
                    }
                }
                // Close fill back to zero line
                val lastX = freqToX(freqPoints.last(), w)
                val firstX = freqToX(freqPoints.first(), w)
                fillPath.lineTo(lastX, midY)
                fillPath.lineTo(firstX, midY)
                fillPath.close()

                drawPath(fillPath, bandColor.copy(alpha = 0.35f))
                drawPath(linePath, bandColor, style = Stroke(width = 1.5f))
            }
        }
    }
}

private fun buildFreqSamples(minF: Float, maxF: Float, count: Int): List<Float> {
    val logMin = log10(minF)
    val logMax = log10(maxF)
    return (0 until count).map { i ->
        10f.pow(logMin + i.toFloat() / (count - 1) * (logMax - logMin))
    }
}

// ===== Normalization (matching SeapEngine) =====

/**
 * Calculate average gain between 250Hz and 2500Hz for normalization.
 * Matches SeapEngine's getNormalizationOffset function.
 */
private fun getNormalizationOffset(data: List<FrequencyPoint>): Float {
    var sum = 0f
    var count = 0
    for (p in data) {
        if (p.freq in 250f..2500f) {
            sum += p.gain
            count++
        }
    }
    return if (count > 0) sum / count else interpolateGain(1000f, data)
}

// ===== Coordinate conversion =====

private fun freqToX(freq: Float, width: Float): Float {
    val logFreq = log10(freq.coerceIn(MIN_FREQ, MAX_FREQ))
    val logMin = log10(MIN_FREQ)
    val logMax = log10(MAX_FREQ)
    val ratio = (logFreq - logMin) / (logMax - logMin)
    return GRAPH_PADDING_LEFT + ratio * (width - GRAPH_PADDING_LEFT - GRAPH_PADDING_RIGHT)
}

private fun gainToY(gain: Float, height: Float, minGain: Float, maxGain: Float): Float {
    val ratio = (gain - minGain) / (maxGain - minGain)
    return (height - GRAPH_PADDING_BOTTOM) - ratio * (height - GRAPH_PADDING_TOP - GRAPH_PADDING_BOTTOM)
}

private fun xToFreq(x: Float, width: Float): Float {
    val logMin = log10(MIN_FREQ)
    val logMax = log10(MAX_FREQ)
    val ratio = (x - GRAPH_PADDING_LEFT) / (width - GRAPH_PADDING_LEFT - GRAPH_PADDING_RIGHT)
    val logFreq = logMin + ratio.coerceIn(0f, 1f) * (logMax - logMin)
    return 10f.pow(logFreq).coerceIn(MIN_FREQ, MAX_FREQ)
}

private fun yToGain(
    y: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    maxAbsDragGain: Float = EqLimits.AUTOEQ_MAX_BAND_DB,
): Float {
    val ratio = ((height - GRAPH_PADDING_BOTTOM) - y) / (height - GRAPH_PADDING_TOP - GRAPH_PADDING_BOTTOM)
    return (minGain + ratio.coerceIn(0f, 1f) * (maxGain - minGain))
        .coerceIn(-maxAbsDragGain, maxAbsDragGain)
}

private fun findNearestBand(
    position: Offset,
    bands: List<EqBand>,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float
): Int {
    val threshold = 50f
    var nearest = -1
    var nearestDist = Float.MAX_VALUE
    bands.forEach { band ->
        if (!band.enabled) return@forEach
        val dotX = freqToX(band.freq, width)
        val dotY = gainToY(band.gain + zeroOffset, height, minGain, maxGain)
        val dist = sqrt((position.x - dotX).pow(2) + (position.y - dotY).pow(2))
        if (dist < threshold && dist < nearestDist) {
            nearest = band.id
            nearestDist = dist
        }
    }
    return nearest
}

private fun interpolateGain(freq: Float, curve: List<FrequencyPoint>): Float {
    if (curve.isEmpty()) return 0f
    if (freq <= curve.first().freq) return curve.first().gain
    if (freq >= curve.last().freq) return curve.last().gain
    for (i in 0 until curve.size - 1) {
        if (freq >= curve[i].freq && freq <= curve[i + 1].freq) {
            val t = (freq - curve[i].freq) / (curve[i + 1].freq - curve[i].freq)
            return curve[i].gain + t * (curve[i + 1].gain - curve[i].gain)
        }
    }
    return 0f
}

// ===== Drawing functions =====

private fun DrawScope.drawGrid(
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float
) {
    val gridColor = Color(0x18FFFFFF)
    val zeroLineColor = Color(0x30FFFFFF)

    // Vertical frequency lines
    val freqs = listOf(20f, 50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)
    freqs.forEach { freq ->
        val x = freqToX(freq, width)
        drawLine(gridColor, Offset(x, GRAPH_PADDING_TOP), Offset(x, height - GRAPH_PADDING_BOTTOM), 1f)
    }

    // Horizontal dB lines — use fixed step based on range
    val range = maxGain - minGain
    val step = when {
        range > 60 -> 10f
        range > 30 -> 5f
        else -> 5f
    }
    var g = (minGain / step).toInt() * step
    while (g <= maxGain) {
        val y = gainToY(g, height, minGain, maxGain)
        if (y in GRAPH_PADDING_TOP..(height - GRAPH_PADDING_BOTTOM)) {
            // Highlight the zero/center line
            val lineColor = if (abs(g - zeroOffset) < 0.5f) zeroLineColor else gridColor
            drawLine(lineColor, Offset(GRAPH_PADDING_LEFT, y), Offset(width - GRAPH_PADDING_RIGHT, y), 1f)
        }
        g += step
    }
}

private fun DrawScope.drawDbLabels(width: Float, height: Float, minGain: Float, maxGain: Float) {
    val range = maxGain - minGain
    val step = when {
        range > 60 -> 10f
        range > 30 -> 5f
        else -> 5f
    }
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(120, 180, 180, 180)
        textSize = 22f
        textAlign = android.graphics.Paint.Align.LEFT
        isAntiAlias = true
    }
    var g = (minGain / step).toInt() * step
    while (g <= maxGain) {
        val y = gainToY(g, height, minGain, maxGain)
        if (y in GRAPH_PADDING_TOP..(height - GRAPH_PADDING_BOTTOM)) {
            drawContext.canvas.nativeCanvas.drawText(
                "${g.toInt()}",
                width - GRAPH_PADDING_RIGHT + 4f,
                y + 7f,
                paint
            )
        }
        g += step
    }
}

private fun DrawScope.drawFreqLabels(width: Float, height: Float) {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(120, 180, 180, 180)
        textSize = 20f
        textAlign = android.graphics.Paint.Align.CENTER
        isAntiAlias = true
    }
    val labels = mapOf(
        20f to "20", 50f to "50", 100f to "100", 200f to "200",
        500f to "500", 1000f to "1k", 2000f to "2k", 5000f to "5k",
        10000f to "10k", 20000f to "20k"
    )
    labels.forEach { (freq, label) ->
        val x = freqToX(freq, width)
        drawContext.canvas.nativeCanvas.drawText(
            label, x, height - 2f, paint
        )
    }
}

private fun DrawScope.drawCurve(
    curve: List<FrequencyPoint>,
    color: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    strokeWidth: Float
) {
    if (curve.size < 2) return
    val path = Path().apply {
        moveTo(freqToX(curve[0].freq, width), gainToY(curve[0].gain, height, minGain, maxGain))
        for (i in 1 until curve.size) {
            lineTo(freqToX(curve[i].freq, width), gainToY(curve[i].gain, height, minGain, maxGain))
        }
    }
    drawPath(path, color, style = Stroke(width = strokeWidth))
}

private fun DrawScope.drawDashedCurve(
    curve: List<FrequencyPoint>,
    color: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    strokeWidth: Float
) {
    if (curve.size < 2) return
    val path = Path().apply {
        moveTo(freqToX(curve[0].freq, width), gainToY(curve[0].gain, height, minGain, maxGain))
        for (i in 1 until curve.size) {
            lineTo(freqToX(curve[i].freq, width), gainToY(curve[i].gain, height, minGain, maxGain))
        }
    }
    drawPath(
        path, color,
        style = Stroke(
            width = strokeWidth,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
        )
    )
}

/**
 * Render FFT spectrum bins as a smooth Catmull-Rom envelope with vertical
 * gradient fill (FabFilter Pro-Q style). Bins span MIN_FREQ..MAX_FREQ on a
 * log axis; magnitudes are dB relative to 0 dB center.
 */
private fun DrawScope.drawSpectrum(
    bins: FloatArray,
    color: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float
) {
    if (bins.isEmpty()) return
    val n = bins.size
    val logMin = log10(MIN_FREQ)
    val logMax = log10(MAX_FREQ)
    val topY = GRAPH_PADDING_TOP
    val bottomY = height - GRAPH_PADDING_BOTTOM

    // Precompute bin x/y in screen space
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    for (i in 0 until n) {
        val t = i.toFloat() / (n - 1).toFloat()
        val freq = 10f.pow(logMin + t * (logMax - logMin))
        xs[i] = freqToX(freq, width)
        val binGain = (zeroOffset + bins[i]).coerceIn(minGain, maxGain)
        ys[i] = gainToY(binGain, height, minGain, maxGain).coerceIn(topY, bottomY)
    }

    // Build a smooth envelope path using Catmull-Rom -> cubic Bézier interpolation.
    // This gives the flowing, continuously-curved look of FabFilter Pro-Q.
    val envelope = Path().apply {
        moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val p0x = xs[(i - 1).coerceAtLeast(0)]; val p0y = ys[(i - 1).coerceAtLeast(0)]
            val p1x = xs[i]; val p1y = ys[i]
            val p2x = xs[i + 1]; val p2y = ys[i + 1]
            val p3x = xs[(i + 2).coerceAtMost(n - 1)]; val p3y = ys[(i + 2).coerceAtMost(n - 1)]
            val c1x = p1x + (p2x - p0x) / 6f
            val c1y = p1y + (p2y - p0y) / 6f
            val c2x = p2x - (p3x - p1x) / 6f
            val c2y = p2y - (p3y - p1y) / 6f
            cubicTo(c1x, c1y, c2x, c2y, p2x, p2y)
        }
    }

    // Filled body — vertical gradient from envelope top down to graph bottom.
    val fill = Path().apply {
        addPath(envelope)
        lineTo(xs[n - 1], bottomY)
        lineTo(xs[0], bottomY)
        close()
    }
    drawPath(
        path = fill,
        brush = Brush.verticalGradient(
            colors = listOf(
                color.copy(alpha = 0.55f),
                color.copy(alpha = 0.18f),
                color.copy(alpha = 0.04f)
            ),
            startY = topY,
            endY = bottomY
        )
    )

    // Soft envelope outline for definition.
    drawPath(envelope, color.copy(alpha = 0.85f), style = Stroke(width = 1.5f))
}

private fun DrawScope.drawFilledCurve(
    curve: List<FrequencyPoint>,
    lineColor: Color,
    width: Float,
    height: Float,
    minGain: Float,
    maxGain: Float,
    zeroOffset: Float,
    strokeWidth: Float
) {
    if (curve.size < 2) return
    val path = Path().apply {
        moveTo(freqToX(curve[0].freq, width), gainToY(curve[0].gain, height, minGain, maxGain))
        for (i in 1 until curve.size) {
            lineTo(freqToX(curve[i].freq, width), gainToY(curve[i].gain, height, minGain, maxGain))
        }
    }
    
    val zeroY = gainToY(zeroOffset, height, minGain, maxGain).coerceIn(GRAPH_PADDING_TOP, height - GRAPH_PADDING_BOTTOM)
    val fillPath = Path().apply {
        addPath(path)
        lineTo(freqToX(curve.last().freq, width), zeroY)
        lineTo(freqToX(curve[0].freq, width), zeroY)
        close()
    }
    
    drawPath(
        path = fillPath,
        brush = Brush.verticalGradient(
            colors = listOf(lineColor.copy(alpha = 0.25f), lineColor.copy(alpha = 0.0f)),
            startY = GRAPH_PADDING_TOP,
            endY = zeroY
        )
    )
    
    drawPath(path, lineColor, style = Stroke(width = strokeWidth))
}
