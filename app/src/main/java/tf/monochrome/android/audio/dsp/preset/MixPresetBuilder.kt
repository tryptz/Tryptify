package tf.monochrome.android.audio.dsp.preset

import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.MixUpmix

/**
 * Builds DSP-engine state JSON for hard-coded presets.
 *
 * The native engine (`DspEngine::loadStateJson`) consumes a positional list:
 * `{ "buses": [ {gain,pan,muted,soloed,inputEnabled,plugins:[{type,bypassed,dryWet,os,params:[...]}]} ... ] }`.
 * Entry 4 is always the master. Entries 0–3 are buses 1–4, and entries 5 and
 * up are the buses added with the + tile (bus 5 is entry 5), up to entry 16.
 * The engine reads the mix-bus count off the list's length, so a preset that
 * only touches buses 1–4 is written as the classic five entries and loads as
 * a four-bus mixer, while one that touches bus 7 is written with entries up to
 * 7 and brings buses 5–7 with it.
 *
 * `params` is a flat array indexed by each processor's parameter enum, so this
 * builder starts from the processor defaults and applies typed overrides — no
 * hand-counting of array positions.
 */
object MixPresetBuilder {
    fun build(block: PresetScope.() -> Unit): String =
        PresetScope().apply(block).toJson()
}

class PresetScope {
    private val buses = sortedMapOf<Int, BusScope>()

    /**
     * Spread a stereo source to 9.1.6 ahead of the mixer ([MixUpmix]), so its
     * nine channel groups land on buses 1–9 the way a 9.1.6 bed's would.
     */
    var upmix: Boolean = false

    /**
     * Configure the bus at engine [index]: 0–3 for buses 1–4, 5–16 for the
     * added buses 5–16. The master has its own call, [master].
     */
    fun bus(index: Int, gainDb: Float = 0f, block: BusScope.() -> Unit) {
        require(index in 0..BusConfig.MAX_MIX_BUSES) {
            "bus index $index is outside 0..${BusConfig.MAX_MIX_BUSES}"
        }
        val bus = buses.getOrPut(index) { BusScope(index) }
        bus.gainDb = gainDb
        bus.block()
    }

    /** Configure the master bus (always engine index 4). */
    fun master(gainDb: Float = 0f, block: BusScope.() -> Unit = {}) =
        bus(BusConfig.MASTER_INDEX, gainDb, block)

    fun toJson(): String {
        // The list has to be contiguous — the engine counts entries, it does
        // not read indices — so any bus skipped below the highest one used is
        // written as an empty bus.
        val last = maxOf(BusConfig.MASTER_INDEX, buses.lastKeyOrNull() ?: 0)
        val sb = StringBuilder()
        sb.append("{\"buses\":[")
        for (i in 0..last) {
            if (i > 0) sb.append(',')
            (buses[i] ?: BusScope(i)).appendJson(sb)
        }
        sb.append("]}")
        return MixUpmix.attach(sb.toString(), upmix)
    }

    private fun java.util.SortedMap<Int, BusScope>.lastKeyOrNull(): Int? =
        if (isEmpty()) null else lastKey()
}

class BusScope(private val index: Int) {
    var gainDb: Float = 0f
    var pan: Float = 0f
    var muted: Boolean = false
    var soloed: Boolean = false

    /**
     * Whether the track feeds this bus. Only bus 1 does by default, as in a
     * fresh mixer; set it on another bus to run it in parallel (a wet path
     * beside a dry one, say). Ignored on the master, which always takes the
     * sum of the buses.
     */
    var inputEnabled: Boolean = index == 0

    // Where this bus goes, as the engine routes it: destination index to
    // linear level. A mix bus starts at the master alone, as in the mixer.
    private val sends: MutableMap<Int, Float> =
        if (index == BusConfig.MASTER_INDEX) mutableMapOf() else mutableMapOf(BusConfig.MASTER_INDEX to 1f)

    /**
     * Route this bus's post-fader output to [dst] at [level] (linear, 0..1;
     * 0 removes the route). Sends to the master are routes like any other, so
     * `sendTo(BusConfig.MASTER_INDEX, 0f)` takes a bus off the master — for a
     * bus that only feeds other buses.
     */
    fun sendTo(dst: Int, level: Float) {
        require(index != BusConfig.MASTER_INDEX) { "the master sends nowhere" }
        require(dst != index) { "bus $index cannot send to itself" }
        require(level in 0f..1f) { "send level $level is outside 0..1" }
        if (level == 0f) sends.remove(dst) else sends[dst] = level
    }

