package tf.monochrome.android.player

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.db.dao.PlaybackStateDao
import tf.monochrome.android.data.db.entity.PlaybackQueueEntity
import tf.monochrome.android.data.db.entity.PlaybackStateEntity
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Remembers what was playing, so reopening the app comes back to it.
 *
 * The queue, the play head and the shuffle/repeat modes all lived only in
 * [QueueManager]'s StateFlows, which die with the process. Closing the app and
 * opening it later landed on an empty player with no mini player and no way
 * back to the song except finding it again and scrubbing.
 *
 * Restores **paused**. Nothing here resolves a stream, takes audio focus or
 * posts a notification — the queue comes back, the scrubber shows where it was,
 * and the first press of play is what goes to the network.
 *
 * The split of responsibilities copies DownloadManager/DownloadQueue: this class
 * owns the I/O so [QueueManager] can stay a plain state holder with no
 * dependencies, which is also what keeps `QueueManagerTest` able to construct it
 * with no mocks.
 */
@Singleton
class PlaybackStateRepository @Inject constructor(
    private val dao: PlaybackStateDao,
    private val queueManager: QueueManager,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
    private val qobuzIdRegistry: QobuzIdRegistry,
) {

    /**
     * Its own scope, deliberately. The service saves the position from
     * `onDestroy`, and `PlaybackService.serviceScope` is cancelled in that same
     * method — a save launched there would be cancelled before it reached the
     * disk, losing exactly the write that matters most.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()

    /** Where the first play should start, if the user hasn't moved on from it. */
    data class PendingStart(val trackId: Long, val positionMs: Long, val durationMs: Long)

    private val _pendingStart = MutableStateFlow<PendingStart?>(null)
    val pendingStart: StateFlow<PendingStart?> = _pendingStart.asStateFlow()

    private var lastWriteAt = 0L
    private var lastPositionMs = -1L
    private var restoreDone = false

    @OptIn(FlowPreview::class)
    fun start(appScope: CoroutineScope) {
        appScope.launch {
            // Restore before the collector below is subscribed. The other order
            // publishes QueueManager's empty initial state straight over the
            // snapshot we are about to read.
            runCatching { restore() }
                .onFailure { Log.e(TAG, "Could not restore the last session", it) }
            restoreDone = true

            combine(
                queueManager.queue,
                queueManager.currentIndex,
                queueManager.shuffleEnabled,
                queueManager.repeatMode,
            ) { queue, index, shuffle, repeat -> QueueShape(queue, index, shuffle, repeat) }
                .debounce(QUEUE_SAVE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { shape ->
                    runCatching { saveQueue(shape) }
                        .onFailure { Log.e(TAG, "Could not save the queue", it) }
                }
        }
    }

    private data class QueueShape(
        val queue: List<Track>,
        val index: Int,
        val shuffle: Boolean,
        val repeat: RepeatMode,
    )

    // ── Saving ──────────────────────────────────────────────────────

    private suspend fun saveQueue(shape: QueueShape) = writeMutex.withLock {
        if (shape.queue.isEmpty()) {
            dao.clearQueue()
            dao.clearState()
            _pendingStart.value = null
            return@withLock
        }

        val window = QueueWindow.around(shape.queue.size, shape.index)
        val windowed = shape.queue.subList(window.from, window.until)
        val entries = windowed.map { track ->
            PersistedQueueEntry(track = track, unified = unifiedTrackRegistry[track.id])
        }
        val json = PlaybackSnapshotCodec.encode(
            PersistedQueue(
                version = PLAYBACK_SNAPSHOT_VERSION,
                entries = entries,
                // Re-encoded against the window, not the full queue, or the
                // indices point past the end of what was actually stored.
                originalOrder = QueueOrdering.encode(windowed, queueManager.originalQueueSnapshot),
            )
        ) ?: return@withLock

        val now = System.currentTimeMillis()
        val current = windowed.getOrNull(window.currentIndex)
        dao.upsertQueue(PlaybackQueueEntity(id = 1, queueJson = json, updatedAt = now))
        dao.upsertState(
            PlaybackStateEntity(
                id = 1,
                currentIndex = window.currentIndex,
                currentTrackId = current?.id ?: 0,
                // A queue edit does not move the play head, so carry the stored
                // position across rather than resetting it — otherwise removing
                // a track further down the queue would drop the play head of
                // the song currently running back to zero.
                positionMs = dao.getState()?.takeIf { it.currentTrackId == current?.id }?.positionMs ?: 0,
                durationMs = (current?.duration ?: 0) * 1000L,
                shuffleEnabled = shape.shuffle,
                repeatMode = shape.repeat.name,
                updatedAt = now,
            )
        )
    }

    /**
     * Record the play head. Fire-and-forget on this class's own scope.
     *
     * [flush] is for the moments where losing the write loses the feature — a
     * pause, a seek, the task swiped away, the service being destroyed —
     * and bypasses the throttle.
     */
    fun savePosition(positionMs: Long, durationMs: Long, flush: Boolean = false) {
        if (!restoreDone) return
        val track = queueManager.currentTrack.value ?: return
        // A station is not a recording: it has no position worth seeking to and
        // resuming one "where you left off" would seek into a live stream.
        // Enforced here as well as at the caller — one invariant, and the
        // callers are spread across six player events.
        val live = isLiveStream(track)
        val position = if (live) 0L else positionMs.coerceAtLeast(0L)
        val duration = if (live) 0L else durationMs.coerceAtLeast(0L)

        val now = System.currentTimeMillis()
        if (!PositionWriteThrottle.shouldWrite(lastWriteAt, now, lastPositionMs, position, flush)) return
        lastWriteAt = now
        lastPositionMs = position

        scope.launch {
            runCatching {
                writeMutex.withLock { dao.updatePosition(position, duration, track.id, now) }
            }.onFailure { Log.e(TAG, "Could not save the play position", it) }
        }
    }

    private fun isLiveStream(track: Track): Boolean =
        unifiedTrackRegistry[track.id]?.source is PlaybackSource.RadioStream

    // ── Restoring ───────────────────────────────────────────────────

    private suspend fun restore() {
        val state = dao.getState() ?: return
        val persisted = PlaybackSnapshotCodec.decode(dao.getQueue()?.queueJson) ?: return

        // Rehydrate the routing registries before QueueManager publishes a
        // current track. resolveAndPlay consults them first and falls through to
        // the legacy TIDAL path when they miss, which for a restored local file
        // means playing a different song under the right title — the same
        // failure the history rows already guard against this way.
        persisted.entries.forEach { entry ->
            val unified = entry.unified ?: return@forEach
            unifiedTrackRegistry.put(entry.track.id, unified)
            if (unified.source is PlaybackSource.QobuzCached) {
                qobuzIdRegistry.registerTrack(entry.track.id)
            }
        }

        val tracks = persisted.entries.map { it.track }
        queueManager.restore(
            queue = tracks,
            originalQueue = QueueOrdering.decode(tracks, persisted.originalOrder),
            currentIndex = state.currentIndex,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = runCatching { RepeatMode.valueOf(state.repeatMode) }
                .getOrDefault(RepeatMode.OFF),
        )

        val current = queueManager.currentTrack.value ?: return
        val duration = state.durationMs.takeIf { it > 0 } ?: (current.duration * 1000L)
        val position = when {
            isLiveStream(current) -> 0L
            // It had effectively finished. Reopening onto the last four seconds
            // of a song, where play means an immediate skip, is worse than
            // reopening onto the start of it.
            duration > 0 && state.positionMs >= duration - END_OF_TRACK_MS -> 0L
            else -> state.positionMs.coerceAtLeast(0L)
        }
        lastPositionMs = position
        _pendingStart.value = PendingStart(current.id, position, duration)
    }

    /**
     * The position [trackId] should start at, consumed on first use.
     *
     * One-shot and keyed on the track so that replaying the same song later
     * starts at the beginning, and so a restore whose track the user skipped
     * past never seeks a different one.
     */
    fun consumePendingStart(trackId: Long): Long {
        val pending = _pendingStart.value ?: return 0L
        // Cleared either way. Any resolveAndPlay is the user picking something
        // to play, which spends the restored session whether or not they picked
        // the track it was holding — leaving it set would keep the scrubber
        // seed and the pre-play seek path armed for the rest of the process.
        _pendingStart.value = null
        return if (pending.trackId == trackId) pending.positionMs else 0L
    }

    /** Same, without consuming — for the lock-screen / Bluetooth resume path. */
    fun peekResumePosition(track: Track?): Long {
        val pending = _pendingStart.value ?: return 0L
        if (track == null || pending.trackId != track.id) return 0L
        return pending.positionMs
    }

    /**
     * Move where the first play will start.
     *
     * Scrubbing before pressing play is a real gesture, and on a restored
     * session the player holds no item yet, so the seek that would normally
     * carry it is dropped on the floor and play resumes at the stale position.
     */
    fun overridePendingStart(positionMs: Long) {
        val pending = _pendingStart.value ?: return
        _pendingStart.value = pending.copy(positionMs = positionMs.coerceAtLeast(0L))
    }

    /** Drop the restored position, e.g. when the track it belonged to won't play. */
    fun clearPendingStart() {
        _pendingStart.value = null
    }

    private companion object {
        const val TAG = "PlaybackState"
        const val QUEUE_SAVE_DEBOUNCE_MS = 1_000L
        const val END_OF_TRACK_MS = 5_000L
    }
}
