package tf.monochrome.android.audio.eq

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Both EQs copy a block straight through when they have nothing to do,
 * instead of taking it apart into floats and putting it back. That round trip
 * was exact, so the copy must be too: the same bytes out as in, for 16-bit and
 * float, at any width. Mono still comes out as stereo.
 *
 * And the parametric EQ keeps its filters running when it is handed the same
 * curve again, which the player does after every seek and rebuffer, and
 * retunes them in place when only the curve moves.
 */
class EqBypassTest {

    private val fs = 48000

    private fun processors(): List<AudioProcessor> = listOf(
        AutoEqProcessor(),
        AutoEqProcessor().apply { applyBands(flat(), 0f, enabled = true) },
        ParametricEqProcessor(),
        ParametricEqProcessor().apply { applyBands(flat(), 0f, enabled = true) },
    )

    /** Bands that are there and do nothing: one disabled, one at 0 dB. */
    private fun flat() = listOf(
        EqBand(0, FilterType.PEAKING, 1000f, 6f, 1f, enabled = false),
        EqBand(1, FilterType.LOWSHELF, 100f, 0f, 0.7f, enabled = true),
    )

    private fun bytes(channels: Int, encoding: Int, frames: Int, seed: Int): ByteArray {
        val random = Random(seed)
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val b = ByteBuffer.allocate(frames * channels * bytesPerSample).order(ByteOrder.nativeOrder())
        repeat(frames * channels) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                b.putFloat(random.nextFloat() * 2.4f - 1.2f)
            } else {
                b.putShort(random.nextInt(-32768, 32768).toShort())
            }
        }
        return b.array()
    }

    private fun run(p: AudioProcessor, input: ByteArray): ByteArray {
        val buf = ByteBuffer.allocateDirect(input.size).order(ByteOrder.nativeOrder())
        buf.put(input).flip()
        p.queueInput(buf)
        assertEquals("the whole block is consumed", 0, buf.remaining())
        val out = p.output
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    @Test
    fun `with nothing to do the output is the input, byte for byte`() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            for (channels in listOf(2, 6, 12)) {
                for (p in processors()) {
                    p.configure(AudioFormat(fs, channels, encoding))
                    p.flush()
                    repeat(3) { round ->
                        val input = bytes(channels, encoding, 1000 + round * 37, channels * 10 + round)
                        val what = "${p.javaClass.simpleName} $channels ch, encoding $encoding, round $round"
                        assertArrayEquals(what, input, run(p, input))
                    }
                }
            }
        }
    }

    @Test
    fun `a part frame is left in the input, as before`() {
        for (p in processors()) {
            p.configure(AudioFormat(fs, 2, C.ENCODING_PCM_16BIT))
            p.flush()
            val buf = ByteBuffer.allocateDirect(4 * 10 + 2).order(ByteOrder.nativeOrder())
            repeat(21) { buf.putShort(it.toShort()) }
            buf.flip()
            p.queueInput(buf)
            assertEquals(2, buf.remaining())
            assertEquals(40, p.output.remaining())
        }
    }

    @Test
    fun `mono still comes out as stereo`() {
        for (encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            for (p in processors()) {
                p.configure(AudioFormat(fs, 1, encoding))
                p.flush()
                val input = bytes(1, encoding, 500, 3)
                val out = run(p, input)
                val size = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
                assertEquals(input.size * 2, out.size)
                for (i in 0 until 500) {
                    for (k in 0 until size) {
                        assertEquals(input[i * size + k], out[(2 * i) * size + k])
                        assertEquals(input[i * size + k], out[(2 * i + 1) * size + k])
                    }
                }
            }
        }
    }

    // ── Parametric EQ: the same curve again, and a moved curve ──────────

    private fun boost(gain: Float) = listOf(
        EqBand(0, FilterType.PEAKING, 1000f, gain, 1f, true),
        EqBand(1, FilterType.HIGHSHELF, 8000f, -3f, 0.7f, true),
    )

    private fun sine(frames: Int, offset: Int): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val s = (0.4 * sin(2 * PI * 1000.0 * (offset + i) / fs)).toFloat()
            b.putFloat(s); b.putFloat(s)
        }
        b.flip()
        return b
    }

    private fun left(p: AudioProcessor, frames: Int, offset: Int): FloatArray {
        p.queueInput(sine(frames, offset))
        val o = p.output
        return FloatArray(o.remaining() / 8) { o.getFloat(it * 8) }
    }

    private fun parametric(bands: List<EqBand>) = ParametricEqProcessor().apply {
        configure(AudioFormat(fs, 2, C.ENCODING_PCM_FLOAT))
        flush()
        applyBands(bands, 0f, enabled = true)
    }

    @Test
    fun `the same curve applied again leaves the filters running`() {
        val steady = parametric(boost(6f))
        val reapplied = parametric(boost(6f))
        for (block in 0 until 8) {
            // A fresh list with the same values, as the player decodes it.
            if (block == 4) reapplied.applyBands(boost(6f), 0f, enabled = true)
            val a = left(steady, 1024, block * 1024)
            val b = left(reapplied, 1024, block * 1024)
            assertArrayEquals("block $block", a, b, 0f)
        }
    }

    @Test
    fun `a moved curve is retuned in place, not restarted from silence`() {
        // Same two bands, the peak half a dB higher. Restarting the filters
        // from silence, as a new snapshot used to, opens the next block on a
        // transient; retuning them in place stays close to where the new
        // curve settles.
        val settled = parametric(boost(6.5f)).let { q ->
            var last = FloatArray(0)
            for (block in 0..6) last = left(q, 1024, block * 1024)
            last
        }
        fun worstAgainstSettled(x: FloatArray): Float {
            var worst = 0f
            for (i in 0 until 256) worst = maxOf(worst, abs(x[i] - settled[i]))
            return worst
        }
        val p = parametric(boost(6f))
        for (block in 0 until 6) left(p, 1024, block * 1024)
        p.applyBands(boost(6.5f), 0f, enabled = true)
        val retuned = worstAgainstSettled(left(p, 1024, 6 * 1024))
        // The old behaviour: the new curve's filters from silence, mid-song.
        val restarted = worstAgainstSettled(left(parametric(boost(6.5f)), 1024, 6 * 1024))
        assertTrue("retuned off by $retuned, restarted by $restarted", retuned * 4 < restarted)
    }

    @Test
    fun `a flush of the same format keeps the filters' memory`() {
        // Media3 flushes the whole chain at every gapless track change, where
        // the audio runs straight on: the filters carry on as if no flush had
        // come, or a boosted band would start again from silence and thump.
        val p = parametric(boost(6f))
        val unbroken = parametric(boost(6f))
        repeat(4) {
            left(p, 1024, it * 1024)
            left(unbroken, 1024, it * 1024)
        }
        p.flush()
        assertArrayEquals(left(unbroken, 1024, 4 * 1024), left(p, 1024, 4 * 1024), 0f)
    }
}
