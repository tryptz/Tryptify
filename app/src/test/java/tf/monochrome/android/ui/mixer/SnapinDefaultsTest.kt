package tf.monochrome.android.ui.mixer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.SnapinType

/**
 * The default a knob shows for an untouched parameter must be the one the
 * effect runs with. Both sides are held to one table: the native host test
 * snapin_defaults_test checks the engine against snapin_defaults.csv, and this
 * checks ParamDefs.kt against the same file. Eleven parameters across eight
 * effects had drifted apart before it existed.
 */
class SnapinDefaultsTest {

    private val table: Map<Pair<Int, Int>, Float> by lazy {
        val stream = javaClass.classLoader!!.getResourceAsStream("snapin_defaults.csv")
            ?: error("snapin_defaults.csv missing from test resources")
        stream.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val (t, i, v) = line.split(',')
                (t.toInt() to i.toInt()) to v.toFloat()
            }
    }

    @Test
    fun `every knob's default is the engine's default`() {
        val types = SnapinType.values()
        var compared = 0
        for (t in 0 until 36) {
            val defs = getParamDefs(types[t])
            val native = table.keys.count { it.first == t }
            assertEquals("${types[t]} parameter count", native, defs.size)
            defs.forEachIndexed { i, def ->
                val want = table[t to i]!!
                assertEquals("${types[t]} ${def.name}", want, def.default, 1e-3f * (1f + kotlin.math.abs(want)))
                compared++
            }
        }
        assertTrue(compared > 200)
    }
}
