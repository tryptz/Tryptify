// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import tf.monochrome.android.glyph.data.GlyphGhost

/**
 * Your best run, played back beside you as a score: its ghost's judgements
 * fed, at the song times they landed, through the same [GlyphScore] a run is
 * scored by, so the two cards on the board count alike.
 *
 * A ghost holds every judgement but not a mine hit or a dropped hold, which
 * the scorer also counts, so the replayed total can drift a little from the
 * run's own; once the ghost has played out, [score] is the run's recorded
 * [finalScore], and the board ends on the number its results showed.
 */
class GlyphGhostRace(
    private val ghost: GlyphGhost,
    totalJudgements: Int,
    private val finalScore: Int,
) {
    private val scorer = GlyphScore(totalJudgements)
    private var next = 0

    /** Plays the ghost up to [songSeconds]; true when its score moved. */
    fun advanceTo(songSeconds: Float): Boolean {
        val ms = songSeconds * 1000f
        var moved = false
        while (next < ghost.size && ghost.timesMs[next] <= ms) {
            scorer.record(ghost.judgementAt(next), ghost.offsetsMs[next] / 1000f)
            next++
            moved = true
        }
        return moved
    }

    val score: Int get() = if (next >= ghost.size) finalScore else scorer.score

    val combo: Int get() = scorer.combo
}
