// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import tf.monochrome.android.domain.patterns.Pattern
import tf.monochrome.android.domain.patterns.PatternLimits
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The JNI surface of the pattern engine, and the only place that knows how a
 * [Pattern] is laid out for the native side.
 *
 * Two separate paths cross this class, and they have different rules:
 *
 * * **Commands** ([post], [postStep]) are wait-free. They land in a lock-free
 *   ring the audio thread drains, so tapping a trig while the loop runs costs
 *   nothing on the audio side and cannot block the UI.
 * * **Uploads** ([uploadPattern], [putSample]) copy real data and take the
 *   engine's loader mutex. They must never be called from the audio thread,
 *   and callers are expected to be on a background dispatcher.
 *
 * Everything is null-safe against a missing library: with no `.so` the handle
 * stays 0 and every call is a no-op returning false, so the Patterns screen
 * degrades to "unavailable" rather than crashing the process.
 */
@Singleton
class NativeSamplerBridge @Inject constructor() {

    // ── native declarations ─────────────────────────────────────────────

    private external fun nativeCreate(sampleRate: Int): Long
    private external fun nativeDestroy(ptr: Long)
    private external fun nativePrepare(ptr: Long, sampleRate: Int)
    private external fun nativeRender(
        ptr: Long,
        outL: FloatArray,
        outR: FloatArray,
        frames: Int,
        clearFirst: Boolean,
    )

    private external fun nativePostCommand(
        ptr: Long,
        type: Int,
        a: Int,
        b: Int,
        c: Int,
        d: Int,
        f: Float,
    ): Boolean

    private external fun nativePostStep(
        ptr: Long,
        patternIndex: Int,
        channel: Int,
        stepIndex: Int,
        enabled: Int,
        velocity: Int,
        pitch: Int,
        probability: Int,
        pan: Int,
        sampleStart: Int,
        locks: Int,
    ): Boolean

    private external fun nativeUploadPattern(
        ptr: Long,
        index: Int,
        header: IntArray,
        bpmOverride: Float,
        channelInts: IntArray,
        channelFloats: FloatArray,
        steps: ByteArray,
    ): Boolean

    private external fun nativePutSample(
        ptr: Long,
        slot: Int,
        left: FloatArray,
        right: FloatArray?,
        frames: Int,
        sampleRate: Int,
        gain: Float,
    ): Boolean

    private external fun nativeClearSample(ptr: Long, slot: Int): Boolean
    private external fun nativeHasSample(ptr: Long, slot: Int): Boolean
    private external fun nativeCollectGarbage(ptr: Long): Int
    private external fun nativeResidentFrames(ptr: Long): Long
    private external fun nativeReadState(ptr: Long, out: IntArray)
    private external fun nativeFramePosition(ptr: Long): Long
    private external fun nativeReadSteps(ptr: Long, index: Int, out: ByteArray): Int

    // ── handle ──────────────────────────────────────────────────────────

    @Volatile
    private var handle: Long = 0L

    private val lifecycleLock = Any()

    val isReady: Boolean get() = handle != 0L

    /** Creates the engine if it does not exist. Safe to call repeatedly. */
    fun create(sampleRate: Int): Boolean {
        if (handle != 0L) return true
        if (!SamplerNativeLoader.available) return false
        synchronized(lifecycleLock) {
            if (handle != 0L) return true
            handle = nativeCreate(sampleRate)
            return handle != 0L
        }
    }

    fun destroy() {
        synchronized(lifecycleLock) {
            val ptr = handle
            handle = 0L
            if (ptr != 0L) nativeDestroy(ptr)
        }
    }

    fun prepare(sampleRate: Int) {
        val ptr = handle
        if (ptr != 0L) nativePrepare(ptr, sampleRate)
    }

    /**
     * Audio-thread entry point. Deliberately not synchronized and deliberately
     * not null-checking anything but the handle: whatever this costs is paid
     * once per output buffer.
     */
    fun render(outL: FloatArray, outR: FloatArray, frames: Int, clearFirst: Boolean) {
        val ptr = handle
        if (ptr == 0L) return
        nativeRender(ptr, outL, outR, frames, clearFirst)
    }

    // ── commands ────────────────────────────────────────────────────────

    fun post(type: Int, a: Int = 0, b: Int = 0, c: Int = 0, d: Int = 0, f: Float = 0f): Boolean {
        val ptr = handle
        return ptr != 0L && nativePostCommand(ptr, type, a, b, c, d, f)
    }

