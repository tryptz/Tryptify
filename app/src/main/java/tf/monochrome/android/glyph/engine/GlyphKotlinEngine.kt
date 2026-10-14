// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import kotlin.math.abs
import tf.monochrome.android.glyph.asset.GlyphLane
import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.chart.GlyphNoteType
import tf.monochrome.android.glyph.chart.GlyphTiming

/**
 * The rules of play in Kotlin: the twin of `cpp/glyph/glyph_engine.cpp`.
 *
 * What the JVM unit tests drive, and what plays if the native library cannot
 * be loaded. Every comparison and every float operation is in the order the
 * C++ makes them, so the two judge a run identically; change one and the
 * other's tests say so.
 *
 * Notes are visited in time order through a sliding index rather than
 * scanned, so a five-thousand-note Challenge chart costs the same per frame
 * as a Beginner one.
 */
internal class KotlinGlyphEngineCore(
    private val chart: GlyphChart,
    private val timing: GlyphTiming,
    private val windows: GlyphTimingWindows,
    private val config: GlyphGameplayEngine.Config,
) : GlyphEngineCore {

    /** Everything the engine knows about one note during play. */
    private class NoteRuntime(val note: GlyphNote, val index: Int) {
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

    private val runtimes: List<NoteRuntime> = chart.notes.mapIndexed { i, note -> NoteRuntime(note, i) }

    // Notes are sorted by time, so everything before this index is resolved or
    // permanently out of reach and never has to be looked at again.
    private var cursor = 0

    private val score = GlyphScore(chart.judgementCount)

    private val laneHeld = BooleanArray(GlyphLane.entries.size)

    /** Holds currently being held down, by lane. */
    private val activeHold = arrayOfNulls<NoteRuntime>(GlyphLane.entries.size)

    private val events = ArrayList<GlyphJudgementEvent>()

    override var positionSeconds: Float = 0f
        private set

    override val combo: Int get() = score.combo

    override val isFinished: Boolean get() = cursor >= runtimes.size

    override val heldMask: Int
        get() {
            var mask = 0
            for (lane in laneHeld.indices) if (laneHeld[lane]) mask = mask or (1 shl lane)
            return mask
        }

    override fun scoreSnapshot(): GlyphScoreSnapshot = score.snapshot()

    override fun drainEvents(): List<GlyphJudgementEvent> {
        if (events.isEmpty()) return emptyList()
        val drained = ArrayList(events)
        events.clear()
        return drained
    }

    override fun advanceTo(songSeconds: Float) {
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

    override fun press(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent? {
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

    override fun release(lane: GlyphLane, songSeconds: Float): GlyphJudgementEvent? {
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

    override fun reset(fromSeconds: Float, untilSeconds: Float) {
        var total = 0
        for (runtime in runtimes) {
            runtime.judgement = null
            runtime.tailResolved = false
            runtime.holding = false
            runtime.releasedAtSeconds = Float.NaN
            runtime.lastRollTapSeconds = 0f
            val note = runtime.note
            if (note.timeSeconds < fromSeconds || note.timeSeconds >= untilSeconds) {
                // Outside the practised window: settled, unscored and never
                // drawn, so a loop's first pass does not open with a miss for
                // every note before it.
                runtime.judgement = GlyphJudgement.MISS
                runtime.tailResolved = true
            } else if (note.type.isScorable) {
                total += if (note.type.hasTail) 2 else 1
            }
        }
        cursor = 0
        while (cursor < runtimes.size && runtimes[cursor].isResolved) cursor += 1
        events.clear()
        score.reset(totalJudgements = total)
        java.util.Arrays.fill(laneHeld, false)
        java.util.Arrays.fill(activeHold, null)
        positionSeconds = if (fromSeconds.isFinite()) fromSeconds else 0f
    }

    override fun fillVisible(
        fromSeconds: Float,
        toSeconds: Float,
        positionSeconds: Float,
        scrollMode: GlyphScrollMode,
        frame: GlyphVisibleFrame,
    ) {
        // Counted first so the frame is grown once, before anything is written.
        var total = 0
        forEachVisible(fromSeconds, toSeconds) { total += 1 }
        frame.ensureCapacity(total)

        var slot = 0
        forEachVisible(fromSeconds, toSeconds) { runtime ->
            val note = runtime.note
            val head = scrollMode.scrollUnits(timing, positionSeconds, note.timeSeconds)
            // A held body's top stops at the receptor while its tail travels.
            val bodyHead = if (runtime.holding && positionSeconds < note.timeSeconds) {
                scrollMode.scrollUnits(timing, positionSeconds, positionSeconds)
            } else {
                head
            }
            frame.put(
                slot = slot,
                noteIndex = runtime.index,
                held = runtime.holding,
                headJudged = runtime.judgement != null,
                headUnits = head,
                bodyHeadUnits = bodyHead,
                tailUnits = scrollMode.scrollUnits(timing, positionSeconds, note.endTimeSeconds),
            )
            slot += 1
        }
        frame.count = slot
    }

    /**
     * From the cursor: everything before it is resolved, and a resolved note
     * is never drawn, so starting at zero would only re-skip the whole past.
     */
    private inline fun forEachVisible(
        fromSeconds: Float,
        toSeconds: Float,
        action: (NoteRuntime) -> Unit,
    ) {
        var index = cursor
        while (index < runtimes.size) {
            val runtime = runtimes[index]
            val note = runtime.note
            index += 1
            if (note.endTimeSeconds < fromSeconds) continue
            if (note.timeSeconds > toSeconds) break
            // A judged tap disappears; a hold stays until its tail is done, so
            // the body can keep drawing while it is being held.
            if (runtime.judgement != null && runtime.isResolved) continue
            action(runtime)
        }
    }
}
