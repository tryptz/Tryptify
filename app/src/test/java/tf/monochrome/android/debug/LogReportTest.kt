package tf.monochrome.android.debug

import android.app.ApplicationExitInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LogReportTest {

    private fun line(n: Int, message: String = "line $n") = DebugLogEntry(
        timestamp = "11-18 10:00:00.%03d".format(n), pid = 1, tid = 1, level = 'D', tag = "Player", message = message,
        prefix = "11-18 10:00:00.%03d     1     1 D Player: ".format(n),
    )

    @Test
    fun `a log that fits is sent whole, as logcat printed it`() {
        val text = logReportLog(listOf(line(1), line(2)))
        assertEquals(
            "11-18 10:00:00.001     1     1 D Player: line 1\n11-18 10:00:00.002     1     1 D Player: line 2\n",
            text,
        )
    }

    @Test
    fun `a log too long keeps its newest lines and says how many it left out`() {
        // From 100 on, so every line is as long as the next.
        val lines = List(100) { line(100 + it) }
        val each = lines.first().raw.length + 1
        val text = logReportLog(lines, limit = each * 10 + 5)
        val sent = text.trimEnd('\n').split('\n')
        assertEquals("(90 earlier lines left out: the log was too long to send whole)", sent.first())
        assertEquals(lines.takeLast(10).map { it.raw }, sent.drop(1))
        // Nothing fits: nothing but the count, never half a line.
        assertEquals("(100 earlier lines left out: the log was too long to send whole)\n", logReportLog(lines, limit = 10))
    }

    @Test
    fun `the default limit is under what the table takes`() {
        val sql = File("../docs/supabase/log_reports.sql").readText()
        val takes = Regex("""char_length\(log\) <= (\d+)""").find(sql)!!.groupValues[1].toInt()
        // The first line saying what was left out comes on top of the limit.
        assertTrue(LOG_REPORT_LIMIT + 200 <= takes)
    }

    @Test
    fun `a sign-in in a line never reaches the sent log`() {
        val buffer = DebugLogBuffer()
        buffer.append(line(1, message = "GET https://example.com/rest?t=abcdef0123456789abcdef0123456789&s=salt12"))
        val text = logReportLog(buffer.snapshot())
        assertTrue(text, "abcdef0123456789abcdef0123456789" !in text)
        assertTrue(text, "example.com" in text)
    }

    @Test
    fun `what the listener wrote is trimmed, cut and cleared of a pasted sign-in`() {
        assertEquals("it stutters", logReportNote("  it stutters \n"))
        assertEquals(2_000, logReportNote("x".repeat(5_000)).length)
        val pasted = logReportNote("fails on https://music.example/rest/stream?id=7&token=abcdef0123456789")
        assertTrue(pasted, "abcdef0123456789" !in pasted)
        assertTrue("a NUL would be refused by the table", '\u0000' !in logReportNote("a\u0000b"))
    }

    @Test
    fun `the row has the table's columns and no other`() {
        val row = LogReportRow(
            user_id = "u", manufacturer = "Maker", model = "Phone 1", device = "phone1", android_release = "16",
            android_sdk = 36, app_version = "1.9.52", app_version_code = 197, git_commit = "abc1234", note = "",
            context = JsonObject(emptyMap()), previous_exits = JsonArray(emptyList()), log = "",
        )
        val sent = Json.encodeToJsonElement(LogReportRow.serializer(), row).jsonObject.keys
        val sql = File("../docs/supabase/log_reports.sql").readText()
        val table = sql.substringAfter("create table if not exists public.log_reports (").substringBefore("\n);")
        val columns = table.lines().mapNotNull { Regex("""^\s{4}(\w+)\s""").find(it)?.groupValues?.get(1) }.toSet()
        assertTrue("sent but not a column: ${sent - columns}", columns.containsAll(sent))
        // What the table works out for itself is never sent: a phone cannot write its own analysis.
        val worked = setOf("id", "created_at", "line_count", "severity", "headline", "signature", "analysis", "analyzed_at")
        assertEquals(columns - worked, sent)
    }

    @Test
    fun `exit reasons are named as Android names them`() {
        assertEquals("CRASH", exitReasonName(ApplicationExitInfo.REASON_CRASH))
        assertEquals("CRASH_NATIVE", exitReasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("ANR", exitReasonName(ApplicationExitInfo.REASON_ANR))
        assertEquals("LOW_MEMORY", exitReasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("EXCESSIVE_RESOURCE_USAGE", exitReasonName(ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
        assertEquals("INITIALIZATION_FAILURE", exitReasonName(ApplicationExitInfo.REASON_INITIALIZATION_FAILURE))
        assertEquals("USER_REQUESTED", exitReasonName(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertEquals("UNKNOWN_99", exitReasonName(99))
        // The names the table's analysis acts on are names this gives.
        val sql = File("../docs/supabase/log_reports.sql").readText()
        val named = (1..16).map(::exitReasonName).toSet()
        for (reason in listOf("CRASH_NATIVE", "ANR", "LOW_MEMORY", "EXCESSIVE_RESOURCE_USAGE", "INITIALIZATION_FAILURE")) {
            assertTrue(reason, reason in named && "'$reason'" in sql)
        }
    }

    @Test
    fun `the last crash is read back as it was kept`() {
        val stack = "java.lang.IllegalStateException: boom\n\tat tf.monochrome.android.Foo.bar(Foo.kt:12)\n"
        val kept = parseLastCrash(formatLastCrash(1_763_460_000_000, "main", stack))!!
        assertEquals(1_763_460_000_000, kept.atMs)
        assertEquals("main", kept.thread)
        assertEquals(stack, kept.stack)
        // A thread's name cannot push the stack down a line, and a long stack is cut.
        assertEquals("a b", parseLastCrash(formatLastCrash(1, "a\nb", "s"))!!.thread)
        assertEquals(LogReport.LAST_CRASH_CHARS, parseLastCrash(formatLastCrash(1, "t", "x".repeat(50_000)))!!.stack.length)
        assertNull(parseLastCrash(""))
        assertNull(parseLastCrash("not a time\nmain\nstack"))
    }
}
