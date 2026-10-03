package tf.monochrome.android.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType

/**
 * Both EQs at Atmos widths. The stereo behaviour is the reference: a wide
 * stream's channels must come out exactly as the equivalent stereo channel
 * would, so nothing about the 2-channel path moved.
 */
class MultichannelEqTest {

    private val fs = 48000
    private val frames = 9600

    /** Interleaved float, every channel the same 1 kHz sine. */
    private fun sine(channels: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * channels * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (0.25 * sin(2 * PI * 1000.0 * i / fs)).toFloat()
            repeat(channels) { b.putFloat(s) }
        }
        b.flip()
        return b
    }

    private fun run(p: AudioProcessor, channels: Int): Array<FloatArray> {
        p.queueInput(sine(channels))
        val out = p.output
        val n = out.remaining() / 4 / channels
        return Array(channels) { c -> FloatArray(n) { i -> out.getFloat((i * channels + c) * 4) } }
    }

    /** RMS in dB over the second half, past the filters' settling. */
    private fun levelDb(x: FloatArray): Double {
        var acc = 0.0
        for (i in x.size / 2 until x.size) acc += x[i].toDouble() * x[i]
        return 20 * log10(sqrt(acc / (x.size - x.size / 2)))
    }

    private val reference = 20 * log10(0.25 / sqrt(2.0))

    private fun peak(gain: Float) = listOf(EqBand(0, FilterType.PEAKING, 1000f, gain, 1f, true))

    private fun autoEq(channels: Int, left: Float, right: Float) = AutoEqProcessor().apply {
        configure(AudioFormat(fs, channels, C.ENCODING_PCM_FLOAT))
        flush()
        applyBands(peak(left), peak(right), preamp = 0f, enabled = true)
    }

    @Test
    fun `AutoEQ on 7_1_4 gives each side its ear and the centre the average`() {
        val out = run(autoEq(12, left = 6f, right = -6f), 12)
        val sides = tf.monochrome.android.audio.dsp.ChannelLayout.sides(12)
        for (c in 0 until 12) {
            val expected = when (sides[c]) {
                tf.monochrome.android.audio.dsp.ChannelLayout.Side.LEFT -> 6.0
                tf.monochrome.android.audio.dsp.ChannelLayout.Side.RIGHT -> -6.0
                tf.monochrome.android.audio.dsp.ChannelLayout.Side.CENTER -> 0.0
            }
            assertEquals("channel $c (${sides[c]})", expected, levelDb(out[c]) - reference, 0.3)
        }
    }

    @Test
    fun `AutoEQ front pair of a wide stream is bit-identical to stereo`() {
        val wide = run(autoEq(12, left = 4f, right = -3f), 12)
        val stereo = run(autoEq(2, left = 4f, right = -3f), 2)
        assertTrue(wide[0].contentEquals(stereo[0]))
        assertTrue(wide[1].contentEquals(stereo[1]))
    }

    @Test
    fun `AutoEQ with matching ears runs the same curve on every channel`() {
        val out = run(autoEq(6, left = 5f, right = 5f), 6)
        for (c in 1 until 6) assertTrue("channel $c", out[c].contentEquals(out[0]))
    }

    @Test
    fun `parametric EQ runs the same curve on every channel of 5_1, as on stereo`() {
        fun peq(channels: Int) = ParametricEqProcessor().apply {
            configure(AudioFormat(fs, channels, C.ENCODING_PCM_FLOAT))
            flush()
            applyBands(peak(-8f), preamp = 0f, enabled = true)
        }
        val wide = run(peq(6), 6)
        val stereo = run(peq(2), 2)
        for (c in 0 until 6) assertTrue("channel $c", wide[c].contentEquals(stereo[0]))
        assertEquals(-8.0, levelDb(wide[0]) - reference, 0.3)
    }

    @Test
    fun `mono still comes out as stereo`() {
        val p = ParametricEqProcessor()
        assertEquals(2, p.configure(AudioFormat(fs, 1, C.ENCODING_PCM_FLOAT)).channelCount)
        assertEquals(6, p.configure(AudioFormat(fs, 6, C.ENCODING_PCM_FLOAT)).channelCount)
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(fs, 17, C.ENCODING_PCM_FLOAT)))
    }
}
