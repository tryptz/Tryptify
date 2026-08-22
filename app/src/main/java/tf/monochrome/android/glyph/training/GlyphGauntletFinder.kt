// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.training

import tf.monochrome.android.glyph.chart.GlyphChart
import tf.monochrome.android.glyph.chart.GlyphNote
import tf.monochrome.android.glyph.chart.GlyphNoteType

/**
 * Finds the passage in a chart that a drill is about.
 *
 * The drills are selection criteria over the player's own chart rather than
 * authored exercises. Practising a synthetic stream teaches you that stream;
 * practising the densest stream in the song you are stuck on teaches you the
 * song. It also means every chart has all five gauntlets without anyone
 * authoring anything.
 *
 * Each finder returns the window with the highest score for its criterion,
 * scanning at [WINDOW_SECONDS] and keeping whatever it lands on. Returns null
 * when the chart genuinely has none of that pattern, which the UI reports
 * rather than silently looping an arbitrary passage.
 */
object GlyphGauntletFinder {

    /** The segment for [gauntletId], or null if the chart has no such passage. */
    fun findSegment(chart: GlyphChart, gauntletId: String): GlyphLoopSegment? {
        val notes = chart.notes.filter { it.type.isScorable }
        if (notes.size < MINIMUM_NOTES) return null

        val scorer: (List<GlyphNote>) -> Float = when (gauntletId) {
            "streams" -> ::streamScore
            "jumps" -> ::jumpScore
            "holds" -> ::holdScore
            "alternating" -> ::alternatingScore
            "timing" -> ::timingScore
            else -> return null
        }

        var best: GlyphLoopSegment? = null
        var bestScore = 0f

        // Windows overlap by half so a passage straddling a boundary is still
        // found whole by one of them.
        var start = notes.first().timeSeconds
        val end = notes.last().timeSeconds
        while (start < end) {
            val stop = start + WINDOW_SECONDS
            val window = notes.filter { it.timeSeconds >= start && it.timeSeconds < stop }
            if (window.size >= MINIMUM_NOTES) {
                val score = scorer(window)
                if (score > bestScore) {
                    bestScore = score
                    best = GlyphLoopSegment(
                        startSeconds = (start - LEAD_IN_SECONDS).coerceAtLeast(0f),
                        endSeconds = stop + LEAD_IN_SECONDS,
                    )
                }
            }
            start += WINDOW_SECONDS / 2f
        }

        return if (bestScore <= 0f) null else best
    }

    /** Density of single notes with even spacing — what a stream actually is. */
    private fun streamScore(window: List<GlyphNote>): Float {
        val singles = window.filter { !it.type.isHoldLike }
        if (singles.size < MINIMUM_NOTES) return 0f
        val gaps = singles.zipWithNext { a, b -> b.timeSeconds - a.timeSeconds }
        if (gaps.isEmpty()) return 0f
        val mean = gaps.average().toFloat()
        if (mean <= 0f) return 0f
        // Evenness matters as much as density: a burst of notes at random
        // spacing is not a stream and does not train the same thing.
        val spread = gaps.map { kotlin.math.abs(it - mean) }.average().toFloat()
        val evenness = 1f / (1f + spread / mean)
        return singles.size * evenness
    }

    /** Rows where two lanes fire at once. */
    private fun jumpScore(window: List<GlyphNote>): Float {
        val byTime = window.groupBy { (it.timeSeconds * 1000f).toInt() }
        return byTime.count { it.value.size >= 2 }.toFloat()
    }

    private fun holdScore(window: List<GlyphNote>): Float =
        window.filter { it.type.isHoldLike }.sumOf { it.holdDurationSeconds.toDouble() }.toFloat()

    /** Runs that genuinely alternate rather than repeating a lane. */
    private fun alternatingScore(window: List<GlyphNote>): Float {
        val singles = window.filter { !it.type.isHoldLike }
        if (singles.size < MINIMUM_NOTES) return 0f
        var alternations = 0
        for ((a, b) in singles.zipWithNext()) {
            if (a.lane != b.lane) alternations += 1
        }
        return alternations.toFloat()
    }

    /**
     * Sparse, on-the-beat passages.
     *
     * The timing drill wants space between the notes: consistency is only
     * measurable when each note is its own event rather than part of a run the
     * hands are carrying through.
     */
    private fun timingScore(window: List<GlyphNote>): Float {
        val quarters = window.count {
            it.division == tf.monochrome.android.glyph.asset.GlyphBeatDivision.QUARTER
        }
        val density = window.size.toFloat()
        if (density <= 0f) return 0f
        return quarters * (quarters / density)
    }

    /** Long enough to be a passage, short enough to repeat without tedium. */
    const val WINDOW_SECONDS = 12f

    /** A moment before and after, so the passage is not entered cold. */
    private const val LEAD_IN_SECONDS = 1.5f

    private const val MINIMUM_NOTES = 6
}
