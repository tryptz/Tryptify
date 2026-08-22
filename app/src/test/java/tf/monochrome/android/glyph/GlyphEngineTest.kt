// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.engine.GlyphClock
import tf.monochrome.android.glyph.engine.GlyphGameplayEngine
import tf.monochrome.android.glyph.engine.GlyphGrade
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.engine.GlyphScore
import tf.monochrome.android.glyph.engine.GlyphTimingWindows

class GlyphEngineTest {

    private fun note(
        seconds: Float,
        lane: GlyphLane = GlyphLane.LEFT,
        type: GlyphNoteType = GlyphNoteType.TAP,
        endSeconds: Float = seconds,
    ) = GlyphNote(
        lane = lane,
        type = type,
        beat = seconds * 2f,
        timeSeconds = seconds,
        endTimeSeconds = endSeconds,
        division = GlyphBeatDivision.QUARTER,
        measure = 0,
    )

    private fun chart(vararg notes: GlyphNote) = GlyphChart(
        difficulty = StepManiaDifficulty.MEDIUM,
        meter = 8,
        chartName = "Test",
        stepsType = "dance-single",
        notes = notes.sortedBy { it.timeSeconds },
    )

    // ── timing windows ──────────────────────────────────────────────────

    @Test
    fun windowBoundariesAreInclusiveAtEachEdge() {
        val windows = GlyphTimingWindows.STANDARD

        // Exactly on a boundary belongs to the better judgement. A boundary
        // that fell the other way would make the tightest window unreachable.
        assertEquals(GlyphJudgement.MARVELOUS, windows.judge(0.0225f))
        assertEquals(GlyphJudgement.PERFECT, windows.judge(0.02251f))
        assertEquals(GlyphJudgement.PERFECT, windows.judge(0.045f))
        assertEquals(GlyphJudgement.GREAT, windows.judge(0.0451f))
        assertEquals(GlyphJudgement.GREAT, windows.judge(0.090f))
        assertEquals(GlyphJudgement.GOOD, windows.judge(0.0901f))
        assertEquals(GlyphJudgement.GOOD, windows.judge(0.135f))
        assertEquals(GlyphJudgement.BOO, windows.judge(0.1351f))
        assertEquals(GlyphJudgement.BOO, windows.judge(0.180f))
        assertNull("past the last window there is nothing to hit", windows.judge(0.1801f))
    }

    @Test
    fun earlyAndLateAreJudgedIdentically() {
        val windows = GlyphTimingWindows.STANDARD
        for (magnitude in listOf(0.01f, 0.03f, 0.07f, 0.12f, 0.17f)) {
            assertEquals(
                "sign must not change the judgement at $magnitude",
                windows.judge(magnitude),
                windows.judge(-magnitude),
            )
        }
    }

    @Test
    fun scalingWindowsKeepsThemOrdered() {
        val tight = GlyphTimingWindows.STANDARD.scaled(0.5f)
        val loose = GlyphTimingWindows.STANDARD.scaled(2f)

        assertTrue(tight.marvelousSeconds < GlyphTimingWindows.STANDARD.marvelousSeconds)
        assertTrue(loose.booSeconds > GlyphTimingWindows.STANDARD.booSeconds)
        // The ordering invariant the constructor enforces must survive scaling.
        assertTrue(tight.marvelousSeconds <= tight.perfectSeconds)
        assertTrue(tight.perfectSeconds <= tight.greatSeconds)
        assertTrue(loose.greatSeconds <= loose.goodSeconds)
        assertTrue(loose.goodSeconds <= loose.booSeconds)
        // Out-of-range scales clamp rather than throw.
        assertEquals(tight, GlyphTimingWindows.STANDARD.scaled(0.01f))
    }

    // ── combo and scoring ───────────────────────────────────────────────

