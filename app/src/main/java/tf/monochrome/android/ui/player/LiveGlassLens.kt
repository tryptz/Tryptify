package tf.monochrome.android.ui.player

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import dev.chrisbanes.haze.HazeState
import kotlin.math.ceil

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
 * offset to where this pane sits, blurs it, and then runs [LIVE_LENS_SRC] over
 * the blurred result: blur first, then bend. What comes out is the live backdrop, bent by the same
 * rounded rim as the slab, under the same frost tint the haze pane used.
 *
 * It replaces the haze pane under a punched slab; the slab still draws on top
 * and supplies the tint, the rim light and the control holes.
 *
 * Same rule as any haze pane: it must be a sibling of the haze source, never
 * inside it. Drawing the source's layer from inside itself would recurse.
 *
 * Prototype: wired into [GlassPanel], the full player's disc and dock, and
 * the mini player and tab bar, behind [LIVE_LENS_GLASS]. The mini player and
 * the tab bar are one material: same lens, same [LIVE_LENS_CHROME_BLUR_SHARE],
 * same tint (docs/ui-invariants.md).
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
    /**
     * What lies behind the page, laid under the capture so the lens is opaque.
     * The same colour the haze pane it replaces used as `HazeStyle.backgroundColor`.
     */
    ground: Color = MaterialTheme.colorScheme.background,
): Modifier {
    val shader = remember { runCatching { RuntimeShader(LIVE_LENS_SRC) }.getOrNull() } ?: return this
    val view = LocalView.current
    val divisor = LocalLensDivisor.current
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
    // The backdrop is blurred first, then bent. The blur runs over a margin of
    // the page around the pane as well as under it. That makes it a real
    // backdrop blur: the rim mixes in whatever lies just past the glass. Blurring
    // only the pane's own rectangle clamps at its edges, and smears the edge row
    // straight into the rim, which is the one band that refracts. So the
    // backdrop goes into its own layer, inflated by the margin, and the blur and
    // then the lens run there. The pane's clip trims it back to the glass.
    val backdrop = rememberGraphicsLayer()
    // Haze's capture holds only the page's content, not the app background
    // behind it, so it is transparent between the text. Drawn as is, the lens
    // was see-through exactly where it mattered: the blurred, bent copy of a
    // line of text lay over the real, sharp line underneath, and the sharp one
    // read straight through. The haze pane avoids this with an opaque
    // `HazeStyle.backgroundColor`; the lens lays [ground] down first.
    val pageColor = ground.copy(alpha = 1f)
    val effect = remember { LensEffectCache() }
    return this
        .onGloballyPositioned { anchor.screen = it.positionOnScreen() }
        .clip(shape)
        .drawBehind {
            redraw.intValue
            if (size.minDimension <= 0f) return@drawBehind
            // See LIVE_LENS_BLUR_SHARE for why a fifth. The slider is the only
            // say: 0 is crisp, unblurred refraction. A 6dp floor was tried and
            // taken out on device — at 0 the listener wants clear glass.
            val blurPx = glass.hazeBlurDp * blurShare * density
            val blurOn = blurPx >= 0.5f
            // A gaussian reaches about 3 sigma, and Android's blur radius maps to
            // sigma ≈ 0.58 × radius + 0.5. Twice the radius plus 2px covers that.
            val margin = if (blurOn) ceil(blurPx * 2f + 2f) else 0f
            val (lensR, lensW) = lensRimPx(corner, size, glass.roundness)
            // Asked for lighter glass (LocalLensDivisor), and only where the
            // blur is wide enough that a smaller copy loses next to nothing it
            // would keep: the backdrop, its blur and its bend all run at 1 / k
            // resolution, every px size scaled with them, and the result is
            // drawn back up to the pane. The bend is the same — the rim's
            // slope is a ratio of its own width — and the clip, the slab and
            // its rim light on top stay at full resolution, so the edge is as
            // crisp as ever.
            val k = if (divisor > 1 && blurPx >= REDUCED_LENS_MIN_BLUR_PX) divisor.toFloat() else 1f
            backdrop.renderEffect = effect.get(
                LensEffectKey(size, margin, blurPx, lensR, lensW, glass, frost, k),
            ) {
                shader.setFloatUniform("uSize", size.width / k, size.height / k)
                shader.setFloatUniform("uMargin", margin / k, margin / k)
                shader.setFloatUniform("uLensR", lensR / k)
                shader.setFloatUniform("uLensW", lensW / k)
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
                // Chain order: the inner effect (the blur) runs first and the
                // outer one (the lens) bends its output.
                if (blurOn) {
                    RenderEffect.createChainEffect(
                        lens,
                        RenderEffect.createBlurEffect(blurPx / k, blurPx / k, Shader.TileMode.CLAMP),
                    )
                } else {
                    lens
                }.asComposeRenderEffect()
            }

            val windowId = view.windowId
            val here = anchor.screen
            var drew = 0
            val inflated = IntSize(
                ceil((size.width + 2f * margin) / k).toInt(),
                ceil((size.height + 2f * margin) / k).toInt(),
            )
            backdrop.record(inflated) {
                drawRect(pageColor)
                scale(1f / k, pivot = Offset.Zero) {
                    hazeState.areas
                        .filter { it.windowId == null || it.windowId == windowId }
                        .sortedBy { it.zIndex }
                        .forEach { area ->
                            val layer = area.contentLayer ?: return@forEach
                            if (layer.isReleased) return@forEach
                            val at = area.positionOnScreen - here
                            translate(at.x + margin, at.y + margin) { drawLayer(layer) }
                            drew++
                        }
                }
            }
            translate(-margin, -margin) {
                if (k == 1f) drawLayer(backdrop) else scale(k, pivot = Offset.Zero) { drawLayer(backdrop) }
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

/** Everything the lens's RenderEffect is built from; a change rebuilds it. */
private data class LensEffectKey(
    val size: Size,
    val margin: Float,
    val blurPx: Float,
    val lensR: Float,
    val lensW: Float,
    val glass: tf.monochrome.android.domain.model.PlayerGlassSettings,
    val frost: Color,
    val divisor: Float,
)

/**
 * The last RenderEffect and the key it was built from. The shader's uniforms
 * are read when the effect is created, so it is rebuilt whenever an input
 * changes. Otherwise the same effect is kept, so an idle frame allocates
 * nothing.
 */
private class LensEffectCache {
    private var key: LensEffectKey? = null
    private var value: androidx.compose.ui.graphics.RenderEffect? = null

    fun get(
        k: LensEffectKey,
        build: () -> androidx.compose.ui.graphics.RenderEffect,
    ): androidx.compose.ui.graphics.RenderEffect {
        val v = value
        if (v != null && k == key) return v
        return build().also { key = k; value = it }
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

/**
 * The live lens's resolution, as a divisor: 1 is full; 2 runs its backdrop,
 * blur and bend at half resolution and draws the result back up. Provided by
 * a screen whose backdrop redraws every frame — the galaxy, when the
 * listener asks for lighter glass — where every pane's lens is redone every
 * frame with it. See [liveGlassLens].
 */
internal val LocalLensDivisor = staticCompositionLocalOf { 1 }

/**
 * The least blur (radius, px) a lens is run smaller with. At half resolution
 * that is still a sigma of four of its px: a gaussian that wide passes
 * nothing near the half-size grid's limit, so what the blur keeps fits the
 * half-size copy. What is not exact is the shrink before it — a sharp point
 * finer than two px is averaged in, not filtered out — which is why this is a
 * switch the listener judges by eye. Narrower than this, the lens stays full.
 */
private const val REDUCED_LENS_MIN_BLUR_PX = 12f

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
// pane and a margin of uMargin around it, already blurred by the chained
// effect. p is in that inflated layer's px; p - uMargin is the pane's own.
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
uniform float2 uMargin;       // backdrop margin around the pane, px each side
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
        float3 N = normalize(float3(lensRimSlope(p - uMargin, uSize, uLensR, uLensW) * uDepth, 1.0));
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
