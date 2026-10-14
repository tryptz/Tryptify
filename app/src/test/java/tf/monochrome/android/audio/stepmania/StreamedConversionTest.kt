// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.sampler.SampleEdits

/**
 * The pieces chart generation streams a song through, so that it never holds
 * the song whole: the envelope has to come out the same however the song was
 * cut, and the cutting has to cover every frame once.
 */
class StreamedConversionTest {

    private fun song(frames: Int, stereo: Boolean, seed: Int = 7): SampleEdits.Buffer {
        val random = Random(seed)
        val left = FloatArray(frames) { i ->
            // A kick every half second over a little noise, like a drum stem.
            val beat = if (i % 24_000 < 400) sin(2 * PI * 60 * i / 48_000).toFloat() * 0.9f else 0f
            beat + (random.nextFloat() - 0.5f) * 0.02f
        }
        val right = if (stereo) FloatArray(frames) { left[it] * 0.7f + (random.nextFloat() - 0.5f) * 0.01f } else null
        return SampleEdits.Buffer(left, right, 48_000)
    }

    @Test
    fun envelopeStreamedInAnyBlocksMatchesTheWholeBuffer() {
        for (stereo in listOf(true, false)) {
            val source = song(48_000 * 6 + 123, stereo)
            val whole = TransientEnvelopeBuilder(48_000).apply {
                add(source.left, source.right, 0, source.frames)
            }
            val streamed = TransientEnvelopeBuilder(48_000)
            val random = Random(3)
            var at = 0
            while (at < source.frames) {
                val next = minOf(source.frames, at + 1 + random.nextInt(5_000))
                streamed.add(source.left, source.right, at, next)
                at = next
            }
            assertEquals(whole.frames, streamed.frames)
            assertArrayEquals(whole.raw(), streamed.raw(), 0f)
        }
    }

    @Test
    fun envelopeHasOneValuePerHopAndAPartialLast() {
        val builder = TransientEnvelopeBuilder(48_000)
        val samples = FloatArray(480 * 3 + 10)
        builder.add(samples, null, 0, samples.size)
        assertEquals(4, builder.raw().size)
        val exact = TransientEnvelopeBuilder(48_000)
        exact.add(samples, null, 0, 480 * 3)
        assertEquals(3, exact.raw().size)
    }

    @Test
    fun streamedAnalysisMatchesTheWholeSongAnalysis() {
        val source = song(48_000 * 12, stereo = true)
        val analyzer = StemRhythmAnalyzer()
        val whole = analyzer.analyze(source, null)
        val builder = TransientEnvelopeBuilder(48_000)
        var at = 0
        while (at < source.frames) {
            val next = minOf(source.frames, at + 4_096)
            builder.add(source.left, source.right, at, next)
            at = next
        }
        val streamed = analyzer.analyze(builder)
        assertEquals(whole.bpm, streamed.bpm, 0f)
        assertEquals(whole.offsetSeconds, streamed.offsetSeconds, 0f)
        assertEquals(whole.durationSeconds, streamed.durationSeconds, 0f)
        assertArrayEquals(whole.gridStrengths, streamed.gridStrengths, 0f)
        assertEquals(120f, streamed.bpm, 1f)
    }

    @Test
    fun chunkerCoversEveryFrameOnceInOrderWithItsContext() {
        val total = 10_007
        val core = 1_000
        val context = 150
        val source = FloatArray(total) { it.toFloat() }
        val chunks = ArrayList<SeparationChunker.Chunk>()
        val chunker = SeparationChunker(core, context, stereo = true) { chunks += it }
        val random = Random(11)
        var at = 0
        while (at < total) {
            val n = minOf(total - at, 1 + random.nextInt(700))
            chunker.add(source.copyOfRange(at, at + n), null, n)
            at += n
        }
        chunker.finish()

        var expectedStart = 0L
        for (chunk in chunks) {
            assertEquals(expectedStart, chunk.startFrame)
            assertTrue(chunk.coreFrames in 1..core)
            // The samples are the song's own frame numbers, so each value says
            // exactly which frame landed where.
            val firstFrame = chunk.startFrame - chunk.coreOffset
            for (i in 0 until chunk.frames) {
                assertEquals((firstFrame + i).toFloat(), chunk.left[i], 0f)
                assertEquals(chunk.left[i], chunk.right!![i], 0f)
            }
            assertEquals(minOf(context.toLong(), chunk.startFrame), chunk.coreOffset.toLong())
            val after = chunk.frames - chunk.coreOffset - chunk.coreFrames
            val coreEnd = chunk.startFrame + chunk.coreFrames
            assertEquals(minOf(context.toLong(), total - coreEnd), after.toLong())
            expectedStart = coreEnd
        }
        assertEquals(total.toLong(), expectedStart)
        assertEquals((total + core - 1) / core, chunks.size)
    }

    @Test
    fun chunkerKeepsMonoMono() {
        val chunks = ArrayList<SeparationChunker.Chunk>()
        val chunker = SeparationChunker(100, 10, stereo = false) { chunks += it }
        chunker.add(FloatArray(250) { 1f }, null, 250)
        chunker.finish()
        assertEquals(3, chunks.size)
        chunks.forEach { assertNull(it.right) }
        assertEquals(250, chunks.sumOf { it.coreFrames })
    }
}
