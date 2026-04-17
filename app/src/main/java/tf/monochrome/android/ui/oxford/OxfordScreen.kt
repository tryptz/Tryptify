// SPDX-License-Identifier: GPL-3.0-or-later
// Compose UI for Seap Inflator + Seap Compressor tabs.

package tf.monochrome.android.ui.oxford

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import tf.monochrome.android.audio.dsp.oxford.CompressorEffect
import tf.monochrome.android.audio.dsp.oxford.CompressorPreset
import tf.monochrome.android.audio.dsp.oxford.CompressorState
import tf.monochrome.android.audio.dsp.oxford.InflatorEffect
import tf.monochrome.android.audio.dsp.oxford.InflatorPreset
import tf.monochrome.android.audio.dsp.oxford.InflatorState
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

// ----------------------------------------------------------------------------
// Palette
// ----------------------------------------------------------------------------

/**
 * Theme-derived Seap palette. Everything is keyed off MaterialTheme.colorScheme
 * so the Inflator / Compressor sit inside whichever of the 16 themes the user
 * has picked (monochrome, ocean, midnight, crimson, …). LEDs stay semantic
 * — green/yellow/red convey signal health independent of brand colour.
 */
private data class SeapPalette(
    val panelSheen: Color,
    val panelTop: Color,
    val panelMid: Color,
    val panelBottom: Color,
    val panelShadow: Color,
    val panelDarkEdge: Color,

    val chromeHi: Color,
    val chromeLight: Color,
    val chromeMid: Color,
    val chromeDark: Color,
    val chromeShadow: Color,

    val readoutRim: Color,
    val readout: Color,
    val readoutTop: Color,
    val readoutText: Color,
    val readoutGlow: Color,

    val ledGreen: Color,
    val ledYellow: Color,
    val ledRed: Color,
    val ledOff: Color,
    val ledOffEdge: Color,

    val trackBg: Color,
    val trackDeep: Color,
    val trackSheen: Color,

    val btnClip: Color,
    val btnClipHi: Color,
    val btnBandSplit: Color,
    val btnEffectOn: Color,
    val btnEffectHi: Color,

    val textOnChrome: Color,
    val textTitle: Color,
)

private val LocalSeapPalette = staticCompositionLocalOf<SeapPalette> {
    error("LocalSeapPalette not provided — wrap content in ProvideSeapPalette()")
}

@Composable
private fun rememberSeapPalette(): SeapPalette {
    val cs = MaterialTheme.colorScheme
    return remember(cs) {
        val base     = cs.surfaceVariant   // panel body
        val elevated = cs.surface          // slightly darker mid
        val deep     = cs.background       // deepest — pure black on monochrome
        SeapPalette(
            panelSheen   = base.lighten(0.25f),
            panelTop     = base.lighten(0.08f),
            panelMid     = base,
            panelBottom  = elevated,
            panelShadow  = deep,
            panelDarkEdge = deep,

            // Dark brushed metal, not silver — subtle sheen from above.
            chromeHi     = base.lighten(0.32f),
            chromeLight  = base.lighten(0.18f),
            chromeMid    = base.lighten(0.05f),
            chromeDark   = base.darken(0.15f),
            chromeShadow = deep,

            readoutRim   = deep,
            readout      = elevated.darken(0.40f),
            readoutTop   = elevated.darken(0.20f),
            readoutText  = cs.primary,
            readoutGlow  = cs.primary.copy(alpha = 0.45f),

            ledGreen   = Color(0xFF3EE26A),
            ledYellow  = Color(0xFFF5D84A),
            ledRed     = Color(0xFFE84A3A),
            ledOff     = elevated.darken(0.35f),
            ledOffEdge = deep,

            trackBg    = elevated.darken(0.20f),
            trackDeep  = deep,
            trackSheen = base.lighten(0.15f),

            btnClip      = cs.error,
            btnClipHi    = cs.error.lighten(0.35f),
            btnBandSplit = base.lighten(0.12f),
            btnEffectOn  = cs.primary,
            btnEffectHi  = cs.primary.lighten(0.40f),

            textOnChrome = cs.onSurface,
            textTitle    = cs.onSurface,
        )
    }
}

/** 4-stop dark brushed-metal vertical gradient. */
private fun brushedMetalGradient(p: SeapPalette): Brush = Brush.verticalGradient(
    0.00f to p.chromeHi,
    0.18f to p.chromeLight,
    0.55f to p.chromeMid,
    1.00f to p.chromeDark,
)

/** Panel gradient — subtle sheen over theme surfaces. */
private fun panelGradient(p: SeapPalette): Brush = Brush.verticalGradient(
    0.00f to p.panelSheen,
    0.06f to p.panelTop,
    0.55f to p.panelMid,
    1.00f to p.panelBottom,
)

@Composable
private fun digitStyle(p: SeapPalette): TextStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Bold,
    fontSize = 14.sp,
    color = p.readoutText,
)

