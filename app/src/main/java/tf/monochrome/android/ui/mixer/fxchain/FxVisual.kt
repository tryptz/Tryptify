package tf.monochrome.android.ui.mixer.fxchain

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.FxTapFrame
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.ui.mixer.getParamDefs
import tf.monochrome.android.ui.theme.MonoDimens
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import tf.monochrome.android.ui.mixer.fxchain.FxVisualMath as M

private const val TWO_PI = 2f * Math.PI.toFloat()

/** Snapin types whose display is a moving picture (LFOs, playheads, sweeps). */
private val MOTION_TYPES = setOf(
    SnapinType.CHORUS, SnapinType.ENSEMBLE, SnapinType.FLANGER, SnapinType.PHASER,
    SnapinType.RING_MOD, SnapinType.TRANCE_GATE, SnapinType.TAPE_STOP,
    SnapinType.REVERSER, SnapinType.DELAY
)

/**
 * Snapin types that do NOT get the faint live-scope underlay: Gain draws the
 * scope full-size, the dynamics family shows a level dot + gain-reduction bar
 * on its transfer curve instead, the waveshapers highlight their exercised
 * region, and the voice-LFO displays are already dense.
 */
private val NO_SCOPE_UNDERLAY = setOf(
    SnapinType.GAIN, SnapinType.COMPRESSOR, SnapinType.LIMITER, SnapinType.GATE,
    SnapinType.DYNAMICS, SnapinType.COMPACTOR, SnapinType.TRANSIENT_SHAPER,
    SnapinType.DISTORTION, SnapinType.SHAPER, SnapinType.CHORUS, SnapinType.ENSEMBLE
)

/**
 * Theme-resolved colors for one visualization panel. The base surface and grid
 * track `MaterialTheme` (so the panel follows the OS/app theme like the rest of
 * the FX chain); only the curve carries the category neon accent.
 */
private class FxVisualStyle(
    val curve: Color,
    val glow: Color,
    val grid: Color,
    val ref: Color,
    val dim: Float,
)

/**
 * Visualization panel for one effect in the FX chain.
 *
 * Every display is a deterministic function of the plugin's parameters —
 * filters draw their frequency response, dynamics their transfer curve,
 * distortions their waveshape, time effects their echo/decay pattern — so the
 * picture *is* the DSP. Knob moves glide in via a spring on each continuous
 * parameter (stepped selectors snap), and effects with inherent motion
 * (LFO modulation, gate patterns, tape/reverse playheads) animate at the speed
 * their Rate/Time knobs dictate. Motion pauses while the snapin is bypassed
 * and the whole drawing dims.
 *
 * When [live] (the bus audio tap) is present, the panel reacts to the actual
 * signal: the neon glow breathes with the plugin's output level, dynamics get
 * a level dot riding the transfer curve plus a gain-reduction bar, waveshapers
 * highlight the region of the curve the signal is exercising, Gain shows the
 * live bus waveform, and most other panels get a faint scope underlay of the
 * real audio.
 */
