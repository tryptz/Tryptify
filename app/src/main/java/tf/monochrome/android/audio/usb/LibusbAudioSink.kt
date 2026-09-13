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
 * AudioSink wrapper that routes decoded PCM directly to the libusb UAC driver
 * while exclusive USB output is active, and otherwise forwards to Media3's
 * normal sink.
 *
 * The exclusive path drives the app's DSP processors manually. Processor
 * output must therefore obey the same back-pressure contract as Media3's
 * AudioTrack sink: once a processor has consumed input, every produced byte
 * must be retained until the USB ring accepts it. Dropping a partially written
 * processor buffer corrupts the PCM stream even though the iso pump itself is
 * healthy.
 */
@UnstableApi
class LibusbAudioSink(
    delegate: AudioSink,
    private val driver: LibusbUacDriver,
    private val volumeController: BypassVolumeController,
    processors: List<AudioProcessor> = emptyList(),
) : ForwardingAudioSink(delegate) {

    private val chain = AudioProcessorChain(processors)

    private var bypassActive = false
    private var configuredFormat: Format? = null

    /**
     * Stride of the PCM the chain hands us — 4 bytes/sample for float, 2 for
     * 16-bit. Distinct from the USB stride because the two differ the moment
     * the DAC runs at a width the chain does not: a float chain feeding a
     * 24-bit stream is 8 bytes in and 6 out per stereo frame. Everything that
     * advances a Media3 buffer counts in this one.
     */
    private var sourceBytesPerFrame = 0
    private var sourceIsFloat = false

    /**
     * Subslot size the driver negotiated, from the device's own descriptor —
     * NOT bitsPerSample / 8. A 24-bit alt may carry each sample in 4 bytes
     * (bSubslotSize = 4, sample left-justified), and the driver strides its
     * ring by exactly this. Guessing would desync the two and the JNI bounds
     * check would reject every write.
     */
    private var usbBytesPerSample = 0
    private var outChannels = 0
    private var outBitsPerSample = 0

    private var framesWritten = 0L
    private var startTimeUs = C.TIME_UNSET
    private var positionPlayedBaseFrames = 0L
    private var endOfStreamRequested = false

    /**
     * Set by [pause], cleared by [play]. While set, the bypass path refuses
     * every buffer: [pause] flushed the DAC's ring, and anything accepted
     * afterwards would reach the speakers through the iso pump — the exact
     * "pause takes a second to stop" symptom. Hard back-pressure keeps the
     * ring empty until playback resumes; Media3 holds the buffer and
     * re-presents it on the next handleBuffer after [play].
     */
    private var paused = false

    /**
     * Output owned by the final AudioProcessor that has already consumed its
     * corresponding Media3 input but has not yet fully fitted in the USB ring.
     * We never call chain.process() again until this buffer is empty, so the
     * processor cannot overwrite it under us.
     */
    private var pendingProcessedOutput: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    private var lastEngageFailHash = 0

    private var firstWriteNs = 0L
    private var lastPlayedFrames = 0L
    private var lastPlayedAdvanceNs = 0L
    private var watchdogTripped = false
    private var firstWriteLogged = false
    private var partialWriteLogged = false

    private var gainScratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var copyScratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var packScratch: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        super.configure(inputFormat, specifiedBufferSize, outputChannels)

        configuredFormat = inputFormat
        lastEngageFailHash = 0
        pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        endOfStreamRequested = false

        val rate = inputFormat.sampleRate
        val channels = inputFormat.channelCount
        if (rate <= 0 || channels <= 0 ||
            sourceBytesPerSample(inputFormat.pcmEncoding) <= 0
        ) {
            bypassActive = false
            return
        }

        val chainOut = chain.configure(
            AudioProcessor.AudioFormat(rate, channels, inputFormat.pcmEncoding)
        )
        val out = if (chainOut != AudioProcessor.AudioFormat.NOT_SET) {
            chainOut
        } else {
            AudioProcessor.AudioFormat(rate, channels, inputFormat.pcmEncoding)
        }

        if (out.sampleRate <= 0 || out.channelCount <= 0 ||
            sourceBytesPerSample(out.encoding) <= 0
        ) {
            bypassActive = false
            return
        }

        bypassActive = driver.isOpen.value &&
            engageDriver(out.sampleRate, out.channelCount, out.encoding)

        if (bypassActive) {
            Log.i(
                TAG,
                "configured: bypass active (chain ${out.sampleRate}/" +
                    "${encodingLabel(out.encoding)}/${out.channelCount}ch -> DAC " +
                    "${out.sampleRate}/${outBitsPerSample}b in " +
                    "${usbBytesPerSample}-byte subslots)",
            )
            resetStreamAccounting()
        }
    }

    /**
     * Brings the DAC up for [encoding] and records the stride it negotiated.
     *
     * A float chain asks for 24-bit and falls back to 16. Float carries a
     * 24-bit mantissa, so rounding it to 16 on the way out throws away
     * precision the DSP actually produced — but a DAC with no 24-bit alt at
     * this rate must still get audio rather than being dropped to the HAL,
     * which is what a single failed start() would have done.
     */
    private fun engageDriver(rate: Int, channels: Int, encoding: Int): Boolean {
        for (bits in usbBitDepthLadder(encoding)) {
            val reused = driver.isStreamingFormat(rate, bits, channels)
            if (reused) Log.i(TAG, "reused active stream ($rate/${bits}b/${channels}ch)")
            if (reused || driver.start(rate, bits, channels)) {
                adoptNegotiatedFormat(bits, channels, encoding)
                // Integer PCM reaches the DAC untouched, so its stride has to
                // match the subslot the device negotiated. Normally it does —
                // 16-bit is two bytes everywhere — but a 24-bit alt may use
                // 4-byte subslots, and handing it 3-byte frames would have the
                // driver read past the buffer. The JNI bounds check turns that
                // into a dropped write and an error line per buffer, i.e.
                // silence, so take the next rung (or the delegate) instead.
                val sourceStride = sourceBytesPerSample(encoding)
                if (!sourceIsFloat && usbBytesPerSample != sourceStride) {
                    Log.w(
                        TAG,
                        "DAC negotiated ${usbBytesPerSample}-byte subslots at ${bits}b but " +
                            "the chain emits $sourceStride bytes/sample — not engaging at this depth",
                    )
                    continue
                }
                return true
            }
        }
        return false
    }

    private fun adoptNegotiatedFormat(bits: Int, channels: Int, encoding: Int) {
        outBitsPerSample = bits
        outChannels = channels
        sourceIsFloat = encoding == C.ENCODING_PCM_FLOAT
        sourceBytesPerFrame = sourceBytesPerSample(encoding) * channels
        // Ask the driver what it actually negotiated rather than assuming
        // bits / 8 — see the note on [usbBytesPerSample]. The guard keeps a
        // stale snapshot from a previous stream out of the arithmetic.
        usbBytesPerSample = driver.diagnostics.value
            ?.takeIf { it.bitsPerSample == bits && it.channels == channels }
            ?.bytesPerSample
            ?.takeIf { it > 0 }
            ?: (bits / 8)
        usbBytesPerFrame = usbBytesPerSample * channels
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        tryLazyEngage()
        checkDriverStillOwned()

        if (!bypassActive) {
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        // Paused: refuse everything. flushRing() emptied the DAC's queue at
        // pause(); a buffer accepted now would play through the iso pump.
        if (paused) return false

        endOfStreamRequested = false

        // Drain processor output retained from a previous partial USB write
        // before accepting another Media3 input buffer.
        if (pendingProcessedOutput.hasRemaining()) {
            drainPendingProcessedOutput()
            if (pendingProcessedOutput.hasRemaining()) {
                return false
            }
        }

        if (!buffer.hasRemaining()) return true

        if (startTimeUs == C.TIME_UNSET) {
            startTimeUs = presentationTimeUs
            positionPlayedBaseFrames = driver.playedFrames()
        }

        val processed = if (chain.anyActive()) {
            chain.process(buffer)
        } else {
            buffer
        }

        if (!processed.hasRemaining()) {
            return !buffer.hasRemaining()
        }

        if (processed !== buffer) {
            pendingProcessedOutput = processed
        }

        val written = writeProcessedBuffer(processed)
        if (written <= 0) {
            return !buffer.hasRemaining()
        }

        if (processed !== buffer && !processed.hasRemaining()) {
            pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        }

        return !buffer.hasRemaining()
    }

    /**
     * Writes from [processed.position] and advances [processed] by exactly the
     * number of frames native accepted.
     *
     * JNI GetDirectBufferAddress() points at the allocation base, not at
     * ByteBuffer.position(). Slicing here makes address zero correspond to the
     * current PCM position, so a partial USB write resumes at the correct frame
     * instead of replaying the beginning of the chunk.
     */
    private fun writeProcessedBuffer(processed: ByteBuffer): Int {
        if (!processed.hasRemaining() || sourceBytesPerFrame <= 0) return 0

        val direct = if (processed.isDirect) processed else copyIntoScratch(processed)
        val framesAvailable = direct.remaining() / sourceBytesPerFrame
        if (framesAvailable <= 0) return 0

        val gain = volumeController.getVolume()
        val toWrite = when {
            // Float chain: gain and the pack down to the DAC's subslot happen
            // in one pass. This is also what gives 24-bit output a working
            // volume control — the integer path only ever had a 16-bit fast
            // path and silently skipped attenuation at any other depth.
            sourceIsFloat -> packFloatForUsb(direct, framesAvailable, gain)
            gain >= 0.9999f || outBitsPerSample != 16 -> direct
            else -> applyGainPcm16(direct, gain)
        }

        val positionedView = toWrite.slice().order(ByteOrder.nativeOrder())
        val written = driver.write(positionedView, framesAvailable)

        if (written > 0) {
            // Source stride, not USB stride: this advances the Media3 buffer,
            // which is still the chain's PCM however narrow the DAC is.
            val bytesWritten = written * sourceBytesPerFrame
            processed.position((processed.position() + bytesWritten).coerceAtMost(processed.limit()))
            framesWritten += written

            if (!firstWriteLogged) {
                firstWriteLogged = true
                Log.i(
                    TAG,
                    "bypass first write succeeded — wrote $written frames at ${outBitsPerSample}b, gain=$gain",
                )
            }

            if (written < framesAvailable && !partialWriteLogged) {
                partialWriteLogged = true
                Log.i(
                    TAG,
                    "USB ring back-pressure: retained ${framesAvailable - written} frames for next drain",
                )
            }
        }

        checkIsoPumpWatchdog()
        return written
    }

    private fun drainPendingProcessedOutput() {
        val pending = pendingProcessedOutput
        if (!pending.hasRemaining()) {
            pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
            return
        }

        writeProcessedBuffer(pending)
        if (!pending.hasRemaining()) {
            pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        }
    }

    /**
     * Hands the stream back to the delegate the moment the driver stops owning
     * the DAC.
     *
     * An unplug or an exclusive-mode toggle tears the stream down on
     * UsbExclusiveController's own IO thread, so [bypassActive] is still true
     * here while every subsequent driver.write() is guaranteed to fail. Before
     * this check the only exit was the 400 ms wedge watchdog, which cost a full
     * audible dropout and ~80 "no active stream format" JNI error lines (one
     * per renderer tick) before the delegate took over. Both flags are cleared
     * only by an explicit close()/stop(), so this cannot false-trip mid-stream.
     */
    private fun checkDriverStillOwned() {
        if (!bypassActive) return
        if (driver.isOpen.value && driver.isStreaming.value) return

        Log.i(TAG, "driver released the DAC — disengaging bypass, delegate takes over")
        bypassActive = false
        pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        // Clear the engage throttle so a replug re-engages on the next buffer
        // instead of waiting for a configure()/flush().
        lastEngageFailHash = 0
        resetWatchdog()
    }

    private fun tryLazyEngage() {
        if (bypassActive || !driver.isOpen.value) return

        val fmt = configuredFormat ?: return
        val chainOut = chain.outputFormat()

        val rate: Int
        val channels: Int
        val encoding: Int
        if (chainOut != AudioProcessor.AudioFormat.NOT_SET) {
            rate = chainOut.sampleRate
            channels = chainOut.channelCount
            encoding = chainOut.encoding
        } else {
            rate = fmt.sampleRate
            channels = fmt.channelCount
            encoding = fmt.pcmEncoding
        }

        if (rate <= 0 || channels <= 0 || sourceBytesPerSample(encoding) <= 0) return

        val fmtHash = (rate * 31 + channels) * 31 + encoding
        if (fmtHash == lastEngageFailHash) return

        bypassActive = engageDriver(rate, channels, encoding)

        if (bypassActive) {
            lastEngageFailHash = 0
            pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
            endOfStreamRequested = false
            resetStreamAccounting()
            Log.i(
                TAG,
                "lazy-engaged bypass mid-stream (chain $rate/${encodingLabel(encoding)}/" +
                    "${channels}ch -> DAC ${outBitsPerSample}b)",
            )
        } else {
            lastEngageFailHash = fmtHash
            Log.w(
                TAG,
                "bypass engage failed for $rate/${encodingLabel(encoding)}/${channels}ch " +
                    "— staying on delegate",
            )
        }
    }

    override fun pause() {
        super.pause()
        // Gate first, flush second: a renderer that is still mid-feed when
        // pause lands must not be able to refill the ring behind the flush.
        // With the gate up the ring stays empty and the DAC goes silent on
        // its next iso packet.
        //
        // Set unconditionally, outside the bypassActive check: `paused`
        // mirrors transport state, not bypass state. Gating it meant that if
        // bypass dropped while paused (DAC unplugged, exclusive mode toggled
        // off, watchdog trip), the matching play() also no-opped and the gate
        // stayed up — so the next engage refused every buffer forever, with
        // no log and no recovery short of reset().
        paused = true
        if (bypassActive) {
            pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
            driver.flushRing()
            framesWritten = 0L
            startTimeUs = C.TIME_UNSET
            positionPlayedBaseFrames = 0L
            endOfStreamRequested = false
        }
    }

    override fun play() {
        super.play()
        // Unconditional, for the same reason as pause(): the gate must come
        // down even if bypass happened to be off when the user hit play.
        paused = false
        if (bypassActive) {
            resetWatchdog()
            startTimeUs = C.TIME_UNSET
            positionPlayedBaseFrames = 0L
            endOfStreamRequested = false
        }
    }

    override fun playToEndOfStream() {
        if (!bypassActive) {
            super.playToEndOfStream()
            return
        }

        endOfStreamRequested = true
        // Same rule as handleBuffer: nothing may reach the ring while paused,
        // or the tail of the track plays after the user hit pause.
        if (!paused && pendingProcessedOutput.hasRemaining()) {
            drainPendingProcessedOutput()
        }
    }

    private fun checkIsoPumpWatchdog() {
        if (watchdogTripped || !bypassActive) return

        val now = System.nanoTime()
        if (firstWriteNs == 0L) {
            if (!firstWriteLogged) return
            firstWriteNs = now
            lastPlayedFrames = driver.playedFrames()
            lastPlayedAdvanceNs = now
            return
        }

        val played = driver.playedFrames()
        if (played != lastPlayedFrames) {
            lastPlayedFrames = played
            lastPlayedAdvanceNs = now
            return
        }

        val sinceFirstWriteNs = now - firstWriteNs
        val sinceAdvanceNs = now - lastPlayedAdvanceNs
        if (sinceFirstWriteNs > kIsoWarmupNs && sinceAdvanceNs > kIsoStallNs) {
            Log.w(
                TAG,
                "iso pump wedged — playedFrames=$played stuck for " +
                    "${sinceAdvanceNs / 1_000_000} ms after $framesWritten frames written; " +
                    "falling back to delegate sink. Re-engages on next configure/flush.",
            )
            watchdogTripped = true
            bypassActive = false
            pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (!bypassActive) return super.getCurrentPositionUs(sourceEnded)

        val rate = chain.outputFormat().let {
            if (it != AudioProcessor.AudioFormat.NOT_SET) it.sampleRate
            else configuredFormat?.sampleRate ?: 0
        }
        // CURRENT_POSITION_NOT_SET, not C.TIME_UNSET: the two differ by one
        // (Long.MIN_VALUE vs Long.MIN_VALUE + 1) and only the former is
        // filtered by MediaCodecAudioRenderer.updateCurrentPosition(). Worse,
        // the guard it does pass assigns straight through while
        // allowPositionDiscontinuity is set — which onPositionReset() sets on
        // every seek, exactly when startTimeUs is back to unset. Returning
        // TIME_UNSET here reported a position of Long.MIN_VALUE + 1.
        if (rate <= 0 || startTimeUs == C.TIME_UNSET) return AudioSink.CURRENT_POSITION_NOT_SET

        val playedDelta = (driver.playedFrames() - positionPlayedBaseFrames).coerceAtLeast(0L)
        val mediaFramesPlayed = minOf(playedDelta, framesWritten)
        return startTimeUs + mediaFramesPlayed * 1_000_000L / rate
    }

    override fun hasPendingData(): Boolean {
        if (!bypassActive) return super.hasPendingData()
        return pendingProcessedOutput.hasRemaining() || driver.pendingFrames() > 0L
    }

    override fun isEnded(): Boolean {
        if (!bypassActive) return super.isEnded()
        return endOfStreamRequested && !hasPendingData()
    }

    override fun flush() {
        super.flush()
        chain.flush()

        pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        endOfStreamRequested = false
        if (bypassActive) {
            driver.flushRing()
            framesWritten = 0L
            startTimeUs = C.TIME_UNSET
            positionPlayedBaseFrames = 0L
        }

        lastEngageFailHash = 0
        resetWatchdog()
    }

    override fun reset() {
        super.reset()
        chain.reset()
        if (driver.isStreaming.value) driver.stop()

        bypassActive = false
        paused = false
        pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        framesWritten = 0L
        startTimeUs = C.TIME_UNSET
        positionPlayedBaseFrames = 0L
        endOfStreamRequested = false
        lastEngageFailHash = 0
        resetWatchdog()
    }

    override fun release() {
        pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        super.release()
        if (driver.isStreaming.value) driver.stop()
    }

    private fun resetStreamAccounting() {
        framesWritten = 0L
        startTimeUs = C.TIME_UNSET
        positionPlayedBaseFrames = 0L
        endOfStreamRequested = false
        resetWatchdog()
    }

    private fun resetWatchdog() {
        firstWriteNs = 0L
        lastPlayedFrames = 0L
        lastPlayedAdvanceNs = 0L
        watchdogTripped = false
        firstWriteLogged = false
        partialWriteLogged = false
    }

    private fun applyGainPcm16(src: ByteBuffer, gain: Float): ByteBuffer {
        val srcPos = src.position()
        val totalBytes = src.remaining()
        val scratch = ensureGainScratch(totalBytes)
        val numSamples = totalBytes / 2

        for (i in 0 until numSamples) {
            val sample = src.getShort(srcPos + i * 2).toInt()
            val scaled = (sample * gain).toInt().coerceIn(-32768, 32767)
            scratch.putShort(i * 2, scaled.toShort())
        }

        scratch.position(0)
        scratch.limit(numSamples * 2)
        return scratch
    }

    /**
     * Converts [frames] frames of native-order float PCM into the DAC's
     * subslot width, applying [gain] on the way.
     *
     * Scales to a 24-bit sample and then keeps the top [usbBytesPerSample]
     * bytes, which is what left-justification means: USB Audio Type I places
     * a sample narrower than its subslot in the high bits with the low ones
     * zeroed — the same mapping snd-usb-audio uses when it reports a
     * 24-bit-in-4-byte alt as S32_LE. One scale therefore covers 2-, 3- and
     * 4-byte subslots with no per-width special case beyond which bytes to
     * emit. USB PCM is always little-endian, hence the explicit byte order
     * rather than the buffer's.
     */
    private fun packFloatForUsb(src: ByteBuffer, frames: Int, gain: Float): ByteBuffer {
        val bytesPerSample = usbBytesPerSample
        val samples = frames * outChannels
        val out = ensurePackScratch(samples * bytesPerSample)
        val srcPos = src.position()

        var o = 0
        for (i in 0 until samples) {
            val sample = floatToSubslotSample(
                src.getFloat(srcPos + (i shl 2)) * gain,
                bytesPerSample,
            )
            out.put(o, sample.toByte())
            if (bytesPerSample > 1) out.put(o + 1, (sample shr 8).toByte())
            if (bytesPerSample > 2) out.put(o + 2, (sample shr 16).toByte())
            if (bytesPerSample > 3) out.put(o + 3, (sample shr 24).toByte())
            o += bytesPerSample
        }

        out.position(0)
        out.limit(samples * bytesPerSample)
        return out
    }

    private fun ensurePackScratch(needBytes: Int): ByteBuffer {
        if (packScratch.capacity() < needBytes) {
            packScratch = ByteBuffer.allocateDirect(needBytes)
                .order(ByteOrder.nativeOrder())
        } else {
            packScratch.clear()
        }
        return packScratch
    }

    private fun ensureGainScratch(needBytes: Int): ByteBuffer {
        if (gainScratch.capacity() < needBytes) {
            gainScratch = ByteBuffer.allocateDirect(needBytes)
                .order(ByteOrder.nativeOrder())
        } else {
            gainScratch.clear()
        }
        return gainScratch
    }

    private fun copyIntoScratch(buffer: ByteBuffer): ByteBuffer {
        val needBytes = buffer.remaining()
        if (copyScratch.capacity() < needBytes) {
            copyScratch = ByteBuffer.allocateDirect(needBytes)
                .order(ByteOrder.nativeOrder())
        }

        copyScratch.clear()
        val originalPosition = buffer.position()
        copyScratch.put(buffer)
        buffer.position(originalPosition)
        copyScratch.flip()
        return copyScratch
    }

    private fun pcmBitsFromEncoding(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_16BIT -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT -> 32
        else -> 0
    }

    /** Bytes one sample of [encoding] occupies in the chain's own buffers. */
    private fun sourceBytesPerSample(encoding: Int): Int =
        if (encoding == C.ENCODING_PCM_FLOAT) 4 else pcmBitsFromEncoding(encoding) / 8

    /**
     * Widths to offer the DAC for [encoding], best first.
     *
     * Only float gets a ladder. An integer chain has exactly as many bits as
     * it has, so there is nothing to gain by asking for more and no converter
     * here to narrow it if the DAC wants less — offering one width keeps that
     * case behaving exactly as it did.
     */
    private fun usbBitDepthLadder(encoding: Int): IntArray =
        if (encoding == C.ENCODING_PCM_FLOAT) intArrayOf(24, 16)
        else intArrayOf(pcmBitsFromEncoding(encoding))

    private fun encodingLabel(encoding: Int): String =
        if (encoding == C.ENCODING_PCM_FLOAT) "float" else "${pcmBitsFromEncoding(encoding)}b"

    companion object {
        private const val TAG = "LibusbAudioSink"
        private const val kIsoWarmupNs = 400_000_000L
        private const val kIsoStallNs = 400_000_000L
    }
}

/**
 * A float sample as the little-endian integer a USB Audio Type I subslot of
 * [bytesPerSample] bytes carries.
 *
 * Scales to 24 bits and then shifts, because that is what left-justification
 * means: a sample narrower than its subslot sits in the subslot's high bits
 * with the low ones zeroed — the same mapping snd-usb-audio uses when it
 * reports a 24-bit-in-4-byte alt as S32_LE. 2^23 - 1 is exactly representable
 * as a float, so the product cannot overflow the Int conversion the way a 2^31
 * scale would, and NaN converts to zero rather than to garbage.
 *
 * A free function, like [tf.monochrome.android.visualizer.shouldDropFrame],
 * because every way to get this wrong still plays: a bad scale is distortion
 * at full level, and a missed shift is 48 dB of attenuation on a 4-byte DAC.
 */
internal fun floatToSubslotSample(value: Float, bytesPerSample: Int): Int {
    val clamped = if (value > 1f) 1f else if (value < -1f) -1f else value
    val sample24 = (clamped * 8_388_607f).toInt()
    return when {
        bytesPerSample >= 4 -> sample24 shl 8
        bytesPerSample == 2 -> sample24 shr 8
        else -> sample24
    }
}
