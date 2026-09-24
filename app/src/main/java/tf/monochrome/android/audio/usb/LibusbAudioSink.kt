package tf.monochrome.android.audio.usb

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import tf.monochrome.android.audio.resample.VariRateAudioProcessor

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
    /**
     * The varispeed resampler from [processors], if it is in there.
     *
     * Handed in separately because this path has to drive it by hand.
     * DefaultAudioSink runs its chain's `applyPlaybackParameters` only from
     * its own processing path, and in bypass it never processes a buffer — so
     * the ratio Media3 hands the sink would never reach the resampler and
     * speed would silently do nothing over USB.
     */
    private val resampler: VariRateAudioProcessor? = null,
    /**
     * The DSP for hi-res sources on the normal Android output (see
     * [HalMode.HIRES]), run here in float because DefaultAudioSink's float
     * branch would skip it. Empty disables that mode.
     */
    halProcessors: List<AudioProcessor> = emptyList(),
    /** The user's "Hi-res output" setting, read at configure time. */
    private val hiResHalEnabled: () -> Boolean = { false },
) : ForwardingAudioSink(delegate) {

    private val chain = AudioProcessorChain(processors)

    /**
     * How the delegate (Android's own output) is fed when bypass is not active.
     *
     * DefaultAudioSink runs the app's DSP only on its int branch, which narrows
     * anything wider than 16 bits first; its float branch keeps the resolution
     * but runs no DSP at all. So which one a stream takes is decided here:
     */
    private enum class HalMode {
        /** 16-bit (or narrower): straight through; the int branch runs the DSP as ever. */
        DIRECT,
        /** Hi-res at unity speed: the DSP runs here in float, the float branch plays the result. */
        HIRES,
        /**
         * Hi-res with hi-res output off, or speed/pitch away from unity (the
         * float path has no varispeed or Sonic): narrowed to 16 bits here, so the
         * int branch and its full chain run exactly as they did before.
         */
        NARROW,
    }

    private val trimmer = PcmTrimmingAudioProcessor()
    private val halAvailable = halProcessors.isNotEmpty()
    private val halChain = AudioProcessorChain(listOf(trimmer) + halProcessors)
    private val narrowChain = AudioProcessorChain(
        listOf(androidx.media3.common.audio.ToInt16PcmAudioProcessor())
    )
    private var halMode = HalMode.DIRECT
    // Processed output the delegate has not taken yet. Delivered before any new
    // input is accepted, so nothing is ever held across end of stream.
    private var halPending: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var unityParams = true
    // Set when speed moved across unity mid-track; applied once halPending is
    // empty, the only point at which the delegate holds no buffer of ours.
    private var halModeSwitchPending = false

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

    /**
     * Media seconds per output second, from the playback parameters.
     *
     * The resampler consumes [speedRatio] input frames per output frame, so
     * one output frame is that many media frames. Position accounting has to
     * scale by it or the progress bar runs at the wrong rate the moment speed
     * leaves 1.
     */
    private var speedRatio = 1f

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

    // What the last configure() was called with, so the delegate can be
    // configured again when the shared processors have to be handed back.
    private var configuredBufferSize = 0
    private var configuredOutputChannels: IntArray? = null

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?,
    ) {
        configuredFormat = inputFormat
        configuredBufferSize = specifiedBufferSize
        configuredOutputChannels = outputChannels
        configureDelegatePath(inputFormat)

        lastEngageFailHash = 0
        pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        endOfStreamRequested = false

        val rate = inputFormat.sampleRate
        val channels = inputFormat.channelCount
        // Both bail-outs below used to be silent. A track that reached here and
        // fell out left no trace at all: no "configured" line, the DAC still
        // streaming the previous track's rate with an empty ring, and the
        // pipeline panel reporting the stale chain rate because that is what it
        // prefers. Indistinguishable, in a log, from configure never being
        // called — which is the other half of the same question.
        if (rate <= 0 || channels <= 0 ||
            sourceBytesPerSample(inputFormat.pcmEncoding) <= 0
        ) {
            Log.w(
                TAG,
                "configure declined the input: ${rate}Hz ${channels}ch " +
                    "encoding=${inputFormat.pcmEncoding} " +
                    "(${encodingLabel(inputFormat.pcmEncoding)}, " +
                    "${sourceBytesPerSample(inputFormat.pcmEncoding)} bytes/sample) " +
                    "— delegate takes over",
            )
            bypassActive = false
            return
        }

        // Exclusive mode off — Bluetooth, the speaker, any HAL route — and the
        // delegate carries the audio. Leave the chain alone.
        //
        // The chain's processors are the same instances DefaultAudioSink runs,
        // and super.configure() above has just set them up for *its* pipeline,
        // which puts Media3's ToInt16PcmAudioProcessor in front of anything
        // wider than 16 bits. Configuring the chain here as well told them
        // the stream was float (ours widens instead), and the last configure
        // wins: the mixer then read the delegate's 16-bit samples two at a
        // time as floats and clamped the garbage to full scale. A 32-bit float
        // or 24-bit song on Bluetooth with the mixer on came out as a scream;
        // 16-bit was fine only because both paths agree on 16 bits.
        if (!driver.isOpen.value) {
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
            Log.w(
                TAG,
                "configure declined the chain output: ${out.sampleRate}Hz " +
                    "${out.channelCount}ch encoding=${out.encoding} " +
                    "(${encodingLabel(out.encoding)}) — delegate takes over",
            )
            bypassActive = false
            lastEngageFailHash = engageHash(rate, channels, inputFormat.pcmEncoding)
            handProcessorsToDelegate()
            return
        }

        val driverOpen = driver.isOpen.value
        bypassActive = driverOpen && engageDriver(out.sampleRate, out.channelCount, out.encoding)
        if (!bypassActive) {
            Log.w(
                TAG,
                "bypass not engaged for ${out.sampleRate}Hz ${out.channelCount}ch " +
                    "${encodingLabel(out.encoding)} (driverOpen=$driverOpen) " +
                    "— delegate takes over",
            )
            // tryLazyEngage would otherwise retry the same format on the next
            // buffer and configure everything a second time for nothing.
            lastEngageFailHash = engageHash(rate, channels, inputFormat.pcmEncoding)
            handProcessorsToDelegate()
        }

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
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        tryLazyEngage()
        checkDriverStillOwned()

        if (!bypassActive) {
            if (halModeSwitchPending && !halPending.hasRemaining()) {
                configuredFormat?.let { fmt ->
                    Log.i(TAG, "speed moved across unity — switching the delegate path")
                    configureDelegatePath(fmt)
                }
            }
            return when (halMode) {
                HalMode.DIRECT -> super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                HalMode.HIRES -> handleThroughHalChain(halChain, buffer, presentationTimeUs, encodedAccessUnitCount)
                HalMode.NARROW -> handleThroughHalChain(narrowChain, buffer, presentationTimeUs, encodedAccessUnitCount)
            }
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
    /**
     * Gives the shared processors back to the delegate's pipeline.
     *
     * Whichever path carries the audio has to be the last to configure them,
     * because they hold one format each and the two paths disagree about it
     * for anything wider than 16 bits (Media3 narrows to 16, the chain widens
     * to float). Configuring the delegate again rebuilds its pipeline, which
     * configures them for its format, and it flushes that in before it
     * handles the next buffer.
     */
    /** Identifies a source format whose engage attempt failed, to throttle retries. */
    private fun engageHash(rate: Int, channels: Int, encoding: Int): Int =
        (rate * 31 + channels) * 31 + encoding

    private fun handProcessorsToDelegate() {
        val fmt = configuredFormat ?: return
        try {
            configureDelegatePath(fmt)
        } catch (e: AudioSink.ConfigurationException) {
            Log.w(TAG, "could not hand the processors back to the delegate", e)
        }
    }

    private fun halModeFor(encoding: Int): HalMode = when {
        !Util.isEncodingHighResolutionPcm(encoding) -> HalMode.DIRECT
        halAvailable && unityParams && hiResHalEnabled() -> HalMode.HIRES
        else -> HalMode.NARROW
    }

    /**
     * Configures the delegate for [fmt] in whichever [HalMode] fits it now.
     * Also the step that makes the delegate the last to configure the shared
     * processors when it is the path carrying the audio (see
     * [handProcessorsToDelegate]): in HIRES they are configured by [halChain],
     * which is this path.
     */
    @Throws(AudioSink.ConfigurationException::class)
    private fun configureDelegatePath(fmt: Format) {
        halPending = AudioProcessor.EMPTY_BUFFER
        halModeSwitchPending = false
        var mode = halModeFor(fmt.pcmEncoding)
        val input = AudioProcessor.AudioFormat(fmt.sampleRate, fmt.channelCount, fmt.pcmEncoding)
        if (mode == HalMode.HIRES) {
            trimmer.setTrimFrameCount(fmt.encoderDelay, fmt.encoderPadding)
            val chainOut = halChain.configure(input)
            val out = if (chainOut != AudioProcessor.AudioFormat.NOT_SET) chainOut else input
            if (out.encoding == C.ENCODING_PCM_FLOAT && out.sampleRate > 0 && out.channelCount > 0) {
                halMode = mode
                // Delay and padding are this chain's to trim now (the float
                // branch would ignore them anyway); channel count is what the
                // DSP made of it (mono and multichannel come out stereo).
                super.configure(
                    fmt.buildUpon()
                        .setPcmEncoding(C.ENCODING_PCM_FLOAT)
                        .setSampleRate(out.sampleRate)
                        .setChannelCount(out.channelCount)
                        .setEncoderDelay(0)
                        .setEncoderPadding(0)
                        .build(),
                    configuredBufferSize,
                    null,
                )
                Log.i(TAG, "delegate: hi-res float path (${fmt.sampleRate}/${encodingLabel(fmt.pcmEncoding)}/" +
                    "${fmt.channelCount}ch -> DSP -> ${out.sampleRate}/float/${out.channelCount}ch)")
                return
            }
            Log.w(TAG, "delegate: hi-res chain produced ${encodingLabel(out.encoding)}, not float — narrowing instead")
            mode = HalMode.NARROW
        }
        halMode = mode
        when (mode) {
            HalMode.NARROW -> {
                narrowChain.configure(input)
                super.configure(
                    fmt.buildUpon().setPcmEncoding(C.ENCODING_PCM_16BIT).build(),
                    configuredBufferSize,
                    configuredOutputChannels,
                )
                Log.i(TAG, "delegate: 16-bit path for ${encodingLabel(fmt.pcmEncoding)} " +
                    "(hi-res output=${hiResHalEnabled()}, unity speed=$unityParams)")
            }
            else -> super.configure(fmt, configuredBufferSize, configuredOutputChannels)
        }
    }

    /** Feeds the delegate through [c], never handing it a new buffer while it holds one of ours. */
    private fun handleThroughHalChain(
        c: AudioProcessorChain,
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (halPending.hasRemaining()) {
            super.handleBuffer(halPending, presentationTimeUs, encodedAccessUnitCount)
            if (halPending.hasRemaining()) return false
            halPending = AudioProcessor.EMPTY_BUFFER
        }
        if (!buffer.hasRemaining()) return true

        val processed = if (c.anyActive()) c.process(buffer) else buffer
        if (processed === buffer) {
            // Nothing to do to it: the delegate consumes the renderer's buffer itself.
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
        if (processed.hasRemaining()) {
            super.handleBuffer(processed, presentationTimeUs, encodedAccessUnitCount)
            if (processed.hasRemaining()) {
                halPending = processed
                return false
            }
        }
        return !buffer.hasRemaining()
    }

    private fun checkDriverStillOwned() {
        if (!bypassActive) return
        if (driver.isOpen.value && driver.isStreaming.value) return

        Log.i(TAG, "driver released the DAC — disengaging bypass, delegate takes over")
        bypassActive = false
        pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        // The processors are still configured for the chain's format.
        handProcessorsToDelegate()
        // Clear the engage throttle so a replug re-engages on the next buffer
        // instead of waiting for a configure()/flush().
        lastEngageFailHash = 0
        resetWatchdog()
    }

    private fun tryLazyEngage() {
        if (bypassActive || !driver.isOpen.value) return

        val fmt = configuredFormat ?: return
        val input = AudioProcessor.AudioFormat(fmt.sampleRate, fmt.channelCount, fmt.pcmEncoding)
        if (input.sampleRate <= 0 || input.channelCount <= 0 ||
            sourceBytesPerSample(input.encoding) <= 0
        ) return

        val fmtHash = engageHash(input.sampleRate, input.channelCount, input.encoding)
        if (fmtHash == lastEngageFailHash) return

        // configure() skipped the chain while the driver was closed, so it is
        // configured for nothing (or for an earlier track). Configure it for
        // this stream now; that also takes the shared processors over from
        // the delegate, and they are handed back below if the DAC says no.
        val chainOut = chain.configure(input)
        val out = if (chainOut != AudioProcessor.AudioFormat.NOT_SET) chainOut else input
        val rate = out.sampleRate
        val channels = out.channelCount
        val encoding = out.encoding
        if (rate <= 0 || channels <= 0 || sourceBytesPerSample(encoding) <= 0) {
            lastEngageFailHash = fmtHash
            handProcessorsToDelegate()
            return
        }

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
            handProcessorsToDelegate()
        }
    }

    /**
     * Drives the varispeed resampler, which nothing else on this path will.
     *
     * Mirrors the rides-tempo decision [TryptifyAudioProcessorChain] makes:
     * pitch equal to speed and away from unity is a record played faster, and
     * goes to the resampler. Anything else — preserve-pitch speed — is Sonic's
     * job, and Sonic is not in the bypass chain, so the ratio stays at 1 and
     * speed does not apply. Better than resampling it here and changing the
     * pitch the user asked to keep.
     */
    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        super.setPlaybackParameters(playbackParameters)
        val speed = playbackParameters.speed
        val pitch = playbackParameters.pitch
        val ridesTempo = abs(pitch - speed) < SPEED_TOLERANCE &&
            abs(speed - 1f) >= SPEED_TOLERANCE
        val ratio = if (ridesTempo) speed else 1f
        speedRatio = ratio
        // The float path has no varispeed or Sonic: away from unity a hi-res
        // stream has to take the 16-bit path, whose chain has both.
        val nowUnity = abs(speed - 1f) < SPEED_TOLERANCE && abs(pitch - 1f) < SPEED_TOLERANCE
        if (nowUnity != unityParams) {
            unityParams = nowUnity
            val fmt = configuredFormat
            if (fmt != null && halModeFor(fmt.pcmEncoding) != halMode) halModeSwitchPending = true
        }
        resampler?.setRatio(ratio)
        // Speed and the mixer interact here and nowhere else, and until now
        // this path wrote nothing to the log at all — so a report of "the
        // mixer stopped when I used speed" had no evidence to sit on. Logs
        // what the sink was actually asked for, what it decided, and (via the
        // chain, below) which stages that left running.
        Log.i(
            TAG,
            "playback params: speed=$speed pitch=$pitch -> " +
                "${if (ridesTempo) "varispeed" else "stretch/none"} ratio=$ratio " +
                "(bypass=$bypassActive)",
        )
        // Setting the ratio is not enough on its own: the resampler is only a
        // member of the chain while that ratio is away from 1, and membership
        // is otherwise fixed at configure. A track configured at 1.00x had
        // already skipped it, so the new ratio went to a processor nothing was
        // calling.
        chain.refreshActive()
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
        val outputFramesPlayed = minOf(playedDelta, framesWritten)
        // Scaled by the ratio: these are output frames, and at 2x one second
        // of them carries two seconds of media. Without this the progress bar
        // crawls at half speed while the music plays twice as fast.
        val mediaFramesPlayed = (outputFramesPlayed * speedRatio).toLong()
        return startTimeUs + mediaFramesPlayed * 1_000_000L / rate
    }

    override fun hasPendingData(): Boolean {
        if (!bypassActive) return halPending.hasRemaining() || super.hasPendingData()
        return pendingProcessedOutput.hasRemaining() || driver.pendingFrames() > 0L
    }

    override fun isEnded(): Boolean {
        if (!bypassActive) return super.isEnded()
        return endOfStreamRequested && !hasPendingData()
    }

    override fun flush() {
        super.flush()
        chain.flush()
        halChain.flush()
        narrowChain.flush()
        halPending = AudioProcessor.EMPTY_BUFFER

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
        halChain.reset()
        narrowChain.reset()
        halPending = AudioProcessor.EMPTY_BUFFER
        halModeSwitchPending = false
        halMode = HalMode.DIRECT
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

    /**
     * Float is supported directly only on the normal Android output, and only
     * with hi-res output on. The decoders ask this before choosing their output
     * format: said yes, MediaCodec decodes to float, which is what carries a
     * 24-bit FLAC past 16 bits. While the USB DAC is open the answer stays what
     * it always was, so a 16-bit file still reaches the DAC as 16-bit integers,
     * bit-perfect.
     */
    override fun getFormatSupport(format: Format): Int {
        val support = super.getFormatSupport(format)
        if (format.pcmEncoding == C.ENCODING_PCM_FLOAT &&
            support == AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY &&
            (driver.isOpen.value || !hiResHalEnabled() || !halAvailable)
        ) {
            return AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING
        }
        return support
    }

    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun release() {
        pendingProcessedOutput = AudioProcessor.EMPTY_BUFFER
        halPending = AudioProcessor.EMPTY_BUFFER
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
        /**
         * How far speed or pitch must sit from unity to count as a change.
         * Matches TryptifyAudioProcessorChain's own dead zone so the two paths
         * agree on when varispeed is running.
         */
        private const val SPEED_TOLERANCE = 1e-4f
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
