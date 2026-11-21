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
 * is held to giving the song back through it, sample for sample, ambience
 * included.
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

    /** A song: a centred voice, a hard-panned part, bass, and a wide decorrelated top. */
    private fun song(n: Int): Pair<FloatArray, FloatArray> {
        val random = Random(11)
        val left = FloatArray(n)
        val right = FloatArray(n)
        var hl = 0f
        var hr = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / rate
            val voice = 0.2f * sin(2.0 * PI * 440.0 * t).toFloat()
            val kick = 0.2f * sin(2.0 * PI * 55.0 * t).toFloat()
            val guitar = 0.15f * sin(2.0 * PI * 1300.0 * t).toFloat()
            val nl = random.nextFloat() - 0.5f
            val nr = random.nextFloat() - 0.5f
            // A crude high-pass: the noise less its own slow average.
            hl += 0.5f * (nl - hl)
            hr += 0.5f * (nr - hr)
            left[i] = voice + kick + guitar + 0.3f * (nl - hl)
            right[i] = voice + kick + 0.3f * (nr - hr)
        }
        return left to right
    }

    @Test
    fun `a whole song folds back sample for sample, ambience and all`() {
        val n = rate * 2
        val (left, right) = song(n)
        val up = upmix(left, right)
        val back = fold(up)
        val errL = maxError(channel(back, 0, 2), left)
        val errR = maxError(channel(back, 1, 2), right)
        assertTrue("left error $errL", errL < 2e-5f)
        assertTrue("right error $errR", errR < 2e-5f)
        // And it is an upmix all the same: the room is in the six pairs.
        val settle = n / 2
        for (c in listOf(StereoUpmixer.FLC, StereoUpmixer.SL, StereoUpmixer.BL, StereoUpmixer.TFL, StereoUpmixer.TSL, StereoUpmixer.TBL)) {
            assertTrue("channel $c", rms(channel(up, c, ch, settle)) > 0.005f)
        }
        // A side's decorrelated copies add up to what the front handed over
        // and nothing else: left and right surrounds are each other's mirror
        // in the sum, as the side signal is.
        val sumL = FloatArray(n - settle)
        val sumR = FloatArray(n - settle)
        for (c in listOf(StereoUpmixer.FLC, StereoUpmixer.SL, StereoUpmixer.BL, StereoUpmixer.TFL, StereoUpmixer.TSL, StereoUpmixer.TBL)) {
            val l = channel(up, c, ch, settle)
            val r = channel(up, c + 1, ch, settle)
            for (i in sumL.indices) { sumL[i] += l[i]; sumR[i] += r[i] }
        }
        assertTrue(maxError(sumL, FloatArray(sumL.size) { -sumR[it] }) < 2e-5f)
    }

    @Test
    fun `the sixteen channels carry the power the stereo had`() {
        val n = rate * 2
        val random = Random(7)
        val left = FloatArray(n) { (random.nextFloat() - 0.5f) * 0.5f }
        val right = FloatArray(n) { (random.nextFloat() - 0.5f) * 0.5f }
        val up = upmix(left, right)
        val settle = n / 2
        fun power(x: FloatArray) = x.fold(0.0) { a, v -> a + v * v } / x.size
        val input = power(left.copyOfRange(settle, n)) + power(right.copyOfRange(settle, n))
        // Each channel at the weight the fold gives its power.
        var output = 0.0
        for (c in 0 until ch) {
            val w = when (c) {
                StereoUpmixer.FC -> 2.0 * DownmixProcessor.CENTER_COEF * DownmixProcessor.CENTER_COEF
                StereoUpmixer.LFE -> 2.0 * DownmixProcessor.LFE_COEF * DownmixProcessor.LFE_COEF
                else -> 1.0
            }
            output += w * power(channel(up, c, ch, settle))
        }
        assertEquals("the upmix's power against the stereo's, dB", 0.0, 10.0 * log10(output / input), 0.5)
    }

    /** The amplitude of [hz] in [x]. */
    private fun toneIn(x: FloatArray, hz: Float): Float {
        var s = 0.0
        var c = 0.0
        for (i in x.indices) {
            val w = 2.0 * PI * hz * i / rate
            s += x[i] * sin(w)
            c += x[i] * kotlin.math.cos(w)
        }
        return (2.0 * sqrt(s * s + c * c) / x.size).toFloat()
    }

    @Test
    fun `a voice in the middle and a wide top are steered apart, band by band`() {
        val n = rate * 2
        val settle = n / 2
        val voice = sine(440f, n, amp = 0.4f)
        val random = Random(5)
        val airL = FloatArray(n)
        val airR = FloatArray(n)
        var hl = 0f
        var hr = 0f
        for (i in 0 until n) {
            val nl = random.nextFloat() - 0.5f
            val nr = random.nextFloat() - 0.5f
            hl += 0.5f * (nl - hl)
            hr += 0.5f * (nr - hr)
            airL[i] = 0.8f * (nl - hl)
            airR[i] = 0.8f * (nr - hr)
        }
        val alone = upmix(voice, voice)
        val air = upmix(airL, airR)
        val both = upmix(FloatArray(n) { voice[it] + airL[it] }, FloatArray(n) { voice[it] + airR[it] })

        // The voice is as much in the centre speaker with the air over it as
        // without. One reading of the whole spectrum saw a mix that was
        // partly decorrelated and took a third of it back out.
        val centreAlone = toneIn(channel(alone, StereoUpmixer.FC, ch, settle), 440f)
        val centreBoth = toneIn(channel(both, StereoUpmixer.FC, ch, settle), 440f)
        assertTrue("the voice in the centre: $centreBoth with the air, $centreAlone alone", centreBoth > 0.9f * centreAlone)
        // And the air is as much in the room with the voice under it.
        val roomAir = rms(channel(air, StereoUpmixer.SL, ch, settle))
        val roomBoth = rms(channel(both, StereoUpmixer.SL, ch, settle))
        assertTrue("the air at the sides: $roomBoth with the voice, $roomAir alone", roomBoth > 0.9f * roomAir)
        // None of the voice is out there with it.
        assertTrue(toneIn(channel(both, StereoUpmixer.SL, ch, settle), 440f) < 0.01f * 0.4f)
    }

    @Test
    fun `at every rate the fold is the song, the top bands of a low rate included`() {
        for (r in listOf(8000, 22050, 44100, 96000, 192000)) {
            val up = StereoUpmixer(r)
            val n = r
            val random = Random(r)
            val left = FloatArray(n) { 0.3f * sin(2.0 * PI * 300.0 * it / r).toFloat() + 0.2f * (random.nextFloat() - 0.5f) }
            val right = FloatArray(n) { 0.3f * sin(2.0 * PI * 300.0 * it / r).toFloat() + 0.2f * (random.nextFloat() - 0.5f) }
            val out = FloatArray(n * ch)
            up.process(left, right, n, out)
            var worst = 0f
            for (i in 0 until n) {
                var l = 0f
                var rr = 0f
                for (c in 0 until ch) {
                    val v = out[i * ch + c]
                    assertTrue("rate $r", v.isFinite())
                    when {
                        c == StereoUpmixer.FC -> { l += DownmixProcessor.CENTER_COEF * v; rr += DownmixProcessor.CENTER_COEF * v }
                        c == StereoUpmixer.LFE -> { l += DownmixProcessor.LFE_COEF * v; rr += DownmixProcessor.LFE_COEF * v }
                        c % 2 == 0 -> l += v
                        else -> rr += v
                    }
                }
                worst = maxOf(worst, kotlin.math.abs(l - left[i]), kotlin.math.abs(rr - right[i]))
            }
            assertTrue("rate $r: fold error $worst", worst < 2e-5f)
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

    @Test
    fun `turned on while out of the chain it asks to be configured, once`() {
        val p = UpmixProcessor()
        var asked = 0
        p.onNeedsConfigure = { asked++ }
        p.setEnabled(true)
        assertEquals(1, asked)
        // Where it cannot join (the fold is off), a configure leaves it out of the chain...
        p.foldEnabled = false
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)))
        // ...and the mix, loaded again by that configure, says once more that it is on.
        // That is not being turned on: asking again here took the song's audio track
        // out and put it back every two seconds, for ever.
        repeat(5) { p.setEnabled(true) }
        assertEquals(1, asked)
        // Off and on again is the listener's doing, and asks.
        p.setEnabled(false)
        p.setEnabled(true)
        assertEquals(2, asked)
        // In the chain already, there is nothing to ask for.
        p.foldEnabled = true
        p.setEnabled(false)
        assertEquals(16, p.apply { setEnabled(true) }.configure(AudioFormat(rate, 2, C.ENCODING_PCM_FLOAT)).channelCount)
        val before = asked
        p.setEnabled(false)
        p.setEnabled(true)
        assertEquals(before, asked)
    }

    private companion object {
        const val BLOCK = 1024
    }
}
