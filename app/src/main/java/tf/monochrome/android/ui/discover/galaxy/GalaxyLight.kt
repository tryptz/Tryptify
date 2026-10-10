package tf.monochrome.android.ui.discover.galaxy

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asComposeRenderEffect
import kotlin.math.floor
import kotlin.math.pow

/**
 * The galaxy's god rays: light from one bright thing — the core, or the star
 * whose planets you are looking at — with the shadows of everything in its
 * way streaming out through it. Volumetric light scattering as a screen-space
 * post-process, the occlusion version of GPU Gems 3, ch. 13.
 *
 * The layer this runs over is a **light pass**, never the scene: the light and
 * its glow drawn in colour, and whatever stands in front of it — planets,
 * moons, dust, the other stars, the nebulae — drawn over it in black. Every
 * pixel marches toward the light and averages what it passes:
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

half4 main(float2 p) {
    float2 toL = uLight - p;
    float dist = length(toL);
    float2 dir = toL / max(dist, 0.001);
    float stepLen = dist * uDensity / uSamples;
    // Interleaved gradient noise as each pixel's start: the march's banding
    // becomes fine grain, so a few dozen samples are enough.
    float2 jp = p + float2(5.588238 * uFrame, 0.0);
    float jitter = fract(52.9829189 * fract(dot(jp, float2(0.06711056, 0.00583715))));
    float3 light = float3(0.0);
    float shade = 0.0;
    float w = 1.0;
    for (int i = 0; i < 40; i++) {
        if (float(i) < uSamples) {
            float2 s = p + dir * ((float(i) + 1.0 - jitter) * stepLen);
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
    fun effect(spot: LightSpot, time: Float, exposure: Float, shade: Float): androidx.compose.ui.graphics.RenderEffect {
        val weights = (1f - DECAY.pow(SAMPLES)) / (1f - DECAY)
        shader.setFloatUniform("uLight", spot.x, spot.y)
        shader.setFloatUniform("uGlowR", spot.glowR.coerceAtLeast(1f))
        shader.setFloatUniform("uSamples", SAMPLES.toFloat())
        shader.setFloatUniform("uDensity", DENSITY)
        shader.setFloatUniform("uDecay", DECAY)
        shader.setFloatUniform("uExposure", EXPOSURE * exposure * spot.strength / weights)
        shader.setFloatUniform("uShade", SHADE * shade * spot.strength / weights)
        shader.setFloatUniform("uFrame", ((floor(time * 60f).toInt() % 64 + 64) % 64).toFloat())
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }

    companion object {
        const val SAMPLES = 32
        const val DENSITY = 0.92f

        /** Per sample: the shafts fade out a little past the glow, and deep space stays dark. */
        const val DECAY = 0.95f
        const val EXPOSURE = 0.8f
        const val SHADE = 1.6f
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
