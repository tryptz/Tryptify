package tf.monochrome.android.ui.discover.galaxy

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * The deep sky behind the galaxy, as one AGSL pass: the space colour and a
 * field of far stars, the way the three.js demo drew it — a few thousand
 * points of light, blue-white, from faint to bright, each twinkling on its
 * own phase, the bright ones with a soft bloom round them.
 *
 * A sky, not a backdrop: every pixel is a direction from the camera, so the
 * stars stay put in space as the galaxy is orbited and only the camera's turn
 * moves them, never a pan or a zoom (they are that far away).
 *
 * The stars live on a cube's six faces, one per grid cell at most, kept to
 * the middle half of their cell so a star and its halo never cross into the
 * next one — which is what lets each pixel look at one cell per layer instead
 * of nine. A cell's chance of a star is scaled by the solid angle it covers,
 * so the field is as dense at a face's corner as at its middle and the cube
 * never shows.
 */
private const val SPACE_SRC = """
uniform float2 uCenter;
uniform float uFocal;
uniform float3 uF;
uniform float3 uR;
uniform float3 uU;
uniform float uTime;
uniform float uDp;
uniform float3 uBase;

float3 hash3(float3 p) {
    p = fract(p * float3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// One layer of stars: [cells] per face edge, [chance] of a star in a cell,
// brightness between [lo] and [hi], a core of [coreDp] and a bloom of [haloDp].
float3 stars(float3 dir, float3 n, float3 bu, float3 bv, float2 uv, float face,
             float cells, float chance, float lo, float hi, float coreDp, float haloDp) {
    float2 cell = floor((uv * 0.5 + 0.5) * cells);
    float3 key = float3(cell, face * 131.0 + cells);
    float3 r = hash3(key);
    // Where the star sits on the face: the middle half of its cell.
    float2 c = (cell + 0.25 + 0.5 * r.xy) / cells * 2.0 - 1.0;
    float solid = pow(1.0 + dot(c, c), -1.5);
    if (r.z > chance * solid) return float3(0.0);
    float3 s = normalize(n + bu * c.x + bv * c.y);
    float px = length(dir - s) * uFocal;
    float3 q = hash3(key + 17.0);
    float bright = mix(lo, hi, q.x * q.x);
    float twinkle = 0.78 + 0.22 * sin(uTime * 1.6 + q.y * 6.2832);
    float core = clamp(coreDp * uDp + 0.5 - px, 0.0, 1.0);
    float sigma = haloDp * uDp * (0.5 + bright);
    float halo = exp(-px * px / (2.0 * sigma * sigma));
    float a = (core + halo * 0.55) * bright * twinkle;
    // Blue-white, whiter at the core, as the demo's far stars were.
    return mix(float3(0.85, 0.9, 1.0), float3(1.0), core * 0.75) * a;
}

half4 main(float2 xy) {
    float2 d = (xy - uCenter) / uFocal;
    float3 dir = normalize(uF + uR * d.x - uU * d.y);

    // Which cube face the direction falls on, and where on it.
    float3 a = abs(dir);
    float3 n = float3(0.0);
    float3 bu = float3(0.0);
    float3 bv = float3(0.0);
    float face = 0.0;
    float m = 1.0;
    if (a.x >= a.y && a.x >= a.z) {
        m = a.x; n = float3(sign(dir.x), 0.0, 0.0); bu = float3(0.0, 1.0, 0.0); bv = float3(0.0, 0.0, 1.0);
        face = dir.x > 0.0 ? 0.0 : 1.0;
    } else if (a.y >= a.z) {
        m = a.y; n = float3(0.0, sign(dir.y), 0.0); bu = float3(1.0, 0.0, 0.0); bv = float3(0.0, 0.0, 1.0);
        face = dir.y > 0.0 ? 2.0 : 3.0;
    } else {
        m = a.z; n = float3(0.0, 0.0, sign(dir.z)); bu = float3(1.0, 0.0, 0.0); bv = float3(0.0, 1.0, 0.0);
        face = dir.z > 0.0 ? 4.0 : 5.0;
    }
    float2 uv = float2(dot(dir, bu), dot(dir, bv)) / m;

    float3 col = uBase;
    // The faint many, then the bright few with their bloom.
    col += stars(dir, n, bu, bv, uv, face, 72.0, 0.42, 0.16, 0.55, 0.5, 0.8);
    col += stars(dir, n, bu, bv, uv, face, 22.0, 0.42, 0.45, 1.0, 0.8, 2.2);
    return half4(half3(col), 1.0);
}
"""

/**
 * The sky shader with its paint, made once. Drawn with one `drawPaint` per
 * frame; [draw] sets this frame's camera on it first.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class SpaceSky {
    private val shader = RuntimeShader(SPACE_SRC)
    private val paint = Paint().apply { shader = this@SpaceSky.shader }

    fun draw(canvas: android.graphics.Canvas, f: CameraFrame, time: Float, dp: Float) {
        shader.setFloatUniform("uCenter", f.centerX, f.centerY)
        shader.setFloatUniform("uFocal", f.focal)
        // The sky's own basis: it holds still while the galaxy turns.
        shader.setFloatUniform("uF", f.sfx, f.sfy, f.sfz)
        shader.setFloatUniform("uR", f.srx, f.sry, f.srz)
        shader.setFloatUniform("uU", f.sux, f.suy, f.suz)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uDp", dp)
        shader.setFloatUniform("uBase", SPACE_R, SPACE_G, SPACE_B)
        canvas.drawPaint(paint)
    }

    private companion object {
        // GALAXY_SPACE, as floats.
        const val SPACE_R = 0x04 / 255f
        const val SPACE_G = 0x06 / 255f
        const val SPACE_B = 0x0E / 255f
    }
}

/**
 * The sky shader on Android 13 and up, unless the device asked for less work;
 * null otherwise, and the sky falls back to its flat colour and drawn points.
 */
@Composable
internal fun rememberSpaceSky(enabled: Boolean): SpaceSky? =
    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { runCatching { SpaceSky() }.getOrNull() }
    } else {
        null
    }
