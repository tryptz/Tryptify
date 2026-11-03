// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.data.GlyphSyncTrack
import tf.monochrome.android.glyph.data.GlyphTapSync
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.random.Random

/** Tap to sync: which click a tap answers, what the taps say, and the track itself. */
class GlyphTapSyncTest {

    private val first = GlyphSyncTrack.FIRST_BEAT_SECONDS
    private val beat = GlyphSyncTrack.BEAT_SECONDS

    /** The song time of a tap [lateMs] after click [index]. */
    private fun at(index: Int, lateMs: Float) = first + index * beat + lateMs / 1000f

    private fun tapAll(sync: GlyphTapSync, lateMs: (Int) -> Float, clicks: Iterable<Int> = 0 until GlyphSyncTrack.BEATS) {
        for (i in clicks) sync.tap(at(i, lateMs(i)))
    }

    @Test
    fun theTrackIsAt140Bpm() {
        assertEquals(140, GlyphSyncTrack.BPM)
        assertEquals(0.4286f, GlyphSyncTrack.BEAT_SECONDS, 0.0001f)
    }

    @Test
    fun bluetoothLatenessLongerThanABeatHalfIsReadWhole() {
        // 350 ms is past half of a 428 ms beat: "the nearest click" would call
        // it 78 ms early. Anchored on the first click, it reads as it is.
        val sync = GlyphTapSync()
        tapAll(sync, { 350f })
        assertTrue(sync.done)
        assertEquals(350, sync.offsetMs)
        assertTrue(sync.steady)
    }

    @Test
    fun wiredHeadphonesReadNearZero() {
        val sync = GlyphTapSync()
        tapAll(sync, { -12f })
        assertEquals(-12, sync.offsetMs)
        assertFalse(sync.couldBeAClickOff)
    }

    @Test
    fun theReadingIsTheMedianThroughHumanJitter() {
        val random = Random(7)
        val sync = GlyphTapSync()
        tapAll(sync, { 240f + (random.nextFloat() * 2f - 1f) * 25f })
        assertTrue(abs(sync.offsetMs!! - 240) <= 8)
        assertTrue(sync.steady)
    }

    @Test
    fun everyTapCountsFromTheFirstAndTheCountStopsWhenFull() {
        val sync = GlyphTapSync()
        assertTrue(sync.tap(at(0, 200f)))
        assertTrue(sync.tap(at(1, 200f)))
        assertEquals(2, sync.taps)
        tapAll(sync, { 200f }, 2 until GlyphSyncTrack.BEATS)
        assertEquals(GlyphTapSync.NEEDED, sync.taps)
        assertFalse(sync.tap(at(40, 200f)))
    }

    @Test
    fun aSlowFirstReactionDoesNotCostTheSecondTap() {
        // The first click after the silence is reacted to, 260 ms after it is
        // heard; the rest are tapped in time. The second tap used to be thrown
        // away as a repeat, and the count stood still for the first few taps.
        val sync = GlyphTapSync()
        assertTrue(sync.tap(at(0, 250f + 260f)))
        assertTrue(sync.tap(at(1, 250f)))
        assertTrue(sync.tap(at(2, 250f)))
        assertEquals(3, sync.taps)
        tapAll(sync, { 250f }, 3 until GlyphSyncTrack.BEATS)
        assertEquals(250, sync.offsetMs)
        assertTrue(sync.steady)
    }

    @Test
    fun aSlowFirstReactionDoesNotReadAClickOff() {
        // 150 ms headphones and a 350 ms first reaction used to read 570 ms.
        val sync = GlyphTapSync()
        sync.tap(at(0, 150f + 350f))
        tapAll(sync, { 150f }, 1 until GlyphSyncTrack.BEATS)
        assertEquals(150, sync.offsetMs)
        assertFalse(sync.couldBeAClickOff)
    }

    @Test
    fun wiredHeadphonesWithAReactionReadNearZero() {
        // Was read as 432 ms: the reaction pushed the anchor onto the next click.
        val sync = GlyphTapSync()
        sync.tap(at(0, 250f))
        tapAll(sync, { 0f }, 1 until GlyphSyncTrack.BEATS)
        assertEquals(0, sync.offsetMs)
    }

    @Test
    fun nothingIsReadUntilEnoughTapsCount() {
        val sync = GlyphTapSync()
        tapAll(sync, { 100f }, 0 until GlyphTapSync.MIN_TAPS - 1)
        assertNull(sync.offsetMs)
        assertNull(sync.spreadMs)
        sync.tap(at(GlyphTapSync.MIN_TAPS - 1, 100f))
        assertEquals(100, sync.offsetMs)
    }

    @Test
    fun aSecondTapOnOneClickIsIgnored() {
        val sync = GlyphTapSync()
        tapAll(sync, { 300f }, 0 until 4)
        val before = sync.taps
        assertFalse(sync.tap(at(3, 330f)))
        assertEquals(before, sync.taps)
    }

    @Test
    fun skippedClicksDoNotShiftTheReading() {
        val sync = GlyphTapSync()
        tapAll(sync, { 280f }, (0 until GlyphSyncTrack.BEATS).filter { it % 3 != 1 })
        assertEquals(280, sync.offsetMs)
    }

    @Test
    fun aTapInTheLeadInIsIgnored() {
        val sync = GlyphTapSync()
        assertFalse(sync.tap(0.5f))
        tapAll(sync, { 150f })
        assertEquals(150, sync.offsetMs)
    }

    @Test
    fun oneStrayTapBarelyMovesIt() {
        val sync = GlyphTapSync()
        tapAll(sync, { 200f }, 0 until 8)
        // Half a beat off, between two clicks.
        sync.tap(at(8, 200f + beat * 500f))
        tapAll(sync, { 200f }, 10 until GlyphSyncTrack.BEATS)
        assertTrue(abs(sync.offsetMs!! - 200) <= 2)
    }

    @Test
    fun aReadingAsLateAsAClickIsFlagged() {
        // Wired headphones, but the first tap came on the second click.
        val sync = GlyphTapSync()
        tapAll(sync, { 10f }, 1 until GlyphSyncTrack.BEATS)
        assertTrue(sync.offsetMs!! > 400)
        assertTrue(sync.couldBeAClickOff)
    }

    @Test
    fun unevenTapsAreNotSteady() {
        val sync = GlyphTapSync()
        tapAll(sync, { i -> 200f + if (i % 2 == 0) 70f else -70f })
        assertFalse(sync.steady)
    }

    @Test
    fun theTrackIsAMonoWavWithAClickOnEachBeat() {
        val wav = GlyphSyncTrack.wav()
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, header.getShort(22).toInt())
        assertEquals(GlyphSyncTrack.SAMPLE_RATE, header.getInt(24))
        assertEquals(wav.size - 44, header.getInt(40))

        fun peak(fromSeconds: Float, toSeconds: Float): Int {
            val from = (fromSeconds * GlyphSyncTrack.SAMPLE_RATE).toInt()
            val to = (toSeconds * GlyphSyncTrack.SAMPLE_RATE).toInt()
            return (from until to).maxOf { abs(header.getShort(44 + it * 2).toInt()) }
        }
        // Silence before the first click, and between clicks once each rings out.
        assertEquals(0, peak(0f, first - 0.001f))
        for (i in 0 until GlyphSyncTrack.BEATS) {
            val click = first + i * beat
            assertTrue("click $i", peak(click, click + 0.01f) > 8_000)
            assertEquals("gap $i", 0, peak(click + 0.05f, click + beat - 0.001f))
        }
    }
}
