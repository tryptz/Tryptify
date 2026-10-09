package tf.monochrome.android.data.fonts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.theme.FontFormat

/**
 * An import is stored under the name the font gives itself. The old importer
 * kept the picker's file name, so a second "font.ttf" silently replaced the
 * first; these pin that a name is always safe on disk and never taken twice.
 */
class FontLibraryNamingTest {

    @Test
    fun `a font's name becomes a safe file name`() {
        assertEquals("Fredoka Bold", FontLibrary.safeFileStem("Fredoka / Bold:*"))
        assertEquals("Noto Sans JP", FontLibrary.safeFileStem("Noto Sans JP"))
        assertEquals("思源黑体", FontLibrary.safeFileStem("思源黑体"))
        assertEquals("Font", FontLibrary.safeFileStem("../../"))
        assertEquals("Font", FontLibrary.safeFileStem("   "))
        assertTrue(FontLibrary.safeFileStem("A".repeat(200)).length <= 60)
    }

    @Test
    fun `a name already in the library gets the next free number`() {
        assertEquals("Fredoka", FontLibrary.uniqueStem("Fredoka", emptySet()))
        assertEquals("Fredoka 2", FontLibrary.uniqueStem("Fredoka", setOf("fredoka")))
        assertEquals("Fredoka 3", FontLibrary.uniqueStem("Fredoka", setOf("fredoka", "fredoka 2")))
        // Compared without case: two files differing only in case collide on
        // case-insensitive storage.
        assertEquals("Fredoka 2", FontLibrary.uniqueStem("Fredoka", setOf("FREDOKA".lowercase())))
    }

    @Test
    fun `the extension follows what the file really is`() {
        assertEquals("ttf", FontLibrary.extensionFor(FontFormat.TRUETYPE))
        assertEquals("otf", FontLibrary.extensionFor(FontFormat.OPENTYPE_CFF))
        assertEquals("ttc", FontLibrary.extensionFor(FontFormat.COLLECTION))
    }
}
