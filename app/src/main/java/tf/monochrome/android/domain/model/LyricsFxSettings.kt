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
        )
    }

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
        )
    }
}
