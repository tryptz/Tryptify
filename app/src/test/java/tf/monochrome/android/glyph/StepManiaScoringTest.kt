// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.rankHighScores
import tf.monochrome.android.glyph.engine.GlyphAppearance
import tf.monochrome.android.glyph.engine.GlyphFailType
import tf.monochrome.android.glyph.engine.GlyphFullCombo
import tf.monochrome.android.glyph.engine.GlyphJudge
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphJudgementEvent
import tf.monochrome.android.glyph.engine.GlyphStepGrade
import tf.monochrome.android.glyph.engine.GlyphTurn
import tf.monochrome.android.glyph.engine.StepManiaScoreKeeper
import tf.monochrome.android.ui.glyph.GlyphComboRays

/**
 * StepMania's scoring, as the default theme's metrics define it: the numbers
 * here are StepMania's, so a run reads the same percent and grade it would on
 * a cabinet.
 */
class StepManiaScoringTest {

    private fun tap(judgement: GlyphJudgement, offset: Float? = 0f) =
        GlyphJudgementEvent(GlyphLane.LEFT, judgement, offset, songSeconds = 1f)

    private fun tail(held: Boolean) = GlyphJudgementEvent(
        GlyphLane.LEFT, if (held) GlyphJudgement.MARVELOUS else GlyphJudgement.MISS, null, 1f, isTail = true,
    )

    private val mine = GlyphJudgementEvent(GlyphLane.LEFT, GlyphJudgement.MISS, 0f, 1f, isMine = true)

    @Test
    fun allFlawlessWithEveryHoldHeldIsTheStarredTier() {
        val keeper = StepManiaScoreKeeper(possibleJudgements = 4)
        repeat(3) { keeper.record(tap(GlyphJudgement.MARVELOUS)) }
        keeper.record(tail(held = true))
        assertEquals(12, keeper.dancePoints)
        assertEquals(1f, keeper.percent, 0f)
        assertEquals(GlyphFullCombo.FLAWLESS, keeper.fullCombo)
        assertEquals(GlyphStepGrade.TIER01, keeper.grade)
        assertEquals("100.00%", keeper.percentLabel)
    }

    @Test
    fun allPerfectOrBetterIsAaaWhateverThePercent() {
        val keeper = StepManiaScoreKeeper(possibleJudgements = 4)
        repeat(4) { keeper.record(tap(GlyphJudgement.PERFECT)) }
        // Perfect is 2 of 3 points, so the percent is two thirds, and still AAA.
        assertEquals(8f / 12f, keeper.percent, 1e-6f)
        assertEquals(GlyphStepGrade.TIER02, keeper.grade)
    }

    @Test
    fun thePercentPicksTheTierBelowAaa() {
        val keeper = StepManiaScoreKeeper(possibleJudgements = 100)
        repeat(97) { keeper.record(tap(GlyphJudgement.MARVELOUS)) }
        repeat(3) { keeper.record(tap(GlyphJudgement.MISS)) }
        assertEquals(0.97f, keeper.percent, 1e-6f)
        assertNull(keeper.fullCombo)
        assertEquals(GlyphStepGrade.TIER04, keeper.grade) // AA: 96% and up
    }

    @Test
    fun minesTakePointsButNeverPushThePercentBelowZero() {
        val keeper = StepManiaScoreKeeper(possibleJudgements = 2)
        keeper.record(mine)
        keeper.record(mine)
        assertEquals(-4, keeper.dancePoints)
        assertEquals(0f, keeper.percent, 0f)
        assertEquals(2, keeper.minesHit)
    }

    @Test
    fun aDroppedHoldBreaksTheFullCombo() {
        val keeper = StepManiaScoreKeeper(possibleJudgements = 2)
        keeper.record(tap(GlyphJudgement.MARVELOUS))
        keeper.record(tail(held = false))
        assertNull(keeper.fullCombo)
        assertEquals(1, keeper.holdsLetGo)
    }

    @Test
    fun theLifeBarFollowsStepManiasChanges() {
        val keeper = StepManiaScoreKeeper(possibleJudgements = 20)
        assertEquals(0.5f, keeper.life, 0f)
        keeper.record(tap(GlyphJudgement.MARVELOUS))
        assertEquals(0.508f, keeper.life, 1e-6f)
        keeper.record(tap(GlyphJudgement.MISS))
        assertEquals(0.428f, keeper.life, 1e-6f)
        keeper.record(mine)
        assertEquals(0.268f, keeper.life, 1e-6f)
    }

