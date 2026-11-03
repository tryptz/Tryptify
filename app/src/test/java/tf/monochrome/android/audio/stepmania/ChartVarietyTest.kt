// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Generated charts that breathe with the song and do not walk one loop. */
class ChartVarietyTest {

    private val measure = 16

    /** A song of [measures] measures, each [loud] or not. */
    private fun song(measures: Int, loud: (Int) -> Boolean) = RhythmAnalysis(
        bpm = 140f,
        offsetSeconds = 0f,
        confidence = 1f,
        durationSeconds = measures * 16 * 60f / 140f / 4f,
        gridStrengths = FloatArray(measures * measure) { tick ->
            val full = loud(tick / measure)
            when {
                tick % 4 == 0 -> if (full) 1f else 0.35f
                tick % 2 == 0 -> if (full) 0.75f else 0.15f
                else -> if (full) 0.6f else 0.05f
            }
        },
    )

    private fun chart(analysis: RhythmAnalysis, difficulty: StepManiaDifficulty, title: String = "Variety") =
        StepManiaMapGenerator { _, _ -> analysis }
            .generate(analysis, StepManiaRequest(title, "Test", "v.flac", difficulties = setOf(difficulty)))
            .charts.single()

    @Test
    fun aQuietMeasureIsQuietAndALoudOneFull() {
        val levels = StepManiaMapGenerator.measureIntensity(song(32) { it >= 16 })
        assertTrue(levels.take(14).all { it < 0.1f })
        assertTrue(levels.drop(18).all { it > 0.9f })
    }

    @Test
    fun aSongAtOneLevelIsNeutralThroughout() {
        val levels = StepManiaMapGenerator.measureIntensity(song(16) { true })
        assertTrue(levels.all { it == StepManiaMapGenerator.NEUTRAL_INTENSITY })
    }

    /** The verse and the chorus: notes a second follow the song, not one rate throughout. */
    @Test
    fun theChorusHasMoreArrowsThanTheVerse() {
        // Verse, chorus, verse, chorus, eight measures each.
        val analysis = song(32) { (it / 8) % 2 == 1 }
        for (difficulty in StepManiaDifficulty.entries) {
            val events = chart(analysis, difficulty).events
            fun inSection(section: Int) = events.count { it.tick / measure / 8 == section }
            val verse = inSection(0) + inSection(2)
            val chorus = inSection(1) + inSection(3)
            assertTrue("$difficulty: chorus $chorus against verse $verse", chorus >= verse * 3 / 2)
        }
        // Hard bursts into sixteenths in the chorus, and never in the verse.
        val hard = chart(analysis, StepManiaDifficulty.HARD).events
        assertTrue(hard.any { it.tick % 2 == 1 && (it.tick / measure / 8) % 2 == 1 })
        assertTrue(hard.none { it.tick % 2 == 1 && (it.tick / measure / 8) % 2 == 0 })
    }

    @Test
    fun theArrowsDoNotWalkOneLoop() {
        val analysis = song(48) { true }
        for (difficulty in listOf(StepManiaDifficulty.MEDIUM, StepManiaDifficulty.HARD)) {
            val lanes = chart(analysis, difficulty).events
                .filter { it.laneCount == 1 }
                .map { Integer.numberOfTrailingZeros(it.laneMask) }
            // A fixed eight-step loop has eight four-arrow shapes at most.
            val shapes = lanes.windowed(4).toSet()
            assertTrue("$difficulty: ${shapes.size} shapes", shapes.size >= 24)
        }
    }

    @Test
    fun jumpsVaryAndComeOnStrongBeats() {
        val events = chart(song(32) { true }, StepManiaDifficulty.CHALLENGE).events
        val jumps = events.filter { it.laneCount == 2 }
        assertTrue(jumps.all { it.tick % 4 == 0 })
        assertTrue("jumps use several pairs", jumps.map { it.laneMask }.toSet().size >= 3)
    }

    @Test
    fun aSongAlwaysChartsTheSame() {
        val analysis = song(24) { it % 6 < 3 }
        for (difficulty in StepManiaDifficulty.entries) {
            assertEquals(chart(analysis, difficulty), chart(analysis, difficulty))
        }
        assertTrue(chart(analysis, StepManiaDifficulty.HARD) != chart(analysis, StepManiaDifficulty.HARD, title = "Another"))
    }

    @Test
    fun noJacksInTheNewPatterns() {
        val analysis = song(48) { it % 8 >= 4 }
        for (difficulty in StepManiaDifficulty.entries) {
            val events = chart(analysis, difficulty).events
            for (i in 1 until events.size) {
                if (events[i].laneCount == 1 && events[i - 1].laneCount == 1) {
                    assertTrue("$difficulty jack at ${events[i].tick}", events[i].laneMask != events[i - 1].laneMask)
                }
            }
        }
    }
}
