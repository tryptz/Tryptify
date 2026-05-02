// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin JNI wrappers + state for Oxford Inflator / Compressor.
//
// Integration points:
//   - MixBusProcessor calls prepare() on format change and
//     processArrays(L, R, frames) after the native engine runs.
//   - UI observes `state` StateFlow; updates go through public setters which
//     mutate the flow and push params to native (native holds the
//     authoritative atomic copy used on the audio thread).

package tf.monochrome.android.audio.dsp.oxford

import tf.monochrome.android.audio.dsp.DspNativeLoader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

// ---- Native entry points -------------------------------------------------

internal object InflatorNative {
    init { DspNativeLoader.ensureLoaded() }
    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativePrepare(handle: Long, sampleRate: Double, channels: Int)
    external fun nativeSetParams(
        handle: Long,
        inputDb: Float, outputDb: Float, effect: Float, curve: Float,
        clipZeroDb: Boolean, bandSplit: Boolean, bypass: Boolean,
    )
    external fun nativeProcess(handle: Long, buffer: ByteBuffer, frames: Int, channels: Int)
    external fun nativeProcessArrays(handle: Long, l: FloatArray, r: FloatArray, frames: Int)
    external fun nativeReadMeters(handle: Long): Long
}

internal object CompressorNative {
    init { DspNativeLoader.ensureLoaded() }
    external fun nativeCreate(): Long
    external fun nativeDestroy(handle: Long)
    external fun nativePrepare(handle: Long, sampleRate: Double, channels: Int)
    external fun nativeSetParams(
        handle: Long,
        thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float,
        kneeDb: Float, makeupDb: Float, bypass: Boolean,
    )
    external fun nativeProcess(handle: Long, buffer: ByteBuffer, frames: Int, channels: Int)
    external fun nativeProcessArrays(handle: Long, l: FloatArray, r: FloatArray, frames: Int)
    external fun nativeGainReductionDb(handle: Long): Float
    external fun nativeReadMeters(handle: Long): Long
}

// ---- State data classes --------------------------------------------------

data class InflatorState(
    val inputDb:    Float   =  0.0f,   // [-6, +12]
    val outputDb:   Float   =  0.0f,   // [-12,  0]
    val effectPct:  Float   = 100.0f,  // [0, 100]  — UI-native unit
    val curve:      Float   =  0.0f,   // [-50, +50]
    val clipZeroDb: Boolean = true,
    val bandSplit:  Boolean = false,
    val effectIn:   Boolean = false,   // UI "Effect In" — false == bypass, off by default
)

/**
 * Factory presets for the Inflator. Each entry carries the full state the
 * user lands in when the chip is tapped. Tuned for common mixing tasks.
 */
enum class InflatorPreset(
    val label: String,
    val tagline: String,
    val state: InflatorState,
) {
    Init(
        "INIT",
        "Unity — clean signal path, effect bypassed.",
        InflatorState()
    ),
    Warmth(
        "WARMTH",
        "Gentle low-order harmonics for analogue-ish body.",
        InflatorState(
            inputDb = 0f, outputDb = -1f, effectPct = 100f, curve = -18f,
            clipZeroDb = true, bandSplit = false, effectIn = true,
        )
    ),
    Presence(
        "PRESENCE",
        "Lifts the upper mids without dulling the low end.",
        InflatorState(
            inputDb = 1f, outputDb = -1f, effectPct = 100f, curve = 22f,
            clipZeroDb = true, bandSplit = true, effectIn = true,
        )
    ),
    Punch(
        "PUNCH",
        "Aggressive enhancement on drums / bass — band-split on.",
        InflatorState(
            inputDb = 2f, outputDb = -2f, effectPct = 100f, curve = 12f,
            clipZeroDb = true, bandSplit = true, effectIn = true,
        )
    ),
    TapeGlue(
        "TAPE",
        "Soft saturation evocative of tape — ceiling slightly off.",
        InflatorState(
            inputDb = 2f, outputDb = -2f, effectPct = 100f, curve = -30f,
            clipZeroDb = false, bandSplit = false, effectIn = true,
        )
    ),
    Loudness(
        "LOUD",
        "Master-chain loudness boost, hard 0 dB ceiling.",
        InflatorState(
            inputDb = 3f, outputDb = -3f, effectPct = 100f, curve = 0f,
            clipZeroDb = true, bandSplit = false, effectIn = true,
        )
    );
}

data class CompressorState(
    val thresholdDb: Float = -20.0f,
    val ratio:       Float =   4.0f,
    val attackMs:    Float =  10.0f,
    val releaseMs:   Float = 100.0f,
    val kneeDb:      Float =   6.0f,
    val makeupDb:    Float =   0.0f,
    val bypass:      Boolean = true,   // off by default
)

