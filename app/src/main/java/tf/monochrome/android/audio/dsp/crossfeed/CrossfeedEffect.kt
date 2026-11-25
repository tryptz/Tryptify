// SPDX-License-Identifier: GPL-3.0-or-later
// Headphone crossfeed — simulates a stereo speaker pair in front of the
// listener by feeding each channel into the opposite ear, delayed (interaural
// time difference) and low-pass filtered (head shadow). Or, in its Room mode,
// the mixer's Binaural Room on its own (HeadphoneRoomNative): a pair of
// speakers in a room, through a chosen HRTF, with the bass as an LFE if
// asked. One page, one switch: Off, Crossfeed or Room.
//
// Integration points:
//   - MixBusProcessor calls prepare() on format change and
//     processArrays(L, R, frames) after the Oxford post-chain.
//   - UI observes `state`; updates go through the public setters. Parameters
//     are recomputed on set and read as @Volatile primitives on the audio
//     thread, with per-sample smoothing so live slider moves never click.

package tf.monochrome.android.audio.dsp.crossfeed

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/**
 * Selectable crossfeed tunings. SPEAKER derives its feed level and delay from
 * the user's speaker angle; the rest are fixed classic networks whose feed
 * level / cutoff / delay triplets follow the published bs2b tunings.
 */
@Serializable
enum class CrossfeedAlgorithm(
    val label: String,
    val blurb: String,
    val feedDb: Float,
    val cutoffHz: Double,
    val delayUs: Double,
) {
    SPEAKER(
        "Speaker angle",
        "Physical model. Set the virtual speaker angle with the slider below.",
        feedDb = 0f, cutoffHz = 700.0, delayUs = 0.0, // derived from the angle
    ),
    BS2B(
        "BS2B",
        "Bauer stereophonic-to-binaural. 700 Hz cut, -4.5 dB feed, 260 µs delay.",
        feedDb = -4.5f, cutoffHz = 700.0, delayUs = 260.0,
    ),
    CMOY(
        "Chu Moy",
        "Chu Moy crossfeeder. 700 Hz cut, -6 dB feed, 260 µs delay.",
        feedDb = -6.0f, cutoffHz = 700.0, delayUs = 260.0,
    ),
    JMEIER(
        "Jan Meier",
        "Jan Meier CORDA network. 650 Hz cut, -9.5 dB feed, 280 µs delay.",
        feedDb = -9.5f, cutoffHz = 650.0, delayUs = 280.0,
    );
}

/** What the page plays while it is on: crossfeed, or the Binaural Room. */
@Serializable
enum class CrossfeedMode { CROSSFEED, ROOM }

/**
 * The Room's settings: the mixer's Binaural Room's own nine, in its units
 * and with its limits (ParamDefs, `snapins/binaural_room.h`), then the LFE's
 * (`dsp/headphone_room.h`) and the HRTF, a .sofa's path or null for the
 * built-in KU100.
 */
@Serializable
data class RoomSettings(
    val roomPercent: Float = 25f,
    val binauralPercent: Float = 100f,
    val angleDeg: Float = 30f,
    val outputDb: Float = 0f,
    val myRoom: Boolean = false,
    val widthM: Float = 5f,
    val depthM: Float = 6f,
    val heightM: Float = 2.7f,
    val dampingPercent: Float = 25f,
    /** The bass below [lfeHz] taken out of the room, as one channel at [lfeDb]. */
    val lfe: Boolean = false,
    val lfeHz: Float = 80f,
    val lfeDb: Float = 0f,
    val hrtfPath: String? = null,
) {
    fun clamped() = copy(
        roomPercent = roomPercent.coerceIn(0f, 100f),
        binauralPercent = binauralPercent.coerceIn(0f, 100f),
        angleDeg = angleDeg.coerceIn(0f, MAX_ANGLE_DEG),
        outputDb = outputDb.coerceIn(-12f, 12f),
        widthM = widthM.coerceIn(MIN_SIDE_M, MAX_SIDE_M),
        depthM = depthM.coerceIn(MIN_SIDE_M, MAX_SIDE_M),
        heightM = heightM.coerceIn(MIN_HEIGHT_M, MAX_HEIGHT_M),
        dampingPercent = dampingPercent.coerceIn(MIN_DAMPING, MAX_DAMPING),
        lfeHz = lfeHz.coerceIn(MIN_LFE_HZ, MAX_LFE_HZ),
        lfeDb = lfeDb.coerceIn(MIN_LFE_DB, MAX_LFE_DB),
        hrtfPath = hrtfPath?.takeIf { it.isNotBlank() },
    )

    /** The native room's parameters, in `HeadphoneRoom::Params` order. */
    fun params(): FloatArray = floatArrayOf(
        roomPercent, binauralPercent, angleDeg, outputDb,
        if (myRoom) 1f else 0f, widthM, depthM, heightM, dampingPercent,
        if (lfe) 1f else 0f, lfeHz, lfeDb,
    )

    companion object {
        const val PARAM_COUNT = 12
        const val MAX_ANGLE_DEG = 110f
        const val MIN_SIDE_M = 2f
        const val MAX_SIDE_M = 30f
        const val MIN_HEIGHT_M = 2f
        const val MAX_HEIGHT_M = 12f
        const val MIN_DAMPING = 5f
        const val MAX_DAMPING = 70f
        const val MIN_LFE_HZ = 40f
        const val MAX_LFE_HZ = 200f
        const val MIN_LFE_DB = -12f
        const val MAX_LFE_DB = 6f
    }
}

