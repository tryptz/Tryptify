package tf.monochrome.android.data.presence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The animated-WebP container, byte for byte.
 *
 * This is the least forgiving code in the app and the least visible when wrong:
 * a length field one byte out produces a file decoders skip in silence rather
 * than reject, so the failure arrives as a missing image on somebody else's
 * screen with nothing anywhere saying why. Everything structural is asserted
 * here rather than trusted.
 */
class AnimatedWebPTest {

    /** A minimal still WebP: RIFF wrapper around one lossy bitstream. */
    private fun stillWebp(payload: ByteArray = ByteArray(7) { it.toByte() }): ByteArray {
        val body = "WEBP".toByteArray() + "VP8 ".toByteArray() + le32(payload.size) +
            payload + if (payload.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        return "RIFF".toByteArray() + le32(body.size) + body
    }

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(), ((v ushr 24) and 0xFF).toByte(),
    )

    private fun readLe32(b: ByteArray, at: Int) =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    /** Every top-level chunk, in order. */
    private fun chunks(file: ByteArray): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        var pos = 12
        while (pos + 8 <= file.size) {
            val id = String(file, pos, 4, Charsets.US_ASCII)
            val size = readLe32(file, pos + 4)
            out += id to size
            pos += 8 + size + (size and 1)
        }
        return out
    }

    @Test
    fun `the file is a RIFF whose declared length matches its contents`() {
        // The one field that makes a decoder give up on the whole file.
        val out = AnimatedWebP.assemble(List(3) { stillWebp() }, listOf(16, 16, 16), 64, 64)
        assertEquals("RIFF", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(out, 8, 4, Charsets.US_ASCII))
        assertEquals("RIFF size must cover everything after it", out.size - 8, readLe32(out, 4))
    }

    @Test
    fun `the header declares animation and one frame per input`() {
        val out = AnimatedWebP.assemble(List(4) { stillWebp() }, List(4) { 20 }, 64, 64)
        val ids = chunks(out).map { it.first }
        // Order is not decoration: VP8X must come first or the file reads as a
        // still, and ANIM must precede the frames.
        assertEquals("VP8X", ids[0])
        assertEquals("ANIM", ids[1])
        assertEquals(4, ids.count { it == "ANMF" })
    }

    @Test
    fun `the canvas is stored minus one, as the format requires`() {
        val out = AnimatedWebP.assemble(listOf(stillWebp()), listOf(30), 320, 200)
        val vp8xAt = 12 + 8
        fun u24(at: Int) = (out[at].toInt() and 0xFF) or ((out[at + 1].toInt() and 0xFF) shl 8) or
            ((out[at + 2].toInt() and 0xFF) shl 16)
        // Off by one here is a canvas one pixel short, which crops every frame.
        assertEquals(319, u24(vp8xAt + 4))
        assertEquals(199, u24(vp8xAt + 7))
    }

    @Test
    fun `an odd-length frame is padded without being counted`() {
        // RIFF pads chunks to even lengths and does NOT include the pad in the
        // size field. Counting it shifts every following chunk by one byte.
        val odd = AnimatedWebP.assemble(listOf(stillWebp(ByteArray(5))), listOf(16), 32, 32)
        val even = AnimatedWebP.assemble(listOf(stillWebp(ByteArray(6))), listOf(16), 32, 32)
        for (file in listOf(odd, even)) {
            assertEquals("chunk walk must reach the end exactly",
                file.size - 8, readLe32(file, 4))
            assertTrue("frame missing", chunks(file).any { it.first == "ANMF" })
        }
    }

    @Test
    fun `a zero duration is lifted to something a decoder will show`() {
        // Zero means "as fast as possible" to some decoders and "broken" to
        // others; neither is what a caller passing 0 wanted.
        val out = AnimatedWebP.assemble(listOf(stillWebp()), listOf(0), 32, 32)
        val anmfAt = out.size - chunks(out).last().second - 8
        fun u24(at: Int) = (out[at].toInt() and 0xFF) or ((out[at + 1].toInt() and 0xFF) shl 8) or
            ((out[at + 2].toInt() and 0xFF) shl 16)
        assertTrue("duration must be positive", u24(anmfAt + 8 + 12) >= 1)
    }

    @Test
    fun `the codec chunk is found however the encoder laid the file out`() {
        // Bitmap.compress may or may not emit a VP8X of its own, so the reader
        // walks the chunk list rather than assuming a fixed offset.
        val plain = AnimatedWebP.codecChunk(stillWebp())
        assertNotNull(plain)
        assertEquals("VP8 ", plain!!.first)

        val withVp8x = "RIFF".toByteArray() + le32(4 + 8 + 10 + 8 + 8) + "WEBP".toByteArray() +
            "VP8X".toByteArray() + le32(10) + ByteArray(10) +
            "VP8 ".toByteArray() + le32(8) + ByteArray(8) { 9 }
        val found = AnimatedWebP.codecChunk(withVp8x)
        assertNotNull(found)
        assertEquals(8, found!!.second.size)
    }

    @Test
    fun `junk is refused rather than assembled into a broken file`() {
        assertNull(AnimatedWebP.codecChunk(ByteArray(4)))
        assertNull(AnimatedWebP.codecChunk("NOTARIFFATALL".toByteArray()))
        // A truncated length must not be read past the end of the array.
        val truncated = "RIFF".toByteArray() + le32(999) + "WEBP".toByteArray() +
            "VP8 ".toByteArray() + le32(999) + ByteArray(4)
        assertNull(AnimatedWebP.codecChunk(truncated))
    }

    @Test
    fun `mismatched inputs are rejected at the call rather than in the file`() {
        for (bad in listOf<() -> Unit>(
            { AnimatedWebP.assemble(emptyList(), emptyList(), 32, 32) },
            { AnimatedWebP.assemble(listOf(stillWebp()), listOf(1, 2), 32, 32) },
            { AnimatedWebP.assemble(listOf(stillWebp()), listOf(1), 0, 32) },
        )) {
            try {
                bad()
                throw AssertionError("expected a rejection")
            } catch (expected: IllegalArgumentException) {
                // The point: caught here, not discovered as a corrupt upload.
            }
        }
    }
}
