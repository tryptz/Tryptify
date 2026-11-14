package tf.monochrome.android.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLogTest {

    private fun line(n: Int, level: Char = 'D', tag: String = "Player", message: String = "line $n") =
        DebugLogEntry(timestamp = "11-14 10:00:00.%03d".format(n), pid = 1, tid = 1, level = level, tag = tag, message = message)

    @Test
    fun `the buffer is read by line number, each line once`() {
        val buffer = DebugLogBuffer()
        repeat(5) { buffer.append(line(it)) }
        assertEquals(5L, buffer.appended())
        val first = buffer.since(0, 3)
        assertEquals(listOf("line 0", "line 1", "line 2"), first.entries.map { it.message })
        assertEquals(3L, first.next)
        assertEquals(0L, first.missed)
        val rest = buffer.since(first.next, 100)
        assertEquals(listOf("line 3", "line 4"), rest.entries.map { it.message })
        assertEquals(5L, rest.next)
        // Nothing new: nothing, and the same place.
        val none = buffer.since(rest.next, 100)
        assertTrue(none.entries.isEmpty())
        assertEquals(5L, none.next)
    }

    @Test
    fun `lines the buffer let go of are counted, not lost without a word`() {
        val buffer = DebugLogBuffer()
        repeat(DebugLogBuffer.MAX_ENTRIES + 40) { buffer.append(line(it)) }
        val read = buffer.since(10, 5)
        assertEquals(30L, read.missed)                       // lines 10 to 39 are gone
        assertEquals("line 40", read.entries.first().message)
        assertEquals(45L, read.next)
        // Clearing the log does not start the numbers again.
        buffer.clear()
        buffer.append(line(7))
        val after = buffer.since(read.next, 5)
        assertEquals(listOf("line 7"), after.entries.map { it.message })
        assertEquals(DebugLogBuffer.MAX_ENTRIES + 41L, after.next)
    }

    @Test
    fun `what is sent leaves out the sender's own lines and the verbose ones`() {
        val lines = DebugLogBuffer.Lines(
            entries = listOf(
                line(1),
                line(2, level = 'V'),
                line(3, tag = LiveLog.TAG),
                line(4, level = 'E', message = "x".repeat(9_000)),
            ),
            next = 4,
            missed = 12,
        )
        val rows = liveLogRows(lines, uid = "u", device = "phone", build = "1.9.52 (abc)")
        assertEquals(3, rows.size)
        // What was missed is said first, as a line of its own.
        assertEquals(LiveLog.TAG, rows[0].tag)
        assertTrue(rows[0].message.startsWith("12 lines"))
        assertEquals("line 1", rows[1].message)
        assertEquals("E", rows[2].level)
        assertEquals(4_000, rows[2].message.length)
        assertTrue(rows.all { it.user_id == "u" && it.device == "phone" && it.build == "1.9.52 (abc)" })
    }

    @Test
    fun `a sign-in in a line never reaches a row`() {
        val buffer = DebugLogBuffer()
        buffer.append(line(1, message = "GET https://example.com/rest?t=abcdef0123456789abcdef0123456789&s=salt12"))
        val rows = liveLogRows(buffer.since(0, 10), "u", "phone", "b")
        assertTrue(rows.single().message, "abcdef0123456789abcdef0123456789" !in rows.single().message)
    }
}
