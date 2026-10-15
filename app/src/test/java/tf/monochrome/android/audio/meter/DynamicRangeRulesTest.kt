package tf.monochrome.android.audio.meter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Only a whole, clean play of a track is its dynamic range. */
class DynamicRangeRulesTest {

    private fun reading(dr: Float? = 9.37f, seconds: Float = 240f, blocks: Int = 80) = DynamicRangeReading(dr, seconds, blocks)

    @Test
    fun `a whole clean play is kept, to a tenth of a dB`() {
        assertEquals(9.4f, DynamicRangeRules.accept(reading(), 240, clean = true)!!, 0f)
    }

    @Test
    fun `a seek spoils it`() {
        assertNull(DynamicRangeRules.accept(reading(), 240, clean = false))
    }

    @Test
    fun `a play that stopped early, or ran long, is not the track`() {
        // A skip at 3:00 of a 4:00 track, or a crossfade's last 8 s on another player.
        assertNull(DynamicRangeRules.accept(reading(seconds = 180f), 240, clean = true))
        assertNull(DynamicRangeRules.accept(reading(seconds = 232f), 240, clean = true))
        assertNull(DynamicRangeRules.accept(reading(seconds = 250f), 240, clean = true))
        // A gapless join moves a few hundred ms between tracks: still whole.
        assertEquals(9.4f, DynamicRangeRules.accept(reading(seconds = 239.4f), 240, clean = true)!!, 0f)
        assertEquals(9.4f, DynamicRangeRules.accept(reading(seconds = 241.5f), 240, clean = true)!!, 0f)
        // A long track's tagged length is an estimate: 2 % of an hour is 72 s.
        assertEquals(9.4f, DynamicRangeRules.accept(reading(seconds = 3560f), 3600, clean = true)!!, 0f)
    }

    @Test
    fun `no reading, no blocks, no length or a reading no master has is nothing`() {
        assertNull(DynamicRangeRules.accept(null, 240, clean = true))
        assertNull(DynamicRangeRules.accept(reading(dr = null), 240, clean = true))
        assertNull(DynamicRangeRules.accept(reading(blocks = 0), 240, clean = true))
        assertNull(DynamicRangeRules.accept(reading(), 0, clean = true))
        assertNull(DynamicRangeRules.accept(reading(dr = 55f), 240, clean = true))
        assertNull(DynamicRangeRules.accept(reading(dr = -9f), 240, clean = true))
    }

    @Test
    fun `at a gapless join to another layout, the reading from before the format change is the track's`() {
        // The next track's first buffer (5.1 after stereo) reset the meter.
        val current = reading(dr = null, seconds = 0.3f, blocks = 0)
        val before = reading(dr = 11.04f, seconds = 239.8f)
        assertEquals(11f, DynamicRangeRules.acceptEither(current, before, 240, clean = true)!!, 0f)
        // A whole current reading wins; a partial earlier one is no fallback.
        assertEquals(9.4f, DynamicRangeRules.acceptEither(reading(), before, 240, clean = true)!!, 0f)
        assertNull(DynamicRangeRules.acceptEither(current, reading(seconds = 120f), 240, clean = true))
        assertNull(DynamicRangeRules.acceptEither(current, before, 240, clean = false))
    }

    @Test
    fun `the native slots read back, its no-reading as null`() {
        assertEquals(DynamicRangeReading(8.5f, 12f, 4), DynamicRangeReading.from(floatArrayOf(8.5f, 12f, 4f)))
        // cpp's kNone is -1000.
        assertEquals(DynamicRangeReading(null, 2f, 0), DynamicRangeReading.from(floatArrayOf(-1000f, 2f, 0f)))
        assertEquals(DynamicRangeReading(null, 0f, 0), DynamicRangeReading.from(floatArrayOf(Float.NaN, Float.NaN, -1f)))
        assertEquals(DynamicRangeReading(null, 0f, 0), DynamicRangeReading.from(FloatArray(0)))
    }
}
