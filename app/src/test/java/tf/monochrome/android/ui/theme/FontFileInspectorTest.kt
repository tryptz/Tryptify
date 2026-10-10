package tf.monochrome.android.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

/**
 * The inspector is the gate in front of the font library: what it accepts, the
 * app later renders the whole interface with. So it is tested against the real
 * fonts the app ships, and against the files people actually pick by mistake.
 */
class FontFileInspectorTest {

    private val assets = File("src/main/assets")

    private fun bundled(path: String) = File(assets, path).readBytes()

    private fun inspectFont(bytes: ByteArray): FontInspection.Font {
        val result = FontFileInspector.inspect(bytes)
        assertTrue("expected a font, got $result", result is FontInspection.Font)
        return result as FontInspection.Font
    }

    @Test
    fun `every bundled font is a sound font that names itself what the app calls it`() {
        BundledFonts.ALL.forEach { font ->
            val result = FontFileInspector.inspect(File(assets, font.assetPath))
            assertTrue("${font.assetPath}: $result", result is FontInspection.Font)
            result as FontInspection.Font
            assertEquals(font.assetPath, font.displayName, result.displayName(fallback = "?"))
            assertTrue("${font.assetPath} is variable", result.variable)
            assertEquals(FontFormat.TRUETYPE, result.format)
        }
    }

    @Test
    fun `a variable font is named by its family, not by its default instance`() {
        // Fraunces' default instance is "Black" and Outfit's is "Thin": shown
        // as names, the library would list "Fraunces Black" for a font that
        // renders every weight.
        val fraunces = inspectFont(bundled("fonts/fraunces.ttf"))
        assertEquals("Black", fraunces.style)
        assertEquals("Fraunces", fraunces.displayName("?"))
    }

    @Test
    fun `a static cut keeps its style, unless it is the plain one`() {
        fun font(family: String?, style: String?) = FontInspection.Font(family, style, variable = false, format = FontFormat.TRUETYPE)
        assertEquals("Fredoka Bold", font("Fredoka", "Bold").displayName("x"))
        assertEquals("Lilita One", font("Lilita One", "Regular").displayName("x"))
        assertEquals("Lora", font("Lora", "Book").displayName("x"))
        assertEquals("my-file", font(null, null).displayName("my-file"))
        assertEquals("my-file", font("  ", "Bold").displayName("my-file"))
    }

    @Test
    fun `web fonts are refused as web fonts, not as junk`() {
        val woff = "wOFF".toByteArray() + ByteArray(64)
        val woff2 = "wOF2".toByteArray() + ByteArray(64)
        assertEquals(FontInspection.Rejected(FontRejection.WEB_FONT), FontFileInspector.inspect(woff))
        assertEquals(FontInspection.Rejected(FontRejection.WEB_FONT), FontFileInspector.inspect(woff2))
    }

    @Test
    fun `an old Type 1 font is refused as Type 1`() {
        val type1 = "typ1".toByteArray() + ByteArray(64)
        assertEquals(FontInspection.Rejected(FontRejection.TYPE1), FontFileInspector.inspect(type1))
    }

    @Test
    fun `an empty file and a text file are not fonts`() {
        assertEquals(FontInspection.Rejected(FontRejection.EMPTY), FontFileInspector.inspect(ByteArray(0)))
        val notes = "Shopping list: milk, eggs, a new font".toByteArray()
        assertEquals(FontInspection.Rejected(FontRejection.NOT_A_FONT), FontFileInspector.inspect(notes))
    }

    @Test
    fun `a truncated font is refused, because its tables run off the end`() {
        val cut = bundled("fonts/manrope.ttf").copyOf(4096)
        assertEquals(FontInspection.Rejected(FontRejection.NOT_A_FONT), FontFileInspector.inspect(cut))
    }

    @Test
    fun `a valid header with no outlines is not a font a renderer can use`() {
        // An sfnt header and one table, `head`, that fits in the file: the
        // signature is right, but there is nothing to draw with.
        val buf = ByteBuffer.allocate(32)
        buf.putInt(0x00010000).putShort(1.toShort()).putShort(16.toShort()).putShort(0.toShort()).putShort(0.toShort())
        buf.put("head".toByteArray()).putInt(0).putInt(28).putInt(4)
        buf.putInt(0)
        assertEquals(FontInspection.Rejected(FontRejection.NOT_A_FONT), FontFileInspector.inspect(buf.array()))
    }

    @Test
    fun `a collection is read from its first face`() {
        val face = bundled("fonts/sora.ttf")
        val collection = asCollection(face)
        val result = inspectFont(collection)
        assertEquals(FontFormat.COLLECTION, result.format)
        assertEquals("Sora", result.displayName("?"))
    }

    @Test
    fun `a missing file is refused, not thrown`() {
        val result = FontFileInspector.inspect(File("does/not/exist.ttf"))
        assertEquals(FontInspection.Rejected(FontRejection.NOT_A_FONT), result)
    }

    /**
     * Wraps one font in a TrueType Collection header. Table offsets in a
     * collection count from the start of the file, so every one in the face's
     * directory moves by the header's 16 bytes.
     */
    private fun asCollection(face: ByteArray): ByteArray {
        val header = ByteBuffer.allocate(16)
        header.put("ttcf".toByteArray()).putInt(0x00010000).putInt(1).putInt(16)
        val shifted = face.copyOf()
        val buf = ByteBuffer.wrap(shifted)
        val numTables = buf.getShort(4).toInt() and 0xFFFF
        for (i in 0 until numTables) {
            val at = 12 + i * 16 + 8
            buf.putInt(at, buf.getInt(at) + 16)
        }
        return header.array() + shifted
    }
}