@Composable
internal fun FxVisual(
    plugin: PluginInstance,
    accent: Color,
    modifier: Modifier = Modifier,
    slotIndex: Int = -1,
    live: FxTapFrame? = null,
) {
    val type = plugin.type ?: return
    val defs = getParamDefs(type)
    val raw = plugin.parameters

    // Spring-smooth continuous parameters so curves morph instead of jumping;
    // stepped/enum parameters snap so e.g. a filter type flips instantly.
    val spec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
    }
    val values = ArrayList<Float>(defs.size)
    for (i in defs.indices) {
        val def = defs[i]
        val target = raw[i] ?: def.default
        values += if (def.steps != null) target
        else animateFloatAsState(target, spec, label = "fxVisualParam$i").value
    }

    val clock = rememberFxClock(running = type in MOTION_TYPES && !plugin.bypassed)

    // Live tap values for this slot (null while bypassed so overlays vanish
    // and the panel falls back to its static, dimmed look).
    val tapped = live != null && slotIndex >= 0 && !plugin.bypassed
    val liveInDb = if (tapped) live!!.inDb(slotIndex) else null
    val liveOutDb = if (tapped) live!!.outDb(slotIndex) else null
    // 0 at −55 dB and below, 1 at 0 dB — drives the signal-reactive glow.
    val activity = liveOutDb?.let { ((it + 55f) / 55f).coerceIn(0f, 1f) }

    val cs = MaterialTheme.colorScheme
    val dim = if (plugin.bypassed) 0.35f else 1f
    val glowBreath = activity?.let { 0.6f + 0.8f * it } ?: 1f
    val style = FxVisualStyle(
        curve = accent.copy(alpha = dim),
        glow = accent.copy(alpha = (0.22f * dim * glowBreath).coerceAtMost(0.45f)),
        grid = cs.onSurfaceVariant.copy(alpha = 0.12f),
        ref = cs.onSurfaceVariant.copy(alpha = 0.30f),
        dim = dim,
    )
    val panelBg = cs.surfaceContainerLowest.copy(alpha = 0.65f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(76.dp)
            .clip(MonoDimens.shapeSm)
            .background(panelBg)
    ) {
        val p: (Int) -> Float = { i -> values.getOrElse(i) { 0f } }
        val time = clock.value
        drawPanelGrid(style)
        // Faint scope of the actual bus audio behind the schematic curve.
        if (tapped && type !in NO_SCOPE_UNDERLAY) {
            drawScopeTap(style, live!!.wave, live.waveLen,
                centerFrac = 0.76f, ampFrac = 0.17f, alpha = 0.30f)
        }
        when (type) {
            // ── Utility ──
            SnapinType.GAIN -> drawGain(style, p, if (tapped) live else null)
            SnapinType.STEREO -> drawStereo(style, p)
            SnapinType.CHANNEL_MIXER -> drawChannelMixer(style, p)
            SnapinType.HAAS -> drawHaas(style, p)
            // ── EQ & Filter ──
            SnapinType.FILTER -> drawResponse(style) { f ->
                M.filterDb(p(0).toInt(), f, p(1), p(2), p(3), p(4).toInt())
            }
            SnapinType.EQ_3BAND -> drawResponse(style) { f ->
                M.biquadDb(M.BiquadShape.LOWSHELF, f, p(0), p(2), p(1)) +
                    M.biquadDb(M.BiquadShape.PEAK, f, p(3), p(5), p(4)) +
                    M.biquadDb(M.BiquadShape.HIGHSHELF, f, p(6), p(8), p(7))
            }
            SnapinType.EQ_10BAND -> drawResponse(style) { f -> eq10Db(f, p) }
            SnapinType.COMB_FILTER -> drawResponse(style, dbRange = 14f) { f ->
                M.combDb(f, p(0), p(1), p(2) > 0.5f)
            }
            SnapinType.FORMANT_FILTER -> drawResponse(style, dbRange = 36f) { f ->
                M.formantDb(f, p(0), p(1), p(2), p(3), p(4))
            }
            SnapinType.LADDER_FILTER -> drawResponse(style) { f -> M.ladderDb(f, p(0), p(1)) }
            SnapinType.NONLINEAR_FILTER -> drawResponse(style) { f ->
                val shape = M.NONLINEAR_TYPE_MAP[p(0).toInt().coerceIn(0, 3)]
                M.biquadDb(shape, f, p(1), p(2))
            }
            SnapinType.RESONATOR -> drawResonator(style, p)
            // ── Dynamics ──
            SnapinType.COMPRESSOR -> drawTransfer(style, threshDb = p(3), liveInDb = liveInDb) { inDb ->
                M.compressorOutDb(inDb, p(3), p(2), p(4), p(5))
            }
            SnapinType.LIMITER -> drawTransfer(style, threshDb = p(1), liveInDb = liveInDb) { inDb ->
                M.limiterOutDb(inDb, p(0), p(1), p(4))
            }
            SnapinType.GATE -> drawTransfer(style, threshDb = p(3), liveInDb = liveInDb) { inDb ->
                val out = M.gateOutDb(inDb, p(3), p(4), p(5))
                if (p(7) > 0.5f) inDb - (p(5) - (inDb - out)) else out
            }
            SnapinType.DYNAMICS -> drawTransfer(style, liveInDb = liveInDb) { inDb ->
                val level = inDb + p(7)
                level + M.dynamicsGainDb(level, p(0), p(1), p(2), p(3), p(6)) + p(8)
            }
            SnapinType.COMPACTOR -> drawTransfer(style, threshDb = p(3), liveInDb = liveInDb) { inDb ->
                M.compactorOutDb(inDb, p(3), p(5))
            }
            SnapinType.TRANSIENT_SHAPER -> drawTransientShaper(style, p)
            SnapinType.TRANCE_GATE -> drawTranceGate(style, p, time)
            // ── Distortion ──
            SnapinType.DISTORTION -> drawShaperCurve(style, liveInDb = liveInDb) { x ->
                M.distortionShape(p(1).toInt(), x * M.dbToLin(p(0)) + p(3))
            }
            SnapinType.SHAPER -> drawShaperCurve(style, liveInDb = liveInDb) { x ->
                M.shaperOverflow(p(2).toInt(), x * M.dbToLin(p(0)))
            }
            SnapinType.BITCRUSH -> drawBitcrush(style, p)
            SnapinType.PHASE_DISTORTION -> drawWave(style) { t ->
                M.phaseDistortion(TWO_PI * 2f * t, p(0), p(3))
            }
            // ── Modulation ──
            SnapinType.CHORUS -> drawVoiceLfos(
                style, voices = p(3).toInt(), depthPct = p(2), rateHz = p(1),
                spreadPct = p(4), lfoPhase = TWO_PI * p(1) * time
            )
            SnapinType.ENSEMBLE -> drawVoiceLfos(
                style, voices = p(0).toInt(), depthPct = 40f + p(1) * 0.5f,
                rateHz = 1f, spreadPct = p(2), detunePct = p(1),
                lfoPhase = TWO_PI * 0.4f * (1f + p(4)) * time
            )
            SnapinType.FLANGER -> {
                // The LFO sweeps the delay, so the comb notches breathe at Rate.
                val lfo = sin(TWO_PI * p(2) * time)
                val delayMs = (p(0) * (1f + 0.85f * (p(1) / 100f) * lfo)).coerceAtLeast(0.05f)
                drawResponse(style, dbRange = 18f) { f -> flangerDb(f, delayMs, p(3)) }
            }
            SnapinType.PHASER -> {
                // Notch cluster glides around Center at Rate, scaled by Depth.
                val sweep = 2f.pow((p(2) / 100f) * 1.2f * sin(TWO_PI * p(1) * time))
                val center = (p(3) * sweep).coerceIn(M.MIN_FREQ, M.MAX_FREQ)
                drawResponse(style) { f -> M.phaserDb(f, p(0).toInt(), center, p(2), p(4)) }
            }
            SnapinType.FREQUENCY_SHIFTER -> drawFrequencyShifter(style, p)
            SnapinType.PITCH_SHIFTER -> drawPitchShifter(style, p)
            SnapinType.RING_MOD -> drawRingMod(style, p, time)
            SnapinType.TAPE_STOP -> drawTapeStop(style, p, time)
            // ── Space ──
            SnapinType.DELAY -> drawDelay(style, p, time)
            SnapinType.REVERB -> drawReverb(style, p)
            SnapinType.REVERSER -> drawReverser(style, p, time)
        }
    }
}

