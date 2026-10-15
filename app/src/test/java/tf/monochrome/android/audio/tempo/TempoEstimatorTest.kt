package tf.monochrome.android.audio.tempo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.audio.tempo.SyntheticDrums.Voice
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Tempo from synthetic tracks with a known pulse: a kick on every beat and a
 * hat on the off-beat, the shape most of the music this is for has.
 */
class TempoEstimatorTest {

    private val rate = 44100

    /** [seconds] of a kick on the beat and a hat between, at [bpm]. */
    private fun beat(bpm: Float, seconds: Float, seed: Int = 1): FloatArray {
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        val period = 60.0 * rate / bpm
        val random = Random(seed)
        var k = 0
        while (true) {
            val at = (k * period).toInt()
            if (at >= n) break
            // Kick: a 55 Hz thump with a fast decay.
            for (i in 0 until (0.15 * rate).toInt()) {
                if (at + i >= n) break
                val t = i.toDouble() / rate
                out[at + i] += (0.8 * sin(2 * PI * 55 * t) * exp(-t * 30)).toFloat()
            }
            // Hat on the off-beat: a short burst of noise.
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

    private fun estimateOf(audio: FloatArray, sampleRate: Int = rate): Float? {
        val est = TempoEstimator(sampleRate)
        // In playback-sized blocks, as the tap feeds it.
        val block = FloatArray(4096)
        var i = 0
        while (i < audio.size) {
            val n = minOf(block.size, audio.size - i)
            System.arraycopy(audio, i, block, 0, n)
            est.feed(block, n)
            i += n
        }
        return est.estimate()
    }

    @Test
    fun `house at 128`() {
        val bpm = estimateOf(beat(128f, 14f))
        assertNotNull(bpm)
        assertEquals(128f, bpm!!, 1f)
    }

    @Test
    fun `drum and bass at 174 is not read at half time`() {
        val bpm = estimateOf(beat(174f, 14f))
        assertNotNull(bpm)
        assertEquals(174f, bpm!!, 1.5f)
    }

    @Test
    fun `hip-hop at 90`() {
        val bpm = estimateOf(beat(90f, 14f))
        assertNotNull(bpm)
        assertEquals(90f, bpm!!, 1f)
    }

    @Test
    fun `a fractional tempo resolves finer than one envelope step`() {
        val bpm = estimateOf(beat(123.4f, 14f))
        assertNotNull(bpm)
        assertEquals(123.4f, bpm!!, 0.6f)
    }

    // ── Half time and double time, by genre ────────────────────────────
    // Each a genre's own pattern (SyntheticDrums). Before the beat was chosen
    // by its grid and its octave by its off-beats, 23 of these 48 tempi read
    // at half, double or two-thirds: every hardcore track at half, every DnB
    // two-step at 116.

    /**
     * At both rates music comes at: an onset's place on the 5 ms envelope
     * differs between them. [mix] sets voices' levels against the rest.
     */
    private fun assertTempo(pattern: SyntheticDrums.Pattern, bpm: Float, mix: Map<Voice, Float> = emptyMap()) {
        for (sampleRate in listOf(44100, 48000)) for (seed in 1..2) {
            val what = "${pattern.name} at $bpm, $sampleRate Hz, mix $mix"
            val got = estimateOf(SyntheticDrums.render(pattern, bpm, 14f, sampleRate, seed, mix), sampleRate)
            assertNotNull(what, got)
            assertEquals(what, bpm, got!!, bpm * 0.02f)
        }
    }

    @Test
    fun `hardcore and frenchcore read at their own tempo, not half`() {
        for (bpm in listOf(160f, 170f, 180f, 190f, 200f, 210f, 220f, 230f, 245f)) assertTempo(SyntheticDrums.HARDCORE, bpm)
    }

    @Test
    fun `a drum and bass two-step reads at 174, not 87 or 116`() {
        for (bpm in listOf(165f, 170f, 174f, 176f)) assertTempo(SyntheticDrums.DNB, bpm)
    }

    @Test
    fun `hardstyle's off-beat bass does not double it`() {
        for (bpm in listOf(150f, 155f)) assertTempo(SyntheticDrums.HARDSTYLE, bpm)
    }

    @Test
    fun `off-beat hats do not double a four-on-the-floor kick`() {
        for (bpm in listOf(122f, 124f, 128f)) assertTempo(SyntheticDrums.HOUSE, bpm)
        for (bpm in listOf(132f, 138f, 180f)) assertTempo(SyntheticDrums.TECHNO, bpm)
        for (bpm in listOf(132f, 138f, 142f)) assertTempo(SyntheticDrums.TRANCE, bpm)
    }

    @Test
    fun `a boom-bap kick on the and does not double hip-hop`() {
        for (bpm in listOf(86f, 90f, 95f)) assertTempo(SyntheticDrums.HIPHOP, bpm)
    }

    @Test
    fun `a slow kick and snare is not read at double, nor a fast one at two-thirds`() {
        for (bpm in listOf(72f, 100f, 120f, 140f)) assertTempo(SyntheticDrums.ROCK, bpm)
        for (bpm in listOf(76f, 100f, 110f, 125f)) assertTempo(SyntheticDrums.POP, bpm)
        for (bpm in listOf(98f, 105f, 115f)) assertTempo(SyntheticDrums.FUNK, bpm)
        for (bpm in listOf(130f, 136f)) assertTempo(SyntheticDrums.BREAKBEAT, bpm)
    }

    @Test
    fun `a slow groove's eighth hats do not double it`() {
        for (bpm in listOf(60f, 62f, 66f, 70f)) {
            assertTempo(SyntheticDrums.ROCK, bpm)
            assertTempo(SyntheticDrums.FUNK, bpm)
            assertTempo(SyntheticDrums.POP, bpm)
            assertTempo(SyntheticDrums.HIPHOP, bpm)
        }
    }

    // ── The mix does not move the octave ───────────────────────────────
    // Whether a beat holds a hit, not how loud it is. When the octave came
    // from comparing their strengths, a pop or rock backbeat at 122-149 read
    // at half as soon as its snare sat under its kick, and loud hats doubled
    // boom bap.

    private val loudKick = mapOf(Voice.KICK to 2f)
    private val quietSnare = mapOf(Voice.SNARE to 0.5f)
    private val loudKickQuietSnare = mapOf(Voice.KICK to 2f, Voice.SNARE to 0.6f)
    private val loudHats = mapOf(Voice.HAT to 2f, Voice.OPEN_HAT to 2f)

    @Test
    fun `a quiet snare or a loud kick does not halve a backbeat`() {
        for (mix in listOf(loudKick, quietSnare, loudKickQuietSnare)) {
            for (bpm in listOf(122f, 134f, 146f, 160f)) {
                assertTempo(SyntheticDrums.POP, bpm, mix)
                assertTempo(SyntheticDrums.ROCK, bpm, mix)
            }
            for (bpm in listOf(122f, 128f)) assertTempo(SyntheticDrums.FUNK, bpm, mix)
        }
    }

    @Test
    fun `a fast backbeat reads at its own tempo, not half`() {
        for (bpm in listOf(152f, 160f, 168f)) {
            assertTempo(SyntheticDrums.POP, bpm)
            assertTempo(SyntheticDrums.ROCK, bpm)
        }
    }

    @Test
    fun `a breakbeat's kicks on the ands do not double it`() {
        for (mix in listOf(emptyMap(), loudHats, loudKick)) {
            for (bpm in listOf(88f, 100f, 112f, 124f)) assertTempo(SyntheticDrums.BREAKBEAT, bpm, mix)
        }
    }

    @Test
    fun `loud hats or a quiet snare do not double boom bap`() {
        for (mix in listOf(loudHats, quietSnare, loudKickQuietSnare)) {
            for (bpm in listOf(62f, 74f, 86f, 98f)) assertTempo(SyntheticDrums.HIPHOP, bpm, mix)
        }
    }

    @Test
    fun `dance music keeps its tempo whatever the mix`() {
        for (mix in listOf(loudKick, quietSnare, loudHats)) {
            assertTempo(SyntheticDrums.HOUSE, 124f, mix)
            assertTempo(SyntheticDrums.TECHNO, 132f, mix)
            assertTempo(SyntheticDrums.TRANCE, 125f, mix)
            assertTempo(SyntheticDrums.HARDSTYLE, 150f, mix)
            assertTempo(SyntheticDrums.HARDCORE, 200f, mix)
            assertTempo(SyntheticDrums.HARDCORE, 230f, mix)
        }
        // A DnB two-step with a loud kick or a quiet snare still reads at
        // half: the known limit on TempoEstimator.
        assertTempo(SyntheticDrums.DNB, 174f, loudHats)
    }

    @Test
    fun `an off-beat bass louder than the kick does not double four-on-the-floor`() {
        // Up to 127.5 the level twice as fast is in range. The worst mixes
        // are those where the bass lands exactly as hard as the kick.
        for (mix in listOf(
            mapOf(Voice.KICK to 0.5f, Voice.OFFBEAT_BASS to 1.5f),
            mapOf(Voice.KICK to 0.4f, Voice.OFFBEAT_BASS to 1.6f),
            mapOf(Voice.KICK to 0.4f, Voice.OFFBEAT_BASS to 1.8f),
            mapOf(Voice.KICK to 0.5f, Voice.OFFBEAT_BASS to 2.5f),
        )) for (bpm in listOf(120f, 123f, 126f, 127f)) {
            assertTempo(SyntheticDrums.TRANCE, bpm, mix)
            assertTempo(SyntheticDrums.HARDSTYLE, bpm, mix)
        }
    }

    @Test
    fun `the first readings, from six to ten seconds, are right too`() {
        // The tap's first reading comes from six seconds of audio, and a
        // window's edges cut onsets in half: a two-step clears its level by
        // a beat or two, so a lost one at an edge must not halve it.
        for (sampleRate in listOf(44100, 48000)) for (seconds in listOf(6.2f, 6.5f, 8f, 9.3f)) for (skip in listOf(0f, 0.11f, 0.23f, 0.37f)) {
            for ((pattern, bpm) in listOf(SyntheticDrums.DNB to 174f, SyntheticDrums.DNB to 170f, SyntheticDrums.HARDCORE to 200f, SyntheticDrums.POP to 128f, SyntheticDrums.BREAKBEAT to 100f)) {
                val full = SyntheticDrums.render(pattern, bpm, seconds + skip + 1f, sampleRate, seed = 3)
                val from = (skip * sampleRate).toInt()
                val audio = full.copyOfRange(from, from + (seconds * sampleRate).toInt())
                val what = "${pattern.name} at $bpm, $seconds s from $skip s, $sampleRate Hz"
                val got = estimateOf(audio, sampleRate)
                assertNotNull(what, got)
                assertEquals(what, bpm, got!!, bpm * 0.02f)
            }
        }
    }

    @Test(timeout = 10_000)
    fun `a NaN or an infinity in the audio is silence, never a hang`() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 1e20f)) {
            val audio = SyntheticDrums.render(SyntheticDrums.HOUSE, 124f, 14f, rate)
            audio[rate] = bad
            val got = estimateOf(audio)
            assertNotNull("$bad", got)
            assertEquals("$bad", 124f, got!!, 124f * 0.02f)
        }
    }

    @Test
    fun `no answer before there is enough audio`() {
        assertNull(estimateOf(beat(128f, 3f)))
    }

    @Test
    fun `no pulse, no tempo`() {
        val random = Random(9)
        assertNull(estimateOf(FloatArray(rate * 12) { (random.nextFloat() - 0.5f) * 0.4f }))
        assertNull(estimateOf(FloatArray(rate * 12)))
    }

    @Test
    fun `a new track starts over`() {
        val est = TempoEstimator(rate)
        val first = beat(128f, 12f)
        est.feed(first, first.size)
        assertNotNull(est.estimate())
        est.reset()
        assertNull(est.estimate())
    }
}
