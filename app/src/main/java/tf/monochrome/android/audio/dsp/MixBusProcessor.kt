package tf.monochrome.android.audio.dsp

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.audio.dsp.oxford.CompressorEffect
import tf.monochrome.android.audio.dsp.oxford.InflatorEffect
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(UnstableApi::class)
class MixBusProcessor @Inject constructor(
    private val inflator: InflatorEffect,
    private val compressor: CompressorEffect,
) : AudioProcessor {

    private var enginePtr: Long = 0L
    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false
    private val _engineReady = MutableStateFlow(false)
    val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

    // Scratch float arrays — allocated once per format change
    private var scratchInL = FloatArray(0)
    private var scratchInR = FloatArray(0)
    private var scratchOutL = FloatArray(0)
    private var scratchOutR = FloatArray(0)

    // Chunk-sized scratch — reused per queueInput when the user-selected
    // DSP block size is smaller than ExoPlayer's incoming buffer. Sized
    // up on demand and never shrinks, so steady-state has zero allocs.
    private var chunkScratchInL = FloatArray(0)
    private var chunkScratchInR = FloatArray(0)
    private var chunkScratchOutL = FloatArray(0)
    private var chunkScratchOutR = FloatArray(0)

    // TPDF dither state for PCM16 output (triangular probability density function)
    private var ditherState: Long = 1L

    // JNI native methods
    private external fun nativeCreate(sampleRate: Int, maxBlockSize: Int): Long
    private external fun nativeDestroy(enginePtr: Long)
    // Live format swap that keeps the bus graph + plugin instances alive.
    // ExoPlayer flushes AudioProcessors on every track transition; recreating
    // the engine at that point drops audio for 5–10 ms while plugin
    // constructors re-allocate FFT tables etc. — audible as a gap between
    // tracks of different sample rates (44.1k → 48k is the common case).
    private external fun nativeReconfigure(enginePtr: Long, sampleRate: Int, maxBlockSize: Int)
    private external fun nativeProcess(
        enginePtr: Long,
        inputL: FloatArray, inputR: FloatArray,
        outputL: FloatArray, outputR: FloatArray,
        numFrames: Int
    )

    external fun nativeSetBusGain(enginePtr: Long, busIndex: Int, gainDb: Float)
    external fun nativeSetBusPan(enginePtr: Long, busIndex: Int, pan: Float)
    external fun nativeSetBusMute(enginePtr: Long, busIndex: Int, muted: Boolean)
    external fun nativeSetBusSolo(enginePtr: Long, busIndex: Int, soloed: Boolean)
    external fun nativeAddPlugin(enginePtr: Long, busIndex: Int, slotIndex: Int, pluginType: Int): Int
    external fun nativeRemovePlugin(enginePtr: Long, busIndex: Int, slotIndex: Int)
    external fun nativeMovePlugin(enginePtr: Long, busIndex: Int, fromSlot: Int, toSlot: Int)
    external fun nativeSetParameter(enginePtr: Long, busIndex: Int, slotIndex: Int, paramIndex: Int, value: Float)
    external fun nativeSetPluginBypassed(enginePtr: Long, busIndex: Int, slotIndex: Int, bypassed: Boolean)
    external fun nativeSetPluginDryWet(enginePtr: Long, busIndex: Int, slotIndex: Int, dryWet: Float)
    external fun nativeSetBusInputEnabled(enginePtr: Long, busIndex: Int, enabled: Boolean)
    external fun nativeGetBusLevels(enginePtr: Long, outLevels: FloatArray)
    external fun nativeGetAndResetClipped(enginePtr: Long): Boolean
    external fun nativeResetPluginState(enginePtr: Long)
    external fun nativeSetMixBypassed(enginePtr: Long, bypassed: Boolean)
    external fun nativeGetStateJson(enginePtr: Long): String
    external fun nativeLoadStateJson(enginePtr: Long, stateJson: String)

    companion object {
        init { DspNativeLoader.ensureLoaded() }
        // Upper bound on the native engine's scratch allocation (sumL/R,
        // busL/R, dryBufL/R) and the chunk-scratch float arrays on the
        // Kotlin side. Sized to the largest entry in
        // PreferencesManager.DSP_BLOCK_SIZES so the user can pick 16k
        // without ever crossing a reconfigure path. ~6 × 16384 × 4B ≈
        // 384 KB resident; trivial.
        const val MAX_BLOCK_SIZE = 16384
    }

    fun getEnginePtr(): Long = enginePtr

    /** Bypass mix bus plugins (0-3) in the C++ engine. Master bus (AutoEQ) still runs. */
    fun setMixBypassed(bypassed: Boolean) {
        val ptr = enginePtr
        if (ptr != 0L) nativeSetMixBypassed(ptr, bypassed)
    }

    // User-selectable block size. Smaller = lower latency + higher CPU /
    // JNI overhead; larger = lower CPU + slightly higher latency. The native
    // engine pre-allocates scratch up to MAX_BLOCK_SIZE so we can change
    // this on the fly via nativeReconfigure without ever needing to grow
    // the underlying vectors. queueInput() chunks each ExoPlayer buffer
    // into slices of this size.
    @Volatile
    private var blockSize: Int = 1024

    /**
     * True DSP bypass. When set, queueInput() copies its input straight to
     * the output ByteBuffer without any deinterleave / nativeProcess /
     * Oxford / interleave work — same CPU cost as a no-op AudioProcessor.
     * Driven from DspEngineManager when the user flips the mixer master
     * toggle off, so "DSP off" really means "no DSP".
     */
    @Volatile
    private var bypassed: Boolean = false

    fun setBypassed(b: Boolean) {
        bypassed = b
    }

    /**
     * Update the per-call DSP block size at runtime. Safe to call from any
     * thread; takes effect on the next queueInput() chunk. Must be one of
     * the values in PreferencesManager.DSP_BLOCK_SIZES.
     */
    fun setBlockSize(size: Int) {
        val clamped = size.coerceIn(64, MAX_BLOCK_SIZE)
        if (clamped == blockSize) return
        blockSize = clamped
        val ptr = enginePtr
        if (ptr != 0L && inputFormat != AudioFormat.NOT_SET) {
            // nativeReconfigure preserves bus graph + plugin state and only
            // re-grows scratch buffers if needed (we cap below MAX_BLOCK_SIZE,
            // so no realloc happens here).
            nativeReconfigure(ptr, inputFormat.sampleRate, MAX_BLOCK_SIZE)
        }
    }

    // ── AudioProcessor implementation ────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        // Accept 16-bit PCM or float PCM, mono or stereo
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }

        pendingFormat = inputAudioFormat
        // Always output stereo — mono is duplicated to both channels
        return if (inputAudioFormat.channelCount == 1) {
            AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
        } else {
            inputAudioFormat
        }
    }

    // Always active so ExoPlayer keeps us in the pipeline.
    // When disabled, queueInput() passes audio through unchanged
    // but the engine still runs for metering.
    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        // True bypass — when the user has the DSP mixer off we don't even
        // touch the audio thread's float scratch arrays. Same as the
        // no-engine pass-through below.
        if (bypassed || enginePtr == 0L) {
            val size = inputBuffer.remaining()
            if (outputBuffer.capacity() < size) {
                outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
            } else {
                outputBuffer.clear()
            }
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        val encoding = inputFormat.encoding
        val inputChannels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * inputChannels
        val numFrames = inputBuffer.remaining() / frameSize

        if (numFrames <= 0) return

        // Ensure scratch arrays are big enough
        if (scratchInL.size < numFrames) {
            scratchInL = FloatArray(numFrames)
            scratchInR = FloatArray(numFrames)
            scratchOutL = FloatArray(numFrames)
            scratchOutR = FloatArray(numFrames)
        }

        // Deinterleave input to L/R float arrays. Using index-based getShort /
        // getFloat rather than `asShortBuffer()` / `asFloatBuffer()` — those
        // allocate a view wrapper on every queueInput call (4 processors × ~47
        // Hz = ~190 allocs/sec on the audio thread), which accumulates into
        // young-gen GC pauses that stall the renderer past the buffer deadline.
        val startPos = inputBuffer.position()
        if (inputChannels == 1) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getFloat(startPos + i * 4)
                    scratchInL[i] = s
                    scratchInR[i] = s
                }
            } else {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getShort(startPos + i * 2).toFloat() / 32768f
                    scratchInL[i] = s
                    scratchInR[i] = s
                }
            }
        } else {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 8
                    scratchInL[i] = inputBuffer.getFloat(off)
                    scratchInR[i] = inputBuffer.getFloat(off + 4)
                }
            } else {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 4
                    scratchInL[i] = inputBuffer.getShort(off).toFloat() / 32768f
                    scratchInR[i] = inputBuffer.getShort(off + 2).toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(startPos + numFrames * frameSize)

        // Always process through native engine — AutoEQ lives on the master bus
        // and must run regardless of the mixer DSP toggle. The toggle controls
        // mix bus bypass in the C++ engine, not a blanket wet/dry switch here.
        //
        // Chunk the buffer into user-selected DSP block sizes (128 / 256 /
        // 512 / 1024 / 2048). The native engine processes whatever frame
        // count we hand it in one shot; chunking lets us bound per-call
        // worst-case latency, keep FFT-driven plugins inside their tuned
        // window size, and gives a knob users can move when CPU pressure
        // shows up as audible PipelineWatcher back-pressure. Chunk scratch
        // is reused so the audio thread never allocates here.
        val chunk = blockSize
        val needChunkScratch = chunk < numFrames
        if (needChunkScratch && chunkScratchInL.size < chunk) {
            chunkScratchInL = FloatArray(chunk)
            chunkScratchInR = FloatArray(chunk)
            chunkScratchOutL = FloatArray(chunk)
            chunkScratchOutR = FloatArray(chunk)
        }

        var processed = 0
        while (processed < numFrames) {
            val n = minOf(chunk, numFrames - processed)
            if (needChunkScratch) {
                System.arraycopy(scratchInL, processed, chunkScratchInL, 0, n)
                System.arraycopy(scratchInR, processed, chunkScratchInR, 0, n)
                nativeProcess(enginePtr, chunkScratchInL, chunkScratchInR, chunkScratchOutL, chunkScratchOutR, n)
                inflator.processArrays(chunkScratchOutL, chunkScratchOutR, n)
                compressor.processArrays(chunkScratchOutL, chunkScratchOutR, n)
                System.arraycopy(chunkScratchOutL, 0, scratchOutL, processed, n)
                System.arraycopy(chunkScratchOutR, 0, scratchOutR, processed, n)
            } else {
                // Single-shot fast path: ExoPlayer's buffer fits in one chunk.
                nativeProcess(enginePtr, scratchInL, scratchInR, scratchOutL, scratchOutR, n)
                inflator.processArrays(scratchOutL, scratchOutR, n)
                compressor.processArrays(scratchOutL, scratchOutR, n)
            }
            processed += n
        }

        val useL = scratchOutL
        val useR = scratchOutR

        // Interleave output back to ByteBuffer (always stereo output)
        val outFrameSize = bytesPerSample * 2  // stereo
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }

        // Interleave via positional put* — no asFloatBuffer / asShortBuffer view
        // allocations on the hot path.
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until numFrames) {
                val off = i * 8
                outputBuffer.putFloat(off, useL[i])
                outputBuffer.putFloat(off + 4, useR[i])
            }
        } else {
            // PCM16 output with TPDF dithering (triangular 1-LSB noise)
            for (i in 0 until numFrames) {
                val d1 = nextDitherSample()
                val d2 = nextDitherSample()
                val dither = d1 + d2 // TPDF: sum of two uniform = triangular
                val off = i * 4
                outputBuffer.putShort(off, ((useL[i] * 32768f) + dither).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(off + 2, ((useR[i] * 32768f) + dither).toInt().coerceIn(-32768, 32767).toShort())
            }
        }
        outputBuffer.position(0)
        outputBuffer.limit(outBytes)
    }

    override fun getOutput(): ByteBuffer {
        val buf = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return buf
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === AudioProcessor.EMPTY_BUFFER

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false

        if (pendingFormat == AudioFormat.NOT_SET) return

        // Only recreate the native engine when the audio format actually changes.
        // ExoPlayer calls configure()+flush() on every track change and seek —
        // recreating needlessly destroys all plugin state (bus gains, chains, etc.)
        val formatChanged = inputFormat == AudioFormat.NOT_SET
            || inputFormat.sampleRate != pendingFormat.sampleRate
            || inputFormat.encoding != pendingFormat.encoding
            || inputFormat.channelCount != pendingFormat.channelCount

        if (formatChanged) {
            inputFormat = pendingFormat
            if (enginePtr == 0L) {
                // Cold start — no existing engine, full construct + state restore.
                enginePtr = nativeCreate(inputFormat.sampleRate, MAX_BLOCK_SIZE)
            } else {
                // Hot path — live reconfigure keeps the bus graph, plugin
                // instances, and every atomic parameter untouched. No state
                // JSON round-trip, no FFT table reallocation, no audible gap.
                nativeReconfigure(enginePtr, inputFormat.sampleRate, MAX_BLOCK_SIZE)
            }

            // Oxford post-chain isn't part of the native engine; still needs
            // its own sample-rate prep call on every format change.
            inflator.prepare(inputFormat.sampleRate.toDouble(), 2)
            compressor.prepare(inputFormat.sampleRate.toDouble(), 2)

            // Signal ready (false→true transition ensures StateFlow emits)
            _engineReady.value = false
            _engineReady.value = true
        }
        // Clear pending so seeks within the same track don't recreate
        pendingFormat = AudioFormat.NOT_SET
    }

    override fun reset() {
        _engineReady.value = false
        flush()
        if (enginePtr != 0L) {
            nativeDestroy(enginePtr)
            enginePtr = 0L
        }
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
    }

    // LCG PRNG for TPDF dither — returns uniform value in [-0.5, 0.5) LSB range
    private fun nextDitherSample(): Float {
        ditherState = ditherState * 1103515245L + 12345L
        return ((ditherState shr 16) and 0x7FFF).toFloat() / 32768f - 0.5f
    }
}
