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
import androidx.compose.runtime.compositionLocalOf
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
    val backdrop = LocalPlayerBackdrop.current
    if (!enabled || !fx.liquidGlass || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(liquidGlassModifier(tint, fx, backdrop))
}

/**
 * What actually sits behind the lyric glass, so the shader can lens the real
 * album tones (Apple-OS style) instead of only a flat wash. Provided at the
 * player route from the album palette + the "Blurred Album Background" setting.
 * The default (disabled) leaves the glass numerically identical to the flat
 * single-tint reconstruction it used before.
 */
internal data class PlayerBackdrop(
    val blurredArt: Boolean = false,
    val dominant: Color = Color(0xFF101018),
    val secondary: Color = Color(0xFF101018),
)

internal val LocalPlayerBackdrop = androidx.compose.runtime.compositionLocalOf { PlayerBackdrop() }

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
    backdrop: PlayerBackdrop,
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
            // Second album tone + how strongly the lensed backdrop bleeds into the
            // glass body. Only non-zero when the blurred album background is on, so
            // the glass reads as sitting over the real artwork (Apple-OS style);
            // otherwise uBackdropMix = 0 keeps the flat single-tint look unchanged.
            val secondary = backdrop.secondary
            shader.setFloatUniform("uTint2", secondary.red, secondary.green, secondary.blue)
            shader.setFloatUniform("uBackdropMix", if (backdrop.blurredArt) 0.6f else 0f)
            shader.setFloatUniform("uBodyOpacity", fx.glassBodyOpacity)
            shader.setFloatUniform("uRefraction", fx.glassRefraction)
            shader.setFloatUniform("uRimGain", fx.glassRimBrightness)
            shader.setFloatUniform("uDispersion", fx.glassDispersion)
            shader.setFloatUniform("uSampleRings", fx.glassSampleRings.toFloat())
            shader.setFloatUniform("uRoundness", 1f)
            shader.setFloatUniform("uDepth", 1f)
            shader.setFloatUniform("uLiquid", 1f)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

/**
 * The SAME refractive lyric glass ([LIQUID_GLASS_SRC]), applied to a solid PANEL
 * surface (the player's dock / sheet) instead of glyphs — so the player chrome
 * reads as the exact liquid glass the active lyric line does, not a flat frost.
 *
 * Apply it to a Box that already has a rounded, translucent fill: the shader
 * bevels that fill's edges into a lit, tilt-reactive refractive rim (with
 * chromatic dispersion and the animated light sweep) over a see-through,
 * album-tinted body. Place it BEHIND the panel's content so the buttons and
 * labels sitting on the glass stay crisp and untouched.
 *
 * Unlike [liquidGlass] it is NOT gated on the lyric-FX toggle (player chrome is
 * always glass) and is tuned for a panel: a more present body and a stronger
 * rim. Requires API 33; a no-op (plain fill) below that or on shader failure.
 */
@Composable
internal fun Modifier.liquidGlassPanel(tint: Color): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(liquidGlassPanelModifier(tint))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun liquidGlassPanelModifier(tint: Color): Modifier {
    val shader = remember { runCatching { RuntimeShader(LIQUID_GLASS_SRC) }.getOrNull() } ?: return Modifier
    val timeSec = rememberFrameSeconds()
    val tilt = rememberGravityTilt()
    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uTime", timeSec.value)
            shader.setFloatUniform("uTilt", tilt.value.x, tilt.value.y)
            shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uTint2", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uBackdropMix", 0f)
            // Panel tuning: a body that stays fairly present (a panel, not a
            // glyph), with a strong lit rim and gentle edge refraction.
            shader.setFloatUniform("uBodyOpacity", 0.82f)
            shader.setFloatUniform("uRefraction", 0.10f)
            shader.setFloatUniform("uRimGain", 1.30f)
            shader.setFloatUniform("uDispersion", 1.0f)
            shader.setFloatUniform("uSampleRings", 2f)
            shader.setFloatUniform("uRoundness", 1f)
            shader.setFloatUniform("uDepth", 1f)
            shader.setFloatUniform("uLiquid", 1f)
            renderEffect = RenderEffect
                .createRuntimeShaderEffect(shader, "content")
                .asComposeRenderEffect()
        }
    }
}

