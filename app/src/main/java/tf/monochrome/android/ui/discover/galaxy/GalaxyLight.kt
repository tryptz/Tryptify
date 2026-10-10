package tf.monochrome.android.ui.discover.galaxy

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asComposeRenderEffect
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

/**
 * The galaxy's god rays: light from one bright thing — the core, or the star
 * whose planets you are looking at — with the shadows of everything in its
 * way streaming out through it. Volumetric light scattering as a screen-space
 * post-process, the occlusion version of GPU Gems 3, ch. 13.
 *
 * The layer this runs over is a **light pass**, never the scene: the light and
 * its glow drawn in colour, and whatever stands in front of it — planets,
 * moons, dust — drawn over it in black. Every pixel marches toward the light
 * and averages what it passes:
 *
 * - **Light**: the colour it meets, decaying step by step. A march that runs
 *   into black finds nothing there, so a planet leaves a dark wedge behind it
 *   in the shafts, pointing straight away from its star — the way its shadow
 *   would fall through a dusty sky.
 * - **Shade**: the black it meets inside the light's glow. That darkens the
 *   scene behind the planet too — the dust and the gas — so the shadow is
 *   more than a gap in the light. Black far from the light blocks nothing and
 *   casts nothing.
 *
 * It replaced the lyric engine's rays here, which made every bright star a
 * starburst and drew stripes of its own round the core: rays with nothing
 * casting them. These come only from what is actually in the light's way.
 *
 * A pixel takes only the samples that can add anything: those within the
 * light's [LightSpot.reach]. Out among the stars that is about half of them;
 * a pixel whose march never comes that close is left untouched, and a pass no
 * pixel can see is not run at all ([reachesView]).
 */
private const val LIGHT_SRC = """
uniform shader content;
uniform float2 uLight;
uniform float uGlowR;
uniform float uSamples;
uniform float uDensity;
uniform float uDecay;
uniform float uExposure;
uniform float uShade;
uniform float uFrame;
uniform float uReach;

half4 main(float2 p) {
    float2 toL = uLight - p;
    float dist = length(toL);
    float2 dir = toL / max(dist, 0.001);
    float stepLen = dist * uDensity / uSamples;
    // Interleaved gradient noise as each pixel's start: the march's banding
    // becomes fine grain, so a few dozen samples are enough.
    float2 jp = p + float2(5.588238 * uFrame, 0.0);
    float jitter = fract(52.9829189 * fract(dot(jp, float2(0.06711056, 0.00583715))));
    // Only samples within uReach of the light can add anything: all of the
    // light is inside it, and past it the shade is under half a step of 8-bit
    // alpha. Sample i lies (i + 1 - jitter) steps from this pixel toward the
    // light, so the first that counts is the first to come within uReach, and
    // a pixel whose march never does is untouched by this light.
    float first = max(ceil((dist - uReach) / max(stepLen, 0.0001) - 1.0 + jitter), 0.0);
    if (first >= uSamples) return half4(0.0);
    float3 light = float3(0.0);
    float shade = 0.0;
    float w = pow(uDecay, first);
    for (int i = 0; i < 40; i++) {
        float fi = float(i);
        if (fi < uSamples && fi >= first) {
            float2 s = p + dir * ((fi + 1.0 - jitter) * stepLen);
            float4 c = float4(content.eval(s));
            light += c.rgb * w;
            // Cover that gives no light is something in the way.
            float block = clamp(c.a - max(c.r, max(c.g, c.b)), 0.0, 1.0);
            float g = distance(s, uLight) / uGlowR;
            shade += block * exp(-2.0 * g * g) * w;
            w *= uDecay;
        }
    }
    float3 col = 1.0 - exp(-light * uExposure);
    float a = max(col.r, max(col.g, col.b));
    float sa = clamp(shade * uShade, 0.0, 0.6);
    return half4(half3(col), half(a + sa * (1.0 - a)));
}
"""

/** Where this frame's light is and how it shines, in the light pass's own px. Reused, never reallocated. */
internal class LightSpot {
    var x = 0f
    var y = 0f

    /** How far the light's glow reaches on screen, px: what stands inside it casts a shadow. */
    var glowR = 1f

