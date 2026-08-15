package tf.monochrome.android.audio.sampler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The sample store's file format.
 *
 * Two things are being checked. That what the sampler writes it can read back
 * bit-for-bit within the resolution it chose — a sample that quietly loses
 * level or gains an offset every time it is saved would be very hard to
 * notice and very annoying once noticed. And that the reader survives the
 * files other tools actually produce, which is mostly a question of whether it
 * copes with chunks it does not recognise sitting between `fmt ` and `data`.
 */
class WavCodecTest {

    private fun roundTrip(left: FloatArray, right: FloatArray?, rate: Int = 48_000): WavCodec.Audio {
        val out = ByteArrayOutputStream()
        WavCodec.write(out, left, right, rate)
        return WavCodec.read(ByteArrayInputStream(out.toByteArray()))
    }

    @Test
    fun `mono round trip preserves samples`() {
        val left = FloatArray(512) { kotlin.math.sin(it * 0.05).toFloat() * 0.8f }
        val audio = roundTrip(left, null)

        assertEquals(512, audio.frames)
        assertEquals(1, audio.channelCount)
        assertEquals(48_000, audio.sampleRate)
        assertNull(audio.right)
        // 24-bit resolution is one part in 8.4 million; anything worse than
        // this would mean the encoder is losing more than it should.
        for (i in left.indices) assertEquals("sample $i", left[i], audio.left[i], 1e-6f)
    }

    @Test
    fun `stereo round trip keeps the channels apart`() {
        val left = FloatArray(256) { it / 256f - 0.5f }
        val right = FloatArray(256) { 0.5f - it / 256f }
        val audio = roundTrip(left, right)

        assertEquals(2, audio.channelCount)
        assertNotNull(audio.right)
        for (i in left.indices) {
            assertEquals(left[i], audio.left[i], 1e-6f)
            assertEquals(right[i], audio.right!![i], 1e-6f)
        }
    }

    @Test
    fun `full scale survives and overs are clamped not wrapped`() {
        // A sample that wraps polarity is a burst of noise; one that clips is
        // merely loud. The first sounds broken, the second sounds like a
        // mistake the user made.
        val left = floatArrayOf(1f, -1f, 2f, -2f, 0f, 0.999f)
        val audio = roundTrip(left, null)

        assertEquals(1f, audio.left[0], 1e-3f)
        assertEquals(-1f, audio.left[1], 1e-3f)
        assertTrue("no wrap on positive over", audio.left[2] > 0.9f)
        assertTrue("no wrap on negative over", audio.left[3] < -0.9f)
        assertEquals(0f, audio.left[4], 1e-6f)
    }

    @Test
    fun `silence stays silent`() {
        val audio = roundTrip(FloatArray(128), null)
        for (v in audio.left) assertEquals(0f, v, 1e-9f)
    }

    @Test
    fun `sample rate is carried through`() {
        for (rate in listOf(44_100, 48_000, 96_000, 192_000)) {
            assertEquals(rate, roundTrip(FloatArray(16), null, rate).sampleRate)
        }
    }

    @Test
    fun `a chunk between fmt and data is skipped`() {
        // LIST/INFO chunks are extremely common and a reader that assumes
        // `data` follows `fmt ` fails on a large share of real files.
        val body = ByteArrayOutputStream()
        WavCodec.write(body, floatArrayOf(0.5f, -0.5f), null, 48_000)
        val original = body.toByteArray()

        val listChunk = "LIST".toByteArray() + intLe(10) + "INFOhello ".toByteArray()
        val spliced = original.copyOfRange(0, 36) + listChunk + original.copyOfRange(36, original.size)
        // Fix up the RIFF size so the file is still self-consistent.
        val patched = spliced.copyOf()
        val riffSize = ByteBuffer.wrap(patched, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        ByteBuffer.wrap(patched, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(riffSize + listChunk.size)

        val audio = WavCodec.read(ByteArrayInputStream(patched))
        assertEquals(2, audio.frames)
        assertEquals(0.5f, audio.left[0], 1e-5f)
        assertEquals(-0.5f, audio.left[1], 1e-5f)
    }

    @Test
    fun `16 bit input is read`() {
        val samples = shortArrayOf(0, 16384, -16384, 32767, -32768)
        val audio = WavCodec.read(ByteArrayInputStream(pcm16Wav(samples, 44_100)))
        assertEquals(5, audio.frames)
        assertEquals(44_100, audio.sampleRate)
        assertEquals(0f, audio.left[0], 1e-4f)
        assertEquals(0.5f, audio.left[1], 1e-4f)
        assertEquals(-0.5f, audio.left[2], 1e-4f)
        assertTrue(audio.left[3] > 0.99f)
        assertEquals(-1f, audio.left[4], 1e-4f)
    }

    @Test
    fun `a non-riff file is rejected rather than misread`() {
        val garbage = ByteArray(64) { it.toByte() }
        try {
            WavCodec.read(ByteArrayInputStream(garbage))
            throw AssertionError("expected an IOException")
        } catch (_: IOException) {
            // expected
        }
    }

    @Test
    fun `a truncated data chunk yields what was actually there`() {
        // Files do get cut short — a save interrupted by a crash, a partial
        // copy. Returning the readable part beats refusing the whole sample.
        val body = ByteArrayOutputStream()
        WavCodec.write(body, FloatArray(100) { 0.25f }, null, 48_000)
        val full = body.toByteArray()
        val cut = full.copyOfRange(0, full.size - 90)

        val audio = WavCodec.read(ByteArrayInputStream(cut))
        assertTrue(audio.frames in 1 until 100)
        assertEquals(0.25f, audio.left[0], 1e-5f)
    }

    @Test
    fun `the frame cap is honoured`() {
        val body = ByteArrayOutputStream()
        WavCodec.write(body, FloatArray(1000) { 0.1f }, null, 48_000)
        val audio = WavCodec.read(ByteArrayInputStream(body.toByteArray()), maxFrames = 64)
        assertEquals(64, audio.frames)
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun shortLe(value: Int): ByteArray =
        ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array()

    /** A minimal 16-bit mono WAV, as any other tool would write one. */
    private fun pcm16Wav(samples: ShortArray, rate: Int): ByteArray {
        val data = ByteArrayOutputStream()
        for (s in samples) data.write(shortLe(s.toInt()))
        val payload = data.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.write(intLe(36 + payload.size))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(intLe(16))
        out.write(shortLe(1))            // PCM
        out.write(shortLe(1))            // mono
        out.write(intLe(rate))
        out.write(intLe(rate * 2))       // byte rate
        out.write(shortLe(2))            // block align
        out.write(shortLe(16))           // bits
        out.write("data".toByteArray())
        out.write(intLe(payload.size))
        out.write(payload)
        return out.toByteArray()
    }
}
