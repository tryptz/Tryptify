// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import tf.monochrome.android.audio.sampler.SampleEdits

/**
 * Where separation actually runs.
 *
 * Named rather than described as "accelerated", because the brief is explicit
 * that the user must be told which one is in use — a silent fall back from NPU
 * to CPU turns a two-minute job into a twenty-minute one, and someone watching
 * a progress bar deserves to know why.
 */
enum class BackendKind(val label: String, val detail: String) {
    /** Qualcomm Hexagon NPU via QNN. Experimental — see MODEL_CARD.md. */
    QNN("Snapdragon NPU", "Hardware accelerated"),

    /** ONNX Runtime on the CPU. The tier the product is actually built on. */
    CPU("CPU", "Slower, works everywhere"),

    /** The built-in DSP separator. No model, no download, always available. */
    DSP("Built-in", "No model needed"),
    ;

    val isAccelerated: Boolean get() = this == QNN
}

/**
 * What a backend can tell the UI about itself.
 *
 * [modelVersion] and [modelName] are null for [BackendKind.DSP], which is the
 * honest answer rather than inventing a version for a fixed algorithm.
 */
data class BackendStatus(
    val kind: BackendKind,
    val available: Boolean,
    val modelName: String? = null,
    val modelVersion: String? = null,
    /** Why it is unavailable, in words a user can act on. Null when available. */
    val unavailableReason: String? = null,
) {
    val displayName: String
        get() = if (modelVersion != null) "${kind.label} · $modelVersion" else kind.label
}

/**
 * Knobs a caller can turn that are not "which stems".
 *
 * [overlap] and [shifts] both trade time for quality, and both are exposed
 * because the right answer differs between a phone on battery and a phone on a
 * desk. [preserveRelativeLevels] defaults on: normalising each stem to its own
 * peak makes each one sound better alone and makes them wrong together, and
 * together is how a sampler uses them.
 */
data class SeparationOptions(
    val quality: StemQuality = StemQuality.BALANCED,
    val overlap: Float = ChunkedSeparation.DEFAULT_OVERLAP,
    val shifts: Int = 1,
    val preserveRelativeLevels: Boolean = true,
)

/**
 * One way of separating audio.
 *
 * Deliberately narrow. The UI depends on this and never on QNN, ONNX, or the
 * DSP — which is what makes swapping the backend a change in
 * [StemBackendRegistry] and nowhere else.
 *
 * Implementations must:
 *
 * * take stereo and return stereo, never collapsing to mono on the way through
 *   (`SpatialMetricsTest` exists to catch a backend that does),
 * * be cancellable by ordinary coroutine cancellation,
 * * never run on the audio thread,
 * * and report their own availability honestly rather than failing at
 *   [separate] time.
 */
interface StemSeparationBackend {

    val kind: BackendKind

    /** Cheap enough to call on every screen open. Must not download anything. */
    fun status(): BackendStatus

    /** Which stems this backend can produce for this input. */
    fun availableStems(input: SampleEdits.Buffer): Set<Stem>

    /** Loads the model into memory. Idempotent; safe to call when already loaded. */
    suspend fun initialize(): Result<Unit>

    suspend fun separate(
        input: SampleEdits.Buffer,
        requested: Set<Stem>,
        options: SeparationOptions,
        onProgress: (StemProgress) -> Unit,
    ): Map<Stem, SampleEdits.Buffer>

    /** Frees model memory. The backend must survive being used again after. */
    fun release()
}
