package tf.monochrome.android.data.scxyt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SoundCloud links asked for while the song before plays: handed to the
 * play once, and only while there are hours left on them.
 */
class ReadyAheadTest {

    private var now = 1_700_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val ready = ReadyAhead<String>(maxAgeMs = hour, capacity = 2, clock = { now })

    @Test
    fun `the play takes the link once, and the warm-ups before it only look`() {
        ready.put("sc:1", "link-1")
        // The gapless job and both preloads warm the same song: no new ask.
        assertEquals("link-1", ready.peek("sc:1"))
        assertEquals("link-1", ready.peek("sc:1"))
        assertEquals("link-1", ready.take("sc:1"))
        // A play that fails on it asks the server afresh.
        assertNull(ready.take("sc:1"))
        assertNull(ready.peek("sc:1"))
    }

    @Test
    fun `a link is not handed out past its first hour`() {
        ready.put("sc:1", "link-1")
        now += hour
        assertEquals("link-1", ready.peek("sc:1"))
        now += 1
        assertNull(ready.take("sc:1"))
    }

    @Test
    fun `only a few are kept, the oldest going first`() {
        ready.put("sc:1", "link-1")
        ready.put("sc:2", "link-2")
        ready.put("sc:3", "link-3")
        assertNull(ready.peek("sc:1"))
        assertEquals("link-2", ready.take("sc:2"))
        assertEquals("link-3", ready.take("sc:3"))
    }
}
