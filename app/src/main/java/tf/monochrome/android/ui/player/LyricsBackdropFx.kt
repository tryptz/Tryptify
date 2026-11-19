package tf.monochrome.android.ui.player

import android.graphics.RenderEffect
import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy as LayerCompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import tf.monochrome.android.performance.LocalLowPerformance

/**
 * The lyrics' light and shadow on the BACKGROUND, under the glass UI.
 *
 * The god rays and the shadow used to be drawn inside the lyric surface, so a
 * shaft stopped dead at its bottom edge, just above the song title (seen on
 * device), and the player's glass — the title, the progress tube, the play
 * disc, the dock — never had any of the light under it. Here they are drawn
 * in the player's `fxUnderlay`: full screen, after the album background and
 * before everything else, and inside the haze sources, so the glass panes
 * frost and bend the shafts like the rest of the backdrop.
 *
 * The light comes from the sung letters, which live in another layer, and a
 * render effect can read nothing but its own. So the lyric view records its
 * letters into a [GraphicsLayer] ([captureLetters]) and this layer draws that
 * copy as its input. The copy is taken before the glass, so the glass shader
 * does not run twice, and it never shows: the effect hands back the shadow and
 * the shafts alone ([GOD_RAYS_SRC]'s `uRaysOnly`), and the real letters draw
 * over them later. Back to front: shadow, shafts, letters.
 *
 * "On top" is drawn here too, so its shafts run under the glass UI as well.
 * While they stayed in the lyric surface they stopped dead above the song
 * title (seen on device). The one thing a layer under the letters cannot do is
 * add the light over them, so the lyric view does that part alone, on its own
 * letters ([lyricRaysOnLetters]). Shafts under the letters plus their own light
 * added to them is the "On top" composite.
 */
@Stable
internal class LyricLetterCapture {
    /** The lyric view's letters, as drawn, before the glass. Null while no lyric view is up. */
    var layer by mutableStateOf<GraphicsLayer?>(null)

    /** Where that layer's top-left sits, in root px. */
    var originInRoot by mutableStateOf(Offset.Zero)

    /** That layer's size, for the edge fade. */
    var size by mutableStateOf(Size.Zero)

    /** Fade the copy's top and bottom as the lyric surface fades its own ([lyricsEdgeFade]). */
    var edgeFade by mutableStateOf(false)

    /** The lyric surface in root px, or null until it is laid out. The light is set over it. */
    val boxInRoot: Rect?
        get() = if (size.width > 0f && size.height > 0f) Rect(originInRoot, size) else null

    /** What is being sung, in root px. Set by the lyric view; read in the draw phase. */
    var bandInRoot: () -> Rect? = { null }

    /**
     * How much of the light is on, 0 to 1: out before the first line and when
     * what was sung is over ([rememberLyricLightLevel]). Set by the lyric
     * view; read in the draw phase.
     */
    var lightLevel: () -> Float = { 1f }

    /**
     * True while [captureLetters] records [layer]. Anything that would draw
     * the copy then is inside the letters, and drawing it would put the layer
     * inside itself: a loop the render thread walks until its stack runs out.
     * Not state: read and written in the draw pass only.
     */
    var recording = false
}

/** What the player hands the lyric view: where to send its letters, and the light. */
internal data class LyricBackdrop(val capture: LyricLetterCapture, val light: LyricRayLight?)

/**
 * Provided by a screen that draws [LyricBackdropFx] under its lyrics. A lyric
 * view that finds one sends its letters there and leaves its shadow and its
 * shafts to it; without one it draws both itself.
 */
internal val LocalLyricBackdrop = compositionLocalOf<LyricBackdrop?> { null }

/** How much light the copy gives against the glass letters the shafts were tuned on. */
internal const val LYRIC_COPY_EMISSION = 0.75f

/**
 * Records what this node draws into a layer [capture] can hand to
 * [LyricBackdropFx], and draws it in place through that same layer. Put it
 * innermost — after the glass — so the copy is the plain letters.
 */
