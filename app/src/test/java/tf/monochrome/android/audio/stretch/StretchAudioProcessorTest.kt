package tf.monochrome.android.audio.stretch

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import tf.monochrome.android.audio.golden.PcmCompare
import tf.monochrome.android.audio.golden.PcmSignals

/**
 * The native library is not present in a JVM test, which is exactly the
 * condition these cover: an effect that cannot load must disappear, not break
 * playback. The engine's own accuracy is pinned by the host test in
 * `cpp/stretch/tests/stretch_precision_test.cpp`, where the engine exists.
 */
class StretchAudioProcessorTest {

    private fun configured(): StretchAudioProcessor {
        val p = StretchAudioProcessor()
        p.configure(AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        p.flush()
        return p
    }

    @Test
    fun `is inactive without the native library`() {
        val p = configured()
        p.setSemitones(7f)
        assertFalse("must not claim to be active when it cannot process", p.isActive)
    }

    @Test
    fun `reports no latency when not engaged`() {
        val p = configured()
        assertEquals(0, p.latencyFrames())
        p.setSemitones(7f)
        // Still zero: there is no engine to be late.
        assertEquals(0, p.latencyFrames())
    }

    @Test
    fun `membership in the chain does not depend on the current pitch`() {
        // This used to assert the opposite, and the opposite was the bug.
        // Media3 decides which processors run when it builds the pipeline and
        // consults isActive only at configure and flush, so a processor that
        // calls itself inactive at zero pitch is left out of every track that
        // starts there -- and the pitch buttons then have nothing to drive
        // until the next seek or track change.
        //
        // Without the native library the answer is false at any pitch, which is
        // all a JVM test can see; what it can hold is that the two answers
        // agree, so no future edit reintroduces a pitch-dependent gate.
        val p = configured()
        p.setSemitones(0f)
        val atZero = p.isActive
        p.setSemitones(7f)
        assertEquals("isActive must not change with the pitch", atZero, p.isActive)
    }

    @Test
    fun `semitones are clamped and degenerate values ignored`() {
        val p = configured()
        p.setSemitones(99f)
        assertEquals(24f, p.getSemitones(), 1e-6f)
        p.setSemitones(-99f)
        assertEquals(-24f, p.getSemitones(), 1e-6f)
        p.setSemitones(Float.NaN)
        assertEquals("NaN must not overwrite a good value", -24f, p.getSemitones(), 1e-6f)
    }

    @Test
    fun `queueInput without an engine passes audio on rather than dropping it`() {
        // Previously this asserted no output at all, which was harmless only
        // because the processor also excluded itself from the pipeline whenever
        // the engine was missing, so nothing ever called it. Now that
        // membership no longer depends on the pitch, the one way to reach this
        // is an engine that failed to construct -- and there, swallowing the
        // buffer would mute playback outright. Silent, never silencing.
        val p = configured()
        p.setSemitones(5f)
        val buf = ByteBuffer.allocateDirect(1024 * 8).order(ByteOrder.nativeOrder())
        repeat(1024) { buf.putFloat(0.25f); buf.putFloat(0.25f) }
        buf.flip()
        p.queueInput(buf)
        assertEquals(1024 * 8, p.output.remaining())
    }

    @Test
    fun `passes audio through untouched when it is not transposing`() {
        // The bug this pins: the processor used to report itself inactive at
        // zero pitch, so Media3 left it out of the pipeline for every track that
        // started there -- and since the pipeline is only rebuilt at configure
        // and flush, pressing the pitch buttons mid-track set a field nothing
        // read. It has to stay in the chain and be inaudible instead, which
        // means passing audio through sample for sample.
        val p = configured()
        val frames = 1024
        val source = PcmSignals.interleave(
            PcmSignals.sine(frames, 440.0, 48000, amplitude = 0.8),
            PcmSignals.sine(frames, 660.0, 48000, amplitude = 0.3),
        )
        val input = ByteBuffer.allocateDirect(source.size * 4).order(ByteOrder.nativeOrder())
        source.forEach { input.putFloat(it) }
        input.flip()

        p.queueInput(input)
        val out = p.output
        val got = FloatArray(out.remaining() / 4) { out.getFloat(it * 4) }

        assertEquals("every frame must come back", source.size, got.size)
        assertTrue(
            "a bypassing effect must be bit-transparent, not merely close",
            PcmCompare.isBitIdentical(source, got),
        )
        assertEquals("the input must be consumed", 0, input.remaining())
    }
}
