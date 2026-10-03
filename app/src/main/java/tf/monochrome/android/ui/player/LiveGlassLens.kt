package tf.monochrome.android.ui.player

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import dev.chrisbanes.haze.HazeState

/**
 * Glass that bends the real screen behind it, the way iOS draws it.
 *
 * Every other pane in the app refracts a stand-in: [LIQUID_GLASS_SRC] spends its
 * one RenderEffect input on the alpha heightfield its bevel is built from, so the
 * backdrop it lenses has to be reconstructed or handed over as a thumbnail. The
 * lens rim does not need a heightfield — it is computed from the pane's corner —
 * and that frees the input for the backdrop itself.
 *
 * So this layer draws Haze's own capture of the screen behind it (the
 * [HazeState] areas' content layers, the same recording the haze blur samples),
 * offset to where this pane sits, and runs a very light blur and then
 * [LIVE_LENS_SRC] over it. What comes out is the live backdrop, bent by the same
 * rounded rim as the slab, under the same frost tint the haze pane used.
 *
 * It replaces the haze pane under a punched slab; the slab still draws on top
 * and supplies the tint, the rim light and the control holes.
 *
 * Same rule as any haze pane: it must be a sibling of the haze source, never
 * inside it. Drawing the source's layer from inside itself would recurse.
 *
 * Prototype: wired into [GlassPanel] and the full player's disc and dock,
 * behind [LIVE_LENS_GLASS]. Not the mini player, which keeps the tab bar's
 * frost: it is the tab bar's material, and over sharp page text a clear lens
 * let the rows behind fight its title.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
internal fun Modifier.liveGlassLens(
    hazeState: HazeState,
    corner: Dp,
    frost: Color,
    glass: tf.monochrome.android.domain.model.PlayerGlassSettings,
    /**
     * The blur as a share of `hazeBlurDp`. The chrome bars pass
     * [LIVE_LENS_CHROME_BLUR_SHARE], a little more than panels get, so the page
     * behind does not fight their labels; both bars pass the same, so they match.
     */
    blurShare: Float = LIVE_LENS_BLUR_SHARE,
): Modifier {
    val shader = remember { runCatching { RuntimeShader(LIVE_LENS_SRC) }.getOrNull() } ?: return this
    val view = LocalView.current
    // Where this pane is on screen. Written by layout, read by draw; not state,
    // because the pre-draw check below is what turns a change into a redraw.
    val anchor = remember { LensAnchor() }
    // Read in draw so that bumping it redraws this layer.
    val redraw = remember { mutableIntStateOf(0) }

    // The capture's display list updates itself — it is drawn here as a render
    // node, so a source that scrolls repaints this lens without any help. What
    // does not reach us on its own is geometry: the source or this pane moving,
    // or the source swapping its layer. Check those once a frame and redraw only
    // when one changed, so an idle screen stays idle.
    DisposableEffect(view, hazeState) {
        var last = 0L
        val listener = ViewTreeObserver.OnPreDrawListener {
            var sig = anchor.screen.hashCode().toLong()
            for (area in hazeState.areas) {
                sig = sig * 31 + area.positionOnScreen.hashCode()
                sig = sig * 31 + area.size.hashCode()
                sig = sig * 31 + System.identityHashCode(area.contentLayer)
            }
            if (sig != last) {
                last = sig
                redraw.intValue++
            }
            true
        }
        view.viewTreeObserver.addOnPreDrawListener(listener)
        onDispose { view.viewTreeObserver.removeOnPreDrawListener(listener) }
    }

    val shape = remember(corner) { lensClipShape(corner) }
    return this
        .onGloballyPositioned { anchor.screen = it.positionOnScreen() }
        .graphicsLayer {
            if (size.minDimension <= 0f) return@graphicsLayer
            val (lensR, lensW) = lensRimPx(corner, size, glass.roundness)
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uLensR", lensR)
            shader.setFloatUniform("uLensW", lensW)
            shader.setFloatUniform("uRefraction", glass.refraction)
            shader.setFloatUniform("uDepth", glass.depth)
            shader.setFloatUniform("uDispersion", glass.dispersion)
            // Premultiplied, for a plain src-over in the shader. Zero: the
            // live lens is clear glass. Any frost veil read on device as a
            // dull, frosted pane over the backdrop, which was asked to go;
            // the slab's thin tint on top is the only colour the glass adds.
            val fa = frost.alpha * LIVE_LENS_FROST_SHARE
            shader.setFloatUniform("uFrost", frost.red * fa, frost.green * fa, frost.blue * fa, fa)
            val lens = RenderEffect.createRuntimeShaderEffect(shader, "content")
            // See LIVE_LENS_BLUR_SHARE for why a fifth. Never below
            // LIVE_LENS_MIN_BLUR, though: glass blurs the text behind it, and
            // with "Backdrop blur" turned down on device the page read straight
            // through every pane, sharp. The slider can add blur, not remove it.
            val blurPx = max(glass.hazeBlurDp * blurShare, LIVE_LENS_MIN_BLUR.value) * density
            renderEffect = if (blurPx >= 0.5f) {
                RenderEffect.createChainEffect(
                    lens,
                    RenderEffect.createBlurEffect(blurPx, blurPx, Shader.TileMode.CLAMP),
                )
            } else {
                lens
            }.asComposeRenderEffect()
            this.shape = shape
            clip = true
        }
        .drawBehind {
            redraw.intValue
            val windowId = view.windowId
            val here = anchor.screen
            var drew = 0
            hazeState.areas
                .filter { it.windowId == null || it.windowId == windowId }
                .sortedBy { it.zIndex }
                .forEach { area ->
                    val layer = area.contentLayer ?: return@forEach
                    if (layer.isReleased) return@forEach
                    val at = area.positionOnScreen - here
                    translate(at.x, at.y) { drawLayer(layer) }
                    drew++
                }
            // With nothing to draw the lens is a frost over transparent: the
            // page shows through unbent. Say so once, so a report from a
            // device carries it in its recent log.
            if (drew == 0 && !LensDiagnostics.warnedEmpty) {
                LensDiagnostics.warnedEmpty = true
                android.util.Log.w(
                    LENS_TAG,
                    "no haze content layer to draw (areas=${hazeState.areas.size}, " +
                        "layers=${hazeState.areas.count { it.contentLayer != null }})",
                )
            }
        }
}