    fun postStep(
        patternIndex: Int,
        channel: Int,
        stepIndex: Int,
        enabled: Boolean,
        velocity: Int,
        pitch: Int = 0,
        probability: Int = 100,
        pan: Int = 0,
        sampleStart: Int = 0,
        locks: Int = 0,
    ): Boolean {
        val ptr = handle
        return ptr != 0L && nativePostStep(
            ptr, patternIndex, channel, stepIndex,
            if (enabled) 1 else 0,
            velocity.coerceIn(0, 127),
            pitch.coerceIn(-24, 24),
            probability.coerceIn(0, 100),
            pan.coerceIn(-100, 100),
            sampleStart.coerceIn(0, 255),
            locks,
        )
    }

    // ── uploads ─────────────────────────────────────────────────────────

    /**
     * Publishes [pattern] into its bank slot.
     *
     * [slotForSample] maps a sample id to the engine's resident sample slot;
     * a channel whose sample has not been loaded yet resolves to -1 and simply
     * stays silent until it is.
     *
     * Returns false when the engine has no spare buffer free — the caller is
     * expected to retry rather than treat it as an error, which is what
     * `SamplePlaybackEngine.publishPattern` does.
     */
    fun uploadPattern(pattern: Pattern, slotForSample: (Long) -> Int): Boolean {
        val ptr = handle
        if (ptr == 0L) return false

        val channels = pattern.channels.take(PatternLimits.MAX_CHANNELS)
        val count = channels.size
        val header = intArrayOf(
            pattern.lengthSteps.coerceIn(1, PatternLimits.MAX_STEPS),
            pattern.stepsPerBeat.coerceIn(1, 16),
            pattern.beatsPerBar.coerceIn(1, 16),
            count,
        )

        val ints = IntArray(count * INTS_PER_CHANNEL)
        val floats = FloatArray(count * FLOATS_PER_CHANNEL)
        val steps = ByteArray(count * PatternLimits.MAX_STEPS * BYTES_PER_STEP)

        channels.forEachIndexed { index, channel ->
            val i = index * INTS_PER_CHANNEL
            ints[i] = channel.sampleId?.let(slotForSample) ?: -1
            ints[i + 1] = if (channel.muted) 1 else 0
            ints[i + 2] = if (channel.soloed) 1 else 0
            ints[i + 3] = if (channel.enabled) 1 else 0
            ints[i + 4] = if (channel.reverse) 1 else 0

            val f = index * FLOATS_PER_CHANNEL
            floats[f] = channel.volume
            floats[f + 1] = channel.pan
            floats[f + 2] = channel.pitch
            floats[f + 3] = channel.sampleStart
            floats[f + 4] = channel.sampleEnd
            floats[f + 5] = channel.attackMs
            floats[f + 6] = channel.releaseMs
            floats[f + 7] = channel.filterHz

            for (s in 0 until PatternLimits.MAX_STEPS) {
                val step = channel.step(s)
                val b = (index * PatternLimits.MAX_STEPS + s) * BYTES_PER_STEP
                steps[b] = if (step.enabled) 1 else 0
                steps[b + 1] = step.velocity.coerceIn(0, 127).toByte()
                steps[b + 2] = step.pitch.coerceIn(-24, 24).toByte()
                steps[b + 3] = step.probability.coerceIn(0, 100).toByte()
                steps[b + 4] = step.pan.coerceIn(-100, 100).toByte()
                steps[b + 5] = step.sampleStart.coerceIn(0, 255).toByte()
                steps[b + 6] = step.locks.toByte()
                steps[b + 7] = 0
            }
        }

        return nativeUploadPattern(
            ptr,
            pattern.bankSlot.coerceIn(0, PatternLimits.MAX_PATTERNS - 1),
            header,
            pattern.bpmOverride ?: 0f,
            ints,
            floats,
            steps,
        )
    }

    /**
     * Loads decoded PCM into a resident slot. [right] is null for mono, which
     * the engine reads as centred rather than duplicating the buffer.
     */
    fun putSample(
        slot: Int,
        left: FloatArray,
        right: FloatArray?,
        frames: Int,
        sampleRate: Int,
        gain: Float,
    ): Boolean {
        val ptr = handle
        return ptr != 0L && nativePutSample(ptr, slot, left, right, frames, sampleRate, gain)
    }

    fun clearSample(slot: Int): Boolean {
        val ptr = handle
        return ptr != 0L && nativeClearSample(ptr, slot)
    }

