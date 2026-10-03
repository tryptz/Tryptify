package tf.monochrome.android.audio.tempo

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The tap measures a track once and then holds the number still: only a new
 * track, a tap ([BpmTapProcessor.measure]) or a typed tempo
 * ([BpmTapProcessor.setTempo]) changes it. It used to republish every second
 * for the whole song.
 */
class BpmTapProcessorTest {

    private val rate = 44100
    private val tap = BpmTapProcessor().apply {
        configure(AudioFormat(rate, 1, C.ENCODING_PCM_FLOAT))
        flush()
    }

    /** Every value [BpmTapProcessor.bpm] took while [seconds] of [bpm] played. */
    private fun play(bpm: Float, seconds: Float): List<Float?> {
        val audio = beat(bpm, seconds)
        val seen = mutableListOf<Float?>()
        val block = 4096
        val buffer = ByteBuffer.allocateDirect(block * 4).order(ByteOrder.nativeOrder())
        var i = 0
        while (i < audio.size) {
            val n = minOf(block, audio.size - i)
            buffer.clear()
            for (k in 0 until n) buffer.putFloat(audio[i + k])
            buffer.flip()
            tap.queueInput(buffer)
            tap.getOutput()
            val now = tap.bpm.value
            if (seen.isEmpty() || seen.last() != now) seen += now
            i += n
        }
        return seen
    }

    @Test
    fun aNewTrackIsMeasuredOnceAndThenHolds() {
        tap.newTrack()
        val first = play(128f, 20f)
        // Unknown, then one reading: never a stream of them.
        assertEquals(listOf(null, first.last()), first)
        assertEquals(128f, first.last()!!, 1f)

        // The music changes tempo under it; the number does not move.
        val later = play(140f, 20f)
        assertEquals(listOf(first.last()), later)
    }

    @Test
    fun aTapMeasuresAgainFromWhatIsPlaying() {
        tap.newTrack()
        play(128f, 20f)
        play(140f, 12f)

        tap.measure()
        assertNull(tap.bpm.value)
        // The last ten seconds are already in hand, so it settles within the
        // five readings of one measurement, then holds.
        val again = play(140f, 6f)
        assertEquals(140f, again.last()!!, 1.5f)
        assertEquals(listOf(again.last()), play(140f, 10f))
    }

    @Test
    fun aTypedTempoHoldsUntilTheTrackChanges() {
        tap.newTrack()
        play(87f, 20f)

        tap.setTempo(174f)
        assertEquals(174f, tap.bpm.value)
        // Not overwritten by anything still measuring.
        assertEquals(listOf(174f), play(87f, 20f))

        tap.newTrack()
        val next = play(128f, 20f)
        assertEquals(null, next.first())
        assertNotNull(next.last())
        assertEquals(128f, next.last()!!, 1f)
    }

    @Test
    fun aTempoTypedDuringTheFirstMeasurementWins() {
        tap.newTrack()
        play(128f, 3f)
        tap.setTempo(64f)
        assertEquals(listOf(64f), play(128f, 20f))
    }

    /** [seconds] of a kick on the beat and a hat between, at [bpm]. */
    private fun beat(bpm: Float, seconds: Float): FloatArray {
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        val period = 60.0 * rate / bpm
        val random = Random(1)
        var k = 0
        while (true) {
            val at = (k * period).toInt()
            if (at >= n) break
            for (i in 0 until (0.15 * rate).toInt()) {
                if (at + i >= n) break
                val t = i.toDouble() / rate
                out[at + i] += (0.8 * sin(2 * PI * 55 * t) * exp(-t * 30)).toFloat()
            }
            val off = (at + period / 2).toInt()
            for (i in 0 until (0.03 * rate).toInt()) {
                if (off + i >= n) break
                val t = i.toDouble() / rate
                out[off + i] += ((random.nextFloat() - 0.5f) * 0.3f * exp(-t * 120)).toFloat()
            }
            k++
        }
        return out
    }
}