    @Test
    fun comboSurvivesGoodAndBreaksOnBooAndMiss() {
        val score = GlyphScore(totalJudgements = 6)

        score.record(GlyphJudgement.MARVELOUS, 0f)
        score.record(GlyphJudgement.PERFECT, 0.03f)
        score.record(GlyphJudgement.GREAT, 0.06f)
        score.record(GlyphJudgement.GOOD, 0.1f)
        assertEquals("Good keeps a combo alive", 4, score.combo)

        score.record(GlyphJudgement.BOO, 0.15f)
        assertEquals("Boo breaks it", 0, score.combo)

        score.record(GlyphJudgement.MARVELOUS, 0f)
        assertEquals(1, score.combo)
        assertEquals("the peak is remembered after a break", 4, score.maxCombo)

        score.record(GlyphJudgement.MISS, null)
        assertEquals(0, score.combo)
        assertEquals(4, score.maxCombo)
    }

    @Test
    fun accuracyAndGradeFollowTheWeights() {
        val perfectRun = GlyphScore(totalJudgements = 4)
        repeat(4) { perfectRun.record(GlyphJudgement.MARVELOUS, 0f) }
        assertEquals(1f, perfectRun.finalAccuracy, 1e-5f)
        assertEquals(GlyphScore.MAX_SCORE, perfectRun.score)
        assertEquals(GlyphGrade.SSS, perfectRun.grade)

        val mixed = GlyphScore(totalJudgements = 4)
        mixed.record(GlyphJudgement.MARVELOUS, 0f)
        mixed.record(GlyphJudgement.GREAT, 0.05f)
        mixed.record(GlyphJudgement.GOOD, 0.1f)
        mixed.record(GlyphJudgement.MISS, null)
        // (1.00 + 0.70 + 0.40 + 0.00) / 4
        assertEquals(0.525f, mixed.finalAccuracy, 1e-5f)
        assertEquals(GlyphGrade.D, mixed.grade)

        // Live accuracy reads over what has been judged, not the whole chart.
        val partial = GlyphScore(totalJudgements = 100)
        partial.record(GlyphJudgement.MARVELOUS, 0f)
        assertEquals("live accuracy is over judged notes", 1f, partial.accuracy, 1e-5f)
        assertEquals("final accuracy is over the chart", 0.01f, partial.finalAccuracy, 1e-5f)
    }

    @Test
    fun earlyLateAndConsistencyAreReportedSeparately() {
        val score = GlyphScore(totalJudgements = 4)
        score.record(GlyphJudgement.PERFECT, -0.030f)
        score.record(GlyphJudgement.PERFECT, -0.030f)
        score.record(GlyphJudgement.PERFECT, -0.030f)
        score.record(GlyphJudgement.PERFECT, -0.030f)

        // A consistently early player: a large mean, almost no spread. Reporting
        // only the mean would hide that this is a calibration problem.
        assertEquals(4, score.early)
        assertEquals(0, score.late)
        assertEquals(-0.030f, score.meanOffsetSeconds, 1e-5f)
        assertEquals(0f, score.offsetDeviationSeconds, 1e-4f)
        assertEquals("30 ms early", score.snapshot().offsetLabel)

        val scattered = GlyphScore(totalJudgements = 2)
        scattered.record(GlyphJudgement.GREAT, -0.060f)
        scattered.record(GlyphJudgement.GREAT, 0.060f)
        assertEquals(0f, scattered.meanOffsetSeconds, 1e-5f)
        assertTrue("spread must survive a zero mean", scattered.offsetDeviationSeconds > 0.05f)
        assertEquals("on time", scattered.snapshot().offsetLabel)
    }

    @Test
    fun missesCarryNoOffsetIntoTheStatistics() {
        val score = GlyphScore(totalJudgements = 2)
        score.record(GlyphJudgement.MARVELOUS, 0.001f)
        score.record(GlyphJudgement.MISS, null)
        // A miss has no timing to speak of; letting it count as "on time" would
        // drag the mean toward zero and flatter the player.
        assertEquals(1, score.early + score.late + 1)
        assertEquals(0.001f, score.meanOffsetSeconds, 1e-6f)
    }

