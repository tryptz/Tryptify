package tf.monochrome.android.ui.discover.galaxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bands are read the way MilkDrop reads them: against their own running
 * average, so a steady song rests at 1 whatever its level, and only a rise
 * above that moves the galaxy.
 */
class AudioBandsTest {

    private fun bins(bassDb: Float, restDb: Float = -30f) = FloatArray(256) { i ->
        if (i in AudioBands.BASS_FROM..AudioBands.BASS_TO) bassDb else restDb
    }

    private fun AudioBands.run(seconds: Float, bins: FloatArray) {
        repeat((seconds * 60).toInt()) { update(bins, 1f / 60f) }
    }

    @Test
    fun `silence moves nothing`() {
        val bands = AudioBands()
        bands.run(5f, FloatArray(256) { -200f })
        assertEquals(0f, bands.bassLift, 1e-3f)
        assertEquals(0f, bands.midLift, 1e-3f)
        assertEquals(0f, bands.trebLift, 1e-3f)
    }

    @Test
    fun `a steady level rests at one, at any loudness`() {
        for (db in listOf(-20f, 0f, 12f)) {
            val bands = AudioBands()
            bands.run(30f, bins(db))
            assertEquals("at $db dB", 1f, bands.bassAtt, 0.05f)
            assertEquals("at $db dB", 0f, bands.bassLift, 0.15f)
        }
    }

    @Test
    fun `a kick above the song's usual level lifts the bass`() {
        val bands = AudioBands()
        bands.run(20f, bins(0f))
        bands.run(0.3f, bins(10f))
        assertTrue("lift ${bands.bassLift}", bands.bassLift > 0.5f)
        assertEquals("mid untouched", 0f, bands.midLift, 0.15f)
    }

    @Test
    fun `quiet settles everything back`() {
        val bands = AudioBands()
        bands.run(20f, bins(0f))
        bands.run(0.3f, bins(10f))
        repeat(120) { bands.quiet(1f / 60f) }
        assertTrue(bands.bassAtt < 0.01f)
    }
}
