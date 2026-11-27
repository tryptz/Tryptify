package io.github.chronosauros.contour.core

/**
 * How the Tune band row uses a row of [widthDp]: one well (an [INSET] on each side) with the bands 1..N, then "+" when a
 * band can still be added, then "-" - both [BUTTON] wide cells pinned at the well's right end. Pure arithmetic in dp so
 * the table can be tested.
 *
 * The numbers take the rest of the well ([Plan.numbersDp], their own inset included). Band cells touch; when they all
 * fit they are [CELL] wide and the rest stays empty; otherwise they scroll, sized so the last visible cell is cut at
 * about its middle (m whole cells and one half), so the cut reads as "more to the right". Cells are never narrower than
 * [CELL] and never wider than [MAX_CELL].
 */
object BandStripPlan {
    const val BUTTON = 44.0
    const val INSET = 4.0
    /** The band cell (owner's pick 14.10: 36 dp well cells with 20 sp numbers; "+" and "-" inside the same well). */
    const val CELL = 36.0
    const val MAX_CELL = 42.0

    data class Plan(val scroll: Boolean, val cellDp: Double, val numbersDp: Double)

    fun plan(widthDp: Double, bands: Int, showPlus: Boolean): Plan {
        val pinned = if (showPlus) 2 else 1
        val numbers = (widthDp - 2 * INSET - pinned * BUTTON).coerceAtLeast(0.0)
        val inner = (numbers - 2 * INSET).coerceAtLeast(0.0)
        if (bands * CELL <= inner) return Plan(false, CELL, numbers)
        for (m in (bands - 1) downTo 1) {
            val c = inner / (m + 0.5)
            if (c >= CELL) return Plan(true, minOf(c, MAX_CELL), numbers)
        }
        return Plan(true, CELL, numbers)
    }
}
