// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import kotlinx.serialization.Serializable

/**
 * The course type: which buttons a chart is written for.
 *
 * [DANCE] is the four panels the mode began with, StepMania's `dance-single`.
 * [POPN] is pop'n music's nine buttons, StepMania's `pnm-nine`: two rows of
 * coloured buttons, four above and five below, that the coloured "pop-kun"
 * notes fall onto. Both come from the same stem analysis; only where the notes
 * land, how many columns a row has and how the field is drawn differ.
 *
 * One song can have a chart of each. They are kept apart, one simfile per
 * course, so generating one never overwrites the other and a run on one
 * never ranks against the other's.
 */
@Serializable
enum class CourseType(
    /** StepMania's `#STEPSTYPE`, as the generator writes it and the parser reads it. */
    val stepsType: String,
    /** Other spellings of the same type that a pack may carry. */
    val stepsTypeAliases: Set<String>,
    val laneCount: Int,
    val label: String,
    /** What the buttons are, for a caption. */
    val buttonsLabel: String,
    /** How this course's simfile is named next to the dance one, "" for the original. */
    val fileSuffix: String,
) {
    DANCE("dance-single", emptySet(), 4, "Dance", "4 panels", ""),
    POPN("pnm-nine", setOf("popn-nine"), 9, "pop'n", "9 buttons", ".popn"),
    ;

    /** Whether a `#STEPSTYPE` names this course; an empty one is the default, dance. */
    fun matchesStepsType(value: String?): Boolean {
        val type = value?.trim()?.lowercase().orEmpty()
        if (type.isEmpty()) return this == DANCE
        return type == stepsType || type in stepsTypeAliases
    }

    /**
     * The chart's `#METER`. Dance keeps StepMania's feet. pop'n rates its
     * charts 1 to 50, so its tiers sit where pop'n's own do: EASY and the old
     * 5-Buttons in the low teens, NORMAL in the twenties, HYPER in the
     * thirties, EX at forty.
     */
    fun meter(difficulty: StepManiaDifficulty): Int = when (this) {
        DANCE -> difficulty.meter
        POPN -> when (difficulty) {
            StepManiaDifficulty.BEGINNER -> 8
            StepManiaDifficulty.EASY -> 14
            StepManiaDifficulty.MEDIUM -> 22
            StepManiaDifficulty.HARD -> 32
            StepManiaDifficulty.CHALLENGE -> 40
        }
    }

    /**
     * What the tier is called on screen. The simfile keeps StepMania's
     * names, which is how the parser finds a chart; pop'n's players know
     * the same tiers as EASY, NORMAL, HYPER and EX, so the chips say so.
     */
    fun difficultyLabel(difficulty: StepManiaDifficulty): String = when (this) {
        DANCE -> difficulty.sscName
        POPN -> when (difficulty) {
            StepManiaDifficulty.BEGINNER -> "Easy"
            StepManiaDifficulty.EASY -> "5-Buttons"
            StepManiaDifficulty.MEDIUM -> "Normal"
            StepManiaDifficulty.HARD -> "Hyper"
            StepManiaDifficulty.CHALLENGE -> "EX"
        }
    }

    companion object {
        /** The course a `#STEPSTYPE` names, or null for one the mode does not play. */
        fun forStepsType(value: String?): CourseType? = entries.firstOrNull { it.matchesStepsType(value) }

        /** Read back from a preference; anything unknown is the default, dance. */
        fun fromId(id: String?): CourseType = entries.firstOrNull { it.name == id } ?: DANCE

        /** The most columns any course has, for arrays sized once. */
        val MAX_LANES: Int = entries.maxOf { it.laneCount }
    }
}
