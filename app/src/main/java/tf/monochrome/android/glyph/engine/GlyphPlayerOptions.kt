// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.glyph.asset.GlyphLane

/**
 * StepMania's "Turn" options: one lane permutation for the whole chart.
 *
 * Left and Right turn the pad a quarter: under Left every Up becomes a Left,
 * every Left a Down, every Down a Right and every Right an Up, as if the
 * arrows had been rotated a quarter turn counter-clockwise. Right is the
 * other way. Shuffle is a permutation seeded by the chart, so the same chart
 * shuffles the same way every time and can be learned.
 */
enum class GlyphTurn(val id: String, val label: String) {
    OFF("off", "Off"),
    MIRROR("mirror", "Mirror"),
    LEFT("left", "Left"),
    RIGHT("right", "Right"),
    SHUFFLE("shuffle", "Shuffle"),
    ;

    /**
     * Where a note written in [lane] lands. [shuffleSeed] seeds Shuffle and
     * is ignored by the rest.
     */
    fun map(lane: GlyphLane, shuffleSeed: Long = 0L): GlyphLane = when (this) {
        OFF -> lane
        MIRROR -> GlyphLane.mirrorOf(lane)
        LEFT -> when (lane) {
            GlyphLane.UP -> GlyphLane.LEFT
            GlyphLane.LEFT -> GlyphLane.DOWN
            GlyphLane.DOWN -> GlyphLane.RIGHT
            GlyphLane.RIGHT -> GlyphLane.UP
        }
        RIGHT -> when (lane) {
            GlyphLane.UP -> GlyphLane.RIGHT
            GlyphLane.RIGHT -> GlyphLane.DOWN
            GlyphLane.DOWN -> GlyphLane.LEFT
            GlyphLane.LEFT -> GlyphLane.UP
        }
        SHUFFLE -> shuffleOrder(shuffleSeed)[lane.ordinal]
    }

    companion object {
        fun fromId(id: String?): GlyphTurn = entries.firstOrNull { it.id == id } ?: OFF

        /** One permutation of the lanes, the same for the same seed. */
        fun shuffleOrder(seed: Long): List<GlyphLane> {
            val order = GlyphLane.entries.toMutableList()
            val random = java.util.Random(seed)
            for (index in order.indices.reversed()) {
                val swap = random.nextInt(index + 1)
                order[index] = order[swap].also { order[swap] = order[index] }
            }
            return order
        }
    }
}

/**
 * StepMania's "Appearance" options: where on the way down a note can be seen.
 *
 * Hidden fades notes out as they near the receptors, Sudden only shows them
 * once they are close, and Stealth hides them entirely. Looks only; the notes
 * are judged exactly as before.
 */
enum class GlyphAppearance(val id: String, val label: String) {
    VISIBLE("visible", "Visible"),
    HIDDEN("hidden", "Hidden"),
    SUDDEN("sudden", "Sudden"),
    STEALTH("stealth", "Stealth"),
    ;

    /**
     * How opaque a note is at [distance]: 0 on the receptor line, 1 at the
     * far edge of the playfield, past it beyond.
     */
    fun alphaAt(distance: Float): Float = when (this) {
        VISIBLE -> 1f
        // Gone by the last 25% of the way, fully there past 45%.
        HIDDEN -> ramp(distance, from = 0.25f, to = 0.45f)
        // Nothing until the last 60%, all there inside 40%.
        SUDDEN -> 1f - ramp(distance, from = 0.40f, to = 0.60f)
        STEALTH -> 0f
    }

    companion object {
        fun fromId(id: String?): GlyphAppearance = entries.firstOrNull { it.id == id } ?: VISIBLE

        private fun ramp(value: Float, from: Float, to: Float): Float =
            ((value - from) / (to - from)).coerceIn(0f, 1f)
    }
}

/**
 * StepMania's timing difficulty ("Judge"): every window scaled together.
 * Judge 4 is the standard set; a run under any other is marked on the high
 * score table.
 */
enum class GlyphJudge(val level: Int, val scale: Float) {
    J1(1, 1.50f),
    J2(2, 1.33f),
    J3(3, 1.16f),
    J4(4, 1.00f),
    J5(5, 0.84f),
    J6(6, 0.66f),
    J7(7, 0.50f),
    JUSTICE(8, 0.33f),
    ;

    val label: String get() = if (this == JUSTICE) "Justice" else "J$level"

    companion object {
        fun forScale(scale: Float): GlyphJudge =
            entries.minBy { kotlin.math.abs(it.scale - scale) }
    }
}
