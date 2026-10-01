package tf.monochrome.android.audio.dsp

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * The Atmos upmix, run the way the app runs it: [UpmixProcessor] into the
 * real [DownmixProcessor] fold. Most listeners hear that fold, so the upmix
 * is held to giving the song back through it — exactly for what it steers
 * (bass, centre, hard pans) and at the same power for what it decorrelates
 * (ambience).
 */
class StereoUpmixerTest {

    private val rate = 48000
    private val ch = StereoUpmixer.CHANNELS

    /** Upmix [left]/[right] to 16 channels through the processor. */
    private fun upmix(left: FloatArray, right: FloatArray, enabled: Boolean = true): FloatArray {
        val p = UpmixProcessor().apply { setEnabled(true) }
        val out = p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(ch, out.channelCount)
        p.setEnabled(enabled)
        p.flush()
        val result = FloatArray(left.size * ch)
        var done = 0
        // In blocks, as the pipeline feeds it, so state carries across them.
        while (done < left.size) {
            val n = minOf(BLOCK, left.size - done)
            val buf = ByteBuffer.allocateDirect(n * 8).order(ByteOrder.nativeOrder())
            for (i in 0 until n) { buf.putFloat(left[done + i]); buf.putFloat(right[done + i]) }
            buf.flip()
            p.queueInput(buf)
            val o = p.getOutput()
            for (s in 0 until n * ch) result[done * ch + s] = o.getFloat(o.position() + s * 4)
            done += n
        }
        return result
    }

    /** The app's own fold of a 16-channel block to stereo, interleaved. */
    private fun fold(upmixed: FloatArray): FloatArray {
        val p = DownmixProcessor(crossfeed = null, placement = null)
        p.configure(AudioFormat(rate, ch, C.ENCODING_PCM_FLOAT))
        p.flush()
        val buf = ByteBuffer.allocateDirect(upmixed.size * 4).order(ByteOrder.nativeOrder())
        for (s in upmixed) buf.putFloat(s)
        buf.flip()
        p.queueInput(buf)
        val o = p.getOutput()
        return FloatArray(o.remaining() / 4) { o.getFloat(o.position() + it * 4) }
    }

    private fun sine(hz: Float, n: Int, amp: Float = 0.5f) =
        FloatArray(n) { amp * sin(2.0 * PI * hz * it / rate).toFloat() }

    private fun channel(interleaved: FloatArray, c: Int, channels: Int, from: Int = 0) =
        FloatArray(interleaved.size / channels - from) { interleaved[(from + it) * channels + c] }

    private fun rms(x: FloatArray): Float = sqrt(x.fold(0.0) { a, v -> a + v * v } / x.size).toFloat()

    private fun db(x: Float) = 20f * log10(x.coerceAtLeast(1e-9f))

    private fun maxError(a: FloatArray, b: FloatArray): Float {
        var m = 0f
        for (i in a.indices) m = maxOf(m, kotlin.math.abs(a[i] - b[i]))
        return m
    }

    @Test
    fun `a centred source folds back to itself and leans on the centre speaker`() {
        val n = rate // one second
        val vocal = sine(440f, n)
        val up = upmix(vocal, vocal)
        val back = fold(up)
        assertTrue(
            "fold of a centred source should be the source, error ${maxError(channel(back, 0, 2), vocal)}",
            maxError(channel(back, 0, 2), vocal) < 1e-4f,
        )
        assertTrue(maxError(channel(back, 1, 2), vocal) < 1e-4f)
        // Once the steering has settled, most of it is in the centre.
        val settle = n / 2
        val centre = rms(channel(up, StereoUpmixer.FC, ch, settle))
        val front = rms(channel(up, StereoUpmixer.FL, ch, settle))
        assertTrue("centre ${db(centre)} dB vs front ${db(front)} dB", centre > front)
        // And nothing of a mono source is ambience.
        assertTrue(rms(channel(up, StereoUpmixer.TFL, ch, settle)) < 1e-4f)
    }

