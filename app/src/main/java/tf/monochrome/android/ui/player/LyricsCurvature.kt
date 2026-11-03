// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.player

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.abs
import kotlin.math.cos

/**
 * The lyrics on a curved tube, as a CRT draws them (Lyrics FX › Curvature,
 * [tf.monochrome.android.domain.model.LyricsFxSettings.curvatureDeg]): the
 * glass bowed [topDeg] away from the viewer toward its top and bottom edges
 * and [sideDeg] toward its sides, with the flat lyrics seen through it. The middle stays as it is;
 * further out each line bows outward and its ends draw in, more toward the
 * corners, which round off into nothing ([crtSource]). Not a fish-eye: it
 * swells nothing, the sides' middles stay at the sides, and a line through
 * the middle stays straight.
 *
 * Put it innermost on the lyric surface, inside the copy of the letters the
 * backdrop takes for their shadow and their light, so the shadow and the
 * shafts bend with them and the glass bevels the bent letters.
 *
 * With [focusInRoot] (Lyrics FX › Curve: the sung line or the sung word) the
 * tube is that box alone, read in the draw phase so it follows the scroll and
 * hops from word to word, a little taller than the box so the letters fit in
 * it ([wide] adds room at its sides for a word). Over the sung letters it
 * bends fully; through the room around them the bend eases off to nothing at
 * the tube's edge ([localTubeSource]), so it meets the flat lyrics outside
 * without a seam. The bend is taken in the box's width both ways, as a line is
 * a slice of a round tube: taken in the box's own proportions, a short, wide
 * line was bent as a whole screen is, and its letters leaned like italics at
 * its ends (seen on device). That room reaches into the lines above and below (and the
 * words beside a word): cut off sharply there, the bend tore and doubled their
 * letters (seen on device). No box (the line off screen) leaves everything
 * flat.
 *
 * Android 13 and later; flat below, and under [MIN_CURVATURE_DEG].
 *
 * The angles are read in the layer block, each frame, so a music link on
 * either bends the tube without recomposing anything.
 *
 * The layer is there whatever the angles: flat, it draws with no effect.
 * It sits inside the letters' copy ([captureLetters]), and a layer put into
 * that chain while the lyrics are on screen (the slider leaving 0) made
 * Compose hand it the glass layer that holds the copy, unre-recorded: the
 * copy and that layer each drew the other, and the render thread walked the
 * loop until its stack ran out (a native crash, seen on device four times).
 * Its parts depend only on the Android version and the shader, which do not
 * change while the app runs.
 */