    fun hasSample(slot: Int): Boolean {
        val ptr = handle
        return ptr != 0L && nativeHasSample(ptr, slot)
    }

    fun collectGarbage(): Int {
        val ptr = handle
        return if (ptr == 0L) 0 else nativeCollectGarbage(ptr)
    }

    fun residentFrames(): Long {
        val ptr = handle
        return if (ptr == 0L) 0L else nativeResidentFrames(ptr)
    }

    // ── published state ─────────────────────────────────────────────────

    /**
     * Fills [out] with everything the UI polls, in one JNI transition. See
     * [STATE_SIZE] for the layout; [out] must be at least that long and is
     * expected to be a single array reused across frames.
     */
    fun readState(out: IntArray) {
        val ptr = handle
        if (ptr != 0L && out.size >= STATE_SIZE) nativeReadState(ptr, out)
    }

    fun framePosition(): Long {
        val ptr = handle
        return if (ptr == 0L) 0L else nativeFramePosition(ptr)
    }

    /**
     * Reads a pattern's trigs back out of the engine.
     *
     * Needed for exactly one thing: a hit played live while recording is
     * quantized by the audio thread, and only the audio thread knows where the
     * playhead was when the tap arrived. [out] must be at least
     * `channels × MAX_STEPS × 8` bytes. Returns the channel count read, or -1.
     */
    fun readSteps(bankSlot: Int, out: ByteArray): Int {
        val ptr = handle
        return if (ptr == 0L) -1 else nativeReadSteps(ptr, bankSlot, out)
    }

    /** Byte stride of one step in the [readSteps] / upload payloads. */
    fun stepStride(): Int = BYTES_PER_STEP

    companion object {
        init { SamplerNativeLoader.ensureLoaded() }

        private const val INTS_PER_CHANNEL = 5
        private const val FLOATS_PER_CHANNEL = 8
        private const val BYTES_PER_STEP = 8

        // ── nativeReadState layout ──────────────────────────────────────
        const val STATE_STEP = 0
        const val STATE_PATTERN = 1
        const val STATE_QUEUED = 2
        const val STATE_FLAGS = 3
        const val STATE_VOICES = 4
        const val STATE_COUNT_IN = 5
        const val STATE_RECORD_EDITS = 6
        const val STATE_TRIGGERS = 7
        const val STATE_SIZE = STATE_TRIGGERS + PatternLimits.MAX_CHANNELS

        const val FLAG_PLAYING = 1
        const val FLAG_RECORDING = 2

        // ── command ids, mirroring cpp/sampler/sampler_types.h ──────────
        const val CMD_SET_STEP = 1
        const val CMD_TOGGLE_STEP = 2
        const val CMD_SET_CHANNEL_PARAM = 3
        const val CMD_SET_CHANNEL_SAMPLE = 4
        const val CMD_SET_PATTERN_LENGTH = 5
        const val CMD_SET_PATTERN_METER = 6
        const val CMD_CLEAR_PATTERN = 7
        const val CMD_CLEAR_CHANNEL = 8
        const val CMD_QUEUE_PATTERN = 9
        const val CMD_SET_BPM = 10
        const val CMD_SET_SWING = 11
        const val CMD_TRANSPORT = 12
        const val CMD_TRIGGER = 13
        const val CMD_SET_METRONOME = 14
        const val CMD_SET_COUNT_IN = 15
        const val CMD_SET_RECORD_QUANTIZE = 16
        const val CMD_SET_MASTER_GAIN = 17
        const val CMD_STOP_CHANNEL = 18

        // ── ChannelParam ids ────────────────────────────────────────────
        const val PARAM_VOLUME = 0
        const val PARAM_PAN = 1
        const val PARAM_PITCH = 2
        const val PARAM_SAMPLE_START = 3
        const val PARAM_SAMPLE_END = 4
        const val PARAM_ATTACK = 5
        const val PARAM_RELEASE = 6
        const val PARAM_FILTER = 7
        const val PARAM_MUTE = 8
        const val PARAM_SOLO = 9
        const val PARAM_ENABLED = 10
        const val PARAM_REVERSE = 11

        // ── TransportAction ids ─────────────────────────────────────────
        const val TRANSPORT_STOP = 0
        const val TRANSPORT_PLAY = 1
        const val TRANSPORT_RECORD_ON = 2
        const val TRANSPORT_RECORD_OFF = 3
        const val TRANSPORT_REWIND = 4
    }
}