    @Test
    fun `a hard-panned part folds back to itself and stays in the front-left speaker`() {
        val n = rate
        val guitar = sine(330f, n)
        val silent = FloatArray(n)
        val up = upmix(guitar, silent)
        val back = fold(up)
        assertTrue(maxError(channel(back, 0, 2), guitar) < 1e-4f)
        assertTrue(maxError(channel(back, 1, 2), silent) < 1e-4f)
        val settle = n / 2
        // Not smeared into the centre, the right, or the room.
        assertTrue(rms(channel(up, StereoUpmixer.FC, ch, settle)) < 1e-3f)
        assertTrue(rms(channel(up, StereoUpmixer.FL + 1, ch, settle)) < 1e-3f)
        for (c in listOf(StereoUpmixer.SL, StereoUpmixer.BL, StereoUpmixer.TFL, StereoUpmixer.TBL)) {
            assertTrue("channel $c", rms(channel(up, c, ch, settle)) < 1e-3f)
        }
    }

    @Test
    fun `bass goes to the LFE and comes back exactly in the fold`() {
        val n = rate
        val kick = sine(50f, n)
        val up = upmix(kick, kick)
        val back = fold(up)
        assertTrue(maxError(channel(back, 0, 2), kick) < 1e-4f)
        val settle = n / 2
        val lfe = rms(channel(up, StereoUpmixer.LFE, ch, settle))
        // 50 Hz is well under the crossover: nearly all of it, at 1/LFE_COEF.
        assertEquals(rms(kick.copyOfRange(settle, n)) / DownmixProcessor.LFE_COEF, lfe, 0.02f)

        val tone = sine(4000f, n)
        val lfeOfTone = rms(channel(upmix(tone, tone), StereoUpmixer.LFE, ch, settle))
        assertTrue("4 kHz leaked into the LFE at ${db(lfeOfTone)} dB", db(lfeOfTone) < -40f)
    }

    @Test
    fun `room sound opens out around and above, at the same power in the fold`() {
        val n = rate * 2
        val random = Random(7)
        // Two independent noises: as decorrelated as a reverb tail gets.
        val left = FloatArray(n) { (random.nextFloat() - 0.5f) * 0.5f }
        val right = FloatArray(n) { (random.nextFloat() - 0.5f) * 0.5f }
        val up = upmix(left, right)
        val back = fold(up)
        val settle = n / 2
        val inPower = rms(left.copyOfRange(settle, n))
        val outPower = rms(channel(back, 0, 2, settle))
        assertEquals("fold level of ambience", 0f, db(outPower) - db(inPower), 1f)
        // And the surrounds and heights carry it, not just the front pair.
        for (c in listOf(StereoUpmixer.SL, StereoUpmixer.BL, StereoUpmixer.TFL, StereoUpmixer.TSL)) {
            val level = db(rms(channel(up, c, ch, settle))) - db(inPower)
            assertTrue("channel $c is only $level dB", level > -18f)
        }
    }

    @Test
    fun `switched off it is a plain copy to the front pair`() {
        val n = rate
        val random = Random(3)
        val left = FloatArray(n) { random.nextFloat() - 0.5f }
        val right = FloatArray(n) { random.nextFloat() - 0.5f }
        val up = upmix(left, right, enabled = false)
        // From the first sample: it was never on.
        assertTrue(maxError(channel(up, StereoUpmixer.FL, ch), left) < 1e-6f)
        assertTrue(maxError(channel(up, StereoUpmixer.FL + 1, ch), right) < 1e-6f)
        for (c in 2 until ch) assertEquals("channel $c", 0f, rms(channel(up, c, ch)), 0f)
    }

    @Test
    fun `it only takes stereo, and only while on`() {
        val p = UpmixProcessor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)))
        p.setEnabled(true)
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(rate, 6, C.ENCODING_PCM_FLOAT)))
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(rate, 1, C.ENCODING_PCM_16BIT)))
        val out = p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        assertEquals(16, out.channelCount)
        assertEquals(C.ENCODING_PCM_16BIT, out.encoding)
        assertTrue(p.isActive)
        assertTrue(AudioProcessor.EMPTY_BUFFER === p.getOutput())
    }

    private companion object {
        const val BLOCK = 1024
    }
}
