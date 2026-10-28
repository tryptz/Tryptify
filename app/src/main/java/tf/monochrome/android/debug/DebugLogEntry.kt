package tf.monochrome.android.debug

/**
 * One line from the process logcat stream. `level` uses the single-char codes
 * logcat emits: V / D / I / W / E / F / S.
 *
 * The line as logcat printed it is [raw], so the export round-trips exactly
 * what logcat wrote even if our parser misread something. It is not stored
 * whole: it is [prefix] and [message] put together, and only the prefix (the
 * date, ids, level and tag, as spaced in the line) is kept, since a second copy
 * of every message was a third of the buffer. [line] holds the whole line
 * instead: as the parser hands it over, and in the buffer only when it is not
 * those two put together, which taking a sign-in out of it can make it (see
 * DebugLogBuffer.append).
 */
data class DebugLogEntry(
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: Char,
    val tag: String,
    val message: String,
    val prefix: String = "",
    val line: String? = null,
) {
    /** The line as logcat printed it. */
    val raw: String get() = line ?: (prefix + message)
}
