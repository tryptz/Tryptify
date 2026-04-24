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
        init {
            System.loadLibrary("monochrome_dsp")
        }
        const val MAX_BLOCK_SIZE = 4096
    }

    fun getEnginePtr(): Long = enginePtr

    /** Bypass mix bus plugins (0-3) in the C++ engine. Master bus (AutoEQ) still runs. */
    fun setMixBypassed(bypassed: Boolean) {
        val ptr = enginePtr
        if (ptr != 0L) nativeSetMixBypassed(ptr, bypassed)
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
        if (enginePtr == 0L) {
            // No engine — pass through
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

        // Deinterleave input to L/R float arrays
        if (inputChannels == 1) {
            // Mono: duplicate to both channels
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val fb = inputBuffer.asFloatBuffer()
                for (i in 0 until numFrames) {
                    val s = fb.get()
                    scratchInL[i] = s
                    scratchInR[i] = s
                }
            } else {
                val sb = inputBuffer.asShortBuffer()
                for (i in 0 until numFrames) {
                    val s = sb.get().toFloat() / 32768f
                    scratchInL[i] = s
                    scratchInR[i] = s
                }
            }
        } else {
            // Stereo
            if (encoding == C.ENCODING_PCM_FLOAT) {
                val fb = inputBuffer.asFloatBuffer()
                for (i in 0 until numFrames) {
                    scratchInL[i] = fb.get()
                    scratchInR[i] = fb.get()
                }
            } else {
                val sb = inputBuffer.asShortBuffer()
                for (i in 0 until numFrames) {
                    scratchInL[i] = sb.get().toFloat() / 32768f
                    scratchInR[i] = sb.get().toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(inputBuffer.position() + numFrames * frameSize)

        // Always process through native engine — AutoEQ lives on the master bus
        // and must run regardless of the mixer DSP toggle. The toggle controls
        // mix bus bypass in the C++ engine, not a blanket wet/dry switch here.
        nativeProcess(enginePtr, scratchInL, scratchInR, scratchOutL, scratchOutR, numFrames)

        // Oxford post-chain: runs after the bus+master chain, before interleave.
        // Each effect handles its own bypass flag internally.
        inflator.processArrays(scratchOutL, scratchOutR, numFrames)
        compressor.processArrays(scratchOutL, scratchOutR, numFrames)

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

        if (encoding == C.ENCODING_PCM_FLOAT) {
            val fb = outputBuffer.asFloatBuffer()
            for (i in 0 until numFrames) {
                fb.put(useL[i])
                fb.put(useR[i])
            }
        } else {
            // PCM16 output with TPDF dithering (triangular 1-LSB noise)
            val sb = outputBuffer.asShortBuffer()
            for (i in 0 until numFrames) {
                val d1 = nextDitherSample()
                val d2 = nextDitherSample()
                val dither = (d1 + d2) // TPDF: sum of two uniform = triangular
                sb.put(((useL[i] * 32768f) + dither).toInt().coerceIn(-32768, 32767).toShort())
                sb.put(((useR[i] * 32768f) + dither).toInt().coerceIn(-32768, 32767).toShort())
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
