// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.engine

/**
 * A run scored the way StepMania 5 scores it: dance points, the life bar, the
 * grade tiers of its default theme, and the full-combo banners.
 *
 * Fed the engine's judgement events, in order, and nothing else. The weights
 * are StepMania's own (Themes/_fallback/metrics.ini, [ScoreKeeperNormal] and
 * [LifeMeterBar]); the tiers and their thresholds are the default theme's
 * ([PlayerStageStats], copied there from Simply Love). So a run reads here as
 * it would on a cabinet: the same percent, the same grade, the same point at
 * which the life bar empties.
 *
 * The engine's own points, multiplied by the combo as osu!'s are, are kept
 * alongside. This percent is what the menus, the results and the high score
 * tables rank by; the points break their ties and rank a room's players.
 */
class StepManiaScoreKeeper(
    /** Judgements the chart can award: taps and hold heads, and one per hold tail. */
    private var possibleJudgements: Int,
    private val failType: GlyphFailType = GlyphFailType.IMMEDIATE,
) {
    var dancePoints: Int = 0
        private set

    /** 0 to 1. Starts half full, as StepMania's does. */
    var life: Float = INITIAL_LIFE
        private set

    /**
     * Failed: the bar emptied under Immediate or Immediate Continue. Under End
     * of Song the verdict waits for the end ([grade] asks then), and the bar
     * may refill in the meantime.
     */
    var failed: Boolean = false
        private set

    /**
     * StepMania's RegenComboAfterMiss: after a loss, the next steps that would
     * have refilled the bar refill nothing, until this many have passed.
     */
    private var comboToRegainLife = 0

    private val tapCounts = IntArray(GlyphJudgement.entries.size)
    var holdsHeld: Int = 0
        private set
    var holdsLetGo: Int = 0
        private set
    var minesHit: Int = 0
        private set

    val possibleDancePoints: Int get() = possibleJudgements * DP_W1

    /**
     * The percent StepMania shows, 0 to 1: dance points over the chart's
     * possible, never below zero (a run of mines can push the points under).
     */
    val percent: Float
        get() = if (possibleDancePoints <= 0) 0f
        else (dancePoints.coerceAtLeast(0).toFloat() / possibleDancePoints).coerceIn(0f, 1f)

    /** [percent] as StepMania prints it: truncated, not rounded, to 0.01 %. */
    val percentLabel: String get() = formatPercent(percent)

    fun tapCount(judgement: GlyphJudgement): Int = tapCounts[judgement.ordinal]

    fun record(event: GlyphJudgementEvent) {
        when {
            event.isMine -> {
                minesHit += 1
                dancePoints += DP_HIT_MINE
                changeLife(LIFE_HIT_MINE)
            }
            event.isTail -> if (event.judgement == GlyphJudgement.MISS) {
                holdsLetGo += 1
                changeLife(LIFE_LET_GO)
            } else {
                holdsHeld += 1
                dancePoints += DP_HELD
                changeLife(LIFE_HELD)
            }
            else -> {
                tapCounts[event.judgement.ordinal] += 1
                dancePoints += dancePointsFor(event.judgement)
                changeLife(lifeFor(event.judgement))
            }
        }
    }

    /** A practice loop narrows what a run can earn. */
    fun reset(possibleJudgements: Int = this.possibleJudgements) {
        this.possibleJudgements = possibleJudgements
        dancePoints = 0
        life = INITIAL_LIFE
        failed = false
        comboToRegainLife = 0
        tapCounts.fill(0)
        holdsHeld = 0
        holdsLetGo = 0
        minesHit = 0
    }

    /**
     * The grade, StepMania's way: failing is F whatever the percent; all
     * Flawless is the starred AAA; every step Perfect or better with nothing
     * dropped is AAA; and below that the percent picks the tier.
     *
     * Asked at the end of the song, which is when StepMania marks a fail for
     * every type but Off: an empty bar then is a fail, whenever it emptied.
     */
    val grade: GlyphStepGrade
        get() = GlyphStepGrade.of(
            percent = percent,
            failed = failed || (failType != GlyphFailType.OFF && life <= 0f),
            fullCombo = fullCombo,
        )

    /** The best full combo the run kept, or null when the combo broke. */
    val fullCombo: GlyphFullCombo?
        get() {
            val judged = tapCounts.sum()
            if (judged == 0 || holdsLetGo > 0 || minesHit > 0) return null
            val worst = GlyphJudgement.entries.lastOrNull { tapCounts[it.ordinal] > 0 } ?: return null
            return when (worst) {
                GlyphJudgement.MARVELOUS -> GlyphFullCombo.FLAWLESS
                GlyphJudgement.PERFECT -> GlyphFullCombo.PERFECT
                GlyphJudgement.GREAT -> GlyphFullCombo.GREAT
                else -> null
            }
        }

    /** LifeMeterBar::ChangeLife, without the drain types the app does not offer. */
    private fun changeLife(delta: Float) {
        var change = delta
        // HarshHotLifePenalty: a full bar loses at least a tenth on any loss,
        // so it takes a while to get back to full.
        if (life >= HOT_LIFE && change < 0f) change = minOf(change, HOT_PENALTY)
        if (change >= 0f) {
            comboToRegainLife = (comboToRegainLife - 1).coerceAtLeast(0)
            if (comboToRegainLife > 0) change = 0f
        } else {
            comboToRegainLife = maxOf(
                comboToRegainLife,
                minOf(MAX_REGEN_COMBO_AFTER_MISS, comboToRegainLife + REGEN_COMBO_AFTER_MISS),
            )
        }
        // Once failed, the bar is left where it ended.
        if (failed) return
        life = (life + change).coerceIn(0f, 1f)
        if (life <= 0f && (failType == GlyphFailType.IMMEDIATE || failType == GlyphFailType.IMMEDIATE_CONTINUE)) {
            failed = true
        }
    }

    /** The run should stop now: Immediate, and the bar is empty. */
    val stopsRun: Boolean get() = failed && failType == GlyphFailType.IMMEDIATE

    companion object {
        // [ScoreKeeperNormal] PercentScoreWeight*
        const val DP_W1 = 3
        const val DP_W2 = 2
        const val DP_W3 = 1
        const val DP_HELD = 3
        const val DP_HIT_MINE = -2

        // [LifeMeterBar]
        const val INITIAL_LIFE = 0.5f
        const val LIFE_W1 = 0.008f
        const val LIFE_W2 = 0.008f
        const val LIFE_W3 = 0.004f
        const val LIFE_W5 = -0.040f
        const val LIFE_MISS = -0.080f
        const val LIFE_HIT_MINE = -0.160f
        const val LIFE_HELD = 0.008f
        const val LIFE_LET_GO = -0.080f

        /** Below this the bar is drawn in danger, as StepMania's is. */
        const val DANGER_LIFE = 0.2f
        const val HOT_LIFE = 1.0f
        const val HOT_PENALTY = -0.10f
        const val REGEN_COMBO_AFTER_MISS = 5
        const val MAX_REGEN_COMBO_AFTER_MISS = 5

        fun dancePointsFor(judgement: GlyphJudgement): Int = when (judgement) {
            GlyphJudgement.MARVELOUS -> DP_W1
            GlyphJudgement.PERFECT -> DP_W2
            GlyphJudgement.GREAT -> DP_W3
            else -> 0
        }

        fun lifeFor(judgement: GlyphJudgement): Float = when (judgement) {
            GlyphJudgement.MARVELOUS -> LIFE_W1
            GlyphJudgement.PERFECT -> LIFE_W2
            GlyphJudgement.GREAT -> LIFE_W3
            GlyphJudgement.GOOD -> 0f
            GlyphJudgement.BOO -> LIFE_W5
            GlyphJudgement.MISS -> LIFE_MISS
        }

        /**
         * "98.76%": truncated to hundredths, as StepMania's percent display
         * does. The nudge is float noise, not rounding: 0.965f is stored as
         * 0.96499997, and must still read 96.50%.
         */
        fun formatPercent(percent: Float): String {
            val hundredths = kotlin.math.floor(percent.toDouble() * 10_000.0 + 1e-3).toLong()
            // Built by hand, not through String.format: the HUD makes one on
            // each step of the percent's roll, and a Formatter (with the
            // locale it looks up) was most of the garbage that made. The text
            // is what "%d.%02d%%" gave in the app's languages, signs included
            // (a negative remainder is already two characters), and the same
            // ASCII digits in any locale, as StepMania's own display has.
            val whole = hundredths / 100
            val rest = hundredths % 100
            return buildString(8) {
                append(whole)
                append('.')
                if (rest in 0..9) append('0')
                append(rest)
                append('%')
            }
        }
    }
}