/** LED peak-hold decay rate in dB/sec (rate-independent). */
private const val DECAY_DB_PER_SEC = 36f

// ----------------------------------------------------------------------------
// Vertical fader — silver cap on a dark track, drag to set value.
// ----------------------------------------------------------------------------

@Composable
private fun OxfordFader(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    trackWidth: Dp = 10.dp,
) {
    val seap = LocalSeapPalette.current
    var dragOriginValue by remember { mutableStateOf(value) }
    var dragAccumPx by remember { mutableStateOf(0f) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(valueRange) {
                    detectDragGestures(
                        onDragStart = {
                            dragOriginValue = value
                            dragAccumPx = 0f
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            dragAccumPx += drag.y
                            val h = size.height.toFloat()
                            if (h > 0f) {
                                val span = valueRange.endInclusive - valueRange.start
                                val delta = -(dragAccumPx / h) * span
                                val next = (dragOriginValue + delta)
                                    .coerceIn(valueRange.start, valueRange.endInclusive)
                                onValueChange(next)
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val trackX = w * 0.5f
            val trackTop = 6f
            val trackBottom = h - 6f
            val trackHeight = trackBottom - trackTop
            val tw = trackWidth.toPx()

            // Track — recessed well. Outer dark rim, deeper centre, sheen along right edge.
            drawRoundRect(
                color = seap.panelDarkEdge,
                topLeft = Offset(trackX - tw / 2f - 1f, trackTop - 1f),
                size = Size(tw + 2f, trackHeight + 2f),
                cornerRadius = CornerRadius(4f, 4f),
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0f to seap.trackDeep,
                    1f to seap.trackBg,
                ),
                topLeft = Offset(trackX - tw / 2f, trackTop),
                size = Size(tw, trackHeight),
                cornerRadius = CornerRadius(3f, 3f),
            )
            // Right-edge specular line — sells the "inset well lit from above-left"
            drawLine(
                color = seap.trackSheen.copy(alpha = 0.65f),
                start = Offset(trackX + tw / 2f - 1f, trackTop + 4f),
                end   = Offset(trackX + tw / 2f - 1f, trackBottom - 4f),
                strokeWidth = 1f,
            )

            val t = ((value - valueRange.start) /
                    (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
            val handleY = trackBottom - t * trackHeight
            val handleW = w * 0.88f
            val handleH = 32f

            // Drop shadow (offset down-right, soft-ish via double draw)
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.35f),
                topLeft = Offset(trackX - handleW / 2f + 1f, handleY - handleH / 2f + 3f),
                size = Size(handleW, handleH),
                cornerRadius = CornerRadius(5f, 5f),
            )
            drawRoundRect(
                color = Color.Black.copy(alpha = 0.20f),
                topLeft = Offset(trackX - handleW / 2f + 2f, handleY - handleH / 2f + 5f),
                size = Size(handleW - 2f, handleH),
                cornerRadius = CornerRadius(5f, 5f),
            )

            // Handle body — 4-stop metallic with darker waist in the middle (brushed cap look)
            drawRoundRect(
                brush = Brush.verticalGradient(
                    0.00f to seap.chromeHi,
                    0.15f to seap.chromeLight,
                    0.50f to seap.chromeMid,
                    0.80f to seap.chromeDark,
                    1.00f to seap.chromeShadow,
                ),
                topLeft = Offset(trackX - handleW / 2f, handleY - handleH / 2f),
                size = Size(handleW, handleH),
                cornerRadius = CornerRadius(5f, 5f),
            )
            // Top specular line (very thin, near-white)
            drawLine(
                color = Color.White.copy(alpha = 0.85f),
                start = Offset(trackX - handleW / 2f + 3f, handleY - handleH / 2f + 1.5f),
                end   = Offset(trackX + handleW / 2f - 3f, handleY - handleH / 2f + 1.5f),
                strokeWidth = 1f,
            )
            // Centre waist line — makes the cap feel machined
            drawLine(
                color = seap.chromeShadow.copy(alpha = 0.65f),
                start = Offset(trackX - handleW / 2f + 2f, handleY),
                end   = Offset(trackX + handleW / 2f - 2f, handleY),
                strokeWidth = 1.4f,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.35f),
                start = Offset(trackX - handleW / 2f + 2f, handleY + 1.5f),
                end   = Offset(trackX + handleW / 2f - 2f, handleY + 1.5f),
                strokeWidth = 1f,
            )
            // Grippers — 5 etched lines above the waist, 3 below
            val gripCount = 5
            for (i in 0 until gripCount) {
                val gy = handleY - (handleH * 0.34f) + i * (handleH * 0.22f / (gripCount - 1))
                drawLine(
                    color = seap.chromeShadow,
                    start = Offset(trackX - handleW * 0.32f, gy),
                    end   = Offset(trackX + handleW * 0.32f, gy),
                    strokeWidth = 1f,
                )
                drawLine(
                    color = Color.White.copy(alpha = 0.35f),
                    start = Offset(trackX - handleW * 0.32f, gy + 1f),
                    end   = Offset(trackX + handleW * 0.32f, gy + 1f),
                    strokeWidth = 0.7f,
                )
            }
            // Bottom-edge shadow to anchor the cap
            drawLine(
                color = Color.Black.copy(alpha = 0.55f),
                start = Offset(trackX - handleW / 2f + 3f, handleY + handleH / 2f - 1.5f),
                end   = Offset(trackX + handleW / 2f - 3f, handleY + handleH / 2f - 1.5f),
                strokeWidth = 1f,
            )
        }
    }
}

// ----------------------------------------------------------------------------
// LED meter — vertical, segmented, green/yellow/red. dB-scaled.
// ----------------------------------------------------------------------------

@Composable
private fun LedMeter(
    peakL: Float,
    peakR: Float,
    modifier: Modifier = Modifier,
    minDb: Float = -42f,
    maxDb: Float = 0f,
    segmentCount: Int = 24,
    stereo: Boolean = true,
) {
    val seap = LocalSeapPalette.current
    var heldL by remember { mutableStateOf(minDb) }
    var heldR by remember { mutableStateOf(minDb) }
    var lastTick by remember { mutableStateOf(0L) }

    val dbL = if (peakL > 1e-6f) 20f * log10(peakL) else minDb
    val dbR = if (peakR > 1e-6f) 20f * log10(peakR) else minDb

    // Decay peak hold at a fixed rate (dB/sec) independent of poll rate so
    // bumping the meter FPS doesn't also speed up how fast the hold falls.
    LaunchedEffect(dbL, dbR) {
        val now = System.nanoTime()
        val dtSec = if (lastTick == 0L) 0f else (now - lastTick) / 1_000_000_000f
        lastTick = now
        val decayDb = DECAY_DB_PER_SEC * dtSec
        heldL = max(dbL, heldL - decayDb).coerceIn(minDb, maxDb + 3f)
        heldR = max(dbR, heldR - decayDb).coerceIn(minDb, maxDb + 3f)
    }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val laneCount = if (stereo) 2 else 1
        val gap = 2f
        val laneW = (w - gap * (laneCount - 1)) / laneCount
        val segGap = 1.2f
        val segH = (h - segGap * (segmentCount - 1)) / segmentCount

        // Outer dark bezel behind all lanes for inset-panel feel
        drawRoundRect(
            color = seap.panelDarkEdge,
            topLeft = Offset(-1.5f, -1.5f),
            size = Size(w + 3f, h + 3f),
            cornerRadius = CornerRadius(3f, 3f),
        )

        for (lane in 0 until laneCount) {
            val lx = lane * (laneW + gap)
            val db = if (lane == 0) dbL else dbR
            val lit = dbToSegments(db, minDb, maxDb, segmentCount)
            val held = if (lane == 0) heldL else heldR
            val heldSeg = dbToSegments(held, minDb, maxDb, segmentCount) - 1

            for (s in 0 until segmentCount) {
                val segY = h - (s + 1) * segH - s * segGap
                val isLit = s < lit
                val isPeak = s == heldSeg && heldSeg >= 0
                val ledColor = ledColorFor(seap, s, segmentCount)

                if (isLit || isPeak) {
                    val c = if (isLit) ledColor else ledColor.copy(alpha = 0.7f)
                    // Soft glow halo underneath (slightly larger, low alpha)
                    drawRoundRect(
                        color = c.copy(alpha = if (isLit) 0.35f else 0.18f),
                        topLeft = Offset(lx - 1.5f, segY - 1f),
                        size = Size(laneW + 3f, segH + 2f),
                        cornerRadius = CornerRadius(2f, 2f),
                    )
                    // LED body — lit with top→bottom brightness gradient for glass-bead look
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.55f).compositeOver(c),
                            0.4f to c,
                            1f to c.copy(alpha = 0.85f).compositeOver(Color.Black.copy(alpha = 0.2f)),
                        ),
                        topLeft = Offset(lx, segY),
                        size = Size(laneW, segH),
                        cornerRadius = CornerRadius(1.5f, 1.5f),
                    )
                    // Very thin top specular
                    drawLine(
                        color = Color.White.copy(alpha = 0.7f),
                        start = Offset(lx + 1f, segY + 0.8f),
                        end   = Offset(lx + laneW - 1f, segY + 0.8f),
                        strokeWidth = 0.8f,
                    )
                } else {
                    // Unlit well — inset look
                    drawRoundRect(
                        color = seap.ledOffEdge,
                        topLeft = Offset(lx - 0.5f, segY - 0.5f),
                        size = Size(laneW + 1f, segH + 1f),
                        cornerRadius = CornerRadius(1.5f, 1.5f),
                    )
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            0f to seap.ledOffEdge,
                            1f to seap.ledOff,
                        ),
                        topLeft = Offset(lx, segY),
                        size = Size(laneW, segH),
                        cornerRadius = CornerRadius(1.5f, 1.5f),
                    )
                }
            }
        }
    }
}

