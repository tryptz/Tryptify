package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/** Another device's song in the mini player, and the times on its timeline. */
class ConnectRemoteTest {

    @Test
    fun `times read as m ss, and h mm ss past an hour`() {
        assertEquals("0:00", clockOf(-3.0))
        assertEquals("1:05", clockOf(65.9))
        assertEquals("1:02:03", clockOf(3723.0))
    }

    @Test
    fun `the mini player's line says where the song plays, and the song stays the same song`() {
        val song = Track(id = 5, title = "Song", duration = 200, artist = Artist(id = 9, name = "Swans"), album = Album(id = 3, title = "Al", cover = "c"))
        val shown = song.shownPlayingOn("Playing on Pixel 8")
        assertEquals("Swans · Playing on Pixel 8", shown.displayArtist)
        // Everything else is the song's own: the same id, so the cover and the like do not start over.
        assertEquals(song, shown.copy(artist = song.artist))
        // Named by its artists only: they are what the line starts with.
        val many = Track(id = 6, title = "Duet", artists = listOf(Artist(id = 1, name = "A"), Artist(id = 2, name = "B")))
        assertEquals("A, B · Playing on Pixel 8", many.shownPlayingOn("Playing on Pixel 8").displayArtist)
        // No artist: the line is just where it plays.
        assertEquals("Playing on Pixel 8", Track(id = 7, title = "x").shownPlayingOn("Playing on Pixel 8").displayArtist)
    }
}