@Serializable
data class CrossfeedState(
    /** Off is false; on, [mode] says which. */
    val enabled: Boolean = false,
    val mode: CrossfeedMode = CrossfeedMode.CROSSFEED,
    val algorithm: CrossfeedAlgorithm = CrossfeedAlgorithm.SPEAKER,
    /**
     * Total angle between the two virtual speakers as seen from the listener,
     * in degrees. 30° = narrow frontal pair (strongest crossfeed), 180° =
     * speakers at the ears, i.e. plain headphones (no crossfeed at all).
     * Only meaningful for [CrossfeedAlgorithm.SPEAKER].
     */
    val speakerAngleDeg: Float = DEFAULT_ANGLE_DEG,
    val room: RoomSettings = RoomSettings(),
    /** The page's picture: in 3D, or flat from above. Kept with the rest. */
    val view3d: Boolean = false,
) {
    val crossfeedOn: Boolean get() = enabled && mode == CrossfeedMode.CROSSFEED
    val roomOn: Boolean get() = enabled && mode == CrossfeedMode.ROOM

    fun clamped() = copy(
        speakerAngleDeg = speakerAngleDeg.coerceIn(MIN_ANGLE_DEG, MAX_ANGLE_DEG),
        room = room.clamped(),
    )

    companion object {
        const val MIN_ANGLE_DEG = 30f
        const val MAX_ANGLE_DEG = 180f
        const val DEFAULT_ANGLE_DEG = 60f

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /** As it is kept on the phone. */
        fun encode(state: CrossfeedState): String = json.encodeToString(state.clamped())

        /** What [encode] wrote, or null for nothing or something unreadable. */
        fun decode(text: String?): CrossfeedState? =
            text?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString(serializer(), it).clamped() }.getOrNull()
            }
    }
}

