package tf.monochrome.android.ui.discover.galaxy

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.math.exp
import kotlin.math.pow

/**
 * The galaxy's gas: smoke lying in the disc, lit by the black hole and moving
 * with the music. An original effect for this map, written the way a MilkDrop
 * preset is: a field warped a little more every frame, driven by bass, mid and
 * treble against their own running averages, and a light composited on top.
 * It is AGSL on the map's canvas rather than a projectM preset on a GL
 * surface, because the map's panels are glass, and glass cannot frost a GL
 * surface (see `docs/ui-invariants.md`, "The genre galaxy").
 *
 * - **Smoke**: domain-warped value-noise fbm, sampled where each pixel's ray
 *   meets the galaxy's plane, so the gas is *in* the disc — it turns with the
 *   galaxy and holds its place as the camera moves. It swirls tighter toward
 *   the middle, like the arms do, and thins out past the rim and inside the
 *   hole's reach.
 * - **Mid** stirs it: the flow runs faster and the warp deepens.
 * - **Bass** is the light: the accretion disk lights the gas near it, warm,
 *   and on a kick the glow swells and ripples run out across the disc.
 * - **Treble** glints in the densest gas.
 *
 * Drawn at a third of the screen's resolution and scaled up: smoke has no
 * detail a third of a pixel wide, and it is nine times fewer pixels.
 */
private const val SMOKE_SRC = """
uniform float uScale;
uniform float2 uCenter;
uniform float uFocal;
uniform float3 uE;
uniform float3 uF;
uniform float3 uR;
uniform float3 uU;
uniform float uTime;
uniform float uBass;
uniform float uMid;
uniform float uTreb;
uniform float3 uCool;
uniform float3 uViolet;
uniform float3 uWarm;
uniform float uAmount;

float hash(float2 p) {
    float3 p3 = fract(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float noise(float2 x) {
    float2 i = floor(x);
    float2 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm(float2 p) {
    float s = 0.0;
    float a = 0.5;
    for (int k = 0; k < 4; k++) {
        s += a * noise(p);
        p = float2(p.x * 1.6 - p.y * 1.2, p.x * 1.2 + p.y * 1.6) + float2(3.1, 1.7);
        a *= 0.5;
    }
    return s;
}

half4 main(float2 xy) {
    float2 d = (xy * uScale - uCenter) / uFocal;
    float3 dir = normalize(uF + uR * d.x - uU * d.y);
    // Only where the ray meets the galaxy's plane.
    if (dir.y * uE.y >= 0.0) return half4(0.0);
    float t = -uE.y / dir.y;
    float3 P = uE + t * dir;
    float r = length(P.xz);

    // Where the disc has no gas, before the noise that would be thrown away:
    // the density below is at most this, so when this is under the cut
    // there, so is it. Inside the hole's clearing, past the rim and out at
    // the horizon, often a large share of the screen, that was the 12 noise
    // lookups of three fbm calls a pixel for nothing.
    float disc = smoothstep(1400.0, 650.0, r) * smoothstep(90.0, 320.0, r);
    float far = exp(-t / 12000.0);
    if ((0.6 + 0.4 * uAmount) * disc * far <= 0.001) return half4(0.0);

    // A swirl that tightens toward the middle and turns, slowly.
    float sw = 1.8 * exp(-r / 650.0) + uTime * 0.012;
    float cs = cos(sw);
    float sn = sin(sw);
    float2 q = float2(P.x * cs - P.z * sn, P.x * sn + P.z * cs) / 330.0;

    float stir = 1.0 + 0.8 * uMid;
    float2 flow = float2(uTime * 0.021, -uTime * 0.014) * stir;
    float w = fbm(q * 1.3 + flow);
    float v = fbm(q * 1.1 - flow + 4.0);
    float n = fbm(q + (1.5 + 0.5 * uMid) * float2(w, v));

    // More gas reaches further down the noise, and is brighter where it is.
    float dens = smoothstep(0.42 + 0.12 * (1.0 - uAmount), 0.86, n) * (0.6 + 0.4 * uAmount);
    // In the disc: thinning past the rim, clear inside the hole's reach.
    dens *= disc;
    // And less, far off, where the plane runs into the horizon.
    dens *= far;
    if (dens <= 0.001) return half4(0.0);

    // The hole lights the gas near it; a kick swells it and runs ripples out.
    float core = exp(-r / 420.0);
    float ripple = 0.5 + 0.5 * sin(r / 80.0 - uTime * 2.6);
    float glow = core * (0.7 + 1.2 * uBass) + ripple * core * 0.8 * uBass;

    float3 gas = mix(uCool, uViolet, smoothstep(0.3, 0.8, w));
    float3 col = gas * dens * (0.55 + 0.35 * v) + uWarm * dens * glow;

    // Treble glints in the thickest of it.
    float spark = pow(noise(P.xz / 10.0 + uTime * 3.0), 20.0);
    col += float3(1.0, 0.95, 0.9) * spark * uTreb * smoothstep(0.5, 0.9, n) * 1.2;

    // Mostly light, a little shade: thick smoke dims what is behind it.
    return half4(half3(col), half(dens * 0.2));
}
"""

