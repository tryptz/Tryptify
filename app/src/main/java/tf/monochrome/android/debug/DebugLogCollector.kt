package tf.monochrome.android.debug

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Long-running subprocess reader that pipes `logcat -v threadtime --pid=<us>`
 * into [DebugLogBuffer]. Captures everything the Android logger sees for our
 * process — framework warnings, our own Log.* calls, native crash traces,
 * StrictMode violations — with no changes to existing logging code.
 *
 * API 24+ only allows an app to read its own pid's output (security hardening
 * from Nougat onwards), which is exactly what we want: `--pid=<us>` both scopes
 * the stream to our process and keeps the approach portable without adding the
 * `READ_LOGS` permission.
 */
@Singleton
class DebugLogCollector @Inject constructor(
    private val buffer: DebugLogBuffer,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var process: Process? = null

    /** Idempotent; safe to call multiple times. Starts at app boot. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    /** Only used by unit-test / teardown paths — the collector normally runs for the whole process lifetime. */
    fun stop() {
        job?.cancel()
        job = null
        process?.destroy()
        process = null
    }

    private suspend fun run() {
        val pid = android.os.Process.myPid()
        // `*:V` = every tag at Verbose and above. `-T 1` starts from the tail so
        // we don't replay megabytes of framework output from before this boot.
        // `--pid` scopes to our own process on API 24+.
        val cmd = arrayOf(
            "logcat",
            "-v", "threadtime",
            "--pid=$pid",
            "-T", "1",
            "*:V",
        )
        val proc = try {
            Runtime.getRuntime().exec(cmd)
        } catch (e: IOException) {
            // Device doesn't have logcat in PATH or blocks Runtime.exec — give up
            // silently. The UI will simply show an empty buffer.
            return
        }
        process = proc
        try {
            proc.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!kotlin.coroutines.coroutineContext.isActive) break
                    if (line.isEmpty()) continue
                    buffer.append(parse(line))
                }
            }
        } catch (_: IOException) {
            // Stream closed — subprocess exited. Fall through and let the
            // coroutine complete; caller can restart if desired.
        } finally {
            runCatching { proc.destroy() }
            process = null
        }
    }

    /**
     * Parse a single `-v threadtime` line:
     * `MM-DD HH:MM:SS.SSS  PID  TID LVL TAG: MESSAGE`
     *
     * Returns a best-effort entry; anything that doesn't match is preserved
     * verbatim as an INFO line so nothing is silently dropped.
     */
    private fun parse(line: String): DebugLogEntry {
        val match = THREADTIME_REGEX.matchEntire(line)
        if (match == null) {
            return DebugLogEntry(
                timestamp = "",
                pid = 0,
                tid = 0,
                level = 'I',
                tag = "?",
                message = line,
                raw = line,
            )
        }
        val (ts, pidStr, tidStr, levelStr, tag, message) = match.destructured
        return DebugLogEntry(
            timestamp = ts,
            pid = pidStr.toIntOrNull() ?: 0,
            tid = tidStr.toIntOrNull() ?: 0,
            level = levelStr.firstOrNull() ?: 'I',
            tag = tag.trim(),
            message = message,
            raw = line,
        )
    }

    private companion object {
        /**
         * threadtime format from AOSP's `logcat.cpp`:
         *   `01-02 03:04:05.678  1234  5678 I SomeTag: body`
         */
        private val THREADTIME_REGEX = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+([^:]+):\s?(.*)$"""
        )
    }
}