/**
 * Player-chrome glass settings (the transport buttons), provided at the player
 * route from the persisted [tf.monochrome.android.domain.model.PlayerGlassSettings].
 */
val LocalPlayerGlass = compositionLocalOf { tf.monochrome.android.domain.model.PlayerGlassSettings() }

/**
 * The SAME refractive lyric glass ([LIQUID_GLASS_SRC]) applied to a player
 * button's icon, so the play/skip shapes read as 3D chrome liquid glass just
 * like the active lyric line. Reads [LocalPlayerGlass] for its parameters
 * (tunable in the Studio's "Player Glass" tab). Apply it to the Icon so the
 * shader bevels the (solid) glyph shape. No-op when disabled, below API 33, or
 * on shader-compile failure.
 */
@Composable
internal fun Modifier.playerGlass(tint: Color): Modifier {
    val g = LocalPlayerGlass.current
    if (!g.enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return this
    return this.then(playerGlassModifier(tint, g))
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun playerGlassModifier(
    tint: Color,
    g: tf.monochrome.android.domain.model.PlayerGlassSettings,
): Modifier {
    val shader = remember { runCatching { RuntimeShader(LIQUID_GLASS_SRC) }.getOrNull() } ?: return Modifier
    val timeSec = rememberFrameSeconds()
    val tilt = rememberGravityTilt()
    return Modifier.graphicsLayer {
        if (size.minDimension > 0f) {
            shader.setFloatUniform("uSize", size.width, size.height)
            shader.setFloatUniform("uTime", timeSec.value)
            shader.setFloatUniform("uTilt", tilt.value.x, tilt.value.y)
            shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uTint2", tint.red, tint.green, tint.blue)
            shader.setFloatUniform("uBackdropMix", 0f)
            shader.setFloatUniform("uBodyOpacity", g.bodyOpacity)
            shader.setFloatUniform("uRefraction", g.refraction)
            shader.setFloatUniform("uRimGain", g.rimBrightness)
            shader.setFloatUniform("uDispersion", g.dispersion)
            shader.setFloatUniform("uSampleRings", g.sampleRings.toFloat())
            shader.setFloatUniform("uRoundness", g.roundness)
            shader.setFloatUniform("uDepth", g.depth)
            // Calmer surface for the button chrome so the big disc reads clean
            // and smooth rather than cloudy.
            shader.setFloatUniform("uLiquid", 0.25f)
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
uniform float3 uTint2;        // second album tone (blurred-art backdrop)
uniform float uBackdropMix;   // 0 = flat single-tint wash; >0 = lens the album art
uniform float uRoundness;     // bevel shoulder width: 1 = neutral, higher = rounder/softer edge
uniform float uDepth;         // profondeur: 1 = neutral, higher = steeper relief / more 3D
uniform float uLiquid;        // surface unrest: 1 = full moving sheen, lower = calmer, cleaner glass

// Smooth album-tinted backdrop field, reconstructed so the glass can lens it.
// Returns a 0..1 luminance weight for the tint at uv (matches the vertical
// wash + soft top glow drawn behind the lyrics).
float backdropField(float2 uv) {
    float wash = mix(0.45, 0.0, clamp(uv.y, 0.0, 1.0));
    float glow = smoothstep(1.0, 0.0, distance(uv, float2(0.5, 0.22)) * 1.5);
    return clamp(wash + glow * 0.5, 0.0, 1.0);
}

// Album tone the glass lenses at uv. With the blurred cover behind the lyrics
// (uBackdropMix > 0) this is a two-tone vertical album blend, so the refraction
// carries real colour variation like a photo behind glass. With no blurred art
// (uBackdropMix = 0) it collapses to uTint — identical to the old single tone.
float3 backdropTintAt(float2 uv) {
    float3 two = mix(uTint, uTint2, smoothstep(0.0, 1.0, clamp(uv.y, 0.0, 1.0)));
    return mix(uTint, two, uBackdropMix);
}

// Procedural studio environment, sampled by the reflection vector. A soft
// vertical light gradient plus a moving key light and a cooler fill, so the
// glass catches a believable "room" that streaks across the bevel as the
// surface normal turns — instead of one flat, generic highlight. Device tilt
// and a slow drift move the lights so the reflections stay alive.
float3 environment(float3 r, float2 tilt, float t, float liquid) {
    float2 d = r.xy;
    // Screen y is down, so the bright sky is where the reflection points up
    // (r.y < 0): a soft top->bottom studio gradient.
    float up = clamp(0.5 - 0.5 * r.y, 0.0, 1.0);
    float3 sky = mix(float3(0.05, 0.06, 0.08), float3(0.82, 0.88, 1.0),
                     smoothstep(0.12, 0.96, up));
    // Bright soft key light near the top, nudged by tilt + a slow drift.
    float2 key = float2(-0.30, -0.62) + tilt * 0.5
               + 0.06 * float2(sin(t * 0.40), cos(t * 0.33)) * liquid;
    float keyI = smoothstep(0.55, 0.0, distance(d, key));
    // Cooler fill light from the opposite corner.
    float2 fill = float2(0.52, 0.5) - tilt * 0.4;
    float fillI = smoothstep(0.72, 0.0, distance(d, fill)) * 0.5;
    return sky + float3(1.0, 0.97, 0.92) * keyI * 1.7
              + float3(0.65, 0.82, 1.0) * fillI;
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
    // Roundness widens the taps: a broader sampling radius reads the alpha edge
    // over a wider band, so the bevel shoulder rolls off round and pillowy
    // instead of a tight, sharp edge (rr = 1 reproduces the original look).
    float rr = uRoundness;
    float aL1 = float(content.eval(p + float2(-1.25 * rr, 0.0)).a);
    float aR1 = float(content.eval(p + float2( 1.25 * rr, 0.0)).a);
    float aU1 = float(content.eval(p + float2(0.0, -1.25 * rr)).a);
    float aD1 = float(content.eval(p + float2(0.0,  1.25 * rr)).a);
    float2 grad = float2(aL1 - aR1, aU1 - aD1);

    if (uSampleRings > 1.5) {
        // Broad ring: rounder, softer bevel.
        float aL2 = float(content.eval(p + float2(-2.5 * rr, 0.0)).a);
        float aR2 = float(content.eval(p + float2( 2.5 * rr, 0.0)).a);
        float aU2 = float(content.eval(p + float2(0.0, -2.5 * rr)).a);
        float aD2 = float(content.eval(p + float2(0.0,  2.5 * rr)).a);
        grad += 0.4 * float2(aL2 - aR2, aU2 - aD2);
    }
    if (uSampleRings > 2.5) {
        // Diagonal ring: smoother normals on curved strokes.
        float aNW = float(content.eval(p + float2(-1.8 * rr, -1.8 * rr)).a);
        float aNE = float(content.eval(p + float2( 1.8 * rr, -1.8 * rr)).a);
        float aSW = float(content.eval(p + float2(-1.8 * rr,  1.8 * rr)).a);
        float aSE = float(content.eval(p + float2( 1.8 * rr,  1.8 * rr)).a);
        grad += 0.3 * float2((aNW + aSW) - (aNE + aSE),
                             (aNW + aNE) - (aSW + aSE));
    }

    // Liquid: subtle surface undulation so highlights swim over the glass.
    // CRUCIAL: gate it by the real bevel strength (the geometric gradient BEFORE
    // the ripple), so the undulation only lives where there already IS an edge.
    // A big flat face — a button disc, the dock slab — has zero gradient inside,
    // so it stays a clean, uniform surface instead of a lattice of ripple
    // highlights. Thin glyphs are all edge, so lyrics keep their liquid life.
    float edge = clamp(length(grad) * 1.5, 0.0, 1.0);
    float w1 = sin(p.x * 0.055 + uTime * 1.7) * cos(p.y * 0.081 - uTime * 1.3);
    float w2 = sin((p.x + p.y) * 0.035 - uTime * 0.9);
    grad += 0.04 * uLiquid * float2(w1, w2) * a * edge;

    // Surface normal from the alpha heightfield. Depth (profondeur) scales how
    // hard the bevel tips the normal off the surface — the dominant "3D" knob,
    // now a strong multiplier on the slope instead of a small z-base nudge.
    float slopeGain = 3.5 * uDepth;
    float3 N = normalize(float3(grad * slopeGain, 1.0));

    float2 uv = p / uSize;
    float3 I = float3(0.0, 0.0, -1.0);   // view ray, into the screen

    // Fresnel (Schlick, F0 = 0.04 for glass): ~4% reflection head-on, climbing
    // to ~100% at grazing edges. This is what makes the rim catch the light and
    // the face stay see-through — the core of the glass look.
    float cosV = clamp(N.z, 0.0, 1.0);
    float fres = 0.04 + 0.96 * pow(1.0 - cosV, 5.0);

    // Refraction (Snell, via refract) with a per-channel index of refraction so
    // R/G/B bend by different amounts — true chromatic dispersion, strongest at
    // the edges where the bevel turns. eta = n_air / n_glass ~ 0.66.
    float dispSpread = 0.06 * uDispersion;
    float3 Tr = refract(I, N, 0.66 - dispSpread);
    float3 Tg = refract(I, N, 0.66);
    float3 Tb = refract(I, N, 0.66 + dispSpread);
    float power = uRefraction * 1.6;
    float2 uvR = uv + Tr.xy * power;
    float2 uvG = uv + Tg.xy * power;
    float2 uvB = uv + Tb.xy * power;
    float3 refr = float3(
        backdropTintAt(uvR).r * backdropField(uvR),
        backdropTintAt(uvG).g * backdropField(uvG),
        backdropTintAt(uvB).b * backdropField(uvB));

    // Reflected environment: the room the glass catches, turning with N so the
    // reflection streaks across the bevel as the surface curves.
    float3 refl = environment(reflect(I, N), uTilt, uTime, uLiquid);

    // Crisp specular glint from a tilt-driven key light, dispersed for sparkle.
    float3 L = normalize(float3(
        -uTilt.x * 0.8 + 0.25 * sin(uTime * 0.37),
         uTilt.y * 0.8 + 0.20 * cos(uTime * 0.29) - 0.5,
         0.85));
    float3 H = normalize(L + float3(0.0, 0.0, 1.0));
    float ndh   = max(dot(N, H), 0.0);
    float spec  = pow(ndh, 90.0);
    float dsp   = 0.015 * uDispersion;
    float specR = pow(max(dot(normalize(N + float3(dsp, 0.0, 0.0)), H), 0.0), 90.0);
    float specB = pow(max(dot(normalize(N - float3(dsp, 0.0, 0.0)), H), 0.0), 90.0);

    // Body: the glyph's own colour (kept legible) with a hint of the lensed
    // backdrop; leans more see-through over real blurred art.
    float3 glyphTint = float3(src.rgb) / a;
    float bodyMix = mix(0.72, 0.42, uBackdropMix);
    float3 bodyCol = mix(refr, glyphTint, bodyMix);

    // Fresnel-blend the reflection over the body (edges reflect, the face
    // transmits), then add the dispersed glint. uRimGain scales both the
    // reflection and the glint, so "Edge highlight" is a real brightness knob.
    // The glint is Fresnel-weighted too, so it rides the bevel edge rather than
    // washing the whole flat face white (a front-facing flat surface would
    // otherwise fire the specular uniformly).
    float3 col3 = mix(bodyCol, refl * uRimGain, clamp(fres * 1.1, 0.0, 1.0));
    col3 += float3(specR, spec, specB) * uRimGain * fres;

    // Transparent face, opaque bright rim: alpha is low across the body (backdrop
    // reads through) and climbs to full where Fresnel and the glint peak, so the
    // rim highlight reads as a crisp glass edge rather than being clamped away.
    float rim = clamp(fres * 1.2 + spec, 0.0, 1.0);
    float outA = clamp(a * (uBodyOpacity + (1.0 - uBodyOpacity) * rim), 0.0, a);

    float3 col = col3 * outA;              // premultiplied
    col = min(col, float3(outA));          // keep rgb <= alpha (premult-valid)
    return half4(half3(col), half(outA));
}
"""
