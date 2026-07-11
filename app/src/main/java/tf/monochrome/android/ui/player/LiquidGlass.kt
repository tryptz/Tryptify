package tf.monochrome.android.ui.player

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * "Liquid glass" treatment for the lyric surfaces: an AGSL RenderEffect that
 * relights the already-drawn glyphs as beveled, undulating glass. Normals are
 * derived from the text's own alpha field, so it is draw-only — layout, font
 * size and line spacing are untouched. The light direction follows device
 * tilt (gravity sensor) plus a slow autonomous drift, and a light sheet
 * sweeps across the surface periodically.
 *
 * Requires API 33 (RuntimeShader); below that, or if the shader fails to
 * compile on some GPU driver, the modifier is a no-op and lyrics render as
 * before.
 */
@Composable
internal fun Modifier.liquidGlass(enabled: Boolean = true): Modifier {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(liquidGlassModifier())
}

// One process-wide epoch so every rememberFrameSeconds() instance reports the
// same timeline: a surface composed later (e.g. the lyrics expand morph)
// continues the animation phase instead of restarting it from zero.
@Volatile
private var frameClockEpochNanos = -1L

/**
 * Per-frame clock in seconds on a shared app-wide timeline. Read it from draw
 * or layout lambdas (graphicsLayer, drawBehind) so animation never recomposes.
 */
@Composable
internal fun rememberFrameSeconds(): State<Float> {
    val timeSec = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { now ->
                if (frameClockEpochNanos < 0L) frameClockEpochNanos = now
                timeSec.floatValue = (now - frameClockEpochNanos) / 1_000_000_000f
            }
        }
    }
    return timeSec
}

/**
 * Adds ~±1 LSB of static triangular noise to break 8-bit banding in smooth
 * gradients. Apply to dedicated background nodes so only the gradient pays
 * the offscreen pass. No-op below API 33 or if the shader fails to compile
 * (banding stays, exactly as before).
 */
