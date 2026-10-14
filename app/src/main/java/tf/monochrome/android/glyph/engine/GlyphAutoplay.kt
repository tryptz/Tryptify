// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphNoteType

/**
 * Plays a chart perfectly: StepMania's autoplay, for the previews.
 *
 * Every step is pressed on its own time, holds are held to their ends, rolls
 * are tapped through and mines are left alone, through the engine's own
 * [GlyphGameplayEngine.press] and [GlyphGameplayEngine.release], so a preview
 * shows exactly what a perfect run looks like with the player's options: the
 * arrows meeting the receptors, the explosions, the rays and the combo.
 *
 * Time only moves forward, except when the preview loops: a position before
 * the last one is a wrap, and the engine is reset from there (see [rewind]).
 */
class GlyphAutoplay {
    private var next = 0
    private var last = Float.NEGATIVE_INFINITY
    private val holdEnds = FloatArray(LANES) { NONE }
    private val rollTaps = FloatArray(LANES) { NONE }

    /**
     * Play up to [position] on [engine] and resolve it; the judgements this
     * produced, drained from the engine.
     */
    fun advance(engine: GlyphGameplayEngine, position: Float): List<GlyphJudgementEvent> {
        if (position < last) rewind(engine, position)
        last = position
        val notes = engine.chart.notes
        while (next < notes.size && notes[next].timeSeconds <= position) {
            val note = notes[next++]
            releaseHolds(engine, note.timeSeconds)
            // The engine walks with the presses, so a frame that covers
            // several notes plays them as if each had its own frame.
            engine.advanceTo(note.timeSeconds)
            val lane = note.lane
            when (note.type) {
                GlyphNoteType.MINE, GlyphNoteType.FAKE -> Unit
                GlyphNoteType.TAP, GlyphNoteType.LIFT -> {
                    engine.press(lane, note.timeSeconds)
                    engine.release(lane, note.timeSeconds)
                }
                GlyphNoteType.HOLD -> {
                    engine.press(lane, note.timeSeconds)
                    holdEnds[lane.ordinal] = note.endTimeSeconds
                }
                GlyphNoteType.ROLL -> {
                    engine.press(lane, note.timeSeconds)
                    engine.release(lane, note.timeSeconds)
                    holdEnds[lane.ordinal] = note.endTimeSeconds
                    rollTaps[lane.ordinal] = note.timeSeconds + ROLL_TAP_SECONDS
                }
            }
        }
        releaseHolds(engine, position)
        engine.advanceTo(position)
        return engine.drainEvents()
    }

    /** Tap rolls through to [until], and let go of every hold that has ended by then. */
    private fun releaseHolds(engine: GlyphGameplayEngine, until: Float) {
        for (index in 0 until LANES) {
            val end = holdEnds[index]
            if (end == NONE) continue
            val lane = GlyphLane.entries[index]
            var tap = rollTaps[index]
            while (tap != NONE && tap < end && tap <= until) {
                engine.press(lane, tap)
                engine.release(lane, tap)
                tap += ROLL_TAP_SECONDS
            }
            rollTaps[index] = if (tap != NONE && tap < end) tap else NONE
            if (end <= until) {
                // Held to the end before it is let go, as a player holds it.
                engine.advanceTo(end)
                engine.release(lane, end)
                holdEnds[index] = NONE
                rollTaps[index] = NONE
            }
        }
    }

    /**
     * Start again at [position]: the engine forgets every judgement and sets
     * aside what is behind [position], so the notes just passed are not drawn
     * a second time as misses.
     */
    fun rewind(engine: GlyphGameplayEngine, position: Float) {
        for (index in 0 until LANES) {
            if (holdEnds[index] != NONE) engine.release(GlyphLane.entries[index], last)
            holdEnds[index] = NONE
            rollTaps[index] = NONE
        }
        engine.reset(fromSeconds = position)
        val notes = engine.chart.notes
        next = notes.indexOfFirst { it.timeSeconds >= position }.let { if (it < 0) notes.size else it }
        last = position
    }

    private companion object {
        const val LANES = 4
        const val NONE = -1f

        /** Inside StepMania's half second between roll taps, comfortably. */
        const val ROLL_TAP_SECONDS = 0.2f
    }
}
