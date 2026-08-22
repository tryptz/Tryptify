// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.training

import tf.monochrome.android.ui.glyph.GlyphGauntlet

/**
 * Short technical drills.
 *
 * Each one names a single skill and a target, because "get better" is not
 * practice and a drill that mixes three techniques cannot tell you which one
 * failed. They are short by design — a minute of one pattern, repeated, beats
 * a full chart for building a specific motion.
 *
 * The drills are selection criteria over the chart the player already has
 * rather than authored patterns: a stream gauntlet finds the densest run of
 * consecutive notes in the chart and loops it, so the practice is on the music
 * being played instead of on a synthetic exercise.
 */
object GlyphGauntlets {

    val ALL: List<GlyphGauntlet> = listOf(
        GlyphGauntlet(
            id = "streams",
            name = "Streams",
            focus = "Even runs",
            description = "The densest unbroken run in the chart, looped. " +
                "Keeping the spacing even matters more than keeping up.",
            targetAccuracy = 0.90f,
        ),
        GlyphGauntlet(
            id = "jumps",
            name = "Jumps",
            focus = "Two at once",
            description = "Every passage with both feet landing together. " +
                "Aim for one sound, not two.",
            targetAccuracy = 0.92f,
        ),
        GlyphGauntlet(
            id = "holds",
            name = "Holds",
            focus = "Staying down",
            description = "Holds and rolls, including the ones that overlap taps. " +
                "Dropped tails cost more than a late head.",
            targetAccuracy = 0.95f,
        ),
        GlyphGauntlet(
            id = "alternating",
            name = "Alternating",
            focus = "Left, right, repeat",
            description = "Runs that strictly alternate. " +
                "Built to catch a foot that leads on both notes.",
            targetAccuracy = 0.90f,
        ),
        GlyphGauntlet(
            id = "timing",
            name = "Timing control",
            focus = "Consistency",
            description = "A sparse quarter-note passage with tightened windows. " +
                "Scored on spread, not on hits.",
            targetAccuracy = 0.97f,
        ),
    )

    fun byId(id: String): GlyphGauntlet? = ALL.firstOrNull { it.id == id }
}
