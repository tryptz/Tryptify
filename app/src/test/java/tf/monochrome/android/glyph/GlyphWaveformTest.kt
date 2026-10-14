// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.training.GlyphWaveformBuilder

class GlyphWaveformTest {

    /** A quiet song with one loud hit at [hitAt] of the way through. */
    private fun feed(builder: GlyphWaveformBuilder, frames: Int, hitAt: Float, block: Int = 1_111) {
        val hit = (frames * hitAt).toInt()
        var at = 0
        while (at < frames) {
            val n = minOf(block, frames - at)
            val left = FloatArray(n) { 0.1f }
            val right = FloatArray(n) { -0.2f }
            if (hit in at until at + n) right[hit - at] = -0.9f
            builder.add(left, right, n)
            at += n
        }
    }

    @Test
    fun nothingDecodedIsNoBars() {
        assertEquals(emptyList<Float>(), GlyphWaveformBuilder().bars(220))
    }

    @Test
    fun barsAreTheLoudestOfEitherChannelWhereTheyHappen() {
        for (expected in listOf(0L, 2_000_000L, 500_000L)) {
            // Known length, unknown length (the buckets double as they go), and
            // a container that said the song was shorter than it is.
            val builder = GlyphWaveformBuilder(expected)
            feed(builder, 2_000_000, hitAt = 0.75f)
            val bars = builder.bars(200)
            assertEquals(200, bars.size)
            assertEquals(0.9f, bars.max(), 0f)
            val loudest = bars.indexOfFirst { it == 0.9f }
            assertTrue("hit at bar $loudest for expected=$expected", loudest in 148..151)
            assertTrue(bars.all { it == 0.9f || it == 0.2f })
            assertEquals(2_000_000L, builder.frames)
        }
    }

    @Test
    fun aSongShorterThanTheBarsStillFillsThem() {
        val builder = GlyphWaveformBuilder(50)
        feed(builder, 50, hitAt = 0.5f, block = 7)
        val bars = builder.bars(220)
        assertEquals(220, bars.size)
        assertTrue(bars.all { it in 0f..1f })
        assertEquals(0.9f, bars.max(), 0f)
    }

    @Test
    fun clipsAboveFullScale() {
        val builder = GlyphWaveformBuilder()
        builder.add(FloatArray(10) { 1.5f }, null, 10)
        assertEquals(listOf(1f, 1f), builder.bars(2))
    }
}
