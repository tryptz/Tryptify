package tf.monochrome.android.domain.patterns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The clock, on the JVM.
 *
 * These assertions are deliberately the same numbers the native tests in
 * `cpp/sampler/tests/pattern_engine_test.cpp` assert. The two implementations
 * have to agree or the playhead the user sees drifts away from the audio they
 * hear, and that kind of disagreement is invisible until someone is trying to
 * play in time with it.
 */
class PatternSchedulerTest {

    private val rate = 48_000.0

    @Test
    fun `step length follows tempo and grid`() {
        // 120 BPM: a beat is half a second, a sixteenth is a quarter of that.
        assertEquals(6000.0, PatternScheduler.framesPerStep(rate, 120.0, 4), 1e-9)
        assertEquals(12000.0, PatternScheduler.framesPerStep(rate, 120.0, 2), 1e-9)
        assertEquals(3000.0, PatternScheduler.framesPerStep(rate, 240.0, 4), 1e-9)
        // 44.1 kHz gives a fractional step length, which is exactly why the
        // engine carries the countdown as a double rather than an int.
        assertEquals(5512.5, PatternScheduler.framesPerStep(44_100.0, 120.0, 4), 1e-9)
    }

    @Test
    fun `degenerate tempo and grid do not divide by zero`() {
        assertEquals(0.0, PatternScheduler.framesPerStep(rate, 0.0, 4), 1e-9)
        assertEquals(0.0, PatternScheduler.framesPerStep(rate, 120.0, 0), 1e-9)
        assertEquals(0.0, PatternScheduler.framesPerStep(0.0, 120.0, 4), 1e-9)
    }

    @Test
    fun `swing delays offbeats and leaves downbeats alone`() {
        val fps = 6000.0
        assertEquals(0.0, PatternScheduler.swingDelayFrames(0, 1.0, fps), 1e-9)
        assertEquals(2000.0, PatternScheduler.swingDelayFrames(1, 1.0, fps), 1e-9)
        assertEquals(0.0, PatternScheduler.swingDelayFrames(2, 1.0, fps), 1e-9)
        assertEquals(1000.0, PatternScheduler.swingDelayFrames(1, 0.5, fps), 1e-9)
        assertEquals(0.0, PatternScheduler.swingDelayFrames(1, 0.0, fps), 1e-9)
    }

    @Test
    fun `swing is clamped so a bad value cannot invert the grid`() {
        val fps = 6000.0
        // A swing above 1 would otherwise push an offbeat past the next
        // downbeat, which reorders the pattern rather than shuffling it.
        assertEquals(2000.0, PatternScheduler.swingDelayFrames(1, 4.0, fps), 1e-9)
    }

    @Test
    fun `full swing gives the classic two to one shuffle`() {
        val fps = 6000.0
        assertEquals(8000.0, PatternScheduler.framesToNextStep(0, 1.0, fps), 1e-9)
        assertEquals(4000.0, PatternScheduler.framesToNextStep(1, 1.0, fps), 1e-9)
    }

    @Test
    fun `a swung pair still spans exactly two steps`() {
        // Whatever swing does inside a pair, the pair has to add up — a loop
        // whose bar length changed with the swing amount would drift away from
        // the music it is playing along with.
        val fps = 6000.0
        for (swing in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val pair = PatternScheduler.framesToNextStep(0, swing, fps) +
                PatternScheduler.framesToNextStep(1, swing, fps)
            assertEquals("swing=$swing", 12000.0, pair, 1e-9)
        }
    }

    @Test
    fun `a whole loop takes the same time at any swing`() {
        val fps = 6000.0
        for (swing in listOf(0.0, 0.33, 1.0)) {
            var total = 0.0
            for (step in 0 until 16) total += PatternScheduler.framesToNextStep(step.toLong(), swing, fps)
            assertEquals("swing=$swing", 16 * 6000.0, total, 1e-6)
        }
    }

    @Test
    fun `step never collapses to zero`() {
        // A pathological tempo must still advance, or the audio thread's step
        // loop would fire once per sample.
        assertTrue(PatternScheduler.framesToNextStep(0, 1.0, 0.0) >= 1.0)
        assertTrue(PatternScheduler.framesToNextStep(3, 1.0, 0.5) >= 1.0)
    }