/** What an empty life bar does: StepMania's "Fail" option, its four types. */
enum class GlyphFailType(val id: String, val label: String) {
    /** The run stops the moment the bar empties. */
    IMMEDIATE("immediate", "Immediate"),
    /** The bar emptying is a fail, but the song plays on to the end. */
    IMMEDIATE_CONTINUE("continue", "Imm. Continue"),
    /** Only an empty bar at the end of the song is a fail; it may refill. */
    END_OF_SONG("end", "End of Song"),
    OFF("off", "Off"),
    ;

    companion object {
        fun fromId(id: String?): GlyphFailType = entries.firstOrNull { it.id == id } ?: IMMEDIATE
    }
}

/** The FULL COMBO banners: every step at least this good, nothing dropped. */
enum class GlyphFullCombo(val asset: String, val label: String) {
    FLAWLESS("fullcombo_w1.png", "Full Flawless Combo"),
    PERFECT("fullcombo_w2.png", "Full Perfect Combo"),
    GREAT("fullcombo_w3.png", "Full Combo"),
}

/**
 * StepMania 5 default theme's seventeen grade tiers and F, with the
 * thresholds from its metrics.ini. The first two are not percents: Tier01 is
 * every step Flawless, Tier02 every step Perfect or better
 * (GradeTier02IsAllW2s).
 */
