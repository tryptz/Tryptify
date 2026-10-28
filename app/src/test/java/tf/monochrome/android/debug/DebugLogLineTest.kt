package tf.monochrome.android.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The debug log's two savings, held to what they replace.
 *
 * Noise is now dropped from the line as logcat prints it, before the regex
 * that splits it up: that check must drop exactly what the regex and the
 * filter after it would, and nothing they would keep. And a line is kept as
 * what comes before its message, the message being kept anyway: the export
 * must still be exactly the line, its sign-ins taken out.
 */
class DebugLogLineTest {

    private val buffer = DebugLogBuffer()
    private val collector = DebugLogCollector(buffer)

    private val threadtime = listOf(
        // Samsung's, 60 to 240 times a second.
        "10-28 12:00:00.123  4321  4321 I View    : setRequestedFrameRate frameRate=60.0, this=v2.s{203ccb3}",
        "10-28 12:00:00.123  4321  4321 I View: setRequestedFrameRate frameRate=NaN",
        "10-28 12:00:00.123  4321  4321 I View    :setRequestedFrameRate frameRate=NaN",
        // The same words later in a message are a different line.
        "10-28 12:00:00.123  4321  4321 I View    : said setRequestedFrameRate frameRate=60",
        "10-28 12:00:00.123  4321  4321 W View    : requestLayout() improperly called",
        "10-28 12:00:00.123  4321  4999 E notrypt.android: Failed to query component interface for required system resources: 6",
        "10-28 12:00:00.123  4321  4999 E notrypt.android: dlopen failed: library \"libfoo.so\" not found",
        "10-28 12:00:00.123  4321  4999 D PipelineWatcher: pipelineFull: too many frames in pipeline (4)",
        "10-28 12:00:00.123  4321  4999 I PipelineWatcher: pipelineFull: too many frames in pipeline (4)",
        "10-28 12:00:00.123  4321  4999 I JankManager: frame stats",
        "10-28 12:00:00.123  4321  4999 E OplusBracketLog: [OplusViewMirrorManager] updateHostViewRootIfNeeded",
        "10-28 12:00:00.123  4321  4999 E MediaScanner: Scan failed: permission denied",
    )

    /** Lines the regex does not split up; the line check leaves them all to it. */
    private val odd = listOf(
        "setRequestedFrameRate frameRate=60.0",
        "--------- beginning of main",
        "10-28 12:00:00.123  4321  4999 I : setRequestedFrameRate frameRate=1",
        "10-28 12:00:00.123  4321 I View    : setRequestedFrameRate frameRate=1",
        "10-28 12:00:00.123  4321  4999 X View    : setRequestedFrameRate frameRate=1",
        "10-28 12:00:00.123  4321  4999 I View    : setRequestedFrameRate frameRate=1\u2028more",
        "10-28 12:00:00.123  4321  4999 I JankManager",
    )

    @Test
    fun `the line check drops exactly what the parse and the filter drop`() {
        for (line in threadtime) {
            assertEquals(line, collector.isNoise(collector.parse(line)), collector.isNoiseLine(line))
        }
    }

    @Test
    fun `a line the regex does not split up is left to the parse and the filter`() {
        // The first is noise all the same, as the whole of an unparsed line is
        // its message; the parse and the filter after it still drop it.
        assertTrue(collector.isNoise(collector.parse(odd[0])))
        for (line in odd) assertFalse(line, collector.isNoiseLine(line))
    }

    @Test
    fun `Samsung's frame rate line goes before it is parsed`() {
        assertTrue(collector.isNoiseLine(threadtime[0]))
        assertFalse(collector.isNoiseLine(threadtime[3]))
    }

    @Test
    fun `the export is the line as logcat printed it, its sign-ins out`() {
        val kept = listOf(
            "10-28 12:00:00.123  4321  4999 I OkHttp  : --> GET https://example.com/rest/ping?u=me&t=abc123&s=salt",
            "10-28 12:00:00.123  4321  4999 W Auth: Bearer abcdefghijklmnop refused",
            // A sign-in in the tag that runs on into the message.
            "10-28 12:00:00.123  4321  4999 I x?t=ab:cd ef",
            "not a threadtime line at all",
        )
        kept.forEach { buffer.append(collector.parse(it)) }
        assertEquals(kept.joinToString("\n") { LogRedactor.redact(it) }, buffer.dumpAsText())
        // And the message the viewer shows has none either.
        assertFalse(buffer.snapshot()[0].message.contains("abc123"))
    }
}
