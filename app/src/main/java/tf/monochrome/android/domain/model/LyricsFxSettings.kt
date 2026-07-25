package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full parameter set for the lyric renderer — typography, the 3D letter wave,
 * the bass beat engine, and the reactive glow. Every field is user-tunable
 * from the Player Visuals Studio (Settings › Interface) and persisted as one JSON
 * blob; defaults reproduce the shipped look exactly.
 */
@Serializable
data class LyricsFxSettings(
    // ── Typography ─────────────────────────────────────────────────────
    val fontSizeSp: Float = 23f,
    val letterSpacingSp: Float = -0.2f,
    /** Side margin (dp) between the lyric lines and the screen/container edges. */
    val edgeMarginDp: Float = 0f,
    /** Max rows a single line may wrap to before it shrinks to fit (1 = never wrap). */
    val maxWrapLines: Int = 3,

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

    // ── Anti-aliasing (FXAA post-process) ──────────────────────────────
    /** Post-process FXAA on the lyric surface to smooth jagged edges. Device/perf setting. */
    val fxaa: Boolean = false,
    /** How hard FXAA smooths edges (final blend of the AA result). Higher softens more. */
    val fxaaStrength: Float = 0.75f,

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

    // ── Glow (bass-reactive bloom behind the active line) ──────────────
    /** Extra glow radius beyond the line's own height, in dp. */
    val glowRadiusDp: Float = 44f,
    /** Peak glow alpha. */
    val glowBrightness: Float = 0.22f,
    /**
     * When on, the bass-reactive glow also blooms and pumps behind the album
     * cover art (not just behind the lyrics). ON by default. Personal
     * preference — a theme preset never changes it. Surfaced in Settings ›
     * Appearance (under Dynamic Colors), not in the Studio.
     */
    val glowBehindArt: Boolean = true,
) {
    /** Spring damping ratio for the bass-pulse spring, derived from [bounce]. */
    val springDampingRatio: Float
        get() = (0.9f - 0.72f * bounce.coerceIn(0f, 1f)).coerceIn(0.15f, 0.9f)

    /** Every field coerced into its slider range; non-finite values reset to default. */
    fun clamped(): LyricsFxSettings {
        val d = DEFAULT
        fun Float.c(min: Float, max: Float, def: Float) = if (isFinite()) coerceIn(min, max) else def
        return LyricsFxSettings(
            fontSizeSp = fontSizeSp.c(14f, 34f, d.fontSizeSp),
            letterSpacingSp = letterSpacingSp.c(-1f, 1f, d.letterSpacingSp),
            edgeMarginDp = edgeMarginDp.c(0f, 48f, d.edgeMarginDp),
            maxWrapLines = maxWrapLines.coerceIn(1, 3),
            bluetoothDelayMs = bluetoothDelayMs.c(-500f, 1500f, d.bluetoothDelayMs),
            customFont = customFont,
            customFontPath = customFontPath,
            liquidGlass = liquidGlass,
            glassBodyOpacity = glassBodyOpacity.c(0.2f, 1f, d.glassBodyOpacity),
            glassRefraction = glassRefraction.c(0f, 0.4f, d.glassRefraction),
            glassRimBrightness = glassRimBrightness.c(0f, 2f, d.glassRimBrightness),
            glassDispersion = glassDispersion.c(0f, 2f, d.glassDispersion),
            glassSampleRings = glassSampleRings.coerceIn(1, 3),
            fxaa = fxaa,
            fxaaStrength = fxaaStrength.c(0f, 1f, d.fxaaStrength),
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
            glowRadiusDp = glowRadiusDp.c(0f, 160f, d.glowRadiusDp),
            glowBrightness = glowBrightness.c(0f, 0.6f, d.glowBrightness),
            glowBehindArt = glowBehindArt,
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
        fxaa = other.fxaa,
        fxaaStrength = other.fxaaStrength,
        glowBehindArt = other.glowBehindArt,
    )

    /**
     * True when this settings object matches [preset] on every aesthetic field —
     * i.e. it equals the preset once the personal fields (which presets never
     * carry) are set aside. Used to light the selected preset chip.
     */
    fun matchesPreset(preset: LyricsFxSettings): Boolean = this == preset.withPersonalFrom(this)

    companion object {
        val DEFAULT = LyricsFxSettings()

        /**
         * Named starting points — one unified roster, paired 1:1 with the
         * Player Glass presets of the SAME NAME (PlayerGlassSettings.PRESETS)
         * so the lyrics and the transport chrome always compose one look. Each
         * theme claims a distinct region of the glass / wave / beat / glow
         * space: mirror metal, etched mist, electric strobes, arctic calm,
         * supernova bloom, brutalist flat, ghost text, a one-line ticker and a
         * fully still accessible mode. `glowBehindArt` is a personal toggle and
         * is intentionally left at its default in every preset.
         */
        val PRESETS: List<Pair<String, LyricsFxSettings>> = listOf(
            // The shipped look.
            "Default" to LyricsFxSettings(),
            // Chrome — crisp metallic type: bright tight rim over a solid body,
            // a firm short-travel wave, a controlled pump.
            "Chrome" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = -0.1f,
                glassBodyOpacity = 0.68f, glassRefraction = 0.12f, glassRimBrightness = 1.8f, glassDispersion = 0.5f,
                rotationDegrees = 8f, waveSpeed = 0.9f, wavePhaseStep = 0.2f, waveTravelDp = 2f, shadowDepth = 0.65f,
                bassReact = 0.6f, pumpAmount = 0.07f, attackMs = 10f, releaseMs = 130f, bounce = 0.45f,
                glowRadiusDp = 40f, glowBrightness = 0.2f,
            ),
            // Frosted — misted soft type: dull rim, airy tracking, slow easy
            // wave, a wide faint halo.
            "Frosted" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = 0.2f,
                glassBodyOpacity = 0.75f, glassRefraction = 0.1f, glassRimBrightness = 0.7f, glassDispersion = 0.6f,
                rotationDegrees = 5f, waveSpeed = 0.6f, wavePhaseStep = 0.15f, waveTravelDp = 2f, shadowDepth = 0.35f,
                bassReact = 0.4f, pumpAmount = 0.05f, attackMs = 20f, releaseMs = 260f, bounce = 0.4f,
                glowRadiusDp = 70f, glowBrightness = 0.18f,
            ),
            // Neon — electric sign: see-through tubes with a blazing fringed
            // rim, lively wave, a big saturated bloom.
            "Neon" to LyricsFxSettings(
                fontSizeSp = 25f, letterSpacingSp = 0.1f,
                glassBodyOpacity = 0.4f, glassRefraction = 0.24f, glassRimBrightness = 2f, glassDispersion = 1.7f,
                rotationDegrees = 13f, waveSpeed = 1.3f, wavePhaseStep = 0.3f, waveTravelDp = 4f, shadowDepth = 0.55f,
                bassReact = 0.9f, pumpAmount = 0.13f, attackMs = 8f, releaseMs = 120f, bounce = 0.8f,
                glowRadiusDp = 90f, glowBrightness = 0.42f,
            ),
            // Voltage — electric punch: fast choppy wave, hard snapping pump,
            // heavy lensing. (Name pinned by tests.)
            "Voltage" to LyricsFxSettings(
                fontSizeSp = 25f, letterSpacingSp = 0f,
                glassBodyOpacity = 0.45f, glassRefraction = 0.3f, glassRimBrightness = 1.9f, glassDispersion = 1.8f,
                rotationDegrees = 16f, waveSpeed = 1.8f, wavePhaseStep = 0.4f, waveTravelDp = 5f, shadowDepth = 0.6f,
                bassReact = 1f, pumpAmount = 0.16f, attackMs = 6f, releaseMs = 100f, bounce = 0.9f,
                glowRadiusDp = 64f, glowBrightness = 0.38f,
            ),
            // Glacier — arctic: ghost-thin body with a cold crisp rim, barely
            // any motion, the faintest glow.
            "Glacier" to LyricsFxSettings(
                fontSizeSp = 23f, letterSpacingSp = 0.15f,
                glassBodyOpacity = 0.3f, glassRefraction = 0.08f, glassRimBrightness = 1.5f, glassDispersion = 0.4f,
                rotationDegrees = 3f, waveSpeed = 0.55f, wavePhaseStep = 0.12f, waveTravelDp = 1.5f, shadowDepth = 0.4f,
                bassReact = 0.3f, pumpAmount = 0.03f, attackMs = 16f, releaseMs = 180f, bounce = 0.3f,
                glowRadiusDp = 24f, glowBrightness = 0.1f,
            ),
            // Bloom — dreamy soft focus: slow swaying wave, long lazy release,
            // a huge soft halo.
            "Bloom" to LyricsFxSettings(
                fontSizeSp = 24f,
                glassBodyOpacity = 0.5f, glassRefraction = 0.2f, glassRimBrightness = 1.2f, glassDispersion = 1.5f,
                rotationDegrees = 9f, waveSpeed = 0.65f, wavePhaseStep = 0.14f, waveTravelDp = 4f, shadowDepth = 0.5f,
                bassReact = 0.6f, pumpAmount = 0.08f, attackMs = 24f, releaseMs = 300f, bounce = 0.6f,
                glowRadiusDp = 120f, glowBrightness = 0.34f,
            ),
            // Midnight — noir cinema: dark slow glass, deep extrusion, a long
            // exhaling release, restrained glow.
            "Midnight" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = 0.1f,
                glassBodyOpacity = 0.4f, glassRefraction = 0.12f, glassRimBrightness = 0.9f, glassDispersion = 1.1f,
                rotationDegrees = 5f, waveSpeed = 0.5f, waveTravelDp = 3f, shadowDepth = 0.75f,
                bassReact = 0.4f, pumpAmount = 0.05f, releaseMs = 300f,
                glowRadiusDp = 54f, glowBrightness = 0.18f,
            ),
            // Silk — draped smoothness: gentle ribbon wave, the deepest soft
            // shadow, a large low-intensity glow.
            "Silk" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = 0.1f,
                glassBodyOpacity = 0.58f, glassRefraction = 0.14f, glassRimBrightness = 1f, glassDispersion = 0.7f,
                rotationDegrees = 7f, waveSpeed = 0.7f, wavePhaseStep = 0.16f, waveTravelDp = 3f, shadowDepth = 0.85f,
                bassReact = 0.5f, pumpAmount = 0.06f, attackMs = 18f, releaseMs = 240f, bounce = 0.5f,
                glowRadiusDp = 96f, glowBrightness = 0.22f,
            ),
            // Hyper — nightcore: the fastest wave, hardest snap, springiest
            // bounce, everything reactive.
            "Hyper" to LyricsFxSettings(
                fontSizeSp = 25f,
                glassBodyOpacity = 0.48f, glassRefraction = 0.24f, glassRimBrightness = 1.7f, glassDispersion = 1.6f,
                rotationDegrees = 19f, waveSpeed = 2.4f, wavePhaseStep = 0.38f, waveTravelDp = 5f, shadowDepth = 0.6f,
                bassReact = 1f, pumpAmount = 0.19f, attackMs = 5f, releaseMs = 85f, bounce = 0.95f,
                glowRadiusDp = 76f, glowBrightness = 0.36f,
            ),
            // Prism — maximal cut-glass: full refraction + full chromatic
            // dispersion, a glitchy choppy per-letter wave that bobs hard.
            "Prism" to LyricsFxSettings(
                fontSizeSp = 25f, letterSpacingSp = 0.1f,
                glassBodyOpacity = 0.45f, glassRefraction = 0.4f, glassRimBrightness = 1.8f, glassDispersion = 2f,
                rotationDegrees = 15f, waveSpeed = 1.3f, wavePhaseStep = 0.65f, waveTravelDp = 6f, shadowDepth = 0.6f,
                bassReact = 0.9f, pumpAmount = 0.15f, attackMs = 8f, releaseMs = 110f, bounce = 0.85f,
                glowRadiusDp = 66f, glowBrightness = 0.4f,
            ),
            // Mirage — heat-haze billboard: big airy tracking, flat letters (no
            // extrusion), a slow breathing swell, wide vapor glow.
            "Mirage" to LyricsFxSettings(
                fontSizeSp = 28f, letterSpacingSp = 0.6f,
                glassBodyOpacity = 0.55f, glassRefraction = 0.26f, glassRimBrightness = 1.3f, glassDispersion = 1.8f,
                rotationDegrees = 8f, waveSpeed = 0.5f, wavePhaseStep = 0.12f, waveTravelDp = 4f, shadowDepth = 0f,
                bassReact = 0.7f, pumpAmount = 0.1f, attackMs = 26f, releaseMs = 280f, bounce = 0.65f,
                glowRadiusDp = 124f, glowBrightness = 0.46f,
            ),
            // Aurora — sculptural slow-turning ribbon: the deepest 3D tilt, an
            // almost seamless per-letter phase, a soft supernova bloom.
            "Aurora" to LyricsFxSettings(
                fontSizeSp = 26f, letterSpacingSp = 0.4f,
                glassBodyOpacity = 0.5f, glassRefraction = 0.3f, glassRimBrightness = 1.45f, glassDispersion = 1.7f,
                rotationDegrees = 22f, waveSpeed = 0.55f, wavePhaseStep = 0.08f, waveTravelDp = 5f, shadowDepth = 0.5f,
                bassReact = 0.6f, pumpAmount = 0.08f, attackMs = 22f, releaseMs = 320f, bounce = 0.6f,
                glowRadiusDp = 150f, glowBrightness = 0.55f,
            ),
            // Onyx — matte brutalist: liquid glass OFF (flat solid text),
            // condensed tracking, a stiff no-overshoot mechanical pump.
            "Onyx" to LyricsFxSettings(
                fontSizeSp = 22f, letterSpacingSp = -0.4f,
                liquidGlass = false,
                rotationDegrees = 4f, waveSpeed = 0.7f, waveTravelDp = 1f, shadowDepth = 0.9f,
                bassReact = 0.5f, pumpAmount = 0.06f, attackMs = 14f, releaseMs = 120f, bounce = 0.1f,
                glowRadiusDp = 18f, glowBrightness = 0.08f,
            ),
            // Halo — ethereal ghost text: minimum body opacity (letters read as
            // a lensed edge), the brightest rim, the biggest fullest bloom.
            "Halo" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = 0.2f,
                glassBodyOpacity = 0.2f, glassRefraction = 0.22f, glassRimBrightness = 2f, glassDispersion = 1.4f,
                rotationDegrees = 12f, waveSpeed = 0.9f, wavePhaseStep = 0.18f, waveTravelDp = 4f, shadowDepth = 0.5f,
                bassReact = 0.6f, pumpAmount = 0.09f, attackMs = 18f, releaseMs = 240f, bounce = 0.7f,
                glowRadiusDp = 160f, glowBrightness = 0.6f,
            ),
            // Ticker — a single-line cinema strip: strict one row, wide side
            // inset, small spaced text, no wave, a tight staccato pulse.
            "Ticker" to LyricsFxSettings(
                maxWrapLines = 1,
                fontSizeSp = 20f, letterSpacingSp = 0.5f, edgeMarginDp = 40f,
                glassBodyOpacity = 0.85f, glassRefraction = 0.08f, glassRimBrightness = 1.2f, glassDispersion = 0.6f,
                rotationDegrees = 0f, waveTravelDp = 0f, shadowDepth = 0.4f,
                bassReact = 0.4f, pumpAmount = 0.05f, attackMs = 12f, releaseMs = 70f, bounce = 0.2f,
                glowRadiusDp = 24f, glowBrightness = 0.12f,
            ),
            // Static — no motion, no pump, no glow (accessible / minimal), with
            // the glass relight itself kept on.
            "Static" to LyricsFxSettings(
                fontSizeSp = 23f,
                glassBodyOpacity = 0.55f, glassRefraction = 0.12f, glassRimBrightness = 1.1f, glassDispersion = 0.8f,
                rotationDegrees = 0f,
                bassReact = 0f,
                glowRadiusDp = 0f, glowBrightness = 0f,
            ),
        )
    }
}