private fun Color.compositeOver(background: Color): Color {
    val a = this.alpha + background.alpha * (1f - this.alpha)
    if (a < 1e-4f) return Color.Transparent
    val r = (this.red * this.alpha + background.red * background.alpha * (1f - this.alpha)) / a
    val g = (this.green * this.alpha + background.green * background.alpha * (1f - this.alpha)) / a
    val b = (this.blue * this.alpha + background.blue * background.alpha * (1f - this.alpha)) / a
    return Color(r, g, b, a)
}

/** Pick a contrasting text colour for the given background. */
private fun onColorFor(bg: Color): Color =
    if (bg.luminance() > 0.55f) Color(0xFF0A0A0A) else Color.White

private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

private fun Color.lighten(amount: Float): Color = Color(
    red   = (red   + (1f - red)   * amount).coerceIn(0f, 1f),
    green = (green + (1f - green) * amount).coerceIn(0f, 1f),
    blue  = (blue  + (1f - blue)  * amount).coerceIn(0f, 1f),
    alpha = alpha,
)

private fun Color.darken(amount: Float): Color = Color(
    red   = (red   * (1f - amount)).coerceIn(0f, 1f),
    green = (green * (1f - amount)).coerceIn(0f, 1f),
    blue  = (blue  * (1f - amount)).coerceIn(0f, 1f),
    alpha = alpha,
)

