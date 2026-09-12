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
 * Remembers what was playing, so reopening the app comes back to it. The queue,
 * play head and modes lived only in [QueueManager]'s StateFlows, which die with
 * the process.
 *
 * Restores **paused**. Nothing here resolves a stream, takes audio focus or
 * posts a notification; the first press of play is what goes to the network.
 *
 * Owns the I/O so [QueueManager] stays a plain state holder with no
 * dependencies — which is what lets `QueueManagerTest` build it with no mocks.
 * Same split as DownloadManager/DownloadQueue.
 */
@Singleton
class PlaybackStateRepository @Inject constructor(
    private val dao: PlaybackStateDao,
    private val queueManager: QueueManager,
    private val unifiedTrackRegistry: UnifiedTrackRegistry,
    private val qobuzIdRegistry: QobuzIdRegistry,
) {

    /**
     * Its own scope, deliberately: the service saves from `onDestroy`, which is
     * where `serviceScope` is cancelled — a save launched there dies before
     * reaching disk, losing exactly the write that matters most.
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
            // Before the collector subscribes: the other order publishes
            // QueueManager's empty initial state over the snapshot.
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
                // Against the window, not the full queue, or the indices
                // point past the end of what was stored.
                originalOrder = QueueOrdering.encode(windowed, queueManager.originalQueueSnapshot),
            )
        ) ?: return@withLock

        val now = System.currentTimeMillis()
        val current = windowed.getOrNull(window.currentIndex)
        dao.upsertSnapshot(
            queue = PlaybackQueueEntity(id = 1, queueJson = json, updatedAt = now),
            state = PlaybackStateEntity(
                id = 1,
                currentIndex = window.currentIndex,
                currentTrackId = current?.id ?: 0,
                // A queue edit doesn't move the play head: carry the stored
                // position across, or removing a track further down the queue
                // drops the running song back to zero.
                positionMs = dao.getState()?.takeIf { it.currentTrackId == current?.id }?.positionMs ?: 0,
                durationMs = (current?.duration ?: 0) * 1000L,
                shuffleEnabled = shape.shuffle,
                repeatMode = shape.repeat.name,
                updatedAt = now,
            ),
        )
    }

    /**
     * Record the play head. Fire-and-forget on this class's own scope. [flush]
     * bypasses the throttle, for when losing the write loses the feature: a
     * pause, a seek, the task swiped away, the service destroyed.
     */
    fun savePosition(positionMs: Long, durationMs: Long, flush: Boolean = false) {
        if (!restoreDone) return
        val track = queueManager.currentTrack.value ?: return
        // A station is not a recording: resuming one "where you left off"
        // seeks into a live stream. Enforced here as well as at the caller,
        // which is spread across six player events.
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

        // Before QueueManager publishes a current track: resolveAndPlay
        // consults these first and falls through to the legacy TIDAL path when
        // they miss, which for a restored local file plays a different song
        // under the right title.
        persisted.entries.forEach { entry ->
            val unified = entry.unified ?: return@forEach
            unifiedTrackRegistry.put(entry.track.id, unified)
            if (unified.source is PlaybackSource.QobuzCached) {
                qobuzIdRegistry.registerTrack(entry.track.id)
            }
        }

        val tracks = persisted.entries.map { it.track }
        // Find the saved track rather than trusting the saved index.
        //
        // The two records are written in one transaction now, so they cannot
        // disagree in future — but snapshots already on disk were written the
        // old way, and a stale index against a fresh queue silently reopens
        // whatever track sits at that slot. Matching on the id fixes those too,
        // and costs one scan of a queue that is at most a few hundred entries.
        //
        // Falls back to the index when the id is not found: it is 0 in
        // snapshots written before it was recorded, and a queue can legitimately
        // no longer contain it.
        val savedIndex = tracks.indexOfFirst { it.id == state.currentTrackId }
            .takeIf { it >= 0 }
            ?: state.currentIndex
        queueManager.restore(
            queue = tracks,
            originalQueue = QueueOrdering.decode(tracks, persisted.originalOrder),
            currentIndex = savedIndex,
            shuffleEnabled = state.shuffleEnabled,
            repeatMode = runCatching { RepeatMode.valueOf(state.repeatMode) }
                .getOrDefault(RepeatMode.OFF),
        )

        val current = queueManager.currentTrack.value ?: return
        // The saved second belongs to the saved track. If we could not find
        // that track and fell back to the index, the position and duration
        // beside it describe a different song — starting this one 2:41 in
        // because that is where the last one was is worse than starting it at
        // the beginning. Zero is only a lost resume; the alternative is a
        // wrong one. currentTrackId is 0 in snapshots from before it was
        // recorded, which is not a mismatch, just nothing to check against.
        val samePosition = state.currentTrackId == 0L || current.id == state.currentTrackId
        val duration = state.durationMs.takeIf { it > 0 && samePosition }
            ?: (current.duration * 1000L)
        val position = when {
            !samePosition -> 0L
            isLiveStream(current) -> 0L
            // Effectively finished. Reopening onto the last four seconds,
            // where play means an immediate skip, is worse than the start.
            duration > 0 && state.positionMs >= duration - END_OF_TRACK_MS -> 0L
            else -> state.positionMs.coerceAtLeast(0L)
        }
        lastPositionMs = position
        _pendingStart.value = PendingStart(current.id, position, duration)
    }

    /**
     * The position [trackId] should start at, consumed on first use. Keyed on
     * the track so replaying it later starts at the beginning, and a restore
     * the user skipped past never seeks a different song.
     */
    fun consumePendingStart(trackId: Long): Long {
        val pending = _pendingStart.value ?: return 0L
        // Cleared either way: any resolveAndPlay spends the restored session,
        // and leaving it set keeps the scrubber seed and the pre-play seek
        // armed for the rest of the process.
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
     * Move where the first play will start. Scrubbing before pressing play is a
     * real gesture, but a restored session holds no item yet, so the seek that
     * would carry it is dropped and play resumes at the stale position.
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
