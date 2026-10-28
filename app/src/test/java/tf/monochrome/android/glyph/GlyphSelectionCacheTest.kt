// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.chart.SscParser
import tf.monochrome.android.glyph.data.ChartSummaries
import tf.monochrome.android.glyph.training.GlyphWaveformBars
import tf.monochrome.android.glyph.training.GlyphWaveformCache

/**
 * What the song list and a pick keep between turns of the wheel: a song's
 * bars, and each chart's tempo and difficulties, so neither is read again
 * until it could have changed.
 */
class GlyphSelectionCacheTest {

    private val dir: File = Files.createTempDirectory("glyph-cache").toFile()

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    // ── waveform bars ───────────────────────────────────────────────────

    private fun bars(level: Float) = GlyphWaveformBars(listOf(level), listOf(level))

    @Test
    fun theLeastRecentlyUsedSongGoesFirst() {
        val cache = GlyphWaveformCache(capacity = 2)
        cache.put("a", bars(0.1f))
        cache.put("b", bars(0.2f))
        // Looked at again, so "b" is the oldest now.
        assertNotNull(cache.get("a"))
        cache.put("c", bars(0.3f))
        assertEquals(2, cache.size)
        assertNull("the oldest was dropped", cache.get("b"))
        assertEquals(bars(0.1f), cache.get("a"))
        assertEquals(bars(0.3f), cache.get("c"))
    }

    @Test
    fun aDecodeThatGotNothingIsNotKept() {
        val cache = GlyphWaveformCache()
        cache.put("broken", GlyphWaveformBars(emptyList(), emptyList()))
        assertNull("tried again next time, as before", cache.get("broken"))
        assertEquals(0, cache.size)
    }

    // ── chart summaries ─────────────────────────────────────────────────

    private fun chart(bpm: Int, vararg difficulties: String): String = buildString {
        append("#TITLE:Song;\n#OFFSET:0;\n#BPMS:0=$bpm;\n")
        for (difficulty in difficulties) {
            append("#NOTEDATA:;\n#STEPSTYPE:dance-single;\n#DIFFICULTY:$difficulty;\n#METER:3;\n")
            append("#NOTES:\n1000\n0100\n0010\n0001\n;\n")
        }
    }

    @Test
    fun aChartIsReadOnceUntilItsFileChanges() {
        var reads = 0
        val summaries = ChartSummaries { file -> reads++; SscParser.parse(file.readText()) }
        val file = File(dir, "a.ssc").apply { writeText(chart(120, "Easy", "Hard")) }

        val first = summaries.of(file)
        assertEquals(120f, first?.bpm)
        assertEquals(listOf(StepManiaDifficulty.EASY, StepManiaDifficulty.HARD), first?.difficulties)
        // The same as the full parse the list used to make of it.
        val parsed = SscParser.parse(file.readText())!!
        assertEquals(parsed.listedBpm, first?.bpm)
        assertEquals(parsed.availableDifficulties, first?.difficulties)

        summaries.of(file)
        summaries.of(file)
        assertEquals("asked three times, read once", 1, reads)

        // Rewritten: a new length is a new chart.
        file.writeText(chart(150, "Easy", "Medium", "Hard"))
        assertEquals(150f, summaries.of(file)?.bpm)
        assertEquals(2, reads)

        // The same length, a later time: also read again.
        val sameLength = chart(160, "Easy", "Medium", "Hard")
        assertEquals(file.length(), sameLength.length.toLong())
        file.writeText(sameLength)
        file.setLastModified(file.lastModified() + 5_000L)
        assertEquals(160f, summaries.of(file)?.bpm)
        assertEquals(3, reads)
    }

    @Test
    fun anUnreadableChartIsRememberedAsUnreadable() {
        var reads = 0
        val summaries = ChartSummaries { file -> reads++; SscParser.parse(file.readText()) }
        val file = File(dir, "bad.ssc").apply { writeText("#TITLE:No charts in here;\n") }
        assertNull(summaries.of(file))
        assertNull(summaries.of(file))
        assertEquals(1, reads)
    }
}
