// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import tf.monochrome.android.audio.sampler.SampleEdits
import tf.monochrome.android.audio.sampler.stems.Stem

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
 *
 * The tempo and [gridStrengths] always come from the drums. [stems] is what the
 * rest of the song does on that same grid, when the separator gave back more
 * than drums; null charts from the drums alone, exactly as before there was
 * anything else to hear.
 */
data class RhythmAnalysis(
    val bpm: Float,
    val offsetSeconds: Float,
    val confidence: Float,
    val durationSeconds: Float,
    val gridStrengths: FloatArray,
    val stems: StemFeatures? = null,
) {
    val beatPeriodSeconds: Float get() = 60f / bpm
    val subdivisionSeconds: Float get() = beatPeriodSeconds / 4f

    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * What each separated stem besides the drums is listened for.
 *
 * The drums still set the tempo and the beat: they are the steadiest clock in
 * a song. These layers add what the drums cannot say — where the tune moves,
 * where a note is held, where the bass line pushes off the beat.
 */
enum class StemLayer(
    /** The stems summed into this layer. A separator without one simply leaves it out. */
    val stems: Set<Stem>,
    /** How the simfile's description names it. */
    val noun: String,
) {
    BASS(setOf(Stem.BASS), "bass"),
    VOCALS(setOf(Stem.VOCALS), "vocal"),

    /**
     * Everything tonal that is not the voice or the bass: what a four-stem
     * model calls "other", and a six-stem model splits into other, piano and
     * guitar. Summed back together, both describe the same instruments.
     */
    MELODY(setOf(Stem.OTHER, Stem.PIANO, Stem.GUITAR), "melody"),
    ;

    companion object {
        /** Every stem chart generation asks the separator for: the drums and each layer's. */
        val REQUESTED: Set<Stem> = setOf(Stem.DRUMS) + entries.flatMap { it.stems }
    }
}

/**
 * The non-drum stems, on the same sixteenth-note grid as
 * [RhythmAnalysis.gridStrengths]. Every array is that long.
 */
class StemFeatures(
    /** 0..1: how sharply a sung or played note starts on each sixteenth. */
    val melodyOnsets: FloatArray,
    /** 0..1: how sharply a bass note starts on each sixteenth. */
    val bassOnsets: FloatArray,
    /** How many sixteenths a sung note starting on each sixteenth is held for; 0 for none. */
    val sustainTicks: IntArray,
    /**
     * The tune's rough pitch at each sixteenth, in octaves, 0 where nothing
     * was heard. Only the direction between two notes means anything: it is
     * read off how often the waveform crosses zero, which follows pitch up
     * and down without naming it.
     */
    val contour: FloatArray,
    /** The layers that were actually heard, for the simfile's description. */
    val layers: Set<StemLayer>,
) {
    init {
        val size = melodyOnsets.size
        require(bassOnsets.size == size && sustainTicks.size == size && contour.size == size) {
            "every stem feature must cover the same grid"
        }
    }
}

/**
 * One four-lane DDR event. Bits 0..3 are left, down, up, right.
 *
 * A [holdTicks] above zero makes it a freeze arrow: held from [tick] for that
 * many sixteenths. Only a single arrow can be one.
 */
data class StepEvent(
    val tick: Int,
    val laneMask: Int,
    val holdTicks: Int = 0,
) {
    init {
        require(tick >= 0) { "tick must be non-negative" }
        require(laneMask in 1..0b1111) { "laneMask must contain at least one lane" }
        require(holdTicks >= 0) { "a hold cannot be negative" }
        require(holdTicks == 0 || Integer.bitCount(laneMask) == 1) { "only a single arrow can be held" }
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

    /**
     * The same from every stem the separator returned. An analyser that only
     * hears drums need not override it.
     */
    fun analyze(
        mix: SampleEdits.Buffer,
        stems: Map<Stem, SampleEdits.Buffer>,
    ): RhythmAnalysis = analyze(mix, stems[Stem.DRUMS])
}