    @Test
    fun afterALossTheBarRefillsOnlyFromTheFifthStep() {
        // RegenComboAfterMiss: the first four good steps after a miss refill
        // nothing; the fifth refills as usual.
        val keeper = StepManiaScoreKeeper(possibleJudgements = 20)
        keeper.record(tap(GlyphJudgement.MISS))
        val afterMiss = keeper.life
        repeat(4) { keeper.record(tap(GlyphJudgement.MARVELOUS)) }
        assertEquals(afterMiss, keeper.life, 0f)
        keeper.record(tap(GlyphJudgement.MARVELOUS))
        assertEquals(afterMiss + 0.008f, keeper.life, 1e-6f)
    }

    @Test
    fun aFullBarLosesATenthAtLeast() {
        // HarshHotLifePenalty.
        val keeper = StepManiaScoreKeeper(possibleJudgements = 200)
        repeat(80) { keeper.record(tap(GlyphJudgement.MARVELOUS)) }
        assertEquals(1f, keeper.life, 0f)
        keeper.record(tap(GlyphJudgement.BOO))
        assertEquals(0.9f, keeper.life, 1e-6f)
    }

    @Test
    fun theFourFailTypesBehaveAsStepManiasDo() {
        fun drain(type: GlyphFailType) = StepManiaScoreKeeper(possibleJudgements = 60, failType = type).apply {
            repeat(7) { record(tap(GlyphJudgement.MISS)) }
        }
        val immediate = drain(GlyphFailType.IMMEDIATE)
        assertTrue(immediate.failed)
        assertTrue(immediate.stopsRun)
        assertEquals(GlyphStepGrade.FAILED, immediate.grade)

        val carryOn = drain(GlyphFailType.IMMEDIATE_CONTINUE)
        assertTrue(carryOn.failed)
        assertFalse("plays on to the end", carryOn.stopsRun)
        repeat(40) { carryOn.record(tap(GlyphJudgement.MARVELOUS)) }
        assertEquals("the bar stays where it emptied", 0f, carryOn.life, 0f)
        assertEquals(GlyphStepGrade.FAILED, carryOn.grade)

        val atEnd = drain(GlyphFailType.END_OF_SONG)
        assertFalse(atEnd.failed)
        assertEquals("empty at the end is a fail", GlyphStepGrade.FAILED, atEnd.grade)
        repeat(40) { atEnd.record(tap(GlyphJudgement.MARVELOUS)) }
        assertTrue("refilled before the end, it passes", atEnd.life > 0f)
        assertTrue(atEnd.grade != GlyphStepGrade.FAILED)

        val off = drain(GlyphFailType.OFF)
        assertFalse(off.failed)
        assertTrue(off.grade != GlyphStepGrade.FAILED)
    }

    @Test
    fun thePercentIsTruncatedNotRounded() {
        assertEquals("99.99%", StepManiaScoreKeeper.formatPercent(0.99999f))
        assertEquals("0.00%", StepManiaScoreKeeper.formatPercent(0f))
        assertEquals("96.50%", StepManiaScoreKeeper.formatPercent(0.965f))
    }

