package tf.monochrome.android.audio.stretch

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

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
    fun `zero semitones is inactive`() {
        val p = configured()
        p.setSemitones(0f)
        assertFalse(p.isActive)
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
    fun `queueInput without an engine produces no output rather than crashing`() {
        val p = configured()
        p.setSemitones(5f)
        val buf = ByteBuffer.allocateDirect(1024 * 8).order(ByteOrder.nativeOrder())
        repeat(1024) { buf.putFloat(0.25f); buf.putFloat(0.25f) }
        buf.flip()
        p.queueInput(buf)
        assertEquals(0, p.output.remaining())
    }
}
