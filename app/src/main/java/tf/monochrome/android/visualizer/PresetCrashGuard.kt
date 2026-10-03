package tf.monochrome.android.visualizer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Notices a preset that crashed this device, so it is never loaded again.
 *
 * The host scan ([KnownCrashPresets]) catches what crashes projectM itself. It
 * cannot catch a preset whose shaders kill one vendor's GPU driver, and that
 * crash takes the whole process with it — there is no exception to catch and
 * no later moment in which to react. So the bridge writes the preset it is
 * about to load to [sentinel] before every load, and deletes the file when it
 * is released cleanly. If the next process finds the file, the last one died
 * with that preset on screen.
 *
 * Died is not the same as crashed. On Android 11 and later the exit record for
 * the dead process says why, and only a native crash or an ANR (a preset that
 * hangs the render thread) flags anything; a swipe from recents, a low-memory
 * kill or an app update does not. Older versions keep no record, so there the
 * file alone decides. That is less certain, but a clean exit there still
 * releases the surface, and with it the file, before the process goes.
 */
internal class PresetCrashGuard(private val context: Context, val sentinel: File) {

    /**
     * The preset the previous process died on, if it crashed — as the absolute
     * path the bridge wrote. Consumes the sentinel either way, so the same death
     * is only ever judged once.
     */
    fun takeCrashedPreset(): String? {
        if (!sentinel.exists()) return null
        val text = runCatching { sentinel.readText() }.getOrNull()
        sentinel.delete()
        val record = parseSentinel(text) ?: return null
        val reason = exitReasonOf(record.pid)
        val crashed = isCrashExit(reason, Build.VERSION.SDK_INT)
        Log.w(
            TAG,
            "Process ${record.pid} ended on ${record.presetPath} (exit reason $reason): " +
                if (crashed) "flagging it" else "not a crash, leaving it",
        )
        return record.presetPath.takeIf { crashed }
    }

    /** Why process [pid] of this app ended, or null when the platform keeps no record. */
    private fun exitReasonOf(pid: Int): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        return runCatching {
            am.getHistoricalProcessExitReasons(context.packageName, pid, 1).firstOrNull()?.reason
        }.getOrNull()
    }

    data class SentinelRecord(val pid: Int, val presetPath: String)

    companion object {
        private const val TAG = "PresetCrashGuard"

        /** What the bridge writes: the process id, then the preset path, a line each. */
        fun parseSentinel(text: String?): SentinelRecord? {
            val lines = text?.lines() ?: return null
            val pid = lines.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val path = lines.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return SentinelRecord(pid, path)
        }

        /**
         * Whether a process that ended with exit [reason] crashed, on [sdk].
         *
         * A Java crash ([ApplicationExitInfo.REASON_CRASH]) is not counted: a
         * preset runs in native code and cannot throw one, so a Java crash while
         * the visualizer happened to be open is somebody else's bug, and blaming
         * the preset would hide it for good.
         */
        fun isCrashExit(reason: Int?, sdk: Int): Boolean {
            if (sdk < Build.VERSION_CODES.R) return true
            // Android 11+ but no record for that pid: the record is gone (the
            // log is bounded) or was never written. Same footing as an old
            // platform.
            if (reason == null) return true
            return reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                reason == ApplicationExitInfo.REASON_ANR
        }
    }
}