enum class GlyphStepGrade(val label: String, val asset: String, val minimumPercent: Float) {
    TIER01("AAAA", "grade_tier01.png", 1.0f),
    TIER02("AAA", "grade_tier02.png", 1.0f),
    TIER03("AA+", "grade_tier03.png", 0.98f),
    TIER04("AA", "grade_tier04.png", 0.96f),
    TIER05("AA-", "grade_tier05.png", 0.94f),
    TIER06("A+", "grade_tier06.png", 0.92f),
    TIER07("A", "grade_tier07.png", 0.89f),
    TIER08("A-", "grade_tier08.png", 0.86f),
    TIER09("B+", "grade_tier09.png", 0.83f),
    TIER10("B", "grade_tier10.png", 0.80f),
    TIER11("B-", "grade_tier11.png", 0.76f),
    TIER12("C+", "grade_tier12.png", 0.72f),
    TIER13("C", "grade_tier13.png", 0.68f),
    TIER14("C-", "grade_tier14.png", 0.64f),
    TIER15("D+", "grade_tier15.png", 0.60f),
    TIER16("D", "grade_tier16.png", 0.55f),
    TIER17("D-", "grade_tier17.png", Float.NEGATIVE_INFINITY),
    FAILED("F", "grade_failed.png", Float.NEGATIVE_INFINITY),
    ;

    companion object {
        fun of(percent: Float, failed: Boolean, fullCombo: GlyphFullCombo?): GlyphStepGrade {
            if (failed) return FAILED
            if (fullCombo == GlyphFullCombo.FLAWLESS && percent >= 1f) return TIER01
            if (fullCombo == GlyphFullCombo.FLAWLESS || fullCombo == GlyphFullCombo.PERFECT) return TIER02
            return entries.first {
                it != TIER01 && it != TIER02 && it != FAILED && percent >= it.minimumPercent
            }
        }

        fun fromName(name: String?): GlyphStepGrade? = entries.firstOrNull { it.name == name }
    }
}
