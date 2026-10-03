package tf.monochrome.android.audio.wav

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.wav.WavExtractor

/**
 * Media3's extractors, with the WAV one corrected for 32-bit float
 * WAVE_FORMAT_EXTENSIBLE files (see [WavProbe]). Every player in the app —
 * the main one, the crossfade tail, the progressive path — builds its media
 * sources from this, so a WAV plays the same wherever it is played.
 */
@UnstableApi
object TryptifyExtractors {

    val factory: ExtractorsFactory = object : ExtractorsFactory {
        private val defaults = DefaultExtractorsFactory()

        override fun createExtractors(): Array<Extractor> = wrap(defaults.createExtractors())

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            wrap(defaults.createExtractors(uri, responseHeaders))

    }

    /** [extractors] with the WAV one wrapped; everything else as it was. */
    internal fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { i ->
            val e = extractors[i]
            if (e is WavExtractor) FloatAwareWavExtractor(e) else e
        }
}

/**
 * [WavExtractor], told what its own header says about float.
 *
 * Before the first read it peeks the head of the file through [WavProbe];
 * when the file is extensible and holds float, the format the extractor
 * declares — 32-bit integer PCM — is corrected to float on its way out. The
 * sample bytes need nothing: they are already IEEE floats, little-endian,
 * exactly what [C.ENCODING_PCM_FLOAT] means. Everything else — sniffing,
 * seeking, chunk walking, RF64 — is the delegate's, unchanged.
 */
@UnstableApi
internal class FloatAwareWavExtractor(private val delegate: Extractor) : Extractor {

    private var probed = false
    private var extensibleFloat = false

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun init(output: ExtractorOutput) = delegate.init(PatchingOutput(output))

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!probed && input.position == 0L) {
            probed = true
            extensibleFloat = peekHeader(input)?.let { it.isExtensibleFloat && it.bitsPerSample == 32 } == true
        }
        return delegate.read(input, seekPosition)
    }

    override fun seek(position: Long, timeUs: Long) = delegate.seek(position, timeUs)

    override fun release() = delegate.release()

    /** Up to [PEEK_BYTES] of the file's head, without moving the read position. */
    private fun peekHeader(input: ExtractorInput): WavFormatInfo? {
        val buf = ByteArray(PEEK_BYTES)
        var have = 0
        try {
            while (have < buf.size) {
                val n = input.peek(buf, have, buf.size - have)
                if (n == C.RESULT_END_OF_INPUT) break
                have += n
            }
        } finally {
            input.resetPeekPosition()
        }
        return WavProbe.readFormat(buf, have)
    }

    private inner class PatchingOutput(private val out: ExtractorOutput) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = PatchingTrack(out.track(id, type))
        override fun endTracks() = out.endTracks()
        override fun seekMap(seekMap: SeekMap) = out.seekMap(seekMap)
    }

    private inner class PatchingTrack(private val out: TrackOutput) : TrackOutput {
        override fun format(format: Format) {
            val fixed = if (extensibleFloat && format.pcmEncoding == C.ENCODING_PCM_32BIT) {
                format.buildUpon().setPcmEncoding(C.ENCODING_PCM_FLOAT).build()
            } else {
                format
            }
            out.format(fixed)
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            out.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            out.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) = out.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private companion object {
        /** Enough for any fmt chunk behind the usual JUNK / bext / LIST padding. */
        const val PEEK_BYTES = 64 * 1024
    }
}
