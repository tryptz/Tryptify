package tf.monochrome.android.audio.wav

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.wav.WavExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 32-bit float WAVs written as WAVE_FORMAT_EXTENSIBLE, which Media3 1.5
 * declares as 32-bit integer PCM and so played as static. Built in memory and
 * run through the app's wrapper, which peeks the real header bytes through a
 * real ExtractorInput.
 */
class WavFloatTest {

    /** A WAV with an optional odd-sized chunk ahead of fmt, and [frames] frames of silence. */
    private fun wav(tag: Int, bits: Int, subTag: Int? = null, junkBefore: Int = 0, frames: Int = 64): ByteArray {
        val channels = 2
        val rate = 48000
        val blockAlign = channels * bits / 8
        val fmt = ByteBuffer.allocate(if (subTag != null) 40 else 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(tag.toShort()); putShort(channels.toShort()); putInt(rate)
            putInt(rate * blockAlign); putShort(blockAlign.toShort()); putShort(bits.toShort())
            if (subTag != null) {
                putShort(22); putShort(bits.toShort()); putInt(3) // cbSize, valid bits, mask
                putShort(subTag.toShort())
                // The rest of the KSDATAFORMAT_SUBTYPE GUID.
                put(byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0xAA.toByte(), 0x00, 0x38, 0x9B.toByte(), 0x71))
            }
        }.array()
        val body = ByteArrayOutputStream()
        fun chunk(id: String, data: ByteArray) {
            body.write(id.toByteArray(Charsets.US_ASCII))
            body.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(data.size).array())
            body.write(data)
            if (data.size % 2 == 1) body.write(0)
        }
        if (junkBefore > 0) chunk("JUNK", ByteArray(junkBefore))
        chunk("fmt ", fmt)
        chunk("data", ByteArray(frames * blockAlign))
        val inner = body.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4 + inner.size).array())
            write("WAVE".toByteArray(Charsets.US_ASCII))
            write(inner)
        }.toByteArray()
    }

    /** The format [extractor] declares for [bytes]. */
    private fun declaredFormat(extractor: Extractor, bytes: ByteArray): Format? {
        var declared: Format? = null
        val track = object : TrackOutput {
            override fun format(format: Format) { declared = format }
            override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
                val skip = ByteArray(length)
                return input.read(skip, 0, length)
            }
            override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) { data.skipBytes(length) }
            override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) {}
        }
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = track
            override fun endTracks() {}
            override fun seekMap(seekMap: SeekMap) {}
        })
        val length = bytes.size.toLong()
        var input = DefaultExtractorInput(BytesReader(bytes, 0), 0, length)
        assertTrue("sniff", extractor.sniff(input))
        input.resetPeekPosition()
        val holder = PositionHolder()
        var steps = 0
        while (declared == null && steps++ < 50) {
            val result = extractor.read(input, holder)
            if (result == Extractor.RESULT_END_OF_INPUT) break
            if (result == Extractor.RESULT_SEEK) {
                input = DefaultExtractorInput(BytesReader(bytes, holder.position.toInt()), holder.position, length)
            }
        }
        return declared
    }

    /** The file, read from [start]: all an extractor needs, without android.net.Uri. */
    private class BytesReader(private val bytes: ByteArray, start: Int) : DataReader {
        private var pos = start
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (pos >= bytes.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, bytes.size - pos)
            System.arraycopy(bytes, pos, buffer, offset, n)
            pos += n
            return n
        }
    }

    /**
     * Stands in for Media3 1.5's WavExtractor, which cannot run on the plain
     * JVM (its header reader uses android.util.Pair). Declares what that
     * extractor declares: WavUtil.getPcmEncodingForType maps any extensible
     * file to integer PCM of its bit depth, and plain float (tag 3) to float.
     */
    private class Media3LikeWav(private val bytes: ByteArray) : Extractor {
        private lateinit var out: ExtractorOutput
        override fun sniff(input: androidx.media3.extractor.ExtractorInput) = true
        override fun init(output: ExtractorOutput) { out = output }
        override fun read(input: androidx.media3.extractor.ExtractorInput, seekPosition: PositionHolder): Int {
            val info = WavProbe.readFormat(bytes)!!
            val encoding = when {
                info.formatTag == 3 && info.bitsPerSample == 32 -> C.ENCODING_PCM_FLOAT
                info.bitsPerSample == 24 -> C.ENCODING_PCM_24BIT
                info.bitsPerSample == 32 -> C.ENCODING_PCM_32BIT // the bug: extensible float too
                else -> C.ENCODING_PCM_16BIT
            }
            out.track(0, C.TRACK_TYPE_AUDIO).format(
                Format.Builder().setSampleMimeType("audio/raw").setPcmEncoding(encoding).build(),
            )
            return Extractor.RESULT_END_OF_INPUT
        }
        override fun seek(position: Long, timeUs: Long) {}
        override fun release() {}
    }

    private fun wrapped(bytes: ByteArray): Format? =
        declaredFormat(FloatAwareWavExtractor(Media3LikeWav(bytes)), bytes)

    @Test
    fun `without the wrapper an extensible float WAV is declared integer - the bug`() {
        val bytes = wav(0xFFFE, 32, subTag = 3)
        assertEquals(C.ENCODING_PCM_32BIT, declaredFormat(Media3LikeWav(bytes), bytes)?.pcmEncoding)
    }

    @Test
    fun `wrapped, an extensible float WAV is float`() {
        assertEquals(C.ENCODING_PCM_FLOAT, wrapped(wav(0xFFFE, 32, subTag = 3, junkBefore = 301))?.pcmEncoding)
    }

    @Test
    fun `wrapped, an extensible 32-bit integer WAV stays integer`() {
        assertEquals(C.ENCODING_PCM_32BIT, wrapped(wav(0xFFFE, 32, subTag = 1))?.pcmEncoding)
    }

    @Test
    fun `plain float and 24-bit WAVs are untouched`() {
        assertEquals(C.ENCODING_PCM_FLOAT, wrapped(wav(3, 32))?.pcmEncoding)
        assertEquals(C.ENCODING_PCM_24BIT, wrapped(wav(1, 24))?.pcmEncoding)
    }

    @Test
    fun `the probe finds fmt behind an odd-sized chunk and reads the sub-format`() {
        val info = WavProbe.readFormat(wav(0xFFFE, 32, subTag = 3, junkBefore = 301))
        assertNotNull(info)
        assertTrue(info!!.isExtensibleFloat)
        assertEquals(2, info.channels)
        assertEquals(48000, info.sampleRate)
        assertEquals(32, info.bitsPerSample)
        assertFalse(WavProbe.readFormat(wav(1, 16))!!.isExtensibleFloat)
    }

    @Test
    fun `not a WAV, no answer`() {
        assertNull(WavProbe.readFormat(byteArrayOf(0x66, 0x4C, 0x61, 0x43, 0, 0, 0, 0x22, 0, 0, 0, 0)))
        assertNull(WavProbe.readFormat(ByteArray(4)))
    }

    @Test
    fun `the app's extractor factory wraps WAV and nothing else`() {
        val mp3 = androidx.media3.extractor.mp3.Mp3Extractor()
        val extractors = TryptifyExtractors.wrap(arrayOf(WavExtractor(), mp3))
        assertTrue(extractors[0] is FloatAwareWavExtractor)
        assertTrue(extractors[1] === mp3)
    }
}