@Singleton
class CrossfeedEffect internal constructor(
    /** A room for the Room mode; null where there can be none (no native library). */
    private val roomFactory: () -> RoomEngine?,
) {
    @Inject constructor() : this({ NativeRoomEngine.createOrNull() })

    private val _state = MutableStateFlow(CrossfeedState())
    val state: StateFlow<CrossfeedState> = _state.asStateFlow()

    // ---- Audio-thread parameters (recomputed on every state/format change) --

    @Volatile private var targetCrossGain = 0f       // contralateral feed level
    @Volatile private var targetDelaySamples = 0f    // ITD as fractional samples
    @Volatile private var lowpassCoeff = 0f          // one-pole head-shadow LPF
    @Volatile private var smoothCoeff = 0f           // per-sample param smoothing

    private var sampleRate = 48000.0

    // Smoothed working copies — audio thread only.
    private var crossGain = 0f
    private var delaySamples = 0f

    // Ring buffers holding recent input for the delayed contralateral tap.
    // Max ITD is (r/c)·(π/2 + 1) ≈ 0.66 ms → 127 samples at 192 kHz; 256 is
    // comfortably above that for any sample rate we'll see.
    private val ringL = FloatArray(RING_SIZE)
    private val ringR = FloatArray(RING_SIZE)
    private var ringPos = 0

    // One-pole low-pass state for each cross path.
    private var lpL = 0f
    private var lpR = 0f

    // ---- The Room ----------------------------------------------------------
    // Its settings as the audio thread reads them: a fresh array each change.
    @Volatile private var roomOn = false
    @Volatile private var roomParams = RoomSettings().params()
    private var room: RoomEngine? = null          // made once, when first wanted
    private val roomLock = Any()
    private var roomRate = 0                      // what it was prepared for; audio thread
    private var roomPlaying = false               // audio thread
    private val roomApplied = FloatArray(RoomSettings.PARAM_COUNT)
    private var roomAppliedValid = false

    /** The HRTF set the room plays, or null for the built-in one. */
    @Volatile var roomHrtf: FloatArray? = null
        private set

    init { recompute() }

    fun prepare(sampleRate: Double) {
        this.sampleRate = sampleRate
        // The room is built for the new rate now if it plays, else when it
        // is first wanted.
        roomRate = 0
        if (roomOn) roomEngine()?.let { prepareRoom(it) }
        recompute()
        // Fresh format — stale ring/filter contents belong to the old stream.
        java.util.Arrays.fill(ringL, 0f)
        java.util.Arrays.fill(ringR, 0f)
        lpL = 0f; lpR = 0f
        crossGain = targetCrossGain
        delaySamples = targetDelaySamples
    }

    fun update(transform: (CrossfeedState) -> CrossfeedState) {
        _state.value = transform(_state.value)
        recompute()
    }

    fun setEnabled(on: Boolean) = update { it.copy(enabled = on) }

    fun setAlgorithm(algorithm: CrossfeedAlgorithm) = update { it.copy(algorithm = algorithm) }

    fun setSpeakerAngleDeg(deg: Float) = update {
        it.copy(speakerAngleDeg = deg.coerceIn(CrossfeedState.MIN_ANGLE_DEG, CrossfeedState.MAX_ANGLE_DEG))
    }

    /** Off, Crossfeed or Room: null is Off. */
    fun setMode(mode: CrossfeedMode?) = update {
        if (mode == null) it.copy(enabled = false) else it.copy(enabled = true, mode = mode)
    }

    fun setView3d(on: Boolean) = update { it.copy(view3d = on) }

    fun updateRoom(transform: (RoomSettings) -> RoomSettings) = update { it.copy(room = transform(it.room).clamped()) }

    /**
     * The HRTF set the Room plays ([tf.monochrome.android.audio.atmos.AtmosSofaStore.prepared]),
     * or null for the built-in KU100. Builds the room's filters: call it off
     * the audio thread. False when the room would not take it (then it plays
     * the built-in set) or there is no room here at all.
     */
    fun setRoomHrtf(set: FloatArray?): Boolean {
        if (set === roomHrtf && set != null) return true
        // Nothing to take back to the built-in set from a room never made.
        if (set == null && synchronized(roomLock) { room } == null) {
            roomHrtf = null
            return true
        }
        val engine = roomEngine() ?: return false
        val ok = engine.setSofa(set)
        roomHrtf = if (ok) set else null
        if (!ok && set != null) engine.setSofa(null)
        return ok
    }

    /** Frees the room. The effect is not used after this. */
    fun release() {
        synchronized(roomLock) {
            room?.release()
            room = null
        }
    }

    private fun roomEngine(): RoomEngine? = synchronized(roomLock) {
        // A room made again after release() plays the HRTF the last one did.
        room ?: roomFactory()?.also { made ->
            room = made
            roomHrtf?.let { made.setSofa(it) }
        }
    }

    private fun prepareRoom(engine: RoomEngine) {
        val rate = sampleRate.toInt()
        engine.prepare(rate)
        roomRate = rate
        roomAppliedValid = false
    }

    /** The Room on the two channels. Audio thread. */
    private fun processRoom(l: FloatArray, r: FloatArray, frames: Int) {
        val engine = roomEngine() ?: return
        if (roomRate != sampleRate.toInt()) prepareRoom(engine)
        if (!roomPlaying) {
            // Back after a while away: nothing of what it last heard.
            engine.reset()
            roomPlaying = true
        }
        val p = roomParams
        for (i in 0 until RoomSettings.PARAM_COUNT) {
            if (!roomAppliedValid || p[i] != roomApplied[i]) {
                engine.setParameter(i, p[i])
                roomApplied[i] = p[i]
            }
        }
        roomAppliedValid = true
        engine.process(l, r, frames)
    }

    fun processArrays(l: FloatArray, r: FloatArray, frames: Int) {
        if (roomOn) {
            processRoom(l, r, frames)
            // Crossfeed starts again from nothing when it is chosen again.
            if (crossGain != 0f) {
                crossGain = 0f
                java.util.Arrays.fill(ringL, 0f)
                java.util.Arrays.fill(ringR, 0f)
                lpL = 0f; lpR = 0f
            }
            return
        }
        roomPlaying = false
        val gT = targetCrossGain
        // Fast path: bypassed and fully faded out — skip the whole loop (same
        // rationale as the Oxford effects' JNI skip). Reset the delay/filter
        // state so re-enabling doesn't replay a stale tail.
        if (gT <= 0f && crossGain < 1e-4f) {
            if (crossGain != 0f) {
                crossGain = 0f
                java.util.Arrays.fill(ringL, 0f)
                java.util.Arrays.fill(ringR, 0f)
                lpL = 0f; lpR = 0f
            }
            return
        }

        val dT = targetDelaySamples
        val lpC = lowpassCoeff
        val smC = smoothCoeff
        var g = crossGain
        var d = delaySamples
        var pos = ringPos
        var fL = lpL
        var fR = lpR

        for (i in 0 until frames) {
            // Glide gain and delay toward their targets (~5 ms time constant).
            g += (gT - g) * smC
            d += (dT - d) * smC

            val inL = l[i]
            val inR = r[i]
            ringL[pos] = inL
            ringR[pos] = inR

            // Fractional delay tap with linear interpolation.
            val di = d.toInt()
            val frac = d - di
            val i0 = (pos - di) and RING_MASK
            val i1 = (i0 - 1) and RING_MASK
            val tapL = ringL[i0] + (ringL[i1] - ringL[i0]) * frac
            val tapR = ringR[i0] + (ringR[i1] - ringR[i0]) * frac

            // Head-shadow low-pass on the cross path only.
            fL += (tapL - fL) * lpC
            fR += (tapR - fR) * lpC

            // Feed each ear the opposite channel; renormalise so the summed
            // level stays roughly constant as the angle (and thus g) moves.
            val norm = 1f / (1f + g)
            l[i] = (inL + fR * g) * norm
            r[i] = (inR + fL * g) * norm

            pos = (pos + 1) and RING_MASK
        }

        crossGain = g
        delaySamples = d
        ringPos = pos
        lpL = fL
        lpR = fR
    }

    /** Map the selected algorithm (and, for SPEAKER, the angle) onto coefficients. */
    private fun recompute() {
        val s = _state.value
        roomParams = s.room.params()
        roomOn = s.roomOn
        if (s.algorithm == CrossfeedAlgorithm.SPEAKER) {
            val halfAngleRad = Math.toRadians(s.speakerAngleDeg / 2.0)

            // Woodworth ITD for a source at the speaker's azimuth: r/c · (θ + sin θ).
            val itdSec = HEAD_RADIUS_M / SPEED_OF_SOUND_MS * (halfAngleRad + sin(halfAngleRad))
            targetDelaySamples = (itdSec * sampleRate).toFloat().coerceIn(0f, RING_SIZE - 2f)

            // Contralateral level: cos(halfAngle) → full crossfeed as the pair
            // narrows toward the front, zero when the speakers sit at the ears
            // (180°), which is exactly "plain headphones". MAX_CROSS ≈ -4 dB at
            // 30°, in bs2b/Meier territory.
            targetCrossGain = if (!s.crossfeedOn) 0f
                else MAX_CROSS_GAIN * cos(halfAngleRad).toFloat().coerceAtLeast(0f)

            lowpassCoeff = onePoleCoeff(HEAD_SHADOW_CUTOFF_HZ)
        } else {
            targetDelaySamples = (s.algorithm.delayUs * 1e-6 * sampleRate)
                .toFloat().coerceIn(0f, RING_SIZE - 2f)
            targetCrossGain = if (!s.crossfeedOn) 0f
                else 10f.pow(s.algorithm.feedDb / 20f)
            lowpassCoeff = onePoleCoeff(s.algorithm.cutoffHz)
        }
        smoothCoeff = onePoleCoeff(1.0 / (2.0 * PI * SMOOTH_TIME_SEC))
    }

    private fun onePoleCoeff(cutoffHz: Double): Float =
        (1.0 - exp(-2.0 * PI * cutoffHz / sampleRate)).toFloat()

    private companion object {
        const val RING_SIZE = 256
        const val RING_MASK = RING_SIZE - 1
        const val HEAD_RADIUS_M = 0.0875
        const val SPEED_OF_SOUND_MS = 343.0
        const val HEAD_SHADOW_CUTOFF_HZ = 700.0
        const val MAX_CROSS_GAIN = 0.65f
        const val SMOOTH_TIME_SEC = 0.005
    }
}
