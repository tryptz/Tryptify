package tf.monochrome.android.audio.dsp.preset

import tf.monochrome.android.audio.dsp.SnapinType

/**
 * Builds DSP-engine state JSON for hard-coded presets.
 *
 * The native engine (`DspEngine::loadStateJson`) consumes a flat structure:
 * `{ "buses": [ {gain,pan,muted,soloed,inputEnabled,plugins:[{type,bypassed,dryWet,params:[...]}]} x5 ] }`.
 * `params` is a flat array indexed by each processor's parameter enum, so this
 * builder starts from the processor defaults and applies typed overrides — no
 * hand-counting of array positions.
 */
object MixPresetBuilder {
    fun build(block: PresetScope.() -> Unit): String =
        PresetScope().apply(block).toJson()
}

class PresetScope {
    private val buses = Array(5) { BusScope(it) }

    /** Configure a mix bus (0-3) or the master bus (4). */
    fun bus(index: Int, gainDb: Float = 0f, block: BusScope.() -> Unit) {
        buses[index].gainDb = gainDb
        buses[index].block()
    }

    /** Configure the master bus (index 4). */
    fun master(gainDb: Float = 0f, block: BusScope.() -> Unit) = bus(4, gainDb, block)

    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\"buses\":[")
        buses.forEachIndexed { i, b ->
            if (i > 0) sb.append(',')
            b.appendJson(sb)
        }
        sb.append("]}")
        return sb.toString()
    }
}

class BusScope(private val index: Int) {
    var gainDb: Float = 0f
    var pan: Float = 0f
    private val plugins = mutableListOf<PluginEntry>()

    /**
     * Add a processor to this bus. [overrides] are `(paramIndex to value)` pairs
     * applied on top of [MixPresetParams.defaults]; [dryWet] is the plugin-level
     * dry/wet blend (0..1, named-only — most demo effects keep this at 1 and
     * shape the blend via the processor's own MIX parameter).
     */
    fun plugin(type: SnapinType, vararg overrides: Pair<Int, Float>, dryWet: Float = 1f) {
        val params = MixPresetParams.defaults(type).copyOf()
        for ((idx, value) in overrides) {
            if (idx in params.indices) params[idx] = value
        }
        plugins.add(PluginEntry(type.ordinal, dryWet, params))
    }

    fun appendJson(sb: StringBuilder) {
        sb.append("{\"gain\":").append(gainDb)
            .append(",\"pan\":").append(pan)
            .append(",\"muted\":false,\"soloed\":false")
            .append(",\"inputEnabled\":").append(index == 0)
            .append(",\"plugins\":[")
        plugins.forEachIndexed { i, p ->
            if (i > 0) sb.append(',')
            p.appendJson(sb)
        }
        sb.append("]}")
    }
}

private class PluginEntry(
    val typeOrdinal: Int,
    val dryWet: Float,
    val params: FloatArray
) {
    fun appendJson(sb: StringBuilder) {
        sb.append("{\"type\":").append(typeOrdinal)
            .append(",\"bypassed\":false")
            .append(",\"dryWet\":").append(dryWet)
            .append(",\"params\":[")
        params.forEachIndexed { i, v ->
            if (i > 0) sb.append(',')
            sb.append(v)
        }
        sb.append("]}")
    }
}

/** Default parameter arrays for the processors used by the built-in catalog. */
object MixPresetParams {
    fun defaults(type: SnapinType): FloatArray = when (type) {
        SnapinType.REVERB -> floatArrayOf(20f, 2f, 50f, 50f, 70f, 0.8f, 20f, 8000f, 80f, 30f, 100f, 30f)
        SnapinType.DELAY -> floatArrayOf(250f, 30f, 0f, 0f, 0f, 80f, 8000f, 0f, 50f)
        SnapinType.CHORUS -> floatArrayOf(7f, 1f, 50f, 3f, 50f, 0f, 50f)
        SnapinType.COMPRESSOR -> floatArrayOf(10f, 100f, 4f, -18f, 6f, 0f, 0f, 0f, 100f)
        SnapinType.DISTORTION -> floatArrayOf(12f, 0f, 8000f, 0f, 0f, 0f, 0f, 100f)
        SnapinType.STEREO -> floatArrayOf(0f, 0f, 0f)
        SnapinType.LIMITER -> floatArrayOf(0f, 0f, 100f, 5f, 0f)
        SnapinType.GAIN -> floatArrayOf(0f)
        else -> floatArrayOf()
    }
}

// ── Parameter indices (mirror the native processor enums) ───────────────────

object ReverbP {
    const val PRE_DELAY = 0
    const val DECAY = 1
    const val SIZE = 2
    const val DAMPING = 3
    const val DIFFUSION = 4
    const val MOD_RATE = 5
    const val MOD_DEPTH = 6
    const val TONE = 7
    const val LOW_CUT = 8
    const val EARLY_LATE = 9
    const val WIDTH = 10
    const val MIX = 11
}

object DelayP {
    const val TIME = 0
    const val FEEDBACK = 1
    const val PING_PONG = 2
    const val PAN = 3
    const val DUCK = 4
    const val FB_LOWCUT = 5
    const val FB_HICUT = 6
    const val MOD_DEPTH = 7
    const val MIX = 8
}

object ChorusP {
    const val DELAY_MS = 0
    const val RATE = 1
    const val DEPTH = 2
    const val VOICES = 3
    const val SPREAD = 4
    const val FEEDBACK = 5
    const val MIX = 6
}

object CompressorP {
    const val ATTACK = 0
    const val RELEASE = 1
    const val RATIO = 2
    const val THRESHOLD = 3
    const val KNEE = 4
    const val MAKEUP = 5
    const val MODE = 6
    const val LOOKAHEAD = 7
    const val MIX = 8
}

object DistortionP {
    const val DRIVE = 0
    const val TYPE = 1
    const val TONE = 2
    const val BIAS = 3
    const val DYNAMICS = 4
    const val SPREAD = 5
    const val OUTPUT = 6
    const val MIX = 7
}

object StereoP {
    const val MID_DB = 0
    const val WIDTH_DB = 1
    const val PAN = 2
}

object LimiterP {
    const val INPUT_GAIN = 0
    const val THRESHOLD = 1
    const val RELEASE = 2
    const val LOOKAHEAD = 3
    const val OUTPUT_GAIN = 4
}
