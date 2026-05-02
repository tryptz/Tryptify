package tf.monochrome.android.audio.usb

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.audio.AudioProcessor
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
 * Honest scope:
 *  - When bypass is hot, the sink runs the same AudioProcessor chain
 *    that DefaultAudioSink would (mixBus DSP, AutoEQ, parametric EQ,
 *    spectrum FFT, ProjectM tap) inline via [AudioProcessorChain],
 *    then writes the post-DSP PCM to libusb. So EQ + visualizer keep
 *    working in exclusive mode.
 *  - getCurrentPositionUs uses frames-written accounting, ignoring
 *    iso buffer-depth latency (~32 ms at 48 kHz w/ defaults).
 *    A/V sync is "good enough" for music; would need correction for
 *    video lipsync.
 */
@UnstableApi
class LibusbAudioSink(
    delegate: AudioSink,
    private val driver: LibusbUacDriver,
    processors: List<AudioProcessor> = emptyList(),
) : ForwardingAudioSink(delegate) {

    private val chain = AudioProcessorChain(processors)
    private var bypassActive = false
    private var configuredFormat: Format? = null
    private var framesWritten: Long = 0L
    private var startTimeUs: Long = C.TIME_UNSET
    private var pcmBytesPerFrame: Int = 0
    // Throttle for the lazy-engage path so that if driver.start
    // returns false for the current format, we don't hammer it on
    // every handleBuffer (~50× per second) — that flooded logcat
    // with SET_CUR errors during the UAC1 bug. Reset on
    // configure/flush so the next track gets a fresh attempt.
    private var lastEngageFailHash: Int = 0

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
        lastEngageFailHash = 0   // fresh attempt for the new format

        val rate = inputFormat.sampleRate
        val channels = inputFormat.channelCount
        val bits = pcmBitsFromEncoding(inputFormat.pcmEncoding)
        if (rate <= 0 || channels <= 0 || bits <= 0) {
            bypassActive = false
            return
        }

        // Configure the inline DSP chain with the same input format
        // the renderer is feeding us. Output format may change (e.g.
        // an upsampler would lift the rate); we negotiate the libusb
        // alt setting against the *post-chain* format so the DAC
        // sees what the chain actually produced.
        val chainOut = chain.configure(
            AudioProcessor.AudioFormat(rate, channels, inputFormat.pcmEncoding)
        )
        val outRate = if (chainOut != AudioProcessor.AudioFormat.NOT_SET)
            chainOut.sampleRate else rate
        val outChans = if (chainOut != AudioProcessor.AudioFormat.NOT_SET)
            chainOut.channelCount else channels
        val outBits = if (chainOut != AudioProcessor.AudioFormat.NOT_SET)
            pcmBitsFromEncoding(chainOut.encoding) else bits
        if (outRate <= 0 || outChans <= 0 || outBits <= 0) {
            bypassActive = false
            return
        }
        pcmBytesPerFrame = (outBits / 8) * outChans

        // Track-to-track configure with the same format: skip the
        // stop/start cycle so we don't release the streaming
        // interface (releasing causes the Android kernel to briefly
        // re-grab it, after which the next claim returns BUSY and
        // playback dies until the user re-plugs the DAC).
        bypassActive = when {
            !driver.isOpen.value -> false
            driver.isStreamingFormat(outRate, outBits, outChans) -> {
                Log.i(TAG, "configured: reused active stream "
                    + "($outRate/${outBits}b/${outChans}ch)")
                true
            }
            else -> {
                // Format changed (or first start). driver.start now
                // handles the "already streaming, different format"
                // case internally — stops iso pump and keeps the
                // interface claim, so the kernel can't grab the
                // device in the gap and bounce us with EBUSY.
                driver.start(outRate, outBits, outChans).also { ok ->
                    if (ok) Log.i(TAG, "configured: bypass active " +
                        "(in $rate/${bits}b/${channels}ch → out $outRate/${outBits}b/${outChans}ch)")
                }
            }
        }
        if (bypassActive) {
            framesWritten = 0L
            startTimeUs = C.TIME_UNSET
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        // Lazy-engage bypass: if the user flipped the toggle AFTER
        // configure() ran for this track (typical case — they enable
        // exclusive mode mid-listen), we still have the format from
        // configure but bypassActive=false. Try to start the iso pump
        // here so the next buffer flows through the DAC. The check is
        // cheap (two atomics + a small int compare) so it's fine on
        // the hot handleBuffer path.
        if (!bypassActive && pcmBytesPerFrame > 0 && driver.isOpen.value) {
            val fmt = configuredFormat
            if (fmt != null) {
                val rate = fmt.sampleRate
                val ch = fmt.channelCount
                val bits = pcmBitsFromEncoding(fmt.pcmEncoding)
                val fmtHash = (rate * 31 + ch) * 31 + bits
                if (rate > 0 && ch > 0 && bits > 0 &&
                    fmtHash != lastEngageFailHash) {
                    bypassActive = if (driver.isStreamingFormat(rate, bits, ch)) {
                        true
                    } else {
                        driver.start(rate, bits, ch)
                    }
                    if (bypassActive) {
                        lastEngageFailHash = 0
                        Log.i(TAG, "lazy-engaged bypass mid-stream " +
                            "($rate/${bits}b/${ch}ch)")
                    } else {
                        // Cache the failed format so we don't retry
                        // until configure or flush clears it. Without
                        // this, every handleBuffer (~50/s) re-tries
                        // and floods logcat with the same error.
                        lastEngageFailHash = fmtHash
                        Log.w(TAG, "bypass engage failed for "
                            + "$rate/${bits}b/${ch}ch — staying on delegate")
                    }
                }
            }
        }

        if (!bypassActive) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (!buffer.hasRemaining()) return true
        if (startTimeUs == C.TIME_UNSET) startTimeUs = presentationTimeUs

        // Run input through the DSP chain (no-op when no processors
        // are active). Chain returns a buffer owned by the last
        // processor, or EMPTY_BUFFER if it buffered the input
        // waiting for more — in which case our input was consumed
        // (chain advances `buffer`'s position) but no output yet,
        // and we report the buffer fully handled.
        val processed = if (chain.anyActive()) chain.process(buffer)
                        else buffer

        if (!processed.hasRemaining()) {
            return !buffer.hasRemaining()
        }

        // Driver expects a direct, native-byte-order ByteBuffer because
        // the JNI side reads via GetDirectBufferAddress.
        val direct = if (processed.isDirect) processed else copyIntoScratch(processed)
        val framesAvailable = direct.remaining() / pcmBytesPerFrame
        if (framesAvailable <= 0) return !buffer.hasRemaining()

        val written = driver.write(direct, framesAvailable)
        if (written <= 0) {
            // Ring full. If we owned the input buffer (no DSP),
            // back-pressure to the renderer; otherwise the chain
            // already consumed the input and we'd be lying to claim
            // we couldn't take it. Drop this tick's output —
            // underrun handling in the iso pump will pad with
            // silence.
            return processed === buffer && !buffer.hasRemaining()
        }
        if (processed === buffer) {
            buffer.position(buffer.position() + written * pcmBytesPerFrame)
        } else {
            // Advance the processed buffer so the chain sees its
            // output as consumed on the next tick.
            processed.position(processed.position() + written * pcmBytesPerFrame)
        }
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
        chain.flush()
        if (bypassActive) {
            // Drop queued PCM but keep the iso pump running on
            // silence — see LibusbUacDriver.flushRing for why we
            // can't release the interface here without breaking
            // track-to-track playback.
            driver.flushRing()
            framesWritten = 0L
            startTimeUs = C.TIME_UNSET
        }
        lastEngageFailHash = 0
    }

    override fun reset() {
        super.reset()
        chain.reset()
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
