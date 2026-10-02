package tf.monochrome.android.ui.player

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.audio.eq.WaterfallNative
import tf.monochrome.android.domain.model.SpectrumWaterfallSettings
import tf.monochrome.android.domain.model.WaterfallStyle
import kotlin.math.exp
import kotlin.math.max

/**
 * The spectrum over the artwork as a receding waterfall: the live spectrum is
 * the bright line at the front, and every fraction of a second a copy of it is
 * laid down and falls back — narrowing toward the centre, rising toward the top
 * and fading out — so the last few seconds of the song stand behind it as a
 * ridgeline. [waterfall] sets how far back that runs, where the fade begins
 * and the angle the lines rise at.
 *
 * Implementation notes:
 *  - The history and the perspective are native (cpp/dsp/scope/
 *    spectrum_waterfall.h), which hands back ready-to-draw segments and one
 *    alpha per line. This side smooths the bins and draws: one `drawLines` per
 *    line on reused arrays and one reused Paint, nothing allocated per frame.
 *    The line count is fixed, so depth and angle cost nothing to change.
 *  - Separate attack/release time constants give the front line a snappy
 *    response on transients and a longer tail on decays.
 *  - Once the front line has caught up with the bins *and* every older line
 *    has had time to become the same picture, the loop sleeps until the
 *    analyzer publishes a new frame: a paused track otherwise redrew an
 *    identical waterfall every vsync. Until then it keeps running, because the
 *    lines are still receding even when the spectrum has stopped moving.
 *  - [bins] is a provider, not the array, and is only ever invoked from the
 *    frame loop. The analyzer publishes a fresh array every FFT frame, so a
 *    caller that read it during composition to pass it down recomposed itself
 *    — on the player, the whole hero and the artwork in it — at the
 *    analyzer's rate, just to hand over a value this loop reads anyway.
 */
