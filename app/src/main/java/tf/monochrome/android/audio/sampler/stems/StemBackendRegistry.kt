// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

import tf.monochrome.android.audio.sampler.SampleEdits
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks which backend runs, and tells the UI which one it picked.
 *
 * The order is NPU, then CPU, then the built-in DSP. What matters is the last
 * one: it needs no model, no download and no network, so stem separation is
 * never "unavailable" — it is only ever better or worse. A user who declines a
 * 300 MB download still gets a working feature.
 *
 * The brief is explicit that a fallback must not be silent, and [active] is how
 * that promise is kept: it returns the backend actually chosen, and the panel
 * shows its name. Falling back from NPU to CPU turns a two-minute job into a
 * much longer one, and someone watching a progress bar is entitled to know why.
 */
@Singleton
class StemBackendRegistry @Inject constructor(
    private val backends: Set<@JvmSuppressWildcards StemSeparationBackend>,
) {

    /**
     * Preference order. Not a quality ranking — the DSP backend is last because
     * it is the floor, and QNN is first because when it is available it is
     * dramatically faster, not because it sounds better.
     */
    private val order = listOf(BackendKind.QNN, BackendKind.CPU, BackendKind.DSP)

    fun all(): List<StemSeparationBackend> =
        backends.sortedBy { order.indexOf(it.kind).takeIf { i -> i >= 0 } ?: order.size }

    /** The best backend that reports itself available right now. */
    fun active(): StemSeparationBackend? = all().firstOrNull { it.status().available }

    fun of(kind: BackendKind): StemSeparationBackend? = backends.firstOrNull { it.kind == kind }

    /**
     * Every backend's status, for the settings screen.
     *
     * Includes the unavailable ones deliberately. "Snapdragon NPU — model not
     * installed" tells a user something they can act on; omitting the row tells
     * them the feature does not exist on their phone.
     */
    fun statuses(): List<BackendStatus> = all().map { it.status() }

    /**
     * True when the chosen backend is not the best one that exists.
     *
     * Drives the "running on CPU" notice, which is the difference between a
     * considered trade-off and a mystery.
     */
    fun isDegraded(): Boolean {
        val running = active() ?: return true
        return all().any { it.kind != running.kind && order.indexOf(it.kind) < order.indexOf(running.kind) }
    }
}

/**
 * Adapts the built-in DSP separator to the backend interface.
 *
 * Thin on purpose. [DspStemSeparator] predates this abstraction and is a
 * perfectly good implementation of it; wrapping rather than rewriting keeps the
 * separation maths in one place and means the fallback path is the code that
 * has already been shipping.
 */
@Singleton
class DspStemBackend @Inject constructor(
    private val separator: DspStemSeparator,
) : StemSeparationBackend {

    override val kind: BackendKind = BackendKind.DSP

    // Always available — no model, no download, no network. That is the whole
    // reason this backend exists.
    override fun status(): BackendStatus = BackendStatus(
        kind = BackendKind.DSP,
        available = true,
        modelName = "Built-in DSP",
    )

    override fun availableStems(input: SampleEdits.Buffer): Set<Stem> =
        separator.availableStems(input)

    override suspend fun initialize(): Result<Unit> = Result.success(Unit)

    override suspend fun separate(
        input: SampleEdits.Buffer,
        requested: Set<Stem>,
        options: SeparationOptions,
        onProgress: (StemProgress) -> Unit,
    ): Map<Stem, SampleEdits.Buffer> =
        separator.separate(input, requested, options.quality, onProgress)

    override fun release() = Unit
}