@Composable
internal fun Modifier.captureLetters(capture: LyricLetterCapture?, edgeFade: Boolean = false): Modifier {
    if (capture == null) return this.then(CopyBoundary)
    val layer = rememberGraphicsLayer()
    DisposableEffect(capture, layer) {
        capture.layer = layer
        capture.edgeFade = edgeFade
        onDispose { if (capture.layer === layer) capture.layer = null }
    }
    return this
        .onGloballyPositioned {
            capture.originInRoot = it.positionInRoot()
            capture.size = Size(it.size.width.toFloat(), it.size.height.toFloat())
        }
        .drawWithContent {
            // A second capture of the same letters inside the first (a lyric
            // view drawn within its own copy) draws plainly instead.
            if (capture.recording) {
                drawContent()
                return@drawWithContent
            }
            RaysProbe.copiesRecorded++
            capture.recording = true
            try {
                layer.record { this@drawWithContent.drawContent() }
            } finally {
                capture.recording = false
            }
            drawLayer(layer)
        }
        .then(CopyBoundary)
}

/**
 * A layout node that only passes its child through, right inside the
 * letters' copy, so the copy's draw always belongs to this node and the
 * layer that holds it stays the one outward of it. Without it, a layer
 * added inward later (the curve, the beat's scale) took over the copy's draw
 * and was handed the old layer that already drew the copy: a loop on the
 * render thread. Anything added inward now gets a fresh layer of its own.
 * There whether or not the copy is taken, so taking it changes nothing here.
 */
private val CopyBoundary: Modifier = Modifier.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}

/**
 * The shadow and the god rays, full screen, from the copy of the letters in
 * [backdrop]. Draws nothing when there is neither, when no lyric view is up,
 * below API 31, or with the low-performance glass switch on.
 */
@Composable
internal fun LyricBackdropFx(backdrop: LyricBackdrop, modifier: Modifier = Modifier) {
    val fx = LocalLyricsFx.current
    val live = LocalVisualLinks.current
    // The reasons it composes nothing go to the Debug Log's rays report.
    if (LocalLowPerformance.current.disableLiquidGlass) {
        RaysProbe.backdropState = "off: low-performance glass"
        return
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        RaysProbe.backdropState = "off: below API 31"
        return
    }
    val capture = backdrop.capture
    val letters = capture.layer ?: run {
        RaysProbe.backdropState = "no letters recorded"
        return
    }
    val light = backdrop.light
    val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) rememberGodRaysShader() else null
    val raysLight = if (shader != null) light else null
    val shadow = fx.shadowDepth > LyricShadowGeometry.OFF
    if (raysLight == null && !shadow) {
        RaysProbe.backdropState = if (light == null) "no light" else "no shader"
        return
    }
    RaysProbe.backdropState = "composed"
    val origin = remember { mutableStateOf<Offset?>(null) }
    // SHAFTS_SCALE at the High rendering quality; smaller below it.
    val shaftsScale = LocalLowPerformance.current.renderQuality.shaftsScale
    val fxLayer = rememberGraphicsLayer()
    val shadeLayer = rememberGraphicsLayer()

    Box(
        modifier
            .fillMaxSize()
            .rayLayerOrigin(origin)
            .drawBehind {
                val at = origin.value ?: run {
                    RaysProbe.hit(RaysProbe.Backdrop.NO_ORIGIN)
                    return@drawBehind
                }
                if (letters.isReleased || size.minDimension <= 0f || capture.recording) {
                    RaysProbe.hit(RaysProbe.Backdrop.LETTERS_RELEASED)
                    return@drawBehind
                }
                val f = light?.frameFor(at)
                if (raysLight != null && f == null) RaysProbe.hit(RaysProbe.Backdrop.NO_LIGHT_FRAME)
                // The shafts are drawn small and scaled up ([SHAFTS_SCALE]).
                val down = shaftsScale
                // No shafts while the light is out (nothing is being sung):
                // the layer would march every pixel to draw nothing. The
                // shadow stays, falling the way the light stands.
                val rays = if (raysLight != null && f != null && f.lit && shader != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ) {
                    setGodRayUniforms(shader, raysLight, f, RayOutput.SHAFTS, emission = LYRIC_COPY_EMISSION, px = down)
                    RenderEffect.createRuntimeShaderEffect(shader, "content")
                } else {
                    null
                }
                val shade = if (shadow) lyricShadowEffect(live.lyrics(fx), f) else null
                val lightOut = raysLight != null && f != null && !f.lit
                if (rays == null && shade == null) {
                    RaysProbe.hit(if (lightOut) RaysProbe.Backdrop.LIGHT_OUT else RaysProbe.Backdrop.NO_EFFECT)
                    return@drawBehind
                }
                RaysProbe.hit(
                    when {
                        rays != null -> RaysProbe.Backdrop.RAYS
                        lightOut -> RaysProbe.Backdrop.LIGHT_OUT
                        else -> RaysProbe.Backdrop.SHADOW_ONLY
                    },
                )
                // Every length in px before recording, and recorded with a
                // plain Density rather than through the node's own record { }:
                // inside that block the node's draw scope reads its density
                // from itself, so the first dp.toPx() there recursed until the
                // stack overflowed, the moment the lyrics opened (seen on
                // device). Here the block draws in the layer's own scope.
                val feather = 14.dp.toPx()
                val plain = Density(density, fontScale)
                val copyAt = capture.originInRoot - at
                // The shadow first, under the shafts, at full size: it is the
                // letters' own shape a few px from them. It had the shafts in
                // its layer, one effect over the other, which held the shafts
                // to full size too. Each layer's effect and the copy it reads
                // are set in the one place, so the copy is never drawn bare,
                // and each has its own buffer, so the copy's edge fade (DstIn)
                // cuts the copy alone.
                if (shade != null) {
                    shadeLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
                    shadeLayer.renderEffect = shade.asComposeRenderEffect()
                    shadeLayer.record(plain, layoutDirection, IntSize(ceil(size.width).toInt(), ceil(size.height).toInt())) {
                        drawLetterCopy(capture, letters, copyAt, feather)
                    }
                    drawLayer(shadeLayer)
                }
                if (rays != null) {
                    fxLayer.compositingStrategy = LayerCompositingStrategy.Offscreen
                    fxLayer.renderEffect = rays.asComposeRenderEffect()
                    fxLayer.record(
                        plain,
                        layoutDirection,
                        IntSize(ceil(size.width * down).toInt(), ceil(size.height * down).toInt()),
                    ) {
                        scale(down, down, pivot = Offset.Zero) { drawLetterCopy(capture, letters, copyAt, feather) }
                    }
                    scale(1f / down, 1f / down, pivot = Offset.Zero) { drawLayer(fxLayer) }
                }
            },
    )
}

