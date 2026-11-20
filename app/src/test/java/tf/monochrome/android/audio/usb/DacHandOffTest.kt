package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the DAC may be taken from Android's own audio: the claim detaches
 * Android's USB audio driver, and doing that under a live stream is thought
 * to have restarted a phone (2026-11-20).
 */
class DacHandOffTest {

    private class FakeAndroid(var routes: Boolean = true, var busy: Boolean = false) : DacHandOff.AndroidOutput {
        var asked = 0
        override fun routesToUsb(): Boolean { asked++; return routes }
        override fun busy(): Boolean = busy
    }

    private var now = 1_000_000L
    private val android = FakeAndroid()
    private val handOff = DacHandOff(android) { now }

    /** Looks every [step] ms for [ms] ms, as a player waiting would; the last answer. */
    private fun watch(ms: Long, step: Long = 100L): Boolean {
        var answer = handOff.clear()
        var waited = 0L
        while (waited < ms) {
            now += step
            waited += step
            answer = handOff.clear()
        }
        return answer
    }

    @Test
    fun `with USB audio routing off the DAC is taken at once`() {
        android.routes = false
        android.busy = true // music on the speaker is none of the DAC's business
        assertTrue(handOff.clear())
    }

    @Test
    fun `with routing on the DAC waits for Android's quiet time`() {
        assertFalse(handOff.clear())
        assertFalse(watch(DacHandOff.QUIET_MS - 200))
        assertTrue(watch(400))
    }

    @Test
    fun `Android playing keeps the DAC and starts the count again`() {
        assertFalse(watch(3_000))
        android.busy = true
        assertFalse(watch(5_000))
        android.busy = false
        assertFalse(watch(DacHandOff.QUIET_MS - 300))
        assertTrue(watch(600))
    }

    @Test
    fun `a gap without looks starts the count again`() {
        assertTrue(watch(DacHandOff.QUIET_MS + 500))
        // Nobody looked for a minute: Android may have played and just stopped.
        now += 60_000
        assertFalse(handOff.clear())
        assertTrue(watch(DacHandOff.QUIET_MS + 500))
    }

    @Test
    fun `a paused player looking once a second still gets there`() {
        assertTrue(watch(DacHandOff.QUIET_MS + 1_000, step = 1_000))
    }

    @Test
    fun `Android is asked at most every quarter second`() {
        handOff.clear()
        repeat(50) { now += 1; handOff.clear() }
        assertEquals(1, android.asked)
        now += DacHandOff.LOOK_EVERY_MS
        handOff.clear()
        assertEquals(2, android.asked)
    }

    @Test
    fun `routing turned on later counts the quiet from then`() {
        android.routes = false
        assertTrue(watch(10_000))
        android.routes = true
        assertFalse(watch(1_000))
        assertTrue(watch(DacHandOff.QUIET_MS))
    }

    @Test
    fun `the reasons are written down for the log`() {
        android.busy = true
        handOff.clear()
        assertEquals(
            "Android routes USB audio: yes, Android busy: yes, quiet for: no",
            handOff.describe(),
        )
    }
}
