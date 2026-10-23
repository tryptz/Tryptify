package tf.monochrome.android.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tf.monochrome.android.connect.ConnectTrack

/** Another device's song in the mini player, and the times on its timeline. */
class ConnectRemoteTest {

    @Test
    fun `times read as m ss, and h mm ss past an hour`() {
        assertEquals("0:00", clockOf(-3.0))
        assertEquals("1:05", clockOf(65.9))
        assertEquals("1:02:03", clockOf(3723.0))
    }

    @Test
    fun `the song says where it plays, and a new song is a new one`() {
        val t = ConnectTrack(src = "hifi", id = "5", title = "Song", artist = "Swans", album = "Al", duration = 200.0,
            cover = "https://resources.tidal.com/images/a/b/320x320.jpg")
        val shown = t.asShownTrackOn("Playing on Pixel 8")
        assertEquals("Song", shown.title)
        assertEquals("Swans · Playing on Pixel 8", shown.artist?.name)
        assertEquals(200, shown.duration)
        // A full cover link is kept as it is.
        assertEquals(t.cover, shown.coverUrl)
        assertEquals(shown.id, t.copy(title = "Renamed").asShownTrackOn("x").id)
        assertNotEquals(shown.id, t.copy(id = "6").asShownTrackOn("x").id)
        // No artist: the line is just where it plays.
        assertEquals("Playing on Pixel 8", t.copy(artist = "").asShownTrackOn("Playing on Pixel 8").artist?.name)
    }
}
