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
 *
 * A YouTube or SoundCloud song's id is a hash too, but every search result in
 * a list that was played lands here, and kept for good they only grew. So
 * only the ones a like or a playlist holds are kept for good: their rows have
 * nothing but the hash ([markScxytHash], on reading the list or saving the
 * song), and this is how this run plays them. The rest are a bound of their
 * own, [KEEP_SCXYT_OUTSIDE_QUEUE], kept by the same order as the others, so
 * the churn of a library queue never pushes them out. Those come back on
 * their own: Recently Played puts its rows here each time it is read, a
 * search puts its results when one is played, a download plays from its file
 * (the local lookup, by the hashed id), and the saved queue puts its songs
 * back when it is restored. One let go stays known by its hash
 * ([isUnresolvedScxyt]), so a list still showing it never sends it to TIDAL.
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

    // A liked or playlisted YouTube or SoundCloud song keeps only its hashed
    // id, with FavoriteTrackEntity.source naming the service. Until this run
    // sees the song itself, that number must not reach TIDAL, whose catalogue
    // may hold a different song under it. The same goes for one whose entry
    // was let go here: a list may still show it by its hash. Only the ids
    // are kept, a few dozen bytes each against an entry's 1.4 KB.
    private val scxytHashes = ConcurrentHashMap.newKeySet<Long>()

    /** The liked and playlisted ones among them, whose entries are kept for good. */
    private val savedScxyt = ConcurrentHashMap.newKeySet<Long>()

    /** [trackId] is a liked or playlisted YouTube or SoundCloud song's hashed id. */
    fun markScxytHash(trackId: Long) {
        scxytHashes.add(trackId)
        savedScxyt.add(trackId)
    }

    /** A YouTube or SoundCloud song known only by its hash: nothing here can play it. */
    fun isUnresolvedScxyt(trackId: Long): Boolean =
        (trackId in scxytHashes || tf.monochrome.android.domain.model.isScxytLegacyId(trackId)) &&
            get(trackId)?.source !is PlaybackSource.Scxyt

    fun clear() { map.clear() }

    /** How many tracks it holds. */
    val size: Int get() = map.size

    /**
     * Forgets what is neither in [queue] nor [original] (the queue's
     * pre-shuffle order) nor among the [KEEP_OUTSIDE_QUEUE] kept outside it
     * (YouTube and SoundCloud songs: [KEEP_SCXYT_OUTSIDE_QUEUE]).
     * On the queue's own thread, right after it changed: the tracks of a new
     * queue are put here before it is set, so they are in it by now.
     */
    @Synchronized
    internal fun retainFor(queue: List<Track>, original: List<Track>) {
        val now = clock.incrementAndGet()
        for (track in queue) map[track.id]?.queuedAt = now
        for (track in original) map[track.id]?.queuedAt = now
        if (map.size <= minOf(KEEP_OUTSIDE_QUEUE, KEEP_SCXYT_OUTSIDE_QUEUE)) return

        val outside = ArrayList<Map.Entry<Long, Entry>>()
        val scxytOutside = ArrayList<Map.Entry<Long, Entry>>()
        for (entry in map.entries) {
            if (entry.value.queuedAt == now || keptForGood(entry.key, entry.value.track.source)) continue
            if (entry.value.track.source is PlaybackSource.Scxyt) scxytOutside += entry else outside += entry
        }
        keepNewest(outside, KEEP_OUTSIDE_QUEUE)
        // Known as a hash from now on, so a tap on it is skipped rather than
        // sent to TIDAL, until a search or Recently Played puts it back.
        keepNewest(scxytOutside, KEEP_SCXYT_OUTSIDE_QUEUE) { scxytHashes.add(it) }
    }

    /** Forgets all but the [keep] of [entries] ranked highest, telling [dropped] of each. */
    private inline fun keepNewest(entries: List<Map.Entry<Long, Entry>>, keep: Int, dropped: (Long) -> Unit = {}) {
        if (entries.size <= keep) return
        val keys = LongArray(entries.size) { rank(entries[it].value) }
        val cutoff = keys.copyOf().also { it.sort() }[entries.size - keep]
        for (i in entries.indices) {
            // Only the entry looked at: one put again meanwhile stays.
            if (keys[i] < cutoff && map.remove(entries[i].key, entries[i].value)) dropped(entries[i].key)
        }
    }

    /** A song this registry is the only way back to where it plays from; see the class comment. */
    private fun keptForGood(trackId: Long, source: PlaybackSource): Boolean =
        source is PlaybackSource.Subsonic || (source is PlaybackSource.Scxyt && trackId in savedScxyt)

    /** Higher is kept first; see the class comment. */
    private fun rank(entry: Entry): Long =
        if (entry.stamp > entry.queuedAt) ASKED_FOR + entry.stamp
        else (entry.queuedAt shl 24) or (entry.stamp and 0xFF_FFFFL)

    companion object {
        /** Tracks kept that are not in the queue: about 1.4 MB of them at most. */
        const val KEEP_OUTSIDE_QUEUE = 1_000

        /**
         * YouTube and SoundCloud songs kept that are not in the queue, nor
         * liked nor in a playlist: about a hundred played searches' worth,
         * some 2.8 MB at most.
         */
        const val KEEP_SCXYT_OUTSIDE_QUEUE = 2_000

        /** Above every rank of a track that only left a queue. */
        private const val ASKED_FOR = 1L shl 62
    }
}
