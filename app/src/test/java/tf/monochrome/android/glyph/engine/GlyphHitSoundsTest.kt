// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.asset.GlyphLane

/** The hit sound kits: every sound present, clean at both ends, and the right one for each lane. */
class GlyphHitSoundsTest {

    @Test
    fun everyKitButOffHasItsSounds() {
        assertEquals(0, GlyphHitSynth.voices(GlyphHitKit.OFF).size)
        assertEquals(2, GlyphHitSynth.voices(GlyphHitKit.TAIKO).size)
        assertEquals(1, GlyphHitSynth.voices(GlyphHitKit.OSU).size)
        assertEquals(9, GlyphHitSynth.voices(GlyphHitKit.POPN).size)
    }

    @Test
    fun eachSoundIsShortPeaksBelowFullScaleAndNeverPops() {
        GlyphHitKit.entries.flatMap { GlyphHitSynth.voices(it) }.forEach { sound ->
            assertTrue(sound.size in 1..GlyphHitSynth.SAMPLE_RATE / 2)
            val peak = sound.maxOf { abs(it.toInt()) }
            // Normalised to the peak, with room for several at once.
            assertEquals(GlyphHitSynth.PEAK * Short.MAX_VALUE, peak.toDouble(), Short.MAX_VALUE * 0.01)
            // Starts and ends at silence: no click on either edge.
            assertTrue(abs(sound.first().toInt()) < 200)
            assertTrue(abs(sound.last().toInt()) < 200)
        }
    }

    @Test
    fun theSoundsAreTheSameOnEveryPhone() {
        assertArrayEquals(GlyphHitSynth.don(), GlyphHitSynth.don())
        assertArrayEquals(GlyphHitSynth.osuTap(), GlyphHitSynth.osuTap())
    }

    @Test
    fun donIsLowAndKaIsBright() {
        // Zero crossings per second: a rough pitch, enough to tell a drum head from a rim.
        fun crossings(sound: ShortArray, frames: Int): Int =
            (1 until frames).count { (sound[it - 1] < 0) != (sound[it] < 0) }
        val window = GlyphHitSynth.SAMPLE_RATE / 20
        assertTrue(crossings(GlyphHitSynth.don(), window) * 3 < crossings(GlyphHitSynth.ka(), window))
        assertTrue(GlyphHitSynth.don().size > GlyphHitSynth.ka().size)
    }

    @Test
    fun taikoPlaysKaOnTheRimAndDonInTheMiddle() {
        val k = GlyphHitKit.TAIKO
        assertEquals(GlyphHitSynth.KA, GlyphHitSynth.voiceFor(k, GlyphLane.LEFT, CourseType.DANCE))
        assertEquals(GlyphHitSynth.DON, GlyphHitSynth.voiceFor(k, GlyphLane.DOWN, CourseType.DANCE))
        assertEquals(GlyphHitSynth.DON, GlyphHitSynth.voiceFor(k, GlyphLane.UP, CourseType.DANCE))
        assertEquals(GlyphHitSynth.KA, GlyphHitSynth.voiceFor(k, GlyphLane.RIGHT, CourseType.DANCE))
        val popn = GlyphLane.forCourse(CourseType.POPN).map { GlyphHitSynth.voiceFor(k, it, CourseType.POPN) }
        assertEquals(listOf(1, 1, 0, 0, 0, 0, 0, 1, 1), popn)
    }

    @Test
    fun popnRisesAlongTheRow() {
        val popn = GlyphLane.forCourse(CourseType.POPN).map { GlyphHitSynth.voiceFor(GlyphHitKit.POPN, it, CourseType.POPN) }
        assertEquals((0..8).toList(), popn)
        val dance = GlyphLane.DANCE.map { GlyphHitSynth.voiceFor(GlyphHitKit.POPN, it, CourseType.DANCE) }
        assertEquals(dance.sorted(), dance)
        assertEquals(4, dance.toSet().size)
        assertNotEquals(GlyphHitSynth.voices(GlyphHitKit.POPN)[0].toList(), GlyphHitSynth.voices(GlyphHitKit.POPN)[8].toList())
    }

    @Test
    fun aWavIsSixteenBitMonoAtTheRate() {
        val sound = GlyphHitSynth.ka()
        val wav = GlyphHitSynth.wav(sound)
        val head = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals(1, head.getShort(22).toInt())
        assertEquals(GlyphHitSynth.SAMPLE_RATE, head.getInt(24))
        assertEquals(16, head.getShort(34).toInt())
        assertEquals(sound.size * 2, head.getInt(40))
        assertEquals(44 + sound.size * 2, wav.size)
        assertEquals(sound[10], head.getShort(44 + 20))
    }

    @Test
    fun anUnknownKitIdIsOff() {
        assertEquals(GlyphHitKit.OFF, GlyphHitKit.fromId("sitar"))
        assertEquals(GlyphHitKit.POPN, GlyphHitKit.fromId("popn"))
    }
}
