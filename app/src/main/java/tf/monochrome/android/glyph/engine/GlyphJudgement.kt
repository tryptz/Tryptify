// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

import kotlin.math.abs
import tf.monochrome.android.glyph.asset.GlyphGradeArt
import tf.monochrome.android.glyph.asset.GlyphJudgementArt

/**
 * How well a note was hit.
 *
 * Ordered best to worst so comparisons read the way they sound: `judgement <=
 * GREAT` is "at least a Great". [continuesCombo] is a property of the judgement
 * rather than a rule applied at the call site, because combo behaviour is the
 * thing players notice first and it must not be able to differ between the
 * live counter and the results screen.
 */
enum class GlyphJudgement(
    val label: String,
    val art: GlyphJudgementArt,
    /** Share of a note's points this judgement earns. */
    val weight: Float,
    val continuesCombo: Boolean,
) {
    MARVELOUS("Marvelous", GlyphJudgementArt.MARVELOUS, 1.00f, true),
    PERFECT("Perfect", GlyphJudgementArt.PERFECT, 0.95f, true),
    GREAT("Great", GlyphJudgementArt.GREAT, 0.70f, true),
    GOOD("Good", GlyphJudgementArt.GOOD, 0.40f, true),
    BOO("Boo", GlyphJudgementArt.BOO, 0.10f, false),
    MISS("Miss", GlyphJudgementArt.MISS, 0.00f, false),
    ;

    val isHit: Boolean get() = this != MISS
}

/**
 * The timing windows, in seconds.
 *
 * Adjustable because Training Ground offers it: a player working on a stream
 * can tighten the windows to feel the drift, or widen them to keep a combo
 * alive while they fix something else. [scale] multiplies every window at once
 * so the shape of the windows stays the same and only their size changes —
 * scaling them independently would let Great become tighter than Perfect.
 */
data class GlyphTimingWindows(
    val marvelousSeconds: Float = 0.0225f,
    val perfectSeconds: Float = 0.045f,
    val greatSeconds: Float = 0.090f,
    val goodSeconds: Float = 0.135f,
    val booSeconds: Float = 0.180f,
) {
    init {
        require(
            marvelousSeconds > 0f &&
                marvelousSeconds <= perfectSeconds &&
                perfectSeconds <= greatSeconds &&
                greatSeconds <= goodSeconds &&
                goodSeconds <= booSeconds,
        ) { "timing windows must widen monotonically" }
    }

    /** Past this, a note is a miss and can no longer be hit. */
    val missSeconds: Float get() = booSeconds

    /**
     * The judgement for being [offsetSeconds] away from a note, or null when
     * the note is out of reach entirely.
     *
     * Sign is ignored here: early and late are scored identically and the
     * direction is recorded separately, because a player who is consistently
     * 30 ms early needs to know that, not to be punished twice for it.
     */
    fun judge(offsetSeconds: Float): GlyphJudgement? {
        val distance = abs(offsetSeconds)
        return when {
            distance <= marvelousSeconds -> GlyphJudgement.MARVELOUS
            distance <= perfectSeconds -> GlyphJudgement.PERFECT
            distance <= greatSeconds -> GlyphJudgement.GREAT
            distance <= goodSeconds -> GlyphJudgement.GOOD
            distance <= booSeconds -> GlyphJudgement.BOO
            else -> null
        }
    }

    fun scaled(scale: Float): GlyphTimingWindows {
        val factor = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        return GlyphTimingWindows(
            marvelousSeconds = marvelousSeconds * factor,
            perfectSeconds = perfectSeconds * factor,
            greatSeconds = greatSeconds * factor,
            goodSeconds = goodSeconds * factor,
            booSeconds = booSeconds * factor,
        )
    }

    companion object {
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.0f

        /** The default set, at the sizes the mode is balanced around. */
        val STANDARD = GlyphTimingWindows()
    }
}

/** The grade a run earns, from its accuracy. */
enum class GlyphGrade(val label: String, val art: GlyphGradeArt, val minimumAccuracy: Float) {
    SSS("SSS", GlyphGradeArt.SSS, 0.99f),
    SS("SS", GlyphGradeArt.SS, 0.95f),
    S("S", GlyphGradeArt.S, 0.90f),
    A("A", GlyphGradeArt.A, 0.80f),
    B("B", GlyphGradeArt.B, 0.70f),
    C("C", GlyphGradeArt.C, 0.60f),
    D("D", GlyphGradeArt.D, 0.50f),
    FAILED("Failed", GlyphGradeArt.FAILED, 0f),
    ;

    companion object {
        fun forAccuracy(accuracy: Float): GlyphGrade =
            entries.firstOrNull { accuracy >= it.minimumAccuracy } ?: FAILED
    }
}
