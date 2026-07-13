package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

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

    companion object {
        val DEFAULT = PlayerGlassSettings()
    }
}
