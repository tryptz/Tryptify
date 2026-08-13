package tf.monochrome.android.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Light themes, built rather than hand-written.
 *
 * The app had fifteen dark schemes and one light one, and the light one was the
 * only place a light value had ever been chosen deliberately. Everything else
 * light-adjacent was a dark token reused: `onSurfaceVariant` set to a grey
 * picked to sit on near-black, glass alphas tuned against a dark backdrop. On
 * white those choices do not degrade gracefully, they fail — secondary text
 * lands near 3:1, and a translucent white slab on a white page has no edge at
 * all.
 *
 * So a light scheme here is *derived*, from two inputs: the theme's accent, and
 * which paper it is printed on. Every foreground is then pushed through
 * [ensureContrast] against the surface it will actually sit on, so a scheme
 * cannot ship failing WCAG AA no matter what accent it was handed — which is
 * the part that hand-written palettes kept getting wrong, fifteen times over.
 */
enum class Paper {
    /** True white. High contrast, crisp, closer to a UI than a page. */
    Crisp,

    /** Warm off-white. Softer, less glare, closer to paper. */
    Warm,
}

/** The neutral ramp a light scheme is printed on. */
private data class PaperRamp(
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val container: Color,
    val outline: Color,
    val ink: Color,
    val inkMuted: Color,
)

private val CrispRamp = PaperRamp(
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F1F4),
    container = Color(0xFFF7F7FA),
    outline = Color(0xFFD5D5DC),
    ink = Color(0xFF14141A),
    inkMuted = Color(0xFF4A4A55),
)

private val WarmRamp = PaperRamp(
    // Warm is not "white with a yellow filter": the whole ramp is shifted, so
    // the greys stay neutral *relative to their own ground* instead of reading
    // as a cool grey card dropped onto a cream page.
    background = Color(0xFFFAF7F2),
    surface = Color(0xFFFFFCF7),
    surfaceVariant = Color(0xFFF0EAE0),
    container = Color(0xFFF6F1E8),
    outline = Color(0xFFDCD4C6),
    ink = Color(0xFF1B1813),
    inkMuted = Color(0xFF4E483D),
)

private fun rampOf(paper: Paper) = when (paper) {
    Paper.Crisp -> CrispRamp
    Paper.Warm -> WarmRamp
}

/**
 * The same paper, carrying a trace of the theme's own hue.
 *
 * Without this every light variant was the same page with a different accent
 * on it — Ocean Light and Forest Light differed only in the colour of their
 * icons, which is not a theme, it is a highlighter. The ground now leans the
 * way the accent does.
 *
 * Tinted toward a *pale* version of the accent rather than the accent itself.
 * Blending white paper toward Monochrome's near-black or Crimson's deep red
 * would darken the page rather than colour it, and a light theme that arrives
 * at grey has stopped being one. Lifting the accent to a tint first means the
 * hue survives and the lightness does not move.
 *
 * The amounts climb with the layer: the page barely leans, the cards lean more,
 * the outlines most. That ordering is what keeps a card visible against the
 * page it sits on — the separation between them is now hue as well as value.
 */
private fun PaperRamp.tintedBy(accent: Color): PaperRamp {
    val pale = blend(Color.White, accent, 0.30f)
    return PaperRamp(
        background = blend(background, pale, 0.10f),
        surface = blend(surface, pale, 0.06f),
        surfaceVariant = blend(surfaceVariant, pale, 0.20f),
        container = blend(container, pale, 0.16f),
        outline = blend(outline, pale, 0.34f),
        // The ink takes the faintest trace of it too, so text on a tinted page
        // does not read as pure black dropped onto a coloured sheet.
        ink = blend(ink, accent, 0.08f),
        inkMuted = blend(inkMuted, accent, 0.14f),
    )
}

// ── Contrast ────────────────────────────────────────────────────────────────

