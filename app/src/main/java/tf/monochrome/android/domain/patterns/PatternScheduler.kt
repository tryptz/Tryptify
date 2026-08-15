// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.domain.patterns

import kotlin.math.roundToLong

/**
 * The musical clock, as arithmetic.
 *
 * This is the Kotlin mirror of `cpp/sampler/pattern_clock.h`, and it exists so
 * the timing model can be proved on the JVM: `PatternSchedulerTest` asserts
 * the step positions, the swing offsets and the quantization decisions that
 * the audio thread will make, with no device and no audio output involved.
 *
 * Nothing in the app drives audio from here — the native engine owns the
 * running clock. What the UI uses it for is the inverse question: given a
 * frame position the engine published, which step is that, and how long until
 * the next one. Keeping both sides on identical formulas is what stops the
 * playhead from disagreeing with what you hear.
 */
object PatternScheduler {

    /** A fully swung offbeat sits one third of a step late. */
    const val MAX_SWING_FRACTION = 1.0 / 3.0

    const val MIN_BPM = 20f
    const val MAX_BPM = 300f

    /** Output samples between two adjacent steps. */
    fun framesPerStep(sampleRate: Double, bpm: Double, stepsPerBeat: Int): Double {
        if (bpm <= 0.0 || stepsPerBeat <= 0 || sampleRate <= 0.0) return 0.0
        return sampleRate * 60.0 / (bpm * stepsPerBeat)
    }

    /**
     * How late step [globalStep] sits because of swing.
     *
     * Offbeats are pushed late and downbeats never move. The parity is taken
     * from the running clock rather than from the position inside the pattern,
     * which is what keeps an odd-length pattern — 5 steps, 12 steps — from
     * flipping its feel on every repeat.
     */
    fun swingDelayFrames(globalStep: Long, swing: Double, framesPerStep: Double): Double {
        if (swing <= 0.0) return 0.0
        val amount = swing.coerceAtMost(1.0)
        return if (globalStep % 2 != 0L) amount * MAX_SWING_FRACTION * framesPerStep else 0.0
    }

    /**
     * Frames from the boundary of [globalStep] to the boundary of the next.
     *
     * A delta rather than an absolute grid position, so a tempo change only
     * moves the step after it instead of yanking the playhead to wherever the
     * new tempo says the current step should have begun.
     */
    fun framesToNextStep(globalStep: Long, swing: Double, framesPerStep: Double): Double {
        val delta = framesPerStep +
            swingDelayFrames(globalStep + 1, swing, framesPerStep) -
            swingDelayFrames(globalStep, swing, framesPerStep)
        return if (delta < 1.0) 1.0 else delta
    }

    /**
     * The step a hit lands on when recording is quantized to every [quantum]
     * steps. [framesIntoStep] is how far past [globalStep]'s boundary it fell.
     */
    fun quantizeToStep(
        globalStep: Long,
        framesIntoStep: Double,
        framesPerStep: Double,
        quantum: Int,
    ): Long {
        if (framesPerStep <= 0.0) return globalStep
        val q = quantum.coerceAtLeast(1)
        val exact = globalStep + framesIntoStep / framesPerStep
        return (exact / q).roundToLong() * q
    }

    /** Folds a global step index into `0 until length`. Correct for negatives. */
    fun wrapStep(globalStep: Long, length: Int): Int {
        if (length <= 0) return 0
        val m = globalStep % length
        return (if (m < 0) m + length else m).toInt()
    }

    /** Wall-clock length of one loop of [pattern] at [bpm], for the UI's readout. */
    fun loopDurationMs(pattern: Pattern, bpm: Float): Long {
        val effective = (pattern.bpmOverride ?: bpm).coerceIn(MIN_BPM, MAX_BPM)
        val perStepMs = 60_000.0 / (effective * pattern.stepsPerBeat.coerceAtLeast(1))
        return (perStepMs * pattern.lengthSteps).roundToLong()
    }

    /**
     * Which bar of the loop step [stepIndex] belongs to, 1-based — the "2 of 4"
     * the transport shows.
     */
    fun barOfStep(pattern: Pattern, stepIndex: Int): Int {
        val perBar = (pattern.stepsPerBeat * pattern.beatsPerBar).coerceAtLeast(1)
        return stepIndex / perBar + 1
    }

    /** True when [stepIndex] falls on a beat — the accented trigs in the grid. */
    fun isBeat(pattern: Pattern, stepIndex: Int): Boolean =
        stepIndex % pattern.stepsPerBeat.coerceAtLeast(1) == 0

    /** True when [stepIndex] starts a bar. */
    fun isBarStart(pattern: Pattern, stepIndex: Int): Boolean =
        stepIndex % (pattern.stepsPerBeat * pattern.beatsPerBar).coerceAtLeast(1) == 0
}
