package tf.monochrome.android.audio.atmos

import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackProvider
import tf.monochrome.android.domain.model.ChannelLayout

/**
 * Builds the AudioTrack for the Atmos speaker render with the layout's real
 * channel mask.
 *
 * Media3 1.5 derives a track's mask from the channel COUNT alone
 * (Util.getAudioTrackChannelConfig), so 8 channels always become 7.1 and 10
 * always 5.1.4 — a 5.1.2 or 7.1.2 render would reach the wrong speakers — and
 * the 24-channel frame that carries 9.1.4 / 9.1.6 would be labelled 22.2
 * (which has no front wides). While [activeLayout] is a speaker layout and the
 * track is the one Media3 derived from that layout's sink channel count, this
 * swaps in [ChannelLayout.sinkChannelMask]: same frame size, correct positions.
 * Height masks need API 32; before that the frame goes out as a channel INDEX
 * mask (channel n to device channel n), which is how multichannel USB
 * interfaces are addressed anyway. Every other track is built by Media3's
 * default provider, unchanged.
 */
@OptIn(UnstableApi::class)
class AtmosAudioTrackProvider(
    private val activeLayout: () -> ChannelLayout,
) : DefaultAudioSink.AudioTrackProvider {

    private val fallback = DefaultAudioTrackProvider()

    override fun getAudioTrack(
        audioTrackConfig: AudioSink.AudioTrackConfig,
        audioAttributes: AudioAttributes,
        audioSessionId: Int,
    ): AudioTrack {
        val layout = activeLayout()
        val pcm = audioTrackConfig.encoding == C.ENCODING_PCM_16BIT ||
            audioTrackConfig.encoding == C.ENCODING_PCM_FLOAT
        val ours = layout.isMultichannel && pcm &&
            !audioTrackConfig.offload && !audioTrackConfig.tunneling &&
            audioTrackConfig.channelConfig == Util.getAudioTrackChannelConfig(layout.sinkChannelCount)
        if (!ours) return fallback.getAudioTrack(audioTrackConfig, audioAttributes, audioSessionId)

        val format = AudioFormat.Builder()
            .setSampleRate(audioTrackConfig.sampleRate)
            // Media3's PCM encoding constants are the platform's.
            .setEncoding(audioTrackConfig.encoding)
            .apply {
                if (Build.VERSION.SDK_INT >= 32 || !layout.hasHeight) {
                    setChannelMask(layout.sinkChannelMask)
                } else {
                    setChannelIndexMask((1 shl layout.sinkChannelCount) - 1)
                }
            }
            .build()
        android.util.Log.i(TAG, "speaker track: ${layout.label} mask=0x${layout.sinkChannelMask.toString(16)}")
        return AudioTrack.Builder()
            .setAudioAttributes(audioAttributes.getAudioAttributesV21().audioAttributes)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(audioTrackConfig.bufferSize)
            .setSessionId(audioSessionId)
            .build()
    }

    private companion object {
        const val TAG = "AtmosTrackProvider"
    }
}