    /**
     * How far from the light, px, anything in its pass can add to the rays:
     * the glow and whatever is drawn in colour, and the shade's reach
     * ([GalaxyLight.SHADE_REACH] glow radii). Infinite when that is not known,
     * and then every sample is taken, as they all used to be.
     */
    var reach = Float.POSITIVE_INFINITY

    /** 0..1: how much of this light there is (it fades as you arrive at another). */
    var strength = 0f
}

/** One light's shader, made once; [effect] sets this frame on it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GalaxyLight {
    private val shader = RuntimeShader(LIGHT_SRC)

    /**
     * The render effect for [spot] at [time] seconds: [exposure] and [shade]
     * as shares of the usual (the listener's ray strength and shade).
     */
    fun effect(
        spot: LightSpot,
        time: Float,
        exposure: Float,
        shade: Float,
        /** The light pass's resolution against the view's: its pixels are this many of the view's. */
        resolution: Float = 1f,
    ): androidx.compose.ui.graphics.RenderEffect {
        val weights = (1f - DECAY.pow(SAMPLES)) / (1f - DECAY)
        shader.setFloatUniform("uLight", spot.x * resolution, spot.y * resolution)
        shader.setFloatUniform("uGlowR", (spot.glowR * resolution).coerceAtLeast(1f))
        shader.setFloatUniform("uSamples", SAMPLES.toFloat())
        shader.setFloatUniform("uDensity", DENSITY)
        shader.setFloatUniform("uDecay", DECAY)
        shader.setFloatUniform("uExposure", EXPOSURE * exposure * spot.strength / weights)
        shader.setFloatUniform("uShade", SHADE * shade * spot.strength / weights)
        shader.setFloatUniform("uFrame", ((floor(time * 60f).toInt() % 64 + 64) % 64).toFloat())
        shader.setFloatUniform("uReach", if (spot.reach.isFinite()) spot.reach * resolution else NO_REACH)
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

    companion object {
        const val SAMPLES = 32
        const val DENSITY = 0.92f

        /** Per sample: the shafts fade out a little past the glow, and deep space stays dark. */
        const val DECAY = 0.95f
        const val EXPOSURE = 0.8f
        const val SHADE = 1.6f

        /**
         * How far the shade reaches, in glow radii. It falls off as
         * exp(-2 g²), and at 2 radii that is 3.4e-4: at the strongest shade
         * setting, everything past it together darkens a pixel by less than
         * half a step of 8-bit alpha, so a sample past it is left out.
         */
        const val SHADE_REACH = 2f

        /** A reach so far no pixel's samples are skipped, for a light whose reach is not known. */
        private const val NO_REACH = 1e9f

        /**
         * The first of the samples of a pixel [dist] px from the light that
         * comes within [reach] of it, with the march started at [jitter] —
         * the shader's own arithmetic. [SAMPLES] or more: none does.
         */
        fun firstSample(dist: Float, reach: Float, jitter: Float): Int {
            if (!reach.isFinite()) return 0
            val step = (dist * DENSITY / SAMPLES).coerceAtLeast(0.0001f)
            return ceil((dist - reach) / step - 1f + jitter).toInt().coerceAtLeast(0)
        }

        /**
         * Whether any pixel of a [width] × [height] view can be lit or shaded
         * by [spot]. A pixel's march stops [DENSITY] of the way to the light,
         * so the nearest it comes is the rest of the way; when that is
         * outside the reach for the pixel nearest the light, it is for all
         * of them, and the pass would be transparent everywhere.
         */
        fun reachesView(spot: LightSpot, width: Float, height: Float): Boolean {
            if (!spot.reach.isFinite()) return true
            val dx = maxOf(0f - spot.x, 0f, spot.x - width)
            val dy = maxOf(0f - spot.y, 0f, spot.y - height)
            return (1f - DENSITY) * hypot(dx, dy) <= spot.reach
        }
    }
}

/** A light's shader on Android 13 and up, when [enabled]; null if it will not compile. */
@Composable
internal fun rememberGalaxyLight(enabled: Boolean): GalaxyLight? =
    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { runCatching { GalaxyLight() }.getOrNull() }
    } else {
        null
    }
