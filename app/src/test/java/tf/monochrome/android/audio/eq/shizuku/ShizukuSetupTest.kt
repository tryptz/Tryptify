package tf.monochrome.android.audio.eq.shizuku

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the Shizuku option's setup steps come up before the switch moves. */
class ShizukuSetupTest {

    @Test
    fun `turning it on the first time shows the steps first`() {
        assertTrue(ShizukuSetup.showsStepsFirst(turningOn = true, seen = false))
    }

    @Test
    fun `once read they are not shown again`() {
        assertFalse(ShizukuSetup.showsStepsFirst(turningOn = true, seen = true))
    }

    @Test
    fun `turning it off is never held up`() {
        assertFalse(ShizukuSetup.showsStepsFirst(turningOn = false, seen = false))
        assertFalse(ShizukuSetup.showsStepsFirst(turningOn = false, seen = true))
        assertFalse(ShizukuSetup.showsStepsFirst(turningOn = false, seen = null))
    }

    @Test
    fun `not knowing yet whether they were read shows nothing`() {
        assertFalse(ShizukuSetup.showsStepsFirst(turningOn = true, seen = null))
    }

    @Test
    fun `the app is fetched over https`() {
        assertTrue(ShizukuSetup.DOWNLOAD_URL.startsWith("https://"))
    }
}
