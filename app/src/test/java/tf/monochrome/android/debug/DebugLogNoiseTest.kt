package tf.monochrome.android.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the debug log throws away before it reaches the buffer.
 *
 * The Errors tab is where you look when something is wrong, so what lands there
 * is the part of this filter that matters. On a ColorOS device it was entirely
 * vendor chatter logged at ERROR — a view-mirroring manager declining to handle
 * an ordinary window, an AudioTrack extension reporting no fade type — while
 * the app was working perfectly.
 *
 * The last test is the one to keep: native logging tags its lines with the
 * process name, so the codec line arrives under our own applicationId, and a
 * rule that dropped that tag wholesale would take real linker and ART failures
 * with it. It is matched on the message instead, and this proves the difference.
 */
class DebugLogNoiseTest {

    private val collector = DebugLogCollector(DebugLogBuffer())

    private fun line(level: Char, tag: String, message: String) =
        DebugLogEntry("09-12 19:50:51.074", 928, 928, level, tag, message, "")

    @Test
    fun `the OPlus view-mirror error is dropped`() {
        assertTrue(
            collector.isNoise(
                line('E', "OplusBracketLog", "[OplusViewMirrorManager] updateHostViewRootIfNeeded, not support android.view.ViewRootImpl@a909b6")
            )
        )
    }

    @Test
    fun `the vendor AudioTrack fade error is dropped`() {
        assertTrue(collector.isNoise(line('E', "AudioTrackExtImpl", "get fade type, but return null, pid 928, uid 10747")))
    }

    @Test
    fun `the codec resource query is dropped by message, under our own tag`() {
        assertTrue(
            collector.isNoise(
                line('E', "notrypt.android", "Failed to query component interface for required system resources: 6")
            )
        )
    }

    @Test
    fun `a real native error under the same tag survives`() {
        // The reason the codec line is matched on its message and not its tag.
        assertFalse(collector.isNoise(line('E', "notrypt.android", "dlopen failed: library \"libfoo.so\" not found")))
    }

    @Test
    fun `our own errors are never noise`() {
        assertFalse(collector.isNoise(line('E', "MediaScanner", "Scan failed: permission denied")))
    }

    @Test
    fun `a threadtime line parses into its parts`() {
        val entry = collector.parse("09-12 19:50:51.107  1234  5678 E AudioTrackExtImpl: get fade type, but return null")
        assertEquals("AudioTrackExtImpl", entry.tag)
        assertEquals('E', entry.level)
        assertEquals(1234, entry.pid)
        assertEquals("get fade type, but return null", entry.message)
    }
}