@Composable
fun SpectrumOverlay(
    bins: () -> FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    /** dB above 0 (pink-noise reference) that maps to the top of a front line. */
    headroomDb: Float = 36f,
    /** dB below 0 that maps to the baseline. */
    floorDb: Float = -24f,
    /** Approach factor per 60 fps frame for rising bins (0..1, higher = snappier). */
    attack: Float = 0.55f,
    /** Approach factor per 60 fps frame for falling bins. */
    release: Float = 0.12f,
    waterfall: SpectrumWaterfallSettings = SpectrumWaterfallSettings.DEFAULT,
) {
    // Persistent in-place smoothing buffer — never replaced.
    val smoothed = remember {
        FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS) { floorDb }
    }
    // Bumped once per frame to invalidate the Canvas without allocating.
    val tick = remember { mutableIntStateOf(0) }
    val currentBins by rememberUpdatedState(bins)
    val currentAttack by rememberUpdatedState(attack)
    val currentRelease by rememberUpdatedState(release)
    val currentColor by rememberUpdatedState(color)
    val currentWaterfall by rememberUpdatedState(waterfall.clamped())

    // The native history, freed with the overlay.
    val handle = remember { WaterfallNative.nativeCreate() }
    DisposableEffect(handle) { onDispose { WaterfallNative.nativeDestroy(handle) } }
    val draw = remember { WaterfallDrawState() }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        var settledSince = -1L
        // The display's refresh period, learned from the frames themselves:
        // a panel with a dynamic rate (120 Hz while touched, 60 at rest) moves
        // under the cap, and the cap has to follow it.
        var refreshNanos = 1_000_000_000.0 / 60.0
        var frameCount = 0L
        var nextDueNanos = 0L
        while (isActive) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            if (draw.epochNanos < 0L) draw.epochNanos = now
            draw.nowNanos = now
            val dt = if (lastFrameNanos == 0L) (1f / 60f)
                else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0.001f, 0.1f)
            if (lastFrameNanos != 0L && now - lastFrameNanos < 100_000_000L) {
                refreshNanos += ((now - lastFrameNanos) - refreshNanos) * 0.05
            }
            lastFrameNanos = now

            val src = currentBins()
            val n = minOf(src.size, smoothed.size)
            if (n == 0) {
                tick.intValue++
                continue
            }
            // Frame-rate–independent exponential smoothing with split
            // attack/release for the snappy-on-rise, gentle-on-fall feel.
            val attackAlpha = (1f - exp(-currentAttack * 60f * dt)).coerceIn(0f, 1f)
            val releaseAlpha = (1f - exp(-currentRelease * 60f * dt)).coerceIn(0f, 1f)
            var largestStep = 0f
            for (i in 0 until n) {
                val target = src[i]
                val cur = smoothed[i]
                val a = if (target > cur) attackAlpha else releaseAlpha
                val step = (target - cur) * a
                smoothed[i] = cur + step
                largestStep = max(largestStep, kotlin.math.abs(step))
            }
            // The smoothing above runs every frame — it is 256 additions and
            // wants the true dt. The draw is what the cap skips.
            val w = currentWaterfall
            if (waterfallFrameDue(frameCount++, now, nextDueNanos, refreshNanos, w.targetFps, w.vsync)) {
                nextDueNanos = waterfallNextDue(now, nextDueNanos, w.targetFps)
                tick.intValue++
            }

            // Settled on these bins, and held there long enough for the whole
            // history to be this same picture: every further frame would draw
            // the same waterfall. Wait for the next array instead.
            // snapshotFlow sees the read through the provider, so a new FFT
            // frame wakes it.
            if (largestStep < SETTLED_DB) {
                if (settledSince < 0L) settledSince = now
                val heldNanos = ((currentWaterfall.depthSeconds + 0.1f) * 1e9f).toLong()
                if (now - settledSince >= heldNanos) {
                    snapshotFlow { currentBins() }.first { it !== src }
                    lastFrameNanos = 0L
                    settledSince = -1L
                }
            } else {
                settledSince = -1L
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        // Subscribe to the per-frame tick so the Canvas redraws.
        @Suppress("UNUSED_VARIABLE")
        val t = tick.intValue
        if (size.width <= 0f || size.height <= 0f || draw.epochNanos < 0L) return@Canvas

        val w = currentWaterfall
        val nowSec = (draw.nowNanos - draw.epochNanos) / 1e9
        val lines = WaterfallNative.nativeRender(
            handle, smoothed, nowSec,
            size.width, size.height, w.depthSeconds, w.fadeStart, w.angleDeg,
            floorDb, headroomDb, draw.segs, draw.meta,
        )
        if (lines <= 0) return@Canvas

        val style = w.style
        val paint = draw.paintFor(currentColor, size.height, style)
        val stroke = w.lineWidthDp.dp.toPx() * if (style == WaterfallStyle.NEON) 1.3f else 1f
        val glow = 7.dp.toPx()
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            for (i in 0 until lines) {
                val m = i * WaterfallNative.META_PER_LINE
                val alpha = draw.meta[m]
                if (alpha <= 0.004f) continue
                val scale = draw.meta[m + 1]
                val offset = i * WaterfallNative.FLOATS_PER_LINE
                if (style == WaterfallStyle.RIDGELINE) {
                    // The ground under the line, down to its own baseline, in
                    // the dark the lines sit on: drawn back to front, each
                    // line's fill covers whatever of the older lines falls
                    // behind it. It fades with its line, so a line on its way
                    // out stops hiding the ones behind it as it goes.
                    native.drawPath(draw.ridgeFill(offset, draw.meta[m + 2], alpha), draw.fillPaint)
                }
                val front = i == lines - 1
                paint.alpha = (alpha * LINE_ALPHA * 255f).toInt().coerceIn(0, 255)
                paint.strokeWidth = stroke * (0.45f + 0.55f * scale)
                // Neon's halo on the live line only: a shadow layer is a blur
                // per draw, and one is the price of the effect, not 49.
                if (style == WaterfallStyle.NEON && front) {
                    paint.setShadowLayer(glow, 0f, 0f, currentColor.toArgb())
                }
                native.drawLines(draw.segs, offset, WaterfallNative.FLOATS_PER_LINE, paint)
                if (style == WaterfallStyle.NEON && front) paint.clearShadowLayer()
            }
        }
    }
}

/**
 * Everything the draw reuses from frame to frame: the segment and metadata
 * arrays the native side fills, the clock the loop stamps, and a Paint whose
 * gradient is rebuilt only when the colour or the height changes.
 */
private class WaterfallDrawState {
    val segs = FloatArray(WaterfallNative.MAX_LINES * WaterfallNative.FLOATS_PER_LINE)
    val meta = FloatArray(WaterfallNative.MAX_LINES * WaterfallNative.META_PER_LINE)
    var epochNanos = -1L
    var nowNanos = 0L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var shaderColor = Color.Unspecified
    private var shaderHeight = -1f
    private var shaderStyle: WaterfallStyle? = null

    /** Ridgeline's ground: the dark the lines sit on, alpha set per line. */
    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPath = android.graphics.Path()

