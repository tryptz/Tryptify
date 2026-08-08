package tf.monochrome.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Library's Favorites and Overview pages each stack several `items()` blocks
 * into one `LazyColumn`, and Compose throws `IllegalArgumentException` the
 * moment two rows in one lazy list claim the same key — it does not fall back
 * to position.
 *
 * Both collisions this guards against are ordinary states, not corruption: a
 * liked track and a liked album carrying the same number (separate catalogue
 * namespaces, both `Long`), and a song that is both liked and recently played.
 * So the test is the number-sharing case, not a spread of unrelated ids.
 */
class LibraryKeysTest {

    @Test
    fun `the favorites page keys three kinds of row apart at one id`() {
        val id = 150973193L
        val keys = listOf(
            LibraryKeys.track(id),
            LibraryKeys.album(id),
            LibraryKeys.artist(id),
        )
        assertEquals("keys collide at id $id: $keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `the overview page keys a recently played song apart from a liked one`() {
        val id = 42L
        assertEquals(
            "a song that is both liked and recently played is one row twice",
            2,
            setOf(LibraryKeys.recent(id), LibraryKeys.liked(id)).size,
        )
    }

    @Test
    fun `no key of one kind is ever the key of another`() {
        val kinds = listOf(
            LibraryKeys::track,
            LibraryKeys::album,
            LibraryKeys::artist,
            LibraryKeys::recent,
            LibraryKeys::liked,
        )
        // Adjacent ids as well as equal ones: a scheme that appended the id
        // without a separator would let "track:1" + 2 meet "track:12".
        val ids = listOf(0L, 1L, 2L, 12L, 150973193L, Long.MAX_VALUE)
        val all = kinds.flatMap { kind -> ids.map { kind(it) } }
        assertEquals("two rows would share a key: $all", all.size, all.toSet().size)
    }

    @Test
    fun `a key is stable for the same row`() {
        // Lazy lists use the key to keep scroll position and state across a
        // recomposition, so it has to be the same string every time.
        assertEquals(LibraryKeys.track(7L), LibraryKeys.track(7L))
        assertEquals(LibraryKeys.album(7L), LibraryKeys.album(7L))
    }
}
