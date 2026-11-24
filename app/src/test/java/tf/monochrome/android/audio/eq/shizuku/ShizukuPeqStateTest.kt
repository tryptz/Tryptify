package tf.monochrome.android.audio.eq.shizuku

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.eq.SystemEqCurve
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.ToneControls

/**
 * The Shizuku system-wide mode outside its service: what the settings row
 * says is in the way, what it is told to play, and who corrects the app's
 * own music while it runs.
 */
class ShizukuPeqStateTest {

    private fun state(
        wanted: Boolean = true,
        sdk: Int = 34,
        installed: Boolean = true,
        binderAlive: Boolean = true,
        permitted: Boolean = true,
        serviceRunning: Boolean = false,
        failed: Boolean = false,
    ) = ShizukuPeqState.resolve(wanted, sdk, installed, binderAlive, permitted, serviceRunning, failed)

    @Test
    fun `not asked for, nothing else is looked at`() {
        assertEquals(ShizukuPeqState.OFF, state(wanted = false, sdk = 26, installed = false, binderAlive = false, permitted = false))
        assertEquals(ShizukuPeqState.OFF, state(wanted = false, serviceRunning = true))
    }

    @Test
    fun `the first thing in the way is the one named`() {
        // An old Android, whatever else is true.
        assertEquals(ShizukuPeqState.UNSUPPORTED, state(sdk = 32, installed = false, binderAlive = false))
        assertEquals(ShizukuPeqState.UNSUPPORTED, state(sdk = 32, serviceRunning = true))
        assertEquals(33, ShizukuPeqState.MIN_SDK)
        // Then Shizuku itself: not there, or there and not started.
        assertEquals(ShizukuPeqState.NOT_INSTALLED, state(installed = false, binderAlive = false, permitted = false))
        assertEquals(ShizukuPeqState.NOT_RUNNING, state(binderAlive = false, permitted = false))
        // Then its permission.
        assertEquals(ShizukuPeqState.NEEDS_PERMISSION, state(permitted = false))
        // Then the service.
        assertEquals(ShizukuPeqState.STARTING, state())
        assertEquals(ShizukuPeqState.ACTIVE, state(serviceRunning = true))
        assertEquals(ShizukuPeqState.FAILED, state(failed = true))
    }

    @Test
    fun `Shizuku answering is Shizuku running, found installed or not`() {
        assertEquals(ShizukuPeqState.NEEDS_PERMISSION, state(installed = false, permitted = false))
        assertEquals(ShizukuPeqState.ACTIVE, state(installed = false, serviceRunning = true))
    }

    @Test
    fun `a service that failed is not called running, and what it said means nothing without Shizuku`() {
        assertEquals(ShizukuPeqState.ACTIVE, state(serviceRunning = true, failed = true))
        assertEquals(ShizukuPeqState.NOT_RUNNING, state(binderAlive = false, serviceRunning = true))
        assertEquals(ShizukuPeqState.NEEDS_PERMISSION, state(permitted = false, failed = true))
    }

    private fun peak(freq: Float, gain: Float) =
        EqBand(id = freq.toInt(), type = FilterType.PEAKING, freq = freq, gain = gain, q = 1.4f, enabled = true)

    @Test
    fun `what it is told to play is the correction itself, an ear at a time`() {
        val left = listOf(peak(3150f, -6f), peak(100f, 0f), peak(500f, 2f).copy(enabled = false))
        val right = listOf(peak(8000f, -4f))
        val tone = ToneControls(bassGainDb = 3f)
        val p = SystemEqCurve.parametric(left, right, stereo = true, tone, 0f, autoPreamp = true)!!
        // The left ear: its one live band and the bass shelf. The idle ones are not sent.
        assertEquals(2 * SystemPeqDsp.FIELDS, p.left.size)
        assertArrayEquals(floatArrayOf(SystemPeqDsp.TYPE_PEAKING.toFloat(), 3150f, -6f, 1.4f), p.left.copyOfRange(0, 4), 0f)
        assertEquals(SystemPeqDsp.TYPE_LOW_SHELF.toFloat(), p.left[4], 0f)
        assertEquals(3f, p.left[6], 0f)
        // The right ear: its own band, and the same shelf.
        assertArrayEquals(floatArrayOf(SystemPeqDsp.TYPE_PEAKING.toFloat(), 8000f, -4f, 1.4f), p.right.copyOfRange(0, 4), 0f)
        assertEquals(2 * SystemPeqDsp.FIELDS, p.right.size)
        // The automatic preamp makes room for the shelf's boost.
        assertTrue("preamp ${p.preampDb}", p.preampDb < -1f)
        // Outside 2-channel mode the left plays in both.
        val mono = SystemEqCurve.parametric(left, right, stereo = false, tone, 0f, autoPreamp = true)!!
        assertArrayEquals(mono.left, mono.right, 0f)
    }

    @Test
    fun `with nothing to play there is nothing to take from other apps`() {
        assertNull(SystemEqCurve.parametric(emptyList(), emptyList(), false, ToneControls(), 0f, autoPreamp = true))
        assertNull(SystemEqCurve.parametric(listOf(peak(1000f, 0f)), emptyList(), true, ToneControls(), 0f, autoPreamp = true))
        // A manual preamp alone is something.
        val turnedDown = SystemEqCurve.parametric(emptyList(), emptyList(), false, ToneControls(), -3f, autoPreamp = false)!!
        assertEquals(-3f, turnedDown.preampDb, 0f)
        assertEquals(0, turnedDown.left.size)
    }

    @Test
    fun `the same curve twice is the same curve, so it is sent once`() {
        val a = SystemEqCurve.parametric(listOf(peak(3150f, -6f)), emptyList(), false, ToneControls(), 0f, true)!!
        val b = SystemEqCurve.parametric(listOf(peak(3150f, -6f)), emptyList(), false, ToneControls(), 0f, true)!!
        val c = SystemEqCurve.parametric(listOf(peak(3150f, -5f)), emptyList(), false, ToneControls(), 0f, true)!!
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, c)
    }

    @Test
    fun `while it plays, the app's own equalizer corrects the app's own music`() {
        // It leaves the app's playback out of what it takes, so nothing else would.
        assertFalse(SystemEqCurve.coversOwnPlayback(systemWide = true, exclusiveDacStreaming = false, shizukuActive = true))
        // Not playing (Shizuku missing, say), the standard effect has the app's music as before.
        assertTrue(SystemEqCurve.coversOwnPlayback(systemWide = true, exclusiveDacStreaming = false, shizukuActive = false))
        assertFalse(SystemEqCurve.coversOwnPlayback(systemWide = false, exclusiveDacStreaming = false, shizukuActive = false))
    }

    @Test
    fun `the service's words are the ones the app listens for`() {
        assertEquals("running", SystemPeqUserService.STATUS_RUNNING)
        assertEquals("stopped", SystemPeqUserService.STATUS_STOPPED)
        assertTrue(SystemPeqUserService.STATUS_ERROR.startsWith("error"))
        assertEquals(48000, SystemPeqUserService.SAMPLE_RATE)
    }
}
