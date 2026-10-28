package tf.monochrome.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test
import tf.monochrome.android.data.db.entity.UserPlaylistEntity

class PlaylistSortTest {

    private fun playlist(id: String, name: String, created: Long, edited: Long) =
        UserPlaylistEntity(id = id, name = name, createdAt = created, updatedAt = edited)

    private val playlists = listOf(
        playlist("a", "beta", created = 1, edited = 30),
        playlist("b", "Alpha", created = 3, edited = 10),
        playlist("c", "gamma", created = 2, edited = 20),
    )

    @Test
    fun `last edited first is the default, as the Library always listed them`() {
        val sorted = PlaylistSort.apply(playlists, PlaylistSort.decode(null))
        assertEquals(listOf("a", "c", "b"), sorted.map { it.id })
    }

    @Test
    fun `by name ignores case`() {
        val sorted = PlaylistSort.apply(playlists, LibrarySort(LibrarySortKey.NAME))
        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test
    fun `by date created, either way`() {
        assertEquals(listOf("a", "c", "b"), PlaylistSort.apply(playlists, LibrarySort(LibrarySortKey.CREATED)).map { it.id })
        assertEquals(
            listOf("b", "c", "a"),
            PlaylistSort.apply(playlists, LibrarySort(LibrarySortKey.CREATED, ascending = false)).map { it.id },
        )
    }

    @Test
    fun `the saved sort round-trips, and anything else reads as the default`() {
        PLAYLIST_SORT_KEYS.forEach { key ->
            listOf(true, false).forEach { ascending ->
                val sort = LibrarySort(key, ascending)
                assertEquals(sort, PlaylistSort.decode(PlaylistSort.encode(sort)))
            }
        }
        assertEquals(PlaylistSort.DEFAULT, PlaylistSort.decode("TIME:asc"))
        assertEquals(PlaylistSort.DEFAULT, PlaylistSort.decode("nonsense"))
    }
}