/** WCAG relative luminance. */
internal fun relativeLuminance(color: Color): Double {
    fun channel(v: Float): Double {
        val c = v.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

/** WCAG contrast ratio, 1.0 (identical) to 21.0 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
}

/**
 * The same colour, darkened only as far as it must be to be readable on [on].
 *
 * Scaling happens in linear light, which keeps the hue and drops the lightness
 * — the accent still looks like itself, it is just no longer a neon meant for a
 * black background. Binary search rather than a fixed step because the accents
 * range from Gruvbox's yellow, which needs a great deal of darkening to pass on
 * white, to Cyberpunk's red, which needs almost none.
 *
 * Returns black if even that fails, which cannot happen against any light
 * ground but is the honest floor.
 */
internal fun ensureContrast(color: Color, on: Color, minimum: Double = 4.5): Color {
    if (contrastRatio(color, on) >= minimum) return color

    fun scaled(factor: Double): Color {
        fun toLinear(v: Float): Double {
            val c = v.toDouble()
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        fun toSrgb(v: Double): Float {
            val c = v.coerceIn(0.0, 1.0)
            val s = if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1 / 2.4) - 0.055
            return s.coerceIn(0.0, 1.0).toFloat()
        }
        return Color(
            red = toSrgb(toLinear(color.red) * factor),
            green = toSrgb(toLinear(color.green) * factor),
            blue = toSrgb(toLinear(color.blue) * factor),
            alpha = color.alpha,
        )
    }

    var low = 0.0
    var high = 1.0
    repeat(24) {
        val mid = (low + high) / 2
        if (contrastRatio(scaled(mid), on) >= minimum) low = mid else high = mid
    }
    val result = scaled(low)
    return if (contrastRatio(result, on) >= minimum) result else Color.Black
}

/** Whichever of black or white reads better on [color]. */
private fun inkOn(color: Color): Color =
    if (contrastRatio(Color.White, color) >= contrastRatio(Color.Black, color)) {
        Color.White
    } else {
        Color.Black
    }

/** [amount] of [top] laid over [bottom]. */
private fun blend(bottom: Color, top: Color, amount: Float): Color = Color(
    red = bottom.red + (top.red - bottom.red) * amount,
    green = bottom.green + (top.green - bottom.green) * amount,
    blue = bottom.blue + (top.blue - bottom.blue) * amount,
    alpha = 1f,
)

// ── Scheme construction ─────────────────────────────────────────────────────

/**
 * A light scheme in [accent], printed on [paper].
 *
 * The accent is darkened until it is legible *as text* on the surface, because
 * in this app the primary is not only a fill — it is the colour of icons, of
 * the globe's coastlines, of active labels. A primary that only works behind
 * `onPrimary` would fail every one of those.
 */
fun lightSchemeFor(accent: Color, paper: Paper): ColorScheme {
    val ramp = rampOf(paper).tintedBy(accent)

    val primary = ensureContrast(accent, ramp.surface)
    val primaryContainer = blend(ramp.surface, accent, 0.18f)
    val secondary = ensureContrast(blend(accent, ramp.ink, 0.35f), ramp.surface)

    return lightColorScheme(
        primary = primary,
        onPrimary = inkOn(primary),
        primaryContainer = primaryContainer,
        onPrimaryContainer = ensureContrast(ramp.ink, primaryContainer),
        secondary = secondary,
        onSecondary = inkOn(secondary),
        secondaryContainer = ramp.container,
        onSecondaryContainer = ensureContrast(ramp.ink, ramp.container),
        tertiary = ensureContrast(ramp.inkMuted, ramp.surface),
        onTertiary = ramp.surface,
        tertiaryContainer = ramp.container,
        onTertiaryContainer = ensureContrast(ramp.ink, ramp.container),
        background = ramp.background,
        onBackground = ensureContrast(ramp.ink, ramp.background),
        surface = ramp.surface,
        onSurface = ensureContrast(ramp.ink, ramp.surface),
        surfaceVariant = ramp.surfaceVariant,
        // The one that was reliably wrong before: secondary text sat on a token
        // chosen to be readable against near-black, and on a light card it came
        // out around 3:1.
        onSurfaceVariant = ensureContrast(ramp.inkMuted, ramp.surfaceVariant),
        surfaceContainerHigh = ramp.container,
        // Held to a floor against the surface, like everything else here.
        // Tinting the ramp toward a pale accent lightens the outline as well,
        // and a pale accent means a very light tint — Gold's landed at 1.29:1
        // on warm paper, which is a border you cannot see. Guaranteed rather
        // than asserted: the test that caught it should be confirming a
        // property, not discovering one.
        outline = ensureContrast(ramp.outline, ramp.surface, OUTLINE_CONTRAST),
        // Deliberately lighter than the outline: outlineVariant draws hairlines
        // between rows, where the outline proper is heavy enough to stripe a
        // list.
        outlineVariant = ensureContrast(
            blend(ramp.surfaceVariant, ramp.outline, 0.5f),
            ramp.surface,
            HAIRLINE_CONTRAST,
        ),
        error = LightError,
        onError = Color.White,
        errorContainer = blend(ramp.surface, LightError, 0.16f),
        onErrorContainer = ensureContrast(ramp.ink, blend(ramp.surface, LightError, 0.16f)),
    )
}

/**
 * Floors for the non-text furniture.
 *
 * Well under the 4.5:1 text needs — a border held to that would be a black
 * frame around every card — but far enough above 1:1 to be a line rather than a
 * suggestion of one.
 */
private const val OUTLINE_CONTRAST = 1.45
private const val HAIRLINE_CONTRAST = 1.18

/** Red that passes on light ground; the dark themes' ErrorRed does not. */
private val LightError = Color(0xFFB3261E)

/**
 * The accent each theme is known by, for building its light variant.
 *
 * The dark schemes' own primaries, unchanged — the light variant of Nord should
 * be recognisably Nord, and darkening for legibility happens in
 * [lightSchemeFor] rather than by picking a second, different blue here that
 * would then drift from the dark one.
 */
val ThemeAccents: Map<String, Color> = mapOf(
    "monochrome" to Color(0xFF1F1F1F),
    "ocean" to OceanPrimary,
    "midnight" to MidnightPrimary,
    "crimson" to CrimsonPrimary,
    "forest" to ForestPrimary,
    "sunset" to SunsetPrimary,
    "cyberpunk" to CyberpunkPrimary,
    "nord" to NordPrimary,
    "gruvbox" to GruvboxPrimary,
    "dracula" to DraculaPrimary,
    "solarized" to SolarizedPrimary,
    "lavender" to LavenderPrimary,
    "gold" to GoldPrimary,
    "rosewater" to RosewaterPrimary,
    "mint" to MintPrimary,
)

/** `"nord_light"` → `"nord"`, or null when the name is not a light variant. */
fun lightVariantBase(themeName: String): String? =
    themeName.removeSuffix(LIGHT_SUFFIX).takeIf { it != themeName }

/** `"nord"` → `"nord_light"`. */
fun lightVariantOf(themeName: String): String = themeName + LIGHT_SUFFIX

private const val LIGHT_SUFFIX = "_light"
