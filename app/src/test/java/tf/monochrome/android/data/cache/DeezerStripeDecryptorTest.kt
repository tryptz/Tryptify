package tf.monochrome.android.data.cache

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class DeezerStripeDecryptorTest {

    // The first 6200 bytes of Deezer track 136889400 exactly as the CDN serves
    // it: block 0 encrypted, blocks 1–2 plain, a short tail of block 3.
    private val served = javaClass.classLoader!!
        .getResourceAsStream("deezer_stripe_136889400.bin")!!.readBytes()

    private fun decrypt(chunk: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val d = DeezerStripeDecryptor(136889400L)
        var pos = 0
        while (pos < served.size) {
            val n = minOf(chunk, served.size - pos)
            d.feed(served, pos, n) { b, o, l -> out.write(b, o, l) }
            pos += n
        }
        d.finish { b, o, l -> out.write(b, o, l) }
        return out.toByteArray()
    }

    @Test
    fun `decrypted file starts as a FLAC`() {
        assertEquals("fLaC", String(decrypt(8192), 0, 4))
    }

    @Test
    fun `plain blocks pass through and length is unchanged`() {
        val out = decrypt(8192)
        assertEquals(served.size, out.size)
        assertArrayEquals(served.copyOfRange(2048, 6144), out.copyOfRange(2048, 6144))
        // The short tail is never encrypted.
        assertArrayEquals(served.copyOfRange(6144, served.size), out.copyOfRange(6144, out.size))
    }

    /** Network reads arrive in any size; the result must not depend on it. */
    @Test
    fun `chunking does not change the output`() {
        assertArrayEquals(decrypt(8192), decrypt(777))
        assertArrayEquals(decrypt(8192), decrypt(1))
    }
}