private fun dbToSegments(db: Float, minDb: Float, maxDb: Float, segmentCount: Int): Int {
    val t = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
    return (t * segmentCount).roundToInt()
}

private fun ledColorFor(p: SeapPalette, segmentIndex: Int, total: Int): Color {
    val t = segmentIndex.toFloat() / (total - 1).toFloat()
    return when {
        t >= 0.90f -> p.ledRed
        t >= 0.75f -> p.ledYellow
        else       -> p.ledGreen
    }
}

// ----------------------------------------------------------------------------
// Chunky pill buttons
// ----------------------------------------------------------------------------

@Composable
private fun OxfordButton(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seap = LocalSeapPalette.current
    val shape = RoundedCornerShape(7.dp)
    // Convex metallic dome when active, brushed chrome when inactive.
    val bodyBrush = if (active) {
        val hi = activeColor.lighten(0.45f)
        val lo = activeColor.darken(0.30f)
        Brush.verticalGradient(
            0.00f to hi,
            0.30f to activeColor,
            1.00f to lo,
        )
    } else brushedMetalGradient(seap)

    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        shadowElevation = if (active) 4.dp else 2.dp,
        modifier = modifier
            .height(46.dp)
            .defaultMinSize(minWidth = 68.dp)
            .shadow(
                elevation = if (active) 5.dp else 3.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.7f),
                spotColor = Color.Black.copy(alpha = 0.7f),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(bodyBrush, shape)
                .border(
                    width = 0.8.dp,
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (active) 0.85f else 0.7f),
                        0.4f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.45f),
                    ),
                    shape = shape,
                )
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            val textColor = if (active) onColorFor(activeColor) else seap.textOnChrome
            Text(
                text = label,
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = textColor,
                    fontFamily = FontFamily.SansSerif,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Fader column
// ----------------------------------------------------------------------------

@Composable
private fun FaderColumn(
    label: String,
    readout: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    peakL: Float,
    peakR: Float,
    modifier: Modifier = Modifier,
    meterStereo: Boolean = true,
    meterMinDb: Float = -42f,
    meterMaxDb: Float = 0f,
) {
    val seap = LocalSeapPalette.current
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = seap.textTitle,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.8.sp,
            ),
        )
        Spacer(Modifier.height(4.dp))
        LcdReadout(text = readout)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OxfordFader(
                value = value,
                valueRange = valueRange,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            LedMeter(
                peakL = peakL,
                peakR = peakR,
                stereo = meterStereo,
                minDb = meterMinDb,
                maxDb = meterMaxDb,
                modifier = Modifier.width(if (meterStereo) 22.dp else 12.dp).fillMaxHeight(),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// LCD readout — recessed black panel with top-lit glass + inset rim
// ----------------------------------------------------------------------------

@Composable
private fun LcdReadout(text: String) {
    val seap = LocalSeapPalette.current
    val rimShape = RoundedCornerShape(4.dp)
    val glassShape = RoundedCornerShape(3.dp)
    Box(
        modifier = Modifier
            .clip(rimShape)
            .background(seap.readoutRim)
            .padding(1.5.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(glassShape)
                .background(
                    Brush.verticalGradient(
                        0f to seap.readoutTop,
                        0.5f to seap.readout,
                        1f to seap.readoutRim,
                    ),
                    glassShape,
                )
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            // Thin specular hairline across the top — sells the glass dome
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(0.8.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            )
            Text(
                text = text,
                style = digitStyle(seap).copy(
                    shadow = androidx.compose.ui.graphics.Shadow(
                        color = seap.readoutGlow,
                        offset = Offset(0f, 0f),
                        blurRadius = 4f,
                    ),
                ),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Panel chrome
// ----------------------------------------------------------------------------

@Composable
private fun OxfordPanel(
    title: String,
    modifier: Modifier = Modifier,
    onHelpClick: (() -> Unit)? = null,
    presetRail: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val seap = LocalSeapPalette.current
    val panelShape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .shadow(
                elevation = 10.dp,
                shape = panelShape,
                ambientColor = Color.Black.copy(alpha = 0.9f),
                spotColor = Color.Black.copy(alpha = 0.9f),
            )
            .clip(panelShape)
            .background(panelGradient(seap))
            // Double bevel: bright inner hairline + dark outer rim for depth
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    0f to seap.chromeHi.copy(alpha = 0.55f),
                    0.4f to Color.Transparent,
                    1f to seap.panelShadow,
                ),
                shape = panelShape,
            )
            .padding(10.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            val headerShape = RoundedCornerShape(5.dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 3.dp,
                        shape = headerShape,
                        ambientColor = Color.Black.copy(alpha = 0.7f),
                        spotColor = Color.Black.copy(alpha = 0.7f),
                    )
                    .clip(headerShape)
                    .background(
                        Brush.verticalGradient(
                            0f to seap.panelMid.darken(0.15f),
                            0.5f to seap.panelBottom.darken(0.25f),
                            1f to seap.panelDarkEdge,
                        ),
                        headerShape,
                    )
                    .border(
                        width = 0.8.dp,
                        brush = Brush.verticalGradient(
                            0f to seap.chromeHi.copy(alpha = 0.3f),
                            0.5f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                        shape = headerShape,
                    )
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "SEAP",
                    style = TextStyle(
                        color = seap.textTitle,
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.2.sp,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = title,
                    style = TextStyle(
                        color = seap.textTitle,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 1.5.sp,
                    ),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "Seap Effects",
                    style = TextStyle(color = seap.textTitle.copy(alpha = 0.8f), fontSize = 10.sp),
                )
                if (onHelpClick != null) {
                    Spacer(Modifier.width(4.dp))
                    HelpChip(onClick = onHelpClick)
                }
            }
            if (presetRail != null) {
                Spacer(Modifier.height(8.dp))
                presetRail()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

// ----------------------------------------------------------------------------
// ? help chip + preset rail
// ----------------------------------------------------------------------------

@Composable
private fun HelpChip(onClick: () -> Unit) {
    val seap = LocalSeapPalette.current
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        modifier = Modifier
            .size(24.dp)
            .shadow(
                elevation = 3.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.8f),
                spotColor = Color.Black.copy(alpha = 0.8f),
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(brushedMetalGradient(seap), CircleShape)
                .border(
                    width = 0.8.dp,
                    brush = Brush.verticalGradient(
                        0f to seap.chromeHi.copy(alpha = 0.9f),
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.55f),
                    ),
                    shape = CircleShape,
                ),
        ) {
            Text(
                text = "?",
                style = TextStyle(
                    fontWeight = FontWeight.Black,
                    fontSize = 13.sp,
                    color = seap.textOnChrome,
                    fontFamily = FontFamily.SansSerif,
                ),
            )
        }
    }
}

@Composable
private fun PresetRail(
    items: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items.forEachIndexed { index, (label, active) ->
            PresetChip(label = label, active = active, onClick = { onSelect(index) })
        }
    }
}

@Composable
private fun PresetChip(label: String, active: Boolean, onClick: () -> Unit) {
    val seap = LocalSeapPalette.current
    val shape = RoundedCornerShape(14.dp)
    val bodyBrush = if (active) {
        Brush.verticalGradient(
            0f to seap.btnEffectHi,
            0.45f to seap.btnEffectOn,
            1f to seap.btnEffectOn.darken(0.35f),
        )
    } else brushedMetalGradient(seap)
    val fg = if (active) onColorFor(seap.btnEffectOn) else seap.textOnChrome

    Surface(
        onClick = onClick,
        shape = shape,
        color = Color.Transparent,
        modifier = Modifier
            .height(30.dp)
            .shadow(
                elevation = if (active) 4.dp else 2.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.7f),
                spotColor = Color.Black.copy(alpha = 0.7f),
            ),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .background(bodyBrush, shape)
                .border(
                    width = 0.8.dp,
                    brush = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = if (active) 0.9f else 0.75f),
                        0.45f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.5f),
                    ),
                    shape = shape,
                )
                .padding(horizontal = 12.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                    color = fg,
                    letterSpacing = 0.7.sp,
                ),
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Tutorial bottom sheet
// ----------------------------------------------------------------------------

data class TutorialSection(val heading: String, val body: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TutorialSheet(
    title: String,
    subtitle: String,
    sections: List<TutorialSection>,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(18.dp))
            sections.forEachIndexed { i, section ->
                Text(
                    text = section.heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = section.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (i != sections.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Inflator screen
// ----------------------------------------------------------------------------

@Composable
fun InflatorScreen(
    effect: InflatorEffect,
    modifier: Modifier = Modifier,
) {
    val state by effect.state.collectAsState()
    val peak  by effect.peak.collectAsState()
    val seap = LocalSeapPalette.current
    var showHelp by rememberSaveable { mutableStateOf(false) }

    if (showHelp) {
        TutorialSheet(
            title = "Seap Inflator",
            subtitle = "Psycho-acoustic enhancer — makes tracks feel louder and richer without raising true peak level.",
            sections = inflatorTutorialSections(),
            onDismiss = { showHelp = false },
        )
    }

    OxfordPanel(
        title = "SEAP INFLATOR",
        modifier = modifier.fillMaxSize(),
        onHelpClick = { showHelp = true },
        presetRail = {
            val entries = InflatorPreset.entries
            val active = entries.indexOfFirst { matchesInflator(it.state, state) }
            PresetRail(
                items = entries.map { it.label to (entries.indexOf(it) == active) },
                onSelect = { effect.applyPreset(entries[it]) },
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FaderColumn(
                label = "INPUT",
                readout = formatDb(state.inputDb),
                value = state.inputDb,
                valueRange = -6f..12f,
                onValueChange = effect::setInputDb,
                peakL = peak.left, peakR = peak.right,
                modifier = Modifier.weight(1f),
                meterStereo = true,
                meterMinDb = -42f, meterMaxDb = 0f,
            )

            Column(
                modifier = Modifier.weight(1.1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FaderColumn(
                        label = "EFFECT",
                        readout = "${state.effectPct.roundToInt()} %",
                        value = state.effectPct,
                        valueRange = 0f..100f,
                        onValueChange = effect::setEffectPct,
                        peakL = peak.left, peakR = peak.right,
                        meterStereo = true,
                        meterMinDb = -42f, meterMaxDb = 0f,
                        modifier = Modifier.weight(1f),
                    )
                    FaderColumn(
                        label = "CURVE",
                        readout = formatCurve(state.curve),
                        value = state.curve,
                        valueRange = -50f..50f,
                        onValueChange = effect::setCurve,
                        peakL = 0f, peakR = 0f,
                        meterStereo = false,
                        meterMinDb = -50f, meterMaxDb = 50f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OxfordButton(
                        label = "CLIP\n0 dB",
                        active = state.clipZeroDb,
                        activeColor = seap.btnClip,
                        onClick = { effect.setClipZeroDb(!state.clipZeroDb) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OxfordButton(
                        label = "BAND\nSPLIT",
                        active = state.bandSplit,
                        activeColor = seap.btnBandSplit,
                        onClick = { effect.setBandSplit(!state.bandSplit) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OxfordButton(
                        label = "EFFECT\nIN",
                        active = state.effectIn,
                        activeColor = seap.btnEffectOn,
                        onClick = { effect.setEffectIn(!state.effectIn) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            FaderColumn(
                label = "OUTPUT",
                readout = formatDb(state.outputDb),
                value = state.outputDb,
                valueRange = -12f..0f,
                onValueChange = effect::setOutputDb,
                peakL = peak.left, peakR = peak.right,
                modifier = Modifier.weight(1f),
                meterStereo = true,
                meterMinDb = -40f, meterMaxDb = 0f,
            )
        }
    }
}

// ----------------------------------------------------------------------------
// Compressor screen
// ----------------------------------------------------------------------------

@Composable
fun CompressorScreen(
    effect: CompressorEffect,
    modifier: Modifier = Modifier,
) {
    val state by effect.state.collectAsState()
    val peak  by effect.peak.collectAsState()
    val grDb  by effect.gainReductionDb.collectAsState()
    val seap = LocalSeapPalette.current
    var showHelp by rememberSaveable { mutableStateOf(false) }

    if (showHelp) {
        TutorialSheet(
            title = "Seap Compressor",
            subtitle = "Dynamics processor — tames loud peaks and evens out levels.",
            sections = compressorTutorialSections(),
            onDismiss = { showHelp = false },
        )
    }

    OxfordPanel(
        title = "SEAP COMPRESSOR",
        modifier = modifier.fillMaxSize(),
        onHelpClick = { showHelp = true },
        presetRail = {
            val entries = CompressorPreset.entries
            val active = entries.indexOfFirst { matchesCompressor(it.state, state) }
            PresetRail(
                items = entries.map { it.label to (entries.indexOf(it) == active) },
                onSelect = { effect.applyPreset(entries[it]) },
            )
        },
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FaderColumn(
                label = "THRESH",
                readout = formatDb(state.thresholdDb),
                value = state.thresholdDb,
                valueRange = -60f..0f,
                onValueChange = effect::setThresholdDb,
                peakL = peak.left, peakR = peak.right,
                modifier = Modifier.weight(1f),
                meterMinDb = -60f, meterMaxDb = 0f,
            )
            FaderColumn(
                label = "RATIO",
                readout = formatRatio(state.ratio),
                value = state.ratio,
                valueRange = 1f..20f,
                onValueChange = effect::setRatio,
                peakL = 0f, peakR = 0f,
                meterStereo = false,
                modifier = Modifier.weight(0.9f),
            )
            FaderColumn(
                label = "ATTACK",
                readout = formatMs(state.attackMs),
                value = state.attackMs.coerceIn(0.1f, 200f),
                valueRange = 0.1f..200f,
                onValueChange = effect::setAttackMs,
                peakL = 0f, peakR = 0f,
                meterStereo = false,
                modifier = Modifier.weight(0.9f),
            )
            FaderColumn(
                label = "RELEASE",
                readout = formatMs(state.releaseMs),
                value = state.releaseMs,
                valueRange = 5f..2000f,
                onValueChange = effect::setReleaseMs,
                peakL = 0f, peakR = 0f,
                meterStereo = false,
                modifier = Modifier.weight(0.9f),
            )
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "GR",
                    style = TextStyle(
                        color = seap.textTitle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                    ),
                )
                Spacer(Modifier.height(4.dp))
                LcdReadout(text = "-${"%.1f".format(grDb)} dB")
                Spacer(Modifier.height(8.dp))
                LedMeter(
                    peakL = grDbToFakePeak(grDb),
                    peakR = grDbToFakePeak(grDb),
                    stereo = false,
                    minDb = -20f,
                    maxDb = 0f,
                    modifier = Modifier.weight(1f).width(14.dp),
                )
                Spacer(Modifier.height(8.dp))
                FaderColumn(
                    label = "MAKEUP",
                    readout = formatDbSigned(state.makeupDb),
                    value = state.makeupDb,
                    valueRange = -12f..24f,
                    onValueChange = effect::setMakeupDb,
                    peakL = 0f, peakR = 0f,
                    meterStereo = false,
                    modifier = Modifier.height(100.dp).fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OxfordButton(
                    label = "EFFECT\nIN",
                    active = !state.bypass,
                    activeColor = seap.btnEffectOn,
                    onClick = { effect.setBypass(!state.bypass) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun grDbToFakePeak(grDb: Float): Float {
    val displayDb = (-grDb).coerceIn(-20f, 0f)
    return 10f.pow(displayDb / 20f)
}

// ----------------------------------------------------------------------------
// Tab host
// ----------------------------------------------------------------------------

@Composable
fun OxfordEffectsTabs(
    inflator: InflatorEffect,
    compressor: CompressorEffect,
    modifier: Modifier = Modifier,
) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Compressor", "Inflator")

    // 60 Hz meter polling — keeps the LED bars fluid under fast transients.
    LaunchedEffect(inflator, compressor) {
        while (isActive) {
            inflator.pollMeters()
            compressor.pollMeters()
            delay(16L)
        }
    }

    CompositionLocalProvider(LocalSeapPalette provides rememberSeapPalette()) {
        Column(modifier = modifier.fillMaxSize()) {
            TabRow(selectedTabIndex = selected) {
                tabs.forEachIndexed { i, title ->
                    Tab(
                        selected = selected == i,
                        onClick = { selected = i },
                        text = { Text(title) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when (selected) {
                0 -> CompressorScreen(
                    effect = compressor,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
                else -> InflatorScreen(
                    effect = inflator,
                    modifier = Modifier.fillMaxSize().padding(12.dp),
                )
            }
        }
    }
}

// ----------------------------------------------------------------------------
// Formatters
// ----------------------------------------------------------------------------

private fun formatDb(db: Float): String {
    val v = if (kotlin.math.abs(db) < 0.05f) 0.0f else db
    return "${"%.1f".format(v)} dB"
}
private fun formatDbSigned(db: Float): String =
    if (db >= 0) "+${"%.1f".format(db)} dB" else "${"%.1f".format(db)} dB"
private fun formatCurve(c: Float): String = "%.1f".format(c)
private fun formatRatio(r: Float): String = "${"%.1f".format(r)}:1"
private fun formatMs(ms: Float): String = when {
    ms < 10f  -> "${"%.2f".format(ms)} ms"
    ms < 100f -> "${"%.1f".format(ms)} ms"
    else      -> "${ms.roundToInt()} ms"
}

// ----------------------------------------------------------------------------
// Preset matching (fuzzy, so tiny float noise from the UI doesn't dehighlight)
// ----------------------------------------------------------------------------

private fun matchesInflator(a: InflatorState, b: InflatorState): Boolean =
    abs(a.inputDb    - b.inputDb)   < 0.05f &&
    abs(a.outputDb   - b.outputDb)  < 0.05f &&
    abs(a.effectPct  - b.effectPct) < 0.5f  &&
    abs(a.curve      - b.curve)     < 0.5f  &&
    a.clipZeroDb == b.clipZeroDb &&
    a.bandSplit  == b.bandSplit  &&
    a.effectIn   == b.effectIn

private fun matchesCompressor(a: CompressorState, b: CompressorState): Boolean =
    abs(a.thresholdDb - b.thresholdDb) < 0.1f  &&
    abs(a.ratio       - b.ratio)       < 0.05f &&
    abs(a.attackMs    - b.attackMs)    < 0.1f  &&
    abs(a.releaseMs   - b.releaseMs)   < 0.5f  &&
    abs(a.kneeDb      - b.kneeDb)      < 0.1f  &&
    abs(a.makeupDb    - b.makeupDb)    < 0.1f  &&
    a.bypass == b.bypass

// ----------------------------------------------------------------------------
// Tutorial copy
// ----------------------------------------------------------------------------

private fun inflatorTutorialSections(): List<TutorialSection> = listOf(
    TutorialSection(
        "What it does",
        "The Inflator shapes the waveform with low-order harmonic distortion driven by the CURVE knob. Unlike a limiter, it doesn't just cap peaks — it adds perceived loudness, density, and warmth or brightness depending on the curve's sign. Keep EFFECT IN lit to hear the processing.",
    ),
    TutorialSection(
        "INPUT / OUTPUT",
        "INPUT drives the signal into the shaping stage (harder drive = more effect). OUTPUT trims the result back to unity. On mixes, nudge INPUT up and OUTPUT down to taste — they're independent, not linked.",
    ),
    TutorialSection(
        "EFFECT",
        "How much of the shaped signal is blended in (0–100 %). Below ~30 % you're in parallel territory; above 60 % the effect becomes obvious. If in doubt, sweep EFFECT while A/B-ing the EFFECT IN button.",
    ),
    TutorialSection(
        "CURVE",
        "Negative values (-50…0) push even-order harmonics — warmer, analogue-ish. Positive values (0…+50) emphasise odd-order — sharper, more present. Zero is neutral and the mildest setting.",
    ),
    TutorialSection(
        "CLIP 0 dB",
        "Hard ceiling at 0 dBFS applied on the way out. Leave on for safety when mastering; turn off if you're feeding another plugin that has its own ceiling management.",
    ),
    TutorialSection(
        "BAND SPLIT",
        "Splits the signal into low / mid / high bands and processes each independently before recombining. Produces tighter results on full mixes and drum buses. More CPU — but worth it on masters.",
    ),
    TutorialSection(
        "Start here",
        "Try the WARMTH preset on a vocal or bass, PUNCH on a drum bus, and LOUD on a finished stereo mix. Once you hear what the curve does, you'll reach for it often.",
    ),
)

private fun compressorTutorialSections(): List<TutorialSection> = listOf(
    TutorialSection(
        "What it does",
        "A compressor reduces gain whenever the signal exceeds a threshold — quieter parts pass through untouched, louder parts get pushed down. Use it to even out performances, add punch, or glue a mix together. EFFECT IN lights up when the compressor is active.",
    ),
    TutorialSection(
        "THRESH (Threshold)",
        "The level above which compression engages. Lower threshold = more of the signal is compressed. Watch the GR meter — for musical results aim for 2–6 dB reduction on peaks.",
    ),
    TutorialSection(
        "RATIO",
        "How aggressively signal above threshold is reduced. 2:1 is gentle, 4:1 is classic, 8:1+ is heavy-handed. Ratios above 10:1 start to behave like a limiter.",
    ),
    TutorialSection(
        "ATTACK",
        "How fast the compressor clamps down once the signal crosses threshold. Fast (0.1–5 ms) catches transients — kills punch if over-used. Slow (20–50 ms) lets transients through for more snap.",
    ),
    TutorialSection(
        "RELEASE",
        "How fast the gain recovers after the signal drops back below threshold. Short releases (10–80 ms) pump and breathe with the music — great for drums. Long releases (200 ms+) are smoother, better for vocals and master buses.",
    ),
    TutorialSection(
        "MAKEUP",
        "Output gain applied after compression so the processed signal matches the bypass level. Bump it up by roughly the amount of reduction shown on the GR meter.",
    ),
    TutorialSection(
        "GR meter",
        "Gain Reduction display — tells you how many dB the compressor is currently pulling down. If it's pinned, your threshold is too low or ratio too high. If it never moves, threshold is too high.",
    ),
    TutorialSection(
        "Start here",
        "VOCAL preset for leads, DRUM BUS to tighten drums, GLUE on your stereo mix bus, LIMITER only on the final master-chain output.",
    ),
)