    // ── the engine ──────────────────────────────────────────────────────

    @Test
    fun aTapIsJudgedByHowFarItIsFromTheNote() {
        val engine = GlyphGameplayEngine(chart(note(1f)))
        engine.advanceTo(0.9f)

        val event = engine.press(GlyphLane.LEFT, 1.01f)
        assertNotNull(event)
        assertEquals(GlyphJudgement.MARVELOUS, event!!.judgement)
        assertEquals(0.01f, event.offsetSeconds!!, 1e-5f)
        assertEquals(1, engine.scoreSnapshot.combo)
    }

    @Test
    fun aNoteLeftAloneBecomesAMissExactlyOnceItsWindowPasses() {
        val engine = GlyphGameplayEngine(chart(note(1f)))

        engine.advanceTo(1.17f)
        assertEquals("still inside the Boo window", 0, engine.scoreSnapshot.judged)

        engine.advanceTo(1.19f)
        assertEquals(1, engine.scoreSnapshot.judged)
        assertEquals(1, engine.scoreSnapshot.counts[GlyphJudgement.MISS])

        // Advancing further must not judge it a second time.
        engine.advanceTo(3f)
        assertEquals(1, engine.scoreSnapshot.judged)
    }

    @Test
    fun pressingEmptySpaceCostsNothing() {
        val engine = GlyphGameplayEngine(chart(note(5f)))
        engine.advanceTo(1f)

        assertNull(engine.press(GlyphLane.RIGHT, 1f))
        assertEquals(0, engine.scoreSnapshot.judged)
        assertEquals(0, engine.scoreSnapshot.combo)
    }

    @Test
    fun theNearestNoteTakesTheTapNotTheEarliest() {
        // Two notes 80 ms apart, both inside the window of a tap at 1.10.
        val engine = GlyphGameplayEngine(chart(note(1.00f), note(1.12f)))
        engine.advanceTo(1.05f)

        val event = engine.press(GlyphLane.LEFT, 1.10f)
        assertNotNull(event)
        // Taking the earliest note would score this as a Good on the 1.00 note;
        // it is plainly a Marvelous on the 1.12 one.
        assertEquals(GlyphJudgement.MARVELOUS, event!!.judgement)
    }

    @Test
    fun holdingToTheEndScoresTheTailAsTheHeadWasScored() {
        val engine = GlyphGameplayEngine(
            chart(note(1f, type = GlyphNoteType.HOLD, endSeconds = 2f)),
        )
        engine.advanceTo(0.9f)
        val head = engine.press(GlyphLane.LEFT, 1.04f)
        assertEquals(GlyphJudgement.PERFECT, head!!.judgement)

        engine.advanceTo(1.5f)
        assertEquals("the tail is not settled mid-hold", 1, engine.scoreSnapshot.judged)

        engine.advanceTo(2.01f)
        assertEquals(2, engine.scoreSnapshot.judged)
        // Holding perfectly after a Perfect head is worth a Perfect, not an
        // upgrade to Marvelous.
        assertEquals(2, engine.scoreSnapshot.counts[GlyphJudgement.PERFECT])
        assertEquals(0, engine.scoreSnapshot.holdsDropped)
    }

    @Test
    fun releasingAHoldEarlyDropsItAfterTheGracePeriod() {
        val engine = GlyphGameplayEngine(
            chart(note(1f, type = GlyphNoteType.HOLD, endSeconds = 3f)),
        )
        engine.advanceTo(0.9f)
        engine.press(GlyphLane.LEFT, 1f)
        engine.release(GlyphLane.LEFT, 1.2f)

        engine.advanceTo(1.25f)
        assertEquals("the grace period has not expired", 1, engine.scoreSnapshot.judged)

        engine.advanceTo(1.45f)
        assertEquals(2, engine.scoreSnapshot.judged)
        assertEquals(1, engine.scoreSnapshot.holdsDropped)
        assertEquals(1, engine.scoreSnapshot.counts[GlyphJudgement.MISS])
    }

