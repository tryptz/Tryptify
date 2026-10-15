package tf.monochrome.android.audio.wav

import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import tf.monochrome.android.audio.mp4.IndexedFragmentedMp4Extractor
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
            wrap(defaults.createExtractors(uri, responseHeaders), indexFragmentedMp4 = isOnDevice(uri.scheme))

    }

    /**
     * [extractors] with the WAV one and the fragmented-MP4 one wrapped;
     * everything else as it was. The fragmented-MP4 wrapper indexes a file
     * that has no `sidx` (Apple's ALAC), which Media3 could not seek in, when
     * [indexFragmentedMp4]: for a file on the device, not a stream.
     *
     * The index is built by walking every fragment before the first sample.
     * On a file that is a moment's reading. Over HTTP (an Apple Music stream)
     * it was a range request per fragment, or the whole file read through,
     * before anything played, and then the file again from the start: seconds
     * of silence at every track and twice the data. A stream plays as Media3
     * plays it, unseekable as it was before the index existed.
     */
    internal fun wrap(extractors: Array<Extractor>, indexFragmentedMp4: Boolean = true): Array<Extractor> =
        Array(extractors.size) { i ->
            when (val e = extractors[i]) {
                is WavExtractor -> FloatAwareWavExtractor(e)
                is FragmentedMp4Extractor -> if (indexFragmentedMp4) IndexedFragmentedMp4Extractor(e) else e
                else -> e
            }
        }

    /**
     * Whether a URI with [scheme] is read from the device: a file, a content
     * provider, the app's own resources. A path with no scheme is a file.
     */
    internal fun isOnDevice(scheme: String?): Boolean = when (scheme?.lowercase()) {
        null, "", "file", "content", "android.resource", "asset", "rawresource", "data" -> true
        else -> false
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
