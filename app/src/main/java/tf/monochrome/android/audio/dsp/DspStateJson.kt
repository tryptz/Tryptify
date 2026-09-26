package tf.monochrome.android.audio.dsp

import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.ui.mixer.getParamDefs

/**
 * Writes the mixer as the native engine's own state JSON, from the Kotlin
 * mirror.
 *
 * The engine only exists once audio has played, but the mixer screen is open
 * before that — and a mix edited then has to be saved, and handed to the
 * engine when it arrives, exactly as if the engine had written it. So the
 * layout here is the one `DspEngine::getStateJson` produces, field for field
 * and in the same order: the native reader is a positional scanner (it looks
 * for `{"gain":`, then `"pan":`, …), not a JSON parser, and a reordered or
 * spaced-out field is silently skipped. `mixer_state_fixture.json` pins the
 * two together from both sides (DspStateJsonTest, and the host test
 * state_fixture_test that loads it into the engine).
 *
 * Buses are written in index order, the master fifth. A bus routed anywhere
 * but the master alone carries `"sends":[dst,level,…]` before its plugins.
 * Every plugin carries its full parameter array: indices the mirror has no value for take the
 * parameter's default, which ParamDefs holds pinned to the engine's
 * (snapin_defaults.csv).
 */
object DspStateJson {

    fun encode(buses: List<BusConfig>): String {
        val sb = StringBuilder()
        sb.append("{\"buses\":[")
        buses.sortedBy { it.index }.forEachIndexed { i, bus ->
            if (i > 0) sb.append(',')
            sb.append("{\"gain\":").append(num(bus.gainDb))
                .append(",\"pan\":").append(num(bus.pan))
                .append(",\"muted\":").append(bus.muted)
                .append(",\"soloed\":").append(bus.soloed)
                .append(",\"inputEnabled\":").append(bus.inputEnabled)
            // Routes only when not the default (master alone), as the engine
            // writes them: [dst, level, ...] in destination order.
            if (bus.hasCustomSends) {
                sb.append(",\"sends\":[")
                bus.sends.filterValues { it > 0f }.toSortedMap().entries.forEachIndexed { k, (dst, level) ->
                    if (k > 0) sb.append(',')
                    sb.append(dst).append(',').append(num(level))
                }
                sb.append(']')
            }
            sb.append(",\"plugins\":[")
            bus.plugins.forEachIndexed { p, plugin ->
                if (p > 0) sb.append(',')
                val type = plugin.type
                val defs = if (type != null) getParamDefs(type) else emptyList()
                sb.append("{\"type\":").append(plugin.typeOrdinal)
                    .append(",\"bypassed\":").append(plugin.bypassed)
                    .append(",\"dryWet\":").append(num(plugin.dryWet))
                    .append(",\"os\":").append(plugin.oversampling)
                    .append(",\"params\":[")
                defs.indices.forEach { k ->
                    if (k > 0) sb.append(',')
                    sb.append(num(plugin.parameters[k] ?: defs[k].default))
                }
                sb.append("]}")
            }
            sb.append("]}")
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Plain decimal, never exponent form, which the native reader's stof handles either way but a reader of the file should not have to. */
    private fun num(v: Float): String {
        val f = if (v.isFinite()) v else 0f
        return if (f == kotlin.math.floor(f) && kotlin.math.abs(f) < 1e7f) f.toLong().toString()
        else java.math.BigDecimal(f.toDouble()).round(java.math.MathContext(7)).stripTrailingZeros().toPlainString()
    }
}
