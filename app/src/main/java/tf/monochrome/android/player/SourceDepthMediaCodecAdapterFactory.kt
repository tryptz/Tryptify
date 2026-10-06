package tf.monochrome.android.player

import android.media.AudioFormat
import android.media.MediaFormat
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter

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
 * So a source that declares 16 bits or fewer is decoded to 16-bit. That is
 * what LibusbAudioSink was designed to receive from it ("16-bit: straight
 * through to the int branch"). A wider source still gets float, and so does
 * one that declares no depth at all: lossy codecs, and FLAC inside MP4. For
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
            !decodesToFloat(configuration.format.pcmEncoding)
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
 * Whether a decoder should be asked for float, given the bit depth its source
 * declares ([androidx.media3.common.Format.pcmEncoding]).
 *
 * No for 8- and 16-bit, which float cannot improve on. Yes for anything wider,
 * and for [androidx.media3.common.Format.NO_VALUE] and C.ENCODING_INVALID. Those
 * are what a lossy source, a FLAC in MP4, or an unusual depth such as 20-bit
 * report, and for them float may be carrying real resolution.
 */
@OptIn(UnstableApi::class)
internal fun decodesToFloat(sourcePcmEncoding: Int): Boolean = when (sourcePcmEncoding) {
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    -> false
    else -> true
}
