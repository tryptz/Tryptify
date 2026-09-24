package tf.monochrome.android.ui.mixer.fxchain

import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.ui.mixer.ParamDef

/**
 * A starting point for one effect: the parameters it sets, on top of the
 * effect's defaults.
 *
 * [mastering] marks a preset that is safe on a whole finished song — this is
 * a listening app, so every preset runs on a master. Those come first; the
 * effect's classic creative settings, where it has them, come after a divider.
 */
data class FxPreset(
    val name: String,
    val values: Map<Int, Float>,
    val dryWet: Float = 1f,
    val mastering: Boolean = true,
) {
    /**
     * Every parameter, not just the ones this preset names: applying it resets
     * the rest to their defaults, so a preset sounds the same whatever the
     * effect was doing before.
     */
    fun resolved(defs: List<ParamDef>): FloatArray =
        FloatArray(defs.size) { i -> values[i] ?: defs[i].default }

    /** Whether [current] is this preset, give or take rounding. */
    fun matches(defs: List<ParamDef>, current: Map<Int, Float>, currentDryWet: Float): Boolean {
        if (kotlin.math.abs(currentDryWet - dryWet) > 0.005f) return false
        val want = resolved(defs)
        return defs.indices.all { i ->
            val have = current[i] ?: defs[i].default
            kotlin.math.abs(have - want[i]) <= 1e-3f * (1f + kotlin.math.abs(want[i]))
        }
    }
}

/**
 * Five presets for every effect, mastering first.
 *
 * Values come from published practice, mapped onto these effects' own
 * parameters (indices follow ParamDefs.kt): the Kilohearts snapins manual
 * (kilohearts.com/docs/snapins, /docs/disperser) for the effects these are
 * modelled on; FabFilter's Pro-C 2, Pro-L 2, Pro-G and Saturn 2 manuals;
 * Universal Audio's EMT 140, Lexicon 480L, LA-2A and 1176 notes; the SPL
 * Transient Designer manual; Valhalla DSP's reverb articles; the Soundtoys
 * EchoBoy manual; iZotope and Abletunes EQ guides; AutoEQ; streaming loudness
 * targets (−14 LUFS, −1 dBTP); and mastering practice for bus compression
 * (1.5–2:1, 1–2 dB of reduction), broad ±1–2 dB tilts, short low-cut
 * ambience at 8–12 %, and mono-safe width. Where an effect of ours behaves
 * differently from its namesake the values were designed around ours (e.g.
 * Trance Gate runs at 120 BPM; delay and reverser times assume it).
 *
 * No `else`: a new effect does not compile until it has presets.
 */
object FxPresets {

    private fun m(name: String, vararg v: Pair<Int, Float>, dw: Float = 1f) =
        FxPreset(name, mapOf(*v), dw, mastering = true)

    private fun c(name: String, vararg v: Pair<Int, Float>, dw: Float = 1f) =
        FxPreset(name, mapOf(*v), dw, mastering = false)

