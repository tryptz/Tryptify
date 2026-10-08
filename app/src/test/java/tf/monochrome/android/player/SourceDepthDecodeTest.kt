package tf.monochrome.android.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which sources a decoder is asked to decode to float. A 16-bit file gains
 * nothing from float, and asking for it anyway is what got Samsung's FLAC
 * decoder to write 16-bit samples labelled as float.
 */
@OptIn(UnstableApi::class)
class SourceDepthDecodeTest {

    @Test
    fun `16 bits or fewer decode to 16-bit`() {
        assertFalse(decodesToFloat(C.ENCODING_PCM_16BIT))
        assertFalse(decodesToFloat(C.ENCODING_PCM_16BIT_BIG_ENDIAN))
        assertFalse(decodesToFloat(C.ENCODING_PCM_8BIT))
    }

    @Test
    fun `wider sources keep float`() {
        assertTrue(decodesToFloat(C.ENCODING_PCM_24BIT))
        assertTrue(decodesToFloat(C.ENCODING_PCM_32BIT))
        assertTrue(decodesToFloat(C.ENCODING_PCM_FLOAT))
    }

    @Test
    fun `an undeclared depth keeps float`() {
        // Lossy codecs declare none, nor does a FLAC whose header cannot be
        // read; a 20-bit FLAC maps to ENCODING_INVALID. Float may be carrying
        // real resolution for them.
        assertTrue(decodesToFloat(Format.NO_VALUE))
        assertTrue(decodesToFloat(C.ENCODING_INVALID))
    }

    // --- The depth of FLAC whose container declares none (MP4, Matroska) ---

    @Test
    fun `16-bit FLAC in MP4 decodes to 16-bit`() {
        // Every TIDAL and Qobuz stream: the container says nothing, and judged
        // by it alone Samsung's decoder was still asked for float.
        val format = flacInContainer(flacHeader(bitsPerSample = 16))
        assertEquals(C.ENCODING_PCM_16BIT, sourcePcmEncoding(format))
        assertFalse(decodesToFloat(sourcePcmEncoding(format)))
    }

    @Test
    fun `24-bit FLAC in MP4 keeps float`() {
        val format = flacInContainer(flacHeader(bitsPerSample = 24))
        assertEquals(C.ENCODING_PCM_24BIT, sourcePcmEncoding(format))
        assertTrue(decodesToFloat(sourcePcmEncoding(format)))
    }

    @Test
    fun `an odd FLAC depth keeps float`() {
        val format = flacInContainer(flacHeader(bitsPerSample = 20))
        assertEquals(C.ENCODING_INVALID, sourcePcmEncoding(format))
        assertTrue(decodesToFloat(sourcePcmEncoding(format)))
    }

    @Test
    fun `a declared depth is taken as it is`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_FLAC)
            .setPcmEncoding(C.ENCODING_PCM_24BIT)
            .setInitializationData(listOf(flacHeader(bitsPerSample = 16)))
            .build()
        assertEquals(C.ENCODING_PCM_24BIT, sourcePcmEncoding(format))
    }

    @Test
    fun `a header that is not STREAMINFO keeps float`() {
        val truncated = flacHeader(bitsPerSample = 16).copyOf(20)
        val notFlac = flacHeader(bitsPerSample = 16).also { it[0] = 'X'.code.toByte() }
        // First block a PADDING block (type 1), not STREAMINFO.
        val wrongBlock = flacHeader(bitsPerSample = 16).also { it[4] = 0x01 }
        for (header in listOf(truncated, notFlac, wrongBlock)) {
            assertEquals(Format.NO_VALUE, sourcePcmEncoding(flacInContainer(header)))
        }
        assertEquals(Format.NO_VALUE, sourcePcmEncoding(Format.Builder().setSampleMimeType(MimeTypes.AUDIO_FLAC).build()))
    }

    @Test
    fun `other codecs are not read as FLAC`() {
        val format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setInitializationData(listOf(flacHeader(bitsPerSample = 16)))
            .build()
        assertEquals(Format.NO_VALUE, sourcePcmEncoding(format))
    }

    private fun flacInContainer(header: ByteArray): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_FLAC)
        .setInitializationData(listOf(header))
        .build()

    /**
     * What Media3 hands the decoder for FLAC in MP4: "fLaC", a metadata block
     * header (last block, type 0 = STREAMINFO, 34 bytes), then STREAMINFO for
     * 44.1 kHz stereo at [bitsPerSample].
     */
    private fun flacHeader(bitsPerSample: Int): ByteArray {
        val sampleRate = 44_100
        val channels = 2
        val info = ByteArray(34)
        info[0] = 0x10 // min block size 4096
        info[2] = 0x10 // max block size 4096
        // Sample rate (20 bits), channels - 1 (3), bits per sample - 1 (5).
        info[10] = (sampleRate shr 12).toByte()
        info[11] = (sampleRate shr 4).toByte()
        info[12] = (((sampleRate and 0xF) shl 4) or ((channels - 1) shl 1) or ((bitsPerSample - 1) shr 4)).toByte()
        info[13] = (((bitsPerSample - 1) and 0xF) shl 4).toByte()
        return byteArrayOf(
            'f'.code.toByte(), 'L'.code.toByte(), 'a'.code.toByte(), 'C'.code.toByte(),
            0x80.toByte(), 0, 0, 34,
        ) + info
    }
}
