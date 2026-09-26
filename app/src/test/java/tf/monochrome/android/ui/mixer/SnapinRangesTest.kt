package tf.monochrome.android.ui.mixer

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.audio.dsp.SnapinType
import java.io.File

/**
 * Every knob's range, as the table the native engine_stress_test sweeps: each
 * effect is driven to the ends of the ranges the UI can reach, at every sample
 * rate and oversampling factor, and must stay finite. Kotlin owns the ranges,
 * so this holds the table to ParamDefs.kt; after a deliberate range change,
 * regenerate it with
 *
 *   WRITE_SNAPIN_RANGES=$PWD/app/src/test/resources/snapin_ranges.csv \
 *     ./gradlew :app:testDebugUnitTest --tests '*SnapinRangesTest'
 */
class SnapinRangesTest {

    private fun expected(): String = buildString {
        append("# type_ordinal,param_index,min,max — mirrors ParamDefs.kt, checked by SnapinRangesTest\n")
        val types = SnapinType.values()
        for (t in 0 until 36) {
            getParamDefs(types[t]).forEachIndexed { i, def ->
                append(t).append(',').append(i).append(',')
                    .append(def.min).append(',').append(def.max).append('\n')
            }
        }
    }

    @Test
    fun `the stress test's range table is ParamDefs`() {
        val want = expected()
        System.getenv("WRITE_SNAPIN_RANGES")?.let { File(it).writeText(want) }
        val stream = javaClass.classLoader!!.getResourceAsStream("snapin_ranges.csv")
            ?: error("snapin_ranges.csv missing from test resources")
        assertEquals(want, stream.bufferedReader().readText())
    }
}
