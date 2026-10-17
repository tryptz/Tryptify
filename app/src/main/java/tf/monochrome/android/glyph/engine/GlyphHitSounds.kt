// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * A hit sound kit: the sound a press makes, in the manner of another rhythm
 * game. Each is synthesised here, not sampled: no other game's audio ships
 * in the app, and a kit is a few kilobytes of arithmetic.
 *
 * [TAIKO] answers each press with a drum: a deep "don" on the middle lanes,
 * a wooden "ka" on the rim (the outer lanes). [OSU] is one soft, crisp tap
 * for every press. [POPN] is a bright "pon" pitched by button, rising along
 * the row, so a run of presses plays a tune.
 */
enum class GlyphHitKit(val id: String, val label: String) {
    OFF("off", "Off"),
    TAIKO("taiko", "Taiko"),
    OSU("osu", "osu!"),
    POPN("popn", "pop'n"),
    ;

    companion object {
        fun fromId(id: String?): GlyphHitKit = entries.firstOrNull { it.id == id } ?: OFF
    }
}

/** The kits' sounds, as 16-bit mono PCM, and which one a lane plays. */
object GlyphHitSynth {

    const val SAMPLE_RATE = 44_100

    /** Every sound [kit] has, in the order [voiceFor] numbers them; none for [GlyphHitKit.OFF]. */
    fun voices(kit: GlyphHitKit): List<ShortArray> = when (kit) {
        GlyphHitKit.OFF -> emptyList()
        GlyphHitKit.TAIKO -> listOf(don(), ka())
        GlyphHitKit.OSU -> listOf(osuTap())
        GlyphHitKit.POPN -> POPN_SCALE_HZ.map { pon(it) }
    }

    /** Which of [kit]'s [voices] a press of [lane] plays, in [course]. */
    fun voiceFor(kit: GlyphHitKit, lane: GlyphLane, course: CourseType): Int = when (kit) {
        GlyphHitKit.OFF, GlyphHitKit.OSU -> 0
        // The rim is the outside: dance's left and right panels, pop'n's two
        // buttons at each end.
        GlyphHitKit.TAIKO -> when (course) {
            CourseType.DANCE -> if (lane == GlyphLane.LEFT || lane == GlyphLane.RIGHT) KA else DON
            CourseType.POPN -> if (lane.ordinal <= 1 || lane.ordinal >= 7) KA else DON
        }
        // Up the scale along the row: pop'n's nine buttons step by step,
        // dance's four panels as a chord's notes.
        GlyphHitKit.POPN -> when (course) {
            CourseType.DANCE -> DANCE_NOTES[lane.ordinal.coerceIn(0, DANCE_NOTES.lastIndex)]
            CourseType.POPN -> lane.ordinal.coerceIn(0, POPN_SCALE_HZ.lastIndex)
        }
    }

    const val DON = 0
    const val KA = 1

    /** pop'n's nine buttons on a major pentatonic scale, C5 to G6. */
    val POPN_SCALE_HZ = floatArrayOf(523.25f, 587.33f, 659.26f, 783.99f, 880f, 1046.5f, 1174.66f, 1318.51f, 1567.98f)

    /** Dance's four panels: C, E, G and the C above, from the same scale. */
    private val DANCE_NOTES = intArrayOf(0, 2, 3, 5)

    // ── the sounds ───────────────────────────────────────────────────────

    /** Taiko's "don": a deep drum head, its pitch falling as it rings, and the stick's thump. */
    fun don(): ShortArray = render(0.38f) { t, noise ->
        // The pitch falls from 142 Hz to 72 Hz as the head settles; this is
        // its phase, the integral of 72 + 70·e^(−28t).
        val phase = 2.0 * PI * (72.0 * t + 70.0 / 28.0 * (1.0 - exp(-t * 28.0)))
        val body = sin(phase) * exp(-t * 8.5)
        val overtone = sin(phase * 1.52) * exp(-t * 16.0) * 0.35
        val thump = noise.lowPassed(0.08) * exp(-t * 110.0) * 0.9
        body + overtone + thump
    }

