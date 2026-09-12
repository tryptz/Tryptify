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
                    val entry = parse(line)
                    if (isNoise(entry)) continue
                    buffer.append(entry)
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
    internal fun parse(line: String): DebugLogEntry {
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

    /**
     * Filter out framework-internal chatter that floods the in-app debug log
     * without telling us anything we'd act on. PipelineWatcher's
     * "pipelineFull: too many frames in pipeline (N)" is normal Media3
     * codec back-pressure during buffering and prebuffering — not an error.
     * Same idea for the other entries: pure plumbing noise from system
     * components, never our app's behavior.
     *
     * Some of it is logged at ERROR, which matters more than the volume does:
     * the viewer's Errors tab is where you look when something is actually
     * wrong, and a screen of vendor chatter there is worse than a long All tab.
     * Everything dropped below was filling that tab on a ColorOS device while
     * the app was working perfectly.
     */
    internal fun isNoise(entry: DebugLogEntry): Boolean {
        // Drop debug-level lines from these tags entirely.
        if (entry.level == 'D' && entry.tag in NOISE_DEBUG_TAGS) return true
        // Some tags spam at info level too — drop those regardless of level.
        if (entry.tag in NOISE_TAGS_ALL_LEVELS) return true
        // And some arrive under a tag worth keeping — see NOISE_MESSAGES.
        if (NOISE_MESSAGES.any { entry.message.startsWith(it) }) return true
        return false
    }

    private companion object {
        /**
         * threadtime format from AOSP's `logcat.cpp`:
         *   `01-02 03:04:05.678  1234  5678 I SomeTag: body`
         */
        private val THREADTIME_REGEX = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(\d+)\s+(\d+)\s+([VDIWEFS])\s+([^:]+):\s?(.*)$"""
        )

        private val NOISE_DEBUG_TAGS = setOf(
            "PipelineWatcher",       // Media3 codec input/output queue depth
            "AidlBufferPool",        // C2 component buffer pool fetch/transfer
            "BufferPoolAccessor2.0", // older C2 buffer pool name
            "C2SoftMP3:DecImpl",     // C2 codec component init logs
        )

        private val NOISE_TAGS_ALL_LEVELS = setOf(
            "ViewRootImplExtImpl",        // OnePlus / OPlus skin: focus + motion event chatter
            "JankManager",                // OPlus skin: per-frame jank stats
            "[JankManager]",
            "DynamicFramerate",           // OPlus skin: refresh-rate adaptation
            "VRR",                        // OPlus skin: variable refresh rate state
            "OplusScrollToTopManager",    // OPlus skin: focus tracking
            "CoreBackPreview",            // System: predictive back gesture
            // Both of these log at ERROR, several times a minute, on OPlus
            // devices. OplusBracketLog is the skin's view-mirroring manager
            // saying it does not handle a plain ViewRootImpl — which is every
            // window that is not in its split-screen bracket, i.e. ours.
            // AudioTrackExtImpl is the vendor's AudioTrack extension reporting
            // that the platform returned no fade type, once per track start.
            "OplusBracketLog",
            "AudioTrackExtImpl",
        )

        /**
         * Noise that arrives under a tag we cannot drop wholesale.
         *
         * Native logging uses the process name as its tag, so this lands on
         * `notrypt.android` — the tail of our own applicationId — alongside
         * linker and ART messages that would matter. Matched on the message
         * instead, by prefix, so only the known line goes.
         *
         * The codec one is Android 15's `getRequiredSystemResources` query
         * against a component that does not implement it. It is logged once per
         * MediaCodec the player creates, and playback is unaffected.
         */
        private val NOISE_MESSAGES = listOf(
            "Failed to query component interface for required system resources",
        )
    }
}