internal fun Modifier.lyricCurvature(
    topDeg: () -> Float,
    sideDeg: () -> Float,
    focusInRoot: (() -> Rect?)? = null,
    wide: Boolean = false,
): Modifier =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        this
    } else {
        this.composed {
            val shader = curvatureShader
            val origin = remember { mutableStateOf(Offset.Zero) }
            if (shader == null) {
                Modifier
            } else {
                Modifier
                    .onGloballyPositioned { origin.value = it.positionInRoot() }
                    .curved(shader, topDeg, sideDeg, focusInRoot, origin, wide)
            }
        }
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private fun Modifier.curved(
    shader: RuntimeShader,
    topDeg: () -> Float,
    sideDeg: () -> Float,
    focusInRoot: (() -> Rect?)?,
    origin: State<Offset>,
    wide: Boolean,
): Modifier =
    graphicsLayer {
        val top = topDeg()
        val side = sideDeg()
        if (size.width <= 0f || size.height <= 0f || maxOf(top, side) < MIN_CURVATURE_DEG) {
            renderEffect = null
            return@graphicsLayer
        }
        val dome = if (focusInRoot == null) {
            Rect(0f, 0f, size.width, size.height)
        } else {
            val box = focusInRoot()?.translate(-origin.value)
            focusDome(box, size.width, size.height, wide)
        }
        if (dome == null) {
            renderEffect = null
            return@graphicsLayer
        }
        // All seven, right before the effect: the shader is shared, and one
        // left out would carry another surface's value.
        shader.setFloatUniform("uSize", size.width, size.height)
        shader.setFloatUniform("uCenter", dome.center.x, dome.center.y)
        shader.setFloatUniform("uRadii", dome.width / 2f, dome.height / 2f)
        shader.setFloatUniform("uLocal", if (focusInRoot == null) 0f else 1f)
        shader.setFloatUniform("uWarp", crtWarp(side), crtWarp(top))
        // The letters' share of the tube: a word has room beside it, a line (the
        // surface's width) a slim margin, so neither has a seam at its sides.
        shader.setFloatUniform("uInner", if (wide) 1f / WORD_SIDE_ROOM else LINE_SIDE_INNER, 1f / DOME_HEADROOM)
        // Its height in its width: the bend is taken in the width both ways.
        shader.setFloatUniform("uAspect", if (dome.width > 0f) dome.height / dome.width else 1f)
        renderEffect = RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

/**
 * The tube over [box] (in the surface's own px), on a surface of [width]:
 * the box's sides kept to the surface (a line's run past it), its height
 * raised by [DOME_HEADROOM] so the letters fit in it, and [wide] adding
 * [WORD_SIDE_ROOM] at the sides for a single word. Null without a box, or
 * for one with no size.
 */
internal fun focusDome(box: Rect?, width: Float, height: Float, wide: Boolean): Rect? {
    if (box == null) return null
    val left = box.left.coerceIn(0f, width)
    val right = box.right.coerceIn(0f, width)
    if (right - left < 1f || box.height < 1f) return null
    val halfW = (right - left) / 2f * if (wide) WORD_SIDE_ROOM else 1f
    val halfH = box.height / 2f * DOME_HEADROOM
    val cx = (left + right) / 2f
    val cy = box.center.y
    return Rect(cx - halfW, cy - halfH, cx + halfW, cy + halfH)
}

/** The tube over a line or a word stands this much taller than it, so no letter reaches its rim. */
internal const val DOME_HEADROOM = 1.6f

/** And this much wider than a word, so its ends are not pressed against the rim. */
internal const val WORD_SIDE_ROOM = 1.35f

/** A line's tube bends fully this far toward its sides, then eases off to its edge. */
internal const val LINE_SIDE_INNER = 0.92f

/**
 * How far the tube bows at [degrees]: `1 / cos θ − 1`, how much further out
 * than its corner on the glass a corner of the flat lyrics lies. The CRT
 * shader's `warp`: about 1/32 at 14.1°, the classic tube's, 0.155 at 30°, 1
 * at 60°. 0 below [MIN_CURVATURE_DEG].
 */
internal fun crtWarp(degrees: Float): Float {
    if (degrees < MIN_CURVATURE_DEG) return 0f
    return (1.0 / cos(Math.toRadians(degrees.coerceAtMost(MAX_CURVATURE_DEG).toDouble())) - 1.0).toFloat()
}

/**
 * Where on the flat lyrics the point [x], [y] of the tube shows, both as
 * shares of the way out from its middle (−1 to 1 across it): each pulled
 * outward by the other's square, `x · (1 + y² · warp)`, so a point's way out
 * grows toward the corners and never along the middle lines. [sideDeg] bows
 * the sides, [topDeg] the top and bottom. Null past the tube's edge, its
 * rounded corners. The shader's sum, here to be checked.
 */
internal fun crtSource(x: Float, y: Float, topDeg: Float, sideDeg: Float = topDeg): Pair<Float, Float>? {
    val sx = x * (1f + y * y * crtWarp(sideDeg))
    val sy = y * (1f + x * x * crtWarp(topDeg))
    if (abs(sx) > 1f || abs(sy) > 1f) return null
    return sx to sy
}

/**
 * Where on the flat lyrics the point [x], [y] of a tube over the sung line or
 * word shows, both as shares of the way out from its middle (−1 to 1 across
 * it). Over the letters, the middle [innerX] by [innerY] of it, it is
 * [crtSource]'s bow taken in the tube's width both ways ([aspect] is its
 * height in its width), held to the tube; through the room around them the bow
 * eases off, smoothly, to none at the tube's edge, so the flat lyrics outside
 * meet it without a seam. The shader's local sum, here to be checked.
 */
internal fun localTubeSource(
    x: Float,
    y: Float,
    topDeg: Float,
    sideDeg: Float,
    innerX: Float,
    innerY: Float,
    aspect: Float = 1f,
): Pair<Float, Float> {
    if (abs(x) >= 1f || abs(y) >= 1f) return x to y
    // In the width both ways: the same scale across and down.
    val ey = y * aspect
    val sx = (x * (1f + ey * ey * crtWarp(sideDeg))).coerceIn(-1f, 1f)
    val sy = (y * (1f + x * x * crtWarp(topDeg))).coerceIn(-1f, 1f)
    val w = easeOff(innerX, abs(x)) * easeOff(innerY, abs(y))
    return (x + (sx - x) * w) to (y + (sy - y) * w)
}

/** 1 within [inner], easing to 0 at 1: the shader's `1 − smoothstep(inner, 1, t)`. */
private fun easeOff(inner: Float, t: Float): Float {
    if (inner >= 0.999f) return 1f
    val u = ((t - inner) / (1f - inner)).coerceIn(0f, 1f)
    return 1f - u * u * (3f - 2f * u)
}

/** The slider's far end; [crtWarp] is held there, where the tube bows four times the corners away. */
private const val MAX_CURVATURE_DEG = 75f

/** Below this the lyrics are drawn flat, with no layer at all. */
internal const val MIN_CURVATURE_DEG = 0.05f

/**
 * [CURVATURE_SHADER], compiled once for the whole process and shared by every
 * curved lyric surface. Safe because an effect copies the uniforms as it is
 * made, [curved] sets all five before each one, and it is never a paint
 * shader (see `liquidGlassShader` in LiquidGlass.kt).
 */
private val curvatureShader: RuntimeShader? by lazy {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        null
    } else {
        runCatching { RuntimeShader(CURVATURE_SHADER) }
            .onFailure { Log.w("LyricsCurvature", "curvature shader did not compile; lyrics drawn flat", it) }
            .getOrNull()
    }
}

/**
 * [crtSource] for every pixel, out from the tube's middle in its own
 * proportions, so the tube fits what it covers whatever its shape. Over the
 * whole surface (uLocal 0) a read past the tube's edge is clear: its rounded
 * corners. Over a line or a word (uLocal 1) it is [localTubeSource]: the bow
 * over the letters (uInner of the tube), easing off to none at the tube's
 * edge, and every pixel outside the box the flat lyrics, so the box has no
 * hole, no edge and no seam of its own.
 */
private const val CURVATURE_SHADER = """
uniform shader content;
uniform float2 uSize;
uniform float2 uCenter;
uniform float2 uRadii;
uniform float uLocal;
uniform float2 uWarp;
uniform float2 uInner;
uniform float uAspect;

float easeOff(float inner, float t) {
    if (inner >= 0.999) {
        return 1.0;
    }
    return 1.0 - smoothstep(inner, 1.0, t);
}

half4 main(float2 p) {
    float2 d = (p - uCenter) / uRadii;
    float2 s = d * float2(1.0 + d.y * d.y * uWarp.x, 1.0 + d.x * d.x * uWarp.y);
    float2 q = p;
    if (uLocal > 0.5) {
        if (abs(d.x) >= 1.0 || abs(d.y) >= 1.0) {
            return content.eval(p);
        }
        // The bend taken in the tube's width both ways, as a slice of a round tube.
        float ey = d.y * uAspect;
        float2 b = d * float2(1.0 + ey * ey * uWarp.x, 1.0 + d.x * d.x * uWarp.y);
        float w = easeOff(uInner.x, abs(d.x)) * easeOff(uInner.y, abs(d.y));
        q = uCenter + (d + (clamp(b, -1.0, 1.0) - d) * w) * uRadii;
    } else {
        if (abs(s.x) > 1.0 || abs(s.y) > 1.0) {
            return half4(0.0);
        }
        q = uCenter + s * uRadii;
    }
    if (q.x < 0.0 || q.y < 0.0 || q.x > uSize.x || q.y > uSize.y) {
        return half4(0.0);
    }
    return content.eval(q);
}
"""
