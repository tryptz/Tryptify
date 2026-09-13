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
    private var pcmBytesPerFrame = 0
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
        val bits = pcmBitsFromEncoding(inputFormat.pcmEncoding)
        if (rate <= 0 || channels <= 0 || bits <= 0) {
            bypassActive = false
            return
        }

        val chainOut = chain.configure(
            AudioProcessor.AudioFormat(rate, channels, inputFormat.pcmEncoding)
        )
        val outRate = if (chainOut != AudioProcessor.AudioFormat.NOT_SET) {
            chainOut.sampleRate
        } else {
            rate
        }
        val outChannels = if (chainOut != AudioProcessor.AudioFormat.NOT_SET) {
            chainOut.channelCount
        } else {
            channels
        }
        val outBits = if (chainOut != AudioProcessor.AudioFormat.NOT_SET) {
            pcmBitsFromEncoding(chainOut.encoding)
        } else {
            bits
        }

        if (outRate <= 0 || outChannels <= 0 || outBits <= 0) {
            bypassActive = false
            return
        }

        pcmBytesPerFrame = (outBits / 8) * outChannels
        outBitsPerSample = outBits

        bypassActive = when {
            !driver.isOpen.value -> false
            driver.isStreamingFormat(outRate, outBits, outChannels) -> {
                Log.i(TAG, "configured: reused active stream ($outRate/${outBits}b/${outChannels}ch)")
                true
            }
            else -> {
                driver.start(outRate, outBits, outChannels).also { ok ->
                    if (ok) {
                        Log.i(
                            TAG,
                            "configured: bypass active " +
                                "(in $rate/${bits}b/${channels}ch -> out $outRate/${outBits}b/${outChannels}ch)",
                        )
                    }
                }
            }
        }

        if (bypassActive) resetStreamAccounting()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        tryLazyEngage()

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
        if (!processed.hasRemaining() || pcmBytesPerFrame <= 0) return 0

        val direct = if (processed.isDirect) processed else copyIntoScratch(processed)
        val framesAvailable = direct.remaining() / pcmBytesPerFrame
        if (framesAvailable <= 0) return 0

        val gain = volumeController.getVolume()
        val toWrite = if (gain >= 0.9999f || outBitsPerSample != 16) {
            direct
        } else {
            applyGainPcm16(direct, gain)
        }

        val positionedView = toWrite.slice().order(ByteOrder.nativeOrder())
        val written = driver.write(positionedView, framesAvailable)

        if (written > 0) {
            val bytesWritten = written * pcmBytesPerFrame
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

    private fun tryLazyEngage() {
        if (bypassActive || pcmBytesPerFrame <= 0 || !driver.isOpen.value) return

        val fmt = configuredFormat ?: return
        val chainOut = chain.outputFormat()

        val rate: Int
        val channels: Int
        val bits: Int
        if (chainOut != AudioProcessor.AudioFormat.NOT_SET) {
            rate = chainOut.sampleRate
            channels = chainOut.channelCount
            bits = pcmBitsFromEncoding(chainOut.encoding)
        } else {
            rate = fmt.sampleRate
            channels = fmt.channelCount
            bits = pcmBitsFromEncoding(fmt.pcmEncoding)
        }

        if (rate <= 0 || channels <= 0 || bits <= 0) return

        val fmtHash = (rate * 31 + channels) * 31 + bits
        if (fmtHash == lastEngageFailHash) return

        bypassActive = if (driver.isStreamingFormat(rate, bits, channels)) {
            true
        } else {
            driver.start(rate, bits, channels)
        }

        if (bypassActive) {
            lastEngageFailHash = 0
            pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
            endOfStreamRequested = false
            resetStreamAccounting()
            Log.i(TAG, "lazy-engaged bypass mid-stream ($rate/${bits}b/${channels}ch)")
        } else {
            lastEngageFailHash = fmtHash
            Log.w(TAG, "bypass engage failed for $rate/${bits}b/${channels}ch — staying on delegate")
        }
    }

    override fun pause() {
        super.pause()
        if (bypassActive) {
            // Gate first, flush second: a renderer that is still mid-feed when
            // pause lands must not be able to refill the ring behind the
            // flush. With the gate up the ring stays empty and the DAC goes
            // silent on its next iso packet.
            paused = true
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
        if (bypassActive) {
            paused = false
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
        if (rate <= 0 || startTimeUs == C.TIME_UNSET) return C.TIME_UNSET

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

    companion object {
        private const val TAG = "LibusbAudioSink"
        private const val kIsoWarmupNs = 400_000_000L
        private const val kIsoStallNs = 400_000_000L
    }
}