    /** The routes as set, for the tests. */
    val routes: Map<Int, Float> get() = sends.toMap()

    private val plugins = mutableListOf<PluginEntry>()

    /**
     * Add a processor to this bus. [overrides] are `(paramIndex to value)` pairs
     * applied on top of [MixPresetParams.defaults]; [dryWet] is the plugin-level
     * dry/wet blend (0..1, named-only — most demo effects keep this at 1 and
     * shape the blend via the processor's own MIX parameter). [bypassed] keeps
     * the processor in the chain but switched off; [oversample] is 1, 2 or 4.
     */
    fun plugin(
        type: SnapinType,
        vararg overrides: Pair<Int, Float>,
        dryWet: Float = 1f,
        bypassed: Boolean = false,
        oversample: Int = 1,
    ) {
        val params = MixPresetParams.defaults(type).copyOf()
        for ((idx, value) in overrides) {
            if (idx in params.indices) params[idx] = value
        }
        add(type, params, dryWet, bypassed, oversample)
    }

    /**
     * Add a processor with its whole parameter array given verbatim — for a
     * patch captured out of the mixer, where every value is already known and
     * restating it as overrides of the defaults would only invite a slip.
     */
    fun pluginWithParams(
        type: SnapinType,
        params: FloatArray,
        dryWet: Float = 1f,
        bypassed: Boolean = false,
        oversample: Int = 1,
    ) = add(type, params.copyOf(), dryWet, bypassed, oversample)

    private fun add(type: SnapinType, params: FloatArray, dryWet: Float, bypassed: Boolean, oversample: Int) {
        require(oversample == 1 || oversample == 2 || oversample == 4) {
            "oversample must be 1, 2 or 4, not $oversample"
        }
        plugins.add(PluginEntry(type.ordinal, dryWet, bypassed, oversample, params))
    }

    fun appendJson(sb: StringBuilder) {
        sb.append("{\"gain\":").append(gainDb)
            .append(",\"pan\":").append(pan)
            .append(",\"muted\":").append(muted)
            .append(",\"soloed\":").append(soloed)
            .append(",\"inputEnabled\":").append(inputEnabled && index != BusConfig.MASTER_INDEX)
        // Written only when they differ from the master alone, exactly as the
        // engine saves them, so an unrouted preset is unchanged byte for byte.
        if (index != BusConfig.MASTER_INDEX && sends != BusConfig.DEFAULT_SENDS) {
            sb.append(",\"sends\":[")
            sends.entries.sortedBy { it.key }.forEachIndexed { i, (dst, level) ->
                if (i > 0) sb.append(',')
                sb.append(dst).append(',').append(level)
            }
            sb.append(']')
        }
        sb.append(",\"plugins\":[")
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
    val bypassed: Boolean,
    val oversample: Int,
    val params: FloatArray
) {
    fun appendJson(sb: StringBuilder) {
        sb.append("{\"type\":").append(typeOrdinal)
            .append(",\"bypassed\":").append(bypassed)
            .append(",\"dryWet\":").append(dryWet)
            .append(",\"os\":").append(oversample)
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
        // The native constructors' values (eq_3band.h, filter.h).
        SnapinType.EQ_3BAND -> floatArrayOf(100f, 0f, 0.707f, 1000f, 0f, 1f, 8000f, 0f, 0.707f)
        SnapinType.FILTER -> floatArrayOf(0f, 1000f, 0.707f, 0f, 0f)
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

/** 3-band EQ: low shelf → mid peak → high shelf (eq_3band.h). */
object Eq3P {
    const val LOW_FREQ = 0
    const val LOW_GAIN = 1
    const val LOW_Q = 2
    const val MID_FREQ = 3
    const val MID_GAIN = 4
    const val MID_Q = 5
    const val HIGH_FREQ = 6
    const val HIGH_GAIN = 7
    const val HIGH_Q = 8
}

/** Filter (filter.h). SLOPE n is n + 1 cascaded 12 dB/oct stages. */
object FilterP {
    const val TYPE = 0
    const val CUTOFF = 1
    const val Q = 2
    const val GAIN_DB = 3
    const val SLOPE = 4

    const val LOW_PASS = 0f
    const val BAND_PASS = 1f
    const val HIGH_PASS = 2f
    const val NOTCH = 3f
    const val LOW_SHELF = 4f
    const val PEAK = 5f
    const val HIGH_SHELF = 6f

    const val SLOPE_12 = 0f
    const val SLOPE_24 = 1f
}
