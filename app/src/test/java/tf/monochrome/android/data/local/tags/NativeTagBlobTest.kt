package tf.monochrome.android.data.local.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The block of bytes the native tag reader returns, read back. Its layout is
 * written twice, in tag_reader.h and in NativeTagBlob; the native side's own
 * tests (cpp/tags/tests) parse the same layout from the other end.
 */
class NativeTagBlobTest {

    private class Blob {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = apply { out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()) }
        fun text(s: String) = apply { val b = s.toByteArray(Charsets.UTF_8); int(b.size); out.write(b) }
        fun bytes(b: ByteArray) = apply { int(b.size); out.write(b) }
    }

    private fun blob(fields: List<Pair<String, String>>, picture: ByteArray = ByteArray(0), flags: Int = 0): ByteArray {
        val b = Blob().int(0x31475454).int(215_500).int(1411).int(44_100).int(2).int(16).int(flags)
            .text("FLAC").text("")
            .int(fields.size)
        fields.forEach { (name, value) -> b.text(name).text(value) }
        b.text(if (picture.isEmpty()) "" else "image/jpeg").bytes(picture)
        return b.out.toByteArray()
    }

    @Test
    fun `a block reads back whole`() {
        val cover = byteArrayOf(-1, -40, -1, 1, 2, 3)
        val tags = NativeTagBlob.parse(
            blob(
                listOf("TITLE" to "Tïtle 日本語 😀", "ARTIST" to "One", "ARTIST" to "Two", "TRACKNUMBER" to "3/12", "BPM" to "174"),
                picture = cover, flags = 1,
            ),
            listOf("en"), null,
        )
        assertNotNull(tags)
        tags!!
        assertEquals(215_500, tags.lengthMs)
        assertEquals(1411, tags.bitrateKbps)
        assertEquals(44_100, tags.sampleRate)
        assertEquals(2, tags.channels)
        assertEquals(16, tags.bitsPerSample)
        assertTrue(tags.hasPicture)
        assertEquals("FLAC", tags.container)
        assertEquals("Tïtle 日本語 😀", tags.first("TITLE"))
        assertEquals("One, Two", tags.joined("ARTIST"))
        assertEquals("One", tags.first("ARTIST"))
        assertEquals("174", tags.asMap()["BPM"])
        assertNull(tags.first("ALBUM"))
        assertEquals("3/12", tags.first("NOPE", "TRACKNUMBER"))
        assertArrayEquals(cover, tags.picture)
    }

    @Test
    fun `no cover is no bytes, and the flag still says whether the file has one`() {
        val tags = NativeTagBlob.parse(blob(emptyList(), flags = 1), emptyList(), null)!!
        assertNull(tags.picture)
        assertTrue(tags.hasPicture)
    }

    @Test
    fun `text in an old code page is read as the phone's language says`() {
        val cp1251 = LegacyText.MARK + String("Сплин".toByteArray(charset("windows-1251")), Charsets.ISO_8859_1)
        val tags = NativeTagBlob.parse(blob(listOf("ARTIST" to cp1251, "TITLE" to "Plain")), listOf("ru"), null)!!
        assertEquals("Сплин", tags.first("ARTIST"))
        assertEquals("Plain", tags.first("TITLE"))
    }

    @Test
    fun `a block cut short, or not a block, is nothing rather than half a file`() {
        val whole = blob(listOf("TITLE" to "A title"), picture = ByteArray(40))
        for (cut in listOf(0, 3, 10, 30, whole.size - 41, whole.size - 1)) {
            assertNull("cut at $cut", NativeTagBlob.parse(whole.copyOf(cut), emptyList(), null))
        }
        assertNull(NativeTagBlob.parse(ByteArray(64), emptyList(), null))
        // A count or a length that runs past the end is refused, not allocated.
        val lying = Blob().int(0x31475454).int(0).int(0).int(0).int(0).int(0).int(0).text("").text("").int(Int.MAX_VALUE)
        assertNull(NativeTagBlob.parse(lying.out.toByteArray(), emptyList(), null))
    }
}
