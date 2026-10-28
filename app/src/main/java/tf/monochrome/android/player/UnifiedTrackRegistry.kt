package tf.monochrome.android.player

import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Shared map from legacy [tf.monochrome.android.domain.model.Track.id] to the
 * originating [UnifiedTrack] (local file, encrypted collection entry, etc.).
 *
 * The main-process [tf.monochrome.android.ui.player.PlayerViewModel] populates
 * this when the user plays a unified queue, and both the ViewModel's
 * `resolveAndPlay()` path and the [PlaybackService]'s media-button / notification
 * skip path consult it to decide which resolver branch to use (unified local
 * vs. legacy HiFi API).
 *
 * Without a shared registry, notification / lock-screen next / previous taps
 * reach `PlaybackService.skipToNext()` → `playQueue()`, which would only ever
 * call the HiFi API resolver — so skipping in the notification while playing
 * a local file would silently fail.
 *
 * Kept to what can still be asked for, each time the queue lets go of tracks
 * wholesale ([QueueManager.onTracksReleased]). It held every track ever
 * queued: a tap on one song of the library queues the whole library, and 20,000
 * songs stayed here, about 28 MB, after the queue had moved on.
 *
 * Most questions are about the queue: the player, the notification's skip,
 * Connect and parties describing what plays, the saved session, the history
 * row, lyrics, ReplayGain. So every track in the queue and its pre-shuffle
 * order stays. The rest are asked about tracks shown somewhere else (Recently
 * Played, which puts its rows here each time it is read, a like from a list,
 * the download and share buttons), so [KEEP_OUTSIDE_QUEUE] of those stay too:
 * first the ones put here since they were last in the queue (someone asked for
 * them), the newest first, then the ones that only left a queue, the latest to
 * leave first.
 *
 * A song from the listener's own server is never let go. Liked Songs,
 * playlists and search show plain tracks and never put their rows here, and
 * for a server song this is the only record of where it plays from: forgotten,
 * a tap on it went to TIDAL with its hashed id. A local file forgotten is
 * still found, by its tags (StreamResolver's local lookup).
 */
@Singleton
class UnifiedTrackRegistry @Inject constructor(queueManager: QueueManager) {

    private class Entry(val track: UnifiedTrack, val stamp: Long) {
        /** The [clock] of the last time it was found in the queue; 0 for never. */
        @Volatile var queuedAt = 0L
    }

    private val map = ConcurrentHashMap<Long, Entry>()

    /** Orders puts and prunes, so the two can be compared. */
    private val clock = AtomicLong()

    init {
        queueManager.onTracksReleased = this::retainFor
    }

    fun put(trackId: Long, unifiedTrack: UnifiedTrack) {
        map[trackId] = Entry(unifiedTrack, clock.incrementAndGet())
    }

    fun putAll(entries: List<UnifiedTrack>, legacyIdFor: (UnifiedTrack) -> Long) {
        entries.forEach { put(legacyIdFor(it), it) }
    }

    operator fun get(trackId: Long): UnifiedTrack? = map[trackId]?.track

    fun clear() { map.clear() }

    /** How many tracks it holds. */
    val size: Int get() = map.size

    /**
     * Forgets what is neither in [queue] nor [original] (the queue's
     * pre-shuffle order) nor among the [KEEP_OUTSIDE_QUEUE] kept outside it.
     * On the queue's own thread, right after it changed: the tracks of a new
     * queue are put here before it is set, so they are in it by now.
     */
    @Synchronized
    internal fun retainFor(queue: List<Track>, original: List<Track>) {
        val now = clock.incrementAndGet()
        for (track in queue) map[track.id]?.queuedAt = now
        for (track in original) map[track.id]?.queuedAt = now
        if (map.size <= KEEP_OUTSIDE_QUEUE) return

        val outside = ArrayList<Map.Entry<Long, Entry>>()
        for (entry in map.entries) {
            if (entry.value.queuedAt != now && entry.value.track.source !is PlaybackSource.Subsonic) outside += entry
        }
        if (outside.size <= KEEP_OUTSIDE_QUEUE) return

        val keys = LongArray(outside.size) { rank(outside[it].value) }
        val cutoff = keys.copyOf().also { it.sort() }[outside.size - KEEP_OUTSIDE_QUEUE]
        for (i in outside.indices) {
            // Only the entry looked at: one put again meanwhile stays.
            if (keys[i] < cutoff) map.remove(outside[i].key, outside[i].value)
        }
    }

    /** Higher is kept first; see the class comment. */
    private fun rank(entry: Entry): Long =
        if (entry.stamp > entry.queuedAt) ASKED_FOR + entry.stamp
        else (entry.queuedAt shl 24) or (entry.stamp and 0xFF_FFFFL)

    companion object {
        /** Tracks kept that are not in the queue: about 1.4 MB of them at most. */
        const val KEEP_OUTSIDE_QUEUE = 1_000

        /** Above every rank of a track that only left a queue. */
        private const val ASKED_FOR = 1L shl 62
    }
}
