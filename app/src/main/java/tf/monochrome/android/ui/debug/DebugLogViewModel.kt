package tf.monochrome.android.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.debug.DebugLogBuffer
import tf.monochrome.android.debug.DebugLogEntry
import javax.inject.Inject

@HiltViewModel
class DebugLogViewModel @Inject constructor(
    private val buffer: DebugLogBuffer,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _levelFilter = MutableStateFlow(LevelFilter.ALL)
    val levelFilter: StateFlow<LevelFilter> = _levelFilter.asStateFlow()

    private val numbering = LogNumbering()

    /**
     * Snapshot emissions, throttled to 4 Hz so a burst of logcat lines doesn't
     * trigger 1 000 recompositions per second on the viewer screen. Copied and
     * numbered off the main thread: the buffer holds up to ten thousand lines.
     */
    private val snapshots: StateFlow<LogLines> =
        buffer.updates
            .sample(SAMPLE_MS)
            .onStart { emit(Unit) }
            .map { numbering.number(buffer.snapshot()) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogLines.EMPTY)

    /** What the list shows, filtered off the main thread too: a search runs over every line. */
    val entries: StateFlow<LogLines> =
        combine(snapshots, _query, _levelFilter) { all, q, filter ->
            val needle = q.trim()
            if (filter == LevelFilter.ALL && needle.isEmpty()) {
                all
            } else {
                all.filter { entry ->
                    (filter == LevelFilter.ALL || entry.level in filter.levels) &&
                        (needle.isEmpty() ||
                            entry.tag.contains(needle, ignoreCase = true) ||
                            entry.message.contains(needle, ignoreCase = true))
                }
            }
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogLines.EMPTY)

    val totalSize: StateFlow<Int> =
        snapshots.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setQuery(value: String) { _query.value = value }
    fun setLevelFilter(value: LevelFilter) { _levelFilter.value = value }
    fun clear() = buffer.clear()
    fun exportText(): String = buffer.dumpAsText()

    private companion object {
        const val SAMPLE_MS = 250L
    }
}

/**
 * Log lines with a key each that stays the line's for as long as it is in the
 * buffer, for the list to key its rows by. By position the key of every row
 * moved whenever the full buffer dropped its oldest line — every visible row
 * was redrawn, four times a second.
 */
class LogLines internal constructor(
    val entries: List<DebugLogEntry>,
    private val keys: LongArray,
) {
    val size: Int get() = entries.size
    val lastIndex: Int get() = entries.lastIndex
    fun isEmpty(): Boolean = entries.isEmpty()
    operator fun get(index: Int): DebugLogEntry = entries[index]
    fun keyAt(index: Int): Long = keys[index]

    /** The lines [keep] says yes to, each keeping its key. */
    internal fun filter(keep: (DebugLogEntry) -> Boolean): LogLines {
        val kept = ArrayList<DebugLogEntry>()
        val keptKeys = LongArray(entries.size)
        for (i in entries.indices) {
            val entry = entries[i]
            if (keep(entry)) {
                keptKeys[kept.size] = keys[i]
                kept.add(entry)
            }
        }
        return LogLines(kept, keptKeys.copyOf(kept.size))
    }

    // Two snapshots holding the same lines are the same list: the keys say so
    // without comparing every line's text, and an unchanged filter result then
    // emits nothing.
    override fun equals(other: Any?): Boolean = other is LogLines && keys.contentEquals(other.keys)
    override fun hashCode(): Int = keys.contentHashCode()

    companion object {
        val EMPTY = LogLines(emptyList(), LongArray(0))
    }
}

/**
 * Hands every line the next number the first time a snapshot shows it.
 *
 * The buffer only ever appends at the end and drops from the front, and each
 * line is its own object, so a new snapshot's first line is found in the last
 * one by identity: everything before it was dropped, everything from it on
 * keeps the number it had. A first line the last snapshot never held means it
 * was all dropped (the buffer was cleared), and numbering carries on past it.
 */
internal class LogNumbering {
    private var previous: List<DebugLogEntry> = emptyList()
    private var previousFirst = 0L

    fun number(snapshot: List<DebugLogEntry>): LogLines {
        val head = snapshot.firstOrNull()
        val dropped = if (head == null) -1 else previous.indexOfFirst { it === head }
        val first = previousFirst + if (dropped >= 0) dropped else previous.size
        previous = snapshot
        previousFirst = first
        return LogLines(snapshot, LongArray(snapshot.size) { first + it })
    }
}

enum class LevelFilter(val label: String, val levels: Set<Char>) {
    ALL("All", setOf('V', 'D', 'I', 'W', 'E', 'F')),
    INFO_PLUS("Info+", setOf('I', 'W', 'E', 'F')),
    WARN_PLUS("Warn+", setOf('W', 'E', 'F')),
    ERROR_ONLY("Errors", setOf('E', 'F')),
}