    fun forType(type: SnapinType): List<FxPreset> = when (type) {
        // ── Utility ─────────────────────────────────────────────────────
        // Headroom and level matching (FabFilter Saturn 2; −1 dBTP streaming).
        SnapinType.GAIN -> listOf(
            m("True-Peak Trim", 0 to -1f),
            m("Loudness Match −3", 0 to -3f),
            m("Drive Headroom", 0 to -6f),
            m("Subtle Lift", 0 to 1.5f),
            m("Quiet Listening", 0 to -12f),
        )
        // Mid/side width; +3..+6 dB side is the usual safe range (Mastering The Mix).
        SnapinType.STEREO -> listOf(
            m("Gentle Width", 1 to 1.5f),
            m("Wide Master", 1 to 3f),
            m("Extra Wide", 0 to -1f, 1 to 6f),
            m("Mono Check", 1 to -24f),
            c("Center Cut", 0 to -24f, 1 to 3f),
        )
        // Mono-compatibility checks and headphone crossfeed (Sound On Sound).
        SnapinType.CHANNEL_MIXER -> listOf(
            m("Mono Sum", 0 to 0.5f, 1 to 0.5f, 2 to 0.5f, 3 to 0.5f),
            m("Headphone Blend", 0 to 0.8f, 1 to 0.2f, 2 to 0.2f, 3 to 0.8f),
            m("Swap L/R", 0 to 0f, 1 to 1f, 2 to 1f, 3 to 0f),
            m("Polarity Check", 3 to -1f),
            m("Left to Both", 2 to 1f, 3 to 0f),
        )
        // Sub-millisecond offsets steer the image; 5–35 ms widens (iZotope, SOS).
        SnapinType.HAAS -> listOf(
            m("Image Nudge Right", 1 to 0.6f),
            m("Image Nudge Left", 0 to 1f, 1 to 0.6f),
            c("Subtle Widen", 1 to 5f),
            c("Classic Haas", 0 to 1f, 1 to 12f),
            c("Edge of Echo", 1 to 25f),
        )

        // ── EQ & filter ─────────────────────────────────────────────────
        SnapinType.FILTER -> listOf(
            m("Subsonic 25 Hz", 0 to 2f, 1 to 25f, 4 to 1f),
            m("Air Shelf", 0 to 6f, 1 to 12000f, 2 to 0.707f, 3 to 1.5f),
            m("Low Shelf Warmth", 0 to 4f, 1 to 100f, 2 to 0.707f, 3 to 1.5f),
            m("De-Harsh 3k", 0 to 5f, 1 to 3000f, 2 to 0.8f, 3 to -1.5f),
            c("Telephone", 0 to 1f, 1 to 950f, 2 to 0.35f, 4 to 1f),
        )
        // Broad mastering tilts and small moves (iZotope EQ guide, Katz).
        SnapinType.EQ_3BAND -> listOf(
            m("Tilt Brighter", 0 to 150f, 1 to -1f, 6 to 6000f, 7 to 1f),
            m("Tilt Warmer", 0 to 150f, 1 to 1f, 6 to 6000f, 7 to -1f),
            m("Low-Mid Scoop", 3 to 300f, 4 to -1.5f, 5 to 0.8f),
            m("Presence Lift", 3 to 3500f, 4 to 1.5f, 5 to 0.8f, 6 to 12000f, 7 to 1f),
            m("Loudness Smile", 1 to 3f, 3 to 800f, 4 to -1f, 5 to 0.7f, 6 to 10000f, 7 to 2f),
        )
        // Band n sits at 1 + 5n: +0 freq, +1 gain, +2 Q, +3 type (1 low shelf,
        // 2 high shelf), +4 on. Preamp (0) is set against the largest boost,
        // the AutoEQ convention.
        SnapinType.EQ_10BAND -> listOf(
            m("Gentle Master Tilt", 0 to -2f, 1 to 150f, 2 to -1.5f, 3 to 0.707f, 4 to 1f,
                46 to 10000f, 47 to 2f, 48 to 0.707f, 49 to 2f),
            m("Mud & Harsh Cleanup", 16 to 300f, 17 to -2f, 18 to 1.4f, 36 to 3500f, 37 to -1.5f,
                38 to 2f, 41 to 7000f, 42 to -1f, 43 to 3f),
            m("Gentle Smile", 0 to -2f, 2 to 2f, 7 to 1.5f, 22 to -0.5f, 27 to -1f, 42 to 1.5f, 47 to 2f),
            m("Air Band", 0 to -1.5f, 46 to 12000f, 47 to 1.5f, 48 to 0.707f, 49 to 2f),
            m("Headphone Bass Shelf", 0 to -6f, 1 to 105f, 2 to 6f, 3 to 0.707f, 4 to 1f),
        )
        SnapinType.LADDER_FILTER -> listOf(
            m("Analog Top Roll-off", 0 to 16000f, 3 to 1f, 4 to 1f),
            m("Warm Analog Roll-off", 0 to 8000f, 1 to 10f, 3 to 1f, 4 to 3f),
            c("Underwater", 0 to 350f, 1 to 20f),
            c("Classic Bass Ladder", 0 to 600f, 1 to 40f, 3 to 1f, 4 to 6f),
            c("Acid Squelch", 0 to 900f, 1 to 75f, 2 to 1f, 3 to 1f, 4 to 12f),
        )
        SnapinType.NONLINEAR_FILTER -> listOf(
            m("Saturated Top", 1 to 16000f, 2 to 0.707f, 3 to 3f, 4 to 1f),
            m("Warm Tanh Lowpass", 1 to 8000f, 2 to 0.8f, 3 to 4f, 4 to 1f),
            c("Gritty Radio Band", 0 to 1f, 1 to 1200f, 2 to 1.2f, 3 to 12f, 4 to 2f),
            c("Tight Driven Highpass", 0 to 2f, 1 to 250f, 2 to 2f, 3 to 3f, 4 to 4f),
            c("Wavefold Resonator", 1 to 1500f, 2 to 6f, 3 to 12f, 4 to 3f, dw = 0.5f),
        )
        SnapinType.COMB_FILTER -> listOf(
            m("Mono-Safe Widener", 0 to 80f, 1 to 20f, 3 to 1f),
            m("Air Color", 0 to 5000f, 1 to 10f),
            c("Hollow Tube", 0 to 600f, 1 to 70f, 2 to 1f),
            c("Tuned A", 0 to 220f, 1 to 80f),
            c("Metallic Stereo", 0 to 2000f, 1 to 90f, 2 to 1f, 3 to 1f),
        )
        // Formant corners: (0,0) "a", (1,0) "e", (1,1) "i", (0,1) "u".
        SnapinType.FORMANT_FILTER -> listOf(
            m("Vocal Presence", 0 to 1f, 1 to 0f, 2 to 2f, dw = 0.2f),
            c("\"Ah\"", 0 to 0f, 1 to 0f, 2 to 8f),
            c("\"Ee\"", 0 to 1f, 1 to 1f, 2 to 8f),
            c("\"Oo\"", 0 to 0f, 1 to 1f, 2 to 8f),
            c("\"Oh\" Talk Box", 0 to 0f, 1 to 0.6f, 2 to 10f),
        )
        // Pitch is a MIDI note: set it to the song's key.
        SnapinType.RESONATOR -> listOf(
            m("Tuned Body A", 0 to 45f, 1 to 30f, 2 to 25f, 4 to 10f),
            c("Metallic Ring C", 0 to 60f, 1 to 80f, 2 to 15f, 4 to 35f),
            c("Sympathetic Strings E", 0 to 52f, 1 to 70f, 2 to 20f, 4 to 30f),
            c("Hollow Square D", 0 to 50f, 1 to 20f, 2 to 15f, 3 to 1f, 4 to 35f),
            c("Infinite Drone A", 0 to 45f, 1 to 95f, 2 to 5f, 4 to 40f),
        )
        // Low-amount all-pass smearing lowers peaks without lowering loudness
        // (Kilohearts Disperser doc).
        SnapinType.DISPERSER -> listOf(
            m("Crest Tamer", 0 to 3f, 1 to 400f, 2 to 0.5f),
            m("Low-End Crest Tamer", 0 to 4f, 1 to 120f, 2 to 0.5f),
            c("Snare Crack", 0 to 12f, 1 to 1500f, 2 to 1f),
            c("Laser Zap", 0 to 16f, 1 to 300f, 2 to 1.5f),
            c("Space Chirp", 0 to 32f, 1 to 200f, 2 to 4f),
        )

        // ── Dynamics ────────────────────────────────────────────────────
        // Mastering bus compression: 1.5–2:1, 30 ms attack, 1–2 dB of reduction
        // (FabFilter Pro-C 2; SSL-bus practice).
        SnapinType.COMPRESSOR -> listOf(
            m("Mastering Glue", 0 to 30f, 1 to 200f, 2 to 1.5f, 3 to -12f, 4 to 6f, 5 to 1f),
            m("Bus Glue 2:1", 0 to 30f, 1 to 200f, 2 to 2f, 3 to -16f, 5 to 2f),
            m("Parallel Density", 0 to 10f, 1 to 150f, 2 to 4f, 3 to -24f, 4 to 6f, 5 to 6f, 8 to 25f),
            m("Late-Night Leveler", 0 to 5f, 1 to 600f, 2 to 3f, 3 to -28f, 4 to 12f, 5 to 8f, 7 to 5f),
            c("Four-Floor Pump", 0 to 5f, 1 to 180f, 2 to 10f, 3 to -26f, 4 to 0f, 5 to 6f, 6 to 1f),
        )
        // Ceiling = Thresh + Out Gain. Sample-peak, so −1 dB for streaming
        // (FabFilter Pro-L 2 true-peak guide).
        SnapinType.LIMITER -> listOf(
            m("Streaming −1 dBTP", 1 to -1f, 2 to 150f, 3 to 5f),
            m("Transparent Lift", 0 to 2f, 1 to -1f, 2 to 250f, 3 to 8f),
            m("CD Master −0.3", 0 to 4f, 1 to -0.3f, 2 to 100f, 3 to 5f),
            m("Broadcast −2", 1 to -2f, 2 to 300f, 3 to 10f),
            c("Clipper Edge", 0 to 6f, 1 to -0.5f, 2 to 10f, 3 to 1f),
        )
        SnapinType.GATE -> listOf(
            m("Fade Noise Floor", 0 to 5f, 1 to 100f, 2 to 500f, 3 to -60f, 4 to 8f, 5 to 10f),
            m("Gentle Floor Expander", 0 to 10f, 1 to 200f, 2 to 800f, 3 to -50f, 4 to 6f, 5 to 6f),
            c("Drum Bus Tighten", 0 to 0.5f, 1 to 20f, 2 to 150f, 3 to -30f, 5 to 15f, 6 to 1f),
            c("Gated Reverb Chop", 1 to 250f, 2 to 10f, 3 to -20f, 4 to 3f),
            c("Inverse Ducker", 0 to 1f, 1 to 20f, 2 to 100f, 3 to -18f, 5 to 12f, 7 to 1f),
        )
        // Knee 0 wherever Lo Ratio ≠ 1: hard knees keep upward gain exact.
        SnapinType.DYNAMICS -> listOf(
            m("Two-Stage Master Control", 0 to -35f, 1 to 1.3f, 2 to -10f, 3 to 2f, 4 to 20f, 5 to 250f, 6 to 0f),
            m("Quiet Detail Lift", 0 to -45f, 1 to 1.5f, 3 to 1f, 4 to 20f, 5 to 300f, 6 to 0f),
            m("Gentle Upward Density", 0 to -40f, 1 to 2f, 2 to -16f, 3 to 2f, 4 to 5f, 5 to 120f, 6 to 0f, 9 to 15f),
            m("Noise-Floor Expander", 0 to -55f, 1 to 0.7f, 3 to 1f, 4 to 1f, 5 to 200f, 6 to 0f),
            c("Squash Both Ways", 0 to -40f, 1 to 4f, 2 to -20f, 4 to 1f, 5 to 80f, 6 to 0f, 9 to 30f),
        )
        // No makeup gain on this one: follow it with Gain if level matters.
        SnapinType.COMPACTOR -> listOf(
            m("ISP Ceiling", 2 to 150f, 3 to -1f, 4 to 2f),
            m("Soft Peak Taming", 2 to 200f, 3 to -6f, 5 to 50f),
            m("Pre-Limiter Catch", 0 to 5f, 2 to 80f, 3 to -3f, 5 to 60f),
            m("Dual-Mono Wide Catch", 2 to 120f, 3 to -3f, 6 to 100f),
            c("Exaggerated Duck", 0 to 10f, 1 to 50f, 2 to 250f, 3 to -12f, 4 to 0f, 5 to 200f),
        )
        // Sustain here is a static gain, so tails are shortened with Pump.
        SnapinType.TRANSIENT_SHAPER -> listOf(
            m("Master Punch", 0 to 15f, 3 to 60f),
            m("Restore Punch", 0 to 25f, 3 to 75f),
            m("Soften Harsh Attacks", 0 to -20f, 3 to 60f),
            m("Tight Low End", 0 to 10f, 1 to 20f, 3 to 70f),
            c("Crunch Clip", 0 to 70f, 3 to 90f, 4 to 1f),
        )
        // Fixed 120 BPM; Length stays 16, the span of the patterns.
        SnapinType.TRANCE_GATE -> listOf(
            m("Gentle Pulse", 0 to 2f, 2 to 5f, 4 to 60f, 5 to 30f, 6 to 20f),
            m("Breathing Offbeat", 0 to 1f, 2 to 60f, 4 to 50f, 5 to 200f, 6 to 25f),
            c("Classic 16th Chop", 0 to 2f, 5 to 15f, 6 to 80f),
            c("Syncopated 3-3-2", 0 to 3f, 2 to 2f, 3 to 120f, 4 to 40f, 5 to 30f, 6 to 75f),
            c("Stutter 32nds", 0 to 6f, 2 to 0.5f, 5 to 5f, 7 to 3f),
        )

        // ── Distortion ──────────────────────────────────────────────────
        // Mastering saturation: little drive, blended (FabFilter Saturn 2).
        // Types: 0 soft clip, 1 hard, 2 tanh, 3 sine fold, 4 rectify, 5 asymmetric.
        SnapinType.DISTORTION -> listOf(
            m("Master Tape Warmth", 0 to 3f, 1 to 2f, 2 to 16000f, 6 to -1.5f, 7 to 20f),
            m("Tube Glow", 0 to 4f, 1 to 5f, 2 to 16000f, 6 to -2f, 7 to 15f),
            m("Soft-Clip Glue", 0 to 2f, 2 to 16000f, 6 to -1f, 7 to 25f),
            c("Overdrive Crunch", 0 to 18f, 2 to 6000f, 5 to 20f, 6 to -6f, 7 to 70f),
            c("Fuzz", 0 to 30f, 1 to 1f, 2 to 4500f, 3 to 0.2f, 6 to -8f),
        )
        SnapinType.SHAPER -> listOf(
            m("Parallel Warmth", 1 to 15f),
            m("Soft Saturate", 0 to 2f, 1 to 25f),
            c("Hard Drive", 0 to 18f),
            c("Wrap Glitch", 0 to 12f, 1 to 50f, 2 to 1f),
            c("Mirror Fold", 0 to 12f, 1 to 60f, 2 to 2f),
        )
        // Clip a dB or three before the limiter; Filter 2 avoids the legacy
        // mode's colour; Out makes up the symmetric −6 dB.
        SnapinType.MISSTORTION -> listOf(
            m("Peak Clip 1 dB", 1 to 1f, 6 to 2f, 7 to 6f),
            m("Peak Clip 3 dB", 1 to 3f, 6 to 2f, 7 to 6f),
            m("Warm Soft Clip", 0 to -12f, 2 to 6f, 6 to 2f, 7 to 12f),
            c("Hard Clip Crush", 1 to 18f, 2 to 12f, 5 to 8000f, 6 to 2f, 7 to 12f),
            c("Radio Clip", 1 to 12f, 4 to 400f, 5 to 3500f, 6 to 2f, 7 to 6f),
        )
        // 12-bit / 26 kHz is the classic sampler grit; 8 kHz / 8-bit is a phone line.
        SnapinType.BITCRUSH -> listOf(
            m("Dusty Blend", 0 to 22050f, 1 to 10f, 4 to 100f, 5 to 12f),
            m("12-bit Warmth", 0 to 26040f, 1 to 12f, 2 to 80f, 5 to 20f),
            c("8-bit Game", 0 to 11025f, 1 to 8f),
            c("Telephone", 0 to 8000f, 1 to 8f, 2 to 50f),
            c("Broken Speaker", 0 to 4000f, 1 to 4f, 5 to 60f),
        )
        // Mix stays 100: a partial blend comb-filters against the dry signal.
        SnapinType.PHASE_DISTORTION -> listOf(
            m("Harmonic Sheen", 0 to 6f, 2 to 75f),
            m("Stereo Phase Spread", 0 to 10f, 4 to 40f),
            c("Phase Buzz", 0 to 40f, 2 to 85f),
            c("Resonant Growl", 0 to 70f, 2 to 60f, 3 to 1.57f),
            c("Normalized Fuzz", 0 to 50f, 1 to 1f, 2 to 50f),
        )

        // ── Modulation ──────────────────────────────────────────────────
        SnapinType.CHORUS -> listOf(
            m("Master Width Subtle", 0 to 12f, 1 to 0.3f, 2 to 10f, 3 to 4f, 4 to 100f, 6 to 15f),
            m("Bus Widener", 0 to 12f, 1 to 0.3f, 2 to 15f, 3 to 4f, 4 to 100f, 6 to 25f),
            c("Classic Poly", 0 to 6f, 1 to 0.5f, 2 to 35f, 3 to 2f, 4 to 100f),
            c("Lush 8-Voice", 0 to 18f, 1 to 0.8f, 2 to 15f, 3 to 8f, 4 to 100f, 5 to 15f),
            c("Vibrato", 0 to 5f, 1 to 5f, 2 to 20f, 3 to 1f, 4 to 0f, 6 to 100f),
        )
        SnapinType.ENSEMBLE -> listOf(
            m("Dimension Subtle", 0 to 2f, 1 to 8f, 2 to 100f, 3 to 20f),
            m("Dimension Wide", 1 to 10f, 2 to 100f, 3 to 25f),
            c("String Ensemble", 0 to 6f, 1 to 12f, 2 to 90f, 3 to 55f, 4 to 1f),
            c("Unison Thick", 0 to 8f, 1 to 25f, 2 to 100f),
            c("Seasick Warble", 0 to 3f, 1 to 10f, 2 to 60f, 3 to 60f, 4 to 2f),
        )
        // Stereo is the L/R LFO offset in degrees; Thru-0 inverts the wet path.
        SnapinType.FLANGER -> listOf(
            m("Bus Sweep", 0 to 4f, 1 to 40f, 2 to 0.08f, 3 to 10f, 4 to 90f, 5 to 8000f, 7 to 15f),
            c("Jet Flange", 0 to 3f, 1 to 90f, 2 to 0.1f, 3 to 70f, 4 to 0f, 5 to 12000f),
            c("Pedal Flange", 0 to 2f, 1 to 80f, 2 to 0.4f, 3 to 50f, 4 to 90f, 5 to 10000f),
            c("Negative Flange", 0 to 1.5f, 1 to 95f, 2 to 0.15f, 3 to -50f, 4 to 0f, 5 to 12000f, 6 to 1f),
            c("Metallic Ring", 0 to 0.8f, 1 to 30f, 2 to 3f, 3 to 90f, 4 to 60f, 5 to 6000f),
        )
        SnapinType.PHASER -> listOf(
            m("Stereo Bus Phase", 0 to 6f, 1 to 0.1f, 2 to 50f, 3 to 1200f, 4 to 15f, 5 to 180f, 6 to 100f, 7 to 25f),
            c("4-Stage Vintage", 0 to 4f, 1 to 0.5f, 2 to 70f, 3 to 800f, 4 to 0f, 5 to 0f, 6 to 0f, 7 to 100f),
            c("4-Stage Color", 0 to 4f, 1 to 0.8f, 2 to 70f, 3 to 900f, 4 to 45f, 5 to 0f, 6 to 0f, 7 to 100f),
            c("12-Stage Swirl", 0 to 12f, 1 to 0.25f, 2 to 80f, 3 to 1000f, 4 to 60f, 5 to 90f, 6 to 100f, 7 to 100f),
            c("Fast Wobble", 0 to 6f, 1 to 5.5f, 2 to 35f, 3 to 1500f, 4 to 20f, 5 to 90f, 6 to 100f, 7 to 100f),
        )
        SnapinType.RING_MOD -> listOf(
            m("Slow Tremolo Subtle", 0 to 4f, 1 to 85f),
            m("Gentle Auto-Pan", 0 to 1f, 1 to 80f, 3 to 100f),
            c("Bell Ring", 0 to 800f, 4 to 50f),
            c("Robot Voice", 0 to 30f),
            c("Rectified Grit", 0 to 220f, 1 to 60f, 2 to 40f, 4 to 35f),
        )
        // No Mix of its own: blends use the effect's dry/wet.
        SnapinType.FREQUENCY_SHIFTER -> listOf(
            m("Phase Drift", 0 to 0.3f, dw = 0.15f),
            m("Shift Chorus", 0 to 3f, dw = 0.2f),
            c("Anti-Howl", 0 to 5f),
            c("Clangorous", 0 to 120f, dw = 0.35f),
            c("Down-Shift Robot", 0 to -300f),
        )
        SnapinType.PITCH_SHIFTER -> listOf(
            m("Subtle Thickener", 0 to -0.07f, 1 to 25f, 2 to 60f, 3 to 15f),
            m("Micro Detune Width", 0 to -0.12f, 1 to 10f, 2 to 40f, 3 to 20f),
            c("Octave Down Sub", 0 to -12f, 2 to 80f, 3 to 50f),
            c("Fourth Below", 0 to -5f, 3 to 40f),
            c("Octave Up Air", 0 to 12f, 2 to 30f, 3 to 40f),
        )
        // Play stays on; the stop is performed with the Play switch. No honest
        // mastering use, so all five are the classic moves.
        SnapinType.TAPE_STOP -> listOf(
            c("Power Down", 1 to 2000f, 3 to 70f),
            c("Beat Stop", 2 to 250f, 3 to 60f),
            c("Quick Brake", 1 to 150f, 2 to 150f, 3 to 30f),
            c("Turntable Start", 1 to 300f, 2 to 1200f, 3 to 80f),
            c("Long Wind-Down", 1 to 4000f, 2 to 2000f),
        )

        // ── Space ───────────────────────────────────────────────────────
        // On a master: short, low-cut, 8–12 % (Valhalla; FabFilter Pro-R).
        SnapinType.REVERB -> listOf(
            m("Mastering Ambience", 0 to 10f, 1 to 0.8f, 2 to 30f, 3 to 55f, 4 to 75f, 6 to 8f,
                7 to 7000f, 8 to 250f, 9 to 40f, 10 to 80f, 11 to 8f),
            m("Mix Glue Ambience", 0 to 10f, 1 to 1f, 2 to 35f, 3 to 55f, 4 to 75f, 6 to 10f,
                7 to 7000f, 8 to 200f, 9 to 40f, 10 to 80f, 11 to 12f),
            m("Short Plate Sheen", 0 to 20f, 1 to 1.2f, 2 to 40f, 3 to 40f, 4 to 90f, 6 to 10f,
                7 to 9000f, 8 to 300f, 9 to 10f, 11 to 8f),
            m("Live Room Depth", 0 to 5f, 1 to 0.6f, 2 to 20f, 3 to 60f, 4 to 60f, 6 to 10f,
                7 to 7000f, 8 to 200f, 9 to 60f, 11 to 10f),
            c("Concert Hall", 0 to 30f, 1 to 2.8f, 2 to 85f, 3 to 45f, 4 to 80f, 5 to 0.5f, 6 to 20f,
                7 to 6500f, 8 to 100f, 9 to 25f, 11 to 25f),
        )
        // Times at 120 BPM (EchoBoy manual styles).
        SnapinType.DELAY -> listOf(
            m("Subtle Slap Depth", 0 to 90f, 1 to 0f, 4 to 50f, 5 to 300f, 6 to 5000f, 8 to 8f),
            m("Ducked Quarter Echo", 0 to 500f, 1 to 20f, 4 to 60f, 5 to 250f, 6 to 5000f, 8 to 8f),
            c("Slapback", 0 to 110f, 1 to 0f, 5 to 150f, 6 to 5000f, 7 to 5f, 8 to 25f),
            c("Dotted-Eighth", 0 to 375f, 1 to 40f, 4 to 30f, 5 to 200f, 6 to 7000f, 8 to 22f),
            c("Dub Throw", 0 to 750f, 1 to 75f, 5 to 300f, 6 to 2500f, 7 to 25f, 8 to 35f),
        )
        SnapinType.REVERSER -> listOf(
            m("Ghost Ambience", 0 to 1000f, 2 to 40f, 3 to 10f),
            c("Reverse Swell", 0 to 500f, 2 to 25f, 3 to 35f),
            c("Backwards Bar", 0 to 2000f, 2 to 20f, 3 to 100f),
            c("Reverse Stutter", 0 to 125f, 2 to 5f, 3 to 60f),
            c("Reverse Solo", 2 to 15f, 3 to 100f),
        )
    }
}