    @Test
    fun thePercentReadsAsTheFormatStringDidInEveryLanguageOfTheApp() {
        // Made by hand now, not by String.format: held to the old format's
        // text, in each of the app's languages as the phone's own, and over
        // every hundredth from below zero to past full, with float noise.
        val before = java.util.Locale.getDefault()
        try {
            for (tag in listOf("en", "de", "es", "fr", "it", "ja", "tr", "zh-CN")) {
                val locale = java.util.Locale.forLanguageTag(tag)
                java.util.Locale.setDefault(locale)
                for (step in -150..10_150) {
                    for (nudge in listOf(-0.000004f, 0f, 0.000004f)) {
                        val percent = step / 10_000f + nudge
                        val hundredths = kotlin.math.floor(percent.toDouble() * 10_000.0 + 1e-3).toLong()
                        val expected = String.format(locale, "%d.%02d%%", hundredths / 100, hundredths % 100)
                        assertEquals("$percent in $tag", expected, StepManiaScoreKeeper.formatPercent(percent))
                    }
                }
            }
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun turnsArePermutationsAndLeftAndRightUndoEachOther() {
        for (turn in GlyphTurn.entries) {
            val mapped = GlyphLane.entries.map { turn.map(it, shuffleSeed = 42L) }
            assertEquals("$turn must keep every lane", GlyphLane.entries.toSet(), mapped.toSet())
        }
        for (lane in GlyphLane.entries) {
            assertEquals(lane, GlyphTurn.RIGHT.map(GlyphTurn.LEFT.map(lane)))
        }
        assertEquals(GlyphLane.LEFT, GlyphTurn.LEFT.map(GlyphLane.UP))
        assertEquals(GlyphLane.RIGHT, GlyphTurn.RIGHT.map(GlyphLane.UP))
        // The same chart shuffles the same way twice.
        assertEquals(GlyphTurn.shuffleOrder(7L), GlyphTurn.shuffleOrder(7L))
    }

    @Test
    fun appearanceShowsWhatEachModPromises() {
        assertEquals(1f, GlyphAppearance.VISIBLE.alphaAt(0.1f), 0f)
        assertEquals(0f, GlyphAppearance.HIDDEN.alphaAt(0.1f), 0f)
        assertEquals(1f, GlyphAppearance.HIDDEN.alphaAt(0.9f), 0f)
        assertEquals(1f, GlyphAppearance.SUDDEN.alphaAt(0.1f), 0f)
        assertEquals(0f, GlyphAppearance.SUDDEN.alphaAt(0.9f), 0f)
        assertEquals(0f, GlyphAppearance.STEALTH.alphaAt(0.5f), 0f)
    }

    @Test
    fun judgeFourIsTheStandardWindows() {
        assertEquals(1f, GlyphJudge.J4.scale, 0f)
        assertEquals(GlyphJudge.J4, GlyphJudge.forScale(1f))
        assertEquals(GlyphJudge.JUSTICE, GlyphJudge.forScale(0.33f))
    }

    @Test
    fun theRaysClimbEveryFiftyComboAndDropBackOnABreak() {
        assertEquals(0, GlyphComboRays.tier(0))
        assertEquals(0, GlyphComboRays.tier(49))
        assertEquals(1, GlyphComboRays.tier(50))
        assertEquals(2, GlyphComboRays.tier(100))
        assertEquals(3, GlyphComboRays.tier(150))
        assertEquals(GlyphComboRays.RAINBOW_TIER, GlyphComboRays.tier(200))
        assertEquals(GlyphComboRays.RAINBOW_TIER, GlyphComboRays.tier(5000))
        assertEquals(Color.White, GlyphComboRays.colour(10, 0L))
        // The rainbow keeps turning.
        val a = GlyphComboRays.colour(250, 0L)
        val b = GlyphComboRays.colour(250, 600_000_000L)
        assertTrue(a != b)
    }

    private fun run(id: String, percent: Float, score: Int, at: Long, segment: Boolean = false) = GlyphAttempt(
        id = id,
        chartId = "c",
        songTitle = "S",
        difficulty = StepManiaDifficulty.HARD,
        playedAtEpochMs = at,
        score = score,
        accuracy = percent,
        maxCombo = 0,
        percent = percent,
        segmentStartSeconds = if (segment) 1f else null,
    )

    @Test
    fun highScoresRankByPercentThenPointsThenWhoGotThereFirst() {
        val ranked = rankHighScores(
            listOf(
                run("late-tie", 0.9f, 900, at = 20),
                run("best", 0.95f, 800, at = 30),
                run("early-tie", 0.9f, 900, at = 10),
                run("segment", 1f, 1_000_000, at = 5, segment = true),
                run("more-points", 0.9f, 950, at = 40),
            ),
        )
        assertEquals(listOf("best", "more-points", "early-tie", "late-tie"), ranked.map { it.id })
    }

    @Test
    fun aRunAtAnotherRateOrJudgeIsMarkedOnTheTable() {
        assertEquals("", run("a", 0.9f, 1, 1).modsLabel)
        assertEquals("1.5x", run("a", 0.9f, 1, 1).copy(speed = 1.5f).modsLabel)
        assertEquals("J5", run("a", 0.9f, 1, 1).copy(judgeScale = 0.84f).modsLabel)
        assertEquals("0.8x J7", run("a", 0.9f, 1, 1).copy(speed = 0.8f, judgeScale = 0.5f).modsLabel)
    }
}
