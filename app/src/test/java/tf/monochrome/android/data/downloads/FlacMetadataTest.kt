package tf.monochrome.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlacMetadataTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    // "fLaC", one metadata block of [type] with a [length]-byte body, then a FLAC frame's sync code.
    private fun flac(firstByte: Int, length: Int = 34, frame: ByteArray = bytes(0xFF, 0xF8, 0x69, 0x08)): ByteArray =
        "fLaC".toByteArray() +
            bytes(firstByte, length shr 16, (length shr 8) and 0xFF, length and 0xFF) +
            ByteArray(length) +
            frame

    @Test
    fun `a STREAMINFO not marked last before the audio is found`() {
        // What a dfLa without the flag remuxes to: JAudioTagger reads the frame as "block type 127".
        assertEquals(4, FlacMetadata.unmarkedLastBlock(flac(firstByte = 0x00)))
    }

    @Test
    fun `a correctly marked file is left alone`() {
        assertNull(FlacMetadata.unmarkedLastBlock(flac(firstByte = 0x80)))
    }

    @Test
    fun `the unmarked block is the one right before the audio`() {
        // STREAMINFO (not last, correctly) then an unmarked PADDING block, then audio.
        val file = "fLaC".toByteArray() +
            bytes(0x00, 0, 0, 34) + ByteArray(34) +
            bytes(0x01, 0, 0, 8) + ByteArray(8) +
            bytes(0xFF, 0xF9, 0x00, 0x00)
        assertEquals(4 + 4 + 34, FlacMetadata.unmarkedLastBlock(file))
    }

    @Test
    fun `not FLAC, or audio out of sight, is never touched`() {
        assertNull(FlacMetadata.unmarkedLastBlock(bytes(0x00, 0x00, 0x00, 0x18) + "ftypdash".toByteArray()))
        assertNull(FlacMetadata.unmarkedLastBlock(flac(firstByte = 0x00).copyOf(4 + 4 + 34)))
        // Not a frame sync after the block: no guess.
        assertNull(FlacMetadata.unmarkedLastBlock(flac(firstByte = 0x00, frame = bytes(0x12, 0x34))))
    }
}
