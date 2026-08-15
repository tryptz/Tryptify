package tf.monochrome.android.audio.sampler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.patterns.CaptureSource

/**
 * The capture tap.
 *
 * The most important assertions here are the ones about the eligibility gate,
 * because they are the ones that describe a rule rather than a behaviour: the
 * gate starts closed, arming is refused without it, and it is re-checked at the
 * point a file would be written. A regression in any of those would be silent
 * — capture would simply start working where it should not.
 */
class SampleCaptureEngineTest {

    private fun engine(allowed: Boolean = true) = SampleCaptureEngine().apply {
        if (allowed) setEligibility(true, "On-device file")
    }

    private fun feed(engine: SampleCaptureEngine, frames: Int, value: Float = 0.5f) {
        val left = FloatArray(frames) { value }
        val right = FloatArray(frames) { -value }
        engine.write(CaptureSource.CLEAN, left, right, frames)
    }

    // ── eligibility ─────────────────────────────────────────────────────

    @Test
    fun `capture is closed by default`() {
        val engine = SampleCaptureEngine()
        assertFalse(engine.eligibility.value.allowed)
        // The failure mode matters: a source whose provenance was never
        // established must be un-capturable, not assumed fine.
        assertFalse(engine.arm(CaptureSource.CLEAN, 48_000))
        assertFalse(engine.isArmed)
    }

    @Test
    fun `arming requires eligibility`() {
        val engine = SampleCaptureEngine()
        engine.setEligibility(false, "Streaming sources can't be saved as samples")
        assertFalse(engine.arm(CaptureSource.CLEAN, 48_000))

        engine.setEligibility(true, "On-device file")
        assertTrue(engine.arm(CaptureSource.CLEAN, 48_000))
        assertTrue(engine.isArmed)
    }

    @Test
    fun `losing eligibility mid-capture throws the capture away`() {
        // A track change during a capture must not be able to smuggle an
        // ineligible source through to a saved file.
        val engine = engine()
        assertTrue(engine.arm(CaptureSource.CLEAN, 48_000))
        feed(engine, 4800)

        engine.setEligibility(false, "Streaming sources can't be saved as samples")
        assertFalse(engine.isArmed)
        assertNull(engine.finish())
    }

    @Test
    fun `finish re-checks eligibility even after a clean capture`() {
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, 48_000)
        feed(engine, 4800)
        engine.stop()
        // Revoked between stop and save.
        engine.setEligibility(false, "Streaming sources can't be saved as samples")
        assertNull(engine.finish())
    }

    // ── capture ─────────────────────────────────────────────────────────

    @Test
    fun `a capture returns deinterleaved audio`() {
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, 48_000)
        feed(engine, 1000, 0.25f)
        engine.stop()

        val captured = engine.finish()
        assertNotNull(captured)
        assertEquals(1000, captured!!.frames)
        assertEquals(48_000, captured.sampleRate)
        assertEquals(CaptureSource.CLEAN, captured.source)
        assertEquals(0.25f, captured.left[0], 1e-6f)
        assertEquals(-0.25f, captured.right[0], 1e-6f)
        assertEquals(0.25f, captured.left[999], 1e-6f)
    }

    @Test
    fun `only the armed tap records`() {
        // Both taps are always in the audio chain; the source selector decides
        // which one is actually writing.
        val engine = engine()
        engine.arm(CaptureSource.PROCESSED, 48_000)
        engine.write(CaptureSource.CLEAN, FloatArray(500) { 1f }, FloatArray(500) { 1f }, 500)
        engine.stop()
        assertNull("the wrong tap wrote nothing", engine.finish())
    }

    @Test
    fun `multiple buffers append`() {
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, 48_000)
        repeat(5) { feed(engine, 200, 0.1f * (it + 1)) }
        engine.stop()

        val captured = engine.finish()!!
        assertEquals(1000, captured.frames)
        assertEquals(0.1f, captured.left[0], 1e-5f)
        assertEquals(0.5f, captured.left[999], 1e-5f)
    }

    @Test
    fun `capture stops itself when the buffer fills`() {
        val engine = engine()
        // One second of capacity at a made-up low rate keeps the test fast.
        assertTrue(engine.arm(CaptureSource.CLEAN, 8_000, maxSeconds = 1))
        feed(engine, 8_000)
        assertFalse("the engine disarms itself rather than growing", engine.isArmed)

        // Further writes past the end are dropped, not written out of bounds.
        feed(engine, 4_000)
        val captured = engine.finish()!!
        assertEquals(8_000, captured.frames)
    }

    @Test
    fun `an odd sample rate falls back to a sane one`() {
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, sampleRate = 0)
        feed(engine, 100)
        engine.stop()
        assertEquals(48_000, engine.finish()!!.sampleRate)
    }

    @Test
    fun `nothing captured yields nothing`() {
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, 48_000)
        engine.stop()
        assertNull(engine.finish())
    }

    @Test
    fun `writing while unarmed is ignored`() {
        val engine = engine()
        feed(engine, 500)
        assertNull(engine.finish())
    }

    @Test
    fun `cancel discards without returning anything`() {
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, 48_000)
        feed(engine, 500)
        engine.cancel()
        assertFalse(engine.isArmed)
        assertNull(engine.finish())
    }

    // ── published state ─────────────────────────────────────────────────

    @Test
    fun `progress reports frames duration and peak`() {
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, 48_000)
        feed(engine, 24_000, 0.75f)
        engine.publishProgress()

        val state = engine.state.value
        assertEquals(24_000, state.frames)
        assertEquals(500L, state.durationMs)
        assertEquals(0.75f, state.peak, 0.01f)
        assertTrue(state.armed)
        assertFalse(state.full)
    }

    @Test
    fun `the peak meter resets between reads so it can fall`() {
        // A meter that only ever rises is not a meter.
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, 48_000)
        feed(engine, 1000, 0.9f)
        engine.publishProgress()
        assertEquals(0.9f, engine.state.value.peak, 0.01f)

        feed(engine, 1000, 0.1f)
        engine.publishProgress()
        assertEquals(0.1f, engine.state.value.peak, 0.01f)
    }

    @Test
    fun `state reports full when the buffer is exhausted`() {
        val engine = engine()
        engine.arm(CaptureSource.CLEAN, 8_000, maxSeconds = 1)
        feed(engine, 8_000)
        engine.publishProgress()
        assertTrue(engine.state.value.full)
    }
}
