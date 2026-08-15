// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import tf.monochrome.android.audio.sampler.SampleEdits

/** The parts a source can be pulled apart into. */
enum class Stem(val id: String, val label: String) {
    VOCALS("VOCALS", "Vocals"),
    DRUMS("DRUMS", "Drums"),
    BASS("BASS", "Bass"),
    OTHER("OTHER", "Other");

    companion object {
        fun fromId(id: String?): Stem? = entries.firstOrNull { it.id.equals(id, true) }
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
        requested: Set<Stem> = Stem.entries.toSet(),
        quality: StemQuality = StemQuality.BALANCED,
        onProgress: (StemProgress) -> Unit = {},
    ): Map<Stem, SampleEdits.Buffer>
}
