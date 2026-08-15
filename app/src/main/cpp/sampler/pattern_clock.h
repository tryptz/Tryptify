// SPDX-License-Identifier: GPL-3.0-or-later
//
// The musical clock, as pure arithmetic.
//
// These four functions are the whole timing model of the pattern looper. They
// are deliberately free of state so the same formulas can be mirrored on the
// Kotlin side (`domain/patterns/PatternScheduler.kt`) and unit-tested there
// without an audio device — the JVM tests assert the step positions this
// header produces.

#pragma once

#include <cmath>
#include <cstdint>

namespace tryptify {

/** Hard swing ceiling: a full-swing offbeat sits one third of a step late. */
static constexpr double kMaxSwingFraction = 1.0 / 3.0;

/** Output samples between two adjacent steps at [bpm]. */
inline double framesPerStep(double sampleRate, double bpm, int stepsPerBeat) {
    if (bpm <= 0.0 || stepsPerBeat <= 0) return 0.0;
    return sampleRate * 60.0 / (bpm * static_cast<double>(stepsPerBeat));
}

/**
 * How late step [globalStep] sits because of swing.
 *
 * Offbeats — odd steps on the running clock, not within the pattern — are
 * pushed late; downbeats never move. Tying it to the clock rather than the
 * pattern is what keeps an odd-length pattern (12 steps, 5 steps) from
 * flipping its feel every time it loops.
 */
inline double swingDelayFrames(int64_t globalStep, double swing, double fps) {
    if (swing <= 0.0) return 0.0;
    const double amount = swing > 1.0 ? 1.0 : swing;
    return (globalStep % 2 != 0) ? amount * kMaxSwingFraction * fps : 0.0;
}

/**
 * Frames from the boundary of step [globalStep] to the boundary of the next.
 *
 * Expressed as a delta rather than an absolute grid position on purpose: a
 * tempo change mid-loop then only affects the step after it, instead of
 * yanking the playhead to wherever the new tempo says the current step "should
 * have" started.
 */
inline double framesToNextStep(int64_t globalStep, double swing, double fps) {
    const double delta = fps + swingDelayFrames(globalStep + 1, swing, fps) -
                         swingDelayFrames(globalStep, swing, fps);
    return delta < 1.0 ? 1.0 : delta;
}

/**
 * The step a hit at [framesIntoStep] past step [globalStep] should be written
 * to, when recording is quantized to every [quantum] steps.
 *
 * quantum <= 1 snaps to the nearest step; 4 snaps to the nearest beat on a
 * sixteenth grid, and so on. Returns a global step index, which the caller
 * folds into the pattern.
 */
inline int64_t quantizeToStep(int64_t globalStep, double framesIntoStep, double fps,
                              int quantum) {
    if (fps <= 0.0) return globalStep;
    const int q = quantum < 1 ? 1 : quantum;
    const double exact = static_cast<double>(globalStep) + framesIntoStep / fps;
    const double scaled = exact / static_cast<double>(q);
    return static_cast<int64_t>(std::llround(scaled)) * static_cast<int64_t>(q);
}

/** Folds a global step index into [0, length). Correct for negatives. */
inline int wrapStep(int64_t globalStep, int length) {
    if (length <= 0) return 0;
    int64_t m = globalStep % static_cast<int64_t>(length);
    if (m < 0) m += length;
    return static_cast<int>(m);
}

}  // namespace tryptify
