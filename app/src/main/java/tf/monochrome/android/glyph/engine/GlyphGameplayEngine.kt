// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import kotlin.math.abs
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.chart.GlyphNoteType

/**
 * The rules of play, with no clock and no renderer.
 *
 * Time arrives from outside as a song position in seconds, which is what makes
 * this testable and what keeps the audio authoritative: the engine cannot
 * advance on its own, so no animation frame can ever become the thing that
 * decides when a note was due. [advanceTo] and [press] are the only ways state
 * moves, and both take the position explicitly.
 *
 * Notes are visited in time order through a sliding index rather than scanned,
 * so a five-thousand-note Challenge chart costs the same per frame as a
 * Beginner one.
 */
class GlyphGameplayEngine(
    val chart: GlyphChart,
    private val windows: GlyphTimingWindows = GlyphTimingWindows.STANDARD,
    private val config: Config = Config(),
) {

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

    /** Everything the engine knows about one note during play. */
    private class NoteRuntime(val note: GlyphNote) {
        var judgement: GlyphJudgement? = null
        var tailResolved = false
        var holding = false
        var lastRollTapSeconds = 0f

        /**
         * When the lane came up, or [Float.NaN] while it is still down.
         *
         * The grace period runs from here rather than from the head: a hold
         * held correctly for ten seconds and released a hair early must get the
         * same tolerance as one released immediately, and measuring from the
         * head gives the long hold none at all.
         */
        var releasedAtSeconds = Float.NaN
        val isResolved: Boolean
            get() = judgement != null && (tailResolved || !note.type.hasTail)
    }

    private val runtimes: List<NoteRuntime> = chart.notes.map { NoteRuntime(it) }

    // Notes are sorted by time, so everything before this index is resolved or
    // permanently out of reach and never has to be looked at again.
    private var cursor = 0

    private val score = GlyphScore(chart.judgementCount)

    private val laneHeld = BooleanArray(GlyphLane.entries.size)

    /** Holds currently being held down, by lane. */
    private val activeHold = arrayOfNulls<NoteRuntime>(GlyphLane.entries.size)

    private val events = ArrayList<GlyphJudgementEvent>()

    var positionSeconds: Float = 0f
        private set

    val scoreSnapshot: GlyphScoreSnapshot get() = score.snapshot()

    val isFinished: Boolean get() = cursor >= runtimes.size

    /**
     * Judgements produced since the last call, then cleared.
     *
     * Drained rather than exposed as a growing list so the UI cannot
     * accidentally hold every judgement of a five-minute run alive in a
     * recomposition.
     */
    fun drainEvents(): List<GlyphJudgementEvent> {
        if (events.isEmpty()) return emptyList()
        val drained = ArrayList(events)
        events.clear()
        return drained
    }

    /**
     * Move the song position forward and resolve anything time alone decides:
     * missed notes, expired mines, completed holds and broken rolls.
     *
     * Idempotent for a position already passed, so a caller that polls faster
     * than the audio clock updates does no harm.
     */
    fun advanceTo(songSeconds: Float) {
        if (songSeconds < positionSeconds) {
            // A seek backwards is a loop or a restart; the caller resets.
            positionSeconds = songSeconds
            return
        }
        positionSeconds = songSeconds

        var index = cursor
        while (index < runtimes.size) {
            val runtime = runtimes[index]
            val note = runtime.note

            // Nothing past here can have expired yet, because the list is in
            // time order and this one is still in the future.
            if (note.timeSeconds - windows.missSeconds > songSeconds) break

            resolveByTime(runtime, songSeconds)
            index += 1
        }

        // Advance the cursor over the settled prefix only. A hold in the middle
        // of the window keeps everything behind it in view, which is correct:
        // its tail has not happened yet.
        while (cursor < runtimes.size && runtimes[cursor].isResolved) cursor += 1
    }

    private fun resolveByTime(runtime: NoteRuntime, songSeconds: Float) {
        val note = runtime.note

        if (runtime.judgement == null) {
            when {
                // Mines and fakes are never judged; a mine simply stops being
                // dangerous once its window has passed.
                !note.type.isScorable -> {
                    if (songSeconds > note.timeSeconds + windows.missSeconds) {
                        runtime.judgement = GlyphJudgement.MISS
                        runtime.tailResolved = true
                    }
                }
                songSeconds > note.timeSeconds + windows.missSeconds -> {
                    runtime.judgement = GlyphJudgement.MISS
                    score.record(GlyphJudgement.MISS, offsetSeconds = null)
                    emit(note, GlyphJudgement.MISS, offsetSeconds = null)
                    if (note.type.hasTail) {
                        // A head that was never hit takes its tail with it,
                        // rather than leaving a hold the player can rescue.
                        runtime.tailResolved = true
                        score.record(GlyphJudgement.MISS, offsetSeconds = null)
                    }
                }
            }
            return
        }

        if (!note.type.hasTail || runtime.tailResolved) return

        val lane = note.lane.ordinal
        val dropped = when (note.type) {
            GlyphNoteType.ROLL ->
                songSeconds - runtime.lastRollTapSeconds > config.rollIntervalSeconds
            else -> {
                val releasedAt = runtime.releasedAtSeconds
                !runtime.holding &&
                    !releasedAt.isNaN() &&
                    songSeconds - releasedAt > config.holdGraceSeconds
            }
        }

        if (dropped) {
            runtime.tailResolved = true
            runtime.holding = false
            if (activeHold[lane] === runtime) activeHold[lane] = null
            score.record(GlyphJudgement.MISS, offsetSeconds = null)
            score.recordHoldDropped()
            emit(note, GlyphJudgement.MISS, offsetSeconds = null, isTail = true)
            return
        }

        if (songSeconds >= note.endTimeSeconds) {
            runtime.tailResolved = true
            runtime.holding = false
            if (activeHold[lane] === runtime) activeHold[lane] = null
            // A held tail is worth the head's judgement rather than a flat
            // Marvelous: holding perfectly after a Good head should not read as
            // a better note than it was.
            val tail = runtime.judgement ?: GlyphJudgement.MARVELOUS
            score.record(tail, offsetSeconds = null)
            emit(note, tail, offsetSeconds = null, isTail = true)
        }
    }

    /**
     * A lane went down at [songSeconds].
     *
     * Returns the judgement, or null if there was nothing in reach — a press
     * into empty space costs nothing, which is what lets a player tap out a
     * rhythm they are unsure of without being punished for it.
     */
    fun press(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent? {
        val at = songSeconds + config.audioOffsetSeconds
        laneHeld[lane.ordinal] = true

        // A roll being held takes the tap before anything else does, otherwise
        // re-tapping the roll would start judging the note after it.
        activeHold[lane.ordinal]?.let { active ->
            if (active.note.type == GlyphNoteType.ROLL && !active.tailResolved) {
                active.lastRollTapSeconds = at
                return null
            }
        }

        val candidate = nearestUnjudged(lane, at) ?: return null
        val note = candidate.note
        val offset = at - note.timeSeconds

        if (note.type == GlyphNoteType.MINE) {
            candidate.judgement = GlyphJudgement.MISS
            candidate.tailResolved = true
            score.recordMineHit()
            return emit(note, GlyphJudgement.MISS, offsetSeconds = offset, isMine = true)
        }
        // A lift is scored on release, so pressing through one does nothing.
        if (note.type == GlyphNoteType.LIFT) return null
        if (note.type == GlyphNoteType.FAKE) return null

        val judgement = windows.judge(offset) ?: return null
        candidate.judgement = judgement

        if (note.type.hasTail) {
            candidate.holding = true
            candidate.releasedAtSeconds = Float.NaN
            candidate.lastRollTapSeconds = at
            activeHold[lane.ordinal] = candidate
        } else {
            candidate.tailResolved = true
        }

        score.record(judgement, offset)
        return emit(note, judgement, offset)
    }

    /** A lane came up at [songSeconds]. Ends holds and judges lifts. */
    fun release(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent? {
        val at = songSeconds + config.audioOffsetSeconds
        laneHeld[lane.ordinal] = false
        activeHold[lane.ordinal]?.let { held ->
            held.holding = false
            // Only the first release starts the clock; a stream of release
            // events from a jittery digitizer must not keep resetting it.
            if (held.releasedAtSeconds.isNaN()) held.releasedAtSeconds = at
        }

        val candidate = nearestUnjudged(lane, at, liftsOnly = true) ?: return null
        val offset = at - candidate.note.timeSeconds
        val judgement = windows.judge(offset) ?: return null

        candidate.judgement = judgement
        candidate.tailResolved = true
        score.record(judgement, offset)
        return emit(candidate.note, judgement, offset)
    }

    /** Whether [lane] is being held, for receptor state. */
    fun isHeld(lane: GlyphLane): Boolean = laneHeld[lane.ordinal]

    /**
     * The closest note in [lane] that can still take a judgement.
     *
     * Nearest rather than earliest on purpose: in a jump-heavy chart the note
     * you meant is the one under your finger, and taking the earliest one turns
     * a slightly-late tap on the second note into a Boo on the first.
     */
    private fun nearestUnjudged(
        lane: GlyphLane,
        at: Float,
        liftsOnly: Boolean = false,
    ): NoteRuntime? {
        var best: NoteRuntime? = null
        var bestDistance = Float.MAX_VALUE

        var index = cursor
        while (index < runtimes.size) {
            val runtime = runtimes[index]
            val note = runtime.note
            if (note.timeSeconds - at > windows.missSeconds) break
            index += 1

            if (note.lane != lane || runtime.judgement != null) continue
            if (liftsOnly && note.type != GlyphNoteType.LIFT) continue
            if (!liftsOnly && note.type == GlyphNoteType.LIFT) continue
            if (note.type == GlyphNoteType.FAKE) continue

            val distance = abs(at - note.timeSeconds)
            if (distance <= windows.missSeconds && distance < bestDistance) {
                bestDistance = distance
                best = runtime
            }
        }
        return best
    }

    private fun emit(
        note: GlyphNote,
        judgement: GlyphJudgement,
        offsetSeconds: Float?,
        isTail: Boolean = false,
        isMine: Boolean = false,
    ): GlyphJudgementEvent {
        val event = GlyphJudgementEvent(
            lane = note.lane,
            judgement = judgement,
            offsetSeconds = offsetSeconds,
            songSeconds = note.timeSeconds,
            isTail = isTail,
            isMine = isMine,
            combo = score.combo,
        )
        events += event
        return event
    }

    /**
     * Notes overlapping the window the playfield is about to draw.
     *
     * Returned as a list of [GlyphVisibleNote] rather than raw notes so the
     * renderer never has to ask the engine what a note's state is while it is
     * drawing — the answer travels with the note.
     */
    fun visibleNotes(fromSeconds: Float, toSeconds: Float): List<GlyphVisibleNote> {
        val visible = ArrayList<GlyphVisibleNote>()
        for (runtime in runtimes) {
            val note = runtime.note
            if (note.endTimeSeconds < fromSeconds) continue
            if (note.timeSeconds > toSeconds) break
            // A judged tap disappears; a hold stays until its tail is done, so
            // the body can keep drawing while it is being held.
            if (runtime.judgement != null && runtime.isResolved) continue
            visible += GlyphVisibleNote(
                note = note,
                isHeld = runtime.holding,
                isHeadJudged = runtime.judgement != null,
            )
        }
        return visible
    }
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

/** A note plus the state the playfield needs to draw it. */
data class GlyphVisibleNote(
    val note: GlyphNote,
    val isHeld: Boolean,
    val isHeadJudged: Boolean,
)