/**
 * "On top" over a [LyricBackdropFx]: the real letters, with the light that
 * falls on them added to their own pixels. The backdrop has already drawn the
 * shafts under them, everywhere else; this is the rest of the "On top"
 * composite, and the letters are the only place it lands, so the march runs
 * on their pixels alone.
 *
 * It is a render effect on the letters' own layer, not a layer of light laid
 * over them, for two reasons. The composite caps the light at what a letter
 * covers, and only the real letters know that: capped on the backdrop's copy,
 * which is drawn before the glass at full strength, there was never any room
 * and the sung line stood grey under its own light (seen in a render through
 * Skia). And it leaves the letters' alpha as it is, so the edge fade's
 * offscreen buffer takes them like any others; a separate layer adds alpha
 * there that hides the shafts behind every soft letter.
 *
 * Put it where [lyricGodRays] would go: outside the glass, so the light is
 * gathered from and added to the finished letters. A no-op without a
 * backdrop (the lyric view's own rays add it then), out of "On top", or below
 * API 33.
 */
@Composable
internal fun Modifier.lyricRaysOnLetters(backdrop: LyricBackdrop?): Modifier {
    val light = backdrop?.light ?: return this
    if (!light.baseFx.godRaysOnTop) return this
    return lyricGodRays(light, RayOutput.ON_LETTERS)
}

/**
 * The lyric view's letters, as [captureLetters] recorded them, drawn with
 * their top-left at [at] in this scope, and faded at the top and bottom as
 * the real ones are when [LyricLetterCapture.edgeFade] says so, over at most
 * [maxFeatherPx].
 */
private fun DrawScope.drawLetterCopy(
    capture: LyricLetterCapture,
    letters: GraphicsLayer,
    at: Offset,
    maxFeatherPx: Float,
) {
    translate(at.x, at.y) {
        drawLayer(letters)
        if (capture.edgeFade) {
            // The same feather lyricsEdgeFade gives the real letters, or a
            // line half-scrolled out would leave its shadow and its light cut
            // off hard at the surface's edge.
            val h = capture.size.height
            if (h > 0f) {
                val top = (h * 0.05f).coerceAtMost(maxFeatherPx) / h
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.Transparent,
                        top to Color.Black,
                        1f - top to Color.Black,
                        1f to Color.Transparent,
                        startY = 0f,
                        endY = h,
                    ),
                    size = capture.size,
                    blendMode = ComposeBlendMode.DstIn,
                )
            }
        }
    }
}