    /** Taiko's "ka": the stick on the drum's wooden rim, short and bright. */
    fun ka(): ShortArray = render(0.11f) { t, noise ->
        val wood = sin(2.0 * PI * 1850.0 * t) * 0.6 + sin(2.0 * PI * 2950.0 * t) * 0.35
        val click = noise.highPassed(0.6) * exp(-t * 260.0) * 0.8
        wood * exp(-t * 55.0) + click
    }

    /** osu!'s plain hit: a soft snap of noise over a short low tone. */
    fun osuTap(): ShortArray = render(0.14f) { t, noise ->
        val snap = noise.highPassed(0.35) * exp(-t * 48.0) * 0.7
        val tone = sin(2.0 * PI * (230.0 - 60.0 * t) * t) * exp(-t * 38.0) * 0.5
        val tick = sin(2.0 * PI * 3200.0 * t) * exp(-t * 400.0) * 0.25
        snap + tone + tick
    }

    /** pop'n's "pon" at [hz]: a bright pluck with a bell's slightly stretched overtone. */
    fun pon(hz: Float): ShortArray = render(0.42f) { t, _ ->
        val f = hz.toDouble()
        val ring = sin(2.0 * PI * f * t) +
            sin(2.0 * PI * 2.0 * f * t) * 0.45 * exp(-t * 6.0) +
            sin(2.0 * PI * 3.0 * f * t) * 0.2 * exp(-t * 10.0) +
            sin(2.0 * PI * 4.2 * f * t) * 0.12 * exp(-t * 18.0)
        ring * exp(-t * 7.5)
    }

    /**
     * [seconds] of [sample] (time, a noise source), with a 1 ms rise so the
     * start never pops, a 6 ms fade at the end, and the peak at [PEAK].
     */
    private fun render(seconds: Float, sample: (Double, Noise) -> Double): ShortArray {
        val frames = (seconds * SAMPLE_RATE).toInt()
        val noise = Noise()
        val raw = DoubleArray(frames) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            noise.next()
            sample(t, noise)
        }
        val rise = SAMPLE_RATE / 1000
        val fall = SAMPLE_RATE * 6 / 1000
        for (i in raw.indices) {
            val inGain = min(1.0, i.toDouble() / rise)
            val outGain = min(1.0, (frames - 1 - i).toDouble() / fall)
            raw[i] *= inGain * outGain
        }
        val peak = raw.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        return ShortArray(frames) { i -> (raw[i] / peak * PEAK * Short.MAX_VALUE).toInt().toShort() }
    }

    /** How loud a sound peaks, of full scale: room left for several at once. */
    const val PEAK = 0.8

    /** A seeded noise source, so every phone renders the same sound, with two simple filters. */
    private class Noise {
        private val random = Random(SEED)
        private var white = 0.0
        private var low = 0.0
        private var lastWhite = 0.0
        private var high = 0.0

        fun next() {
            lastWhite = white
            white = random.nextDouble() * 2.0 - 1.0
        }

        /** One-pole low-pass of the noise; [a] is how much of each new sample it takes. */
        fun lowPassed(a: Double): Double {
            low += a * (white - low)
            return low
        }

        /** One-pole high-pass of the noise; [a] near 1 keeps only the hiss. */
        fun highPassed(a: Double): Double {
            high = a * (high + white - lastWhite)
            return high
        }

        companion object {
            const val SEED = 0x7E57L
        }
    }

    /** [samples] as a WAV file: what Android's SoundPool loads. */
    fun wav(samples: ShortArray): ByteArray {
        val data = samples.size * 2
        val out = ByteArrayOutputStream(44 + data)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII)); putInt(36 + data)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII)); putInt(16)
            putShort(1) // PCM
            putShort(1) // mono
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
            putShort(2); putShort(16)
            put("data".toByteArray(Charsets.US_ASCII)); putInt(data)
        }
        out.write(header.array())
        val body = ByteBuffer.allocate(data).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { body.putShort(it) }
        out.write(body.array())
        return out.toByteArray()
    }
}