/**
 * Factory presets for the Compressor. Tuned for typical engineering
 * targets rather than maximum gain reduction — audition, then taste.
 */
enum class CompressorPreset(
    val label: String,
    val tagline: String,
    val state: CompressorState,
) {
    Init(
        "INIT",
        "Bypassed — compressor off, nothing applied.",
        CompressorState()
    ),
    Vocal(
        "VOCAL",
        "Even out lead vocals: medium attack, smooth release.",
        CompressorState(
            thresholdDb = -18f, ratio = 3f, attackMs = 10f,
            releaseMs = 150f, kneeDb = 6f, makeupDb = 2f, bypass = false,
        )
    ),
    DrumBus(
        "DRUM BUS",
        "Tighten a drum submix — fast attack, punchy release.",
        CompressorState(
            thresholdDb = -12f, ratio = 4f, attackMs = 5f,
            releaseMs = 80f, kneeDb = 4f, makeupDb = 3f, bypass = false,
        )
    ),
    MixGlue(
        "GLUE",
        "Master-bus glue: low ratio, slow attack/release, soft knee.",
        CompressorState(
            thresholdDb = -14f, ratio = 2f, attackMs = 30f,
            releaseMs = 250f, kneeDb = 8f, makeupDb = 1.5f, bypass = false,
        )
    ),
    Aggressive(
        "AGGRESSIVE",
        "Heavy taming — high ratio, fast attack, hard knee.",
        CompressorState(
            thresholdDb = -20f, ratio = 8f, attackMs = 1f,
            releaseMs = 50f, kneeDb = 2f, makeupDb = 4f, bypass = false,
        )
    ),
    SoftMaster(
        "SOFT MASTER",
        "Gentle ceiling management for final mixes.",
        CompressorState(
            thresholdDb = -8f, ratio = 1.5f, attackMs = 20f,
            releaseMs = 400f, kneeDb = 10f, makeupDb = 1f, bypass = false,
        )
    ),
    Limiter(
        "LIMITER",
        "Peak limiter: 20:1, fastest attack, hard knee.",
        CompressorState(
            thresholdDb = -3f, ratio = 20f, attackMs = 0.5f,
            releaseMs = 20f, kneeDb = 0f, makeupDb = 0f, bypass = false,
        )
    );
}

data class StereoPeak(val left: Float, val right: Float) {
    companion object { val Zero = StereoPeak(0f, 0f) }
}

// ---- Effect classes ------------------------------------------------------

@Singleton
class InflatorEffect @Inject constructor() {
    private val handle = AtomicLong(InflatorNative.nativeCreate())

    private val _state = MutableStateFlow(InflatorState())
    val state: StateFlow<InflatorState> = _state.asStateFlow()

    private val _peak = MutableStateFlow(StereoPeak.Zero)
    val peak: StateFlow<StereoPeak> = _peak.asStateFlow()

    init { push(_state.value) }

    fun prepare(sampleRate: Double, channels: Int) {
        val h = handle.get()
        if (h != 0L) InflatorNative.nativePrepare(h, sampleRate, channels)
    }

    fun process(planarFloats: ByteBuffer, frames: Int, channels: Int) {
        require(planarFloats.isDirect) { "Buffer must be direct" }
        require(planarFloats.order() == ByteOrder.nativeOrder()) { "Buffer must be native-order" }
        val h = handle.get()
        if (h != 0L) InflatorNative.nativeProcess(h, planarFloats, frames, channels)
    }

    fun processArrays(l: FloatArray, r: FloatArray, frames: Int) {
        // Fast path: when the user has the effect bypassed (the default
        // shipping state), skip the JNI round-trip entirely. The C++ side
        // also early-returns on bypass, but the per-buffer JNI border
        // overhead alone is enough to chip into headroom on top of the
        // already-running mix-bus engine, contributing to PipelineWatcher
        // back-pressure on the audio renderer.
        if (!_state.value.effectIn) return
        val h = handle.get()
        if (h != 0L) InflatorNative.nativeProcessArrays(h, l, r, frames)
    }

    fun pollMeters() {
        val h = handle.get()
        if (h == 0L) return
        val packed = InflatorNative.nativeReadMeters(h)
        val l = java.lang.Float.intBitsToFloat((packed ushr 32).toInt())
        val r = java.lang.Float.intBitsToFloat((packed and 0xFFFFFFFFL).toInt())
        _peak.value = StereoPeak(l, r)
    }

    fun update(transform: (InflatorState) -> InflatorState) {
        val next = transform(_state.value)
        _state.value = next
        push(next)
    }