    @Test
    fun aMissedHoldHeadTakesItsTailWithIt() {
        val engine = GlyphGameplayEngine(
            chart(note(1f, type = GlyphNoteType.HOLD, endSeconds = 2f)),
        )
        engine.advanceTo(1.5f)

        // Both halves are gone; the player cannot rescue a hold they never took.
        assertEquals(2, engine.scoreSnapshot.judged)
        assertEquals(2, engine.scoreSnapshot.counts[GlyphJudgement.MISS])
    }

    @Test
    fun hittingAMineBreaksComboWithoutCountingAsAJudgedNote() {
        val engine = GlyphGameplayEngine(chart(note(1f), note(2f, type = GlyphNoteType.MINE)))
        engine.advanceTo(0.9f)
        engine.press(GlyphLane.LEFT, 1f)
        engine.release(GlyphLane.LEFT, 1.05f)
        assertEquals(1, engine.scoreSnapshot.combo)

        engine.advanceTo(1.95f)
        engine.press(GlyphLane.LEFT, 2f)

        assertEquals("a mine breaks the combo", 0, engine.scoreSnapshot.combo)
        assertEquals(1, engine.scoreSnapshot.minesHit)
        // Mines are not scorable, so the chart's judgement total is unaffected.
        assertEquals(1, engine.scoreSnapshot.judged)
    }

    @Test
    fun minesAndFakesAreExcludedFromTheChartTotal() {
        val built = chart(
            note(1f),
            note(2f, type = GlyphNoteType.MINE),
            note(3f, type = GlyphNoteType.FAKE),
            note(4f, type = GlyphNoteType.HOLD, endSeconds = 5f),
        )
        // One tap (1) plus one hold (head + tail = 2). The mine and the fake
        // are drawn but never scored.
        assertEquals(3, built.judgementCount)
    }

    @Test
    fun aLiftIsScoredOnReleaseNotOnPress() {
        val engine = GlyphGameplayEngine(chart(note(1f, type = GlyphNoteType.LIFT)))
        engine.advanceTo(0.8f)

        assertNull("pressing through a lift does nothing", engine.press(GlyphLane.LEFT, 0.85f))
        assertEquals(0, engine.scoreSnapshot.judged)

        val event = engine.release(GlyphLane.LEFT, 1.0f)
        assertNotNull(event)
        assertEquals(GlyphJudgement.MARVELOUS, event!!.judgement)
    }

    @Test
    fun theCalibrationOffsetShiftsEveryPress() {
        // A device reporting touches 40 ms late is corrected by an offset, not
        // by editing the chart.
        val engine = GlyphGameplayEngine(
            chart(note(1f)),
            config = GlyphGameplayEngine.Config(audioOffsetSeconds = -0.04f),
        )
        engine.advanceTo(0.9f)

        val event = engine.press(GlyphLane.LEFT, 1.04f)
        assertEquals(GlyphJudgement.MARVELOUS, event!!.judgement)
        assertEquals(0f, event.offsetSeconds!!, 1e-5f)
    }

    @Test
    fun resettingMakesEveryNoteHittableAgain() {
        // What a loop wrap needs. Without it the second pass through a practice
        // segment has nothing to hit: the notes are still marked resolved from
        // the first pass, so the player taps into silence.
        val engine = GlyphGameplayEngine(chart(note(1f), note(2f)))

        engine.advanceTo(0.9f)
        engine.press(GlyphLane.LEFT, 1f)
        engine.advanceTo(2.5f)
        assertEquals(2, engine.scoreSnapshot.judged)
        assertTrue(engine.isFinished)

        engine.reset()

        assertEquals("scoring restarts with the pass", 0, engine.scoreSnapshot.judged)
        assertEquals(0, engine.scoreSnapshot.combo)
        assertEquals(0, engine.scoreSnapshot.maxCombo)
        assertTrue("the chart is playable again", !engine.isFinished)

        engine.advanceTo(0.9f)
        val event = engine.press(GlyphLane.LEFT, 1f)
        assertNotNull("the first note must be hittable on the second pass", event)
        assertEquals(GlyphJudgement.MARVELOUS, event!!.judgement)
    }

