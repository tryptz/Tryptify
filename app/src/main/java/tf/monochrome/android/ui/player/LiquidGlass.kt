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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext

/**
 * "Liquid glass" treatment for the lyric surfaces: an AGSL RenderEffect that
 * turns the already-drawn glyphs into real, refractive glass. The bevel normal
 * is derived from the text's own alpha field (so it stays draw-only — layout,
 * font size and line spacing are untouched); the glyph body is rendered
 * see-through, the smooth album-tinted backdrop is reconstructed and *lensed*
 * through that bevel (with chromatic aberration), and a bright specular rim
 * rides the beveled edge. The light direction follows device tilt (gravity
 * sensor) plus a slow autonomous drift, and a light sheet sweeps the surface.
 *
 * [tint] is the album colour the reconstructed backdrop and the glass frost are
 * tinted with — pass the active accent so the refraction matches what's behind
 * the lyrics.
 *
 * Requires API 33 (RuntimeShader); below that, or if the shader fails to
 * compile on some GPU driver, the modifier is a no-op and lyrics render as the
 * solid text they were handed.
 */
@Composable
internal fun Modifier.liquidGlass(
    enabled: Boolean = true,
    tint: Color = Color(0xFF8FB4FF),
): Modifier {
    val fx = LocalLyricsFx.current
    if (!enabled || !fx.liquidGlass || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(liquidGlassModifier(tint, fx))
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

/**
 * Post-process FXAA (single-pass luma edge anti-aliasing) for the lyric
 * surface: smooths the jagged edges the 3D letter tilts, the glass relight and
 * a reduced panel resolution leave behind. Chain it OUTSIDE (before) the
 * [liquidGlass] modifier so it runs on the glass output.
 *
 * Requires API 33 (RuntimeShader) and the fx toggle; below that, or if the
 * shader fails to compile, the modifier is a no-op and lyrics render unchanged.
 */
@Composable
internal fun Modifier.fxaa(): Modifier {
    val fx = LocalLyricsFx.current
    if (!fx.fxaa || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(fxaaModifier(fx.fxaaStrength))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun fxaaModifier(strength: Float): Modifier {
    val shader = remember {
        runCatching { RuntimeShader(FXAA_SRC) }
            .onSuccess { LyricsDebug.log("FXAA shader compiled") }
            .onFailure { LyricsDebug.log("FXAA shader FAILED to compile: ${it.message}") }
            .getOrNull()
    } ?: return Modifier
    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uStrength", strength)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun liquidGlassModifier(
    tint: Color,
    fx: tf.monochrome.android.domain.model.LyricsFxSettings,
): Modifier {
    val shader = remember {
        runCatching { RuntimeShader(LIQUID_GLASS_SRC) }
            .onSuccess { LyricsDebug.log("liquid-glass shader compiled") }
            .onFailure { LyricsDebug.log("liquid-glass shader FAILED to compile: ${it.message}") }
            .getOrNull()
    } ?: return Modifier

    val timeSec = rememberFrameSeconds()
    val tilt = rememberGravityTilt()

    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uTime", timeSec.value)
            shader.setFloatUniform("uTilt", tilt.value.x, tilt.value.y)
            shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uBodyOpacity", fx.glassBodyOpacity)
            shader.setFloatUniform("uRefraction", fx.glassRefraction)
            shader.setFloatUniform("uRimGain", fx.glassRimBrightness)
            shader.setFloatUniform("uDispersion", fx.glassDispersion)
            shader.setFloatUniform("uSampleRings", fx.glassSampleRings.toFloat())
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

// Single-pass luma FXAA (the classic NVIDIA/Geeks3D formulation). Operates on
// the premultiplied-alpha lyric surface: luma is read from premultiplied rgb so
// glyph alpha-edges register, and the directional 4-tap blend runs on the full
// premultiplied colour (averaging premultiplied samples stays valid). uStrength
// cross-fades the anti-aliased result back over the original.
private const val FXAA_SRC = """
uniform shader content;
uniform float uStrength;

half4 main(float2 frag) {
    float3 luma = float3(0.299, 0.587, 0.114);
    float SPAN_MAX = 8.0;
    float REDUCE_MUL = 1.0 / 8.0;
    float REDUCE_MIN = 1.0 / 128.0;

    half4 c = content.eval(frag);
    float lumaM  = dot(float3(c.rgb), luma);
    float lumaNW = dot(float3(content.eval(frag + float2(-1.0, -1.0)).rgb), luma);
    float lumaNE = dot(float3(content.eval(frag + float2( 1.0, -1.0)).rgb), luma);
    float lumaSW = dot(float3(content.eval(frag + float2(-1.0,  1.0)).rgb), luma);
    float lumaSE = dot(float3(content.eval(frag + float2( 1.0,  1.0)).rgb), luma);

    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    // Flat region: nothing to smooth, return the source untouched.
    if (lumaMax - lumaMin < 0.02) {
        return c;
    }

    float2 dir = float2(
        -((lumaNW + lumaNE) - (lumaSW + lumaSE)),
         ((lumaNW + lumaSW) - (lumaNE + lumaSE)));
    float dirReduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * REDUCE_MUL), REDUCE_MIN);
    float rcpDirMin = 1.0 / (min(abs(dir.x), abs(dir.y)) + dirReduce);
    // frag is in pixels (see the +/-1px luma taps above), so the blend offsets
    // must stay in pixels too — no texel-size (1/uSize) scaling here.
    dir = clamp(dir * rcpDirMin, -SPAN_MAX, SPAN_MAX);

    // Do the blend in float precision (the codebase's convention) to avoid
    // half/float mismatch on scalar-vector ops, then convert back at the end.
    float4 rgbA = 0.5 * (
        float4(content.eval(frag + dir * (1.0 / 3.0 - 0.5))) +
        float4(content.eval(frag + dir * (2.0 / 3.0 - 0.5))));
    float4 rgbB = rgbA * 0.5 + 0.25 * (
        float4(content.eval(frag + dir * -0.5)) +
        float4(content.eval(frag + dir *  0.5)));

    float lumaB = dot(rgbB.rgb, luma);
    float4 aa = (lumaB < lumaMin || lumaB > lumaMax) ? rgbA : rgbB;
    return half4(mix(float4(c), aa, uStrength));
}
"""

// True refractive glass. Output stays in premultiplied alpha (RenderEffect
// contract): the final rgb is clamped to <= the emitted alpha, so anti-aliased
// glyph edges remain valid and halo-free. The glyph body is emitted at reduced
// alpha (the backdrop shows through), while the beveled rim rises back to full
// alpha so its bright specular can read as a crisp glass edge.
//
// Smooth-backdrop note: the field the glass refracts is reconstructed in-shader
// (a soft vertical wash + a top glow, album-tinted) to mirror the smooth
// gradient that actually sits behind the lyrics. Because that real backdrop is
// low-frequency, a reconstructed field lenses indistinguishably from sampling
// the real pixels — and it needs no fragile per-frame backdrop capture.
private const val LIQUID_GLASS_SRC = """
uniform shader content;
uniform float2 uSize;
uniform float uTime;
uniform float2 uTilt;
uniform float3 uTint;
uniform float uBodyOpacity;   // glass body opacity (lower = more see-through)
uniform float uRefraction;    // how hard the bevel lenses the backdrop
uniform float uRimGain;       // brightness of the specular glass edge
uniform float uDispersion;    // chromatic aberration at the refracting edges
uniform float uSampleRings;   // bevel sample rings 1/2/3 → 5/9/13 taps per pixel

// Smooth album-tinted backdrop field, reconstructed so the glass can lens it.
// Returns a 0..1 luminance weight for the tint at uv (matches the vertical
// wash + soft top glow drawn behind the lyrics).
float backdropField(float2 uv) {
    float wash = mix(0.45, 0.0, clamp(uv.y, 0.0, 1.0));
    float glow = smoothstep(1.0, 0.0, distance(uv, float2(0.5, 0.22)) * 1.5);
    return clamp(wash + glow * 0.5, 0.0, 1.0);
}

half4 main(float2 p) {
    half4 src = content.eval(p);
    float a = float(src.a);
    // Outside the glyphs the surface is empty, so the glass exists only where a
    // letter is: hand those pixels straight back (transparent) and the real
    // backdrop behind the lyric layer shows through untouched.
    if (a < 0.004) {
        return src;
    }

    // Bevel normals from the glyph alpha field. The sample count is a user
    // setting (uSampleRings): the fine cross is always taken; the broad ring and
    // the diagonal ring are gated so lower quality skips those texture fetches —
    // fewer taps per pixel is a real GPU saving.
    float aL1 = float(content.eval(p + float2(-1.25, 0.0)).a);
    float aR1 = float(content.eval(p + float2( 1.25, 0.0)).a);
    float aU1 = float(content.eval(p + float2(0.0, -1.25)).a);
    float aD1 = float(content.eval(p + float2(0.0,  1.25)).a);
    float2 grad = float2(aL1 - aR1, aU1 - aD1);

    if (uSampleRings > 1.5) {
        // Broad ring: rounder, softer bevel.
        float aL2 = float(content.eval(p + float2(-2.5, 0.0)).a);
        float aR2 = float(content.eval(p + float2( 2.5, 0.0)).a);
        float aU2 = float(content.eval(p + float2(0.0, -2.5)).a);
        float aD2 = float(content.eval(p + float2(0.0,  2.5)).a);
        grad += 0.4 * float2(aL2 - aR2, aU2 - aD2);
    }
    if (uSampleRings > 2.5) {
        // Diagonal ring: smoother normals on curved strokes.
        float aNW = float(content.eval(p + float2(-1.8, -1.8)).a);
        float aNE = float(content.eval(p + float2( 1.8, -1.8)).a);
        float aSW = float(content.eval(p + float2(-1.8,  1.8)).a);
        float aSE = float(content.eval(p + float2( 1.8,  1.8)).a);
        grad += 0.3 * float2((aNW + aSW) - (aNE + aSE),
                             (aNW + aNE) - (aSW + aSE));
    }

    // Liquid: the surface itself undulates, so highlights swim over glyphs.
    float w1 = sin(p.x * 0.055 + uTime * 1.7) * cos(p.y * 0.081 - uTime * 1.3);
    float w2 = sin((p.x + p.y) * 0.035 - uTime * 0.9);
    grad += 0.05 * float2(w1, w2) * a;

    float2 slope = grad;                        // raw bevel slope → lens displacement
    float3 N = normalize(float3(grad, 0.62));

    // Light = device tilt + slow autonomous drift, biased above the screen.
    float3 L = normalize(float3(
        -uTilt.x * 0.7 + 0.30 * sin(uTime * 0.37),
         uTilt.y * 0.7 + 0.22 * cos(uTime * 0.29) - 0.35,
         0.80));
    float3 H = normalize(L + float3(0.0, 0.0, 1.0));

    float ndh   = max(dot(N, H), 0.0);
    float spec  = pow(ndh, 110.0);
    float sheen = pow(ndh, 8.0);
    float fres  = pow(1.0 - clamp(N.z, 0.0, 1.0), 3.0);

    // Chromatic dispersion: red/blue speculars from nudged normals.
    float disp = 0.02 * uDispersion;
    float specR = pow(max(dot(normalize(N + float3(disp, 0.0, 0.0)), H), 0.0), 110.0);
    float specB = pow(max(dot(normalize(N - float3(disp, 0.0, 0.0)), H), 0.0), 110.0);

    // Refraction: the bevel slope bends where we read the backdrop, and each
    // colour channel bends by a slightly different amount (chromatic aberration
    // at the rim), so the glass genuinely lenses the field behind it.
    float2 uv = p / uSize;
    float2 lens = slope * uRefraction;
    float aberr = 0.12 * uDispersion;
    float fieldR = backdropField(uv + lens * (1.0 + aberr));
    float fieldG = backdropField(uv + lens);
    float fieldB = backdropField(uv + lens * (1.0 - aberr));
    float3 bg = uTint * float3(fieldR, fieldG, fieldB);

    // A sheet of light sweeping diagonally across the whole surface.
    float band = 1.0 - smoothstep(0.0, 0.18,
        abs(uv.x * 0.8 + uv.y * 0.5 - mix(-0.35, 1.65, fract(uTime * 0.11))));

    // Frost body: mostly the glyph's own colour (so the active accent line stays
    // legible), with the lensed backdrop bleeding in for depth.
    float3 tint = float3(src.rgb) / a;
    float lum = clamp(dot(tint, float3(0.299, 0.587, 0.114)), 0.0, 1.0);
    float3 frost = mix(bg, tint, 0.7);

    // Transparent body, opaque bright rim. Alpha is low across the letter face
    // (backdrop reads through) and climbs to full at the beveled edge, so the
    // rim's specular can shine as a crisp glass edge instead of being clamped
    // away. Dim past/upcoming lines (low src alpha) stay subtle automatically.
    float rim = clamp(spec * 1.3 + fres * 0.9, 0.0, 1.0);
    float bodyOpacity = uBodyOpacity;
    float outA = clamp(a * (bodyOpacity + (1.0 - bodyOpacity) * rim), 0.0, a);
    float lightGain = (0.30 + 0.70 * lum) * uRimGain;

    float3 col = frost * (0.9 + 0.14 * band) * outA;   // premultiplied frost body
    col += (float3(specR, spec, specB) * 1.10
            + float3(0.80, 0.90, 1.00) * (fres * 0.40)
            + float3(sheen) * (0.05 + 0.22 * band)) * (outA * lightGain);
    col = min(col, float3(outA));
    return half4(half3(col), half(outA));
}
"""