/**
 * The clip for a lens of [corner], which may be [Dp.Infinity] for a disc or a
 * pill. `RoundedCornerShape(Dp.Infinity)` cannot be drawn: Compose scales
 * corners that overflow the box by minDimension / their sum, which for
 * infinite corners is 0, and infinity × 0 is NaN — it throws on the first
 * frame. The full player's disc crashed exactly that way. 50% is the same
 * shape with finite corners.
 */
internal fun lensClipShape(corner: Dp): Shape =
    if (corner.value.isFinite()) RoundedCornerShape(corner) else RoundedCornerShape(percent = 50)

/** Off restores the haze pane under every GlassPanel and the player's disc and dock, exactly. */
internal const val LIVE_LENS_GLASS = true

private const val LENS_TAG = "LiveGlassLens"

/** One-shot flags for the lens's own diagnostics, so they log once per process. */
private object LensDiagnostics {
    @Volatile var warnedEmpty = false
}

/**
 * The live lens's blur as a share of the haze pane's (`hazeBlurDp`): a fifth,
 * 6.4dp on Liquid. Glass frosts what is behind it as well as bending it — at
 * 2-3dp page text read straight through and fought the labels on top. Much
 * more and there is no detail left to bend: at 10dp the rim's bend all but
 * vanished (checked offline before shipping). Everything shares it.
 */
private const val LIVE_LENS_BLUR_SHARE = 0.2f

/**
 * The mini player's and the tab bar's blur share. The same as every other
 * pane's now; kept as its own name so the two bars keep passing one value.
 */
internal const val LIVE_LENS_CHROME_BLUR_SHARE: Float = LIVE_LENS_BLUR_SHARE

/** The live lens's frost as a share of the haze pane's tint alpha: none, clear glass. */
private const val LIVE_LENS_FROST_SHARE = 0f

/** The least a live lens blurs what is behind it, whatever "Backdrop blur" says. */
private val LIVE_LENS_MIN_BLUR = 6.dp

private class LensAnchor {
    var screen: Offset = Offset.Zero
}

/** Whether [LIVE_LENS_SRC] compiles here, asked once per process. */
internal val liveLensCompiles: Boolean by lazy {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        runCatching { RuntimeShader(LIVE_LENS_SRC) }
            .onFailure { android.util.Log.w(LENS_TAG, "live lens shader did not compile: ${it.message}") }
            .getOrNull() != null
}

// The live backdrop, bent by the lens rim. `content` is the screen behind the
// pane (already lightly blurred by the chained effect), in this pane's own px.
//
// The bend is [LIQUID_GLASS_SRC]'s with a lens rim, term for term: the same
// rounded-rim normal, Snell at eta 0.66 with the same per-channel dispersion,
// and the same pixel scale — refraction × rim width × 5 — so this layer and the slab
// on top of it move together. A convex rim bends rays inward, so every sample
// lands inside the pane and the layer's own bounds are always enough.
private const val LIVE_LENS_SRC = """
$LENS_RIM_SKSL
uniform shader content;
uniform float2 uSize;
uniform float uLensR;
uniform float uLensW;
uniform float uRefraction;
uniform float uDepth;
uniform float uDispersion;
uniform float4 uFrost;        // premultiplied tint laid over the bent backdrop

half4 main(float2 p) {
    float4 c;
    if (uLensW < 0.5) {
        c = float4(content.eval(p));
    } else {
        float3 N = normalize(float3(lensRimSlope(p, uSize, uLensR, uLensW) * uDepth, 1.0));
        float3 I = float3(0.0, 0.0, -1.0);
        float ds = 0.06 * uDispersion;
        float k = uRefraction * uLensW * 5.0;
        float4 cR = float4(content.eval(p + refract(I, N, 0.66 - ds).xy * k));
        float4 cG = float4(content.eval(p + refract(I, N, 0.66).xy * k));
        float4 cB = float4(content.eval(p + refract(I, N, 0.66 + ds).xy * k));
        c = float4(cR.r, cG.g, cB.b, cG.a);
    }
    return half4(uFrost + c * (1.0 - uFrost.a));
}
"""
