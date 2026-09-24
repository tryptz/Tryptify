package tf.monochrome.android.audio.usb

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer

/**
 * Gapless trimming for any linear PCM, float included — what Media3's
 * TrimmingAudioProcessor does, without its 16-bit-only restriction.
 *
 * The hi-res HAL path needs it because it hands DefaultAudioSink finished
 * float, which DefaultAudioSink routes down its float branch — and that branch
 * runs its float converter and nothing else. The encoder delay at the start of
 * an MP3/AAC and the padding at its end, which the int branch trims, would
 * otherwise play: a click or a few milliseconds of silence at every track
 * boundary of a gapless album.
 *
 * Same contract as Media3's: [setTrimFrameCount] before configure; the first
 * flush after a configure arms the start trim, a later flush is a seek and
 * does not; the last [trimEndFrames] frames are held back and dropped when the
 * next configure (the next track) or a flush arrives, which is exactly the
 * padding. This chain is never drained to end of stream, so a held tail is
 * never flushed out as audio.
 */
@UnstableApi
internal class PcmTrimmingAudioProcessor : BaseAudioProcessor() {

    private var trimStartFrames = 0
    private var trimEndFrames = 0
    private var reconfigurationPending = false
    private var pendingTrimStartBytes = 0
    private var endBuffer = ByteArray(0)
    private var endBufferSize = 0
    private var endBufferBytesPerFrame = 0

    /**
     * Frames dropped so far — start trim, and padding discarded at the next
     * track. Media time that produced no output: the sink's playout-time
     * mapping adds it back so positions stay on the source's clock.
     * Monotonic until [reset].
     */
    var trimmedFrames = 0L
        private set

    fun setTrimFrameCount(trimStartFrames: Int, trimEndFrames: Int) {
        this.trimStartFrames = trimStartFrames.coerceAtLeast(0)
        this.trimEndFrames = trimEndFrames.coerceAtLeast(0)
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (!Util.isEncodingLinearPcm(inputAudioFormat.encoding)) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        reconfigurationPending = true
        return if (trimStartFrames != 0 || trimEndFrames != 0) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val position = inputBuffer.position()
        val limit = inputBuffer.limit()
        var remaining = limit - position
        if (remaining == 0) return

        val trimBytes = minOf(remaining, pendingTrimStartBytes)
        pendingTrimStartBytes -= trimBytes
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        if (bytesPerFrame > 0) trimmedFrames += trimBytes / bytesPerFrame
        inputBuffer.position(position + trimBytes)
        if (pendingTrimStartBytes > 0) return
        remaining -= trimBytes

        // Everything beyond the last endBuffer.size bytes seen so far is safe
        // to output: it cannot be padding.
        var bytesToOutput = endBufferSize + remaining - endBuffer.size
        val out = replaceOutputBuffer(bytesToOutput.coerceAtLeast(0))

        val fromEnd = bytesToOutput.coerceIn(0, endBufferSize)
        out.put(endBuffer, 0, fromEnd)
        bytesToOutput -= fromEnd

        val fromInput = bytesToOutput.coerceIn(0, remaining)
        inputBuffer.limit(inputBuffer.position() + fromInput)
        out.put(inputBuffer)
        inputBuffer.limit(limit)
        remaining -= fromInput

        endBufferSize -= fromEnd
        System.arraycopy(endBuffer, fromEnd, endBuffer, 0, endBufferSize)
        inputBuffer.get(endBuffer, endBufferSize, remaining)
        endBufferSize += remaining

        out.flip()
    }

    override fun onFlush() {
        val bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(0)
        if (reconfigurationPending) {
            reconfigurationPending = false
            // The held tail of the previous track is its padding, dropped here.
            if (endBufferBytesPerFrame > 0) trimmedFrames += endBufferSize / endBufferBytesPerFrame
            endBufferBytesPerFrame = bytesPerFrame
            endBuffer = ByteArray(trimEndFrames * bytesPerFrame)
            pendingTrimStartBytes = trimStartFrames * bytesPerFrame
        } else {
            // A flush during playback is a seek; the start trim no longer applies.
            pendingTrimStartBytes = 0
        }
        endBufferSize = 0
    }

    override fun onReset() {
        endBuffer = ByteArray(0)
        endBufferSize = 0
        endBufferBytesPerFrame = 0
        trimmedFrames = 0
        pendingTrimStartBytes = 0
    }
}
