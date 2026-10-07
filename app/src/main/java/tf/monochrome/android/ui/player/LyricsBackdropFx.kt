package tf.monochrome.android.ui.player

import android.graphics.BlendMode
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
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
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
 * "On top" adds the light over the letters, which a layer under them cannot
 * do, so in that mode the shafts stay in the lyric surface and only the
 * shadow is drawn here.
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

    /** What is being sung, in root px. Set by the lyric view; read in the draw phase. */
    var bandInRoot: () -> Rect? = { null }
}

/** What the player hands the lyric view: where to send its letters, and the light. */
internal data class LyricBackdrop(val capture: LyricLetterCapture, val light: LyricRayLight?)

/**
 * Provided by a screen that draws [LyricBackdropFx] under its lyrics. A lyric
 * view that finds one sends its letters there and leaves its shadow (and, but
 * for "On top", its shafts) to it; without one it draws both itself.
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
    if (capture == null) return this
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
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

/**
 * The shadow and (in "Under the lyrics" mode) the god rays, full screen, from
 * the copy of the letters in [backdrop]. Draws nothing when there is neither,
 * when no lyric view is up, below API 31, or with the low-performance glass
 * switch on.
 */
@Composable
internal fun LyricBackdropFx(backdrop: LyricBackdrop, modifier: Modifier = Modifier) {
    val fx = LocalLyricsFx.current
    if (LocalLowPerformance.current.disableLiquidGlass) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val capture = backdrop.capture
    val letters = capture.layer ?: return
    val light = backdrop.light
    val shader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) rememberGodRaysShader() else null
    val raysLight = if (shader != null && !fx.godRaysOnTop) light else null
    val shadow = fx.shadowDepth > LyricShadowGeometry.OFF
    if (raysLight == null && !shadow) return
    val origin = remember { mutableStateOf(Offset.Zero) }

    Box(
        modifier
            .fillMaxSize()
            .then(if (raysLight != null) Modifier.backdropFrame(raysLight.surface) else Modifier)
            .onGloballyPositioned { origin.value = it.positionInRoot() }
            .graphicsLayer {
                if (size.minDimension <= 0f) return@graphicsLayer
                compositingStrategy = CompositingStrategy.Offscreen
                val rays = if (raysLight != null && shader != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setGodRayUniforms(
                        shader, raysLight, raysLight.frame(size.width, size.height),
                        raysOnly = true, emission = LYRIC_COPY_EMISSION,
                    )
                    RenderEffect.createRuntimeShaderEffect(shader, "content")
                } else {
                    null
                }
                val shade = if (shadow) lyricShadowEffect(fx, light?.frameFor(origin.value)) else null
                renderEffect = when {
                    rays != null && shade != null -> RenderEffect.createBlendModeEffect(shade, rays, BlendMode.SRC_OVER)
                    rays != null -> rays
                    else -> shade
                }?.asComposeRenderEffect()
            }
            .drawBehind {
                if (letters.isReleased) return@drawBehind
                val at = capture.originInRoot - origin.value
                translate(at.x, at.y) {
                    drawLayer(letters)
                    if (capture.edgeFade) {
                        // The same feather lyricsEdgeFade gives the real letters,
                        // or a line half-scrolled out would leave its shadow and
                        // its light cut off hard at the surface's edge.
                        val h = capture.size.height
                        if (h > 0f) {
                            val top = (h * 0.05f).coerceAtMost(14.dp.toPx()) / h
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
            },
    )
}
