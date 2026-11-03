// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.glyph.engine.GlyphGhostRace
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphScore

/** Racing your best: its ghost replayed as a score, at the times it landed. */
class GlyphGhostRaceTest {

    private val judgements = listOf(GlyphJudgement.MARVELOUS, GlyphJudgement.GREAT, GlyphJudgement.MISS, GlyphJudgement.PERFECT)
    private val ghost = GlyphGhost(
        timesMs = listOf(1_000, 2_000, 3_000, 4_000),
        lanes = listOf(0, 1, 2, 3),
        offsetsMs = listOf(0, 30, 0, -10),
        judgements = judgements.map { it.ordinal },
    )

    @Test
    fun theBestRunScoresAsTheSongReachesEachOfItsSteps() {
        val race = GlyphGhostRace(ghost, totalJudgements = 4, finalScore = 9_999)
        assertFalse(race.advanceTo(0.5f))
        assertEquals(0, race.score)
        assertTrue(race.advanceTo(2.0f))
        // Scored by the same rules as a run: the first two steps, in order.
        val same = GlyphScore(4).apply {
            record(GlyphJudgement.MARVELOUS, 0f)
            record(GlyphJudgement.GREAT, 0.03f)
        }
        assertEquals(same.score, race.score)
        assertEquals(2, race.combo)
        race.advanceTo(3.5f)
        assertEquals("a miss breaks its combo too", 0, race.combo)
    }

    @Test
    fun itEndsOnTheScoreItsResultsShowed() {
        val race = GlyphGhostRace(ghost, totalJudgements = 4, finalScore = 9_999)
        race.advanceTo(10f)
        assertEquals(9_999, race.score)
        assertFalse("nothing left to play", race.advanceTo(11f))
    }
}
