// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphTiming

/**
 * The rules of play, with no clock and no renderer.
 *
 * Time arrives from outside as a song position in seconds, which is what makes
 * this testable and what keeps the audio authoritative: the engine cannot
 * advance on its own, so no animation frame can ever become the thing that
 * decides when a note was due. [advanceTo] and [press] are the only ways state
 * moves, and both take the position explicitly.
 *
 * The rules themselves run in C++ (`cpp/glyph`) when the native library is
 * there, and on [KotlinGlyphEngineCore] — the same rules, kept to the same
 * arithmetic — when it is not, which includes every JVM unit test. Callers
 * cannot tell which, apart from [isNative].
 *
 * Single-threaded: everything calls in from the main thread. Call [close]
 * when replacing an engine; a closed engine judges and draws nothing.
 */
class GlyphGameplayEngine(
    val chart: GlyphChart,
    windows: GlyphTimingWindows = GlyphTimingWindows.STANDARD,
    config: Config = Config(),
    /** The song's beat map, for XMod and MMod spacing. CMod ignores it. */
    val timing: GlyphTiming = GlyphTiming.constant(GlyphTiming.DEFAULT_BPM),
    /** False forces the Kotlin twin, whatever is loaded. */
    preferNative: Boolean = true,
) : AutoCloseable {

    /**
     * @param holdGraceSeconds how long a hold may be released before it counts
     *   as dropped. Without a grace period a hold is unplayable on a
     *   touchscreen, where a finger that stays put still reports the odd
     *   release.
     * @param rollIntervalSeconds the longest gap between taps a roll tolerates.
     * @param audioOffsetSeconds the player's own calibration. Added to every
     *   incoming press time, so a device that reports touches late can be
     *   corrected without touching the chart.
     */
    data class Config(
        val holdGraceSeconds: Float = 0.10f,
        val rollIntervalSeconds: Float = 0.28f,
        val audioOffsetSeconds: Float = 0f,
    )

    private val core: GlyphEngineCore =
        (if (preferNative) NativeGlyphEngineCore.create(chart, timing, windows, config) else null)
            ?: KotlinGlyphEngineCore(chart, timing, windows, config)

    /** Whether the C++ engine is the one playing. */
    val isNative: Boolean get() = core is NativeGlyphEngineCore

    val positionSeconds: Float get() = core.positionSeconds

    /**
     * The full readout. Builds a snapshot, so it is for publishing a few times
     * a second; a per-tick check wants [combo].
     */
    val scoreSnapshot: GlyphScoreSnapshot get() = core.scoreSnapshot()

    /** The live combo, without building a snapshot. */
    val combo: Int get() = core.combo

    val isFinished: Boolean get() = core.isFinished

    /**
     * Judgements produced since the last call, then cleared.
     *
     * Drained rather than exposed as a growing list so the UI cannot
     * accidentally hold every judgement of a five-minute run alive in a
     * recomposition.
     */
    fun drainEvents(): List<GlyphJudgementEvent> = core.drainEvents()

    /**
     * Move the song position forward and resolve anything time alone decides:
     * missed notes, expired mines, completed holds and broken rolls.
     *
     * Idempotent for a position already passed, so a caller that polls faster
     * than the audio clock updates does no harm.
     */
    fun advanceTo(songSeconds: Float) = core.advanceTo(songSeconds)

    /**
     * A lane went down at [songSeconds].
     *
     * Returns the judgement, or null if there was nothing in reach — a press
     * into empty space costs nothing, which is what lets a player tap out a
     * rhythm they are unsure of without being punished for it.
     */
    fun press(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent? = core.press(lane, songSeconds)

    /** A lane came up at [songSeconds]. Ends holds and judges lifts. */
    fun release(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent? = core.release(lane, songSeconds)

    /** Whether [lane] is being held, for receptor state. */
    fun isHeld(lane: GlyphLane): Boolean = core.heldMask and (1 shl lane.ordinal) != 0

    /** Bit n set while lane n is down; one call for all four receptors. */
    val heldMask: Int get() = core.heldMask

    /**
     * Clear every judgement and play from [fromSeconds].
     *
     * What a loop wrap needs. Without it the second pass through a practice
     * segment has nothing to hit: the notes are still marked resolved from the
     * first pass, so they are skipped and the player taps into silence — which
     * is the whole feature failing on its second iteration.
     *
     * Notes before [fromSeconds] or at or after [untilSeconds] are set aside:
     * not scored, not drawn and not counted in the total. A practice loop is
     * judged on what is inside it — without this, the first advance of every
     * pass timed out each note before the loop as a miss.
     *
     * Scoring resets with it. Accuracy accumulated across passes is not a
     * number anyone can act on; per-pass is what tells a player whether this
     * repetition went better than the last.
     */
    fun reset(
        fromSeconds: Float = Float.NEGATIVE_INFINITY,
        untilSeconds: Float = Float.POSITIVE_INFINITY,
    ) = core.reset(fromSeconds, untilSeconds)

    /**
     * The notes overlapping [fromSeconds]..[toSeconds], placed for a playfield
     * at [positionSeconds] under [scrollMode], written into [frame].
     *
     * One call per drawn frame, and no allocation once [frame] has grown to
     * the densest screen of the chart. Notes are reported by index into
     * [chart]'s notes, with the state the renderer needs travelling with them,
     * so it never has to ask the engine anything while it draws.
     */
    fun fillVisible(
        fromSeconds: Float,
        toSeconds: Float,
        positionSeconds: Float,
        scrollMode: GlyphScrollMode,
        frame: GlyphVisibleFrame,
    ) = core.fillVisible(fromSeconds, toSeconds, positionSeconds, scrollMode, frame)

    override fun close() = core.close()
}

/** One judgement, as it happened. */
data class GlyphJudgementEvent(
    val lane: GlyphLane,
    val judgement: GlyphJudgement,
    /** Null when nothing was pressed — a timed-out note or a completed tail. */
    val offsetSeconds: Float?,
    val songSeconds: Float,
    val isTail: Boolean = false,
    val isMine: Boolean = false,
    val combo: Int = 0,
)