    /**
     * The line paint for [style]. Every style colours by a screen-space
     * gradient, so a line's colour follows how high it reaches rather than
     * which line it is — the peaks catch the light the way the reference's
     * ridges do.
     */
    fun paintFor(color: Color, height: Float, style: WaterfallStyle): Paint {
        if (color != shaderColor || height != shaderHeight || style != shaderStyle) {
            shaderColor = color
            shaderHeight = height
            shaderStyle = style
            paint.shader = when (style) {
                // The reference's own palette, whatever the album: deep green
                // on the floor, lime through the body, yellow-white at the top.
                WaterfallStyle.HEAT -> LinearGradient(
                    0f, 0f, 0f, height,
                    intArrayOf(0xFFFFF6C8.toInt(), 0xFFE4F55A.toInt(), 0xFF7BD85A.toInt(), 0xFF1F8F5A.toInt()),
                    floatArrayOf(0f, 0.3f, 0.6f, 1f),
                    Shader.TileMode.CLAMP,
                )
                else -> LinearGradient(
                    0f, 0f, 0f, height,
                    lerp(color, Color.White, 0.55f).toArgb(),
                    color.toArgb(),
                    Shader.TileMode.CLAMP,
                )
            }
            // Additive: where lines cross or crowd, the light adds up.
            paint.xfermode = if (style == WaterfallStyle.NEON) {
                android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
            } else {
                null
            }
            fillPaint.color = RIDGE_GROUND
        }
        return paint
    }

    /**
     * The area under one line, down to its baseline, built into a reused
     * Path from the segments the native side wrote at [offset].
     */
    fun ridgeFill(offset: Int, baseline: Float, alpha: Float): android.graphics.Path {
        val p = fillPath
        p.rewind()
        p.moveTo(segs[offset], baseline)
        p.lineTo(segs[offset], segs[offset + 1])
        var k = offset
        val end = offset + WaterfallNative.FLOATS_PER_LINE
        while (k < end) {
            p.lineTo(segs[k + 2], segs[k + 3])
            k += 4
        }
        p.lineTo(segs[end - 2], baseline)
        p.close()
        fillPaint.alpha = (alpha * RIDGE_GROUND_ALPHA * 255f).toInt().coerceIn(0, 255)
        return p
    }
}

/**
 * Whether this display frame should be drawn under a [targetFps] cap.
 *
 * With [vsync] the cap snaps to an even step of the refresh rate: every Nth
 * frame, N = refresh ÷ target rounded, so 60 on a 120 Hz panel is every second
 * refresh and evenly spaced, and 45 on 120 Hz becomes every third (40 fps).
 * Without it the clock decides: each draw is due a target interval after the
 * last one was *due* — not after it happened — and taken on the refresh
 * nearest that time. Measuring from the draw itself rounded every interval up
 * to whole refreshes, so 45 on 120 Hz came out at 40, the same as with vsync;
 * scheduling against the due time lets those roundings cancel, so the average
 * is the rate asked for and only the spacing is uneven. 0 draws every frame.
 */
internal fun waterfallFrameDue(
    frameIndex: Long,
    nowNanos: Long,
    nextDueNanos: Long,
    refreshNanos: Double,
    targetFps: Int,
    vsync: Boolean,
): Boolean {
    if (targetFps <= 0) return true
    if (vsync) {
        val refreshHz = 1_000_000_000.0 / refreshNanos.coerceAtLeast(1.0)
        val every = kotlin.math.round(refreshHz / targetFps).toLong().coerceAtLeast(1L)
        return frameIndex % every == 0L
    }
    if (nextDueNanos == 0L) return true
    return nowNanos >= nextDueNanos - refreshNanos * 0.5
}

/**
 * When the draw after one taken at [nowNanos] is due. A stall (the screen was
 * off, the overlay slept) restarts the schedule rather than leaving a backlog
 * of overdue frames to be drawn back to back.
 */
internal fun waterfallNextDue(nowNanos: Long, previousDueNanos: Long, targetFps: Int): Long {
    if (targetFps <= 0) return 0L
    val interval = (1_000_000_000.0 / targetFps).toLong()
    val next = if (previousDueNanos == 0L) nowNanos + interval else previousDueNanos + interval
    return if (next < nowNanos) nowNanos + interval else next
}

/** Ridgeline's ground colour; its alpha comes from the line it sits under. */
private val RIDGE_GROUND = 0xFF07090D.toInt()
private const val RIDGE_GROUND_ALPHA = 0.94f

/** Overall strength of a line at full alpha; the front line is never pure white. */
private const val LINE_ALPHA = 0.92f

/**
 * Largest per-frame move, in dB, below which the front line counts as caught
 * up. At the overlay's 60 dB span on a ~300 px band that is well under a tenth
 * of a pixel, so the frame it stops on is the frame it would have kept drawing.
 */
private const val SETTLED_DB = 0.005f
