package tf.monochrome.android.domain.usecase

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The filter that keeps a genre shelf from filling up with records that are
 * only there because they say the genre's name.
 *
 * Every "drops" case below is a real shape the catalogue search returns for a
 * genre query, and every "keeps" case is a real record that a careless version
 * of this filter would throw away.
 */
class GenreShelfFilterTest {

    private val hardstyle = listOf("Hardstyle")

    @Test
    fun `a track with the genre bolted onto its name is dropped`() {
        assertTrue(echoesGenreName("Hardstyle Fish", null, hardstyle))
    }

    @Test
    fun `a genre used as a dashed suffix is dropped`() {
        // The case that motivates foldWhole. normalizeForMatch cuts everything
        // after " - ", so folding this the chart matcher's way would leave
        // "im so lucky" and the echo would vanish before it could be caught.
        assertTrue(echoesGenreName("I'm so lucky! - Hardstyle", null, hardstyle))
    }

    @Test
    fun `a genre inside brackets is dropped`() {
        assertTrue(echoesGenreName("Sandstorm (Hardstyle Remix)", null, hardstyle))
    }

    @Test
    fun `a compilation named after the genre is dropped on its album title`() {
        assertTrue(echoesGenreName("Track Four", "Techno 2024", listOf("Techno")))
    }

    @Test
    fun `an alias counts as the genre's name`() {
        // node.queries() is the name plus every alias, and filler is written
        // against whichever spelling people search for.
        assertTrue(echoesGenreName("DnB Anthems Vol 3", null, listOf("Drum & Bass", "DnB")))
    }

    @Test
    fun `punctuation and case do not hide an echo`() {
        assertTrue(echoesGenreName("HARD-STYLE!!", null, listOf("hard style")))
    }

    @Test
    fun `a record that simply is the genre is kept`() {
        assertFalse(echoesGenreName("Gasolina", "Barrio Fino", listOf("Reggaeton")))
    }

    @Test
    fun `the genre's name inside a longer word is not an echo`() {
        // Substring matching would drop "Entrance" from every trance shelf.
        assertFalse(echoesGenreName("Entrance", null, listOf("Trance")))
        assertFalse(echoesGenreName("Housework", null, listOf("House")))
    }

    @Test
    fun `only part of a multi word genre is not an echo`() {
        // "Hard Times" shares a word with hard techno and is not filler for it.
        assertFalse(echoesGenreName("Hard Times", null, listOf("Hard Techno")))
    }

    @Test
    fun `the record from the bug report is an echo, and the one beside it is not`() {
        // Both were on the Neoclassical row. The first is what the filter was
        // built to catch; the second is why catching it was never enough. A
        // production-library track called "Playing with Fire" is in that row
        // because the search ranked its album, and no amount of name filtering
        // can tell that it is not neoclassical — which is why the genre path
        // now asks the genre's chart instead of the genre's spelling.
        val neoclassical = listOf("Neoclassical", "neo-classical")
        assertTrue(echoesGenreName("Neoclassical Cello", null, neoclassical))
        assertFalse(echoesGenreName("Playing with Fire", null, neoclassical))
    }

    @Test
    fun `an empty title or genre list is not an echo`() {
        assertFalse(echoesGenreName("", null, hardstyle))
        assertFalse(echoesGenreName("Hardstyle Fish", null, emptyList()))
        assertFalse(echoesGenreName("Hardstyle Fish", null, listOf("", "  ")))
    }
}
