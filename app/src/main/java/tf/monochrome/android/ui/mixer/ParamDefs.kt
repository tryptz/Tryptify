package tf.monochrome.android.ui.mixer

import tf.monochrome.android.audio.dsp.SnapinType

/**
 * Parameter metadata for a single snapin parameter.
 *
 * [steps], when non-null, marks an enum-like / toggle parameter: knobs snap to
 * [steps]+1 discrete stops across [min]..[max] and readouts render as integers.
 * The native processor stores raw floats regardless — this only shapes the UI.
 */
data class ParamDef(
    val name: String,
    val min: Float,
    val max: Float,
    val default: Float,
    val unit: String = "",
    val steps: Int? = null
)

/**
 * Ordered parameter list per snapin type. Order and count mirror the native
 * `enum Params` in `cpp/dsp/snapins/*.h` (index == native param index).
 *
 * The `when` is intentionally exhaustive (no `else`): adding a new [SnapinType]
 * without a branch here is a compile error rather than a silently-empty editor.
 */
internal fun getParamDefs(type: SnapinType?): List<ParamDef> = when (type) {
    SnapinType.GAIN -> listOf(
        ParamDef("Gain", -100f, 24f, 0f, "dB")
    )
    SnapinType.STEREO -> listOf(
        ParamDef("Mid", -24f, 24f, 0f, "dB"),
        ParamDef("Width", -24f, 24f, 0f, "dB"),
        ParamDef("Pan", -1f, 1f, 0f, "")
    )
    SnapinType.FILTER -> listOf(
        ParamDef("Type", 0f, 6f, 0f, "", steps = 6),
        ParamDef("Cutoff", 20f, 20000f, 1000f, "Hz"),
        ParamDef("Q", 0.1f, 20f, 0.707f, ""),
        ParamDef("Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Slope", 0f, 3f, 0f, "x", steps = 3)
    )
    SnapinType.EQ_3BAND -> listOf(
        ParamDef("Lo Freq", 20f, 500f, 100f, "Hz"),
        ParamDef("Lo Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Lo Q", 0.1f, 10f, 0.7f, ""),
        ParamDef("Mid Freq", 200f, 8000f, 1000f, "Hz"),
        ParamDef("Mid Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Mid Q", 0.1f, 10f, 1f, ""),
        ParamDef("Hi Freq", 2000f, 20000f, 8000f, "Hz"),
        ParamDef("Hi Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Hi Q", 0.1f, 10f, 0.7f, "")
    )
    SnapinType.COMPRESSOR -> listOf(
        ParamDef("Attack", 0.1f, 300f, 10f, "ms"),
        ParamDef("Release", 1f, 3000f, 100f, "ms"),
        ParamDef("Ratio", 1f, 100f, 4f, ":1"),
        ParamDef("Thresh", -60f, 0f, -18f, "dB"),
        ParamDef("Knee", 0f, 24f, 6f, "dB"),
        ParamDef("Makeup", 0f, 40f, 0f, "dB"),
        ParamDef("Mode", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Look", 0f, 10f, 0f, "ms"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.LIMITER -> listOf(
        ParamDef("In Gain", -24f, 24f, 0f, "dB"),
        ParamDef("Thresh", -24f, 0f, 0f, "dB"),
        ParamDef("Release", 1f, 1000f, 100f, "ms"),
        ParamDef("Look", 1f, 10f, 5f, "ms"),
        ParamDef("Out Gain", -24f, 24f, 0f, "dB")
    )
    SnapinType.GATE -> listOf(
        ParamDef("Attack", 0.01f, 100f, 0.1f, "ms"),
        ParamDef("Hold", 0f, 500f, 50f, "ms"),
        ParamDef("Release", 1f, 2000f, 100f, "ms"),
        ParamDef("Thresh", -80f, 0f, -30f, "dB"),
        ParamDef("Toler.", 0f, 24f, 6f, "dB"),
        ParamDef("Range", 0f, 80f, 80f, "dB"),
        ParamDef("Lookahead", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Flip", 0f, 1f, 0f, "", steps = 1),
        ParamDef("SC", 0f, 1f, 0f, "", steps = 1)
    )
    SnapinType.DYNAMICS -> listOf(
        ParamDef("Lo Thr", -60f, 0f, -40f, "dB"),
        ParamDef("Lo Ratio", 0.5f, 4f, 1f, ":1"),
        ParamDef("Hi Thr", -60f, 0f, -12f, "dB"),
        ParamDef("Hi Ratio", 1f, 100f, 4f, ":1"),
        ParamDef("Attack", 0.1f, 300f, 10f, "ms"),
        ParamDef("Release", 1f, 3000f, 100f, "ms"),
        ParamDef("Knee", 0f, 24f, 6f, "dB"),
        ParamDef("In", -24f, 24f, 0f, "dB"),
        ParamDef("Out", -24f, 24f, 0f, "dB"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.COMPACTOR -> listOf(
        ParamDef("Attack", 0f, 50f, 5f, "ms"),
        ParamDef("Hold", 0f, 500f, 10f, "ms"),
        ParamDef("Release", 1f, 1000f, 100f, "ms"),
        ParamDef("Thresh", -60f, 0f, -12f, "dB"),
        ParamDef("Mode", 0f, 2f, 1f, "", steps = 2),
        ParamDef("Range", 0f, 200f, 100f, "%"),
        ParamDef("Stereo", 0f, 100f, 0f, "%"),
        ParamDef("SC", 0f, 1f, 0f, "", steps = 1)
    )
    SnapinType.TRANSIENT_SHAPER -> listOf(
        ParamDef("Attack", -100f, 100f, 0f, "%"),
        ParamDef("Pump", 0f, 100f, 0f, "%"),
        ParamDef("Sustain", -100f, 100f, 0f, "%"),
        ParamDef("Speed", 0f, 100f, 50f, "%"),
        ParamDef("Clip", 0f, 1f, 0f, "", steps = 1),
        ParamDef("SC", 0f, 1f, 0f, "", steps = 1)
    )
    SnapinType.DISTORTION -> listOf(
        ParamDef("Drive", 0f, 48f, 12f, "dB"),
        ParamDef("Type", 0f, 5f, 0f, "", steps = 5),
        ParamDef("Tone", 200f, 20000f, 12000f, "Hz"),
        ParamDef("Bias", -1f, 1f, 0f, ""),
        ParamDef("Dynamics", 0f, 100f, 0f, "%"),
        ParamDef("Spread", 0f, 100f, 0f, "%"),
        ParamDef("Output", -24f, 0f, 0f, "dB"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.SHAPER -> listOf(
        ParamDef("Drive", 0f, 48f, 0f, "dB"),
        ParamDef("Mix", 0f, 100f, 100f, "%"),
        ParamDef("Overflow", 0f, 2f, 0f, "", steps = 2),
        ParamDef("DC Filter", 0f, 1f, 1f, "", steps = 1)
    )
    SnapinType.CHORUS -> listOf(
        ParamDef("Delay", 1f, 40f, 7f, "ms"),
        ParamDef("Rate", 0.01f, 10f, 1f, "Hz"),
        ParamDef("Depth", 0f, 100f, 50f, "%"),
        ParamDef("Voices", 1f, 8f, 4f, "", steps = 7),
        ParamDef("Spread", 0f, 100f, 50f, "%"),
        ParamDef("FB", 0f, 50f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.ENSEMBLE -> listOf(
        ParamDef("Voices", 2f, 8f, 4f, "", steps = 6),
        ParamDef("Detune", 0f, 100f, 50f, "%"),
        ParamDef("Spread", 0f, 100f, 80f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%"),
        ParamDef("Motion", 0f, 2f, 0f, "", steps = 2)
    )
    SnapinType.FLANGER -> listOf(
        ParamDef("Delay", 0.1f, 10f, 1f, "ms"),
        ParamDef("Depth", 0f, 100f, 50f, "%"),
        ParamDef("Rate", 0.01f, 10f, 0.5f, "Hz"),
        ParamDef("FB", -95f, 95f, 30f, "%"),
        ParamDef("Stereo", 0f, 100f, 50f, "%"),
        ParamDef("Tone", 200f, 20000f, 12000f, "Hz"),
        ParamDef("Thru-0", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.PHASER -> listOf(
        ParamDef("Stages", 2f, 12f, 6f, "", steps = 10),
        ParamDef("Rate", 0.01f, 10f, 0.5f, "Hz"),
        ParamDef("Depth", 0f, 100f, 50f, "%"),
        ParamDef("Center", 200f, 10000f, 1000f, "Hz"),
        ParamDef("FB", -90f, 90f, 30f, "%"),
        ParamDef("Spread", 0f, 360f, 90f, "°"),
        ParamDef("Stereo", 0f, 100f, 50f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.DELAY -> listOf(
        ParamDef("Time", 1f, 2000f, 250f, "ms"),
        ParamDef("FB", 0f, 100f, 30f, "%"),
        ParamDef("PP", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Pan", -100f, 100f, 0f, ""),
        ParamDef("Duck", 0f, 100f, 0f, "%"),
        ParamDef("FB Lo", 20f, 2000f, 80f, "Hz"),
        ParamDef("FB Hi", 500f, 20000f, 12000f, "Hz"),
        ParamDef("Mod", 0f, 100f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.REVERB -> listOf(
        ParamDef("Pre-Dly", 0f, 500f, 20f, "ms"),
        ParamDef("Decay", 0.1f, 30f, 2f, "s"),
        ParamDef("Size", 0f, 100f, 50f, "%"),
        ParamDef("Damp", 0f, 100f, 50f, "%"),
        ParamDef("Diffuse", 0f, 100f, 70f, "%"),
        ParamDef("Mod Rate", 0.05f, 5f, 0.8f, "Hz"),
        ParamDef("Mod Dep", 0f, 100f, 20f, "%"),
        ParamDef("Tone", 500f, 20000f, 8000f, "Hz"),
        ParamDef("Lo Cut", 20f, 500f, 80f, "Hz"),
        ParamDef("Early", 0f, 100f, 30f, "%"),
        ParamDef("Width", 0f, 100f, 100f, "%"),
        ParamDef("Mix", 0f, 100f, 30f, "%")
    )
    SnapinType.BITCRUSH -> listOf(
        ParamDef("Rate", 200f, 48000f, 48000f, "Hz"),
        ParamDef("Bits", 1f, 24f, 24f, ""),
        ParamDef("ADC", 0f, 100f, 100f, "%"),
        ParamDef("DAC", 0f, 100f, 100f, "%"),
        ParamDef("Dither", 0f, 100f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.COMB_FILTER -> listOf(
        ParamDef("Cutoff", 20f, 20000f, 440f, "Hz"),
        ParamDef("Mix", 0f, 100f, 50f, "%"),
        ParamDef("Polar.", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Stereo", 0f, 1f, 0f, "", steps = 1)
    )
    SnapinType.CHANNEL_MIXER -> listOf(
        ParamDef("L→L", -1f, 1f, 1f, ""),
        ParamDef("R→L", -1f, 1f, 0f, ""),
        ParamDef("L→R", -1f, 1f, 0f, ""),
        ParamDef("R→R", -1f, 1f, 1f, "")
    )
    SnapinType.FORMANT_FILTER -> listOf(
        ParamDef("Vowel X", 0f, 1f, 0.5f, ""),
        ParamDef("Vowel Y", 0f, 1f, 0.5f, ""),
        ParamDef("Q", 0.5f, 20f, 5f, ""),
        ParamDef("Lows", 0f, 100f, 0f, "%"),
        ParamDef("Highs", 0f, 100f, 0f, "%")
    )
    SnapinType.FREQUENCY_SHIFTER -> listOf(
        ParamDef("Shift", -5000f, 5000f, 0f, "Hz")
    )
    SnapinType.HAAS -> listOf(
        ParamDef("Channel", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Delay", 0f, 30f, 10f, "ms")
    )
    SnapinType.LADDER_FILTER -> listOf(
        ParamDef("Cutoff", 20f, 20000f, 1000f, "Hz"),
        ParamDef("Reso", 0f, 100f, 0f, "%"),
        ParamDef("Topo", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Sat", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Drive", 0f, 48f, 0f, "dB"),
        ParamDef("Bias", -1f, 1f, 0f, "")
    )
    SnapinType.NONLINEAR_FILTER -> listOf(
        ParamDef("Type", 0f, 3f, 0f, "", steps = 3),
        ParamDef("Cutoff", 20f, 20000f, 1000f, "Hz"),
        ParamDef("Q", 0.1f, 20f, 0.707f, ""),
        ParamDef("Drive", 0f, 48f, 0f, "dB"),
        ParamDef("Mode", 0f, 4f, 0f, "", steps = 4)
    )
    SnapinType.PHASE_DISTORTION -> listOf(
        ParamDef("Drive", 0f, 100f, 30f, "%"),
        ParamDef("Norm", 0f, 1f, 0f, "", steps = 1),
        ParamDef("Tone", 0f, 100f, 100f, "%"),
        ParamDef("Bias", -3.14f, 3.14f, 0f, "rad"),
        ParamDef("Spread", 0f, 100f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.PITCH_SHIFTER -> listOf(
        ParamDef("Pitch", -24f, 24f, 0f, "st"),
        ParamDef("Jitter", 0f, 100f, 0f, "%"),
        ParamDef("Grain", 10f, 200f, 50f, "ms"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.RESONATOR -> listOf(
        ParamDef("Pitch", 0f, 127f, 69f, ""),
        ParamDef("Decay", 0f, 100f, 50f, "%"),
        ParamDef("Intens.", 0f, 100f, 50f, "%"),
        ParamDef("Timbre", 0f, 1f, 0f, ""),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.REVERSER -> listOf(
        ParamDef("Time", 50f, 2000f, 250f, "ms"),
        ParamDef("Sync", 0f, 1f, 0f, "", steps = 1),
        ParamDef("X-Fade", 1f, 50f, 10f, "%"),
        ParamDef("Mix", 0f, 100f, 50f, "%")
    )
    SnapinType.RING_MOD -> listOf(
        ParamDef("Freq", 1f, 5000f, 440f, "Hz"),
        ParamDef("Bias", 0f, 100f, 0f, "%"),
        ParamDef("Rect", -100f, 100f, 0f, "%"),
        ParamDef("Spread", 0f, 100f, 0f, "%"),
        ParamDef("Mix", 0f, 100f, 100f, "%")
    )
    SnapinType.TAPE_STOP -> listOf(
        ParamDef("Play", 0f, 1f, 1f, "", steps = 1),
        ParamDef("Stop", 50f, 5000f, 500f, "ms"),
        ParamDef("Start", 50f, 5000f, 500f, "ms"),
        ParamDef("Curve", 0f, 100f, 50f, "%")
    )
    SnapinType.TRANCE_GATE -> listOf(
        ParamDef("Pattern", 0f, 7f, 0f, "", steps = 7),
        ParamDef("Length", 1f, 32f, 16f, "", steps = 31),
        ParamDef("Attack", 0.1f, 100f, 1f, "ms"),
        ParamDef("Decay", 0.1f, 500f, 50f, "ms"),
        ParamDef("Sustain", 0f, 100f, 100f, "%"),
        ParamDef("Release", 0.1f, 500f, 10f, "ms"),
        ParamDef("Mix", 0f, 100f, 100f, "%"),
        ParamDef("Res", 0f, 3f, 2f, "", steps = 3)
    )
    SnapinType.EQ_10BAND -> buildList {
        // 51 params: preamp + 10 bands x (freq, gain, Q, type, enabled).
        // Mirrors cpp/dsp/snapins/eq_10band.h (PREAMP=0; 1+n*5+{0..4}).
        add(ParamDef("Preamp", -24f, 24f, 0f, "dB"))
        val freqs = listOf(31f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        freqs.forEachIndexed { n, f ->
            val b = n + 1
            add(ParamDef("B$b Freq", 20f, 20000f, f, "Hz"))
            add(ParamDef("B$b Gain", -24f, 24f, 0f, "dB"))
            add(ParamDef("B$b Q", 0.1f, 30f, 1f, ""))
            add(ParamDef("B$b Type", 0f, 2f, 0f, "", steps = 2))   // 0 peak, 1 lo-shelf, 2 hi-shelf
            add(ParamDef("B$b On", 0f, 1f, 1f, "", steps = 1))
        }
    }
    null -> emptyList()
}

/** Format a parameter value for display; integer-style for stepped params. */
internal fun formatParamValue(value: Float, def: ParamDef): String {
    val formatted = when {
        def.steps != null -> "%.0f".format(value)
        def.max - def.min > 100 -> "%.0f".format(value)
        def.max - def.min > 10 -> "%.1f".format(value)
        else -> "%.2f".format(value)
    }
    return if (def.unit.isNotEmpty()) "$formatted ${def.unit}" else formatted
}
