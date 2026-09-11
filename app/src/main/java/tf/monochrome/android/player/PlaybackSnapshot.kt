package tf.monochrome.android.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * What the player writes down so it can come back to where it was.
 *
 * Pure Kotlin on purpose — no Android, no Room, no coroutines — so every
 * decision worth pinning is callable from the unit tests, which carry JUnit
 * and nothing else.
 */

/** Bumped when the shape below changes incompatibly; older blobs are discarded. */
const val PLAYBACK_SNAPSHOT_VERSION = 1

/**
 * One queue position.
 *
 * [unified] is load-bearing, not an optimization: `UnifiedTrackRegistry` is
 * in-memory and empty after process death, so a queue of bare [Track]s falls
 * through `resolveAndPlay` to the legacy TIDAL branch and plays a different
 * song under the right title.
 *
 * Both halves are stored because `UnifiedTrack.toLegacyTrack()` is not a
 * faithful inverse — it rewrites the album id to a hash — so deriving either
 * one would corrupt the entries that arrived as legacy tracks.
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
     * Pre-shuffle order, as indices into [entries]. Empty means identity — the
     * common case. A second copy of the list would double the blob to say it.
     */
    val originalOrder: List<Int> = emptyList(),
)

object PlaybackSnapshotCodec {

    /**
     * `encodeDefaults = false` roughly halves the blob (most of `UnifiedTrack`
     * is null on any given track); `ignoreUnknownKeys` keeps a snapshot
     * readable after a later build adds a field. Same config the history rows
     * already round-trip `UnifiedTrack` through.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    fun encode(queue: PersistedQueue): String? =
        runCatching { json.encodeToString(queue) }.getOrNull()

    /**
     * Null for anything not worth acting on — malformed, wrong version, empty.
     * The caller reads that as "no snapshot" and leaves the user where they
     * are rather than somewhere wrong.
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
 * Encoding of the pre-shuffle queue order. Positional, never by track id: a
 * queue may hold the same track twice, and an id-keyed map collapses both
 * copies onto whichever it found first.
 */
object QueueOrdering {

    /**
     * [original] as indices into [current], dropping originals [current] no
     * longer holds. The two genuinely diverge: `QueueManager.originalQueue` is
     * maintained only by setQueue, addToQueue, clearUpcoming and toggleShuffle,
     * so treating it as a permutation would let a shuffle toggled off after a
     * restore resurrect tracks the user removed.
     *
     * Empty when the result is the identity — an unshuffled queue, not worth
     * storing.
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
 * How much of a long queue is worth writing down. A queue can run to thousands
 * of entries and the blob is rewritten on every change. Biased forward: what's
 * coming up matters more on reopening than what already played.
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
        // Take the tail near the end, so the window is always exactly `max`.
        val from = (current - KEEP_BEHIND).coerceIn(0, size - max)
        return Window(from, from + max, current - from)
    }
}

/**
 * When a position is worth writing to disk. Player events and a heartbeat both
 * feed the save, so without a throttle a pause mid-seek is several writes in a
 * millisecond. [flush] is for when losing the write loses the feature: a pause,
 * a seek, the task swiped away, the service dying.
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
        // Backwards means a seek back or a new track, so the stored position
        // is wrong about which second of which song. Always write.
        if (positionMs < lastPositionMs) return true
        return now - lastWriteAt >= MIN_INTERVAL_MS &&
            positionMs - lastPositionMs >= MIN_DELTA_MS
    }
}
