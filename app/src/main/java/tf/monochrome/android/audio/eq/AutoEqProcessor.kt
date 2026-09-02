package tf.monochrome.android.audio.eq

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log2
import kotlin.math.pow

/**
 * Standalone 10-band parametric EQ AudioProcessor for AutoEQ headphone correction.
 * Completely independent of the mixer/DSP engine — sits in ExoPlayer's pipeline
 * as its own processor, always active when EQ is enabled.
 *
 * Uses decramped biquads (peaking, low shelf, high shelf): Vicanek matched-Z
 * peaking and tournament-matched shelves, so the response lands on the analog
 * prototypes the correction curves are designed against — see EqDspKernels.
 *
 * ## Pitch pre-warp
 *
 * Media3 puts [androidx.media3.common.audio.SonicAudioProcessor] — the thing
 * that serves `PlaybackParameters`' speed and pitch — at the *end* of
 * `DefaultAudioSink`'s chain: `DefaultAudioProcessorChain` copies the app's
 * processors into slots `0..n-1` and appends silence-skipping and Sonic after
 * them. This processor therefore runs *upstream* of the pitch shift.
 *
 * That ordering silently breaks the correction whenever pitch rides the speed
 * control (preserve-pitch off, e.g. the Nightcore preset). A pitch shift is a
 * multiplicative map on frequency, so Sonic scales *everything* this EQ
 * produced by the same ratio — including the correction curve itself. A notch
 * cut for a 3 kHz driver resonance leaves the sink at 3 kHz x ratio: it misses
 * the resonance it was aimed at, *and* digs a hole in clean spectrum next to
 * it. Two errors from one. The damage tracks the slope of the correction, so
 * it is worst exactly where AutoEQ works hardest — high-Q treble resonance
 * notches, where a band's whole half-gain bandwidth can be ~0.36 octaves
 * against a 1.25x shift's 0.32.
 *
 * The compensation is exact: tune each band to `freq / ratio` so Sonic's
 * `x ratio` lands it back on `freq`. Q needs no adjustment — a pitch shift is
 * a rigid translation on a log-frequency axis, which preserves bandwidth in
 * octaves — and the preamp is untouched, since a translation does not change
 * peak gain. At ratio 1.0 (any speed with preserve-pitch on, or 1.0x either
 * way) the warp is the identity and this costs nothing.
 *
 * ## Why the warp glides
 *
 * Retuning the whole chain the instant the ratio changes would step every
 * band's centre frequency at once, which is audible as a lurch. Instead the
 * applied warp chases the target with a one-pole glide in the log-frequency
 * domain (constant octaves per second, the musically even sweep), so the
 * correction slides into its new alignment over [GLIDE_TAU_MS]-ish rather than
 * jumping. Chasing a live target means dragging the speed slider sweeps the
 * correction along with it, and letting go settles it exactly on the target —
 * no touch/release plumbing needed, and the glide stops ticking once settled.
 *
 * Coefficient *design* stays off the audio thread. It has to: the matched
 * shelf design allocates and runs a scored tournament over candidate
 * constructions, whose own docs note the cost is only irrelevant because
 * "shelves change rarely". Under a glide they change constantly, so the glide
 * loop designs coefficients at control rate and publishes them as an immutable
 * snapshot; the audio thread does five float stores per band to install them,
 * with the filter memory left running so the sweep is continuous.
 */
@Singleton
@OptIn(UnstableApi::class)
class AutoEqProcessor @Inject constructor() : AudioProcessor {

    private var pendingFormat = AudioFormat.NOT_SET
    private var inputFormat = AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var inputEnded = false

    // Scratch float arrays
    private var scratchL = FloatArray(0)
    private var scratchR = FloatArray(0)

    /**
     * A designed chain, ready to install. Published as one immutable object so
     * the audio thread always sees a consistent (enabled, preamp, coefficients)
     * set — never half of an old curve and half of a new one.
     */
    private class Design(
        val enabled: Boolean,
        val preampLinear: Float,
        val coefsL: Array<FloatArray>,
        val coefsR: Array<FloatArray>,
    )

    /** The user's curve, in absolute Hz, before any pitch pre-warp. */
    private class Source(
        val bandsL: List<EqBand>,
        val bandsR: List<EqBand>,
        val preamp: Float,
        val enabled: Boolean,
    )

