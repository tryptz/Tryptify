package tf.monochrome.android.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
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
        // Lossy codecs and FLAC in MP4 declare none; a 20-bit FLAC maps to
        // ENCODING_INVALID. Float may be carrying real resolution for them.
        assertTrue(decodesToFloat(Format.NO_VALUE))
        assertTrue(decodesToFloat(C.ENCODING_INVALID))
    }
}
