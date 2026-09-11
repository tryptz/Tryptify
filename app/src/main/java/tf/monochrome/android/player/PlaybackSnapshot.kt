package tf.monochrome.android.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * What the player writes down so it can come back to where it was.
 *
 * Pure Kotlin on purpose — no Android, no Room, no coroutines. The unit test
 * source set carries JUnit and nothing else, so every decision worth pinning
 * (queue ordering, the size cap, when a position is worth writing) lives in a
 * function that can be called without a device.
 */

/** Bumped when the shape below changes incompatibly; older blobs are discarded. */
const val PLAYBACK_SNAPSHOT_VERSION = 1

/**
 * One queue position.
 *
 * [unified] is the load-bearing half, not an optimization. `UnifiedTrackRegistry`
 * is an in-memory map and is empty after process death, and
 * `PlayerViewModel.resolveAndPlay` resolves through it first, then a synthesized
 * Qobuz track, then the legacy TIDAL path. A queue persisted as bare [Track]s
 * would send every restored local file down that last branch and play a
 * different song under the right title.
 *
 * Both halves are stored rather than deriving one from the other:
 * `UnifiedTrack.toLegacyTrack()` is not a faithful inverse (it rewrites the
 * album id to a hash), so a round trip through it would quietly corrupt the
 * entries that came in as legacy tracks.
 */
@Serializable
data class PersistedQueueEntry(
    val track: Track,
    val unified: UnifiedTrack? = null,
)

@Serializable
data class PersistedQueue(
    /** No default: it must survive `encodeDefaults = false` to be worth checking. */
    val version: Int,
    val entries: List<PersistedQueueEntry> = emptyList(),
    /**
     * The pre-shuffle order, as indices into [entries]. Empty means "same as
     * [entries]", which is the common case and costs nothing to store. A second
     * copy of the track list would double the blob to say the same thing.
     */
    val originalOrder: List<Int> = emptyList(),
)

object PlaybackSnapshotCodec {

    /**
     * `encodeDefaults = false` roughly halves the blob — most of `UnifiedTrack`'s
     * fields are null defaults on any given track — and `ignoreUnknownKeys`
     * means a field added in a later build doesn't invalidate a snapshot written
     * by this one. Same configuration the history rows already round-trip
     * `UnifiedTrack` through, so `PlaybackSource`'s sealed polymorphism is
     * already proven against it in production.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(queue: PersistedQueue): String? =
        runCatching { json.encodeToString(queue) }.getOrNull()

    /**
     * Returns null for anything we shouldn't act on — malformed JSON, a version
     * this build doesn't know, an empty queue. The caller treats null as "no
     * snapshot", which lands the user exactly where they are today rather than
     * somewhere wrong.
     */
    fun decode(raw: String?): PersistedQueue? {
        if (raw.isNullOrBlank()) return null
        val decoded = runCatching { json.decodeFromString<PersistedQueue>(raw) }.getOrNull()
            ?: return null
        if (decoded.version != PLAYBACK_SNAPSHOT_VERSION) return null
        if (decoded.entries.isEmpty()) return null
        return decoded
    }
}

/**
 * Encoding of the pre-shuffle queue order.
 *
 * Matching is positional, never by track id: a queue is allowed to hold the same
 * track twice, and an id-keyed mapping would collapse both copies onto whichever
 * one it found first.
 */
object QueueOrdering {

    /**
     * [original] as indices into [current], dropping originals [current] no
     * longer holds.
     *
     * The two lists genuinely do diverge. `QueueManager.originalQueue` is only
     * maintained by setQueue, addToQueue, clearUpcoming and toggleShuffle —
     * removing a track, moving one, or queueing one to play next all leave it
     * untouched. Treating it as a permutation would mean a shuffle toggled off
     * after a restore resurrects tracks the user removed.
     *
     * Returns empty when the result is the identity, which is what an unshuffled
     * queue produces and is the case not worth storing.
     */
    fun encode(current: List<Track>, original: List<Track>): List<Int> {
        if (original.isEmpty() || current.isEmpty()) return emptyList()

        val free = HashMap<Long, ArrayDeque<Int>>()
        current.forEachIndexed { index, track ->
            free.getOrPut(track.id) { ArrayDeque() }.addLast(index)
        }

        val order = ArrayList<Int>(current.size)
        for (track in original) {
            val slots = free[track.id] ?: continue
            if (slots.isEmpty()) continue
            order.add(slots.removeFirst())
        }

        val identity = order.size == current.size && order.withIndex().all { it.value == it.index }
        return if (identity) emptyList() else order
    }

    /** [entries] back in their pre-shuffle order. An empty [order] is identity. */
    fun decode(entries: List<Track>, order: List<Int>): List<Track> {
        if (order.isEmpty()) return entries
        return order.mapNotNull { entries.getOrNull(it) }
    }
}

/**
 * How much of a long queue is worth writing down.
 *
 * A queue can run to thousands of entries (an album shuffle, a radio tail), and
 * the blob is rewritten whenever the queue changes. Biased forward because
 * that is where the value is: what's coming up matters more on reopening than
 * what already played.
 */
object QueueWindow {
    const val MAX_PERSISTED_ENTRIES = 300
    const val KEEP_BEHIND = 50

    /** [from] until [until] of the original queue, and where the current track lands in it. */
    data class Window(val from: Int, val until: Int, val currentIndex: Int)

    fun around(size: Int, currentIndex: Int, max: Int = MAX_PERSISTED_ENTRIES): Window {
        if (size <= 0) return Window(0, 0, -1)
        if (size <= max) return Window(0, size, currentIndex)

        val current = currentIndex.coerceIn(0, size - 1)
        // Take the tail when the play head is near the end, so the window is
        // always exactly `max` long rather than running off the end.
        val from = (current - KEEP_BEHIND).coerceIn(0, size - max)
        return Window(from, from + max, current - from)
    }
}

/**
 * When a position is worth writing to disk.
 *
 * Two sources feed the save — player events and a heartbeat — and without a
 * throttle between them a pause during a seek is several writes in a
 * millisecond. [flush] is for the moments where losing the write loses the
 * feature: a pause, a seek, the task being swiped away, the service dying.
 */
object PositionWriteThrottle {
    const val MIN_INTERVAL_MS = 5_000L
    const val MIN_DELTA_MS = 1_000L

    fun shouldWrite(
        lastWriteAt: Long,
        now: Long,
        lastPositionMs: Long,
        positionMs: Long,
        flush: Boolean,
    ): Boolean {
        if (flush) return true
        // Backwards means a seek back or a new track: the stored position is
        // now wrong about which second of which song, so it lands regardless of
        // how recently we wrote.
        if (positionMs < lastPositionMs) return true
        return now - lastWriteAt >= MIN_INTERVAL_MS &&
            positionMs - lastPositionMs >= MIN_DELTA_MS
    }
}
