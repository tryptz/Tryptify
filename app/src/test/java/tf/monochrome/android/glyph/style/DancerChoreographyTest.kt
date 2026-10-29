// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.glyph.style.DancerChoreography.Phase

/**
 * A character's dancing as DDR's dancers do it: warming up until the chart's
 * first measure, dancing on the beat from it, moves changing on a measure and
 * blended, resting once failed, and the DDR PC moves' length in beats.
 */
class DancerChoreographyTest {

    private val moves = mapOf(
        Phase.WARMUP to listOf("w1", "w2"),
        Phase.DANCE to listOf("d1", "d2", "d3"),
        Phase.REST to listOf("r"),
    )
    private val beats = mapOf("w1" to 8f, "w2" to 8f, "d1" to 16f, "d2" to 16f, "d3" to 16f, "r" to 4f)

    private fun choreography(loaded: (String) -> Float? = { beats[it] }) =
        DancerChoreography(moves, loaded, Random(7))

    @Test
    fun ddrPcMovesAreWholeBeatsAtSixtyOneFramesAMeasure() {
        // The archive's moves: 793, 244, 61 and 122 frames (last frame one less).
        assertEquals(52f, DancerChoreography.beatsOf(792f))
        assertEquals(16f, DancerChoreography.beatsOf(243f))
        assertEquals(4f, DancerChoreography.beatsOf(60f))
        assertEquals(8f, DancerChoreography.beatsOf(121f))
        assertEquals(1f, DancerChoreography.beatsOf(0f))
    }

    @Test
    fun itWarmsUpUntilTheFirstMeasureWithNotesThenDancesOnTheBeat() {
        val c = choreography()
        // Notes from beat 9: dancing from the measure they are in, beat 8.
        val warm = c.update(beat = 2f, dtSeconds = 0.03f, firstNoteBeat = 9f, failed = false)
        assertTrue(warm.move in moves.getValue(Phase.WARMUP))
        // The warm-up began on the measure at beat 0: two beats in.
        assertEquals(2f * DancerChoreography.FRAMES_PER_BEAT, warm.frame, 1e-3f)

        val dance = c.update(beat = 8.5f, dtSeconds = 0.03f, firstNoteBeat = 9f, failed = false)
        assertTrue(dance.move in moves.getValue(Phase.DANCE))
        // From beat 8, on the beat: half a beat in.
        assertEquals(0.5f * DancerChoreography.FRAMES_PER_BEAT, dance.frame, 1e-3f)
    }

    @Test
    fun aMoveIsFollowedByAnotherOnTheMeasureBlendedIn() {
        val c = choreography()
        c.update(0f, 0.03f, firstNoteBeat = 0f, failed = false)
        val first = c.update(15.9f, 0.03f, 0f, false).move
        val next = c.update(16.1f, 0.03f, 0f, false)
        assertNotEquals(first, next.move)
        // The new move starts on beat 16, not where the frame happened to land.
        assertEquals(0.1f * DancerChoreography.FRAMES_PER_BEAT, next.frame, 1e-3f)
        // Blended in from the one left, and fully in within half a beat.
        assertEquals(first, next.previous)
        assertTrue(next.blend in 0f..0.99f)
        val settled = c.update(16.7f, 0.03f, 0f, false)
        assertEquals(1f, settled.blend)
        assertNull(settled.previous)
    }

    @Test
    fun aMoveNotYetReadIsNotWaitedOnTheCurrentOneRepeats() {
        var ready = setOf("w1", "w2", "d1", "r")
        val c = choreography { if (it in ready) beats[it] else null }
        val first = c.update(0f, 0.03f, 0f, false).move
        assertEquals("d1", first)
        // d2 and d3 not read yet: d1 again, from beat 16.
        val again = c.update(16.5f, 0.03f, 0f, false)
        assertEquals("d1", again.move)
        assertEquals(0.5f * DancerChoreography.FRAMES_PER_BEAT, again.frame, 1e-3f)
        assertTrue(c.wanted().any { it == "d2" || it == "d3" })
        ready = ready + "d2" + "d3"
        assertNotEquals("d1", c.update(32.5f, 0.03f, 0f, false).move)
    }

    @Test
    fun aFailedRunRestsAndARetryWarmsUpAgain() {
        val c = choreography()
        c.update(20f, 0.03f, 0f, false)
        assertEquals("r", c.update(21f, 0.03f, 0f, failed = true).move)
        // Back to the start (a retry): warming up again.
        assertTrue(c.update(-2f, 0.03f, firstNoteBeat = 4f, failed = false).move in moves.getValue(Phase.WARMUP))
    }

    @Test
    fun theCameraSpinsThroughAStopAndCutsEveryTwoMeasures() {
        val c = choreography()
        val before = c.update(3f, 0.03f, 0f, false).yawDegrees
        // The beat stands still for a second: a stop.
        var yaw = before
        repeat(30) { yaw = c.update(3f, 1f / 30, 0f, false).yawDegrees }
        assertEquals(before + 40f, yaw, 0.5f)
        // Within two measures a still angle stays still; past them it may cut.
        val cut = c.update(8.01f, 0.03f, 0f, false).yawDegrees
        assertTrue(cut in -200f..200f)
    }
}