@Composable
internal fun Modifier.dithered(): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    val shader = remember { runCatching { RuntimeShader(DITHER_SRC) }.getOrNull() }
        ?: return this
    return this.graphicsLayer {
        renderEffect = RenderEffect
            .createRuntimeShaderEffect(shader, "content")
            .asComposeRenderEffect()
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun liquidGlassModifier(): Modifier {
    val shader = remember { runCatching { RuntimeShader(LIQUID_GLASS_SRC) }.getOrNull() }
        ?: return Modifier

    val timeSec = rememberFrameSeconds()
    val tilt = rememberGravityTilt()

    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uTime", timeSec.value)
            shader.setFloatUniform("uTilt", tilt.value.x, tilt.value.y)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

/** Low-pass-filtered gravity in [-1, 1] per axis; Offset.Zero if no sensor. */
@Composable
private fun rememberGravityTilt(): State<Offset> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val listener = object : SensorEventListener {
            private var fx = 0f
            private var fy = 0f
            override fun onSensorChanged(event: SensorEvent) {
                val gx = (event.values[0] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                val gy = (event.values[1] / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
                fx += (gx - fx) * 0.12f
                fy += (gy - fy) * 0.12f
                tilt.value = Offset(fx, fy)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (manager != null && sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
        onDispose { manager?.unregisterListener(listener) }
    }
    return tilt
}

// Static (not time-animated) noise: fixes banding without shimmer. Noise is
// scaled by alpha to stay premultiplied-valid.
private const val DITHER_SRC = """
uniform shader content;

half4 main(float2 p) {
    half4 c = content.eval(p);
    float n1 = fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453);
    float n2 = fract(sin(dot(p + 17.13, float2(26.651, 41.778))) * 24634.6345);
    float d = (n1 + n2 - 1.0) * (1.5 / 255.0);
    float a = float(c.a);
    float3 rgb = clamp(float3(c.rgb) + d * a, 0.0, a);
    return half4(half3(rgb), c.a);
}
"""

// All colour math below is in premultiplied alpha (RenderEffect contract):
// additive light terms are scaled by src.a and the result clamped to <= a,
// which keeps anti-aliased glyph edges valid and free of halos.
private const val LIQUID_GLASS_SRC = """
uniform shader content;
uniform float2 uSize;
uniform float uTime;
uniform float2 uTilt;

half4 main(float2 p) {
    half4 src = content.eval(p);
    float a = float(src.a);
    if (a < 0.004) {
        return src;
    }

    // Bevel normals from the glyph alpha field: a fine rim gradient plus a
    // broader one so letters read as rounded glass, not flat stickers.
    float aL1 = float(content.eval(p + float2(-1.25, 0.0)).a);
    float aR1 = float(content.eval(p + float2( 1.25, 0.0)).a);
    float aU1 = float(content.eval(p + float2(0.0, -1.25)).a);
    float aD1 = float(content.eval(p + float2(0.0,  1.25)).a);
    float aL2 = float(content.eval(p + float2(-2.0, 0.0)).a);
    float aR2 = float(content.eval(p + float2( 2.0, 0.0)).a);
    float aU2 = float(content.eval(p + float2(0.0, -2.0)).a);
    float aD2 = float(content.eval(p + float2(0.0,  2.0)).a);
    float2 grad = float2((aL1 - aR1) + 0.35 * (aL2 - aR2),
                         (aU1 - aD1) + 0.35 * (aU2 - aD2));

    // Liquid: the surface itself undulates, so highlights swim over glyphs.
    float w1 = sin(p.x * 0.055 + uTime * 1.7) * cos(p.y * 0.081 - uTime * 1.3);
    float w2 = sin((p.x + p.y) * 0.035 - uTime * 0.9);
    grad += 0.05 * float2(w1, w2) * a;

    float3 N = normalize(float3(grad, 0.62));

    // Light = device tilt + slow autonomous drift, biased above the screen.
    float3 L = normalize(float3(
        -uTilt.x * 0.7 + 0.30 * sin(uTime * 0.37),
         uTilt.y * 0.7 + 0.22 * cos(uTime * 0.29) - 0.35,
         0.80));
    float3 H = normalize(L + float3(0.0, 0.0, 1.0));

    float ndl   = max(dot(N, L), 0.0);
    float spec  = pow(max(dot(N, H), 0.0), 96.0);
    float sheen = pow(max(dot(N, H), 0.0), 7.0);
    float fres  = pow(1.0 - clamp(N.z, 0.0, 1.0), 3.0);

    // Slight dispersion: red/blue speculars from nudged normals.
    float specR = pow(max(dot(normalize(N + float3(0.015, 0.0, 0.0)), H), 0.0), 96.0);
    float specB = pow(max(dot(normalize(N - float3(0.015, 0.0, 0.0)), H), 0.0), 96.0);

    // A sheet of light sweeping diagonally across the whole surface.
    float2 uv = p / uSize;
    float band = 1.0 - smoothstep(0.0, 0.18,
        abs(uv.x * 0.8 + uv.y * 0.5 - mix(-0.35, 1.65, fract(uTime * 0.11))));

    float3 base = float3(src.rgb);
    // Light scales with the line's own brightness: dim past/upcoming lines
    // keep tight, quiet edges instead of full-brightness rims that read as
    // blur; the bright active line gets the full glass treatment.
    float lum = clamp(dot(base / a, float3(0.299, 0.587, 0.114)), 0.0, 1.0);
    float lightGain = 0.10 + 0.90 * lum * lum;
    float3 col = base * (0.82 + 0.16 * ndl + 0.06 * band);
    col += (float3(specR, spec, specB) * 0.85
            + float3(0.75, 0.85, 1.0) * (fres * 0.22)
            + float3(sheen) * (0.06 + 0.30 * band)) * (a * lightGain);
    col = min(col, float3(a));
    return half4(half3(col), src.a);
}
"""
