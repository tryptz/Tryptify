// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tf.monochrome.android.domain.patterns.CaptureSource
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Captures PCM out of Tryptify's own playback pipeline.
 *
 * This is a tap on the app's existing chain, not a system recorder. There is
 * no `AudioPlaybackCapture`, no microphone and no route to anything the app is
 * not itself decoding — the only audio that can ever reach this class is audio
 * Tryptify already has in a buffer, taken from one of two points:
 *
 * ```
 *   Media3 decoder ──┬─→ CLEAN tap
 *                    ↓
 *              Tryptify DSP ──→ PROCESSED tap ──→ output
 * ```
 *
 * ## Eligibility
 *
 * Capture is gated by [eligibility], which defaults to ineligible. Recording
 * only arms for sources where keeping a copy is permitted — on-device files
 * and the user's own downloads — and never for streamed catalogue or radio.
 * The gate is deliberately checked at *arm* time and again at *save* time:
 * arming decides whether the meter runs at all, and saving is the point where
 * a permanent file would be written, so a track change mid-capture cannot turn
 * an ineligible source into a saved sample.
 *
 * ## Threading
 *
 * [write] runs on the audio thread. It allocates nothing, takes no lock and
 * does no I/O: the destination buffer is allocated by [arm] on a background
 * thread before the first sample arrives, and the audio thread only ever
 * advances an atomic index into it. When the buffer fills, capture stops
 * itself rather than growing.
 */
@Singleton
class SampleCaptureEngine @Inject constructor() {

    /** Why capture is or is not available for what is currently playing. */
    data class Eligibility(
        val allowed: Boolean = false,
        val reason: String = "Nothing is playing",
    )

    data class CaptureState(
        val armed: Boolean = false,
        val source: CaptureSource = CaptureSource.CLEAN,
        val frames: Int = 0,
        val sampleRate: Int = 48000,
        val peak: Float = 0f,
        val full: Boolean = false,
    ) {
        val durationMs: Long
            get() = if (sampleRate <= 0) 0 else frames * 1000L / sampleRate
    }

    private val _eligibility = MutableStateFlow(Eligibility())
    val eligibility: StateFlow<Eligibility> = _eligibility.asStateFlow()

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    // Interleaved stereo. Allocated by arm(), read by finish(), written only
    // by the audio thread between the two.
    @Volatile
    private var buffer: FloatArray? = null

    @Volatile
    private var capacityFrames = 0

    @Volatile
    private var activeSource: CaptureSource = CaptureSource.CLEAN

    @Volatile
    private var armed = false

    @Volatile
    private var captureRate = 48000

    private val writeIndex = AtomicInteger(0)
    private val peakMilli = AtomicInteger(0)

    // ── eligibility ─────────────────────────────────────────────────────

    /**
     * Sets whether the currently playing item may be captured.
     *
     * The decision itself lives in [CaptureEligibilityResolver], which is the
     * only thing that knows how a track's provenance is established. This
     * class just holds the answer and enforces it — at [arm], and again at
     * [finish] before anything could be written to disk.
     */
    fun setEligibility(allowed: Boolean, reason: String) {
        _eligibility.value = Eligibility(allowed, reason)
        if (!allowed && armed) cancel()
    }

    // ── capture ─────────────────────────────────────────────────────────

    /**
     * Prepares to capture up to [maxSeconds] from [source].
     *
     * Returns false when the current item is not eligible, or when the
     * allocation would be unreasonable. Called from a coroutine, never from
     * the audio thread — the array below is the only allocation in the whole
     * capture path and it happens here.
     */
    fun arm(source: CaptureSource, sampleRate: Int, maxSeconds: Int = DEFAULT_MAX_SECONDS): Boolean {
        if (!_eligibility.value.allowed) return false
        val rate = if (sampleRate in 8000..384000) sampleRate else 48000
        val frames = rate * maxSeconds.coerceIn(1, MAX_SECONDS_CEILING)
        val needed = frames * 2
        if (needed <= 0) return false

        buffer = FloatArray(needed)
        capacityFrames = frames
        captureRate = rate
        activeSource = source
        writeIndex.set(0)
        peakMilli.set(0)
        armed = true
        _state.value = CaptureState(armed = true, source = source, sampleRate = rate)
        return true
    }

    /**
     * Audio-thread entry point. [source] identifies which tap is calling, so
     * the two taps can both be in the chain while only the armed one records.
     */
    fun write(source: CaptureSource, left: FloatArray, right: FloatArray, frames: Int) {
        if (!armed || source != activeSource) return
        val target = buffer ?: return
        val capacity = capacityFrames
        val start = writeIndex.get()
        if (start >= capacity) return

        val count = minOf(frames, capacity - start)
        var peak = 0f
        var out = start * 2
        for (i in 0 until count) {
            val l = left[i]
            val r = right[i]
            target[out] = l
            target[out + 1] = r
            out += 2
            val magnitude = if (abs(l) > abs(r)) abs(l) else abs(r)
            if (magnitude > peak) peak = magnitude
        }
        writeIndex.set(start + count)

        // Peak is published as milli-units through an int so the UI meter can
        // read it without a lock and without a float box.
        val milli = (peak * 1000f).toInt()
        while (true) {
            val current = peakMilli.get()
            if (milli <= current || peakMilli.compareAndSet(current, milli)) break
        }

        if (start + count >= capacity) armed = false
    }

    /** Cheap enough to call from a UI frame callback. */
    fun publishProgress() {
        if (buffer == null) return
        val frames = writeIndex.get()
        _state.value = CaptureState(
            armed = armed,
            source = activeSource,
            frames = frames,
            sampleRate = captureRate,
            peak = peakMilli.getAndSet(0) / 1000f,
            full = frames >= capacityFrames,
        )
    }

    /** Stops writing but keeps what was captured, so it can still be trimmed. */
    fun stop() {
        armed = false
        publishProgress()
    }

    /**
     * Ends the capture and hands back what was recorded, or null if nothing
     * usable was. Also re-checks eligibility: this is the last point before a
     * file could be written, and a track change during the capture must not
     * be able to smuggle an ineligible source through.
     */
    fun finish(): CapturedAudio? {
        armed = false
        val source = buffer
        val frames = writeIndex.get()
        buffer = null
        capacityFrames = 0
        _state.value = CaptureState()
        if (source == null || frames <= 0) return null
        if (!_eligibility.value.allowed) return null

        val left = FloatArray(frames)
        val right = FloatArray(frames)
        for (i in 0 until frames) {
            left[i] = source[i * 2]
            right[i] = source[i * 2 + 1]
        }
        return CapturedAudio(left, right, captureRate, activeSource)
    }

    /** Throws the capture away. */
    fun cancel() {
        armed = false
        buffer = null
        capacityFrames = 0
        writeIndex.set(0)
        _state.value = CaptureState()
    }

    val isArmed: Boolean get() = armed

    companion object {
        const val DEFAULT_MAX_SECONDS = 20
        const val MAX_SECONDS_CEILING = 60
    }
}

/** Deinterleaved result of one capture, before any trimming. */
data class CapturedAudio(
    val left: FloatArray,
    val right: FloatArray,
    val sampleRate: Int,
    val source: CaptureSource,
) {
    val frames: Int get() = left.size

    // Data classes over arrays get identity equals, which silently breaks any
    // `==` on this type. Comparing megabytes of PCM is never what a caller
    // wants either, so identity is made explicit instead of accidental.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}
