package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * Full parameter set for the lyric renderer — typography, the 3D letter wave,
 * the bass beat engine, and the god-ray light. Every field is user-tunable
 * from the Lyrics FX Studio (Settings › Interface) and persisted as one JSON
 * blob; defaults reproduce the shipped look exactly.
 */
@Serializable
data class LyricsFxSettings(
    // ── Typography ─────────────────────────────────────────────────────
    val fontSizeSp: Float = 23f,
    val letterSpacingSp: Float = -0.2f,

    // ── Playback sync ──────────────────────────────────────────────────
    /**
     * Bluetooth sync delay in milliseconds. Bluetooth audio reaches the ears
     * later than the reported playback position, so synced lyrics run ahead of
     * what's heard; this pushes the lyric timeline back by the same amount.
     * Positive = lyrics wait longer (the usual case); negative = lyrics lead.
     * Personal/device setting — preserved when a theme preset is applied.
     */
    val bluetoothDelayMs: Float = 0f,

    // ── Custom font ────────────────────────────────────────────────────
    /** Override the lyric typeface with an imported font. Off = app theme font. */
    val customFont: Boolean = false,
    /**
     * Absolute path to the chosen font file in filesDir/custom_fonts (the same
     * store Settings › Appearance imports into). Blank = none. Personal setting —
     * preserved when a theme preset is applied.
     */
    val customFontPath: String = "",

    // ── Liquid glass (refractive relight of the lyric surface) ─────────
    /** Master toggle for the refractive glass relight. Off = flat solid text. */
    val liquidGlass: Boolean = true,
    /** Glass body opacity — lower lets more of the backdrop read through the letters. */
    val glassBodyOpacity: Float = 0.62f,
    /** Refraction strength — how hard the beveled edges lens the backdrop behind them. */
    val glassRefraction: Float = 0.14f,
    /** Rim highlight gain — brightness of the specular glass edge. */
    val glassRimBrightness: Float = 1f,
    /** Chromatic aberration — colour fringing where the edges refract. */
    val glassDispersion: Float = 1f,
    /**
     * Bevel sample rings the glass shader takes per pixel: 1/2/3 → 5/9/13 taps.
     * Higher = smoother rounded glass but heavier GPU. Device/perf setting —
     * preserved when a theme preset is applied.
     */
    val glassSampleRings: Int = 2,

    // ── 3D letter wave (active line) ───────────────────────────────────
    /** Tilt of the per-letter ripple. 0 disables the per-letter path entirely. */
    val rotationDegrees: Float = 12f,
    val waveSpeed: Float = 1f,
    /** Radians of wave phase per letter — small = one smooth ribbon, large = choppy. */
    val wavePhaseStep: Float = 0.22f,
    /** Vertical travel of each letter riding the wave, in dp. */
    val waveTravelDp: Float = 3f,
    val shadowDepth: Float = 0.7f,

    // ── Beat engine (bass → pulse) ─────────────────────────────────────
    /** Master intensity. 0 disables the whole audio-reactive path. */
    val bassReact: Float = 0.8f,
    /** How much the active line swells on a kick (fraction of its size). */
    val pumpAmount: Float = 0.08f,
    /** Envelope attack — how fast the pulse snaps onto a kick. */
    val attackMs: Float = 12f,
    /** Envelope release — how long the pulse holds through a kick. */
    val releaseMs: Float = 150f,
    /** 0 = stiff (no overshoot) … 1 = rubbery (rings visibly). */
    val bounce: Float = 0.7f,
    /** Pop-in size swing when a new line activates (fraction of its size). */
    val popAmount: Float = 0.08f,

    // ── God rays / glow ────────────────────────────────────────────────
    /** Beams radiating from EACH letter. 0 turns rays off. */
    val rayCount: Int = 8,
    /** Beam reach as a multiple of each glyph's size (grows with the pulse). */
    val rayLength: Float = 0.62f,
    /** Beam stroke width at full pulse, in dp. */
    val rayWidthDp: Float = 9f,
    /** Peak beam alpha. */
    val rayBrightness: Float = 0.26f,
    /** Orbit speed of the beam fan, degrees per second (negative = reverse). */
    val raySpinDegPerSec: Float = 10f,
    /** Extra glow radius beyond the line's own height, in dp. */
    val glowRadiusDp: Float = 44f,
    /** Peak glow alpha. */
    val glowBrightness: Float = 0.22f,
    /** Off = radial burst; on = parallel directional shafts all aimed one way. */
    val rayFixedDirection: Boolean = false,
    /** Light direction in degrees, 0 = up, clockwise (set by the studio joystick). */
    val rayAngleDeg: Float = 0f,
    /** Fan cone width around the angle. 360 = full even burst; small = tight cone. */
    val raySpreadDeg: Float = 360f,
    /** Falloff position along each beam where it fades out (0 = short, 1 = reaches the tip). */
    val rayDecay: Float = 0.5f,
    /** Width taper from base to tip. 0 = even shaft, 1 = sharp spear. */
    val rayTaper: Float = 0f,
    /** Recolour the rays off the album accent, in degrees of hue rotation. */
    val rayHueShift: Float = 0f,
    /** Time-based shimmer/twinkle of each beam's alpha. 0 = steady. */
    val rayFlicker: Float = 0f,
    /** Per-beam random length variance for an organic burst. 0 = uniform. */
    val rayLengthJitter: Float = 0f,
    /** How much beam reach/width react to the bass pulse. 0 = steady, 1 = fully bass-driven. */
    val rayPulseAmount: Float = 0.5f,
) {
    /** Spring damping ratio for the pulse/pop springs, derived from [bounce]. */
    val springDampingRatio: Float
        get() = (0.9f - 0.72f * bounce.coerceIn(0f, 1f)).coerceIn(0.15f, 0.9f)

    /** Every field coerced into its slider range; non-finite values reset to default. */
    fun clamped(): LyricsFxSettings {
        val d = DEFAULT
        fun Float.c(min: Float, max: Float, def: Float) = if (isFinite()) coerceIn(min, max) else def
        return LyricsFxSettings(
            fontSizeSp = fontSizeSp.c(14f, 34f, d.fontSizeSp),
            letterSpacingSp = letterSpacingSp.c(-1f, 1f, d.letterSpacingSp),
            bluetoothDelayMs = bluetoothDelayMs.c(-500f, 1500f, d.bluetoothDelayMs),
            customFont = customFont,
            customFontPath = customFontPath,
            liquidGlass = liquidGlass,
            glassBodyOpacity = glassBodyOpacity.c(0.2f, 1f, d.glassBodyOpacity),
            glassRefraction = glassRefraction.c(0f, 0.4f, d.glassRefraction),
            glassRimBrightness = glassRimBrightness.c(0f, 2f, d.glassRimBrightness),
            glassDispersion = glassDispersion.c(0f, 2f, d.glassDispersion),
            glassSampleRings = glassSampleRings.coerceIn(1, 3),
            rotationDegrees = rotationDegrees.c(0f, 25f, d.rotationDegrees),
            waveSpeed = waveSpeed.c(0.25f, 3f, d.waveSpeed),
            wavePhaseStep = wavePhaseStep.c(0.05f, 0.9f, d.wavePhaseStep),
            waveTravelDp = waveTravelDp.c(0f, 8f, d.waveTravelDp),
            shadowDepth = shadowDepth.c(0f, 1f, d.shadowDepth),
            bassReact = bassReact.c(0f, 1f, d.bassReact),
            pumpAmount = pumpAmount.c(0f, 0.25f, d.pumpAmount),
            attackMs = attackMs.c(4f, 60f, d.attackMs),
            releaseMs = releaseMs.c(40f, 500f, d.releaseMs),
            bounce = bounce.c(0f, 1f, d.bounce),
            popAmount = popAmount.c(0f, 0.2f, d.popAmount),
            rayCount = rayCount.coerceIn(0, 24),
            rayLength = rayLength.c(0.1f, 1f, d.rayLength),
            rayWidthDp = rayWidthDp.c(1f, 16f, d.rayWidthDp),
            rayBrightness = rayBrightness.c(0f, 0.6f, d.rayBrightness),
            raySpinDegPerSec = raySpinDegPerSec.c(-60f, 60f, d.raySpinDegPerSec),
            glowRadiusDp = glowRadiusDp.c(0f, 160f, d.glowRadiusDp),
            glowBrightness = glowBrightness.c(0f, 0.6f, d.glowBrightness),
            rayFixedDirection = rayFixedDirection,
            rayAngleDeg = rayAngleDeg.c(0f, 360f, d.rayAngleDeg),
            raySpreadDeg = raySpreadDeg.c(0f, 360f, d.raySpreadDeg),
            rayDecay = rayDecay.c(0f, 1f, d.rayDecay),
            rayTaper = rayTaper.c(0f, 1f, d.rayTaper),
            rayHueShift = rayHueShift.c(-180f, 180f, d.rayHueShift),
            rayFlicker = rayFlicker.c(0f, 1f, d.rayFlicker),
            rayLengthJitter = rayLengthJitter.c(0f, 1f, d.rayLengthJitter),
            rayPulseAmount = rayPulseAmount.c(0f, 1f, d.rayPulseAmount),
        )
    }

    /**
     * The user's personal/device settings — the lyric font and the perf/latency
     * knobs — that a theme preset should carry over rather than overwrite.
     */
    fun withPersonalFrom(other: LyricsFxSettings): LyricsFxSettings = copy(
        customFont = other.customFont,
        customFontPath = other.customFontPath,
        bluetoothDelayMs = other.bluetoothDelayMs,
        glassSampleRings = other.glassSampleRings,
    )

    /**
     * True when this settings object matches [preset] on every aesthetic field —
     * i.e. it equals the preset once the personal fields (which presets never
     * carry) are set aside. Used to light the selected preset chip.
     */
    fun matchesPreset(preset: LyricsFxSettings): Boolean = this == preset.withPersonalFrom(this)

    companion object {
        val DEFAULT = LyricsFxSettings()

        /** Named starting points, vizzy-style. */
        val PRESETS: List<Pair<String, LyricsFxSettings>> = listOf(
            "Default" to DEFAULT,
            "Nightcore" to LyricsFxSettings(
                fontSizeSp = 25f,
                rotationDegrees = 16f,
                waveSpeed = 1.4f,
                bassReact = 1f,
                pumpAmount = 0.14f,
                bounce = 0.85f,
                popAmount = 0.12f,
                rayCount = 12,
                rayLength = 0.85f,
                rayWidthDp = 11f,
                rayBrightness = 0.34f,
                raySpinDegPerSec = 16f,
                glowRadiusDp = 70f,
                glowBrightness = 0.3f,
            ),
            "Subtle" to LyricsFxSettings(
                rotationDegrees = 6f,
                waveSpeed = 0.7f,
                shadowDepth = 0.5f,
                bassReact = 0.5f,
                pumpAmount = 0.04f,
                bounce = 0.4f,
                popAmount = 0.04f,
                rayCount = 4,
                rayLength = 0.35f,
                rayBrightness = 0.14f,
                glowRadiusDp = 30f,
                glowBrightness = 0.12f,
            ),
            "Still" to LyricsFxSettings(
                rotationDegrees = 0f,
                bassReact = 0f,
                rayCount = 0,
                popAmount = 0f,
            ),
            "Aurora" to LyricsFxSettings(
                fontSizeSp = 24f,
                glassBodyOpacity = 0.5f, glassRefraction = 0.2f, glassRimBrightness = 1.2f, glassDispersion = 1.4f,
                rotationDegrees = 8f, waveSpeed = 0.8f, waveTravelDp = 4f,
                bassReact = 0.6f, pumpAmount = 0.06f,
                rayCount = 10, rayLength = 0.7f, rayWidthDp = 7f, rayBrightness = 0.2f, raySpinDegPerSec = 6f,
                glowRadiusDp = 80f, glowBrightness = 0.28f,
                rayFixedDirection = true, rayAngleDeg = 0f, rayDecay = 0.7f, rayTaper = 0.4f,
                rayHueShift = 120f, rayFlicker = 0.2f, rayPulseAmount = 0.4f,
            ),
            "Neon" to LyricsFxSettings(
                fontSizeSp = 25f, letterSpacingSp = 0f,
                glassBodyOpacity = 0.45f, glassRefraction = 0.28f, glassRimBrightness = 1.8f, glassDispersion = 2f,
                rotationDegrees = 14f, waveSpeed = 1.6f, wavePhaseStep = 0.3f,
                bassReact = 1f, pumpAmount = 0.14f, bounce = 0.8f, popAmount = 0.12f,
                rayCount = 16, rayLength = 0.9f, rayWidthDp = 6f, rayBrightness = 0.4f, raySpinDegPerSec = 30f,
                glowRadiusDp = 60f, glowBrightness = 0.34f,
                rayDecay = 0.6f, rayHueShift = -60f, rayFlicker = 0.4f, rayPulseAmount = 0.9f,
            ),
            "Frost" to LyricsFxSettings(
                fontSizeSp = 23f,
                glassBodyOpacity = 0.3f, glassRefraction = 0.08f, glassRimBrightness = 1.4f, glassDispersion = 0.6f,
                rotationDegrees = 4f, waveSpeed = 0.6f, shadowDepth = 0.4f,
                bassReact = 0.3f, pumpAmount = 0.03f,
                rayCount = 0,
                glowRadiusDp = 24f, glowBrightness = 0.1f,
            ),
            "Ember" to LyricsFxSettings(
                fontSizeSp = 24f,
                glassBodyOpacity = 0.55f, glassRefraction = 0.16f, glassRimBrightness = 1.1f, glassDispersion = 0.8f,
                rotationDegrees = 10f, waveSpeed = 0.9f, waveTravelDp = 3f,
                bassReact = 0.7f, pumpAmount = 0.1f, releaseMs = 220f,
                rayCount = 8, rayLength = 0.75f, rayWidthDp = 9f, rayBrightness = 0.3f,
                glowRadiusDp = 70f, glowBrightness = 0.3f,
                rayFixedDirection = true, rayAngleDeg = 180f, rayDecay = 0.6f, rayTaper = 0.3f,
                rayHueShift = -20f, rayFlicker = 0.3f, rayLengthJitter = 0.3f, rayPulseAmount = 0.6f,
            ),
            "Cinematic" to LyricsFxSettings(
                fontSizeSp = 30f, letterSpacingSp = 0.2f,
                glassBodyOpacity = 0.6f, glassRefraction = 0.12f, glassRimBrightness = 0.9f, glassDispersion = 0.5f,
                rotationDegrees = 5f, waveSpeed = 0.5f, waveTravelDp = 2f, shadowDepth = 0.6f,
                bassReact = 0.4f, pumpAmount = 0.05f,
                rayCount = 6, rayLength = 0.6f, rayWidthDp = 10f, rayBrightness = 0.18f,
                glowRadiusDp = 50f, glowBrightness = 0.18f,
                rayFixedDirection = true, rayAngleDeg = 90f, rayDecay = 0.5f, rayTaper = 0.5f, rayPulseAmount = 0.3f,
            ),
            "Vaporwave" to LyricsFxSettings(
                fontSizeSp = 26f, letterSpacingSp = 0.3f,
                glassBodyOpacity = 0.5f, glassRefraction = 0.24f, glassRimBrightness = 1.5f, glassDispersion = 2f,
                rotationDegrees = 12f, waveSpeed = 1.1f,
                bassReact = 0.8f, pumpAmount = 0.12f, bounce = 0.7f,
                rayCount = 12, rayLength = 0.8f, rayWidthDp = 8f, rayBrightness = 0.32f, raySpinDegPerSec = -12f,
                glowRadiusDp = 100f, glowBrightness = 0.4f,
                rayDecay = 0.7f, rayHueShift = 150f, rayFlicker = 0.25f, rayPulseAmount = 0.7f,
            ),
            "Minimal Glass" to LyricsFxSettings(
                fontSizeSp = 23f,
                glassBodyOpacity = 0.55f, glassRefraction = 0.14f, glassRimBrightness = 1.2f, glassDispersion = 1f,
                rotationDegrees = 0f,
                bassReact = 0f,
                rayCount = 0, popAmount = 0f,
                glowRadiusDp = 0f, glowBrightness = 0f,
            ),
            "Karaoke Pop" to LyricsFxSettings(
                fontSizeSp = 27f,
                glassBodyOpacity = 0.6f, glassRefraction = 0.16f, glassRimBrightness = 1.3f, glassDispersion = 1.2f,
                rotationDegrees = 12f, waveSpeed = 1.2f,
                bassReact = 1f, pumpAmount = 0.18f, attackMs = 8f, releaseMs = 120f, bounce = 0.9f, popAmount = 0.16f,
                rayCount = 14, rayLength = 0.85f, rayWidthDp = 10f, rayBrightness = 0.38f, raySpinDegPerSec = 20f,
                glowRadiusDp = 80f, glowBrightness = 0.34f,
                rayDecay = 0.6f, rayFlicker = 0.3f, rayLengthJitter = 0.4f, rayPulseAmount = 1f,
            ),
            "Sunbeam" to LyricsFxSettings(
                fontSizeSp = 24f,
                glassBodyOpacity = 0.5f, glassRefraction = 0.18f, glassRimBrightness = 1.2f, glassDispersion = 0.9f,
                rotationDegrees = 8f, waveSpeed = 0.8f,
                bassReact = 0.6f, pumpAmount = 0.08f,
                rayCount = 10, rayLength = 0.95f, rayWidthDp = 8f, rayBrightness = 0.3f,
                glowRadiusDp = 70f, glowBrightness = 0.3f,
                rayFixedDirection = true, rayAngleDeg = 45f, raySpreadDeg = 40f, rayDecay = 0.8f, rayTaper = 0.6f,
                rayHueShift = 30f, rayFlicker = 0.15f, rayLengthJitter = 0.5f, rayPulseAmount = 0.5f,
            ),
            "Nightdrive" to LyricsFxSettings(
                fontSizeSp = 24f,
                glassBodyOpacity = 0.4f, glassRefraction = 0.12f, glassRimBrightness = 1f, glassDispersion = 1.3f,
                rotationDegrees = 6f, waveSpeed = 0.5f, waveTravelDp = 3f,
                bassReact = 0.4f, pumpAmount = 0.05f, releaseMs = 300f,
                rayCount = 8, rayLength = 0.6f, rayWidthDp = 6f, rayBrightness = 0.16f, raySpinDegPerSec = 4f,
                glowRadiusDp = 60f, glowBrightness = 0.2f,
                rayDecay = 0.5f, rayHueShift = -120f, rayFlicker = 0.15f, rayPulseAmount = 0.4f,
            ),
        )
    }
}
