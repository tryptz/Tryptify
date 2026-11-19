package tf.monochrome.android.data.local.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A moved file is the track it was. What is matched wrongly here hands one
 * song another's likes, charts and corrections; what is missed makes a
 * stranger of a song the listener only moved.
 */
class TrackMovesTest {

    private fun gone(id: Long, path: String, size: Long, modified: Long = 1_700_000_000_000) =
        DepartedTrack(id, path, size, modified, dynamicRange = null)

    private fun here(path: String, size: Long, modified: Long = 1_700_000_000_000) = ArrivedFile(path, size, modified)

    @Test
    fun `a folder moved whole keeps every track`() {
        val departed = listOf(
            gone(1, "/storage/emulated/0/Music/A/01 One.flac", 1000),
            gone(2, "/storage/emulated/0/Music/A/02 Two.flac", 2000),
        )
        val arrived = listOf(
            here("/storage/1A2B-3C4D/Music/A/02 Two.flac", 2000),
            here("/storage/1A2B-3C4D/Music/A/01 One.flac", 1000),
        )
        val moves = TrackMoves.match(departed, arrived)
        assertEquals(1L, moves["/storage/1A2B-3C4D/Music/A/01 One.flac"]?.id)
        assertEquals(2L, moves["/storage/1A2B-3C4D/Music/A/02 Two.flac"]?.id)
    }

    @Test
    fun `a file renamed is known by its size and date`() {
        val moves = TrackMoves.match(
            listOf(gone(7, "/m/track01.mp3", 5000, modified = 1_600_000_000_000)),
            listOf(here("/m/Artist - Song.mp3", 5000, modified = 1_600_000_000_999)),
        )
        assertEquals(7L, moves["/m/Artist - Song.mp3"]?.id)
    }

    @Test
    fun `a copy that lost its date is known by its size and name`() {
        val moves = TrackMoves.match(
            listOf(gone(7, "/old/Song.mp3", 5000, modified = 1_600_000_000_000)),
            listOf(here("/new/song.MP3", 5000, modified = 1_700_000_000_000)),
        )
        assertEquals(7L, moves["/new/song.MP3"]?.id)
    }

    @Test
    fun `another size is another file, whatever it is called`() {
        assertTrue(TrackMoves.match(listOf(gone(1, "/a/Song.mp3", 5000)), listOf(here("/b/Song.mp3", 5001))).isEmpty())
    }

    @Test
    fun `the same size alone is not enough`() {
        val moves = TrackMoves.match(
            listOf(gone(1, "/a/One.mp3", 5000, modified = 1_600_000_000_000)),
            listOf(here("/b/Two.mp3", 5000, modified = 1_700_000_000_000)),
        )
        assertTrue(moves.isEmpty())
    }

    @Test
    fun `an empty file matches nothing`() {
        assertTrue(TrackMoves.match(listOf(gone(1, "/a/x.mp3", 0)), listOf(here("/b/x.mp3", 0))).isEmpty())
    }

    @Test
    fun `each track and each file is matched once, the best evidence first`() {
        // Two copies of one file left; two turned up. Name and date decide which is which.
        val departed = listOf(
            gone(1, "/a/Song.mp3", 5000, modified = 1_000_000),
            gone(2, "/a/Song (copy).mp3", 5000, modified = 2_000_000),
        )
        val arrived = listOf(
            here("/b/Song (copy).mp3", 5000, modified = 2_000_000),
            here("/b/Song.mp3", 5000, modified = 1_000_000),
        )
        val moves = TrackMoves.match(departed, arrived)
        assertEquals(1L, moves["/b/Song.mp3"]?.id)
        assertEquals(2L, moves["/b/Song (copy).mp3"]?.id)
        assertEquals(2, moves.values.map { it.id }.toSet().size)
    }

    @Test
    fun `a weak match does not take the file a strong one wants`() {
        // Track 1 shares only its date with the file; track 2 shares name and date.
        val departed = listOf(
            gone(1, "/a/Aaa.mp3", 5000, modified = 3_000_000),
            gone(2, "/a/Song.mp3", 5000, modified = 3_000_000),
        )
        val moves = TrackMoves.match(departed, listOf(here("/b/Song.mp3", 5000, modified = 3_000_000)))
        assertEquals(2L, moves["/b/Song.mp3"]?.id)
        assertEquals(1, moves.size)
    }

    @Test
    fun `the storage a path is on`() {
        assertEquals("/storage/emulated/0", TrackMoves.volumeRootOf("/storage/emulated/0/Music/a.mp3"))
        assertEquals("/storage/emulated/10", TrackMoves.volumeRootOf("/storage/emulated/10/x.flac"))
        assertEquals("/storage/1A2B-3C4D", TrackMoves.volumeRootOf("/storage/1A2B-3C4D/Music/a.mp3"))
        assertNull(TrackMoves.volumeRootOf("/data/user/0/app/files/a.mp3"))
        assertNull(TrackMoves.volumeRootOf("relative/a.mp3"))
        assertNull(TrackMoves.volumeRootOf("/storage/emulated"))
    }

    @Test
    fun `tracks on a card that is out are not missing files`() {
        val missing = listOf(
            "/storage/1A2B-3C4D/Music/a.mp3",
            "/storage/1A2B-3C4D/Music/b.mp3",
            "/storage/emulated/0/Music/deleted.mp3",
            "/data/odd/place.mp3",
        )
        var asked = 0
        val toDelete = TrackMoves.withoutOffline(missing) { volume ->
            asked++
            volume == "/storage/emulated/0"
        }
        assertEquals(listOf("/storage/emulated/0/Music/deleted.mp3", "/data/odd/place.mp3"), toDelete)
        assertEquals("each volume is asked about once", 2, asked)
    }
}
