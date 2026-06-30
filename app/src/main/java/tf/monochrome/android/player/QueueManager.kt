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

    private val _playHistory = MutableStateFlow<List<Track>>(emptyList())
    val playHistory: StateFlow<List<Track>> = _playHistory.asStateFlow()

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
        originalQueue = _queue.value
    }

    fun removeFromQueue(index: Int) {
        removeAt(index)
    }

    fun clearUpcoming() {
        val current = _currentIndex.value
        if (current < 0) {
            _queue.value = emptyList()
            originalQueue = emptyList()
            _currentIndex.value = -1
            _currentTrack.value = null
            return
        }

        val currentTrack = _queue.value.getOrNull(current) ?: return
        _queue.value = listOf(currentTrack)
        originalQueue = _queue.value
        _currentIndex.value = 0
        updateCurrentTrack()
    }

    fun removeAt(index: Int) {
        if (index < 0 || index >= _queue.value.size) return
        val currentList = _queue.value.toMutableList()
        val current = _currentIndex.value
        currentList.removeAt(index)
        _queue.value = currentList
        originalQueue = currentList

        _currentIndex.value = when {
            currentList.isEmpty() -> -1
            index < current -> current - 1
            index == current -> current.coerceAtMost(currentList.lastIndex)
            else -> current
        }
        updateCurrentTrack()
    }

    fun removeMany(indices: Set<Int>) {
        if (indices.isEmpty()) return

        val queue = _queue.value
        val validIndices = indices.filterTo(mutableSetOf()) { it in queue.indices }
        if (validIndices.isEmpty()) return

        val current = _currentIndex.value
        val currentTrack = queue.getOrNull(current)
        val newQueue = queue.filterIndexed { index, _ -> index !in validIndices }

        val newCurrent = when {
            newQueue.isEmpty() -> -1
            currentTrack == null -> -1
            current in validIndices -> current.coerceAtMost(newQueue.lastIndex)
            else -> newQueue.indexOfFirst { it.id == currentTrack.id }
                .takeIf { it >= 0 }
                ?: current.coerceAtMost(newQueue.lastIndex)
        }

        _queue.value = newQueue
        originalQueue = newQueue
        _currentIndex.value = newCurrent
        updateCurrentTrack()
    }

    fun move(fromIndex: Int, toIndex: Int) {
        val currentQueue = _queue.value
        if (fromIndex !in currentQueue.indices || toIndex !in currentQueue.indices) return
        if (fromIndex == toIndex) return

        val queue = currentQueue.toMutableList()
        val currentTrack = queue.getOrNull(_currentIndex.value)
        val item = queue.removeAt(fromIndex)
        queue.add(toIndex, item)

        _queue.value = queue
        originalQueue = queue
        _currentIndex.value = currentTrack
            ?.let { track -> queue.indexOfFirst { it.id == track.id } }
            ?.takeIf { it >= 0 }
            ?: _currentIndex.value.coerceIn(0, queue.lastIndex)
        updateCurrentTrack()
    }

    fun moveToPlayNext(index: Int) {
        val queue = _queue.value
        val current = _currentIndex.value
        if (index !in queue.indices || current !in queue.indices || index == current) return
        val target = (current + 1).coerceAtMost(queue.lastIndex)
        move(index, target)
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
        _playHistory.value = emptyList()
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
        val nextTrack = if (index in queue.indices) queue[index] else null
        _currentTrack.value = nextTrack
        if (nextTrack != null && _playHistory.value.lastOrNull()?.id != nextTrack.id) {
            _playHistory.value = (_playHistory.value + nextTrack).takeLast(MAX_PLAY_HISTORY)
        }
    }

    private companion object {
        const val MAX_PLAY_HISTORY = 20
    }
}
