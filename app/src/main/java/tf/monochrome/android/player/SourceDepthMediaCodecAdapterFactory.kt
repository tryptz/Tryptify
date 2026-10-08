package tf.monochrome.android.player

import android.media.AudioFormat
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.extractor.FlacStreamMetadata

/**
 * Asks an audio decoder for float output only when the source has more than
 * 16 bits to carry.
 *
 * Media3 asks every audio decoder for float whenever the sink takes float
 * directly, which LibusbAudioSink does on the normal output with hi-res output
 * on. That was meant to carry 24-bit FLAC past 16 bits, and it caught every
 * 16-bit FLAC as well, which gains nothing by it. Samsung's
 * c2.sec.flac.decoder (Galaxy A35, Android 16) answers that request for a
 * 16-bit file by reporting float output and writing 16-bit samples anyway.
 * Read as float, each pair of samples became one value that was either near
 * zero or far past full scale, and every buffer looked half as long as it
 * was. The result was full-scale static, and a play position running at twice
 * the real rate: DefaultAudioSink logged "Unexpected audio track timestamp
 * discontinuity" about every 200 ms.
 *
 * So a source of 16 bits or fewer is decoded to 16-bit. That is what
 * LibusbAudioSink was designed to receive from it ("16-bit: straight through
 * to the int branch"). The depth is the container's when it declares one, and
 * otherwise, for FLAC, the stream's own ([sourcePcmEncoding]): MP4 and Matroska
 * declare none for FLAC, and every TIDAL and Qobuz stream is FLAC in MP4, so
 * judged by the container alone the streams were never covered. A wider source
 * still gets float, and so does one with no depth to find (lossy codecs). For
 * those, FloatPcmGuard checks that what comes out really is float.
 */
@OptIn(UnstableApi::class)
class SourceDepthMediaCodecAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
) : MediaCodecAdapter.Factory {
    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val mediaFormat = configuration.mediaFormat
        if (mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT &&
            !decodesToFloat(sourcePcmEncoding(configuration.format))
        ) {
            mediaFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            Log.i(
                TAG,
                "${configuration.codecInfo.name}: 16-bit source, decoding to 16-bit rather than float",
            )
        }
        return delegate.createAdapter(configuration)
    }

    private companion object {
        const val TAG = "SourceDepthCodec"
    }
}

/**
 * Whether a decoder should be asked for float, given its source's bit depth
 * ([sourcePcmEncoding]).
 *
 * No for 8- and 16-bit, which float cannot improve on. Yes for anything wider,
 * and for [androidx.media3.common.Format.NO_VALUE] and C.ENCODING_INVALID. Those
 * are what a lossy source, a FLAC whose header cannot be read, or an unusual
 * depth such as 20-bit report, and for them float may be carrying real
 * resolution.
 */
@OptIn(UnstableApi::class)
internal fun decodesToFloat(sourcePcmEncoding: Int): Boolean = when (sourcePcmEncoding) {
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    -> false
    else -> true
}

/**
 * The PCM encoding [format]'s source decodes from: the container's
 * declaration, or for FLAC the bit depth in its STREAMINFO block.
 *
 * MP4's dfLa box and Matroska's A_FLAC CodecPrivate both reach the decoder as
 * initializationData[0]: the "fLaC" marker, then the metadata blocks with
 * STREAMINFO first. A raw .flac file needs none of this; FlacExtractor
 * declares its depth. Anything unreadable stays [Format.NO_VALUE], which keeps
 * float, the behaviour before this was looked at.
 */
@OptIn(UnstableApi::class)
internal fun sourcePcmEncoding(format: Format): Int {
    if (format.pcmEncoding != Format.NO_VALUE) return format.pcmEncoding
    if (format.sampleMimeType != MimeTypes.AUDIO_FLAC) return Format.NO_VALUE
    val header = format.initializationData.firstOrNull() ?: return Format.NO_VALUE
    val bits = flacStreamInfoBitsPerSample(header) ?: return Format.NO_VALUE
    return when (bits) {
        8 -> C.ENCODING_PCM_8BIT
        16 -> C.ENCODING_PCM_16BIT
        24 -> C.ENCODING_PCM_24BIT
        32 -> C.ENCODING_PCM_32BIT
        // 12-, 20-bit and the like: no integer encoding, so float.
        else -> C.ENCODING_INVALID
    }
}

/** Bits per sample from "fLaC" + a STREAMINFO block header + STREAMINFO, or null if it is not that. */
@OptIn(UnstableApi::class)
private fun flacStreamInfoBitsPerSample(header: ByteArray): Int? {
    if (header.size < FLAC_STREAM_INFO_OFFSET + FLAC_STREAM_INFO_SIZE) return null
    if (header[0] != 'f'.code.toByte() || header[1] != 'L'.code.toByte() ||
        header[2] != 'a'.code.toByte() || header[3] != 'C'.code.toByte()
    ) return null
    // The first block's type, low 7 bits of its header: 0 is STREAMINFO.
    if (header[4].toInt() and 0x7F != 0) return null
    return FlacStreamMetadata(header, FLAC_STREAM_INFO_OFFSET).bitsPerSample
}

/** "fLaC" (4 bytes) and the metadata block header (4 bytes) come first. */
private const val FLAC_STREAM_INFO_OFFSET = 8
private const val FLAC_STREAM_INFO_SIZE = 34
