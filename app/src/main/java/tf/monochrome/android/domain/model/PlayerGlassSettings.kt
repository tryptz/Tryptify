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
     * what's behind them. 0 disables the frost layer entirely. Distinct from
     * [frost], which is the shader's surface roughness.
     *
     * Which surfaces depends on whose copy this is, because the app keeps two
     * of these blobs. The UI-panel copy drives the mini player bar, the
     * audio-tools sheet, the speed panel, the nav pill and the map panels; the
     * player copy drives the now-playing chrome — the action dock and the play
     * disc, whose punched glyphs open onto this blur rather than onto the raw
     * background.
     */
    val hazeBlurDp: Float = 40f,
    /** Strength multiplier on the frost layer's luminance-picked tint (0–2). */
    val hazeTint: Float = 1f,
    /**
     * The mini player's thin progress line, which doubles as the bar's top
     * border. Off drops both the line and the 2dp it occupies, so the bar
     * closes up rather than leaving a gap where it used to be.
     *
     * Personal, not material: whether you want a progress readout is a
     * preference about the bar, not part of a glass theme's optics, so
     * [withPersonalFrom] carries it across a theme change instead of letting
     * every preset silently switch it back on.
     */
    val miniProgressBar: Boolean = true,
) {
    fun clamped(): PlayerGlassSettings {
        val d = DEFAULT
        fun Float.c(min: Float, max: Float, fb: Float) = if (isFinite()) coerceIn(min, max) else fb
        return copy(
            bodyOpacity = bodyOpacity.c(0f, 1f, d.bodyOpacity),
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
     * Studio-preview background, the per-pixel quality, and whether the mini
     * player shows its progress line — that a theme should carry over rather
     * than overwrite. A theme changes only the glass MATERIAL.
     */
    fun withPersonalFrom(other: PlayerGlassSettings): PlayerGlassSettings = copy(
        sampleRings = other.sampleRings,
        tintColor = other.tintColor,
        previewBg = other.previewBg,
        miniProgressBar = other.miniProgressBar,
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
         * Clear — iOS's clear Liquid Glass, and what every glass setting starts
         * from ([INITIAL]). On the panes that bend the live screen (see
         * LiveGlassLens) it is almost nothing but optics: an 8% tint, the full
         * rim bend, a crisp rim lit from the upper left, a whisper of
         * dispersion and a calm room reflection. Still until touched — no
         * surface motion and no tilt, which on the app-wide mini player also
         * means no frame clock and no gravity sensor.
         */
        val CLEAR = PlayerGlassSettings(
            bodyOpacity = 0.16f, refraction = 0.4f, rimBrightness = 1.1f, dispersion = 0.3f,
            roundness = 2f, depth = 1.2f, reflection = 0.8f, gloss = 0.85f,
            surfaceMotion = 0f, tiltReactivity = 0f, lightAngleDeg = 120f, edgeWidth = 0.2f,
            frost = 0f, shadowDepth = 0.25f, shadowSoftness = 0.35f, shadowTint = 0f,
            hazeBlurDp = 32f, hazeTint = 0.8f,
        )

        /**
         * What every glass setting starts from — the player's and the mini
         * player's alike — and what "Reset to defaults" restores: [CLEAR], the
         * first chip.
         *
         * Not [DEFAULT], and DEFAULT does not move: it is also what a preset
         * inherits for any field it leaves out, and the fallback for a
         * non-finite value, so changing it would silently restyle everything
         * built on it. It is no longer offered as a chip.
         */
        val INITIAL = CLEAR

        /**
         * The glass MATERIAL themes, in two families. The first three are
         * iOS's own Liquid Glass: Clear, Tinted (the legibility variant) and
         * Tilt (its highlights riding the phone's motion). The rest extend the
         * same material where iOS does not go — a water drop, a prism, a soap
         * film, liquid metal, ice, a glowing halo, an aurora, dusk light and a
         * hologram — still nothing but optics, never colour: the tint is the
         * album's or the listener's own, and a theme never touches it.
         *
         * Every theme sets every material field. A field left out would be
         * inherited from [DEFAULT], whose 0.53 surface motion and heavy
         * dispersion would leak into a theme that never asked for them.
         *
         * None uses `frost`, the shader's grain: frosted glass is made from
         * backdrop blur (`hazeBlurDp`), which the live lens takes a fifth of:
         * 0 is crisp refraction, 80 is ~16dp of frost.
         *
         * Motion and tilt are not free. On the mini player, which is on every
         * screen, `surfaceMotion` > 0 runs a frame clock and `tiltReactivity`
         * > 0 holds the gravity sensor, app-wide. The iOS-quiet themes keep
         * both at zero; the ones that move are the ones about moving.
         */
        val PRESETS: List<Pair<String, PlayerGlassSettings>> = listOf(
            "Clear" to CLEAR,
            // Tinted — iOS's Tinted option: more body for legibility over busy
            // pages (a 30% tint), a heavier 12dp blur, a gentler bend.
            "Tinted" to PlayerGlassSettings(
                bodyOpacity = 0.6f, refraction = 0.32f, rimBrightness = 0.9f, dispersion = 0.15f,
                roundness = 1.8f, depth = 1.1f, reflection = 0.6f, gloss = 0.75f,
                surfaceMotion = 0f, tiltReactivity = 0f, lightAngleDeg = 120f, edgeWidth = 0.3f,
                frost = 0f, shadowDepth = 0.3f, shadowSoftness = 0.4f, shadowTint = 0f,
                hazeBlurDp = 60f, hazeTint = 1.2f,
            ),
            // Tilt — Clear whose highlights follow the phone, the way iOS's do:
            // tilt moves the key light along the rim, with a faint shimmer.
            "Tilt" to PlayerGlassSettings(
                bodyOpacity = 0.18f, refraction = 0.4f, rimBrightness = 1.4f, dispersion = 0.35f,
                roundness = 2f, depth = 1.3f, reflection = 1f, gloss = 0.9f,
                surfaceMotion = 0.2f, tiltReactivity = 1f, lightAngleDeg = 110f, edgeWidth = 0.25f,
                frost = 0f, shadowDepth = 0.3f, shadowSoftness = 0.4f, shadowTint = 0.2f,
                hazeBlurDp = 36f, hazeTint = 0.8f,
            ),
            // Pure — the least glass can be: full bend, no colour fringe, a
            // quiet rim and almost no reflection. Still.
            "Pure" to PlayerGlassSettings(
                bodyOpacity = 0.15f, refraction = 0.4f, rimBrightness = 0.4f, dispersion = 0f,
                roundness = 2f, depth = 1f, reflection = 0.25f, gloss = 0.9f,
                surfaceMotion = 0f, tiltReactivity = 0f, lightAngleDeg = 120f, edgeWidth = 0.1f,
                frost = 0f, shadowDepth = 0.15f, shadowSoftness = 0.3f, shadowTint = 0f,
                hazeBlurDp = 30f, hazeTint = 0.6f,
            ),
            // Droplet — a thick drop of water: the steepest, roundest rim, so
            // the edge magnifies hardest, with a slow wobble and a sharp glint.
            "Droplet" to PlayerGlassSettings(
                bodyOpacity = 0.1f, refraction = 0.4f, rimBrightness = 1.2f, dispersion = 0.5f,
                roundness = 2f, depth = 2f, reflection = 0.9f, gloss = 1f,
                surfaceMotion = 0.35f, tiltReactivity = 0.5f, lightAngleDeg = 100f, edgeWidth = 0.1f,
                frost = 0f, shadowDepth = 0.45f, shadowSoftness = 0.6f, shadowTint = 0.3f,
                hazeBlurDp = 24f, hazeTint = 0.7f,
            ),
            // Prism — cut crystal: a narrow, steep, faceted rim that splits the
            // backdrop into full rainbow fringes, which sway with tilt.
            "Prism" to PlayerGlassSettings(
                bodyOpacity = 0.12f, refraction = 0.4f, rimBrightness = 1.8f, dispersion = 2f,
                roundness = 0.6f, depth = 1.9f, reflection = 1.2f, gloss = 1f,
                surfaceMotion = 0f, tiltReactivity = 0.8f, lightAngleDeg = 60f, edgeWidth = 0.05f,
                frost = 0f, shadowDepth = 0.35f, shadowSoftness = 0.2f, shadowTint = 0.5f,
                hazeBlurDp = 28f, hazeTint = 0.8f,
            ),
            // Bubble — a soap film: almost no body, a wide iridescent shoulder
            // that catches the room, a soft glint and a lazy drift.
            "Bubble" to PlayerGlassSettings(
                bodyOpacity = 0.05f, refraction = 0.25f, rimBrightness = 2f, dispersion = 1.6f,
                roundness = 2f, depth = 0.8f, reflection = 1.4f, gloss = 0.3f,
                surfaceMotion = 0.6f, tiltReactivity = 0.4f, lightAngleDeg = 150f, edgeWidth = 0.9f,
                frost = 0f, shadowDepth = 0.1f, shadowSoftness = 0.8f, shadowTint = 0.7f,
                hazeBlurDp = 0f, hazeTint = 0.5f,
            ),
            // Mercury — liquid metal: a dense, mirror-bright body that moves,
            // a broad reflective shoulder and a deep floating shadow.
            "Mercury" to PlayerGlassSettings(
                bodyOpacity = 0.7f, refraction = 0.22f, rimBrightness = 1.6f, dispersion = 0.1f,
                roundness = 1.5f, depth = 1.6f, reflection = 2f, gloss = 1f,
                surfaceMotion = 0.45f, tiltReactivity = 0.6f, lightAngleDeg = 90f, edgeWidth = 0.5f,
                frost = 0f, shadowDepth = 0.6f, shadowSoftness = 0.5f, shadowTint = 0f,
                hazeBlurDp = 70f, hazeTint = 1.3f,
            ),
            // Ice — a cold slab: the heaviest blur (~16dp) frosts the page
            // behind without grain, under a still, clean edge.
            "Ice" to PlayerGlassSettings(
                bodyOpacity = 0.35f, refraction = 0.32f, rimBrightness = 1f, dispersion = 0.2f,
                roundness = 1.2f, depth = 1.4f, reflection = 0.7f, gloss = 0.6f,
                surfaceMotion = 0f, tiltReactivity = 0f, lightAngleDeg = 135f, edgeWidth = 0.35f,
                frost = 0f, shadowDepth = 0.3f, shadowSoftness = 0.5f, shadowTint = 0.1f,
                hazeBlurDp = 80f, hazeTint = 1.4f,
            ),
            // Halo — a glowing rim: the widest, brightest edge, top-lit, and an
            // accent-coloured glow under the play button instead of a shadow.
            "Halo" to PlayerGlassSettings(
                bodyOpacity = 0.2f, refraction = 0.35f, rimBrightness = 2f, dispersion = 0.6f,
                roundness = 1.6f, depth = 1f, reflection = 1.6f, gloss = 0.5f,
                surfaceMotion = 0.15f, tiltReactivity = 0.3f, lightAngleDeg = 90f, edgeWidth = 1f,
                frost = 0f, shadowDepth = 0.7f, shadowSoftness = 0.9f, shadowTint = 1f,
                hazeBlurDp = 40f, hazeTint = 1f,
            ),
            // Aurora — colour that lives in the rim: strong dispersion carried
            // by slow liquid motion and tilt, lit from the lower left.
            "Aurora" to PlayerGlassSettings(
                bodyOpacity = 0.15f, refraction = 0.38f, rimBrightness = 1.3f, dispersion = 1.2f,
                roundness = 1.9f, depth = 1.2f, reflection = 1.2f, gloss = 0.7f,
                surfaceMotion = 0.8f, tiltReactivity = 1.2f, lightAngleDeg = 200f, edgeWidth = 0.4f,
                frost = 0f, shadowDepth = 0.4f, shadowSoftness = 0.7f, shadowTint = 0.6f,
                hazeBlurDp = 40f, hazeTint = 0.9f,
            ),
            // Dusk — low raking light: the key light almost level from the
            // right, a warm accent glow under the play button, a slow drift.
            "Dusk" to PlayerGlassSettings(
                bodyOpacity = 0.25f, refraction = 0.36f, rimBrightness = 1.5f, dispersion = 0.4f,
                roundness = 1.7f, depth = 1.3f, reflection = 1.1f, gloss = 0.8f,
                surfaceMotion = 0.1f, tiltReactivity = 0.2f, lightAngleDeg = 20f, edgeWidth = 0.3f,
                frost = 0f, shadowDepth = 0.5f, shadowSoftness = 0.7f, shadowTint = 0.8f,
                hazeBlurDp = 44f, hazeTint = 1f,
            ),
            // Holo — a hologram: lit from below, near-total dispersion, and the
            // most tilt there is, so the rainbow slides as the phone moves.
            "Holo" to PlayerGlassSettings(
                bodyOpacity = 0.14f, refraction = 0.4f, rimBrightness = 1.6f, dispersion = 1.8f,
                roundness = 1.4f, depth = 1.5f, reflection = 1.3f, gloss = 0.6f,
                surfaceMotion = 0.3f, tiltReactivity = 1.5f, lightAngleDeg = 270f, edgeWidth = 0.6f,
                frost = 0f, shadowDepth = 0.3f, shadowSoftness = 0.5f, shadowTint = 0.9f,
                hazeBlurDp = 32f, hazeTint = 0.8f,
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
