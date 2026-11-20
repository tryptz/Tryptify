package tf.monochrome.android.audio.usb

import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A note on disk while the app takes the USB DAC, so a restart of the whole
 * phone in that moment is known afterwards.
 *
 * A phone restarted when the Quick Settings tile took the DAC back
 * (2026-11-20), and nothing of it could be read: the app's log is in memory
 * and dies with the phone. [write] puts the note down, synced, before the
 * interfaces are claimed; [clear] takes it away once [WATCH_MS] have passed
 * without trouble (at once when the claim failed). A note still there at the
 * next start was written by a process that never got to clear it. Written
 * in an earlier boot, the phone restarted; in this one, the app's own
 * process ended. [atStart] says which, for the log, and removes it.
 *
 * Plain file I/O, no Android: the boot is given as the phone's boot count
 * and the wall-clock time the phone booted, either of which may be unknown.
 */
internal class DacClaimNote(private val file: File) {

    /** Who this boot is: Android's boot count (or -1) and when it booted, in wall-clock ms. */
    data class Boot(val count: Int, val bootedAtMs: Long)

    /** What a note left from before says: the whole phone restarted, or only the app's process ended. */
    data class Report(val phoneRestarted: Boolean, val text: String)

    /** Puts the note down, flushed to the disk, before a claim. Never throws. */
    fun write(boot: Boot, nowMs: Long, detail: String) {
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                out.write(encode(boot, nowMs, detail).toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }

    /** The claim went by without trouble, or failed: there is nothing to report. Never throws. */
    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * At the app's start: what a note left from before says, or null when
     * there is none. The note is gone afterwards either way.
     */
    fun atStart(now: Boot): Report? {
        val text = runCatching { if (file.exists()) file.readText(Charsets.UTF_8) else null }.getOrNull()
        clear()
        return text?.let { report(it, now) }
    }

    companion object {
        /** How long after a claim a restart is still put down to it. */
        const val WATCH_MS = 15_000L

        /** Boots further apart than this, by when they started, are not the same one. */
        private const val SAME_BOOT_SLACK_MS = 60_000L

        internal fun encode(boot: Boot, nowMs: Long, detail: String): String =
            "boot=${boot.count}\nbootedAt=${boot.bootedAtMs}\nat=$nowMs\n" +
                "detail=${detail.replace('\n', ' ')}\n"

        /** What the note [text] says to a process started in [now], or null if it is unreadable. */
        internal fun report(text: String, now: Boot): Report? {
            val fields = text.lineSequence()
                .mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }
                .toMap()
            val count = fields["boot"]?.toIntOrNull() ?: return null
            val bootedAt = fields["bootedAt"]?.toLongOrNull() ?: return null
            val at = fields["at"]?.toLongOrNull() ?: return null
            val detail = fields["detail"].orEmpty()
            val when_ = utc(at)
            return if (restarted(Boot(count, bootedAt), now)) {
                Report(
                    phoneRestarted = true,
                    "The phone restarted within ${WATCH_MS / 1000} s of the app taking the USB DAC " +
                        "(claim at $when_ UTC: $detail)",
                )
            } else {
                Report(
                    phoneRestarted = false,
                    "The app's process ended within ${WATCH_MS / 1000} s of taking the USB DAC, " +
                        "the phone kept running (claim at $when_ UTC: $detail)",
                )
            }
        }

        /** Whether [then] and [now] are different boots of the phone. */
        internal fun restarted(then: Boot, now: Boot): Boolean =
            if (then.count >= 0 && now.count >= 0) then.count != now.count
            else kotlin.math.abs(then.bootedAtMs - now.bootedAtMs) > SAME_BOOT_SLACK_MS

        private fun utc(ms: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
    }
}
