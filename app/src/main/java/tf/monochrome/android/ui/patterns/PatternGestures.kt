// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.patterns

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * The looper's haptic vocabulary.
 *
 * Kept in one place because the rule that matters is restraint: a step
 * sequencer invites hundreds of taps a minute, and a device buzzing on every
 * one of them stops being an instrument and starts being irritating. So the
 * palette is two strengths, and the *absence* of feedback is used as much as
 * its presence — painting a run of steps by dragging ticks once per step
 * crossed, never per frame, and the playhead never vibrates at all.
 *
 * Only the two universally available [HapticFeedbackType] constants are used.
 * The richer set arrived in a later Compose release than this project builds
 * against, and a nonexistent constant is a compile error rather than a
 * graceful degradation.
 */
object PatternHaptics {

    /** A step turning on — the firmest thing in the palette. */
    fun stepOn(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** A step turning off — lighter, so on and off are distinguishable blind. */
    fun stepOff(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** Crossing into a new step while dragging across the grid. */
    fun paint(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** Switching or queueing a pattern. */
    fun patternChange(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** Moving between grid pages or channels. */
    fun pageChange(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** Transport state changes: play, stop, record arm and disarm. */
    fun transport(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /**
     * Tapping a pad to audition or to record a hit.
     *
     * Deliberately the light one. A pad tap already produces a sound, which is
     * the feedback that matters; the haptic is only there to confirm the touch
     * registered when the sample is quiet or the channel is empty.
     */
    fun padHit(haptics: HapticFeedback) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
}
