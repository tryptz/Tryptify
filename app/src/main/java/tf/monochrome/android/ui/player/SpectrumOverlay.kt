package tf.monochrome.android.ui.player

import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

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
 *  - [SpectrumAverager] holds each bin over time the way the waterfall's
 *    analysis type and averaging time say; the real-time average rises
 *    quickly on transients and falls over the averaging time.
 *  - The lines are one triangle mesh drawn in a single call ([WaterfallMesh]),
 *    not a drawLines and a drawPath per line, which the renderer could not
 *    batch and which halved the frame rate.
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
 *  - The [WaterfallStyle.singleLine] styles draw the live spectrum alone,
 *    straight from the smoothed bins, and never touch the native history.
 *    [WaterfallStyle.LEGACY] is the overlay as it was before the waterfall: a
 *    Catmull-Rom envelope filled to the baseline. [WaterfallStyle.GLASS] turns
 *    that envelope into a body — smoothed, given a floor so it reads as a
 *    pool rather than a fence of spikes — drawn SOLID in its own layer and
 *    relit by [playerGlass], the same material as the transport. The shader
 *    bevels the alpha shape it is handed, so the body has to be opaque; where
 *    the shader is not coming ([rememberLiquidGlassAvailable]) a solid body
 *    would just be a block over the cover, so it is drawn as a translucent
 *    fill with its top edge stroked instead.
 */
