// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.glyph.chart.GlyphTiming

/**
 * How fast the chart scrolls, in StepMania's three families.
 *
 * The distinction is not cosmetic and it is not a speed preference. It decides
 * **what the distance between two notes on screen means**:
 *
 * * [XMod] measures distance in *beats*. Spacing is musical — a sixteenth is
 *   always a quarter of a beat apart — so patterns keep their shape, and a
 *   tempo change makes the whole field speed up or slow down. Stops freeze the
 *   scroll dead, because no beats are passing.
 * * [CMod] measures distance in *seconds*. Spacing is constant whatever the
 *   music does, so reading speed never changes; the cost is that a tempo change
 *   silently re-spaces the notes, and a stop scrolls straight through.
 * * [MMod] is [XMod] with the multiplier solved for you, so the chart's fastest
 *   section lands exactly on a chosen speed and nothing is ever faster than you
 *   asked for.
 *
 * Anyone who plays charts with tempo changes has an opinion about which of
 * these they want, which is why all three are here rather than one.
 */
sealed interface GlyphScrollMode {

    /** Short label, in the notation players already use: `2.00x`, `C400`, `M400`. */
    val label: String

    /** A longer line for the control, saying what it does rather than what it is. */
    val summary: String

    /**
     * Scroll distance between two song positions, in beats-at-1×.
     *
     * The single primitive the playfield needs. One unit is the on-screen gap
     * one beat occupies at a 1× multiplier, so a renderer multiplies by a
     * pixels-per-unit constant and is done — it never has to know which mode is
     * active.
     */
    fun scrollUnits(timing: GlyphTiming, fromSeconds: Float, toSeconds: Float): Float

    /**
     * Seconds a note is visible before it reaches the receptor, at [bpm].
     *
     * Used to size the culling window and to keep the ghost overlay in step.
     * Constant for [CMod]; for the others it moves with the tempo, which is the
     * whole point of them.
     */
    fun visibleSeconds(bpm: Float): Float

    /** Multiplier by tempo. Musical spacing; speed follows the music. */
    data class XMod(val multiplier: Float) : GlyphScrollMode {
        override val label: String get() = "%.2fx".format(multiplier)
        override val summary: String
            get() = "Spacing stays musical. Tempo changes speed the field up and down."

        override fun scrollUnits(
            timing: GlyphTiming,
            fromSeconds: Float,
            toSeconds: Float,
        ): Float {
            // Beat-space, so a stop — during which secondsToBeat is flat —
            // contributes no distance and the arrows hold still.
            val from = timing.secondsToBeat(fromSeconds)
            val to = timing.secondsToBeat(toSeconds)
            return (to - from) * multiplier
        }

        override fun visibleSeconds(bpm: Float): Float =
            if (bpm <= 0f || multiplier <= 0f) FALLBACK_SECONDS
            else UNITS_ON_SCREEN * 60f / (bpm * multiplier)
    }

    /**
     * Constant speed, named by the tempo it reads as.
     *
     * `C400` scrolls exactly as fast as a 400 BPM chart under 1×, no matter what
     * the song is doing. Tempo changes and stops pass underneath without moving
     * the field, which is why it is the mode of choice for anything with a gimmick.
     */
    data class CMod(val targetBpm: Float) : GlyphScrollMode {
        override val label: String get() = "C${targetBpm.toInt()}"
        override val summary: String
            get() = "Constant reading speed. Tempo changes and stops do not move the field."

        override fun scrollUnits(
            timing: GlyphTiming,
            fromSeconds: Float,
            toSeconds: Float,
        ): Float {
            // Time-space. The timing map is deliberately not consulted: that is
            // exactly what makes a stop scroll straight through.
            if (targetBpm <= 0f) return 0f
            return (toSeconds - fromSeconds) * targetBpm / 60f
        }

        override fun visibleSeconds(bpm: Float): Float =
            if (targetBpm <= 0f) FALLBACK_SECONDS else UNITS_ON_SCREEN * 60f / targetBpm
    }

    /**
     * Multiplier solved so the chart's fastest section reads at [targetBpm].
     *
     * Keeps [XMod]'s musical spacing while guaranteeing a ceiling, which is the
     * reason it exists: on a chart that doubles tempo for one section, an XMod
     * comfortable everywhere else becomes unreadable there.
     */
    data class MMod(val targetBpm: Float, val chartMaxBpm: Float) : GlyphScrollMode {
        val multiplier: Float
            get() = if (chartMaxBpm <= 0f) 1f else targetBpm / chartMaxBpm

        override val label: String get() = "M${targetBpm.toInt()}"
        override val summary: String
            get() = "Musical spacing, capped so the fastest section reads at ${targetBpm.toInt()}."

        override fun scrollUnits(
            timing: GlyphTiming,
            fromSeconds: Float,
            toSeconds: Float,
        ): Float = XMod(multiplier).scrollUnits(timing, fromSeconds, toSeconds)

        override fun visibleSeconds(bpm: Float): Float = XMod(multiplier).visibleSeconds(bpm)
    }

    companion object {
        /**
         * Beats of chart visible above the receptor at 1×.
         *
         * Two bars. It sets what every mod value *means* — C400 is 1.2 seconds
         * of reading time because of this number — so changing it re-scales
         * every saved preference and is not a tuning knob.
         */
        const val UNITS_ON_SCREEN = 8f

        /** Used only when a tempo is missing or nonsensical. */
        const val FALLBACK_SECONDS = 1.2f

        /** The default: constant speed, which is the safer read on a phone. */
        val DEFAULT: GlyphScrollMode = CMod(400f)

        /** Offered CMod values, in the steps players actually use. */
        val C_STEPS = listOf(200f, 300f, 400f, 500f, 600f, 800f)

        /** Offered XMod multipliers. */
        val X_STEPS = listOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 4.0f)

        /** Offered MMod ceilings. */
        val M_STEPS = listOf(300f, 400f, 500f, 600f)
    }
}

/** Which family a control is showing. Kept separate so a switch keeps its value. */
enum class GlyphScrollFamily(val label: String) {
    X("XMod"),
    C("CMod"),
    M("MMod"),
}
