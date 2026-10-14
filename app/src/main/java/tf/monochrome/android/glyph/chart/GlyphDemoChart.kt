// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.chart

import kotlin.math.roundToInt
import tf.monochrome.android.audio.stepmania.StepManiaDifficulty
import tf.monochrome.android.glyph.asset.GlyphBeatDivision
import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * The chart that plays itself behind the start menu: eight bars of quarters,
 * eighths, a sixteenth burst, holds and jumps, written three times over.
 *
 * Periodic on purpose. The menu plays the middle copy round and round
 * ([loopStartSeconds] for [loopSeconds]), and because the copies either side
 * are the same notes, the field at the end of a lap is the field at its start:
 * no arrow pops in or out when the lap wraps.
 */
object GlyphDemoChart {
    const val BPM = 140f
    private const val PERIOD_BEATS = 32
    private const val COPIES = 3

    private val SECONDS_PER_BEAT = 60f / BPM

    val loopStartSeconds: Float = PERIOD_BEATS * SECONDS_PER_BEAT
    val loopSeconds: Float = PERIOD_BEATS * SECONDS_PER_BEAT

    val timing: GlyphTiming = GlyphTiming.constant(BPM)

    /** Beat, lane (left, down, up, right), and how long it is held (0 for a tap). */
    private val BAR_PATTERN: List<Triple<Float, Int, Float>> = buildList {
        // Bar 1: quarters.
        add(Triple(0f, 0, 0f)); add(Triple(1f, 3, 0f)); add(Triple(2f, 1, 0f)); add(Triple(3f, 2, 0f))
        // Bar 2: eighths.
        listOf(0, 1, 3, 2, 1, 0, 2, 3).forEachIndexed { i, lane -> add(Triple(4f + i * 0.5f, lane, 0f)) }
        // Bar 3: a hold under quarters.
        add(Triple(8f, 0, 1.5f)); add(Triple(9f, 3, 0f)); add(Triple(10f, 2, 0f)); add(Triple(11f, 1, 0f))
        // Bar 4: eighths into a jump.
        listOf(3, 2, 0, 1, 3, 2).forEachIndexed { i, lane -> add(Triple(12f + i * 0.5f, lane, 0f)) }
        add(Triple(15f, 0, 0f)); add(Triple(15f, 3, 0f))
        // Bar 5: a sixteenth burst.
        listOf(0, 1, 2, 3, 2, 1, 0, 1).forEachIndexed { i, lane -> add(Triple(16f + i * 0.25f, lane, 0f)) }
        add(Triple(18f, 3, 0f)); add(Triple(19f, 0, 0f))
        // Bar 6: eighths.
        listOf(1, 2, 1, 3, 0, 2, 1, 3).forEachIndexed { i, lane -> add(Triple(20f + i * 0.5f, lane, 0f)) }
        // Bar 7: a long hold, the other hand busy.
        add(Triple(24f, 0, 2f))
        listOf(2, 3, 2, 3).forEachIndexed { i, lane -> add(Triple(24.5f + i * 0.5f, lane, 0f)) }
        add(Triple(27f, 1, 0f))
        // Bar 8: eighths and a closing jump.
        listOf(3, 1, 2, 0, 3, 2).forEachIndexed { i, lane -> add(Triple(28f + i * 0.5f, lane, 0f)) }
        add(Triple(31f, 1, 0f)); add(Triple(31f, 2, 0f))
    }

    val chart: GlyphChart by lazy {
        val notes = ArrayList<GlyphNote>()
        for (copy in 0 until COPIES) {
            for ((offset, lane, length) in BAR_PATTERN) {
                val beat = copy * PERIOD_BEATS + offset
                val seconds = timing.beatToSeconds(beat)
                val row = (((beat % 4f) / 4f) * ROWS).roundToInt() % ROWS
                notes += GlyphNote(
                    lane = GlyphLane.entries[lane],
                    type = if (length > 0f) GlyphNoteType.HOLD else GlyphNoteType.TAP,
                    beat = beat,
                    timeSeconds = seconds,
                    endTimeSeconds = if (length > 0f) timing.beatToSeconds(beat + length) else seconds,
                    division = GlyphBeatDivision.forRow(row, ROWS),
                    measure = (beat / 4f).toInt(),
                )
            }
        }
        GlyphChart(
            difficulty = StepManiaDifficulty.MEDIUM,
            meter = StepManiaDifficulty.MEDIUM.meter,
            chartName = "Demo",
            stepsType = "dance-single",
            notes = notes.sortedWith(compareBy({ it.timeSeconds }, { it.lane.ordinal })),
        )
    }

    /** Rows a measure at the finest the pattern needs, sixteenths. */
    private const val ROWS = 16
}
