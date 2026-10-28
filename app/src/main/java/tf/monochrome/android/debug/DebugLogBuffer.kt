package tf.monochrome.android.debug

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory ring buffer of parsed logcat lines. Written by [DebugLogCollector]
 * from a background coroutine; read by the debug log viewer UI through
 * [snapshot] / [updates]. Bounded to [MAX_ENTRIES] so the process RAM cost
 * stays under a few MB regardless of how long the app has been up.
 */
@Singleton
class DebugLogBuffer @Inject constructor() {

    private val lock = Any()
    private val entries = ArrayDeque<DebugLogEntry>(INITIAL_CAPACITY)

    /**
     * Coalescing change notification. `extraBufferCapacity` + DROP_OLDEST so a
     * busy logcat stream never back-pressures the collector coroutine; the UI
     * samples this at a UI-friendly rate.
     */
    private val _updates = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val updates: SharedFlow<Unit> = _updates.asSharedFlow()

    fun append(entry: DebugLogEntry) {
        // Sign-ins never reach the buffer, so the viewer, the export and crash files never see them.
        val message = LogRedactor.redact(entry.message)
        val line = LogRedactor.redact(entry.raw)
        // The line is kept as what comes before its message, the message being
        // kept anyway. A redaction that ran across the two would make the line
        // no longer end with the message; then it is kept whole, so the export
        // is always exactly the redacted line.
        val clean = if (line.endsWith(message)) {
            entry.copy(message = message, prefix = line.substring(0, line.length - message.length), line = null)
        } else {
            entry.copy(message = message, prefix = "", line = line)
        }
        synchronized(lock) {
            entries.addLast(clean)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
        _updates.tryEmit(Unit)
    }

    fun snapshot(): List<DebugLogEntry> = synchronized(lock) { entries.toList() }

    fun size(): Int = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
        _updates.tryEmit(Unit)
    }

    /** Serialize the buffer as a plain-text logcat dump suitable for export. */
    fun dumpAsText(): String = snapshot().joinToString("\n") { it.raw }

    companion object {
        const val MAX_ENTRIES = 10_000
        private const val INITIAL_CAPACITY = 1024
    }
}
