package tf.monochrome.android.visualizer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * MilkDrop as a layer inside the album's atmosphere, rather than instead of it.
 *
 * The hero visualizer replaces the artwork: an opaque black surface at the
 * cover's aspect ratio, or the whole screen. This is the other composition —
 * the preset drawn *over* the blurred cover and its scrim, under the player's
 * glass, so the visuals feel like part of the artwork rather than a separate
 * full-screen mode.
 *
 * ## Transparency is inferred from the rendered image, not from the preset
 *
 * MilkDrop presets assume they own an opaque framebuffer, usually black, and a
 * great many depend on frame feedback — trails, warps, glow accumulation. Make
 * the render target transparent and those break. So projectM keeps rendering
 * exactly as it always has, into an opaque buffer, and the alpha is derived
 * afterwards from the pixels it produced: black falls away, dim trails go
 * translucent, bright waves and particles survive. That is what makes this
 * work across an entire `.milk` collection without touching a single preset.
 *
 * ## Why the maths lives here in Kotlin as well as in GLSL
 *
 * [AMBIENT_FRAGMENT_SHADER] is the code that runs. The functions in this file
 * are the same arithmetic written again in Kotlin, and they exist so it can be
 * tested: there is no device in the build, so a shader is otherwise a string
 * nobody can check. The two must be kept in step by hand — `AmbientVisualizerTest`
 * pins the behaviour the shader is supposed to have.
 */

/** How the preset's pixels are combined with the album backdrop under them. */
enum class VisualizerBlendMode(val id: String, val label: String) {
    /** Straight over the top. Faithful colours, heaviest on the artwork. */
    NORMAL("normal", "Normal"),

    /**
     * The default, and the one MilkDrop was made for: black contributes
     * nothing at all, and luminous detail sits over the cover without
     * flattening it.
     */
    SCREEN("screen", "Screen"),

    /** Brightest. Blows out quickly on a pale cover. */
    ADDITIVE("additive", "Additive"),

    /** Gentlest — tints the backdrop rather than lighting it. */
    SOFT_LIGHT("soft_light", "Soft Light");

