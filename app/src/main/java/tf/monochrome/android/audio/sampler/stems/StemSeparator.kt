// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import tf.monochrome.android.audio.sampler.SampleEdits

/**
 * The parts a source can be pulled apart into.
 *
 * [GUITAR] and [PIANO] only exist for six-stem models. No backend is obliged to
 * produce them — which is the whole reason [StemSeparator.availableStems] is a
 * per-backend question and not a constant. Asking a four-stem model for a piano
 * stem gets you four stems, not an error.
 *
 * Note the ordering: the six-stem split does not add two new sources so much as
 * carve them out of [OTHER], so a six-stem `other` is what is left after guitar
 * and piano have been taken, not the same signal a four-stem model calls
 * `other`.
 */
enum class Stem(val id: String, val label: String) {
    VOCALS("VOCALS", "Vocals"),
    DRUMS("DRUMS", "Drums"),
    BASS("BASS", "Bass"),
    GUITAR("GUITAR", "Guitar"),
    PIANO("PIANO", "Piano"),
    OTHER("OTHER", "Other");

    companion object {
        fun fromId(id: String?): Stem? = entries.firstOrNull { it.id.equals(id, true) }

        /** The classic split every separator can do. */
        val FOUR: Set<Stem> = setOf(VOCALS, DRUMS, BASS, OTHER)

        /** The six-stem split, for models trained for it. */
        val SIX: Set<Stem> = setOf(VOCALS, DRUMS, BASS, GUITAR, PIANO, OTHER)

        /** Stems only a six-stem model produces. */
        val EXTENDED: Set<Stem> = setOf(GUITAR, PIANO)
    }
}

/** How much time the caller is willing to spend. */
enum class StemQuality(val label: String) {
    FAST("Fast"),
    BALANCED("Balanced"),
    HIGH("High quality"),
}

/** Progress for the UI: a fraction and what is happening. */
data class StemProgress(val fraction: Float, val stage: String)

/**
 * Pulls a recording apart into stems.
 *
 * An interface rather than a class because the backend is expected to change.
 * The one here is DSP; a model-based backend — ONNX, TFLite, NNAPI, or a
 * remote service — implements the same three-line contract and the UI does not
 * move. That is the reason [separate] takes a quality hint and a progress
 * callback it may ignore: those are the two things every backend needs to
 * expose and the only two the UI can rely on.
 *
 * Implementations must be cancellable through ordinary coroutine cancellation
 * and must never touch the audio thread.
 */
interface StemSeparator {

    /** Which stems this backend can actually produce for [input]. */
    fun availableStems(input: SampleEdits.Buffer): Set<Stem>

    /** A short, honest description of what the backend does, for the UI. */
    val description: String

    /**
     * Separates [input] into [requested].
     *
     * Returns only the stems it could produce. Suspends, and is expected to be
     * called from a background dispatcher; [onProgress] is invoked on the
     * calling context.
     */
    suspend fun separate(
        input: SampleEdits.Buffer,
        requested: Set<Stem> = Stem.SIX,
        quality: StemQuality = StemQuality.BALANCED,
        onProgress: (StemProgress) -> Unit = {},
    ): Map<Stem, SampleEdits.Buffer>
}
