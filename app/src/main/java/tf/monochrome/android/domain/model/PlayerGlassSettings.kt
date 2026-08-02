package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Liquid-glass parameters for the PLAYER chrome (the transport buttons) — the
 * same refractive-glass controls the lyrics have, but for the player. Tuned in
 * the Player Visuals Studio's "Player Glass" tab and persisted as one JSON blob.
 * Defaults reproduce the shipped look: a ghost-thin body at full refraction
 * with heavy dispersion, a mirror-strong room reflection under a tight
 * polished glint, a dim rim on a hairline edge, perfectly clear (unfrosted)
 * glass at neutral relief, a calm living surface, a locked (tilt-free) key
 * light at 215°, and a faint, tight drop shadow.
 */
@Serializable
data class PlayerGlassSettings(
    /** Master on/off for the button glass. */
    val enabled: Boolean = true,
    /** Body see-through amount (lower = more transparent). */
    val bodyOpacity: Float = 0.2f,
    /** How hard the bevel lenses the backdrop. */
    val refraction: Float = 0.4f,
    /** Specular rim brightness (the lit glass edge). */
    val rimBrightness: Float = 0.2633547f,
    /** Chromatic aberration at the refracting edges. */
    val dispersion: Float = 1.8702691f,
    /** Bevel sample rings 1/2/3 → 5/9/13 taps per pixel (quality vs GPU cost). */
    val sampleRings: Int = 3,
    /** Bevel shoulder width (1 = neutral, higher = rounder, softer glass edge). */
    val roundness: Float = 2f,
    /** Profondeur / relief: 1 = neutral, higher = steeper, deeper 3D bevel. */
    val depth: Float = 1.0025804f,
    /** Drop-shadow depth (darkness) under the round play button (0 = flat, 1 = deepest). */
    val shadowDepth: Float = 0.20475428f,
    /** Environment ("room") reflection strength on the glass (0 = none, 2 = strong). */
    val reflection: Float = 2f,
    /** Highlight polish: 0 = soft/frosted-wide glint, 1 = tight mirror-polished. */
    val gloss: Float = 1f,
    /** Living-liquid surface motion: 0 = still glass, 1 = full shimmer/undulation. */
    val surfaceMotion: Float = 0.53f,
    /** How strongly device tilt moves the light/reflection (0 = static studio light). */
    val tiltReactivity: Float = 0f,
    /** Key-light direction in degrees (0..360) — where the highlights sit. */
    val lightAngleDeg: Float = 215.1965f,
    /** Reflective rim width: 0 = thin crisp edge, 1 = broad glassy shoulder. */
    val edgeWidth: Float = 0f,
    /** Frosted roughness: 0 = clear glass, 1 = misted/frosted. */
    val frost: Float = 0f,
    /** Drop-shadow softness (blur/spread): 0 = tight, 1 = soft diffuse. */
    val shadowSoftness: Float = 0.01565171f,
    /** Drop-shadow tint: 0 = neutral black, 1 = full accent-tinted glow. */
    val shadowTint: Float = 0f,
    /** Button glass tint as an ARGB int; 0 = use the current album accent. */
    val tintColor: Int = 0,
    /** Studio-preview background as an ARGB int; 0 = the current album wash. */
    val previewBg: Int = 0,
    /** Glass "thermometer" scrubber (tube + sine-bulge dot) vs a plain slider. */
    val progressGlass: Boolean = true,
    /**
     * Backdrop (Haze) frost blur radius in dp for surfaces that gaussian-blur
     * what's behind them — the mini player bar, the player's audio-tools
     * sheet, the nav pill. 0 disables the frost layer entirely. Distinct from
     * [frost], which is the shader's surface roughness.
     */
    val hazeBlurDp: Float = 40f,
    /** Strength multiplier on the frost layer's luminance-picked tint (0–2). */
    val hazeTint: Float = 1f,
) {
    fun clamped(): PlayerGlassSettings {
        val d = DEFAULT
        fun Float.c(min: Float, max: Float, fb: Float) = if (isFinite()) coerceIn(min, max) else fb
        return copy(
            bodyOpacity = bodyOpacity.c(0.2f, 1f, d.bodyOpacity),
            refraction = refraction.c(0f, 0.4f, d.refraction),
            rimBrightness = rimBrightness.c(0f, 2f, d.rimBrightness),
            dispersion = dispersion.c(0f, 2f, d.dispersion),
            sampleRings = sampleRings.coerceIn(1, 3),
            roundness = roundness.c(0.5f, 2f, d.roundness),
            depth = depth.c(0.5f, 2f, d.depth),
            shadowDepth = shadowDepth.c(0f, 1f, d.shadowDepth),
            reflection = reflection.c(0f, 2f, d.reflection),
            gloss = gloss.c(0f, 1f, d.gloss),
            surfaceMotion = surfaceMotion.c(0f, 1f, d.surfaceMotion),
            tiltReactivity = tiltReactivity.c(0f, 1.5f, d.tiltReactivity),
            lightAngleDeg = lightAngleDeg.c(0f, 360f, d.lightAngleDeg),
            edgeWidth = edgeWidth.c(0f, 1f, d.edgeWidth),
            frost = frost.c(0f, 1f, d.frost),
            shadowSoftness = shadowSoftness.c(0f, 1f, d.shadowSoftness),
            shadowTint = shadowTint.c(0f, 1f, d.shadowTint),
            hazeBlurDp = hazeBlurDp.c(0f, 80f, d.hazeBlurDp),
            hazeTint = hazeTint.c(0f, 2f, d.hazeTint),
        )
    }

    /**
     * The user's personal/perf settings — the chosen button-tint colour, the
     * Studio-preview background, and the per-pixel quality — that a theme should
     * carry over rather than overwrite. A theme changes only the glass MATERIAL.
     */
    fun withPersonalFrom(other: PlayerGlassSettings): PlayerGlassSettings = copy(
        sampleRings = other.sampleRings,
        tintColor = other.tintColor,
        previewBg = other.previewBg,
    )

    /**
     * True when this equals [preset] on every material field — i.e. once the
     * personal fields (which themes never carry) are set aside. Lights the
     * selected theme chip regardless of the user's colour/quality choices.
     */
    fun matchesPreset(preset: PlayerGlassSettings): Boolean = this == preset.withPersonalFrom(this)

    companion object {
        val DEFAULT = PlayerGlassSettings()

        /**
         * Built-in glass MATERIAL themes — one unified roster, paired 1:1 with
         * the Lyrics FX presets of the SAME NAME so picking a theme on both
         * tabs composes a single look. Each varies only the aesthetic fields;
         * tintColor / previewBg / sampleRings stay at their defaults so a theme
         * never touches the user's colour or quality.
         *
         * Tuned for the reworked glass optics: `refraction` now also drives the
         * interior slab parallax (flat faces lens, not just bevels) and
         * `surfaceMotion` scales the whole living layer — face swell, edge
         * shimmer, the traveling light sheet and the glint twinkle — so 0 is
         * truly still and 1 is fully alive.
         */
        val PRESETS: List<Pair<String, PlayerGlassSettings>> = listOf(
            // The shipped look.
            "Default" to DEFAULT,
            // Chrome — liquid metal: polished mirror body, steady surface,
            // tight bright glint. (Name pinned by tests.)
            "Chrome" to PlayerGlassSettings(
                bodyOpacity = 0.66f, refraction = 0.14f, rimBrightness = 1.8f, dispersion = 0.6f,
                roundness = 0.75f, depth = 1.25f, reflection = 1.8f, gloss = 0.9f,
                surfaceMotion = 0.18f, tiltReactivity = 0.8f, lightAngleDeg = 120f, edgeWidth = 0.22f,
                frost = 0f, shadowDepth = 0.5f, shadowSoftness = 0.3f,
            ),
            // Frosted — etched sea-glass: heavy mist, broad soft shoulder, dull
            // wide highlight, a slow living surface. (Name pinned by tests.)
            "Frosted" to PlayerGlassSettings(
                bodyOpacity = 0.74f, refraction = 0.12f, rimBrightness = 0.85f, dispersion = 0.5f,
                roundness = 1.7f, depth = 0.85f, reflection = 0.5f, gloss = 0.12f,
                surfaceMotion = 0.35f, tiltReactivity = 0.5f, lightAngleDeg = 150f, edgeWidth = 0.75f,
                frost = 0.85f, shadowDepth = 0.35f, shadowSoftness = 0.8f,
            ),
            // Neon — electric sign: blazing rim, strong fringing, full
            // accent-tinted glow under the disc. (Name pinned by tests.)
            "Neon" to PlayerGlassSettings(
                bodyOpacity = 0.42f, refraction = 0.22f, rimBrightness = 2f, dispersion = 1.7f,
                roundness = 1f, depth = 1.35f, reflection = 1.7f, gloss = 0.8f,
                surfaceMotion = 0.5f, tiltReactivity = 0.9f, lightAngleDeg = 335f, edgeWidth = 0.4f,
                frost = 0f, shadowDepth = 0.75f, shadowSoftness = 0.75f, shadowTint = 1f,
            ),
            // Voltage — harder and choppier than Neon: deep relief, restless
            // surface, hard side light, tinted strike shadow.
            "Voltage" to PlayerGlassSettings(
                bodyOpacity = 0.46f, refraction = 0.28f, rimBrightness = 1.9f, dispersion = 1.4f,
                roundness = 0.85f, depth = 1.5f, reflection = 1.4f, gloss = 0.7f,
                surfaceMotion = 0.75f, tiltReactivity = 1.1f, lightAngleDeg = 60f, edgeWidth = 0.3f,
                frost = 0f, shadowDepth = 0.6f, shadowSoftness = 0.45f, shadowTint = 0.7f,
            ),
            // Glacier — arctic stillness: thin cold rim, faint mist, almost no
            // motion, a crisp restrained glint.
            "Glacier" to PlayerGlassSettings(
                bodyOpacity = 0.55f, refraction = 0.1f, rimBrightness = 1.5f, dispersion = 0.35f,
                roundness = 0.9f, depth = 0.9f, reflection = 1.1f, gloss = 0.65f,
                surfaceMotion = 0.06f, tiltReactivity = 0.4f, lightAngleDeg = 105f, edgeWidth = 0.2f,
                frost = 0.15f, shadowDepth = 0.3f, shadowSoftness = 0.4f,
            ),
            // Bloom — dreamy soft-focus: pillowy shoulder, light mist, gentle
            // living surface, softly tinted floated shadow.
            "Bloom" to PlayerGlassSettings(
                bodyOpacity = 0.5f, refraction = 0.2f, rimBrightness = 1.2f, dispersion = 1.3f,
                roundness = 1.6f, depth = 1.1f, reflection = 0.9f, gloss = 0.35f,
                surfaceMotion = 0.45f, tiltReactivity = 0.7f, lightAngleDeg = 160f, edgeWidth = 0.65f,
                frost = 0.25f, shadowDepth = 0.5f, shadowSoftness = 0.85f, shadowTint = 0.4f,
            ),
            // Midnight — noir: dark near-still glass, dim rim, deep relief,
            // light from the lower left like a table lamp.
            "Midnight" to PlayerGlassSettings(
                bodyOpacity = 0.38f, refraction = 0.12f, rimBrightness = 0.75f, dispersion = 0.45f,
                roundness = 1.15f, depth = 1.5f, reflection = 0.35f, gloss = 0.22f,
                surfaceMotion = 0.12f, tiltReactivity = 0.3f, lightAngleDeg = 210f, edgeWidth = 0.5f,
                frost = 0.1f, shadowDepth = 0.65f, shadowSoftness = 0.6f,
            ),
            // Silk — draped softness: the widest shoulder, satin (not mirror)
            // highlight, deepest softest shadow bed.
            "Silk" to PlayerGlassSettings(
                bodyOpacity = 0.6f, refraction = 0.13f, rimBrightness = 1f, dispersion = 0.7f,
                roundness = 1.85f, depth = 0.95f, reflection = 0.7f, gloss = 0.28f,
                surfaceMotion = 0.3f, tiltReactivity = 0.55f, lightAngleDeg = 145f, edgeWidth = 0.8f,
                frost = 0.3f, shadowDepth = 0.75f, shadowSoftness = 1f, shadowTint = 0.2f,
            ),
            // Hyper — everything on: full surface churn, hardest gyro sway,
            // top-right strike light, tinted kick shadow.
            "Hyper" to PlayerGlassSettings(
                bodyOpacity = 0.45f, refraction = 0.26f, rimBrightness = 1.7f, dispersion = 1.5f,
                roundness = 1.2f, depth = 1.4f, reflection = 1.5f, gloss = 0.6f,
                surfaceMotion = 1f, tiltReactivity = 1.3f, lightAngleDeg = 30f, edgeWidth = 0.45f,
                frost = 0f, shadowDepth = 0.6f, shadowSoftness = 0.5f, shadowTint = 0.5f,
            ),
            // Prism — cut diamond: maxed refraction + dispersion, top-lit,
            // faceted (steep depth, low roundness), thin crisp edge.
            "Prism" to PlayerGlassSettings(
                bodyOpacity = 0.5f, refraction = 0.4f, rimBrightness = 1.8f, dispersion = 2f,
                roundness = 0.55f, depth = 1.9f, reflection = 1.5f, gloss = 0.9f,
                surfaceMotion = 0.2f, tiltReactivity = 1f, lightAngleDeg = 90f, edgeWidth = 0.15f,
                frost = 0f, shadowDepth = 0.5f, shadowSoftness = 0.3f,
            ),
            // Mirage — molten sea-glass: near-full churn UNDER heavy frost, a
            // broad dull shoulder, heat-haze languor.
            "Mirage" to PlayerGlassSettings(
                bodyOpacity = 0.52f, refraction = 0.26f, rimBrightness = 0.85f, dispersion = 1f,
                roundness = 1.55f, depth = 1.25f, reflection = 0.55f, gloss = 0.15f,
                surfaceMotion = 0.9f, tiltReactivity = 0.85f, lightAngleDeg = 200f, edgeWidth = 0.7f,
                frost = 0.75f, shadowDepth = 0.5f, shadowSoftness = 0.85f, shadowTint = 0.35f,
            ),
            // Aurora — alive holo glass: reactive tilt, an unusual back-left key
            // light and an accent-tinted bloom under the disc.
            "Aurora" to PlayerGlassSettings(
                bodyOpacity = 0.44f, refraction = 0.3f, rimBrightness = 1.45f, dispersion = 1.6f,
                roundness = 1.4f, depth = 1.35f, reflection = 1.25f, gloss = 0.5f,
                surfaceMotion = 0.65f, tiltReactivity = 1.25f, lightAngleDeg = 300f, edgeWidth = 0.55f,
                frost = 0.1f, shadowDepth = 0.55f, shadowSoftness = 0.7f, shadowTint = 0.65f,
            ),
            // Onyx — locked-studio brutalism: perfectly still (zero motion, zero
            // tilt), lit from BELOW, cut like a faceted gem.
            "Onyx" to PlayerGlassSettings(
                bodyOpacity = 0.42f, refraction = 0.1f, rimBrightness = 0.7f, dispersion = 0.3f,
                roundness = 0.75f, depth = 1.75f, reflection = 0.3f, gloss = 0.25f,
                surfaceMotion = 0f, tiltReactivity = 0f, lightAngleDeg = 270f, edgeWidth = 0.5f,
                frost = 0.15f, shadowDepth = 0.6f, shadowSoftness = 0.5f,
            ),
            // Halo — ghost levitate: near-invisible body over a lensed backdrop,
            // maximum gyro tilt, maximally floated soft accent shadow.
            "Halo" to PlayerGlassSettings(
                bodyOpacity = 0.26f, refraction = 0.34f, rimBrightness = 1.65f, dispersion = 1.5f,
                roundness = 1.3f, depth = 1.35f, reflection = 1.55f, gloss = 0.6f,
                surfaceMotion = 0.55f, tiltReactivity = 1.5f, lightAngleDeg = 315f, edgeWidth = 0.5f,
                frost = 0f, shadowDepth = 1f, shadowSoftness = 0.95f, shadowTint = 0.7f,
            ),
            // Ticker — deliberately flat: button glass OFF and a plain progress
            // bar, matching the stripped-back single-line lyric ticker.
            "Ticker" to PlayerGlassSettings(
                enabled = false, progressGlass = false,
                bodyOpacity = 0.85f, refraction = 0.05f, rimBrightness = 0.7f, dispersion = 0.3f,
                roundness = 0.9f, depth = 0.7f, reflection = 0.35f, gloss = 0.3f,
                surfaceMotion = 0f, tiltReactivity = 0f, edgeWidth = 0.35f,
                frost = 0f, shadowDepth = 0.2f, shadowSoftness = 0.3f,
            ),
            // Static — accessible stillness with the glass kept ON: zero motion,
            // zero tilt, modest neutral material (the no-animation counterpart
            // to the Static lyric preset).
            "Static" to PlayerGlassSettings(
                bodyOpacity = 0.62f, refraction = 0.1f, rimBrightness = 1f, dispersion = 0.6f,
                roundness = 1f, depth = 0.9f, reflection = 0.6f, gloss = 0.35f,
                surfaceMotion = 0f, tiltReactivity = 0f, lightAngleDeg = 135f, edgeWidth = 0.4f,
                frost = 0.05f, shadowDepth = 0.3f, shadowSoftness = 0.4f,
            ),
        )
    }
}

