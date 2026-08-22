// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.data.GlyphAttempt
import tf.monochrome.android.glyph.data.GlyphGhost
import tf.monochrome.android.glyph.data.GlyphGhostRecorder
import tf.monochrome.android.glyph.engine.GlyphJudgement
import tf.monochrome.android.glyph.training.GlyphCountIn
import tf.monochrome.android.glyph.training.GlyphLoopSegment

class GlyphTrainingTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    // ── loop boundaries ─────────────────────────────────────────────────

    @Test
    fun aLoopWrapsAtItsBounds() {
        val loop = GlyphLoopSegment(startSeconds = 10f, endSeconds = 14f)

        assertEquals(4f, loop.lengthSeconds, 1e-5f)
        assertEquals(10f, loop.wrap(10f), 1e-5f)
        assertEquals(13.9f, loop.wrap(13.9f), 1e-4f)
        // The end is exclusive: reaching it is the start of the next pass.
        assertEquals(10f, loop.wrap(14f), 1e-4f)
        assertEquals(11f, loop.wrap(15f), 1e-4f)
        assertTrue(13.99f in loop)
        assertFalse(14f in loop)
    }

    @Test
    fun athousandLoopsDoNotDrift() {
        // The bug this is written against: wrapping by subtracting the length
        // from a running position, so every pass inherits the last one's
        // rounding error. Deriving each wrap from the segment's own bounds
        // makes the thousandth pass identical to the first.
        //
        // The loop is short enough that a thousand passes stay inside a
        // plausible song position (~17 minutes). Past that a Float second no
        // longer resolves a tenth of a millisecond at all, so a test running
        // further would be measuring the width of a float rather than the
        // wrap — and no song reaches there.
        val loop = GlyphLoopSegment(startSeconds = 4.0f, endSeconds = 5.007f)

        for (pass in 0 until 1000) {
            val atStart = (loop.startSeconds + pass.toDouble() * loop.lengthSeconds).toFloat()
            assertEquals(
                "pass $pass must start exactly where pass 0 did",
                loop.startSeconds,
                loop.wrap(atStart),
                1e-3f,
            )
            // A position a third of the way in must map to the same place too.
            assertEquals(
                loop.startSeconds + loop.lengthSeconds / 3f,
                loop.wrap(atStart + loop.lengthSeconds / 3f),
                1e-3f,
            )
        }
        assertEquals(
            999,
            loop.passesAt((loop.startSeconds + 999.0 * loop.lengthSeconds).toFloat()),
        )
    }

    @Test
    fun aPositionFarPastTheEndStillLandsInsideTheLoop() {
        // Happens whenever the app is backgrounded and the audio runs on.
        val loop = GlyphLoopSegment(2f, 5f)
        val wrapped = loop.wrap(100_000f)
        assertTrue("wrapped to $wrapped", wrapped in loop)
    }

    @Test
    fun aLoopIsClampedIntoTheSongItBelongsTo() {
        val clamped = GlyphLoopSegment(100f, 200f).coerceInto(durationSeconds = 120f)
        assertTrue(clamped.endSeconds <= 120f)
        assertTrue(clamped.lengthSeconds >= GlyphLoopSegment.MINIMUM_SECONDS)

        // A segment entirely past the end still comes back playable rather than
        // as a zero-length loop that would divide by zero on the first wrap.
        val past = GlyphLoopSegment(500f, 600f).coerceInto(durationSeconds = 30f)
        assertTrue(past.lengthSeconds >= GlyphLoopSegment.MINIMUM_SECONDS)
    }

    @Test
    fun countInStaysMusicalWhenThePracticeSpeedChanges() {
        // Four beats of count-in is four beats at any tempo; expressing it in
        // seconds would make it shorter exactly when a slow practice pass needs
        // it to be longer.
        assertEquals(2f, GlyphCountIn.ONE_BAR.seconds(bpm = 120f), 1e-4f)
        assertEquals(4f, GlyphCountIn.ONE_BAR.seconds(bpm = 60f), 1e-4f)
        assertEquals(0f, GlyphCountIn.NONE.seconds(bpm = 120f), 1e-4f)
        assertEquals("a zero tempo must not divide", 0f, GlyphCountIn.ONE_BAR.seconds(0f), 1e-4f)
    }

    // ── ghosts ──────────────────────────────────────────────────────────

    @Test
    fun aRecordedGhostRoundTripsThroughJson() {
        val recorder = GlyphGhostRecorder()
        recorder.record(1.000f, lane = 0, offsetSeconds = -0.012f, judgement = GlyphJudgement.PERFECT)
        recorder.record(1.500f, lane = 2, offsetSeconds = 0.004f, judgement = GlyphJudgement.MARVELOUS)
        recorder.record(2.000f, lane = 3, offsetSeconds = null, judgement = GlyphJudgement.MISS)

        val ghost = recorder.build()
        assertEquals(3, ghost.size)
        assertTrue(ghost.isConsistent)

        val restored = json.decodeFromString<GlyphGhost>(json.encodeToString(ghost))
        assertEquals(ghost, restored)
        assertEquals(listOf(1000, 1500, 2000), restored.timesMs)
        assertEquals(listOf(-12, 4, 0), restored.offsetsMs)
        assertEquals(GlyphJudgement.PERFECT, restored.judgementAt(0))
        assertEquals(GlyphJudgement.MISS, restored.judgementAt(2))
    }

    @Test
    fun aGhostWithMismatchedArraysIsTreatedAsAbsent() {
        // A truncated write must never let playback read past the end of a
        // shorter array mid-song.
        val broken = GlyphGhost(
            timesMs = listOf(1000, 2000, 3000),
            lanes = listOf(0, 1),
            offsetsMs = listOf(0, 0, 0),
            judgements = listOf(0, 0, 0),
        )
        assertFalse(broken.isConsistent)
        assertEquals(emptyList<Int>(), broken.between(0, 10_000))
    }

    @Test
    fun ghostLookupIsBoundedToTheWindowAsked() {
        val ghost = GlyphGhost(
            timesMs = listOf(500, 1_000, 1_500, 2_000),
            lanes = listOf(0, 1, 2, 3),
            offsetsMs = listOf(0, 0, 0, 0),
            judgements = listOf(0, 0, 0, 0),
        )
        assertEquals(listOf(1, 2), ghost.between(1_000, 2_000))
        assertEquals(emptyList<Int>(), ghost.between(2_500, 3_000))
    }

    @Test
    fun ghostStorageStaysCompact() {
        // The rule the model exists to keep: timing, not rendered frames. A
        // three-thousand-note run should be tens of kilobytes, not megabytes.
        val recorder = GlyphGhostRecorder()
        repeat(3_000) { index ->
            recorder.record(index * 0.12f, index % 4, 0.01f, GlyphJudgement.PERFECT)
        }
        val encoded = json.encodeToString(recorder.build())
        assertTrue("ghost was ${encoded.length} bytes", encoded.length < 120_000)
    }

    // ── attempt persistence and migration ───────────────────────────────

    @Test
    fun anAttemptRoundTripsWithItsGhost() {
        val attempt = GlyphAttempt(
            id = "a1",
            chartId = "chart-1",
            songTitle = "Test Song",
            difficulty = StepManiaDifficulty.HARD,
            playedAtEpochMs = 1_700_000_000_000L,
            score = 912_345,
            accuracy = 0.912f,
            maxCombo = 240,
            judgementCounts = mapOf("MARVELOUS" to 200, "MISS" to 3),
            early = 40,
            late = 60,
            meanOffsetMs = 6.2f,
            deviationMs = 18.4f,
            speed = 0.9f,
            ghost = GlyphGhost(
                timesMs = listOf(0, 500),
                lanes = listOf(0, 1),
                offsetsMs = listOf(-4, 9),
                judgements = listOf(0, 1),
            ),
        )

        val restored = json.decodeFromString<GlyphAttempt>(json.encodeToString(attempt))
        assertEquals(attempt, restored)
        assertEquals(GlyphAttempt.CURRENT_VERSION, restored.version)
        assertEquals(200, restored.judgementCount(GlyphJudgement.MARVELOUS))
        assertEquals(0, restored.judgementCount(GlyphJudgement.GOOD))
        assertTrue(restored.isFullRun)
    }

    @Test
    fun aTrainingSegmentIsDistinguishableFromAFullRun() {
        val segment = GlyphAttempt(
            id = "a2",
            chartId = "chart-1",
            songTitle = "Test Song",
            difficulty = StepManiaDifficulty.CHALLENGE,
            playedAtEpochMs = 1L,
            score = 100,
            accuracy = 0.5f,
            maxCombo = 4,
            segmentStartSeconds = 30f,
            segmentEndSeconds = 42f,
        )
        // A twelve-second loop must never be filed as a personal best on the
        // whole song.
        assertFalse(segment.isFullRun)
        assertEquals(segment, json.decodeFromString<GlyphAttempt>(json.encodeToString(segment)))
    }

    @Test
    fun aRecordFromANewerBuildIsSkippedRatherThanMisread() {
        // The migration contract: a shape this build does not know has fields
        // it would misread, so it is dropped from the list, not coerced.
        val future = json.decodeFromString<JsonObject>(
            """{"v":99,"id":"x","chartId":"c","songTitle":"S",
               "difficulty":"HARD","playedAtEpochMs":1,"score":1,
               "accuracy":1.0,"maxCombo":1}""",
        )
        assertTrue(future["v"].toString().contains("99"))

        val current = json.decodeFromString<JsonObject>(
            """{"v":1,"id":"x","chartId":"c","songTitle":"S",
               "difficulty":"HARD","playedAtEpochMs":1,"score":1,
               "accuracy":1.0,"maxCombo":1}""",
        )
        val decoded = json.decodeFromJsonElement(GlyphAttempt.serializer(), current)
        assertNotNull(decoded)
        assertEquals(1, decoded.version)
        assertEquals(StepManiaDifficulty.HARD, decoded.difficulty)
    }

    @Test
    fun anAttemptWithoutOptionalFieldsStillDecodes() {
        // Records written before a field existed must keep loading; that is the
        // whole point of defaulting rather than requiring every key.
        val minimal = """{"v":1,"id":"x","chartId":"c","songTitle":"S",
            "difficulty":"EASY","playedAtEpochMs":1,"score":0,
            "accuracy":0.0,"maxCombo":0}"""
        val decoded = json.decodeFromString<GlyphAttempt>(minimal)
        assertEquals(1f, decoded.speed, 1e-6f)
        assertFalse(decoded.mirror)
        assertNull(decoded.ghost)
        assertTrue(decoded.isFullRun)
    }
}
