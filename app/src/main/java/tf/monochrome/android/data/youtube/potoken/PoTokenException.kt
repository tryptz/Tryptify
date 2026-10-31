// SPDX-License-Identifier: GPL-3.0-or-later
// Adapted from NewPipe (TeamNewPipe/NewPipe), GPL-3.0-or-later:
// app/src/main/java/org/schabi/newpipe/util/potoken/PoTokenException.kt (master, 00acf2f3).
package tf.monochrome.android.data.youtube.potoken

/** A PO token could not be made this time; the next attempt may succeed. */
internal class PoTokenException(message: String) : Exception(message)

/**
 * The phone's WebView cannot run BotGuard at all (too old for its
 * JavaScript, or broken), so no token is tried again until the app restarts.
 */
internal class BadWebViewException(message: String) : Exception(message)

/**
 * A syntax error means the WebView does not understand the page's
 * JavaScript, which no retry fixes; anything else may pass next time.
 */
internal fun buildExceptionForJsError(error: String): Exception =
    if (error.contains("SyntaxError")) BadWebViewException(error) else PoTokenException(error)