/**
 * Monotonic seconds counter driven by the frame clock. Ticks only while
 * [running]; freezes (and stops invalidating frames) otherwise.
 */
@Composable
private fun rememberFxClock(running: Boolean): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time.floatValue += (now - last) / 1e9f
                last = now
            }
        }
    }
    return time
}

// ─────────────────────────────────────────────────────────────────────────────
// Shared drawing primitives
// ─────────────────────────────────────────────────────────────────────────────

private fun DrawScope.drawPanelGrid(s: FxVisualStyle) {
    for (i in 1..3) {
        val x = size.width * i / 4f
        drawLine(s.grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
    }
    drawLine(
        s.grid, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f),
        strokeWidth = 1f
    )
}

/** Stroke [path] as a neon curve: a wide soft halo pass under a crisp accent pass. */
private fun DrawScope.strokeNeon(path: Path, s: FxVisualStyle, alpha: Float = 1f) {
    drawPath(
        path, s.glow.copy(alpha = s.glow.alpha * alpha),
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
    drawPath(
        path, s.curve.copy(alpha = s.curve.alpha * alpha),
        style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )
}

/** Fill the region between the curve [path] (closed to the bottom) and the panel floor. */
private fun DrawScope.fillUnder(path: Path, s: FxVisualStyle) {
    val fill = Path().apply {
        addPath(path)
        lineTo(size.width, size.height)
        lineTo(0f, size.height)
        close()
    }
    drawPath(
        fill,
        Brush.verticalGradient(
            0f to s.curve.copy(alpha = 0.25f * s.dim),
            1f to s.curve.copy(alpha = 0.02f * s.dim)
        )
    )
}

/**
 * Generic curve across the panel: [y] maps normalized x position 0..1 to a
 * normalized y position 0..1 (0 = top).
 */
private fun DrawScope.drawCurve(
    s: FxVisualStyle,
    samples: Int = 110,
    fill: Boolean = true,
    y: (t: Float) -> Float,
) {
    val path = Path()
    for (i in 0..samples) {
        val t = i / samples.toFloat()
        val py = y(t).coerceIn(0f, 1f) * size.height
        if (i == 0) path.moveTo(0f, py) else path.lineTo(t * size.width, py)
    }
    if (fill) fillUnder(path, s)
    strokeNeon(path, s)
}

/**
 * Live oscilloscope of the bus tap: per-column min/max bands of the actual
 * audio, drawn around `centerFrac` with `ampFrac` half-height.
 */
private fun DrawScope.drawScopeTap(
    s: FxVisualStyle,
    wave: FloatArray,
    waveLen: Int,
    centerFrac: Float = 0.5f,
    ampFrac: Float = 0.40f,
    alpha: Float = 1f,
) {
    if (waveLen <= 0) return
    val buckets = 96
    val mm = M.waveMinMax(wave, waveLen, buckets)
    val cy = centerFrac * size.height
    val amp = ampFrac * size.height
    val colW = size.width / buckets
    val minLen = 0.75.dp.toPx()
    val col = s.curve.copy(alpha = s.curve.alpha * alpha)
    for (k in 0 until buckets) {
        val lo = mm[k * 2].coerceIn(-1f, 1f)
        val hi = mm[k * 2 + 1].coerceIn(-1f, 1f)
        val x = (k + 0.5f) * colW
        var yTop = cy - hi * amp
        var yBot = cy - lo * amp
        if (yBot - yTop < minLen) {  // keep silence visible as a hairline
            val mid = (yTop + yBot) / 2f
            yTop = mid - minLen / 2f
            yBot = mid + minLen / 2f
        }
        drawLine(col, Offset(x, yTop), Offset(x, yBot), strokeWidth = colW * 0.65f)
    }
}

/** Log-frequency response curve; [db] gives magnitude at each frequency. */
private fun DrawScope.drawResponse(
    s: FxVisualStyle,
    dbRange: Float = 30f,
    db: (freq: Float) -> Float,
) {
    // 0 dB reference
    drawLine(
        s.ref,
        Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    )
    drawCurve(s) { t ->
        0.5f - (db(M.freqAt(t)).coerceIn(-dbRange, dbRange) / dbRange) * 0.46f
    }
}

/**
 * Dynamics transfer curve: input −60..0 dB (x) vs output −60..0 dB (y), over a
 * faint unity diagonal. [threshDb], when finite, draws a threshold marker.
 * With a live tap, a dot rides the curve at the current input level and a
 * gain-reduction bar grows from the top-right corner.
 */
private fun DrawScope.drawTransfer(
    s: FxVisualStyle,
    threshDb: Float = Float.NaN,
    liveInDb: Float? = null,
    outDb: (inDb: Float) -> Float,
) {
    drawLine(
        s.ref,
        Offset(0f, size.height), Offset(size.width, 0f),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
    )
    if (!threshDb.isNaN()) {
        val x = ((threshDb + 60f) / 60f).coerceIn(0f, 1f) * size.width
        drawLine(
            s.curve.copy(alpha = 0.30f * s.dim), Offset(x, 0f), Offset(x, size.height),
            strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
        )
    }
    val yFor: (Float) -> Float = { inDb ->
        1f - ((outDb(inDb).coerceIn(-60f, 0f) + 60f) / 60f)
    }
    drawCurve(s) { t -> yFor(-60f + 60f * t) }

    if (liveInDb != null && liveInDb > -59f) {
        // Level dot riding the transfer curve at the current input level.
        val t = ((liveInDb + 60f) / 60f).coerceIn(0f, 1f)
        val center = Offset(t * size.width, yFor(liveInDb).coerceIn(0f, 1f) * size.height)
        drawCircle(s.glow, radius = 5.dp.toPx(), center = center)
        drawCircle(s.curve, radius = 2.5.dp.toPx(), center = center)
        // Gain-reduction bar: how far below unity the curve pushes this level.
        val gr = (liveInDb - outDb(liveInDb)).coerceAtLeast(0f)
        if (gr > 0.05f) {
            val barW = (gr / 24f).coerceAtMost(1f) * size.width * 0.35f
            drawLine(
                s.curve.copy(alpha = 0.85f * s.dim),
                Offset(size.width - barW, 3.dp.toPx()),
                Offset(size.width, 3.dp.toPx()),
                strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
            )
        }
    }
}

/** Bipolar waveform: [y] returns −1..1 over normalized time 0..1. */
private fun DrawScope.drawWave(
    s: FxVisualStyle,
    samples: Int = 160,
    alpha: Float = 1f,
    fill: Boolean = false,
    y: (t: Float) -> Float,
) {
    val path = Path()
    for (i in 0..samples) {
        val t = i / samples.toFloat()
        val py = (0.5f - y(t).coerceIn(-1.15f, 1.15f) * 0.40f) * size.height
        if (i == 0) path.moveTo(0f, py) else path.lineTo(t * size.width, py)
    }
    if (fill) fillUnder(path, s)
    strokeNeon(path, s, alpha)
}

/**
 * Waveshaper transfer curve: input −1..1 (x) → output −1..1 (y), over a faint
 * identity diagonal — the classic distortion transfer display. With a live
 * tap, the segment of the curve the signal is currently exercising
 * (|x| ≤ input peak) is restroked brighter with end-cap dots.
 */
private fun DrawScope.drawShaperCurve(
    s: FxVisualStyle,
    liveInDb: Float? = null,
    shape: (x: Float) -> Float,
) {
    drawLine(
        s.ref,
        Offset(0f, size.height), Offset(size.width, 0f),
        strokeWidth = 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f))
    )
    val yFor: (Float) -> Float = { x -> 0.5f - shape(x).coerceIn(-1.25f, 1.25f) * 0.40f }
    drawCurve(s, samples = 220, fill = false) { t -> yFor(t * 2f - 1f) }

    if (liveInDb != null && liveInDb > -59f) {
        val inLin = M.dbToLin(liveInDb).coerceIn(0.01f, 1f)
        // Restroke the exercised region brighter.
        val active = Path()
        val steps = 60
        for (i in 0..steps) {
            val x = -inLin + (2f * inLin) * i / steps
            val px = (x + 1f) / 2f * size.width
            val py = yFor(x).coerceIn(0f, 1f) * size.height
            if (i == 0) active.moveTo(px, py) else active.lineTo(px, py)
        }
        drawPath(
            active, s.glow,
            style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        drawPath(
            active, s.curve,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
        for (x in floatArrayOf(-inLin, inLin)) {
            drawCircle(
                s.curve, radius = 2.5.dp.toPx(),
                center = Offset((x + 1f) / 2f * size.width, yFor(x).coerceIn(0f, 1f) * size.height)
            )
        }
    }
}

/** One vertical spike rising from [baseY] (defaults to the panel floor). */
private fun DrawScope.drawSpike(
    s: FxVisualStyle,
    x: Float,
    height: Float,
    alpha: Float = 1f,
    baseY: Float = size.height,
    up: Boolean = true,
) {
    val h = height.coerceIn(0f, 1f) * (size.height * 0.85f)
    val yEnd = if (up) baseY - h else baseY + h
    drawLine(
        s.glow.copy(alpha = s.glow.alpha * alpha),
        Offset(x, baseY), Offset(x, yEnd),
        strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round
    )
    drawLine(
        s.curve.copy(alpha = s.curve.alpha * alpha),
        Offset(x, baseY), Offset(x, yEnd),
        strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Utility snapins
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Gain: with a live tap, a full-size oscilloscope of the actual bus audio
 * (post-fader, so the knob visibly scales it) over a faint gain-scaled sine;
 * without one, the schematic reference sine vs the gain-scaled copy.
 */
private fun DrawScope.drawGain(s: FxVisualStyle, p: (Int) -> Float, live: FxTapFrame?) {
    val amp = M.dbToLin(p(0)).coerceAtMost(4f)
    if (live != null) {
        drawWave(s, alpha = 0.15f) { t -> (sin(TWO_PI * 2f * t) * amp).coerceIn(-1.12f, 1.12f) }
        drawScopeTap(s, live.wave, live.waveLen, centerFrac = 0.5f, ampFrac = 0.42f)
    } else {
        drawWave(s, alpha = 0.20f) { t -> sin(TWO_PI * 2f * t) }
        drawWave(s) { t -> (sin(TWO_PI * 2f * t) * amp).coerceIn(-1.12f, 1.12f) }
    }
}

/** Stereo: goniometer-style ellipse — width sets X radius, mid sets Y, pan offsets. */
private fun DrawScope.drawStereo(s: FxVisualStyle, p: (Int) -> Float) {
    val midR = (M.dbToLin(p(0)).coerceAtMost(2f)) * size.height * 0.38f
    val sideR = (M.dbToLin(p(1)).coerceAtMost(2f)) * size.width * 0.22f
    val cx = size.width / 2f + p(2) * size.width * 0.22f
    val cy = size.height / 2f
    drawLine(s.grid, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), 1f)
    val path = Path()
    val n = 72
    for (i in 0..n) {
        val a = TWO_PI * i / n
        val px = cx + cos(a) * sideR
        val py = cy + sin(a) * midR
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    path.close()
    drawPath(path, s.curve.copy(alpha = 0.12f * s.dim))
    strokeNeon(path, s)
    drawCircle(s.curve, radius = 2.dp.toPx(), center = Offset(cx, cy))
}

/** Channel mixer: four signed bars (L→L, R→L, L→R, R→R) around the center line. */
private fun DrawScope.drawChannelMixer(s: FxVisualStyle, p: (Int) -> Float) {
    val cy = size.height / 2f
    val barW = size.width * 0.10f
    for (i in 0..3) {
        val v = p(i).coerceIn(-1f, 1f)
        val x = size.width * (0.125f + 0.25f * i) - barW / 2f
        val h = abs(v) * size.height * 0.42f
        val top = if (v >= 0f) cy - h else cy
        drawRect(
            Brush.verticalGradient(
                0f to s.curve.copy(alpha = (if (v >= 0f) 0.85f else 0.45f) * s.dim),
                1f to s.curve.copy(alpha = 0.10f * s.dim),
                startY = top, endY = top + h
            ),
            topLeft = Offset(x, top),
            size = Size(barW, h.coerceAtLeast(1.5f))
        )
    }
    drawLine(s.ref, Offset(0f, cy), Offset(size.width, cy), 1f)
}

/** Haas: L/R sine bursts in the top/bottom half, one delayed horizontally. */
private fun DrawScope.drawHaas(s: FxVisualStyle, p: (Int) -> Float) {
    val shift = (p(1) / 30f) * 0.20f
    val delayLeft = p(0) < 0.5f
    fun halfWave(centerFrac: Float, offset: Float, alpha: Float) {
        val path = Path()
        val n = 130
        for (i in 0..n) {
            val t = i / n.toFloat()
            val phase = (t - offset) * TWO_PI * 3f
            val py = (centerFrac - sin(phase) * 0.18f) * size.height
            if (i == 0) path.moveTo(0f, py) else path.lineTo(t * size.width, py)
        }
        strokeNeon(path, s, alpha)
    }
    halfWave(0.28f, if (delayLeft) shift else 0f, if (delayLeft) 1f else 0.45f)
    halfWave(0.72f, if (delayLeft) 0f else shift, if (delayLeft) 0.45f else 1f)
}

// ─────────────────────────────────────────────────────────────────────────────
// EQ & Filter snapins
// ─────────────────────────────────────────────────────────────────────────────

/** Summed response of the 10-band EQ (preamp + all enabled bands). */
private fun eq10Db(f: Float, p: (Int) -> Float): Float {
    var db = p(0)  // preamp
    for (band in 0 until 10) {
        val base = 1 + band * 5
        if (p(base + 4) < 0.5f) continue  // band off
        val shape = when (p(base + 3).toInt()) {
            1 -> M.BiquadShape.LOWSHELF
            2 -> M.BiquadShape.HIGHSHELF
            else -> M.BiquadShape.PEAK
        }
        db += M.biquadDb(shape, f, p(base), p(base + 2), p(base + 1))
    }
    return db
}

/** Flanger comb response: H = 1 + d·e^{-jωτ} resonated by feedback. */
private fun flangerDb(f: Float, delayMs: Float, feedbackPct: Float): Float {
    val tau = delayMs / 1000f
    val d = 0.9f
    val fb = (feedbackPct / 100f).coerceIn(-0.95f, 0.95f)
    val theta = TWO_PI * f * tau
    val numRe = 1f + d * cos(theta)
    val numIm = d * sin(theta)
    val denRe = 1f - fb * cos(theta)
    val denIm = fb * sin(theta)
    val mag = hypot(numRe, numIm) / hypot(denRe, denIm).coerceAtLeast(1e-4f)
    return M.linToDb(mag.coerceAtLeast(1e-5f))
}

/** Resonator: spikes at the harmonics of the tuned pitch. */
private fun DrawScope.drawResonator(s: FxVisualStyle, p: (Int) -> Float) {
    val f0 = M.midiToHz(p(0))
    val decay = 0.45f + (p(1) / 100f) * 0.5f          // per-harmonic rolloff
    val intensity = (p(2) / 100f).coerceIn(0f, 1f)
    val timbre = p(3).coerceIn(0f, 1f)
    for (k in 1..12) {
        val fk = f0 * k
        if (fk > M.MAX_FREQ) break
        var h = (0.25f + 0.75f * intensity) * decay.pow(k - 1)
        if (k % 2 == 0) h *= 0.35f + 0.65f * timbre   // timbre morphs even harmonics in
        drawSpike(s, M.freqToT(fk) * size.width, h, alpha = 0.5f + 0.5f / k)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dynamics snapins
// ─────────────────────────────────────────────────────────────────────────────

/** Transient shaper: faint original envelope vs the shaped attack/sustain envelope. */
private fun DrawScope.drawTransientShaper(s: FxVisualStyle, p: (Int) -> Float) {
    val attack = p(0) / 100f          // −1..1
    val pump = p(1) / 100f            // 0..1
    val sustain = p(2) / 100f         // −1..1

    // Base envelope: instant attack, decay to a sustain shelf, then tail off.
    val base: (Float) -> Float = { t ->
        when {
            t < 0.06f -> t / 0.06f
            else -> {
                val d = (t - 0.06f) / 0.94f
                0.34f + 0.66f * exp(-d * 5f)
            }
        }
    }
    val shaped: (Float) -> Float = { t ->
        val e = base(t)
        val attackRegion = exp(-((t - 0.06f).coerceAtLeast(0f)) * 18f)
        val sustainRegion = 1f - attackRegion
        val pumpSwell = if (t > 0.12f) pump * 0.35f * (1f - exp(-(t - 0.12f) * 6f)) else 0f
        (e * (1f + attack * 0.9f * attackRegion) * (1f + sustain * 0.8f * sustainRegion) + pumpSwell)
            .coerceIn(0f, 1.15f)
    }
    // Faint reference envelope, then the shaped envelope on top.
    val basePath = Path()
    val n = 130
    for (i in 0..n) {
        val t = i / n.toFloat()
        val py = (0.92f - base(t) * 0.72f) * size.height
        if (i == 0) basePath.moveTo(0f, py) else basePath.lineTo(t * size.width, py)
    }
    strokeNeon(basePath, s, alpha = 0.25f)
    drawCurve(s) { t -> 0.92f - shaped(t) * 0.72f }
}

/**
 * Trance gate: the actual step pattern with sustain level and, while running,
 * a 120 BPM playhead stepping at the selected resolution.
 */
private fun DrawScope.drawTranceGate(s: FxVisualStyle, p: (Int) -> Float, time: Float) {
    val pattern = M.TRANCE_PATTERNS[p(0).toInt().coerceIn(0, M.TRANCE_PATTERNS.size - 1)]
    val length = p(1).toInt().coerceIn(1, 32)
    val sustain = (p(4) / 100f).coerceIn(0f, 1f)
    // Native runs at 120 BPM: resolution 0..3 = 1/4..1/32 → 2/4/8/16 steps per second.
    val stepsPerSec = 2f * 2f.pow(p(7).toInt().coerceIn(0, 3))
    val playStep = (floor(time * stepsPerSec).toInt()).mod(length)
    val stepW = size.width / length
    val floorY = size.height * 0.92f
    for (step in 0 until length) {
        val on = pattern[step]
        val active = step == playStep
        val h = if (on) (0.25f + 0.65f * sustain) * size.height else size.height * 0.05f
        val topAlpha = (if (on) 0.85f else 0.15f) * (if (active) 1.2f else 1f)
        val x = step * stepW
        val top = floorY - h
        drawRect(
            Brush.verticalGradient(
                0f to s.curve.copy(alpha = (topAlpha * s.dim).coerceAtMost(1f)),
                1f to s.curve.copy(alpha = (if (on) 0.15f else 0.04f) * s.dim),
                startY = top, endY = floorY
            ),
            topLeft = Offset(x + stepW * 0.08f, top),
            size = Size(stepW * 0.84f, h)
        )
        if (active) {
            drawRect(
                s.curve.copy(alpha = 0.10f * s.dim),
                topLeft = Offset(x, 0f),
                size = Size(stepW, size.height)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Distortion snapins
// ─────────────────────────────────────────────────────────────────────────────

/** Bitcrush: sample-held, bit-quantized sine. */
private fun DrawScope.drawBitcrush(s: FxVisualStyle, p: (Int) -> Float) {
    val steps = (4f + (p(0) / M.SAMPLE_RATE) * 60f).toInt().coerceIn(4, 64)
    drawWave(s, alpha = 0.18f) { t -> sin(TWO_PI * 2f * t) }
    drawWave(s, samples = 240) { t ->
        val held = floor(t * steps) / steps
        M.bitcrushQuantize(sin(TWO_PI * 2f * held), p(1))
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Modulation snapins
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Chorus/Ensemble: one LFO trace per voice, phase-spread and detuned, drifting
 * at the actual modulation rate via [lfoPhase].
 */
private fun DrawScope.drawVoiceLfos(
    s: FxVisualStyle,
    voices: Int,
    depthPct: Float,
    rateHz: Float,
    spreadPct: Float,
    detunePct: Float = 0f,
    lfoPhase: Float = 0f,
) {
    val v = voices.coerceIn(1, 8)
    val depth = (depthPct / 100f).coerceIn(0f, 1f) * 0.85f
    val cycles = 1.5f + (rateHz / 10f).coerceIn(0f, 1f) * 3f
    val spread = (spreadPct / 100f).coerceIn(0f, 1f)
    for (k in 0 until v) {
        val centered = if (v == 1) 0f else k / (v - 1f) - 0.5f     // −0.5..0.5
        val phase = centered * spread * TWO_PI
        val detune = 1f + centered * (detunePct / 100f) * 0.35f
        drawWave(s, alpha = 0.35f + 0.65f * (1f - abs(centered) * 1.2f).coerceIn(0f, 1f)) { t ->
            sin(TWO_PI * cycles * detune * t + phase + lfoPhase) * depth
        }
    }
}

/** Frequency shifter: ghost harmonic stack vs the linearly shifted copy. */
private fun DrawScope.drawFrequencyShifter(s: FxVisualStyle, p: (Int) -> Float) {
    val baseHz = 220f
    for (k in 1..5) {
        val orig = baseHz * k
        val height = 1f / k.toFloat().pow(0.6f)
        drawSpike(s, M.freqToT(orig) * size.width, height * 0.85f, alpha = 0.20f)
        val shifted = orig + p(0)
        if (shifted in M.MIN_FREQ..M.MAX_FREQ) {
            drawSpike(s, M.freqToT(shifted) * size.width, height * 0.85f)
        }
    }
}

/** Pitch shifter: faint dry sine vs the ratio-scaled shifted sine, jitter-wobbled. */
private fun DrawScope.drawPitchShifter(s: FxVisualStyle, p: (Int) -> Float) {
    val ratio = M.semitonesToRatio(p(0))
    val jitter = p(1) / 100f
    drawWave(s, alpha = 0.20f) { t -> sin(TWO_PI * 2f * t) }
    drawWave(s) { t ->
        val wobble = 1f + jitter * 0.15f * sin(TWO_PI * 7.3f * t)
        sin(TWO_PI * 2f * ratio * t * wobble)
    }
}

/** Ring mod: audio sine multiplied by the (bias-lifted, rectify-morphed) carrier. */
private fun DrawScope.drawRingMod(s: FxVisualStyle, p: (Int) -> Float, time: Float) {
    // Map carrier 1..5000 Hz onto 3..30 visual cycles (log-ish), scrolling gently.
    val carrierCycles = 3f + 27f * (ln(p(0).coerceIn(1f, 5000f)) / ln(5000f)).coerceIn(0f, 1f)
    val bias = (p(1) / 100f).coerceIn(0f, 1f)
    val rect = (p(2) / 100f).coerceIn(-1f, 1f)
    val scroll = TWO_PI * 1.2f * time
    drawWave(s, samples = 300, fill = true) { t ->
        var c = sin(TWO_PI * carrierCycles * t + scroll)
        c = if (rect >= 0f) c * (1f - rect) + abs(c) * rect
        else c * (1f + rect) - abs(c) * (-rect)
        c = bias + (1f - bias) * c
        sin(TWO_PI * 1.5f * t) * c
    }
}

/**
 * Tape stop: playback-speed ramp. While playing, a flat line with gentle tape
 * flutter; while stopped, the dive curve with a playhead riding down it at the
 * Stop-time loop.
 */
private fun DrawScope.drawTapeStop(s: FxVisualStyle, p: (Int) -> Float, time: Float) {
    val playing = p(0) > 0.5f
    val gamma = 0.35f + (p(3) / 100f) * 2.2f
    if (playing) {
        drawCurve(s) { t -> 0.12f + 0.015f * sin(TWO_PI * (3f * t - 1.1f * time)) }
        return
    }
    val speedAt: (Float) -> Float = { t -> (1f - t).pow(gamma) }
    drawCurve(s) { t -> 0.90f - speedAt(t) * 0.78f }
    // Playhead loops along the dive at the configured stop time (+ short rest).
    val cycle = (p(1) / 1000f).coerceAtLeast(0.05f)
    val prog = ((time.mod(cycle + 0.5f)) / cycle).coerceAtMost(1f)
    drawCircle(
        s.curve, radius = 2.5.dp.toPx(),
        center = Offset(prog * size.width, (0.90f - speedAt(prog) * 0.78f) * size.height)
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Space snapins
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Delay: dry hit + feedback-decaying echo taps, alternating sides in ping-pong,
 * with a repeat-synced pulse rippling down the tap line.
 */
private fun DrawScope.drawDelay(s: FxVisualStyle, p: (Int) -> Float, time: Float) {
    val spacing = (p(0) / 2000f).coerceIn(0.02f, 1f) * 0.42f
    val fb = (p(1) / 100f).coerceIn(0f, 0.97f)
    val pingPong = p(2) > 0.5f
    val duck = (p(4) / 100f).coerceIn(0f, 1f)
    val period = (p(0) / 1000f).coerceAtLeast(0.05f)
    val cy = size.height / 2f
    if (pingPong) {
        drawLine(s.grid, Offset(0f, cy), Offset(size.width, cy), 1f)
    }
    var x = 0.05f
    var h = 1f
    var k = 0
    while (x < 0.98f && h > 0.02f && k < 24) {
        val ducked = if (k == 0) h else h * (1f - duck * 0.6f * exp(-(k - 1) * 0.8f))
        // Pulse: the tap whose turn it is glows a little brighter.
        val active = floor(time / period).toInt().mod(24) == k
        val alpha = (if (k == 0) 1f else 0.8f) * (if (active) 1.25f else 1f)
        if (pingPong && k > 0) {
            drawSpike(s, x * size.width, ducked * 0.5f, baseY = cy, up = k % 2 == 1,
                alpha = alpha.coerceAtMost(1f))
        } else {
            drawSpike(s, x * size.width, ducked, alpha = alpha.coerceAtMost(1f))
        }
        x += spacing
        h *= if (k == 0) fb.coerceAtLeast(0.03f) else fb
        k++
    }
}

/** Reverb: pre-delay gap, early-reflection cluster, then the exponential tail. */
private fun DrawScope.drawReverb(s: FxVisualStyle, p: (Int) -> Float) {
    val pre = (p(0) / 500f) * 0.18f
    val decayNorm = (p(1) / 30f).coerceIn(0.005f, 1f)
    val size01 = (p(2) / 100f).coerceIn(0f, 1f)
    val damp = (p(3) / 100f).coerceIn(0f, 1f)
    val diffuse = (p(4) / 100f).coerceIn(0f, 1f)
    val early = (p(9) / 100f).coerceIn(0f, 1f)

    drawSpike(s, 0.03f * size.width, 1f)  // dry impulse

    // Early reflections: fixed pseudo-random comb inside the size-scaled window
    val erPositions = floatArrayOf(0.012f, 0.03f, 0.041f, 0.058f, 0.072f, 0.088f)
    val erCount = (2 + (diffuse * 4)).toInt().coerceIn(2, 6)
    for (i in 0 until erCount) {
        val x = 0.05f + pre + erPositions[i] * (0.6f + size01)
        drawSpike(s, x * size.width, early * (0.7f - i * 0.08f), alpha = 0.65f)
    }

    // Tail: exponential decay envelope, tilted down further by damping
    val tailStart = 0.08f + pre
    val tau = 0.06f + decayNorm * (0.5f + size01 * 0.8f)
    drawCurve(s) { t ->
        if (t < tailStart) 1f
        else {
            val d = (t - tailStart)
            val env = exp(-d / tau) * exp(-d * damp * 2.2f) * 0.8f
            0.92f - env * 0.80f
        }
    }
}

/**
 * Reverser: playback ramps running backwards inside each buffer window, with a
 * scan head sweeping right-to-left through the active window in real time.
 */
private fun DrawScope.drawReverser(s: FxVisualStyle, p: (Int) -> Float, time: Float) {
    val window = ((p(0) / 2000f).coerceIn(0.025f, 1f) * 0.9f + 0.08f)
    val xfade = (p(2) / 100f).coerceIn(0.01f, 0.5f)
    val heightAt: (Float) -> Float = { local ->
        val ramp = 1f - local
        val fadeIn = min(1f, local / xfade)
        val fadeOut = min(1f, (1f - local) / xfade)
        ramp * min(fadeIn, fadeOut).coerceIn(0f, 1f)
    }
    drawCurve(s, samples = 200) { t ->
        val local = (t / window).let { it - floor(it) }  // 0..1 inside the window
        0.90f - heightAt(local) * 0.75f
    }
    // Scan head: runs backwards through consecutive windows at Time speed.
    val windowSec = (p(0) / 1000f).coerceAtLeast(0.05f)
    val windows = (1f / window).toInt() + 1
    val cycleIdx = floor(time / windowSec).toInt().mod(windows)
    val frac = 1f - (time / windowSec - floor(time / windowSec))  // 1 → 0, backwards
    val tHead = ((cycleIdx + frac) * window).coerceAtMost(1f)
    val localHead = frac
    drawCircle(
        s.curve, radius = 2.5.dp.toPx(),
        center = Offset(tHead * size.width, (0.90f - heightAt(localHead) * 0.75f) * size.height)
    )
}
