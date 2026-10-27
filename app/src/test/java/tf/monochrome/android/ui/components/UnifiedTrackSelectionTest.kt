package tf.monochrome.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * The selection the Local, My server and local detail lists share. It holds
 * the picked songs themselves, so the paged Songs list can still act on a song
 * whose row has scrolled away.
 */
class UnifiedTrackSelectionTest {

    private fun track(name: String) = UnifiedTrack(
        id = "local_/music/$name.flac",
        title = name,
        durationSeconds = 200,
        artistName = "Swans",
        source = PlaybackSource.LocalFile("/music/$name.flac", AudioCodec.FLAC, 44_100, 16),
        sourceType = SourceType.LOCAL,
    )

    private val a = track("a")
    private val b = track("b")
    private val c = track("c")

    @Test
    fun `selection is active while a song is picked`() {
        val selection = UnifiedTrackSelection()
        assertFalse(selection.active)
        selection.toggle(a)
        assertTrue(selection.active)
        assertTrue(selection.isSelected(a))
        selection.toggle(a)
        assertFalse(selection.active)
        assertFalse(selection.isSelected(a))
    }

    @Test
    fun `without a list the songs come back in the order they were picked`() {
        val selection = UnifiedTrackSelection()
        selection.toggle(c)
        selection.toggle(a)
        assertEquals(listOf(c, a), selection.tracks())
        assertEquals(2, selection.count)
    }

    @Test
    fun `with a list the songs come back in its order`() {
        val selection = UnifiedTrackSelection()
        selection.toggle(c)
        selection.toggle(a)
        assertEquals(listOf(a, c), selection.tracks(listOf(a, b, c)))
    }

    @Test
    fun `an unpicked song is gone from the songs, and clear empties it`() {
        val selection = UnifiedTrackSelection()
        selection.toggle(a)
        selection.toggle(b)
        selection.toggle(a)
        assertEquals(listOf(b), selection.tracks())
        selection.clear()
        assertFalse(selection.active)
        assertEquals(emptyList<UnifiedTrack>(), selection.tracks())
    }
}