    @Test
    fun resettingClearsHeldLanesAndOpenHolds() {
        val engine = GlyphGameplayEngine(
            chart(note(1f, type = GlyphNoteType.HOLD, endSeconds = 5f)),
        )
        engine.advanceTo(0.9f)
        engine.press(GlyphLane.LEFT, 1f)
        assertTrue(engine.isHeld(GlyphLane.LEFT))

        engine.reset()

        // A lane left "down" across a wrap would light its receptor for the
        // whole of the next pass and hold a note nobody is touching.
        assertTrue(!engine.isHeld(GlyphLane.LEFT))
        engine.advanceTo(0.9f)
        assertNotNull(engine.press(GlyphLane.LEFT, 1f))
    }

    // ── the clock ───────────────────────────────────────────────────────

    @Test
    fun theClockInterpolatesButNeverAccumulates() {
        val clock = GlyphClock()
        clock.sync(songSeconds = 10f, realtimeNanos = 0L)
        clock.start(0L)

        // Half a second of frames, polled at 165 Hz, with no new audio reading.
        var lastPosition = 0f
        for (frame in 1..99) {
            lastPosition = clock.positionAt(frame * 6_060_606L)
        }
        assertEquals(10.6f, lastPosition, 0.01f)

        // Asking repeatedly at one instant must give one answer — the property
        // that makes accumulated drift impossible.
        val at = 500_000_000L
        val first = clock.positionAt(at)
        repeat(1000) { assertEquals(first, clock.positionAt(at), 0f) }
        assertEquals(10.5f, first, 1e-4f)
    }

    @Test
    fun speedChangesRebaseRatherThanReinterpretTheElapsedTime() {
        val clock = GlyphClock()
        clock.sync(0f, 0L)
        clock.start(0L)

        val oneSecond = 1_000_000_000L
        assertEquals(1f, clock.positionAt(oneSecond), 1e-4f)

        clock.setSpeed(0.5f, oneSecond)
        // Without rebasing, the first second would be re-read at half speed and
        // the position would jump backwards to 0.5.
        assertEquals("no jump at the change", 1f, clock.positionAt(oneSecond), 1e-4f)
        assertEquals("half a second of song per second after", 1.5f, clock.positionAt(2 * oneSecond), 1e-4f)
    }

    @Test
    fun pauseAndResumeDoNotLoseOrGainSongTime() {
        val clock = GlyphClock()
        clock.sync(5f, 0L)
        clock.start(0L)

        val second = 1_000_000_000L
        clock.pause(2 * second)
        val paused = clock.positionAt(2 * second)
        assertEquals(7f, paused, 1e-4f)

        // Ten seconds of wall time pass while paused; none of it is song time.
        assertEquals(7f, clock.positionAt(12 * second), 0f)

        clock.resume(12 * second)
        assertEquals(7f, clock.positionAt(12 * second), 1e-4f)
        assertEquals(8f, clock.positionAt(13 * second), 1e-4f)
    }

    @Test
    fun smallAudioJitterIsSmoothedAndARealSeekSnaps() {
        val clock = GlyphClock()
        clock.sync(0f, 0L)
        clock.start(0L)
        val second = 1_000_000_000L

        // A 4 ms disagreement is ordinary player jitter; snapping to it every
        // poll is what makes the scroll look nervous.
        clock.syncSmooth(1.004f, second)
        assertEquals(1.0f, clock.positionAt(second), 0.002f)

        // A 300 ms disagreement is a seek and must be taken at face value.
        clock.syncSmooth(30f, 2 * second)
        assertEquals(30f, clock.positionAt(2 * second), 1e-3f)
    }
}
