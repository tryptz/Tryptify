package tf.monochrome.android.visualizer

import tf.monochrome.android.domain.model.VisualizerPreset

/**
 * Where Previous goes.
 *
 * A stack of presets we have moved away from, rather than an index into the
 * preset list: Next is the native playlist's business and it shuffles, so
 * "the one before this" is not the entry above it in the list — it is
 * whatever you were actually watching.
 *
 * Pulled out of [ProjectMEngineRepository] so the walk-back rules can be
 * tested without a GL context, for the same reason [shouldDropFrame] is a
 * free function: the failure here is quiet. A stack that records its own
 * walk-back makes Previous bounce between two presets forever, and one that
 * never evicts holds every preset of a long shuffling session alive.
 *
 * Thread-safe: recorded from the main thread (a Select) and from the GL
 * thread (a Next landing), so every operation takes the lock.
 */
internal class PresetHistory(private val maxDepth: Int = DEFAULT_MAX_DEPTH) {

    private val lock = Any()
    private val entries = ArrayDeque<VisualizerPreset>()

    /**
     * Records the preset being left.
     *
     * Ignores a move that does not change anything — re-selecting the preset
     * already showing is not a step you can go back from, and recording it
     * would make Previous appear to do nothing.
     */
    fun record(outgoing: VisualizerPreset?, incoming: VisualizerPreset?) {
        if (outgoing == null || outgoing.id == incoming?.id) return
        synchronized(lock) {
            entries.addLast(outgoing)
            while (entries.size > maxDepth) entries.removeFirst()
        }
    }

    /** The preset to go back to, removed from the stack, or null if there is none. */
    fun back(): VisualizerPreset? = synchronized(lock) { entries.removeLastOrNull() }

    /** Whether [back] would return anything. */
    fun canGoBack(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    /** Visible for tests. */
    fun depth(): Int = synchronized(lock) { entries.size }

    /** Drops everything — the preset library changed under us. */
    fun clear() {
        synchronized(lock) { entries.clear() }
    }

    companion object {
        /**
         * How far Previous can walk back. Deep enough to undo a run of taps,
         * shallow enough that a long session does not retain presets nobody
         * is going back to.
         */
        const val DEFAULT_MAX_DEPTH = 32
    }
}
