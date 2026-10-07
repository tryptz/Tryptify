package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Full parameter set for the lyric renderer — typography, the liquid-glass
 * letters, the 3D letter wave, the bass beat engine, the reactive glow and the
 * god rays. Every field is user-tunable
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

    // ── Liquid glass optics — the Player Glass material, on the letters ─
    // The lyric shader used to pin these uniforms; each default is the value
    // it was pinned to, so an upgrade does not move a pixel. Same meaning and
    // range as the PlayerGlassSettings field of the same name.
    /** Bevel shoulder width: 1 = neutral, higher = rounder, softer letter edges. */
    val glassRoundness: Float = 1f,
    /** Bevel relief: 1 = neutral, higher = steeper, deeper 3D letters. */
    val glassDepth: Float = 1f,
    /** Living-liquid undulation of the letter surface: 0 = still glass. */
    val glassSurfaceMotion: Float = 1f,
    /** Environment ("room") reflection strength on the letters. */
    val glassReflection: Float = 1f,
    /** Highlight polish, 0 = soft wide glint .. 1 = tight mirror. 0.2917 is the old fixed exponent of 90. */
    val glassGloss: Float = 0.29166666f,
    /** How strongly device tilt moves the light and reflection on the letters. */
    val glassTiltReactivity: Float = 0.7f,
    /** Key-light direction in degrees — where the glint sits on each letter. */
    val glassLightAngleDeg: Float = 135f,
    /** Reflective rim width: 0 = thin crisp edge, 1 = broad glassy shoulder. */
    val glassEdgeWidth: Float = 0.5f,
    /** Frosted surface grain: 0 = clear letters, 1 = sea glass. */
    val glassFrost: Float = 0f,
    /**
     * How much the glass letters catch the god rays' light: a glint and a lit
     * bevel on the edges facing the rays' source, and with a backlight, rims
     * glowing with the light behind them. Acts only while [godRays] is on, so
     * it changes nothing for anyone without them.
     */
    val glassRayCatch: Float = 0.7f,
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
    /**
     * The soft shadow the lyrics cast on the background under them, falling
     * away from the light: 0 = none, 1 = darkest and softest. On every line
     * and whether or not the wave is on; it used to be a blocky extrusion on
     * the sung line's letters (see lyricShadow).
     */
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
    /**
     * Album-cover bloom radius, in dp. `null` follows [glowRadiusDp] — what the
     * cover glow used before it had its own knob — so an upgrade changes
     * nothing and the slider opens on the glow already on screen. Setting it
     * pins the cover glow. Personal, like the [glowBehindArt] that gates it.
     */
    val artGlowRadiusDp: Float? = null,
    /** Album-cover bloom peak alpha. `null` follows [glowBrightness]. */
    val artGlowBrightness: Float? = null,

    // ── God rays (volumetric light scattering through the sung line) ───
    // The screen-space light-scattering post-process from GPU Gems 3 ch. 13,
    // as Community Play 3D's "God rays, what's that?" and the Shadertoy
    // "Crepuscular light" (ls2Xzd) build it: every pixel marches toward the
    // light's on-screen position and gathers what it passes. Off by default,
    // so the shipped look is unchanged.
    /** Master toggle for the light shafts. */
    val godRays: Boolean = false,
    /** What shines: [GOD_RAYS_LETTERS] (the sung line streams light) or [GOD_RAYS_BACKLIGHT] (a light behind the lyrics, the letters cast shadow shafts). */
    val godRaySource: Int = 0,
    /**
     * Where the shafts draw: false = under the lyrics (the letters sit crisp
     * in front of their own light), true = on top (the light is added over
     * the letters too — the article's additive composite, hazier and hotter).
     */
    val godRaysOnTop: Boolean = false,
    /**
     * With word-timed (karaoke) lyrics, only the word being sung shines and the
     * light hops from word to word as it is sung. Line-timed lyrics have no
     * word to follow and keep the whole line.
     */
    val godRaysFollowWord: Boolean = false,
    /**
     * Every lyric shines, not only what is being sung — the Shadertoy's whole
     * image as the light (its `iChannel0`). Overrides [godRaysFollowWord], and
     * drops the dimming that keeps the sung line readable under its own light.
     */
    val godRaysAllLyrics: Boolean = false,
    /** Brightness of the shafts (the article's exposure). */
    val godRayExposure: Float = 0.6f,
    /** How far toward the light each pixel gathers — the shaft length (the article's density). */
    val godRayDensity: Float = 0.85f,
    /** Light kept per 1/50 of a shaft's length (the article's decay): 1 = no falloff. */
    val godRayDecay: Float = 0.95f,
    /** Where the light comes from around the screen, in degrees: 0 = right, 90 = above. */
    val godRayAzimuthDeg: Float = 90f,
    /** How far the light is raised out of the screen toward you: 0 = raking from the side, 90 = straight behind the line. */
    val godRayElevationDeg: Float = 60f,
    /** Backlight disc radius, as a fraction of the window's short side. */
    val godRaySunSize: Float = 0.08f,
    /** Dust in the shafts: slow flicker across neighbouring rays. 0 = clean beams. */
    val godRayShimmer: Float = 0.35f,
    /** How much a kick brightens and lengthens the shafts (needs the beat engine on). */
    val godRayBeat: Float = 0.5f,
    /** The light orbiting the line, degrees per second; the sign picks the direction. */
    val godRaySpinDps: Float = 0f,
    /**
     * The light wanders over the lyrics on the Shadertoy's own path,
     * `(sin(t), sin(0.913·t))`, out to half the window's short side at 1.
     */
    val godRaySway: Float = 0f,
    /** How strongly tilting the phone swings the light. */
    val godRayTilt: Float = 0f,
    /**
     * Samples per pixel: 1/2/3/4 → 16/24/32/50, 50 being the Shadertoy's
     * own count. Higher = cleaner shafts, heavier
     * GPU. Device/perf setting, like [glassSampleRings] — preserved when a
     * theme preset is applied.
     */
    val godRayQuality: Int = 2,
) {
    /** Spring damping ratio for the bass-pulse spring, derived from [bounce]. */
    val springDampingRatio: Float
        get() = (0.9f - 0.72f * bounce.coerceIn(0f, 1f)).coerceIn(0.15f, 0.9f)

    /** The cover bloom's radius: its own if pinned, else the lyric glow's. */
    val effectiveArtGlowRadiusDp: Float get() = artGlowRadiusDp ?: glowRadiusDp

    /** The cover bloom's peak alpha: its own if pinned, else the lyric glow's. */
    val effectiveArtGlowBrightness: Float get() = artGlowBrightness ?: glowBrightness

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
            glassRoundness = glassRoundness.c(0.5f, 2f, d.glassRoundness),
            glassDepth = glassDepth.c(0.5f, 2f, d.glassDepth),
            glassSurfaceMotion = glassSurfaceMotion.c(0f, 1f, d.glassSurfaceMotion),
            glassReflection = glassReflection.c(0f, 2f, d.glassReflection),
            glassGloss = glassGloss.c(0f, 1f, d.glassGloss),
            glassTiltReactivity = glassTiltReactivity.c(0f, 1.5f, d.glassTiltReactivity),
            glassLightAngleDeg = glassLightAngleDeg.c(0f, 360f, d.glassLightAngleDeg),
            glassEdgeWidth = glassEdgeWidth.c(0f, 1f, d.glassEdgeWidth),
            glassFrost = glassFrost.c(0f, 1f, d.glassFrost),
            glassRayCatch = glassRayCatch.c(0f, 1f, d.glassRayCatch),
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
            // Only when pinned: coercing null would turn "follow" into 0.
            artGlowRadiusDp = artGlowRadiusDp?.c(0f, 160f, d.glowRadiusDp),
            artGlowBrightness = artGlowBrightness?.c(0f, 0.6f, d.glowBrightness),
            godRays = godRays,
            godRaySource = godRaySource.coerceIn(0, 1),
            godRaysOnTop = godRaysOnTop,
            godRaysFollowWord = godRaysFollowWord,
            godRaysAllLyrics = godRaysAllLyrics,
            godRayExposure = godRayExposure.c(0f, 1.5f, d.godRayExposure),
            godRayDensity = godRayDensity.c(0.2f, 1f, d.godRayDensity),
            godRayDecay = godRayDecay.c(0.85f, 1f, d.godRayDecay),
            godRayAzimuthDeg = godRayAzimuthDeg.c(0f, 360f, d.godRayAzimuthDeg),
            godRayElevationDeg = godRayElevationDeg.c(0f, 90f, d.godRayElevationDeg),
            godRaySunSize = godRaySunSize.c(0.03f, 0.3f, d.godRaySunSize),
            godRayShimmer = godRayShimmer.c(0f, 1f, d.godRayShimmer),
            godRayBeat = godRayBeat.c(0f, 1f, d.godRayBeat),
            godRaySpinDps = godRaySpinDps.c(-45f, 45f, d.godRaySpinDps),
            godRaySway = godRaySway.c(0f, 1f, d.godRaySway),
            godRayTilt = godRayTilt.c(0f, 1.5f, d.godRayTilt),
            godRayQuality = godRayQuality.coerceIn(1, 4),
        )
    }

    /**
     * This look with its letter glass made of the same material as [glass] —
     * the Studio's "Match player glass". Copies the optics the two shaders
     * share and switches the lyric glass on.
     *
     * Body opacity stays the lyrics' own. A pane is meant to be nearly clear
     * (Clear's body is 0.16) and a letter is not: it has to stay readable over
     * the artwork, which is why the lyric body is floored at 0.2 and ships at
     * 0.62. `frost` is copied because it is the same shader grain on both; the
     * pane's backdrop blur is not, since the letters have no pane behind them.
     */
    fun withGlassOpticsFrom(glass: PlayerGlassSettings): LyricsFxSettings = copy(
        liquidGlass = true,
        glassRefraction = glass.refraction,
        glassRimBrightness = glass.rimBrightness,
        glassDispersion = glass.dispersion,
        glassRoundness = glass.roundness,
        glassDepth = glass.depth,
        glassSurfaceMotion = glass.surfaceMotion,
        glassReflection = glass.reflection,
        glassGloss = glass.gloss,
        glassTiltReactivity = glass.tiltReactivity,
        glassLightAngleDeg = glass.lightAngleDeg,
        glassEdgeWidth = glass.edgeWidth,
        glassFrost = glass.frost,
    ).clamped()

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
        artGlowRadiusDp = other.artGlowRadiusDp,
        artGlowBrightness = other.artGlowBrightness,
        godRayQuality = other.godRayQuality,
    )

    /**
     * True when this settings object matches [preset] on every aesthetic field —
     * i.e. it equals the preset once the personal fields (which presets never
     * carry) are set aside. Used to light the selected preset chip.
     */
    fun matchesPreset(preset: LyricsFxSettings): Boolean = this == preset.withPersonalFrom(this)

    companion object {
        val DEFAULT = LyricsFxSettings()

        /** [godRaySource]: the sung line itself shines, and its light streams out of the letters. */
        const val GOD_RAYS_LETTERS = 0

        /** [godRaySource]: a light behind the lyrics; the letters block it and cast shadow shafts. */
        const val GOD_RAYS_BACKLIGHT = 1

        /**
         * Named starting points. These were paired 1:1 by name with the
         * Player Glass presets; the glass roster has since been rebuilt as an
         * iOS Liquid Glass set and no longer mirrors these names. Each
         * theme claims a distinct region of the glass / wave / beat / glow
         * space: mirror metal, etched mist, electric strobes, arctic calm,
         * supernova bloom, brutalist flat, ghost text, a one-line ticker and a
         * fully still accessible mode. `glowBehindArt` and the two album-glow
         * knobs it gates are personal and are intentionally left at their
         * defaults in every preset.
         *
         * The last nine came with the letter-glass optics and the god rays:
         * seven light the lyrics (from behind the line, from above, a hidden
         * backlight, a low sun, a raking searchlight, the sung word alone, and
         * the Shadertoy they come from, as written) and
         * two are made of the Player Glass material (liquid metal, frosted sea
         * glass). Every preset
         * before them leaves both at their defaults, which is exactly what the
         * lyrics looked like before either existed — so a listener on one of
         * those chips sees no change and keeps the chip lit.
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
            // Sunburst — the light sits straight behind the sung line (90°),
            // so its shafts burst out of the letters in every direction and
            // flare on the kick. The halo is pulled in so the two don't stack.
            "Sunburst" to LyricsFxSettings(
                fontSizeSp = 24f,
                glassBodyOpacity = 0.66f, glassRefraction = 0.16f, glassRimBrightness = 1.3f, glassDispersion = 1.1f,
                rotationDegrees = 10f, waveSpeed = 0.9f, waveTravelDp = 3f, shadowDepth = 0.55f,
                bassReact = 0.85f, pumpAmount = 0.09f, attackMs = 10f, releaseMs = 160f, bounce = 0.65f,
                glowRadiusDp = 30f, glowBrightness = 0.14f,
                godRays = true, godRaySource = GOD_RAYS_LETTERS,
                godRayExposure = 0.6f, godRayDensity = 0.85f, godRayDecay = 0.95f,
                godRayAzimuthDeg = 90f, godRayElevationDeg = 90f,
                godRayShimmer = 0.4f, godRayBeat = 0.7f,
            ),
            // Cathedral — light from high above (60°), so the line throws a
            // perspective fan of shafts down the screen, with dust in them and
            // a slow, reverent wave.
            "Cathedral" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = 0.1f,
                glassBodyOpacity = 0.6f, glassRefraction = 0.18f, glassRimBrightness = 1.4f, glassDispersion = 0.8f,
                glassReflection = 1.3f, glassGloss = 0.6f, glassLightAngleDeg = 90f,
                rotationDegrees = 6f, waveSpeed = 0.6f, wavePhaseStep = 0.15f, waveTravelDp = 2.5f, shadowDepth = 0.5f,
                bassReact = 0.5f, pumpAmount = 0.05f, attackMs = 20f, releaseMs = 260f, bounce = 0.5f,
                glowRadiusDp = 24f, glowBrightness = 0.1f,
                godRays = true, godRaySource = GOD_RAYS_LETTERS,
                godRayExposure = 0.65f, godRayDensity = 0.9f, godRayDecay = 0.96f,
                godRayAzimuthDeg = 90f, godRayElevationDeg = 60f,
                godRayShimmer = 0.5f, godRayBeat = 0.4f,
            ),
            // Eclipse — a light hidden directly behind the sung line: a corona
            // all round it, and every letter's shadow streaming out through it.
            // Solid letters, so they read as the thing in front of the light.
            "Eclipse" to LyricsFxSettings(
                fontSizeSp = 25f, letterSpacingSp = 0.05f,
                glassBodyOpacity = 0.85f, glassRefraction = 0.1f, glassRimBrightness = 1.6f, glassDispersion = 0.6f,
                glassDepth = 1.3f, glassReflection = 1.5f,
                rotationDegrees = 5f, waveSpeed = 0.6f, waveTravelDp = 2f, shadowDepth = 0.8f,
                bassReact = 0.7f, pumpAmount = 0.06f, attackMs = 14f, releaseMs = 220f, bounce = 0.5f,
                glowRadiusDp = 0f, glowBrightness = 0f,
                godRays = true, godRaySource = GOD_RAYS_BACKLIGHT,
                godRayExposure = 0.9f, godRayDensity = 1f, godRayDecay = 0.97f,
                godRayAzimuthDeg = 90f, godRayElevationDeg = 90f, godRaySunSize = 0.1f,
                godRayShimmer = 0.25f, godRayBeat = 0.6f,
            ),
            // Daybreak — a low sun just off the top right (35°) shining through
            // the lyrics, wandering a little and swinging as the phone tilts.
            "Daybreak" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = 0.15f,
                glassBodyOpacity = 0.7f, glassRefraction = 0.14f, glassRimBrightness = 1.2f, glassDispersion = 0.7f,
                glassLightAngleDeg = 30f, glassTiltReactivity = 1f,
                rotationDegrees = 7f, waveSpeed = 0.7f, wavePhaseStep = 0.18f, waveTravelDp = 3f, shadowDepth = 0.6f,
                bassReact = 0.5f, pumpAmount = 0.06f, attackMs = 18f, releaseMs = 240f, bounce = 0.55f,
                glowRadiusDp = 36f, glowBrightness = 0.12f,
                godRays = true, godRaySource = GOD_RAYS_BACKLIGHT,
                godRayExposure = 1f, godRayDensity = 1f, godRayDecay = 0.97f,
                godRayAzimuthDeg = 30f, godRayElevationDeg = 35f, godRaySunSize = 0.12f,
                godRayShimmer = 0.45f, godRayBeat = 0.3f, godRaySway = 0.15f, godRayTilt = 0.8f,
            ),
            // Searchlight — a light raking in almost level from the left (12°),
            // so the shafts are long and nearly parallel, sweeping round the
            // line and punching hard on every kick.
            "Searchlight" to LyricsFxSettings(
                fontSizeSp = 25f, letterSpacingSp = 0.2f,
                glassBodyOpacity = 0.55f, glassRefraction = 0.22f, glassRimBrightness = 1.7f, glassDispersion = 1.3f,
                rotationDegrees = 12f, waveSpeed = 1.2f, wavePhaseStep = 0.28f, waveTravelDp = 4f, shadowDepth = 0.6f,
                bassReact = 0.9f, pumpAmount = 0.12f, attackMs = 7f, releaseMs = 110f, bounce = 0.8f,
                glowRadiusDp = 40f, glowBrightness = 0.2f,
                godRays = true, godRaySource = GOD_RAYS_LETTERS,
                godRayExposure = 0.75f, godRayDensity = 1f, godRayDecay = 0.97f,
                godRayAzimuthDeg = 160f, godRayElevationDeg = 12f,
                godRayShimmer = 0.2f, godRayBeat = 0.85f, godRaySpinDps = 12f,
            ),
            // Spotlight — karaoke light: the shafts burst from the one word
            // being sung and hop to the next as it lights up. Word-timed lyrics
            // only; line-timed lyrics shine as a whole line.
            "Spotlight" to LyricsFxSettings(
                fontSizeSp = 25f, letterSpacingSp = 0.1f,
                glassBodyOpacity = 0.62f, glassRefraction = 0.2f, glassRimBrightness = 1.5f, glassDispersion = 1.2f,
                rotationDegrees = 10f, waveSpeed = 1f, wavePhaseStep = 0.24f, waveTravelDp = 3.5f, shadowDepth = 0.6f,
                bassReact = 0.8f, pumpAmount = 0.1f, attackMs = 9f, releaseMs = 140f, bounce = 0.7f,
                glowRadiusDp = 20f, glowBrightness = 0.1f,
                godRays = true, godRaySource = GOD_RAYS_LETTERS, godRaysFollowWord = true,
                godRayExposure = 0.8f, godRayDensity = 0.9f, godRayDecay = 0.95f,
                godRayAzimuthDeg = 90f, godRayElevationDeg = 75f,
                godRayShimmer = 0.3f, godRayBeat = 0.6f,
            ),
            // Crepuscular — toninoni's Shadertoy "Crepuscular light" (ls2Xzd),
            // the article's own numbers: decay 0.92, density 1, every lyric
            // shining, the light wandering on its sin(t), sin(0.913t) path.
            // Exposure 0.57 is its 0.4 + 0.4 × 0.58767 weights; at the 50-sample
            // quality the march matches it to 0.1% (GodRayGeometryTest).
            "Crepuscular" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = 0.05f,
                glassBodyOpacity = 0.6f, glassRefraction = 0.16f, glassRimBrightness = 1.2f, glassDispersion = 0.9f,
                rotationDegrees = 8f, waveSpeed = 0.8f, wavePhaseStep = 0.2f, waveTravelDp = 3f, shadowDepth = 0.6f,
                bassReact = 0.6f, pumpAmount = 0.07f, attackMs = 14f, releaseMs = 180f, bounce = 0.6f,
                glowRadiusDp = 0f, glowBrightness = 0f,
                godRays = true, godRaySource = GOD_RAYS_LETTERS, godRaysAllLyrics = true,
                godRayExposure = 0.57f, godRayDensity = 1f, godRayDecay = 0.92f,
                godRayAzimuthDeg = 90f, godRayElevationDeg = 90f,
                godRayShimmer = 0f, godRayBeat = 0f, godRaySway = 1f,
            ),
            // Mercury — liquid-metal letters: the Player Glass material's
            // mirror end — full room reflection, a tight glint, deep relief and
            // a broad shoulder, top-lit and moving with the phone.
            "Mercury" to LyricsFxSettings(
                fontSizeSp = 25f, letterSpacingSp = -0.1f,
                glassBodyOpacity = 0.8f, glassRefraction = 0.18f, glassRimBrightness = 1.6f, glassDispersion = 0.2f,
                glassRoundness = 1.5f, glassDepth = 1.7f, glassSurfaceMotion = 0.6f, glassReflection = 2f,
                glassGloss = 1f, glassTiltReactivity = 1f, glassLightAngleDeg = 90f, glassEdgeWidth = 0.6f,
                rotationDegrees = 9f, waveSpeed = 0.8f, wavePhaseStep = 0.18f, waveTravelDp = 3f, shadowDepth = 0.75f,
                bassReact = 0.7f, pumpAmount = 0.08f, attackMs = 12f, releaseMs = 180f, bounce = 0.6f,
                glowRadiusDp = 48f, glowBrightness = 0.18f,
            ),
            // Sea Glass — tumbled, frosted letters: heavy surface grain, a soft
            // wide glint on a broad round edge, barely moving.
            "Sea Glass" to LyricsFxSettings(
                fontSizeSp = 24f, letterSpacingSp = 0.25f,
                glassBodyOpacity = 0.7f, glassRefraction = 0.16f, glassRimBrightness = 0.8f, glassDispersion = 0.5f,
                glassRoundness = 1.9f, glassDepth = 0.8f, glassSurfaceMotion = 0.3f, glassReflection = 0.6f,
                glassGloss = 0.15f, glassTiltReactivity = 0.4f, glassLightAngleDeg = 160f, glassEdgeWidth = 0.8f,
                glassFrost = 0.75f,
                rotationDegrees = 4f, waveSpeed = 0.5f, wavePhaseStep = 0.12f, waveTravelDp = 2f, shadowDepth = 0.4f,
                bassReact = 0.35f, pumpAmount = 0.04f, attackMs = 24f, releaseMs = 300f, bounce = 0.35f,
                glowRadiusDp = 60f, glowBrightness = 0.12f,
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