    companion object {
        val DEFAULT = SCREEN
        fun fromId(id: String?): VisualizerBlendMode =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * The three controls, as stored.
 *
 * Percentages rather than floats because that is what a slider and a
 * preference hold, and because "2%" is a thing a person can read back to you.
 */
data class AmbientVisualizerSettings(
    val enabled: Boolean = false,
    /** Overall contribution, 0–100. */
    val opacityPercent: Int = DEFAULT_OPACITY,
    /** How much of the dark end goes invisible, 0–[MAX_BLACK_POINT] percent. */
    val blackPointPercent: Int = DEFAULT_BLACK_POINT,
    val blend: VisualizerBlendMode = VisualizerBlendMode.DEFAULT,
) {
    val opacity: Float get() = (opacityPercent / 100f).coerceIn(0f, 1f)

    /** The lower edge of the fade, in luminance. */
    val blackPoint: Float
        get() = (blackPointPercent.coerceIn(0, MAX_BLACK_POINT) / 100f)

    companion object {
        const val DEFAULT_OPACITY = 70
        const val DEFAULT_BLACK_POINT = 2
        const val MAX_BLACK_POINT = 25
    }
}

/**
 * How far above the black point a pixel has to climb to be fully opaque.
 *
 * Constant rather than a fourth slider. It is the softness of the fade, and
 * the two ends of it are not independently useful — moving the black point
 * moves the whole ramp, which is the control people actually want.
 */
const val AMBIENT_KNEE = 0.16f

/** Rec. 709 luma. */
internal fun luminance(r: Float, g: Float, b: Float): Float =
    0.2126f * r + 0.7152f * g + 0.0722f * b

internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x < edge0) 0f else 1f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * How much of this pixel survives.
 *
 * Plain luminance is not enough for MilkDrop. A saturated blue at full
 * intensity has a Rec. 709 luma of 0.07 — below almost any useful threshold —
 * so a luminance-only rule erases exactly the deep blues, reds and purples
 * that presets lean on. Taking the max of the luma and the brightest channel
 * (scaled, so a saturated colour does not read as *brighter* than white)
 * keeps them.
 *
 * The final `pow(0.8)` lifts the low end: it makes faint trails visible
 * without dragging the black point up with them.
 */
internal fun ambientAlpha(
    r: Float,
    g: Float,
    b: Float,
    blackPoint: Float,
    opacity: Float,
    knee: Float = AMBIENT_KNEE,
): Float {
    val peak = max(r, max(g, b))
    val lum = luminance(r, g, b)
    val ramp = smoothstep(blackPoint, blackPoint + knee, max(peak * 0.8f, lum))
    return (ramp.pow(0.8f) * opacity).coerceIn(0f, 1f)
}

/** One channel of [mode], `bg` being the album backdrop and `fx` the preset. */
internal fun blendChannel(bg: Float, fx: Float, mode: VisualizerBlendMode): Float = when (mode) {
    VisualizerBlendMode.NORMAL -> fx
    VisualizerBlendMode.SCREEN -> 1f - (1f - bg) * (1f - fx)
    VisualizerBlendMode.ADDITIVE -> min(1f, bg + fx)
    // W3C compositing soft-light, the same curve Photoshop uses.
    VisualizerBlendMode.SOFT_LIGHT -> if (fx <= 0.5f) {
        bg - (1f - 2f * fx) * bg * (1f - bg)
    } else {
        val d = if (bg <= 0.25f) ((16f * bg - 12f) * bg + 4f) * bg else sqrt(bg)
        bg + (2f * fx - 1f) * (d - bg)
    }
}

/** The blend, faded in by the alpha the image itself implied. */
internal fun compositeChannel(
    bg: Float,
    fx: Float,
    alpha: Float,
    mode: VisualizerBlendMode,
): Float = bg + (blendChannel(bg, fx, mode) - bg) * alpha

// ── The shader ──────────────────────────────────────────────────────────

/**
 * Vertex stage: a full-screen triangle strip, with the two textures sampled
 * on the same 0..1 grid.
 *
 * `vAlbum` is flipped in Y against `vFx` on purpose. The album comes from a
 * `Bitmap` uploaded with `texImage2D`, whose first row is the top; the
 * visualizer texture comes from a framebuffer blit, whose first row is the
 * bottom. Two conventions meeting in one pass, resolved once here rather than
 * with a flag in the fragment stage.
 */
const val AMBIENT_VERTEX_SHADER = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vFx;
out vec2 vAlbum;
void main() {
    vec2 uv = aPos * 0.5 + 0.5;
    vFx = uv;
    vAlbum = vec2(uv.x, 1.0 - uv.y);
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

/**
 * Fragment stage: album, scrim, alpha, blend — one pass, no readback.
 *
 * The scrim reproduces `PlayerBlurredArtBackground`'s three stops (black at
 * 0.58, a darkened album tone at 0.52, black at 0.72) in screen space,
 * because this layer stands in for that composable when ambient mode is on
 * and must not look like a different background.
 *
 * Output is **opaque**. The blend cannot be left to the view compositor —
 * HWUI composites a TextureView with plain source-over and `glBlendFunc`
 * only reaches inside our own surface — so the only way to screen-blend
 * against the artwork is to have the artwork here, in the shader. Which
 * also means this layer *is* the background rather than a pane over it, so
 * there is no seam to hide.
 */
const val AMBIENT_FRAGMENT_SHADER = """#version 300 es
precision mediump float;

in vec2 vFx;
in vec2 vAlbum;
out vec4 fragColor;

uniform sampler2D uFx;      // what projectM drew, opaque
uniform sampler2D uAlbum;   // the cover, small and upscaled into a blur
uniform vec3 uScrimTone;    // dominant mixed toward black
uniform float uOpacity;
uniform float uBlackPoint;
uniform float uKnee;
uniform int uBlend;         // 0 normal, 1 screen, 2 additive, 3 soft light

vec3 albumBackdrop(vec2 uv) {
    vec3 art = texture(uAlbum, uv).rgb;
    // Screen-space vertical scrim, matching PlayerBlurredArtBackground.
    float y = clamp(vFx.y, 0.0, 1.0);
    float top = 1.0 - smoothstep(0.0, 0.5, y);
    float bot = smoothstep(0.5, 1.0, y);
    float mid = 1.0 - top - bot;
    vec3 dark = mix(art, vec3(0.0), 0.58 * top + 0.72 * bot);
    return mix(dark, uScrimTone, 0.52 * mid);
}

float ambientAlpha(vec3 c) {
    float peak = max(c.r, max(c.g, c.b));
    float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float ramp = smoothstep(uBlackPoint, uBlackPoint + uKnee, max(peak * 0.8, lum));
    return clamp(pow(ramp, 0.8) * uOpacity, 0.0, 1.0);
}

vec3 softLight(vec3 bg, vec3 fx) {
    vec3 d = mix(sqrt(bg), ((16.0 * bg - 12.0) * bg + 4.0) * bg, step(bg, vec3(0.25)));
    vec3 lo = bg - (1.0 - 2.0 * fx) * bg * (1.0 - bg);
    vec3 hi = bg + (2.0 * fx - 1.0) * (d - bg);
    return mix(hi, lo, step(fx, vec3(0.5)));
}

vec3 blended(vec3 bg, vec3 fx) {
    if (uBlend == 1) return 1.0 - (1.0 - bg) * (1.0 - fx);
    if (uBlend == 2) return min(vec3(1.0), bg + fx);
    if (uBlend == 3) return softLight(bg, fx);
    return fx;
}

void main() {
    vec3 fx = texture(uFx, vFx).rgb;
    vec3 bg = albumBackdrop(vAlbum);
    float a = ambientAlpha(fx);
    fragColor = vec4(mix(bg, blended(bg, fx), a), 1.0);
}
"""
