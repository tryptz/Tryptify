package tf.monochrome.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * The Local page's index, and the promise that every row on it opens something.
 *
 * Modelled on AppPagesTest, which reads LibraryScreen to check that every page
 * id has a branch. The failure it guards against is the same one: a category
 * added to the enum, shown on the index, and never given a branch — which
 * compiles fine and draws a blank page.
 */
class LibraryCategoryTest {

    @Test
    fun `every category id is unique`() {
        val ids = LibraryCategory.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `ids are stable wire values, not derived from the enum name`() {
        // Spelled-out ids are what let the open category survive process death
        // across a rename. If someone switches these to `name.lowercase()`,
        // this test is the reminder that a user's restored session depends on
        // them; the two happen to agree today and that must stay deliberate.
        assertEquals("album_artists", LibraryCategory.ALBUM_ARTISTS.id)
        assertEquals("songs", LibraryCategory.SONGS.id)
        assertEquals("folders", LibraryCategory.FOLDERS.id)
    }

    @Test
    fun `fromId round-trips every category`() {
        LibraryCategory.entries.forEach { category ->
            assertEquals(category, LibraryCategory.fromId(category.id))
        }
    }

    @Test
    fun `fromId is null for the index and for anything unknown`() {
        // null is the index — a saved id this build no longer has must land
        // there rather than crash or open the wrong list.
        assertNull(LibraryCategory.fromId(null))
        assertNull(LibraryCategory.fromId(""))
        assertNull(LibraryCategory.fromId("mixtapes"))
    }

    @Test
    fun `every category has a branch in the Local page`() {
        val source = File("src/main/java/tf/monochrome/android/ui/library/LocalLibraryTab.kt")
            .readText()
        LibraryCategory.entries.forEach { category ->
            assert(source.contains("LibraryCategory.${category.name}")) {
                "LibraryCategory.${category.name} is on the index but has no branch " +
                    "in LocalLibraryTab — it would open a blank page."
            }
        }
    }

    @Test
    fun `the index still offers everything the swipeable tabs did`() {
        // The five sub-tabs this list replaced. Dropping one would be a
        // regression that nothing else would catch.
        val labels = LibraryCategory.entries.map { it.label }
        listOf("Songs", "Albums", "Artists", "Genres", "Folders").forEach {
            assert(it in labels) { "$it was a Local sub-tab and is missing from the index" }
        }
    }

    @Test
    fun `facet-backed categories borrow the icon of the screen they open`() {
        // The row you tap and the hero it opens must wear the same symbol.
        assertEquals(
            tf.monochrome.android.ui.detail.LocalFacet.GENRE.icon,
            LibraryCategory.GENRES.icon,
        )
        assertEquals(
            tf.monochrome.android.ui.detail.LocalFacet.ALBUM_ARTIST.icon,
            LibraryCategory.ALBUM_ARTISTS.icon,
        )
        assertNotNull(LibraryCategory.SONGS.icon)
    }
}
