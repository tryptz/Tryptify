package tf.monochrome.android.ui.player

import android.graphics.BlendMode
import android.graphics.BlendModeColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.performance.LocalLowPerformance
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The lyrics' shadow: soft, on the background under the letters, falling away
 * from the light.
 *
 * It used to be part of the letters — each glyph stamped a second time in
 * near-black a couple of dp down-right, plus a tight contact shadow — and both
 * were drawn inside the glass layer, so the glass bevelled and relit them as
 * more glass. On a phone every letter came out as a block with a dark slab
 * stuck to it. Here the shadow is its own layer OUTSIDE the glass: a blurred,
 * offset, darkened copy of the finished letters' silhouette, laid under them.
 *
 * Apply it outside the side inset (a blur is cut off at its layer's edge, like
 * the rays) and inside [lyricGodRays], so the shafts pass over the shadow and
 * under the letters; the rays shader leaves the shadow out of what shines and
 * what blocks the light ([GOD_RAYS_SRC]'s `lit`).
 *
 * [LyricsFxSettings.shadowDepth] sets how dark and how soft; 0 is no shadow.
 * Needs API 31 (RenderEffect); dropped, like the glass, by the low-performance
 * switch.
 */
@Composable
internal fun Modifier.lyricShadow(rayLight: LyricRayLight? = null): Modifier {
    val fx = LocalLyricsFx.current
    if (fx.shadowDepth <= LyricShadowGeometry.OFF) return this
    if (LocalLowPerformance.current.disableLiquidGlass) return this
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return this
    return this.then(lyricShadowModifier(fx, rayLight))
}

@RequiresApi(Build.VERSION_CODES.S)
private fun lyricShadowModifier(fx: LyricsFxSettings, rayLight: LyricRayLight?): Modifier =
    Modifier.graphicsLayer {
        if (size.minDimension <= 0f) return@graphicsLayer
        // Same box as the rays layer it sits in, so the rays' frame is ours.
        val shadow = lyricShadowEffect(fx, rayLight?.frame(size.width, size.height))
        // The letters, untouched, over their shadow.
        renderEffect = RenderEffect
            .createBlendModeEffect(shadow, RenderEffect.createOffsetEffect(0f, 0f), BlendMode.SRC_OVER)
            .asComposeRenderEffect()
    }

/**
 * The shadow alone, from whatever letters this layer holds: their silhouette
 * blurred, moved away from the light ([ray], in this layer's pixels, or the
 * glass key light without one) and darkened. The letters themselves are not
 * in it — the caller lays them over it, or (the backdrop layer) leaves them to
 * the real ones drawn later.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal fun Density.lyricShadowEffect(fx: LyricsFxSettings, ray: RayFrame?): RenderEffect {
    val cast = LyricShadowGeometry.cast(fx, ray)
    val reach = cast.lengthDp.dp.toPx()
    val blur = LyricShadowGeometry.blurDp(fx.shadowDepth).dp.toPx()
    return RenderEffect.createColorFilterEffect(
        BlendModeColorFilter(
            android.graphics.Color.argb(LyricShadowGeometry.alpha(fx.shadowDepth), 0f, 0f, 0f),
            BlendMode.SRC_IN,
        ),
        RenderEffect.createOffsetEffect(
            cast.direction.x * reach,
            cast.direction.y * reach,
            RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL),
        ),
    )
}

/** Which way the lyrics' shadow falls, how far and how dark — off the GPU, so it can be tested. */
internal object LyricShadowGeometry {
    /** At or below this depth there is no shadow at all. */
    const val OFF = 0.01f

    /** A direction on screen (y down) and a length in dp. */
    data class Cast(val direction: Offset, val lengthDp: Float)

    /**
     * Away from the light. With god rays lighting the letters from in front,
     * that is away from the rays' own light, and the lower it lies the longer
     * the shadow; straight behind the line (90°) it drops short and straight
     * down. A backlight would throw the shadow toward the viewer, where there
     * is no background to land on, so then — and with no rays — it falls away
     * from the letter glass's key light at a middling 45°.
     */
    fun cast(fx: LyricsFxSettings, ray: RayFrame?): Cast {
        val depth = fx.shadowDepth.coerceIn(0f, 1f)
        val direction: Offset
        val elevationDeg: Float
        if (ray != null && fx.godRaySource != LyricsFxSettings.GOD_RAYS_BACKLIGHT) {
            val away = ray.center - ray.light
            val d = away.getDistance()
            direction = if (d > 1f) away / d else Offset(0f, 1f)
            elevationDeg = ray.elevationDeg
        } else {
            val a = fx.glassLightAngleDeg * (PI.toFloat() / 180f)
            // The key light sits along (cos a, -sin a) on screen.
            direction = Offset(-cos(a), sin(a))
            elevationDeg = 45f
        }
        val lean = 0.35f + 0.65f * cos(elevationDeg.coerceIn(0f, 90f) * (PI.toFloat() / 180f))
        return Cast(direction, (2f + 6f * depth) * lean)
    }

    /** Soft at any depth: a few dp of blur even when shallow, more as it deepens. */
    fun blurDp(depth: Float): Float = 2f + 7f * depth.coerceIn(0f, 1f)

    /** Never black: the background shows through even the deepest shadow. */
    fun alpha(depth: Float): Float = (0.15f + 0.5f * depth.coerceIn(0f, 1f)).coerceAtMost(0.65f)
}
