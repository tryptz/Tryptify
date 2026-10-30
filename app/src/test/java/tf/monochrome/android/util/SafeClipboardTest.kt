// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A copy stays inside what the clipboard takes in one go, newest lines first kept, none cut in half. */
class SafeClipboardTest {

    @Test
    fun `what fits is copied whole`() {
        assertEquals("a\nb\nc", SafeClipboard.newestLinesThatFit("a\nb\nc", 100))
    }

    @Test
    fun `a long log keeps its newest whole lines within the limit`() {
        // 30 000 lines, about 1.3 MB: what crashed the debug log's Copy.
        val log = (1..30_000).joinToString("\n") { "10-30 02:29:09.000 3873 3873 I Tag: line $it ".padEnd(44, 'x') }
        val copied = SafeClipboard.newestLinesThatFit(log)
        assertTrue(copied.length <= SafeClipboard.MAX_CHARS)
        assertTrue(copied.endsWith("line 30000 ".padEnd(44 - "10-30 02:29:09.000 3873 3873 I Tag: ".length, 'x')))
        // It starts on a line's start, not in the middle of one.
        assertTrue(copied.startsWith("10-30 02:29:09.000"))
        // Two bytes a character: well inside the binder's megabyte.
        assertTrue(copied.length * 2 < 1_000_000 / 2)
    }

    @Test
    fun `one line longer than the limit is cut to its end`() {
        val line = "y".repeat(500)
        assertEquals("y".repeat(100), SafeClipboard.newestLinesThatFit(line, 100))
    }
}
