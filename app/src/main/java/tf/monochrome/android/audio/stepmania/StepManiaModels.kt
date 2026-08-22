// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import tf.monochrome.android.audio.sampler.SampleEdits

/** A StepMania chart tier generated from one shared rhythm analysis. */
enum class StepManiaDifficulty(
    val sscName: String,
    val meter: Int,
) {
    BEGINNER("Beginner", 3),
    EASY("Easy", 5),
    MEDIUM("Medium", 8),
    HARD("Hard", 11),
    CHALLENGE("Challenge", 14),
}

/** Metadata and output choices for one generated simfile. */
data class StepManiaRequest(
    val title: String,
    val artist: String,
    /** Relative file name of the exact MP3 or FLAC that was analysed. */
    val musicFileName: String,
    val credit: String = "Tryptify",
    val difficulties: Set<StepManiaDifficulty> = setOf(
        StepManiaDifficulty.EASY,
        StepManiaDifficulty.MEDIUM,
        StepManiaDifficulty.HARD,
    ),
)

/**
 * Beat-space features shared by every generated difficulty.
 *
 * [gridStrengths] is a sixteenth-note grid beginning at [offsetSeconds].
 * Keeping analysis behind this representation lets a compact ONNX model
 * replace the built-in analyser without touching chart generation.
 */
data class RhythmAnalysis(
    val bpm: Float,
    val offsetSeconds: Float,
    val confidence: Float,
    val durationSeconds: Float,
    val gridStrengths: FloatArray,
) {
    val beatPeriodSeconds: Float get() = 60f / bpm
    val subdivisionSeconds: Float get() = beatPeriodSeconds / 4f

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** One four-lane DDR event. Bits 0..3 are left, down, up, right. */
data class StepEvent(
    val tick: Int,
    val laneMask: Int,
) {
    init {
        require(tick >= 0) { "tick must be non-negative" }
        require(laneMask in 1..0b1111) { "laneMask must contain at least one lane" }
    }

    val laneCount: Int get() = Integer.bitCount(laneMask)
}

data class StepChart(
    val difficulty: StepManiaDifficulty,
    val events: List<StepEvent>,
    /** Sixteenth-note ticks covered by the serialized note data. */
    val lengthTicks: Int,
)

data class GeneratedSimfile(
    val ssc: String,
    val analysis: RhythmAnalysis,
    val charts: List<StepChart>,
)

/**
 * Offline rhythm analysis contract.
 *
 * Implementations may allocate and perform file-sized work, but callers must
 * keep them on a background dispatcher and away from realtime audio callbacks.
 */
fun interface StepManiaRhythmAnalyzer {
    fun analyze(
        mix: SampleEdits.Buffer,
        drums: SampleEdits.Buffer?,
    ): RhythmAnalysis
}