/** The smoke's shader and paint, made once; [draw] sets this frame on it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class GalaxySmoke {
    private val shader = RuntimeShader(SMOKE_SRC)
    private val paint = Paint().apply { shader = this@GalaxySmoke.shader }

    /**
     * Draws the smoke onto a canvas [scale] times smaller than the view [f]
     * describes, at [time] seconds, moved by [bands].
     */
    fun draw(canvas: android.graphics.Canvas, f: CameraFrame, scale: Float, time: Float, bands: AudioBands?, amount: Float = 1f) {
        shader.setFloatUniform("uAmount", amount)
        shader.setFloatUniform("uScale", scale)
        shader.setFloatUniform("uCenter", f.centerX, f.centerY)
        shader.setFloatUniform("uFocal", f.focal)
        shader.setFloatUniform("uE", f.ex, f.ey, f.ez)
        // The galaxy's own basis: the gas turns with it.
        shader.setFloatUniform("uF", f.fx, f.fy, f.fz)
        shader.setFloatUniform("uR", f.rx, f.ry, f.rz)
        shader.setFloatUniform("uU", f.ux, f.uy, f.uz)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uBass", bands?.bassLift ?: 0f)
        shader.setFloatUniform("uMid", bands?.midLift ?: 0f)
        shader.setFloatUniform("uTreb", bands?.trebLift ?: 0f)
        shader.setFloatUniform("uCool", 0.09f, 0.20f, 0.40f)
        shader.setFloatUniform("uViolet", 0.30f, 0.12f, 0.42f)
        shader.setFloatUniform("uWarm", 1.0f, 0.55f, 0.22f)
        canvas.drawPaint(paint)
    }
}

/** The smoke on Android 13 and up, unless the device asked for less work. */
@Composable
internal fun rememberGalaxySmoke(enabled: Boolean): GalaxySmoke? =
    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { runCatching { GalaxySmoke() }.getOrNull() }
    } else {
        null
    }

/**
 * Bass, mid and treble the way MilkDrop reads them: each band's energy against
 * its own running average, so 1 is "as loud as this song usually is" whatever
 * the mix level, plus the smoothed `_att` value presets move things with.
 *
 * Read from the spectrum tap's 256 log-spaced bins (20 Hz to 20 kHz, in dB).
 * Not snapshot state: the map's clock updates it every frame and the smoke
 * reads it while drawing, which it is doing every frame anyway.
 */
class AudioBands {
    var bass = 0f; private set
    var mid = 0f; private set
    var treb = 0f; private set
    var bassAtt = 0f; private set
    var midAtt = 0f; private set
    var trebAtt = 0f; private set

    private val average = FloatArray(3)

    /** How hard the galaxy answers: the listener's reactivity, 1 as shipped. */
    var gain = 1f

    /** How far each band runs above its usual level, 0 when it is at or under it. */
    val bassLift: Float get() = ((bassAtt - LIFT_FROM) * LIFT_GAIN * gain).coerceIn(0f, 2f)
    val midLift: Float get() = ((midAtt - LIFT_FROM) * LIFT_GAIN * gain).coerceIn(0f, 2f)
    val trebLift: Float get() = ((trebAtt - LIFT_FROM) * LIFT_GAIN * gain).coerceIn(0f, 2f)

    fun update(bins: FloatArray, dt: Float) {
        val now = floatArrayOf(
            energy(bins, BASS_FROM, BASS_TO),
            energy(bins, MID_FROM, MID_TO),
            energy(bins, TREB_FROM, TREB_TO),
        )
        val avgCoef = 1f - exp(-dt / AVERAGE_SEC)
        val attCoef = 1f - exp(-dt / ATT_SEC)
        val level = FloatArray(3)
        for (k in 0..2) {
            average[k] += (now[k] - average[k]) * avgCoef
            level[k] = if (average[k] < SILENCE) 0f else (now[k] / average[k]).coerceIn(0f, 3f)
        }
        bass = level[0]; mid = level[1]; treb = level[2]
        bassAtt += (bass - bassAtt) * attCoef
        midAtt += (mid - midAtt) * attCoef
        trebAtt += (treb - trebAtt) * attCoef
    }

    /** No music: everything settles back to rest. */
    fun quiet(dt: Float) {
        val coef = 1f - exp(-dt / ATT_SEC)
        bass = 0f; mid = 0f; treb = 0f
        bassAtt -= bassAtt * coef
        midAtt -= midAtt * coef
        trebAtt -= trebAtt * coef
    }

    private fun energy(bins: FloatArray, from: Int, to: Int): Float {
        if (bins.isEmpty()) return 0f
        val a = from.coerceIn(0, bins.size - 1)
        val b = to.coerceIn(a, bins.size - 1)
        var sum = 0f
        for (i in a..b) sum += 10f.pow(bins[i] / 20f)
        return sum / (b - a + 1)
    }

    internal companion object {
        // Bins are log10(f / 20) / 3 * 255: 40–110 Hz, 110 Hz–2 kHz, 2–12 kHz.
        const val BASS_FROM = 26
        const val BASS_TO = 62
        const val MID_FROM = 63
        const val MID_TO = 170
        const val TREB_FROM = 171
        const val TREB_TO = 236

        /** MilkDrop's long average is a few seconds, its _att a quarter of one. */
        const val AVERAGE_SEC = 4f
        const val ATT_SEC = 0.25f

        /** Below this the band is silent, and a ratio against it is noise. */
        const val SILENCE = 1e-6f

        /** Only what runs above the song's usual level moves anything: a steady passage rests. */
        const val LIFT_FROM = 1f
        const val LIFT_GAIN = 1.6f
    }
}