    @Test
    fun `wrapping folds into range including negatives`() {
        assertEquals(0, PatternScheduler.wrapStep(0, 16))
        assertEquals(15, PatternScheduler.wrapStep(15, 16))
        assertEquals(0, PatternScheduler.wrapStep(16, 16))
        assertEquals(1, PatternScheduler.wrapStep(17, 16))
        // Recording quantization produces negatives whenever a hit lands just
        // before step 0 and snaps backwards.
        assertEquals(15, PatternScheduler.wrapStep(-1, 16))
        assertEquals(15, PatternScheduler.wrapStep(-17, 16))
        assertEquals(11, PatternScheduler.wrapStep(-1, 12))
        assertEquals(0, PatternScheduler.wrapStep(5, 0))
    }

    @Test
    fun `every supported length wraps correctly`() {
        for (length in listOf(4, 8, 12, 16, 32, 64)) {
            for (step in 0 until length * 3) {
                assertEquals(step % length, PatternScheduler.wrapStep(step.toLong(), length))
            }
        }
    }

    @Test
    fun `quantization snaps to the nearer step`() {
        val fps = 6000.0
        assertEquals(4L, PatternScheduler.quantizeToStep(4, 100.0, fps, 1))
        assertEquals(4L, PatternScheduler.quantizeToStep(4, 2999.0, fps, 1))
        assertEquals(5L, PatternScheduler.quantizeToStep(4, 3001.0, fps, 1))
        assertEquals(5L, PatternScheduler.quantizeToStep(4, 5900.0, fps, 1))
    }

    @Test
    fun `quantization to a coarser grid lands on the grid`() {
        val fps = 6000.0
        // Quantum 4 on a sixteenth grid is quarter-note quantize.
        assertEquals(4L, PatternScheduler.quantizeToStep(5, 100.0, fps, 4))
        assertEquals(8L, PatternScheduler.quantizeToStep(6, 3000.0, fps, 4))
        assertEquals(8L, PatternScheduler.quantizeToStep(7, 0.0, fps, 4))
        assertEquals(0L, PatternScheduler.quantizeToStep(1, 0.0, fps, 4))
    }

    @Test
    fun `quantization treats zero quantum as one`() {
        val fps = 6000.0
        assertEquals(
            PatternScheduler.quantizeToStep(4, 3001.0, fps, 1),
            PatternScheduler.quantizeToStep(4, 3001.0, fps, 0),
        )
    }

    @Test
    fun `beats and bars are identified from the pattern meter`() {
        val pattern = Pattern(bankSlot = 0, lengthSteps = 32, stepsPerBeat = 4, beatsPerBar = 4)
        assertTrue(PatternScheduler.isBeat(pattern, 0))
        assertTrue(PatternScheduler.isBeat(pattern, 4))
        assertFalse(PatternScheduler.isBeat(pattern, 5))
        assertTrue(PatternScheduler.isBarStart(pattern, 0))
        assertTrue(PatternScheduler.isBarStart(pattern, 16))
        assertFalse(PatternScheduler.isBarStart(pattern, 4))

        assertEquals(1, PatternScheduler.barOfStep(pattern, 0))
        assertEquals(1, PatternScheduler.barOfStep(pattern, 15))
        assertEquals(2, PatternScheduler.barOfStep(pattern, 16))
    }

    @Test
    fun `an odd length still reports a whole number of bars`() {
        // 12 steps on a sixteenth grid is three quarters of a bar; the readout
        // must say "1", not "0" and not a fraction.
        val pattern = Pattern(bankSlot = 0, lengthSteps = 12)
        assertEquals(1, pattern.barCount)
        assertEquals(1, PatternScheduler.barOfStep(pattern, 11))

        assertEquals(4, Pattern(bankSlot = 0, lengthSteps = 64).barCount)
        assertEquals(2, Pattern(bankSlot = 0, lengthSteps = 20).barCount)
    }

    @Test
    fun `loop duration follows tempo and the pattern override`() {
        val sixteen = Pattern(bankSlot = 0, lengthSteps = 16)
        assertEquals(2000L, PatternScheduler.loopDurationMs(sixteen, 120f))
        assertEquals(1000L, PatternScheduler.loopDurationMs(sixteen, 240f))

        val half = Pattern(bankSlot = 0, lengthSteps = 8)
        assertEquals(1000L, PatternScheduler.loopDurationMs(half, 120f))

        // An override wins over the transport tempo.
        val slower = sixteen.copy(bpmOverride = 60f)
        assertEquals(4000L, PatternScheduler.loopDurationMs(slower, 120f))
    }
}
