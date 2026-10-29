// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import kotlin.math.floor
import kotlin.random.Random

/**
 * What a StepMania character dances, and when, as DDR's dancers do and as
 * StepMania's DancingCharacters imitates them: warming up until the chart's
 * first measure, dancing from it, resting once the run has failed; each move
 * played through, then another, the camera cutting to a new angle every two
 * measures and spinning through a stop.
 *
 * Unlike StepMania, which only speeds its dancers up and down with the tempo,
 * every move here is locked to the song's beat. The DDR PC moves the archive
 * shares are cut in measures of 61 frames (a beat is [FRAMES_PER_BEAT]
 * frames, a step lands on it), so a move's frame is its beat times that, and
 * the dancer stays on the music through tempo changes and stops. A move
 * starts on a measure and lasts whole measures, so the next starts on one too.
 *
 * Pure Kotlin: the renderer asks [update] once a frame and draws what it says.
 */
class DancerChoreography(
    /** The moves by phase, by name. */
    private val moves: Map<Phase, List<String>>,
    /** A move's length in beats once it is loaded; null while it is not. */
    private val beatsOf: (String) -> Float?,
    private val random: Random = Random.Default,
) {
    enum class Phase { WARMUP, DANCE, REST }

    /** What to draw: a move at a frame, blended in from the one before, seen from [yawDegrees]. */
    class Step {
        var move: String? = null
        var frame: Float = 0f
        /** The move being left, and how far the new one has come in (0 to 1); 1 when nothing is left. */
        var previous: String? = null
        var previousFrame: Float = 0f
        var blend: Float = 1f
        /** The camera's turn about the dancer, degrees; 0 faces the player. */
        var yawDegrees: Float = 0f
    }

    private val step = Step()
    private var phase: Phase? = null
    private var move: String? = null
    private var moveStart = 0f
    /** The move after this one, picked as this one starts, so it is read in time. */
    private var queued: String? = null
    private var previous: String? = null
    private var previousStart = 0f
    private var switchedAt = Float.NEGATIVE_INFINITY
    private var lastBeat = Float.NaN

    private var cameraEnd = Float.NEGATIVE_INFINITY
    private var cameraStart = 0f
    private var yawFrom = 0f
    private var yawTo = 0f
    private var spin = 0f

    /** The moves to have loaded now: the one danced, the one left, the next, and the first of each phase. */
    fun wanted(): Set<String> = buildSet {
        move?.let(::add)
        previous?.let(::add)
        queued?.let(::add)
        for (p in Phase.entries) movesFor(p).firstOrNull()?.let(::add)
    }

    /**
     * The step at [beat] of the song. [dtSeconds] is the time since the last
     * update, for the camera's spin through a stop; [firstNoteBeat] is where
     * the chart's notes begin, [failed] whether the run has.
     */
    fun update(beat: Float, dtSeconds: Float, firstNoteBeat: Float, failed: Boolean): Step {
        // Back in time (a retry, a seek): start over.
        if (!lastBeat.isNaN() && beat < lastBeat - REWIND_BEATS) reset()
        val stalled = !lastBeat.isNaN() && beat == lastBeat && dtSeconds > 0f
        lastBeat = beat

        val danceFrom = floor(firstNoteBeat / BEATS_PER_MEASURE) * BEATS_PER_MEASURE
        val wantedPhase = when {
            failed -> Phase.REST
            beat < danceFrom -> Phase.WARMUP
            else -> Phase.DANCE
        }
        if (wantedPhase != phase || move == null) {
            val start = when (wantedPhase) {
                Phase.DANCE -> if (phase == Phase.WARMUP && beat - danceFrom < BEATS_PER_MEASURE) danceFrom else measureOf(beat)
                Phase.WARMUP -> measureOf(beat)
                Phase.REST -> floor(beat)
            }
            // Straight in with one already read when there is one: the first of a phase is kept loaded.
            val first = movesFor(wantedPhase).filter { beatsOf(it) != null }.randomOrNull(random)
                ?: movesFor(wantedPhase).randomOrNull(random)
            begin(wantedPhase, first, start, beat)
        } else {
            val length = move?.let(beatsOf)
            if (length != null && beat >= moveStart + length) {
                // Whole measures long, so the next starts on a measure; the one
                // queued if it has been read by now, else this one again.
                val nextStart = moveStart + length * floor((beat - moveStart) / length)
                val next = queued?.takeIf { beatsOf(it) != null }
                if (next != null) begin(wantedPhase, next, nextStart, beat) else moveStart = nextStart
            }
        }

        step.move = move
        step.frame = frameOf(beat, moveStart)
        val blend = ((beat - switchedAt) / BLEND_BEATS).coerceIn(0f, 1f)
        step.blend = blend * blend * (3 - 2 * blend)
        step.previous = if (step.blend < 1f) previous else null
        step.previousFrame = frameOf(beat, previousStart)
        step.yawDegrees = camera(beat, stalled, dtSeconds)
        return step
    }

    private fun begin(newPhase: Phase, newMove: String?, start: Float, beat: Float) {
        previous = move
        previousStart = moveStart
        phase = newPhase
        move = newMove
        moveStart = start
        switchedAt = if (previous == null) Float.NEGATIVE_INFINITY else start
        queued = movesFor(newPhase).filter { it != newMove }.ifEmpty { movesFor(newPhase) }.randomOrNull(random)
    }

    private fun reset() {
        phase = null
        move = null
        previous = null
        queued = null
        switchedAt = Float.NEGATIVE_INFINITY
        cameraEnd = Float.NEGATIVE_INFINITY
        spin = 0f
    }

    /** A phase's moves, or a stand-in's: a character without warm-ups warms up resting. */
    private fun movesFor(p: Phase): List<String> = when (p) {
        Phase.WARMUP -> moves[Phase.WARMUP].orEmpty().ifEmpty { moves[Phase.REST].orEmpty() }.ifEmpty { moves[Phase.DANCE].orEmpty() }
        Phase.DANCE -> moves[Phase.DANCE].orEmpty().ifEmpty { moves[Phase.WARMUP].orEmpty() }.ifEmpty { moves[Phase.REST].orEmpty() }
        Phase.REST -> moves[Phase.REST].orEmpty().ifEmpty { moves[Phase.WARMUP].orEmpty() }.ifEmpty { moves[Phase.DANCE].orEmpty() }
    }

    private fun frameOf(beat: Float, start: Float) = ((beat - start) * FRAMES_PER_BEAT).coerceAtLeast(0f)

    /**
     * StepMania's camera: every two measures a cut, two times in six to a
     * sweep (from one side or the front, round by up to [SWEEP_TURN] more),
     * otherwise to a still angle; through a stop it spins.
     */
    private fun camera(beat: Float, stalled: Boolean, dt: Float): Float {
        if (beat >= cameraEnd) {
            cameraStart = floor(beat / CAMERA_BEATS) * CAMERA_BEATS
            cameraEnd = cameraStart + CAMERA_BEATS
            if (random.nextInt(6) >= 4) {
                yawFrom = side() * SWEEP_FROM
                yawTo = yawFrom + side() * SWEEP_TURN
            } else {
                yawFrom = side() * STILL_ANGLE
                yawTo = yawFrom
            }
        }
        if (stalled) spin += dt * SPIN_DEGREES_PER_SECOND
        val t = ((beat - cameraStart) / CAMERA_BEATS).coerceIn(0f, 1f)
        return yawFrom + (yawTo - yawFrom) * t + spin
    }

    private fun side(): Int = random.nextInt(3) - 1

    private fun measureOf(beat: Float) = floor(beat / BEATS_PER_MEASURE) * BEATS_PER_MEASURE

    companion object {
        /** DDR PC's moves: 61 frames a measure of four beats. */
        const val FRAMES_PER_BEAT = 61f / 4f
        const val BEATS_PER_MEASURE = 4f

        /** A move's length in beats from its last frame: whole beats, at least one. */
        fun beatsOf(lastFrame: Float): Float = maxOf(1f, Math.round((lastFrame + 1f) / FRAMES_PER_BEAT).toFloat())

        private const val BLEND_BEATS = 0.5f
        private const val REWIND_BEATS = 1f
        private const val CAMERA_BEATS = 8f
        // StepMania's DancingCharacters: sweep pan range and variance, still pan range, the spin.
        private const val SWEEP_FROM = 45f
        private const val SWEEP_TURN = 60f
        private const val STILL_ANGLE = 35f
        private const val SPIN_DEGREES_PER_SECOND = 40f
    }
}