@Composable
internal fun SpectrumOverlay(
    bins: () -> FloatArray,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 240.dp,
    /** dB above 0 (pink-noise reference) that maps to the top of a front line. */
    headroomDb: Float = 36f,
    /** dB below 0 that maps to the baseline. */
    floorDb: Float = -24f,
    /**
     * Scales the waterfall's averaging time: the hero's speed button, which
     * makes the whole motion slower or faster without changing the setting.
     */
    timeScale: Float = 1f,
    waterfall: SpectrumWaterfallSettings = SpectrumWaterfallSettings.DEFAULT,
    /**
     * Starts the long-term analysis types over when it changes — the track,
     * on the player, so "average since the track started" means that track.
     */
    resetKey: Any? = null,
    /**
     * The box the cover behind this overlay is drawn in, so the glass style
     * lenses the slice of artwork it actually lies on. Null maps against the
     * window, which is right wherever the backdrop is the blurred background.
     */
    glassArtFrame: BackdropAnchor? = null,
) {
    // Asked unconditionally, before anything that can change, so switching
    // style never alters the shape of the composition.
    val glassAvailable = rememberLiquidGlassAvailable()
    val still = tf.monochrome.android.ui.theme.reduceMotion()
    // Persistent in-place history — never replaced.
    val averager = remember { SpectrumAverager(SpectrumAnalyzerTap.OUTPUT_BINS, floorDb) }
    val smoothed = averager.values
    LaunchedEffect(resetKey) { averager.reset() }
    // Bumped once per frame to invalidate the Canvas without allocating.
    val tick = remember { mutableIntStateOf(0) }
    val currentBins by rememberUpdatedState(bins)
    val currentTimeScale by rememberUpdatedState(timeScale)
    val clampedWaterfall = waterfall.clamped()
    // The chosen colour, or the album's.
    val lineColor = clampedWaterfall.colorArgb?.let { Color(it) } ?: color
    val currentColor by rememberUpdatedState(lineColor)
    val currentWaterfall by rememberUpdatedState(clampedWaterfall)

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
            if (src.isEmpty()) {
                tick.intValue++
                continue
            }
            val w = currentWaterfall
            // Runs every frame — 256 bins, and it wants the true dt. The
            // draw is what the cap skips.
            val largestStep = averager.process(src, dt, w.analysis, w.avgTimeMs * currentTimeScale)
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
                val history = if (currentWaterfall.style.singleLine) 0f else currentWaterfall.depthSeconds
                val heldNanos = ((history + 0.1f) * 1e9f).toLong()
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

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
    Canvas(Modifier.fillMaxSize()) {
        // Subscribe to the per-frame tick so the Canvas redraws.
        @Suppress("UNUSED_VARIABLE")
        val t = tick.intValue
        if (size.width <= 0f || size.height <= 0f || draw.epochNanos < 0L) return@Canvas

        val w = currentWaterfall
        val nowSec = (draw.nowNanos - draw.epochNanos) / 1e9
        val style = w.style
        if (style.singleLine) {
            draw.envelope(smoothed, floorDb, headroomDb, size.width, size.height)
            if (style == WaterfallStyle.LEGACY) {
                drawIntoCanvas { draw.drawLegacy(it.nativeCanvas, currentColor, size.height) }
                draw.hasBody = false
            } else {
                val rippleSec = if (still) 0.0 else nowSec
                draw.buildBody(
                    baseline = size.height - GLASS_FOOT_DP.dp.toPx(),
                    minThickness = GLASS_MIN_THICKNESS_DP.dp.toPx(),
                    ceiling = GLASS_CEILING_DP.dp.toPx(),
                    ripple = if (still) 0f else GLASS_RIPPLE_DP.dp.toPx(),
                    phase = (rippleSec * GLASS_RIPPLE_HZ * 2.0 * PI).toFloat(),
                )
            }
            return@Canvas
        }
        draw.hasBody = false

        val lines = WaterfallNative.nativeRender(
            handle, smoothed, nowSec,
            size.width, size.height, w.depthSeconds, w.fadeStart, w.angleDeg,
            floorDb, headroomDb, draw.segs, draw.meta,
        )
        if (lines <= 0) return@Canvas

        val paint = draw.paintFor(currentColor, size.height, style)
        val stroke = w.lineWidthDp.dp.toPx() * if (style == WaterfallStyle.NEON) 1.3f else 1f
        val glow = 7.dp.toPx()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            drawIntoCanvas { draw.drawMesh(it.nativeCanvas, lines, currentColor, size.height, style, stroke, glow) }
            return@Canvas
        }
        // Before Android 10 the renderer cannot draw a mesh, so each line is
        // one stroked path: still far cheaper than drawLines, which strokes
        // every segment as a path of its own.
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
                native.drawPath(draw.linePath(offset), paint)
                if (style == WaterfallStyle.NEON && front) paint.clearShadowLayer()
            }
        }
    }

    if (waterfall.style == WaterfallStyle.GLASS) {
        // Its own layer: the shader works on everything the layer holds.
        // Offscreen so the inner ramp's DST_OUT erases the body and nothing
        // under it, whichever way the modifier below resolves.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .then(
                    if (glassAvailable) {
                        Modifier.playerGlass(tint = lineColor, artFrame = glassArtFrame)
                    } else {
                        Modifier
                    },
                ),
        ) {
            @Suppress("UNUSED_VARIABLE")
            val t = tick.intValue
            if (!draw.hasBody) return@Canvas
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                native.drawPath(draw.bodyPath, draw.bodyPaint(currentColor, size.height, solid = glassAvailable))
                if (glassAvailable) {
                    draw.innerRamp(native, GLASS_RAMP_STEP_DP.dp.toPx())
                } else {
                    native.drawPath(draw.bodyEdgePath, draw.bodyEdgePaint(currentColor, 1.6.dp.toPx()))
                }
            }
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

    /** The glass body under the front line, rebuilt each drawn frame; see [buildBody]. */
    val bodyPath = android.graphics.Path()
    /** Its top edge alone, for the fallback's stroke. */
    val bodyEdgePath = android.graphics.Path()
    var hasBody = false
    private val bodyScratch = FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS)
    private val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bodyEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var bodyColor = Color.Unspecified
    private var bodyHeight = -1f
    private var bodySolid = false

    /** The live spectrum as points across the box, for the single-line styles. */
    private val envXs = FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS)
    private val envYs = FloatArray(SpectrumAnalyzerTap.OUTPUT_BINS)
    private var envCount = 0

    /**
     * [smoothed] across the box: bins spaced evenly in x (they are already
     * log-spaced in frequency), [floorDb]..[headroomDb] across the full height.
     * The legacy overlay's own mapping.
     */
    fun envelope(smoothed: FloatArray, floorDb: Float, headroomDb: Float, width: Float, height: Float) {
        val n = minOf(smoothed.size, envXs.size)
        envCount = n
        if (n < 2) return
        val span = max(0.001f, headroomDb - floorDb)
        for (i in 0 until n) {
            envXs[i] = i.toFloat() / (n - 1) * width
            envYs[i] = height - (smoothed[i].coerceIn(floorDb, headroomDb) - floorDb) / span * height
        }
    }

    private val legacyEnvelope = android.graphics.Path()
    private val legacyFill = android.graphics.Path()
    private val legacyFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val legacyStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var legacyColor = Color.Unspecified
    private var legacyHeight = -1f

    /**
     * The overlay as it was before the waterfall: the envelope through every
     * bin as a Catmull-Rom spline, filled down to the baseline bright at the
     * peaks and gone at the floor, outlined white under an album-coloured
     * halo. Same numbers as then; only the per-frame allocation is gone.
     */
    fun drawLegacy(canvas: android.graphics.Canvas, color: Color, height: Float) {
        val n = envCount
        if (n < 2) return
        val xs = envXs
        val ys = envYs
        val env = legacyEnvelope
        env.rewind()
        env.moveTo(xs[0], ys[0])
        for (i in 0 until n - 1) {
            val i0 = (i - 1).coerceAtLeast(0)
            val i3 = (i + 2).coerceAtMost(n - 1)
            env.cubicTo(
                xs[i] + (xs[i + 1] - xs[i0]) / 6f, ys[i] + (ys[i + 1] - ys[i0]) / 6f,
                xs[i + 1] - (xs[i3] - xs[i]) / 6f, ys[i + 1] - (ys[i3] - ys[i]) / 6f,
                xs[i + 1], ys[i + 1],
            )
        }
        val fill = legacyFill
        fill.rewind()
        fill.addPath(env)
        fill.lineTo(xs[n - 1], height)
        fill.lineTo(xs[0], height)
        fill.close()
        if (color != legacyColor || height != legacyHeight) {
            legacyColor = color
            legacyHeight = height
            legacyFillPaint.shader = LinearGradient(
                0f, 0f, 0f, height,
                intArrayOf(
                    color.copy(alpha = 0.75f).toArgb(),
                    color.copy(alpha = 0.35f).toArgb(),
                    color.copy(alpha = 0.10f).toArgb(),
                    Color.Transparent.toArgb(),
                ),
                null,
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(fill, legacyFillPaint)
        legacyStroke.color = Color.White.copy(alpha = 0.92f).toArgb()
        legacyStroke.strokeWidth = 2.5f
        canvas.drawPath(env, legacyStroke)
        legacyStroke.color = color.copy(alpha = 0.55f).toArgb()
        legacyStroke.strokeWidth = 5f
        canvas.drawPath(env, legacyStroke)
    }

    /**
     * The glass body under the live envelope: [glassBodyProfile] for its
     * outline, then joined through the midpoints with quadratics so the surface
     * reads as liquid rather than as straight runs. Closed along [baseline].
     */
    fun buildBody(baseline: Float, minThickness: Float, ceiling: Float, ripple: Float, phase: Float) {
        val n = envCount
        if (n < 2) {
            hasBody = false
            return
        }
        val xs = envXs
        val tops = envYs
        glassBodyProfile(tops, n, baseline, minThickness, ceiling, ripple, phase, bodyScratch)
        val p = bodyPath
        val e = bodyEdgePath
        p.rewind()
        e.rewind()
        p.moveTo(xs[0], baseline)
        p.lineTo(xs[0], tops[0])
        e.moveTo(xs[0], tops[0])
        for (j in 1 until n) {
            val mx = (xs[j - 1] + xs[j]) * 0.5f
            val my = (tops[j - 1] + tops[j]) * 0.5f
            p.quadTo(xs[j - 1], tops[j - 1], mx, my)
            e.quadTo(xs[j - 1], tops[j - 1], mx, my)
        }
        p.lineTo(xs[n - 1], tops[n - 1])
        e.lineTo(xs[n - 1], tops[n - 1])
        p.lineTo(xs[n - 1], baseline)
        p.close()
        hasBody = true
    }

    private val rampPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        color = android.graphics.Color.argb(GLASS_RAMP_ALPHA, 0, 0, 0)
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
    }

    /**
     * Shapes the body's alpha into the heightfield the shader bevels from.
     *
     * The shader reads its surface normals off the alpha gradient a few
     * pixels either side of each point — sized for glyphs and buttons. On a
     * body the width of the cover that is a hairline, and the body reads as a
     * flat pane with a scratched outline. Blurring the edge to widen it does
     * not work either: the output alpha is capped by the input alpha, so a
     * soft edge comes out as a smudge.
     *
     * So the silhouette stays crisp and the ramp goes INSIDE it: [GLASS_RAMP_STEPS]
     * nested strokes, clipped to the body, each erasing a little alpha. The
     * rim keeps about two thirds and the core all of it, and the shader reads
     * that slope as a rounded shoulder — a body of liquid, not a slab.
     */
    fun innerRamp(canvas: android.graphics.Canvas, step: Float) {
        val save = canvas.save()
        canvas.clipPath(bodyPath)
        for (i in GLASS_RAMP_STEPS downTo 1) {
            rampPaint.strokeWidth = step * i
            canvas.drawPath(bodyPath, rampPaint)
        }
        canvas.restoreToCount(save)
    }

    /**
     * The body's fill. [solid] is the shader's slab — opaque, a little lighter
     * than the album colour so the glass carries it without going muddy.
     * Otherwise a see-through wash, light at the surface and gone at the floor.
     */
    fun bodyPaint(color: Color, height: Float, solid: Boolean): Paint {
        if (color != bodyColor || height != bodyHeight || solid != bodySolid) {
            bodyColor = color
            bodyHeight = height
            bodySolid = solid
            if (solid) {
                bodyFill.shader = null
                bodyFill.color = lerp(color, Color.White, 0.3f).toArgb()
            } else {
                bodyFill.shader = LinearGradient(
                    0f, 0f, 0f, height,
                    lerp(color, Color.White, 0.4f).copy(alpha = 0.42f).toArgb(),
                    color.copy(alpha = 0.12f).toArgb(),
                    Shader.TileMode.CLAMP,
                )
            }
        }
        return bodyFill
    }

    /** The fallback's top edge: the Lines style's front line, in effect. */
    fun bodyEdgePaint(color: Color, width: Float): Paint {
        bodyEdge.color = lerp(color, Color.White, 0.55f).copy(alpha = LINE_ALPHA).toArgb()
        bodyEdge.strokeWidth = width
        return bodyEdge
    }

    private val mesh = WaterfallMesh()
    private val gradient = VerticalGradient()
    private var gradientColor = Color.Unspecified
    private var gradientHeight = -1f
    private var gradientStyle: WaterfallStyle? = null
    /** White and opaque: the mesh's vertex colours are the colour. */
    private val meshPaint = Paint().apply { color = android.graphics.Color.WHITE }
    private val linePathScratch = android.graphics.Path()

    /**
     * Every visible line, back to front, as one mesh — Ridgeline's ground
     * under each line included — in as few drawVertices calls as the 16-bit
     * index limit allows (two, at the most lines and the widest style).
     * Neon's live line is the exception: its halo is a shadow layer, which a
     * mesh cannot carry, so that one line is still a stroked path.
     */
    fun drawMesh(
        canvas: android.graphics.Canvas,
        lines: Int,
        color: Color,
        height: Float,
        style: WaterfallStyle,
        stroke: Float,
        glow: Float,
    ) {
        updateGradient(color, height, style)
        meshPaint.xfermode = if (style == WaterfallStyle.NEON) {
            android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
        } else {
            null
        }
        val ground = style == WaterfallStyle.RIDGELINE
        val segments = WaterfallNative.FLOATS_PER_LINE / 4
        val haloFront = style == WaterfallStyle.NEON
        mesh.reset()
        for (i in 0 until lines) {
            val m = i * WaterfallNative.META_PER_LINE
            val alpha = meta[m]
            if (alpha <= 0.004f) continue
            if (haloFront && i == lines - 1) continue
            if (!mesh.hasRoom(segments + 1, ground)) {
                flushMesh(canvas)
                mesh.reset()
            }
            val offset = i * WaterfallNative.FLOATS_PER_LINE
            val points = mesh.loadPoints(segs, offset, segments)
            if (ground) {
                val a = (alpha * RIDGE_GROUND_ALPHA * 255f).toInt().coerceIn(0, 255)
                mesh.addGround(points, meta[m + 2], (a shl 24) or (RIDGE_GROUND and 0x00FFFFFF))
            }
            mesh.addRibbon(points, stroke * (0.45f + 0.55f * meta[m + 1]), alpha * LINE_ALPHA, gradient)
        }
        flushMesh(canvas)

        if (haloFront && lines > 0) {
            val m = (lines - 1) * WaterfallNative.META_PER_LINE
            val alpha = meta[m]
            if (alpha > 0.004f) {
                paint.alpha = (alpha * LINE_ALPHA * 255f).toInt().coerceIn(0, 255)
                paint.strokeWidth = stroke * (0.45f + 0.55f * meta[m + 1])
                paint.setShadowLayer(glow, 0f, 0f, color.toArgb())
                canvas.drawPath(linePath((lines - 1) * WaterfallNative.FLOATS_PER_LINE), paint)
                paint.clearShadowLayer()
            }
        }
    }

    private fun flushMesh(canvas: android.graphics.Canvas) {
        if (mesh.vertexCount == 0) return
        canvas.drawVertices(
            android.graphics.Canvas.VertexMode.TRIANGLES,
            mesh.vertexCount * 2, mesh.verts, 0,
            null, 0,
            mesh.colors, 0,
            mesh.indices, 0, mesh.indexCount,
            meshPaint,
        )
    }

    /** The line paint's gradient, as per-vertex colours; rebuilt only when it changes. */
    private fun updateGradient(color: Color, height: Float, style: WaterfallStyle) {
        if (color == gradientColor && height == gradientHeight && style == gradientStyle) return
        gradientColor = color
        gradientHeight = height
        gradientStyle = style
        if (style == WaterfallStyle.HEAT) {
            gradient.set(height, HEAT_STOPS, HEAT_POSITIONS)
        } else {
            gradient.set(
                height,
                intArrayOf(lerp(color, Color.White, 0.55f).toArgb(), color.toArgb()),
                floatArrayOf(0f, 1f),
            )
        }
    }

    /** One line at [offset] in [segs] as a polyline, in a reused Path. */
    fun linePath(offset: Int): android.graphics.Path {
        val p = linePathScratch
        p.rewind()
        p.moveTo(segs[offset], segs[offset + 1])
        var k = offset
        val end = offset + WaterfallNative.FLOATS_PER_LINE
        while (k < end) {
            p.lineTo(segs[k + 2], segs[k + 3])
            k += 4
        }
        return p
    }

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
                    HEAT_STOPS,
                    HEAT_POSITIONS,
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

/**
 * Turns the live envelope in [tops] — the y of each of its first [n] points,
 * spread evenly across the box — into the top edge of the glass body, in place.
 *
 * Three things turn a spectrum into something that reads as a pool of liquid:
 *  - **Smoothed across frequency** — two passes of a 1-2-1 kernel. The raw line
 *    is jagged bin to bin, and the bevel would pick out every tooth.
 *  - **A floor of [minThickness]** under the whole span, so a quiet band is a
 *    thin film rather than a gap that splits the body into islands. The level
 *    above it is compressed so the tallest peak still stops [ceiling] px from
 *    the top: the bevel needs a few pixels of room, and a body flattened
 *    against the edge of its layer would lose its top rim.
 *  - **Rounded ends** — the thickness falls to nothing over the outer
 *    [GLASS_TAPER] of the width along a square-rooted smoothstep, which meets
 *    the floor vertically: a drop's rounded end rather than a wedge.
 *
 * Heights are measured up from [baseline]. [ripple] px of slow surface swell,
 * phased by [phase], keeps it liquid in a quiet passage; zero is perfectly
 * still. [scratch] is a work buffer at least [n] long. Allocates nothing.
 */
internal fun glassBodyProfile(
    tops: FloatArray,
    n: Int,
    baseline: Float,
    minThickness: Float,
    ceiling: Float,
    ripple: Float,
    phase: Float,
    scratch: FloatArray,
) {
    if (n < 2) return
    for (j in 0 until n) tops[j] = (baseline - tops[j]).coerceAtLeast(0f)
    repeat(2) {
        for (j in 0 until n) {
            val l = tops[if (j > 0) j - 1 else 0]
            val r = tops[if (j < n - 1) j + 1 else n - 1]
            scratch[j] = (l + 2f * tops[j] + r) * 0.25f
        }
        scratch.copyInto(tops, 0, 0, n)
    }
    val room = (baseline - ceiling).coerceAtLeast(0f)
    val floor = minThickness.coerceAtMost(room)
    val gain = if (room > 0f) (room - floor) / room else 0f
    for (j in 0 until n) {
        val u = j.toFloat() / (n - 1)
        val edge = (minOf(u, 1f - u) / GLASS_TAPER).coerceIn(0f, 1f)
        val s = edge * edge * (3f - 2f * edge)
        val swell = ripple * (0.6f * sin(u * 9.4f + phase) + 0.4f * sin(u * 23.1f - phase * 1.7f))
        val thick = (floor + tops[j].coerceAtMost(room) * gain + swell).coerceIn(0f, room)
        tops[j] = baseline - thick * sqrt(s)
    }
}

/** Fraction of the width at each end over which the glass body rounds off. */
internal const val GLASS_TAPER = 0.07f
/** The film the body never thins below, so it stays one piece. */
private const val GLASS_MIN_THICKNESS_DP = 10f
/** Room left above the tallest peak for the bevel's top rim. */
private const val GLASS_CEILING_DP = 4f
/** Height of the slow swell that keeps the surface moving in a quiet passage. */
private const val GLASS_RIPPLE_DP = 1.5f
/** How fast that swell rolls. Slow on purpose: liquid, not a signal. */
private const val GLASS_RIPPLE_HZ = 0.35
/** Lifts the body off the bottom edge so its lower rim is not cut by the layer. */
private const val GLASS_FOOT_DP = 2f
/** The inner ramp: this many nested strokes, each this much wider, each erasing this much alpha. */
private const val GLASS_RAMP_STEPS = 8
private const val GLASS_RAMP_STEP_DP = 3f
private const val GLASS_RAMP_ALPHA = 13

/** Heat's palette, whatever the album: deep green at the floor to yellow-white at the top. */
private val HEAT_STOPS = intArrayOf(0xFFFFF6C8.toInt(), 0xFFE4F55A.toInt(), 0xFF7BD85A.toInt(), 0xFF1F8F5A.toInt())
private val HEAT_POSITIONS = floatArrayOf(0f, 0.3f, 0.6f, 1f)

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
