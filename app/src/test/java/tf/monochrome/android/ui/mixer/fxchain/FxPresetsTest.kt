package tf.monochrome.android.ui.mixer.fxchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.ui.mixer.getParamDefs
import kotlin.math.abs
import kotlin.math.round

/**
 * 180 presets are typed by hand from research tables, so every value is
 * checked against the parameter it lands on — and the ones sold as safe on a
 * finished master are held to that.
 */
class FxPresetsTest {

    private val all = SnapinType.values().filter { it.ordinal < 36 }

    @Test
    fun `every effect has five presets with distinct names`() {
        for (type in all) {
            val presets = FxPresets.forType(type)
            assertEquals("$type count", 5, presets.size)
            assertEquals("$type names unique", 5, presets.map { it.name }.toSet().size)
            assertTrue("$type blank name", presets.none { it.name.isBlank() })
        }
    }

    @Test
    fun `every value is a real parameter, in range, and on a step where stepped`() {
        for (type in all) {
            val defs = getParamDefs(type)
            for (p in FxPresets.forType(type)) {
                assertTrue("$type ${p.name} dryWet", p.dryWet in 0f..1f)
                for ((i, v) in p.values) {
                    assertTrue("$type ${p.name}: index $i", i in defs.indices)
                    val d = defs[i]
                    assertTrue("$type ${p.name}: ${d.name}=$v outside ${d.min}..${d.max}", v in d.min..d.max)
                    d.steps?.let { steps ->
                        val pos = (v - d.min) / (d.max - d.min) * steps
                        assertTrue("$type ${p.name}: ${d.name}=$v not on a step", abs(pos - round(pos)) < 1e-3)
                    }
                }
            }
        }
    }

    @Test
    fun `each preset changes something and no two are the same`() {
        for (type in all) {
            val defs = getParamDefs(type)
            val defaults = FloatArray(defs.size) { defs[it].default }
            val resolved = FxPresets.forType(type).map { it.name to (it.resolved(defs).toList() + it.dryWet) }
            for ((name, values) in resolved) {
                assertFalse("$type $name is just the defaults", values == defaults.toList() + 1f)
            }
            assertEquals("$type duplicate presets", resolved.size, resolved.map { it.second }.toSet().size)
        }
    }

    @Test
    fun `mastering presets come first`() {
        for (type in all) {
            val flags = FxPresets.forType(type).map { it.mastering }
            assertEquals("$type: a creative preset before a mastering one", flags.sortedDescending(), flags)
        }
    }

    @Test
    fun `the mastering presets are safe on a finished song`() {
        for (p in FxPresets.forType(SnapinType.GAIN).filter { it.mastering }) {
            assertTrue("Gain ${p.name} boosts more than 1.5 dB", (p.values[0] ?: 0f) <= 1.5f)
        }
        val limiter = getParamDefs(SnapinType.LIMITER)
        for (p in FxPresets.forType(SnapinType.LIMITER).filter { it.mastering }) {
            val r = p.resolved(limiter)
            assertTrue("Limiter ${p.name} ceiling ${r[1] + r[4]} dB", r[1] + r[4] <= -0.3f + 1e-4f)
        }
        // Time and modulation effects on a master: the wet signal stays a
        // seasoning, at most a quarter of the output.
        val mixIndex = mapOf(
            SnapinType.REVERB to 11, SnapinType.DELAY to 8, SnapinType.CHORUS to 6,
            SnapinType.ENSEMBLE to 3, SnapinType.FLANGER to 7, SnapinType.PHASER to 7,
            SnapinType.REVERSER to 3, SnapinType.COMB_FILTER to 1,
        )
        for ((type, mix) in mixIndex) {
            val defs = getParamDefs(type)
            for (p in FxPresets.forType(type).filter { it.mastering }) {
                val wet = p.resolved(defs)[mix] / 100f * p.dryWet
                assertTrue("$type ${p.name} is ${wet * 100}% wet", wet <= 0.25f + 1e-4f)
            }
        }
    }

    @Test
    fun `a preset recognises itself and nothing else`() {
        val defs = getParamDefs(SnapinType.REVERB)
        val presets = FxPresets.forType(SnapinType.REVERB)
        val applied = presets[0].resolved(defs).withIndex().associate { it.index to it.value }
        assertTrue(presets[0].matches(defs, applied, presets[0].dryWet))
        assertTrue(presets.drop(1).none { it.matches(defs, applied, 1f) })
        // One knob moved: no longer that preset.
        assertFalse(presets[0].matches(defs, applied + (1 to 5f), 1f))
    }
}