/**
 * A user-saved Lyrics FX preset: a name plus a full [LyricsFxSettings] snapshot.
 * Serialises to a compact, shareable code (see [encode]/[decode]) so presets can
 * be exported to a friend and imported back — the whole look travels in one
 * copy-pasteable string.
 */
@Serializable
data class LyricsFxPreset(
    val name: String,
    val settings: LyricsFxSettings,
) {
    companion object {
        /** Marker so an imported blob is recognisably one of our preset codes. */
        const val CODE_PREFIX = "TRYPTFX1:"

        private val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** Encode a preset to a single shareable string (prefix + compact JSON). */
        fun encode(preset: LyricsFxPreset): String =
            CODE_PREFIX + codec.encodeToString(preset.copy(settings = preset.settings.clamped()))

        /**
         * Decode a shared code back to a preset, tolerating the prefix being
         * present or not and any surrounding whitespace. Returns null if the
         * text isn't a valid preset. The settings are re-clamped on the way in
         * so a hand-edited or hostile code can't push values out of range.
         */
        fun decode(code: String): LyricsFxPreset? = runCatching {
            val trimmed = code.trim()
            val start = trimmed.indexOf('{')
            // No JSON object present → not a preset code. Throwing here is caught
            // by runCatching and surfaces as null (a plain `return` isn't allowed
            // from an expression-body function).
            require(start >= 0) { "no preset payload" }
            val jsonBody = trimmed.substring(start)
            codec.decodeFromString<LyricsFxPreset>(jsonBody).let {
                it.copy(name = it.name.trim().ifBlank { "Imported" }, settings = it.settings.clamped())
            }
        }.getOrNull()
    }
}
