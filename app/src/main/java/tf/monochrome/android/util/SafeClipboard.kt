// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log

/**
 * The clipboard, without crashing on a large copy. A clip goes to the system
 * in one binder transaction, which holds about a megabyte; a string is two
 * bytes a character in it, so a copy past roughly half a million characters
 * throws TransactionTooLargeException from setPrimaryClip and takes the app
 * down with it (the debug log's Copy did, at 1.3 MB). Anything that can grow
 * goes to a file instead (the backup export, the log's Export); what is copied
 * is kept under [MAX_CHARS].
 */
object SafeClipboard {

    /** What a copy is kept under: well inside the transaction, with room for the rest of it. */
    const val MAX_CHARS = 100_000

    /** Copies [text]; false when the system refused it. */
    fun copy(context: Context, label: String, text: String): Boolean = try {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        true
    } catch (e: RuntimeException) {
        Log.w(TAG, "clipboard refused ${text.length} characters: ${e.message}")
        false
    }

    /**
     * The end of [text] in at most [maxChars], cut at a line's start so no line
     * is half there; the whole of it when it fits. For a log, whose newest
     * lines are last and matter most.
     */
    fun newestLinesThatFit(text: String, maxChars: Int = MAX_CHARS): String {
        if (text.length <= maxChars) return text
        val from = text.length - maxChars
        val lineStart = text.indexOf('\n', from)
        return if (lineStart in from until text.length - 1) text.substring(lineStart + 1) else text.substring(from)
    }

    private const val TAG = "SafeClipboard"
}