/**
 * A user-saved Player Glass theme: a name plus a full [PlayerGlassSettings]
 * snapshot. Serialises to a compact, shareable code ([encode]/[decode]) so a
 * whole glass look travels in one copy-pasteable string, exactly like the
 * Lyrics FX presets.
 */
@Serializable
data class PlayerGlassPreset(
    val name: String,
    val settings: PlayerGlassSettings,
) {
    companion object {
        /** Marker so an imported blob is recognisably one of our glass codes. */
        const val CODE_PREFIX = "TRYPTGLASS1:"

        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Encode a preset to a single shareable string (prefix + compact JSON). */
        fun encode(preset: PlayerGlassPreset): String =
            CODE_PREFIX + codec.encodeToString(preset.copy(settings = preset.settings.clamped()))

        /**
         * Decode a shared code back to a preset, tolerating the prefix being
         * present or not and surrounding whitespace. Returns null if the text
         * isn't a valid preset. Settings are re-clamped so a hand-edited or
         * hostile code can't push values out of range.
         */
        fun decode(code: String): PlayerGlassPreset? = runCatching {
            val trimmed = code.trim()
            val start = trimmed.indexOf('{')
            require(start >= 0) { "no preset payload" }
            codec.decodeFromString<PlayerGlassPreset>(trimmed.substring(start)).let {
                it.copy(name = it.name.trim().ifBlank { "Imported" }, settings = it.settings.clamped())
            }
        }.getOrNull()
    }
}