    fun setInputDb(db: Float)       = update { it.copy(inputDb    = db.coerceIn(-6f, 12f)) }
    fun setOutputDb(db: Float)      = update { it.copy(outputDb   = db.coerceIn(-12f, 0f)) }
    fun setEffectPct(pct: Float)    = update { it.copy(effectPct  = pct.coerceIn(0f, 100f)) }
    fun setCurve(c: Float)          = update { it.copy(curve      = c.coerceIn(-50f, 50f)) }
    fun setClipZeroDb(on: Boolean)  = update { it.copy(clipZeroDb = on) }
    fun setBandSplit(on: Boolean)   = update { it.copy(bandSplit  = on) }
    fun setEffectIn(on: Boolean)    = update { it.copy(effectIn   = on) }

    /** Apply a factory preset atomically (single native push). */
    fun applyPreset(preset: InflatorPreset) = update { preset.state }

    fun release() {
        val h = handle.getAndSet(0L)
        if (h != 0L) InflatorNative.nativeDestroy(h)
    }

    private fun push(s: InflatorState) {
        val h = handle.get()
        if (h == 0L) return
        InflatorNative.nativeSetParams(
            h,
            s.inputDb, s.outputDb, s.effectPct / 100f, s.curve,
            s.clipZeroDb, s.bandSplit, bypass = !s.effectIn,
        )
    }
}

@Singleton
class CompressorEffect @Inject constructor() {
    private val handle = AtomicLong(CompressorNative.nativeCreate())

    private val _state = MutableStateFlow(CompressorState())
    val state: StateFlow<CompressorState> = _state.asStateFlow()

    private val _gainReductionDb = MutableStateFlow(0f)
    val gainReductionDb: StateFlow<Float> = _gainReductionDb.asStateFlow()

    private val _peak = MutableStateFlow(StereoPeak.Zero)
    val peak: StateFlow<StereoPeak> = _peak.asStateFlow()

    init { push(_state.value) }

    fun prepare(sampleRate: Double, channels: Int) {
        val h = handle.get()
        if (h != 0L) CompressorNative.nativePrepare(h, sampleRate, channels)
    }

    fun process(planarFloats: ByteBuffer, frames: Int, channels: Int) {
        val h = handle.get()
        if (h != 0L) CompressorNative.nativeProcess(h, planarFloats, frames, channels)
    }

    fun processArrays(l: FloatArray, r: FloatArray, frames: Int) {
        // Fast path: skip JNI when the user has the compressor bypassed.
        if (_state.value.bypass) return
        val h = handle.get()
        if (h != 0L) CompressorNative.nativeProcessArrays(h, l, r, frames)
    }

    fun pollMeters() {
        val h = handle.get()
        if (h == 0L) return
        _gainReductionDb.value = CompressorNative.nativeGainReductionDb(h)
        val packed = CompressorNative.nativeReadMeters(h)
        val l = java.lang.Float.intBitsToFloat((packed ushr 32).toInt())
        val r = java.lang.Float.intBitsToFloat((packed and 0xFFFFFFFFL).toInt())
        _peak.value = StereoPeak(l, r)
    }

    fun update(transform: (CompressorState) -> CompressorState) {
        val next = transform(_state.value)
        _state.value = next
        push(next)
    }

    fun setThresholdDb(v: Float) = update { it.copy(thresholdDb = v.coerceIn(-60f, 0f)) }
    fun setRatio(v: Float)       = update { it.copy(ratio       = v.coerceIn(1f, 20f)) }
    fun setAttackMs(v: Float)    = update { it.copy(attackMs    = v.coerceIn(0.1f, 200f)) }
    fun setReleaseMs(v: Float)   = update { it.copy(releaseMs   = v.coerceIn(5f, 2000f)) }
    fun setKneeDb(v: Float)      = update { it.copy(kneeDb      = v.coerceIn(0f, 24f)) }
    fun setMakeupDb(v: Float)    = update { it.copy(makeupDb    = v.coerceIn(-12f, 24f)) }
    fun setBypass(b: Boolean)    = update { it.copy(bypass      = b) }

    /** Apply a factory preset atomically (single native push). */
    fun applyPreset(preset: CompressorPreset) = update { preset.state }

    fun release() {
        val h = handle.getAndSet(0L)
        if (h != 0L) CompressorNative.nativeDestroy(h)
    }

    private fun push(s: CompressorState) {
        val h = handle.get()
        if (h == 0L) return
        CompressorNative.nativeSetParams(
            h,
            s.thresholdDb, s.ratio, s.attackMs, s.releaseMs,
            s.kneeDb, s.makeupDb, s.bypass,
        )
    }
}
