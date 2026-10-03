package tf.monochrome.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** The speed chip's three units, and the BPM stepper's arithmetic. */
class SpeedUnitTest {

    @Test
    fun `the stored switches pick the unit, BPM first`() {
        assertEquals(SpeedUnit.MULTIPLIER, SpeedUnit.of(semitones = false, bpm = false))
        assertEquals(SpeedUnit.SEMITONES, SpeedUnit.of(semitones = true, bpm = false))
        assertEquals(SpeedUnit.BPM, SpeedUnit.of(semitones = false, bpm = true))
        assertEquals(SpeedUnit.BPM, SpeedUnit.of(semitones = true, bpm = true))
    }

    @Test
    fun `the chip reads in the chosen unit`() {
        assertEquals("1.10x", SpeedUnit.MULTIPLIER.format(1.10f, 128f))
        assertEquals("+2 st", SpeedUnit.SEMITONES.format(PitchRatio.ratioFor(2), 128f))
        // What is heard: the track's tempo times the speed.
        assertEquals("141 BPM", SpeedUnit.BPM.format(1.10f, 128f))
        // Until the tempo is known, the multiplier — never a blank chip.
        assertEquals("1.10x", SpeedUnit.BPM.format(1.10f, null))
    }

    @Test
    fun `the BPM stepper lands on whole numbers`() {
        assertEquals(128f, SpeedUnit.stepBpm(127.6f, +1), 0f)
        assertEquals(127f, SpeedUnit.stepBpm(127.6f, -1), 0f)
        assertEquals(129f, SpeedUnit.stepBpm(128f, +1), 0f)
        assertEquals(127f, SpeedUnit.stepBpm(128f, -1), 0f)
    }

    @Test
    fun `a target tempo becomes a speed within the player's range`() {
        assertEquals(140f / 128f, SpeedUnit.speedFor(140f, 128f), 1e-6f)
        assertEquals(PitchRatio.MAX_SPEED, SpeedUnit.speedFor(1000f, 100f), 0f)
        assertEquals(PitchRatio.MIN_SPEED, SpeedUnit.speedFor(1f, 100f), 0f)
    }
}
