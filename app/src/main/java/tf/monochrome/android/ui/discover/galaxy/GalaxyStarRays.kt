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
 * God rays for the other stars: the biggest few on screen each shine their own
 * shafts, with the dust in their glow streaking dark through them — what the
 * star you are at does with its planets and moons ([GalaxyLight]).
 *
 * One pass for all of them, not one each. Every star's glow is drawn into one
 * light pass, and each pixel marches toward each light in turn, taking only
 * the samples within that light's reach: a pixel far from every star takes
 * none, and each star costs the pixels near it and little else. A pass per
 * star would be a full-screen layer per star.
 *
 * Each light is [GalaxyLight]'s march, line for line, with its share of the
 * light ([StarLights.power]) on its weights, so a star leaving the few dims out
 * instead of switching off.
 */
private const val STAR_RAYS_SRC = """
uniform shader content;
uniform float4 uLights[12];
uniform float4 uPower[12];
uniform float uCount;
uniform float uSamples;
uniform float uDensity;
uniform float uDecay;
uniform float uExposure;
uniform float uShade;
uniform float uFrame;

half4 main(float2 p) {
    float2 jp = p + float2(5.588238 * uFrame, 0.0);
    float jitter = fract(52.9829189 * fract(dot(jp, float2(0.06711056, 0.00583715))));
    float3 light = float3(0.0);
    float shade = 0.0;
    for (int l = 0; l < 12; l++) {
        if (float(l) >= uCount) break;
        // x, y, glow radius, reach: as GalaxyLight's uniforms, one light each.
        float4 L = uLights[l];
        float2 toL = L.xy - p;
        float dist = length(toL);
        float stepLen = dist * uDensity / uSamples;
        float first = max(ceil((dist - L.w) / max(stepLen, 0.0001) - 1.0 + jitter), 0.0);
        if (first < uSamples) {
            float2 dir = toL / max(dist, 0.001);
            float w = pow(uDecay, first) * uPower[l].x;
            // From the first sample that counts: a pixel pays for the samples
            // it takes, not the ones it skips.
            for (int k = 0; k < 32; k++) {
                float fi = first + float(k);
                if (fi >= uSamples) break;
                float2 s = p + dir * ((fi + 1.0 - jitter) * stepLen);
                float4 c = float4(content.eval(s));
                light += c.rgb * w;
                float block = clamp(c.a - max(c.r, max(c.g, c.b)), 0.0, 1.0);
                float g = distance(s, L.xy) / L.z;
                shade += block * exp(-2.0 * g * g) * w;
                w *= uDecay;
            }
        }
    }
    float3 col = 1.0 - exp(-light * uExposure);
    float a = max(col.r, max(col.g, col.b));
    float sa = clamp(shade * uShade, 0.0, 0.6);
    return half4(half3(col), half(a + sa * (1.0 - a)));
}
"""

/**
 * The stars shining rays this frame, in the view's px. Reused, never
 * reallocated; [count] of them are this frame's.
 */
internal class StarLights {
    val star = IntArray(MAX)
    val x = FloatArray(MAX)
    val y = FloatArray(MAX)
    val depth = FloatArray(MAX)

    /** How far each star's glow reaches on screen, px, and how far its pass can add anything (see [LightSpot.reach]). */
    val glowR = FloatArray(MAX)
    val reach = FloatArray(MAX)

    /** The radius of each star's white-hot middle in the pass, px. */
    val core = FloatArray(MAX)

    /** How much of each star's light there is, 0..1: less as it nears the cut. */
    val power = FloatArray(MAX)
    var count = 0

    // The picking's running top list, one past the most it keeps: the next
    // star in line is what each one's fade is measured against.
    val pick = IntArray(MAX + 1)
    val score = FloatArray(MAX + 1)

    companion object {
        /** The most stars with rays at once: the shader's array size. */
        const val MAX = 12
    }
}

/** The other stars' rays' shader, made once; [effect] sets this frame on it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GalaxyStarRays {
    private val shader = RuntimeShader(STAR_RAYS_SRC)
    private val lights = FloatArray(StarLights.MAX * 4)
    private val power = FloatArray(StarLights.MAX * 4)

    init {
        // Arrays as uniforms: set once here, so a device that will not take
        // them fails now, and the stars go without rays, rather than on the
        // first frame.
        shader.setFloatUniform("uLights", lights)
        shader.setFloatUniform("uPower", power)
    }

    /**
     * The render effect for [stars] at [time] seconds: [exposure] and
     * [shade] as shares of the usual, [decay] the march's per-sample falloff
     * (see [GalaxyLight.decayFor]), and the pass's px per view px.
     */
    fun effect(
        stars: StarLights,
        time: Float,
        exposure: Float,
        shade: Float,
        decay: Float,
        resolution: Float,
    ): androidx.compose.ui.graphics.RenderEffect {
        val n = stars.count.coerceAtMost(StarLights.MAX)
        for (k in 0 until StarLights.MAX) {
            val on = k < n
            lights[k * 4] = if (on) stars.x[k] * resolution else 0f
            lights[k * 4 + 1] = if (on) stars.y[k] * resolution else 0f
            lights[k * 4 + 2] = if (on) (stars.glowR[k] * resolution).coerceAtLeast(1f) else 1f
            lights[k * 4 + 3] = if (on) stars.reach[k] * resolution else 0f
            power[k * 4] = if (on) stars.power[k] else 0f
        }
        val weights = (1f - decay.pow(GalaxyLight.SAMPLES)) / (1f - decay)
        shader.setFloatUniform("uLights", lights)
        shader.setFloatUniform("uPower", power)
        shader.setFloatUniform("uCount", n.toFloat())
        shader.setFloatUniform("uSamples", GalaxyLight.SAMPLES.toFloat())
        shader.setFloatUniform("uDensity", GalaxyLight.DENSITY)
        shader.setFloatUniform("uDecay", decay)
        shader.setFloatUniform("uExposure", GalaxyLight.EXPOSURE * exposure / weights)
        shader.setFloatUniform("uShade", GalaxyLight.SHADE * shade / weights)
        shader.setFloatUniform("uFrame", ((floor(time * 60f).toInt() % 64 + 64) % 64).toFloat())
        return RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
    }
}

/** The other stars' rays on Android 13 and up, when [enabled]; null if the shader will not run here. */
@Composable
internal fun rememberGalaxyStarRays(enabled: Boolean): GalaxyStarRays? =
    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember {
            runCatching { GalaxyStarRays() }
                .onFailure { android.util.Log.w(GalaxyFrameStats.TAG, "star rays shader did not build: ${it.message}") }
                .getOrNull()
        }
    } else {
        null
    }
