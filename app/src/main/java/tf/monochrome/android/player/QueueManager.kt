package tf.monochrome.android.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
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

    val currentQueue: List<Track> get() = _queue.value
    val currentQueueIndex: Int get() = _currentIndex.value

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        _queue.value = tracks
        originalQueue = tracks
        _currentIndex.value = startIndex
        _shuffleEnabled.value = false
        updateCurrentTrack()
    }

    fun playTrackInQueue(track: Track, tracks: List<Track>) {
        val index = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        setQueue(tracks, index)
    }

    fun addToQueue(tracks: List<Track>) {
        _queue.value = _queue.value + tracks
        if (_shuffleEnabled.value) {
            originalQueue = originalQueue + tracks
        }
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

        val currentTrack = queue.getOrNull(_currentIndex.value)

        val item = queue.removeAt(fromIndex)
        queue.add(toIndex, item)

        _queue.value = queue
        _currentIndex.value = currentTrack
            ?.let { track -> queue.indexOfFirst { it.id == track.id } }
            ?.takeIf { it >= 0 }
            ?: _currentIndex.value.coerceIn(0, queue.lastIndex)
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
        updateCurrentTrack()
    }

    fun skipToIndex(index: Int) {
        if (index < 0 || index >= _queue.value.size) return
        _currentIndex.value = index
        updateCurrentTrack()
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
        _currentTrack.value = null
    }

    fun hasNext(): Boolean {
        return when (_repeatMode.value) {
            RepeatMode.ONE, RepeatMode.ALL -> _queue.value.isNotEmpty()
            RepeatMode.OFF -> _currentIndex.value < _queue.value.size - 1
        }
    }

    fun hasPrevious(): Boolean {
        return when (_repeatMode.value) {
            RepeatMode.ALL -> _queue.value.isNotEmpty()
            else -> _currentIndex.value > 0
        }
    }

    private fun updateCurrentTrack() {
        val index = _currentIndex.value
        val queue = _queue.value
        _currentTrack.value = if (index in queue.indices) queue[index] else null
    }
}
