package tf.monochrome.android.audio.tempo

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * A drum machine for tempo tests: kick, snare, closed and open hat, and a
 * hardstyle-style bass, laid on a bar of sixteen steps at a known tempo. The
 * patterns are the genres' own, so a test asks the question real music asks:
 * is a DnB two-step 174 or 87, a four-on-the-floor kick with off-beat hats 128
 * or 256, a 200 BPM hardcore kick 200 or 100.
 */
internal object SyntheticDrums {

    enum class Voice { KICK, SNARE, HAT, OPEN_HAT, OFFBEAT_BASS }

    /** One bar: for each voice, the sixteenth-note steps (0..15) it plays on. */
    class Pattern(val name: String, val hits: Map<Voice, List<Int>>)

    private fun steps(vararg s: Int) = s.toList()
    private val EIGHTHS = steps(0, 2, 4, 6, 8, 10, 12, 14)
    private val SIXTEENTHS = (0..15).toList()
    private val OFFBEATS = steps(2, 6, 10, 14)
    private val QUARTERS = steps(0, 4, 8, 12)

    val HOUSE = Pattern("house", mapOf(Voice.KICK to QUARTERS, Voice.SNARE to steps(4, 12), Voice.OPEN_HAT to OFFBEATS, Voice.HAT to EIGHTHS))
    val TECHNO = Pattern("techno", mapOf(Voice.KICK to QUARTERS, Voice.HAT to SIXTEENTHS, Voice.OPEN_HAT to OFFBEATS))
    val DNB = Pattern("drum and bass two-step", mapOf(Voice.KICK to steps(0, 10), Voice.SNARE to steps(4, 12), Voice.HAT to EIGHTHS))
    val HARDSTYLE = Pattern("hardstyle", mapOf(Voice.KICK to QUARTERS, Voice.OFFBEAT_BASS to OFFBEATS, Voice.HAT to EIGHTHS))
    val HARDCORE = Pattern("hardcore", mapOf(Voice.KICK to QUARTERS, Voice.HAT to OFFBEATS))
    val HIPHOP = Pattern("boom bap", mapOf(Voice.KICK to steps(0, 7, 10), Voice.SNARE to steps(4, 12), Voice.HAT to EIGHTHS))
    val ROCK = Pattern("rock", mapOf(Voice.KICK to steps(0, 8, 10), Voice.SNARE to steps(4, 12), Voice.HAT to EIGHTHS))
    val BREAKBEAT = Pattern("breakbeat", mapOf(Voice.KICK to steps(0, 6, 10), Voice.SNARE to steps(4, 12), Voice.HAT to SIXTEENTHS))
    val TRANCE = Pattern("trance", mapOf(Voice.KICK to QUARTERS, Voice.OFFBEAT_BASS to OFFBEATS, Voice.HAT to SIXTEENTHS, Voice.SNARE to steps(4, 12)))
    val FUNK = Pattern("funk", mapOf(Voice.KICK to steps(0, 3, 8, 10), Voice.SNARE to steps(4, 12), Voice.HAT to SIXTEENTHS))
    val POP = Pattern("pop", mapOf(Voice.KICK to steps(0, 8), Voice.SNARE to steps(4, 12), Voice.HAT to EIGHTHS))

    /**
     * [seconds] of [pattern] at [bpm], at [rate] Hz, mono. [gain] sets a
     * voice's level against the others (1 when absent): mixes differ, and a
     * loud kick or a quiet snare must not change the tempo.
     */
    fun render(
        pattern: Pattern,
        bpm: Float,
        seconds: Float,
        rate: Int = 44100,
        seed: Int = 1,
        gain: Map<Voice, Float> = emptyMap(),
    ): FloatArray {
        val n = (rate * seconds).toInt()
        val out = FloatArray(n)
        val step = 60.0 * rate / bpm / 4.0
        val random = Random(seed)
        var s = 0
        while (true) {
            val at = (s * step).toInt()
            if (at >= n) break
            val inBar = s % 16
            for ((voice, on) in pattern.hits) if (inBar in on) hit(voice, out, at, rate, random, gain[voice] ?: 1f)
            s++
        }
        return out
    }

    private fun hit(voice: Voice, out: FloatArray, at: Int, rate: Int, random: Random, level: Float) {
        fun add(seconds: Double, sample: (Double) -> Double) {
            for (i in 0 until (seconds * rate).toInt()) {
                if (at + i >= out.size) break
                out[at + i] += level * sample(i.toDouble() / rate).toFloat()
            }
        }
        var lastNoise = 0.0
        fun highNoise(): Double {
            val x = random.nextDouble() - 0.5
            val d = x - lastNoise
            lastNoise = x
            return d
        }
        when (voice) {
            // A 55 Hz thump falling from 120 Hz, its attack a 2 ms ramp as a
            // real drum's is, not a step: a step is a click across the whole
            // spectrum, which no kick has.
            Voice.KICK -> add(0.18) { t -> 0.8 * sin(2 * PI * (55 + 65 * exp(-t * 40)) * t) * exp(-t * 22) * minOf(1.0, t / 0.002) }
            Voice.SNARE -> add(0.2) { t -> (0.35 * sin(2 * PI * 185 * t) + 0.5 * (random.nextDouble() - 0.5)) * exp(-t * 22) }
            Voice.HAT -> add(0.04) { t -> 0.35 * highNoise() * exp(-t * 110) }
            Voice.OPEN_HAT -> add(0.15) { t -> 0.3 * highNoise() * exp(-t * 18) }
            // Hardstyle's reverse bass on the off-beat: low, and loud.
            Voice.OFFBEAT_BASS -> add(0.12) { t -> 0.6 * sin(2 * PI * 50 * t) * (1 - exp(-t * 60)) * exp(-t * 12) }
        }
    }
}
