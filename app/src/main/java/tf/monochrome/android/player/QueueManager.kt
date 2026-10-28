package tf.monochrome.android.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class QueueManager @Inject constructor() {
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    private val _currentIndex = MutableStateFlow(-1)
    val currentIndex: StateFlow<Int> = _currentIndex.asStateFlow()

    private val _currentTrack = MutableStateFlow<Track?>(null)
    val currentTrack: StateFlow<Track?> = _currentTrack.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private var originalQueue: List<Track> = emptyList()

    /**
     * The lists this queue was played from, or null when it came from
     * anywhere else. Set only with a whole new queue, and by [ListContinuation]
     * as it adds lists; a new queue, clearing what is up next and clearing the
     * queue all forget it. Editing the queue keeps it, and dropping the oldest
     * of a long one moves its head ([trimmed]).
     */
    private val _listContext = MutableStateFlow<ListContext?>(null)
    val listContext: StateFlow<ListContext?> = _listContext.asStateFlow()

    val currentQueue: List<Track> get() = _queue.value
    val currentQueueIndex: Int get() = _currentIndex.value

    /**
     * The pre-shuffle order, for whoever is persisting this. Not a permutation
     * of [queue]: setQueue, addToQueue, clearUpcoming, toggleShuffle and the
     * dropping of the oldest ([trimmed]) keep it, but remove, move and
     * play-next leave it alone, so it can hold tracks the queue no longer has
     * and miss ones it does.
     */
    val originalQueueSnapshot: List<Track> get() = originalQueue

    /**
     * Told after the queue lets go of tracks wholesale (a new queue, a clear,
     * the oldest of a long one dropped), with the queue as it is now and its
     * pre-shuffle order. [UnifiedTrackRegistry] sets it, to forget what no one
     * can ask for any more; QueueManager itself still depends on nothing.
     */
    @Volatile
    var onTracksReleased: ((queue: List<Track>, original: List<Track>) -> Unit)? = null

    /**
     * The first track of each list in the queue, by identity, and the list it
     * starts: where the oldest can be dropped whole ([trimmed]). Only lists the
     * queue was given whole, and forgotten when the order is no longer theirs
     * (shuffle turned on or off, a track moved).
     */
    private val listStarts = IdentityHashMap<Track, ListRef>()

    /**
     * Where playing this queue started: the song a queue was set or restored
     * at, or shuffle turned on or off at. The songs before it were chosen, not
     * played, so the cap on what is behind ([trimmed]) counts from here: a tap
     * on the 5,000th song of a library is not 5,000 songs played.
     */
    private var playedFrom = 0

    /**
     * Put every field back at once from a persisted session. Not [setQueue],
     * which forces shuffle off and discards the pre-shuffle order — the two
     * things a restore exists to bring back. Touches no player; the only
     * visible effect is [currentTrack] going non-null, raising the mini player.
     */
    fun restore(
        queue: List<Track>,
        originalQueue: List<Track>,
        currentIndex: Int,
        shuffleEnabled: Boolean,
        repeatMode: RepeatMode,
        listContext: ListContext? = null,
    ) {
        _queue.value = queue
        this.originalQueue = originalQueue.ifEmpty { queue }
        _currentIndex.value = if (queue.isEmpty()) -1 else currentIndex.coerceIn(0, queue.lastIndex)
        playedFrom = _currentIndex.value.coerceAtLeast(0)
        _shuffleEnabled.value = shuffleEnabled
        _repeatMode.value = repeatMode
        _listContext.value = listContext
        startLists(queue, listContext)
        updateCurrentTrack()
        released()
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0, listContext: ListContext? = null) {
        _queue.value = tracks
        originalQueue = tracks
        _currentIndex.value = startIndex
        playedFrom = startIndex.coerceAtLeast(0)
        _shuffleEnabled.value = false
        _listContext.value = listContext
        startLists(tracks, listContext)
        updateCurrentTrack()
        released()
    }

    fun playTrackInQueue(track: Track, tracks: List<Track>, listContext: ListContext? = null) {
        val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        setQueue(tracks, index, listContext)
    }

    /**
     * Add the next list behind the queue. [tracks] is in play order (shuffled
     * when shuffle is on), [inOrder] the same tracks unshuffled, which is what
     * turning shuffle off brings back.
     */
    fun appendList(tracks: List<Track>, inOrder: List<Track>, context: ListContext) {
        tracks.firstOrNull()?.let { first -> synchronized(listStarts) { listStarts[first] = context.tail } }
        grow(
            queue = _queue.value + tracks,
            original = if (_shuffleEnabled.value) originalQueue + inOrder else originalQueue,
            context = context,
        )
    }

    /**
     * Put the previous list in front of the queue and move to its last track,
     * in one step: Previous on the first song of a list lands on the last song
     * of the list before. Does nothing with [tracks] empty.
     */
    fun prependList(tracks: List<Track>, inOrder: List<Track>, context: ListContext) {
        if (tracks.isEmpty()) return
        synchronized(listStarts) { listStarts[tracks.first()] = context.head }
        _queue.value = tracks + _queue.value
        if (_shuffleEnabled.value) originalQueue = inOrder + originalQueue
        _currentIndex.value = tracks.lastIndex
        playedFrom += tracks.size
        _listContext.value = context
        updateCurrentTrack()
    }

    /** Forget the lists, keeping the queue: there is nothing left to continue into. */
    fun forgetListContext() {
        _listContext.value = null
        synchronized(listStarts) { listStarts.clear() }
    }

    fun addToQueue(tracks: List<Track>) {
        grow(
            queue = _queue.value + tracks,
            original = if (_shuffleEnabled.value) originalQueue + tracks else originalQueue,
            context = _listContext.value,
        )
    }

    fun addNextInQueue(track: Track) {
        val currentList = _queue.value.toMutableList()
        val insertIndex = (_currentIndex.value + 1).coerceAtMost(currentList.size)
        currentList.add(insertIndex, track)
        _queue.value = currentList
    }

    fun removeFromQueue(index: Int) {
        if (index < 0 || index >= _queue.value.size) return
        val currentList = _queue.value.toMutableList()
        currentList.removeAt(index)
        _queue.value = currentList

        if (index < _currentIndex.value) {
            _currentIndex.value = _currentIndex.value - 1
        } else if (index == _currentIndex.value) {
            // Current track removed, stay at same index (next track slides in)
            _currentIndex.value = _currentIndex.value.coerceAtMost(currentList.size - 1)
        }
        updateCurrentTrack()
    }

    /** Remove several queue positions at once, preserving the current track when possible. */
    fun removeMany(indices: Set<Int>) {
        if (indices.isEmpty()) return

        val queue = _queue.value
        val current = _currentIndex.value
        val currentTrack = queue.getOrNull(current)

        val newQueue = queue.filterIndexed { index, _ -> index !in indices }

        val newCurrent = when {
            newQueue.isEmpty() -> -1
            currentTrack == null -> -1
            current in indices ->
                // Current track deleted: land on whatever slid into its slot,
                // accounting for how many removed entries preceded it.
                (current - indices.count { it < current }).coerceIn(0, newQueue.lastIndex)
            else -> newQueue.indexOfFirst { it.id == currentTrack.id }
                .takeIf { it >= 0 }
                ?: current.coerceAtMost(newQueue.lastIndex)
        }

        _queue.value = newQueue
        _currentIndex.value = newCurrent
        updateCurrentTrack()
    }

    /** Move a queue item, keeping the currently playing track's identity intact. */
    fun move(fromIndex: Int, toIndex: Int) {
        val queue = _queue.value.toMutableList()
        if (fromIndex !in queue.indices || toIndex !in queue.indices) return
        if (fromIndex == toIndex) return

        val cur = _currentIndex.value

        val item = queue.removeAt(fromIndex)
        queue.add(toIndex, item)
        _queue.value = queue
        // A list's first track may be anywhere now.
        synchronized(listStarts) { listStarts.clear() }

        // Track the current position through the move POSITIONALLY, not by an
        // id search: indexOfFirst{ it.id == current.id } jumped the now-playing
        // marker onto the wrong copy whenever the queue held duplicate tracks.
        _currentIndex.value = when {
            cur == fromIndex -> toIndex // the moved item itself was playing
            else -> {
                var c = cur
                if (fromIndex < c) c-- // an earlier item was pulled out
                if (toIndex <= c) c++ // an item was inserted at/before it
                c.coerceIn(0, queue.lastIndex)
            }
        }
        updateCurrentTrack()
    }

    /** Move a queue item so it plays right after the current track. */
    fun moveToPlayNext(index: Int) {
        val current = _currentIndex.value
        if (index == current) return
        // Removing an item from before the current track shifts the current
        // position down by one, so the slot "right after current" differs by
        // which side the item comes from.
        val target = (if (index < current) current else current + 1)
            .coerceIn(0, _queue.value.lastIndex.coerceAtLeast(0))
        move(index, target)
    }

    /** Drop all upcoming tracks, keeping only the one currently playing. */
    fun clearUpcoming() {
        val current = _currentIndex.value
        if (current < 0) {
            clearQueue()
            return
        }

        val currentTrack = _queue.value.getOrNull(current) ?: return
        _queue.value = listOf(currentTrack)
        originalQueue = listOf(currentTrack)
        _currentIndex.value = 0
        playedFrom = 0
        // Clearing what is up next means nothing next, not the next list.
        _listContext.value = null
        synchronized(listStarts) { listStarts.clear() }
        updateCurrentTrack()
        released()
    }

    fun skipToIndex(index: Int) {
        if (index < 0 || index >= _queue.value.size) return
        // Songs jumped over were not played: they do not count towards the cap.
        val from = _currentIndex.value
        if (index > from + 1) playedFrom += index - from - 1
        _currentIndex.value = index
        updateCurrentTrack()
    }

    /**
     * The track [next] would move to, without moving to it.
     *
     * Used to decide what to pre-queue for gapless playback. Deliberately null
     * under [RepeatMode.ONE]: the current track repeats, so there is no next
     * track to hand the player, and pre-queuing one would jump the queue.
     * Shuffle needs no special handling — it reorders the queue in place, so
     * the following index really is what plays next.
     */
    fun peekNext(): Track? {
        val queue = _queue.value
        if (queue.isEmpty()) return null
        return when (_repeatMode.value) {
            RepeatMode.ONE -> null
            RepeatMode.ALL -> queue.getOrNull((_currentIndex.value + 1) % queue.size)
            RepeatMode.OFF -> queue.getOrNull(_currentIndex.value + 1)
        }
    }

    fun next(): Track? {
        val queue = _queue.value
        if (queue.isEmpty()) return null

        return when (_repeatMode.value) {
            RepeatMode.ONE -> {
                // Replay same track
                updateCurrentTrack()
                _currentTrack.value
            }
            RepeatMode.ALL -> {
                val nextIndex = (_currentIndex.value + 1) % queue.size
                _currentIndex.value = nextIndex
                updateCurrentTrack()
                _currentTrack.value
            }
            RepeatMode.OFF -> {
                val nextIndex = _currentIndex.value + 1
                if (nextIndex >= queue.size) {
                    null // End of queue
                } else {
                    _currentIndex.value = nextIndex
                    updateCurrentTrack()
                    _currentTrack.value
                }
            }
        }
    }

    fun previous(): Track? {
        val queue = _queue.value
        if (queue.isEmpty()) return null

        val prevIndex = if (_currentIndex.value <= 0) {
            if (_repeatMode.value == RepeatMode.ALL) queue.size - 1 else 0
        } else {
            _currentIndex.value - 1
        }

        _currentIndex.value = prevIndex
        updateCurrentTrack()
        return _currentTrack.value
    }

    fun toggleShuffle() {
        if (_shuffleEnabled.value) {
            // Disable shuffle - restore original queue
            val currentTrack = _currentTrack.value
            _queue.value = originalQueue
            _currentIndex.value = if (currentTrack != null) {
                originalQueue.indexOfFirst { it.id == currentTrack.id }.coerceAtLeast(0)
            } else 0
            _shuffleEnabled.value = false
        } else {
            // Enable shuffle - Fisher-Yates preserving current track at index 0
            originalQueue = _queue.value
            val currentTrack = _currentTrack.value
            val remaining = _queue.value.toMutableList()

            if (currentTrack != null) {
                remaining.removeAll { it.id == currentTrack.id }
            }
            remaining.shuffle()

            _queue.value = if (currentTrack != null) {
                listOf(currentTrack) + remaining
            } else {
                remaining
            }
            _currentIndex.value = 0
            _shuffleEnabled.value = true
        }
        // The lists are not in their order any more, or not in the order they were added.
        synchronized(listStarts) { listStarts.clear() }
        playedFrom = _currentIndex.value.coerceAtLeast(0)
        updateCurrentTrack()
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        _repeatMode.value = mode
    }

    fun clearQueue() {
        _queue.value = emptyList()
        originalQueue = emptyList()
        _currentIndex.value = -1
        playedFrom = 0
        _currentTrack.value = null
        _listContext.value = null
        synchronized(listStarts) { listStarts.clear() }
        released()
    }

    private fun updateCurrentTrack() {
        val index = _currentIndex.value
        val queue = _queue.value
        _currentTrack.value = if (index in queue.indices) queue[index] else null
    }

    private fun released() {
        onTracksReleased?.invoke(_queue.value, originalQueue)
    }

    /** A new queue played from [context]'s lists starts the first of them. */
    private fun startLists(queue: List<Track>, context: ListContext?) {
        synchronized(listStarts) {
            listStarts.clear()
            if (context != null) queue.firstOrNull()?.let { listStarts[it] = context.head }
        }
    }

    /**
     * The queue grown at its end ([queue], with [original] its pre-shuffle
     * order and [context] its lists), published, and [trimmed] if it has run
     * on too far.
     */
    private fun grow(queue: List<Track>, original: List<Track>, context: ListContext?) {
        val trim = trimmed(queue, _currentIndex.value, context)
        if (trim == null) {
            _queue.value = queue
            originalQueue = original
            _listContext.value = context
            return
        }
        // The play head first: in between, it points at a track already
        // played, which asks nobody to add anything, where the shortened queue
        // under the old index would look nearer its end than it is.
        _currentIndex.value = trim.index
        playedFrom = (playedFrom - trim.cut).coerceAtLeast(0)
        _queue.value = trim.queue
        originalQueue = original.withoutOnce(queue.subList(0, trim.cut))
        _listContext.value = trim.context
        updateCurrentTrack()
        released()
    }

    private class Trim(val cut: Int, val queue: List<Track>, val index: Int, val context: ListContext?)

    /**
     * [queue] without its oldest tracks, when more than [MAX_BEHIND] have
     * played since [playedFrom] up to the one at [index]; null when not.
     *
     * Finished lists stay in the queue so the queue sheet scrolls back through
     * them and its count runs from the first song played, and under list
     * continuation and song radio the queue only grows. This bounds it. Down to
     * about [KEEP_BEHIND_AFTER_TRIM] behind, so it happens once every few hundred
     * songs, and at the start of a list where the queue knows where its lists
     * start: the queue then starts where its first list does again, and
     * Previous on its first song goes to the list before. Cut inside a list,
     * the queue no longer starts where that list does ([ListContext.headCut]).
     *
     * Never under Repeat All, which plays the whole queue round again.
     */
    private fun trimmed(queue: List<Track>, index: Int, context: ListContext?): Trim? {
        if (index - playedFrom <= MAX_BEHIND || index > queue.lastIndex) return null
        if (_repeatMode.value == RepeatMode.ALL) return null
        val least = index - MAX_BEHIND
        val target = index - KEEP_BEHIND_AFTER_TRIM
        var cut = target
        var front: ListRef? = null
        var whole = false
        synchronized(listStarts) {
            // The list start nearest below the target, else above it, never
            // keeping more than MAX_BEHIND and never past the current track.
            val at = (target downTo least).firstOrNull { listStarts[queue[it]] != null }
                ?: (target + 1..index).firstOrNull { listStarts[queue[it]] != null }
            if (at != null) {
                cut = at
                front = listStarts[queue[at]]
                whole = true
            } else {
                // The list the new front is in: the last to start before it.
                front = (cut - 1 downTo 0).firstNotNullOfOrNull { listStarts[queue[it]] }
            }
            for (i in 0 until cut) listStarts.remove(queue[i])
        }
        val kept = queue.subList(cut, queue.size).toList()
        val lists = context?.copy(head = front ?: context.head, headCut = !whole)
        return Trim(cut, kept, index - cut, lists)
    }

    /** This list without one occurrence (by identity) of each of [dropped]. */
    private fun List<Track>.withoutOnce(dropped: List<Track>): List<Track> {
        if (dropped.isEmpty() || isEmpty()) return this
        val left = IdentityHashMap<Track, Int>(dropped.size)
        for (t in dropped) left[t] = (left[t] ?: 0) + 1
        return filter { t ->
            val n = left[t] ?: 0
            if (n > 0) left[t] = n - 1
            n == 0
        }
    }

    companion object {
        /** More songs than this played behind the one playing, and the oldest are dropped ([trimmed]). */
        const val MAX_BEHIND = 2_000

        /** About how many are left behind it then. */
        const val KEEP_BEHIND_AFTER_TRIM = 1_500
    }
}
