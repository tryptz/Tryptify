package tf.monochrome.android.ui.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.debug.DebugLogEntry

/**
 * The debug log's row keys. The list keys its rows by them, so a line must
 * keep its key for as long as the buffer holds it — through appends, through
 * the full buffer dropping its oldest lines, through a filter — and no key may
 * ever be handed out twice, even across a clear, or the list throws.
 */
class LogNumberingTest {

    // Identical text on purpose: lines are told apart by identity, not content.
    private fun line(level: Char = 'I', message: String = "same") = DebugLogEntry(
        timestamp = "00:00:00.000",
        pid = 1,
        tid = 1,
        level = level,
        tag = "Tag",
        message = message,
    )

    private fun keys(lines: LogLines) = List(lines.size) { lines.keyAt(it) }

    @Test
    fun `appended lines keep the keys they had`() {
        val numbering = LogNumbering()
        val a = List(3) { line() }
        val first = numbering.number(a)
        val b = a + List(2) { line() }
        val second = numbering.number(b)
        assertEquals(keys(first), keys(second).take(3))
        assertEquals(listOf(0L, 1L, 2L, 3L, 4L), keys(second))
    }

    @Test
    fun `a full buffer dropping its oldest lines leaves the rest where they were`() {
        val numbering = LogNumbering()
        val all = List(10) { line() }
        val before = numbering.number(all.subList(0, 6))
        // Four new lines in, four old ones out: the buffer stays at six.
        val after = numbering.number(all.subList(4, 10))
        assertEquals(keys(before).drop(4), keys(after).take(2))
        assertEquals(listOf(4L, 5L, 6L, 7L, 8L, 9L), keys(after))
    }

    @Test
    fun `a cleared buffer never reuses a key`() {
        val numbering = LogNumbering()
        val old = numbering.number(List(5) { line() })
        numbering.number(emptyList())
        val fresh = numbering.number(List(3) { line() })
        assertTrue(keys(fresh).none { it in keys(old) })
        assertEquals(keys(fresh).toSet().size, fresh.size)
    }

    @Test
    fun `filtering keeps each line's key`() {
        val numbering = LogNumbering()
        val lines = numbering.number(listOf(line('I'), line('E'), line('I'), line('W')))
        val errors = lines.filter { it.level == 'E' || it.level == 'W' }
        assertEquals(listOf(1L, 3L), keys(errors))
        assertEquals(listOf('E', 'W'), errors.entries.map { it.level })
    }

    @Test
    fun `the same lines are the same list, so nothing is re-emitted for them`() {
        val numbering = LogNumbering()
        val lines = List(4) { line() }
        val once = numbering.number(lines)
        val again = numbering.number(lines)
        assertEquals(once, again)
        assertNotEquals(once, numbering.number(lines + line()))
    }
}
