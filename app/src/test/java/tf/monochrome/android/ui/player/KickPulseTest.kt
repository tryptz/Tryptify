package tf.monochrome.android.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class KickPulseTest {

    private fun tone(hz: Double, amp: Float = 0.8f) =
        FloatArray(1024) { (amp * sin(2 * PI * hz * it / 48_000)).toFloat() }

    /** The detector listens to the kick's band, not to everything loud. */
    @Test
    fun `a 60 Hz kick reads far stronger than a 5 kHz hi-hat of the same level`() {
        val kick = tone(60.0)
        val hat = tone(5_000.0)
        val kickRms = lowBandRms(kick, kick, kick.size)
        val hatRms = lowBandRms(hat, hat, hat.size)
        assertTrue("kick $kickRms vs hat $hatRms", kickRms > hatRms * 10)
    }

    @Test
    fun `silence is no energy`() {
        val z = FloatArray(1024)
        assertTrue(lowBandRms(z, z, z.size) == 0f)
    }
}
