package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import org.junit.Test

class KugouLyricsClientTest {

    @Test
    fun `parseKrc computes absolute word times from line-relative offsets`() {
        // Synthetic KRC body: line starts at 5000ms; word offsets are
        // relative to the line, so absolute word time = lineStart + offset.
        val raw = "[ti:Test]\n[5000,900]<0,300,0>Foo<300,400,0>bar"
        val lines = KugouLyricsClient.parseKrc(raw, convertToRomaji = false)

        assertEquals(1, lines.size)
        val line = lines.single()
        assertEquals(5000L, line.timeMs)
        assertEquals("Foobar", line.text)
        assertEquals(5000L, line.words[0].startMs)
        assertEquals(5300L, line.words[0].endMs)
        assertEquals(5300L, line.words[1].startMs)
        assertEquals(5700L, line.words[1].endMs)
    }

    @Test
    fun `parseKrc skips header metadata lines`() {
        val raw = "[ar:Some Artist]\n[al:Some Album]\n[1000,500]<0,500,0>Only line"
        val lines = KugouLyricsClient.parseKrc(raw, convertToRomaji = false)
        assertEquals(1, lines.size)
        assertEquals(1000L, lines.single().timeMs)
    }

    @Test
    fun `parseKrc returns empty for content with no timed lines`() {
        val raw = "[ti:Instrumental]\n[ar:Nobody]"
        assertTrue(KugouLyricsClient.parseKrc(raw, convertToRomaji = false).isEmpty())
    }

    @Test
    fun `decryptKrc round-trips a synthetically encoded blob`() {
        val plain = "[1000,500]<0,500,0>Round trip"
        val encoded = encodeKrcForTest(plain)
        val decrypted = KugouLyricsClient.decryptKrc(encoded)
        assertEquals(plain, decrypted)
    }

    @Test
    fun `decryptKrc returns null for garbage input`() {
        assertNull(KugouLyricsClient.decryptKrc("not-valid-base64!!"))
        assertNull(KugouLyricsClient.decryptKrc(Base64.getEncoder().encodeToString(byteArrayOf(1, 2))))
    }

    /**
     * Mirrors KugouLyricsClient's private encode path (magic header + XOR +
     * zlib) so the decrypt round-trip can be verified without a real
     * network-fetched KRC sample.
     */
    private fun encodeKrcForTest(plain: String): String {
        val magic = byteArrayOf(0x6B, 0x72, 0x63, 0x31)
        val key = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5E.toByte(), 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2D, 0xCE.toByte(), 0xD2.toByte(), 0x6E, 0x69,
        )
        val deflater = Deflater()
        deflater.setInput(plain.toByteArray(Charsets.UTF_8))
        deflater.finish()
        val deflated = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buffer)
            deflated.write(buffer, 0, n)
        }
        deflater.end()
        val deflatedBytes = deflated.toByteArray()
        val xored = ByteArray(deflatedBytes.size) { i ->
            (deflatedBytes[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
        return Base64.getEncoder().encodeToString(magic + xored)
    }
}