    private val designRef = AtomicReference(
        Design(false, 1f, emptyArray(), emptyArray())
    )
    private var appliedDesign: Design? = null

    // Per-band biquad filter state (audio thread only)
    private var filtersL = arrayOf<EqBiquad>()
    private var filtersR = arrayOf<EqBiquad>()

    @Volatile private var source = Source(emptyList(), emptyList(), 0f, false)

    /** Written by the audio thread in [flush], read by the design loop. */
    @Volatile private var sampleRate = 44100.0

    /** Where the warp is headed: the playback pitch ratio Sonic will apply. */
    @Volatile private var targetRatio = 1f

    /**
     * Where the warp is now, in octaves. Advanced only by the design loop, but
     * read by any thread that publishes a design synchronously.
     */
    @Volatile private var glidedOctaves = 0f

    /** Set by [setPitchRatio] to make the next pass jump instead of glide. */
    @Volatile private var snapToTarget = false

    /**
     * One-pole time constant for the warp glide, in milliseconds.
     *
     * Set from the latency of whatever stage is actually applying the pitch
     * downstream. The pre-warp is applied *upstream* of that stage, so a step
     * change here reaches the output before the pitch change it compensates
     * for does — with the resampler that gap is under a millisecond and does
     * not matter, but the phase vocoder runs about 350 ms behind, and an
     * instant re-warp would audibly correct for a pitch the listener has not
     * heard yet. Spreading the glide across the same window keeps the two
     * roughly in step and turns a hard misalignment into a brief, smoothly
     * varying one.
     */
    @Volatile private var glideTauMs: Double = DEFAULT_GLIDE_TAU_MS

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Conflated: a redesign only ever needs the *latest* inputs, so a burst of
     * slider updates collapses into one pass instead of queueing a backlog the
     * glide would then have to replay.
     */
    private val redesignRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (unused in redesignRequests) runGlide()
        }
    }

    /** Same curve on both ears. */
    fun applyBands(bands: List<EqBand>, preamp: Float, enabled: Boolean) =
        applyBands(bands, bands, preamp, enabled)

    /**
     * Independent curves per channel, for per-ear headphone calibration.
     *
     * The two lists may differ in length — a left calibration with 10 filters
     * and a right with 8 is entirely normal — so the filter chains are built
     * and run separately rather than index-locked to each other.
     */
    fun applyBands(
        bandsL: List<EqBand>,
        bandsR: List<EqBand>,
        preamp: Float,
        enabled: Boolean,
    ) {
        source = Source(bandsL, bandsR, preamp, enabled)
        // Published synchronously: a curve change has no glide to wait for, and
        // callers (and tests) reasonably expect the next processed block to
        // carry it. Any glide already in flight picks the new bands up on its
        // next tick, since it re-reads [source] every pass.
        publishDesign()
    }

    /**
     * Sets the playback pitch ratio the downstream Sonic stage will apply —
     * `speed` when pitch rides the tempo, `1.0` when preserve-pitch is on.
     * Bands are pre-warped by its inverse so the correction still lands on the
     * headphone's real resonances once Sonic has scaled the signal.
     *
     * The warp glides to the new ratio unless [immediate] is set, which exists
     * for seeding a freshly built chain (a crossfade copy) that has no current
     * alignment to slide away from.
     */
    @JvmOverloads
    fun setPitchRatio(
        ratio: Float,
        immediate: Boolean = false,
        glideMillis: Int = DEFAULT_GLIDE_MILLIS,
    ) {
        if (!ratio.isFinite() || ratio <= 0f) return
        // A one-pole settles to within a thousandth in about 5 tau, so the tau
        // that spans a given transition window is that window over five.
        glideTauMs = (glideMillis.toDouble() / 5.0)
            .coerceIn(MIN_GLIDE_TAU_MS, MAX_GLIDE_TAU_MS)
        // Generous sanity clamp — not the UI's range, just a guard against a
        // pathological value turning every band's frequency into a NaN.
        targetRatio = ratio.coerceIn(MIN_RATIO, MAX_RATIO)
        // A flag rather than writing the glide's own state from here: this is
        // called from the player thread, and glidedOctaves belongs to the
        // design loop.
        if (immediate) {
            snapToTarget = true
            redesignRequests.trySend(Unit)
            // Seeded chains are about to be handed live audio, so don't wait a
            // dispatch for the loop to land the first design.
            glidedOctaves = -log2(targetRatio)
            publishDesign()
            return
        }
        redesignRequests.trySend(Unit)
    }

    /**
     * Stops the design loop. For short-lived copies of the chain (see
     * `DspChain`); the injected singleton lives as long as the process.
     */
    fun release() {
        scope.cancel()
        redesignRequests.close()
    }

    // ── Pre-warp glide ───────────────────────────────────────────────────

    /**
     * Walks the applied warp toward the target and republishes the chain each
     * step, then returns once it has settled so nothing ticks at rest.
     *
     * Re-reads [targetRatio] every iteration rather than latching it, so a
     * target that is still moving (a slider mid-drag) is chased rather than
     * overshot and re-approached.
     */
    private suspend fun runGlide() {
        while (true) {
            val target = -log2(targetRatio)
            if (snapToTarget) {
                snapToTarget = false
                glidedOctaves = target
                publishDesign()
                return
            }
            val delta = target - glidedOctaves
            if (abs(delta) <= SETTLE_OCTAVES) {
                glidedOctaves = target
                publishDesign()
                return
            }
            // Recomputed per tick: the window tracks the active pitch stage,
            // which can change under us when the user switches modes.
            val alpha = (1.0 - exp(-GLIDE_TICK_MS / glideTauMs)).toFloat()
            glidedOctaves += delta * alpha
            publishDesign()
            delay(GLIDE_TICK_MS)
        }
    }

    /**
     * Serialises [publishDesign] so a design cannot be installed out of order.
     *
     * Never taken on the audio thread. That thread reads [designRef] and
     * installs what it finds; the one design-path call it makes, [flush], asks
     * the loop for a redesign rather than designing in place. The lock-free
     * read on the hot path is the invariant that matters, and it is untouched;
     * this side is a player/UI-thread concern where contention is nothing.
     */
    private val designPublishLock = Any()

    /**
     * Designs and publishes the chain for the warp as it stands.
     *
     * Never call it from the audio thread: the matched shelf design allocates
     * and runs a scored tournament (see [designBiquadCoefficients]).
     *
     * Volatile inputs and a single reference store are *not* enough to make
     * this safe from any thread, which is what it used to claim. They rule out
     * a torn read; they do not rule out two publishes committing in the wrong
     * order. [flush] asks the design loop for a redesign and returns, so a
     * caller doing the ordinary thing -- flush, then apply a curve -- has the
     * loop reading the pre-curve [source] while the caller writes the new one.
     * If the loop is then descheduled between its read and its store, which a
     * loaded machine will do, its result lands last and the curve is gone. The
     * initial [source] is disabled, so what that installs is not a stale curve
     * but no EQ at all: the processor drops to hard bypass and passes audio
     * through untouched, silently, until something else republishes.
     *
     * Holding the lock across the read and the store makes the last publish to
     * commit also the last one to read [source], so whichever order two
     * publishes arrive in, both see the newest curve.
     */
    private fun publishDesign() = synchronized(designPublishLock) {
        val src = source
        // 2^(-log2(pitchRatio)) == 1 / pitchRatio: the inverse of what Sonic
        // will do downstream, which is exactly the pre-warp.
        val warpFactor = 2f.pow(glidedOctaves)
        val sr = sampleRate
        designRef.set(
            Design(
                enabled = src.enabled,
                preampLinear = if (src.preamp == 0f) 1f else 10f.pow(src.preamp / 20f),
                coefsL = designChain(src.bandsL, warpFactor, sr),
                coefsR = designChain(src.bandsR, warpFactor, sr),
            )
        )
    }

    /**
     * Designs one channel's chain with every band's centre pre-warped.
     *
     * Bands are dropped exactly as before — disabled, or zero gain, contributes
     * nothing but a wasted biquad. The warped frequency is clamped below
     * Nyquist: at a low pitch ratio the warp pushes bands *up*, and a treble
     * band can land past the sample rate's ceiling, where it cannot be realised
     * at all. Clamping degrades it to the highest band that can be, which is
     * what the kernels' own guards would do anyway.
     */
    private fun designChain(
        bands: List<EqBand>,
        warpFactor: Float,
        sr: Double,
    ): Array<FloatArray> {
        val active = bands.filter { it.enabled && it.gain != 0f }
        if (active.isEmpty()) return emptyArray()
        val maxHz = sr * 0.49
        // Decramped (matched) coefficients throughout: peaking bands use
        // Vicanek matched-Z, shelves the tournament-matched design — the top
        // octave lands on the analog prototype with no oversampling machinery.
        return Array(active.size) { i ->
            val band = active[i]
            val type = when (band.type) {
                FilterType.LOWSHELF -> EqBiquadType.LOW_SHELF
                FilterType.HIGHSHELF -> EqBiquadType.HIGH_SHELF
                else -> EqBiquadType.PEAKING
            }
            val warped = (band.freq.toDouble() * warpFactor).coerceIn(MIN_HZ, maxHz)
            designBiquadCoefficients(
                type, sr, warped, band.q.toDouble(), band.gain.toDouble(),
                matched = true,
            )
        }
    }

    // ── AudioProcessor implementation ────────────────────────────────────

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        if (inputAudioFormat.channelCount > 2) {
            // Multichannel passthrough (downmix toggle off): go inactive
            // instead of failing playback — EQ simply doesn't apply. Clear
            // both trackers so isActive() reads false immediately (Media3's
            // pipeline checkState()s active processors against NOT_SET).
            pendingFormat = AudioFormat.NOT_SET
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        pendingFormat = inputAudioFormat
        return if (inputAudioFormat.channelCount == 1) {
            AudioFormat(inputAudioFormat.sampleRate, 2, inputAudioFormat.encoding)
        } else {
            inputAudioFormat
        }
    }

    override fun isActive(): Boolean =
        pendingFormat != AudioFormat.NOT_SET || inputFormat != AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        val encoding = inputFormat.encoding
        val inputChannels = inputFormat.channelCount
        val bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        val frameSize = bytesPerSample * inputChannels
        val numFrames = inputBuffer.remaining() / frameSize
        if (numFrames <= 0) return

        // Ensure scratch arrays
        if (scratchL.size < numFrames) {
            scratchL = FloatArray(numFrames)
            scratchR = FloatArray(numFrames)
        }

        // Deinterleave with index-based reads — no asFloatBuffer / asShortBuffer
        // view allocations on the audio thread.
        val startPos = inputBuffer.position()
        if (inputChannels == 1) {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getFloat(startPos + i * 4)
                    scratchL[i] = s; scratchR[i] = s
                }
            } else {
                for (i in 0 until numFrames) {
                    val s = inputBuffer.getShort(startPos + i * 2).toFloat() / 32768f
                    scratchL[i] = s; scratchR[i] = s
                }
            }
        } else {
            if (encoding == C.ENCODING_PCM_FLOAT) {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 8
                    scratchL[i] = inputBuffer.getFloat(off)
                    scratchR[i] = inputBuffer.getFloat(off + 4)
                }
            } else {
                for (i in 0 until numFrames) {
                    val off = startPos + i * 4
                    scratchL[i] = inputBuffer.getShort(off).toFloat() / 32768f
                    scratchR[i] = inputBuffer.getShort(off + 2).toFloat() / 32768f
                }
            }
        }
        inputBuffer.position(startPos + numFrames * frameSize)

        // Apply EQ if enabled
        val design = designRef.get()
        if (design.enabled) {
            if (design !== appliedDesign) {
                installDesign(design)
                appliedDesign = design
            }
            // Hard bypass when the chain is flat: with no active filters and
            // unity preamp the samples are left untouched.
            val hasWork = filtersL.isNotEmpty() || filtersR.isNotEmpty() ||
                design.preampLinear != 1f
            if (hasWork) {
                applyEq(scratchL, scratchR, numFrames, design.preampLinear)
            }
        }

        // Interleave output (always stereo)
        val outFrameSize = bytesPerSample * 2
        val outBytes = numFrames * outFrameSize
        if (outputBuffer.capacity() < outBytes) {
            outputBuffer = ByteBuffer.allocateDirect(outBytes).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        // Interleave via positional put* — no view allocations on the hot path.
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until numFrames) {
                val off = i * 8
                outputBuffer.putFloat(off, scratchL[i])
                outputBuffer.putFloat(off + 4, scratchR[i])
            }
        } else {
            for (i in 0 until numFrames) {
                val off = i * 4
                outputBuffer.putShort(off, (scratchL[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
                outputBuffer.putShort(off + 2, (scratchR[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort())
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
    override fun queueEndOfStream() { inputEnded = true }

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        inputEnded = false
        if (pendingFormat != AudioFormat.NOT_SET) {
            val formatChanged = inputFormat == AudioFormat.NOT_SET
                || inputFormat.sampleRate != pendingFormat.sampleRate
                || inputFormat.encoding != pendingFormat.encoding
                || inputFormat.channelCount != pendingFormat.channelCount
            if (formatChanged) {
                inputFormat = pendingFormat
                sampleRate = inputFormat.sampleRate.toDouble()
                // Coefficients are sample-rate-dependent, so the published
                // design is now stale. Drop the filters (which also clears
                // their memory across the discontinuity) and ask for a
                // redesign at the new rate; the next block runs on the old
                // curve for the millisecond or so that takes.
                filtersL = emptyArray()
                filtersR = emptyArray()
                appliedDesign = null
                redesignRequests.trySend(Unit)
            }
            pendingFormat = AudioFormat.NOT_SET
        }
    }

    override fun reset() {
        flush()
        pendingFormat = AudioFormat.NOT_SET
        inputFormat = AudioFormat.NOT_SET
        filtersL = emptyArray()
        filtersR = emptyArray()
        appliedDesign = null
    }

    // ── DSP internals ───────────────────────────────────────────────────

    /**
     * Installs a freshly designed chain.
     *
     * Filters are reallocated only when the band *count* changes; otherwise the
     * existing sections are retuned in place with their memory intact. That is
     * what makes the glide continuous — a new [EqBiquad] starts from silence,
     * so allocating per step would restart every filter dozens of times a
     * second. It also means a curve swap at the same band count (switching
     * headphone profile) no longer clicks.
     */
    private fun installDesign(design: Design) {
        if (filtersL.size != design.coefsL.size) {
            filtersL = Array(design.coefsL.size) { EqBiquad() }
        }
        if (filtersR.size != design.coefsR.size) {
            filtersR = Array(design.coefsR.size) { EqBiquad() }
        }
        for (i in filtersL.indices) filtersL[i].retune(design.coefsL[i])
        for (i in filtersR.indices) filtersR[i].retune(design.coefsR[i])
    }

    private fun applyEq(bufL: FloatArray, bufR: FloatArray, numFrames: Int, preampLinear: Float) {
        if (preampLinear != 1f) {
            for (i in 0 until numFrames) {
                bufL[i] *= preampLinear
                bufR[i] *= preampLinear
            }
        }
        // Biquad bands. Indexed per channel, NOT off a shared range: the two
        // chains hold different filter counts whenever the ears are calibrated
        // separately, and driving both from filtersL.indices would walk off the
        // end of the shorter one on the audio thread.
        for (i in filtersL.indices) filtersL[i].processBlock(bufL, numFrames)
        for (i in filtersR.indices) filtersR[i].processBlock(bufR, numFrames)
    }

    private companion object {
        /** Glide tick, ~60 Hz: fine enough that each coefficient step is small. */
        const val GLIDE_TICK_MS = 16L

        /**
         * Transition window used when the caller does not name one — the
         * resampler's latency is negligible, so this is chosen for feel rather
         * than for alignment.
         */
        const val DEFAULT_GLIDE_MILLIS = 700

        const val DEFAULT_GLIDE_TAU_MS = 140.0

        /** Fast enough to still be a glide; slow enough to stay a transition. */
        const val MIN_GLIDE_TAU_MS = 20.0
        const val MAX_GLIDE_TAU_MS = 600.0

        /**
         * Snap-to-target threshold. 0.0005 octaves is ~0.03% in Hz — orders of
         * magnitude below audibility, and well inside the resolution of the
         * measurements the correction curves come from.
         */
        const val SETTLE_OCTAVES = 0.0005f

        const val MIN_RATIO = 0.05f
        const val MAX_RATIO = 20f
        const val MIN_HZ = 5.0
    }
}
