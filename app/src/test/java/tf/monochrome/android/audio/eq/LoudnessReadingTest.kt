package tf.monochrome.android.audio.eq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The native meter's slots, as Kotlin reads them. */
class LoudnessReadingTest {

    // cpp/dsp/meter/loudness_meter.h: LoudnessMeter::kNone.
    private val none = -1000f

    @Test
    fun `nothing measured is no reading at all`() {
        assertNull(LoudnessReading.from(floatArrayOf(none, none, none, none, none)))
    }

    @Test
    fun `the native none sentinel becomes null, real values pass through`() {
        val r = LoudnessReading.from(floatArrayOf(-18f, none, none, none, -3f))!!
        assertEquals(-18f, r.momentary)
        assertNull(r.shortTerm)
        assertNull(r.integrated)
        assertNull(r.range)
        assertEquals(-3f, r.truePeak)
    }

    @Test
    fun `silence is a reading at the floor, not a missing one`() {
        val r = LoudnessReading.from(floatArrayOf(-120f, -120f, none, none, -120f))!!
        assertEquals(LoudnessReading.SILENCE, r.momentary)
    }

    @Test
    fun `a short or non-finite array cannot invent a value`() {
        assertNull(LoudnessReading.from(floatArrayOf()))
        assertNull(LoudnessReading.from(floatArrayOf(Float.NaN, Float.NEGATIVE_INFINITY)))
    }

    @Test
    fun `the Kotlin floor matches the native one`() {
        val header = java.io.File("src/main/cpp/dsp/meter/loudness_meter.h").readText()
        val floor = Regex("""kFloorLufs\s*=\s*(-?[\d.]+)f""").find(header)!!.groupValues[1].toFloat()
        val noneValue = Regex("""kNone\s*=\s*(-?[\d.]+)f""").find(header)!!.groupValues[1].toFloat()
        assertEquals(floor, LoudnessReading.SILENCE)
        assertEquals(none, noneValue)
    }
}
