package tf.monochrome.android.audio.usb

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioSink] that hands PCM directly to libusb when the user
 * has Exclusive USB DAC mode on AND the iso pump is live; otherwise
 * delegates everything to a [DefaultAudioSink] (provided by the
 * caller as `delegate`). Wrapping ForwardingAudioSink keeps every
 * one of the ~35 AudioSink methods doing the right thing without
 * us having to re-implement them.
 *
 * Honest scope of this commit:
 *  - When the libusb path is hot, audio bypasses the
 *    DefaultAudioSink-internal AudioProcessor chain — i.e. mixBus
 *    DSP, AutoEQ, parametric EQ, the spectrum tap, and the ProjectM
 *    audio tap don't run. Exclusive mode is therefore raw
 *    bit-perfect playback for now. Bringing the processor chain
 *    inline is a follow-up: will run them on the buffer here before
 *    [LibusbUacDriver.write], with a separate config() pass.
 *  - getCurrentPositionUs uses frames-written accounting, ignoring
 *    iso buffer-depth latency (~32 ms at 48 kHz w/ defaults).
 *    A/V sync is "good enough" for music; would need correction for
 *    video lipsync.
 */
@UnstableApi
class LibusbAudioSink(
    delegate: AudioSink,
    private val driver: LibusbUacDriver,
) : ForwardingAudioSink(delegate) {

    private var bypassActive = false
    private var configuredFormat: Format? = null
    private var framesWritten: Long = 0L
    private var startTimeUs: Long = C.TIME_UNSET
    private var pcmBytesPerFrame: Int = 0

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        // Always configure the delegate so [flush] / [reset] / fallback
        // playback all work. The libusb pump only kicks in if start()
        // succeeds; if it doesn't, audio still plays via the delegate.
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
        configuredFormat = inputFormat

        val rate = inputFormat.sampleRate
        val channels = inputFormat.channelCount
        val bits = pcmBitsFromEncoding(inputFormat.pcmEncoding)
        if (rate <= 0 || channels <= 0 || bits <= 0) {
            bypassActive = false
            return
        }
        pcmBytesPerFrame = (bits / 8) * channels

        // Driver only attempts start() when the user toggle is on AND
        // a DAC handle was acquired by UsbExclusiveController — both
        // gating happens in driver.isOpen.value upstream. start()
        // returns false on any descriptor-mismatch / EBUSY / fractional
        // rate; bypassActive stays false and we fall through.
        bypassActive = if (driver.isOpen.value) {
            driver.start(rate, bits, channels)
        } else {
            false
        }
        if (bypassActive) {
            framesWritten = 0L
            startTimeUs = C.TIME_UNSET
            Log.i(TAG, "configured: bypass active ($rate Hz / $bits-bit / ${channels}ch)")
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (!bypassActive) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!buffer.hasRemaining()) return true
        if (startTimeUs == C.TIME_UNSET) startTimeUs = presentationTimeUs

        // Driver expects a direct, native-byte-order ByteBuffer because
        // the JNI side reads via GetDirectBufferAddress. ExoPlayer's
        // decoder usually hands us a direct ByteBuffer already; if it
        // doesn't, copy into a scratch direct buffer.
        val direct = if (buffer.isDirect) buffer else copyIntoScratch(buffer)
        val framesAvailable = direct.remaining() / pcmBytesPerFrame
        if (framesAvailable <= 0) return true

        val written = driver.write(direct, framesAvailable)
        if (written <= 0) {
            // Ring full — back-pressure: tell the renderer we couldn't
            // consume this buffer right now, it'll retry on next tick.
            return false
        }
        // Advance position of the *original* caller buffer so Media3
        // sees the correct consumption count.
        val bytesWritten = written * pcmBytesPerFrame
        buffer.position(buffer.position() + bytesWritten)
        framesWritten += written
        return !buffer.hasRemaining()
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!bypassActive) return super.getCurrentPositionUs(sourceEnded)
        val rate = configuredFormat?.sampleRate ?: return C.TIME_UNSET
        if (rate <= 0) return C.TIME_UNSET
        val playedUs = framesWritten * 1_000_000L / rate
        return (if (startTimeUs == C.TIME_UNSET) 0L else startTimeUs) + playedUs
    }

    override fun hasPendingData(): Boolean {
        if (!bypassActive) return super.hasPendingData()
        // While the libusb ring still has anything in it, we have
        // pending data. writableFrames > 0 doesn't mean empty — we
        // approximate "near-empty" with a generous threshold so we
        // don't spuriously stall the renderer.
        return false
    }

    override fun isEnded(): Boolean {
        if (!bypassActive) return super.isEnded()
        return !driver.isStreaming.value && !hasPendingData()
    }

    override fun flush() {
        super.flush()
        if (bypassActive) {
            driver.stop()
            framesWritten = 0L
            startTimeUs = C.TIME_UNSET
            // configure() will re-start the pump on the next play.
            bypassActive = false
        }
    }

    override fun reset() {
        super.reset()
        if (driver.isStreaming.value) driver.stop()
        bypassActive = false
        framesWritten = 0L
        startTimeUs = C.TIME_UNSET
    }

    override fun release() {
        super.release()
        if (driver.isStreaming.value) driver.stop()
    }

    private fun copyIntoScratch(buffer: ByteBuffer): ByteBuffer {
        val scratch = ByteBuffer.allocateDirect(buffer.remaining())
            .order(ByteOrder.nativeOrder())
        val mark = buffer.position()
        scratch.put(buffer)
        buffer.position(mark)  // restore — handleBuffer will advance below
        scratch.flip()
        return scratch
    }

    private fun pcmBitsFromEncoding(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        else -> 0   // float / packed / encoded streams not supported on bypass
    }

    companion object {
        private const val TAG = "LibusbAudioSink"
    }
}
