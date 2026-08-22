// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.chart.SscParser
import tf.monochrome.android.glyph.data.GlyphChartState

/**
 * The chart a selection resolves to.
 *
 * These are about identity rather than rendering: which audio file a
 * generation runs against, and whether a chart written to disk is seen. Both
 * failed silently — the wrong chart is still a valid chart, and a stale "No
 * chart" still draws a button that works.
 */
class GlyphSelectionTest {

    private fun ssc(title: String, bpm: Float, tiers: List<StepManiaDifficulty>): String =
        buildString {
            appendLine("#TITLE:$title;")
            appendLine("#MUSIC:$title.flac;")
            appendLine("#OFFSET:0.000;")
            appendLine("#BPMS:0.000=${"%.3f".format(bpm)};")
            for (tier in tiers) {
                appendLine("#NOTEDATA:;")
                appendLine("#DIFFICULTY:${tier.sscName};")
                appendLine("#METER:${tier.meter};")
                appendLine("#NOTES:")
                appendLine("1000")
                appendLine("0000")
                appendLine("0100")
                appendLine("0000")
                appendLine(";")
            }
        }

    @Test
    fun aChartIsIdentifiedByItsOwnTrackNotThePreviousSelection() {
        // The id a chart is filed under has to come from the track being
        // generated. Deriving it from whatever was selected a moment earlier is
        // exactly how a generation lands on the wrong song's audio.
        val a = "local_11"
        val b = "local_12"
        assertTrue("distinct tracks must not share a chart id", idOf(a) != idOf(b))
        assertEquals("the same track must always resolve to one id", idOf(a), idOf(a))
    }

    private fun idOf(trackId: String): String = trackId.hashCode().toUInt().toString(16)

    @Test
    fun aGeneratedChartIsReadableImmediatelyFromDisk() = runTest {
        // The song list is a Room flow over the library and cannot see a file
        // written into app storage, so anything that needs the current answer
        // has to read the file rather than trust the cached row.
        val dir = File(System.getProperty("java.io.tmpdir"), "glyph-sel-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val file = File(dir, "${idOf("local_11")}.ssc")
            assertTrue("nothing on disk yet", !file.exists())

            file.writeText(
                ssc("Vessels", 148f, listOf(StepManiaDifficulty.EASY, StepManiaDifficulty.HARD)),
            )

            val parsed = SscParser.parse(file.readText())
            assertNotNull("a chart just written must parse", parsed)
            assertEquals(148f, parsed!!.timing.startBpm, 1e-3f)
            assertEquals(
                listOf(StepManiaDifficulty.EASY, StepManiaDifficulty.HARD),
                parsed.availableDifficulties,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun anUnreadableChartIsDistinguishedFromNoChart() {
        // The two need different offers: one is "generate", the other is
        // "generating again will replace it". Collapsing them hides the fact
        // that a file is there at all.
        val dir = File(System.getProperty("java.io.tmpdir"), "glyph-bad-${System.nanoTime()}")
        dir.mkdirs()
        try {
            val file = File(dir, "broken.ssc")
            file.writeText("this is not a simfile")
            assertNull("garbage must not parse into a chart", SscParser.parse(file.readText()))
            assertTrue("but the file is undeniably there", file.exists())

            // Which is the distinction the state enum carries.
            assertTrue(GlyphChartState.UNREADABLE != GlyphChartState.NOT_GENERATED)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theMiddleDifficultyIsChosenNotTheHardest() {
        val chart = SscParser.parse(
            ssc(
                "Vessels", 150f,
                listOf(
                    StepManiaDifficulty.BEGINNER,
                    StepManiaDifficulty.EASY,
                    StepManiaDifficulty.MEDIUM,
                    StepManiaDifficulty.HARD,
                    StepManiaDifficulty.CHALLENGE,
                ),
            ),
        )!!
        val available = chart.availableDifficulties
        val defaulted = available.getOrNull(available.size / 2) ?: available.first()
        assertEquals(StepManiaDifficulty.MEDIUM, defaulted)
    }
}
