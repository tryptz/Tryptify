package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Liquid-glass parameters for the PLAYER chrome (the transport buttons) — the
 * same refractive-glass controls the lyrics have, but for the player. Tuned in
 * the Lyrics FX Studio's "Player Glass" tab and persisted as one JSON blob.
 * Defaults reproduce a chrome, lyric-like glass on the buttons.
 */
@Serializable
data class PlayerGlassSettings(
    /** Master on/off for the button glass. */
    val enabled: Boolean = true,
    /** Body see-through amount (lower = more transparent). */
    val bodyOpacity: Float = 0.5f,
    /** How hard the bevel lenses the backdrop. */
    val refraction: Float = 0.16f,
    /** Specular rim brightness (the lit glass edge). */
    val rimBrightness: Float = 1.3f,
    /** Chromatic aberration at the refracting edges. */
    val dispersion: Float = 1.2f,
    /** Bevel sample rings 1/2/3 → 5/9/13 taps per pixel (quality vs GPU cost). */
    val sampleRings: Int = 2,
    /** Bevel shoulder width (1 = neutral, higher = rounder, softer glass edge). */
    val roundness: Float = 1f,
    /** Profondeur / relief: 1 = neutral, higher = steeper, deeper 3D bevel. */
    val depth: Float = 1f,
    /** Drop-shadow depth (darkness) under the round play button (0 = flat, 1 = deepest). */
    val shadowDepth: Float = 0.45f,
    /** Environment ("room") reflection strength on the glass (0 = none, 2 = strong). */
    val reflection: Float = 1f,
    /** Highlight polish: 0 = soft/frosted-wide glint, 1 = tight mirror-polished. */
    val gloss: Float = 0.4f,
    /** Living-liquid surface motion: 0 = still glass, 1 = full shimmer/undulation. */
    val surfaceMotion: Float = 0.25f,
    /** How strongly device tilt moves the light/reflection (0 = static studio light). */
    val tiltReactivity: Float = 0.7f,
    /** Key-light direction in degrees (0..360) — where the highlights sit. */
    val lightAngleDeg: Float = 135f,
    /** Reflective rim width: 0 = thin crisp edge, 1 = broad glassy shoulder. */
    val edgeWidth: Float = 0.4f,
    /** Frosted roughness: 0 = clear glass, 1 = misted/frosted. */
    val frost: Float = 0f,
    /** Drop-shadow softness (blur/spread): 0 = tight, 1 = soft diffuse. */
    val shadowSoftness: Float = 0.4f,
    /** Drop-shadow tint: 0 = neutral black, 1 = full accent-tinted glow. */
    val shadowTint: Float = 0f,
    /** Button glass tint as an ARGB int; 0 = use the current album accent. */
    val tintColor: Int = 0,
    /** Studio-preview background as an ARGB int; 0 = the current album wash. */
    val previewBg: Int = 0,
    /** Glass "thermometer" scrubber (tube + sine-bulge dot) vs a plain slider. */
    val progressGlass: Boolean = true,
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
         * Built-in glass MATERIAL themes. Each varies only the aesthetic fields;
         * tintColor / previewBg stay 0 so a theme never touches the user's colour.
         */
        val PRESETS: List<Pair<String, PlayerGlassSettings>> = listOf(
            "Default" to DEFAULT,
            "Chrome" to PlayerGlassSettings(
                bodyOpacity = 0.62f, refraction = 0.12f, rimBrightness = 1.8f, dispersion = 0.7f,
                roundness = 0.8f, depth = 1.2f, reflection = 1.7f, gloss = 0.85f,
                surfaceMotion = 0.12f, edgeWidth = 0.25f, frost = 0f, shadowDepth = 0.5f,
                shadowSoftness = 0.35f,
            ),
            "Frosted" to PlayerGlassSettings(
                bodyOpacity = 0.7f, refraction = 0.1f, rimBrightness = 0.9f, dispersion = 0.6f,
                roundness = 1.5f, depth = 0.9f, reflection = 0.55f, gloss = 0.18f,
                surfaceMotion = 0.2f, edgeWidth = 0.7f, frost = 0.7f, shadowDepth = 0.4f,
                shadowSoftness = 0.7f,
            ),
            "Liquid" to PlayerGlassSettings(
                bodyOpacity = 0.44f, refraction = 0.3f, rimBrightness = 1.4f, dispersion = 1.4f,
                roundness = 1.7f, depth = 1.6f, reflection = 1.2f, gloss = 0.5f,
                surfaceMotion = 0.9f, edgeWidth = 0.6f, frost = 0.05f, shadowDepth = 0.5f,
                shadowSoftness = 0.55f,
            ),
            "Crystal" to PlayerGlassSettings(
                bodyOpacity = 0.5f, refraction = 0.22f, rimBrightness = 1.6f, dispersion = 2f,
                roundness = 0.7f, depth = 1.4f, reflection = 1.3f, gloss = 0.8f,
                surfaceMotion = 0.15f, edgeWidth = 0.2f, frost = 0f, shadowDepth = 0.45f,
                shadowSoftness = 0.3f,
            ),
            "Bubble" to PlayerGlassSettings(
                bodyOpacity = 0.32f, refraction = 0.34f, rimBrightness = 1.3f, dispersion = 1.1f,
                roundness = 2f, depth = 2f, reflection = 1.1f, gloss = 0.55f,
                surfaceMotion = 0.4f, edgeWidth = 0.85f, frost = 0f, shadowDepth = 0.6f,
                shadowSoftness = 0.85f,
            ),
            "Minimal" to PlayerGlassSettings(
                bodyOpacity = 0.85f, refraction = 0.05f, rimBrightness = 0.7f, dispersion = 0.3f,
                roundness = 0.9f, depth = 0.7f, reflection = 0.35f, gloss = 0.3f,
                surfaceMotion = 0.08f, edgeWidth = 0.35f, frost = 0f, shadowDepth = 0.25f,
                shadowSoftness = 0.3f,
            ),
            "Neon" to PlayerGlassSettings(
                bodyOpacity = 0.46f, refraction = 0.2f, rimBrightness = 2f, dispersion = 1.6f,
                roundness = 1f, depth = 1.3f, reflection = 1.8f, gloss = 0.75f,
                surfaceMotion = 0.35f, edgeWidth = 0.4f, frost = 0f, shadowDepth = 0.7f,
                shadowSoftness = 0.7f, shadowTint = 1f,
            ),
            "Obsidian" to PlayerGlassSettings(
                bodyOpacity = 0.4f, refraction = 0.14f, rimBrightness = 0.8f, dispersion = 0.5f,
                roundness = 1.1f, depth = 1.5f, reflection = 0.4f, gloss = 0.25f,
                surfaceMotion = 0.1f, edgeWidth = 0.5f, frost = 0.1f, shadowDepth = 0.6f,
                shadowSoftness = 0.55f,
            ),
            "Pillow" to PlayerGlassSettings(
                bodyOpacity = 0.58f, refraction = 0.14f, rimBrightness = 1f, dispersion = 0.8f,
                roundness = 1.9f, depth = 1.1f, reflection = 0.7f, gloss = 0.3f,
                surfaceMotion = 0.25f, edgeWidth = 0.75f, frost = 0.2f, shadowDepth = 0.8f,
                shadowSoftness = 1f,
            ),
            "Mirror" to PlayerGlassSettings(
                bodyOpacity = 0.7f, refraction = 0.08f, rimBrightness = 1.9f, dispersion = 0.6f,
                roundness = 0.6f, depth = 1f, reflection = 2f, gloss = 1f,
                surfaceMotion = 0.1f, edgeWidth = 0.15f, frost = 0f, shadowDepth = 0.5f,
                shadowSoftness = 0.3f,
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
