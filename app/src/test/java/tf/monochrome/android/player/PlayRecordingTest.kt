package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.Track

/** Which track change is a play, for Recently Played. */
class PlayRecordingTest {

    private val stream = Track(id = 1234L, title = "Streamed")
    // A local file's queue id is a hash; it plays under its library id.
    private val local = Track(id = -987654321L, title = "Local")
    private val unifiedIds = mapOf(local.id to "local:42")
    private val queue = listOf(stream, local)

    private fun find(mediaId: String, current: Track?) =
        PlayRecording.trackFor(mediaId, current, queue) { unifiedIds[it] }

    @Test
    fun `a local song is found by the library id it plays under`() {
        // The old lookup read the media id as a number: "local:42" never matched.
        assertEquals(local, find("local:42", current = local))
        assertEquals(local, find("local:42", current = stream))
    }

    @Test
    fun `a stream is found by its numeric id`() {
        assertEquals(stream, find("1234", current = stream))
        assertEquals(stream, find("1234", current = local))
    }

    @Test
    fun `a blank id is the current track, and a stranger is nobody`() {
        assertEquals(stream, find("", current = stream))
        assertNull(find("not-in-the-queue", current = stream))
    }

    @Test
    fun `the same play started again is counted once`() {
        val d = PlayDeduper(windowMs = 15_000)
        assertTrue(d.shouldRecord(1, nowMs = 0, repeat = false))
        assertFalse("an error retry", d.shouldRecord(1, nowMs = 2_000, repeat = false))
        assertTrue("another song", d.shouldRecord(2, nowMs = 3_000, repeat = false))
        assertTrue("back to the first", d.shouldRecord(1, nowMs = 4_000, repeat = false))
        assertTrue("later, the same song again", d.shouldRecord(1, nowMs = 60_000, repeat = false))
    }

    @Test
    fun `a repeat is a play of its own`() {
        val d = PlayDeduper(windowMs = 15_000)
        assertTrue(d.shouldRecord(7, nowMs = 0, repeat = false))
        assertTrue(d.shouldRecord(7, nowMs = 1_000, repeat = true))
    }
}
