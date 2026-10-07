package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exclusive USB path's volume. A review of the app warned that a DAC
 * started at a dangerous level: there was no volume on that path a user
 * could reach, and 24-bit output ignored the one there was. These pin the
 * level a session starts at, the dB scale the slider and keys move on, and
 * the ramp that fades a stream in and keeps a change from clicking.
 */
class BypassVolumeTest {

    @Test
    fun `a session starts quiet, whatever the last one ended at`() {
        val volume = BypassVolumeController()
        volume.setLevelDb(0f)
        volume.startSession()
        assertEquals(BypassVolumeController.SAFE_START_DB, volume.levelDb.value)
        // -24 dB is about a sixteenth of full scale.
        assertEquals(0.063f, volume.getVolume(), 0.001f)
    }

    @Test
    fun `0 dB is exactly unity, so bit-perfect stays bit-perfect`() {
        val volume = BypassVolumeController()
        volume.setLevelDb(0f)
        assertEquals(1f, volume.getVolume())
        assertEquals(1f, BypassVolumeController.dbToGain(0f))
    }

    @Test
    fun `the bottom of the scale is silence`() {
        assertEquals(0f, BypassVolumeController.dbToGain(BypassVolumeController.MIN_DB))
        assertEquals(0f, BypassVolumeController.dbToGain(-90f))
        assertEquals(BypassVolumeController.MIN_DB, BypassVolumeController.gainToDb(0f))
    }

    @Test
    fun `the DAC level and the player's gain multiply, and neither can boost`() {
        val volume = BypassVolumeController()
        volume.setLevelDb(-6f)
        volume.setVolume(0.5f) // a crossfade halfway down
        assertEquals(0.5012f * 0.5f, volume.getVolume(), 0.001f)
        volume.setLevelDb(12f)
        volume.setVolume(3f)
        assertEquals(1f, volume.getVolume())
        // The DAC level alone, for a crossfade's outgoing tail.
        assertEquals(1f, volume.getDacGain())
    }

    @Test
    fun `the slider is even in dB, end to end`() {
        assertEquals(BypassVolumeController.MIN_DB, BypassVolumeController.positionToDb(0f))
        assertEquals(0f, BypassVolumeController.positionToDb(1f))
        assertEquals(-30f, BypassVolumeController.positionToDb(0.5f))
        assertEquals(0.6f, BypassVolumeController.dbToPosition(BypassVolumeController.SAFE_START_DB), 0.0001f)
    }

    @Test
    fun `a key press moves 2 dB and lands on whole marks`() {
        assertEquals(-22f, BypassVolumeController.stepFrom(-24f, 1))
        assertEquals(-26f, BypassVolumeController.stepFrom(-24f, -1))
        // Off a mark (left there by the slider): the first press goes to the next one that way.
        assertEquals(-22f, BypassVolumeController.stepFrom(-23.4f, 1))
        assertEquals(-24f, BypassVolumeController.stepFrom(-23.4f, -1))
        // Never past either end.
        assertEquals(0f, BypassVolumeController.stepFrom(0f, 1))
        assertEquals(BypassVolumeController.MIN_DB, BypassVolumeController.stepFrom(BypassVolumeController.MIN_DB, -3))
        // Out of silence in one press.
        assertEquals(BypassVolumeController.MIN_DB + 2f, BypassVolumeController.stepFrom(BypassVolumeController.MIN_DB, 1))
    }

    @Test
    fun `unmuting goes back to the level the mute came from, never louder`() {
        val volume = BypassVolumeController()
        volume.setLevelDb(-18f)
        volume.setMuted(true)
        assertEquals(0f, volume.getVolume())
        // Muting twice must not remember silence as the level to return to.
        volume.setMuted(true)
        volume.setMuted(false)
        assertEquals(-18f, volume.levelDb.value)
        // Unmuting when not muted changes nothing.
        volume.setLevelDb(-10f)
        volume.setMuted(false)
        assertEquals(-10f, volume.levelDb.value)
        // A new session forgets the last one's level, for unmuting too.
        volume.setLevelDb(-2f)
        volume.setMuted(true)
        volume.startSession()
        volume.setMuted(true)
        volume.setMuted(false)
        assertEquals(BypassVolumeController.SAFE_START_DB, volume.levelDb.value)
    }

    @Test
    fun `a stream fades in over half a second, at any level`() {
        val rate = 48_000
        for (db in listOf(-24f, -6f, 0f)) {
            val target = BypassVolumeController.dbToGain(db)
            val rise = GainRamp.risePerFrame(target, rate)
            val fall = GainRamp.fallPerFrame(rate)
            val quarter = GainRamp.after(0f, target, rate / 4, rise, fall)
            assertEquals("halfway up at 0.25 s for $db dB", target / 2, quarter, target * 0.01f)
            assertEquals("there at 0.5 s for $db dB", target, GainRamp.after(0f, target, rate / 2, rise, fall), 1e-6f)
            // And never past it.
            assertEquals(target, GainRamp.after(0f, target, rate * 5, rise, fall), 1e-6f)
        }
    }

    @Test
    fun `turning down takes 30 ms, and the ramp is exact at any frame`() {
        val rate = 48_000
        val fall = GainRamp.fallPerFrame(rate)
        val rise = GainRamp.risePerFrame(0f, rate)
        // 30 ms at 48 kHz, in integers: 0.03f * rate is 1439.99 and would truncate.
        val down = GainRamp.after(1f, 0f, rate * 3 / 100, rise, fall)
        assertEquals(0f, down, 1e-4f)
        // A partial write resumes from where the frames it wrote got to.
        val split = GainRamp.after(GainRamp.after(1f, 0.25f, 300, rise, fall), 0.25f, 400, rise, fall)
        assertEquals(GainRamp.after(1f, 0.25f, 700, rise, fall), split, 1e-6f)
        assertTrue(down < 1f)
    }
}
