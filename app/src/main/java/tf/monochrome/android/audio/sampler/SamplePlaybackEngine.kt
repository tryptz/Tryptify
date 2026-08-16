// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.samples.SampleRepository
import tf.monochrome.android.domain.patterns.Pattern
import tf.monochrome.android.domain.patterns.PatternLimits
import tf.monochrome.android.domain.patterns.PatternSwitchMode
import tf.monochrome.android.domain.patterns.Step
import tf.monochrome.android.domain.patterns.TransportState
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's single view of the pattern engine.
 *
 * Everything above the audio layer talks to this: view models call [play],
 * [toggleStep], [queuePattern] and so on, and read [transport] for the
 * playhead. Underneath, it owns three things nothing else should have to think
 * about.
 *
 * **Sample residency.** The engine addresses sounds by slot, not by database
 * id. This class keeps the map between them, loads PCM off disk on demand,
 * and evicts the least recently used slot when all 256 are taken — so a
 * library of two thousand samples works on a phone with the same footprint as
 * a library of ten.
 *
 * **Publication.** Whole patterns are uploaded by atomic swap; individual
 * edits go over the lock-free command queue. Which one a change takes is
 * decided here, and it always favours the queue: tapping a trig during
 * playback must not re-upload a pattern.
 *
 * **The output claim.** Holding [acquire] keeps the engine rendering — the
 * standalone pump runs while nothing is playing, and [PatternMixProcessor]
 * takes over when a track starts. Screens acquire on entry and release on
 * exit, so an app that is not sampling holds no audio resources at all.
 */
@Singleton
class SamplePlaybackEngine @Inject constructor(
    private val bridge: NativeSamplerBridge,
    private val router: SamplerRenderRouter,
    private val pump: SamplerOutputPump,
    private val samples: SampleRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val residencyLock = Mutex()

    private val _transport = MutableStateFlow(TransportState())
    val transport: StateFlow<TransportState> = _transport.asStateFlow()

    private val _channelTriggers = MutableStateFlow(IntArray(PatternLimits.MAX_CHANNELS))
    /** Monotonic per-channel hit counters; the grid flashes a row when one moves. */
    val channelTriggers: StateFlow<IntArray> = _channelTriggers.asStateFlow()

    private val _available = MutableStateFlow(true)
    /** False when the native library is missing — the screen explains rather than crashing. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    /**
     * Bumped whenever the engine records a live hit into the current pattern,
     * so the view model knows to read the pattern back rather than polling it.
     */
    private val _recordEdits = MutableStateFlow(0)
    val recordEdits: StateFlow<Int> = _recordEdits.asStateFlow()

    // ── sample residency ────────────────────────────────────────────────

    private val slotBySampleId = HashMap<Long, Int>()
    private val sampleIdBySlot = HashMap<Int, Long>()
    private val lastUsed = HashMap<Long, Long>()
    private val useCounter = AtomicInteger(0)

    private val stateScratch = IntArray(NativeSamplerBridge.STATE_SIZE)
    private var pollJob: kotlinx.coroutines.Job? = null
    private var holders = 0
    private val holderLock = Any()

    // ── lifecycle ───────────────────────────────────────────────────────

    /**
     * Claims the engine. Balanced by [release]; the first claim starts the
     * output pump and the state poll, the last one stops both.
     */
    fun acquire() {
        synchronized(holderLock) {
            holders += 1
            if (holders > 1) return
        }
        scope.launch {
            if (!bridge.create(pump.sampleRate)) {
                _available.value = false
                Log.w(TAG, "pattern engine unavailable: ${SamplerNativeLoader.loadError?.message}")
                return@launch
            }
            _available.value = true
            router.engineNeeded = true
            pump.start()
            startPolling()
        }
    }

    fun release() {
        synchronized(holderLock) {
            holders -= 1
            if (holders > 0) return
            if (holders < 0) holders = 0
        }
        scope.launch {
            stop()
            router.engineNeeded = false
            pump.stop()
            pollJob?.cancel()
            pollJob = null
        }
    }

    /**
     * Polls the engine's published atomics.
     *
     * A poll rather than a callback, on purpose: a native callback into Kotlin
     * would have to happen on the audio thread, and attaching a JVM thread
     * there is precisely the kind of unbounded work the audio thread must not
     * do. Reading six ints at frame rate costs nothing by comparison.
     */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                bridge.readState(stateScratch)
                val flags = stateScratch[NativeSamplerBridge.STATE_FLAGS]
                val current = _transport.value
                val next = current.copy(
                    playing = flags and NativeSamplerBridge.FLAG_PLAYING != 0,
                    recording = flags and NativeSamplerBridge.FLAG_RECORDING != 0,
                    currentStep = stateScratch[NativeSamplerBridge.STATE_STEP],
                    currentPattern = stateScratch[NativeSamplerBridge.STATE_PATTERN],
                    queuedPattern = stateScratch[NativeSamplerBridge.STATE_QUEUED],
                    activeVoices = stateScratch[NativeSamplerBridge.STATE_VOICES],
                    countInRemaining = stateScratch[NativeSamplerBridge.STATE_COUNT_IN],
                )
                if (next != current) _transport.value = next

                val triggers = IntArray(PatternLimits.MAX_CHANNELS) {
                    stateScratch[NativeSamplerBridge.STATE_TRIGGERS + it]
                }
                if (!triggers.contentEquals(_channelTriggers.value)) {
                    _channelTriggers.value = triggers
                }

                val edits = stateScratch[NativeSamplerBridge.STATE_RECORD_EDITS]
                if (edits != _recordEdits.value) _recordEdits.value = edits

                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // ── patterns ────────────────────────────────────────────────────────

    /**
     * Makes [pattern] resident and publishes it.
     *
     * Sample loading happens first and off the audio path; the upload itself
     * is retried a few times because the engine refuses a publish while every
     * spare buffer is still in flight, which is a "try again in a millisecond"
     * condition rather than an error.
     */
    suspend fun publishPattern(pattern: Pattern) {
        if (!bridge.isReady) return
        ensureSamplesResident(pattern)
        withContext(Dispatchers.Default) {
            var attempt = 0
            while (attempt < UPLOAD_ATTEMPTS) {
                val published = residencyLock.withLock {
                    bridge.uploadPattern(pattern) { sampleId -> slotBySampleId[sampleId] ?: -1 }
                }
                if (published) return@withContext
                attempt += 1
                delay(UPLOAD_RETRY_MS)
            }
            Log.w(TAG, "pattern ${pattern.bankSlot} upload gave up after $UPLOAD_ATTEMPTS attempts")
        }
    }

    suspend fun publishAll(patterns: Collection<Pattern>) {
        for (pattern in patterns) publishPattern(pattern)
    }

    /**
     * Loads whatever [pattern] needs and evicts what it does not.
     *
     * Eviction is least-recently-used and only ever runs when the bank is
     * full, so the common case — a handful of drum hits reused across every
     * pattern — never touches the disk twice.
     */
    private suspend fun ensureSamplesResident(pattern: Pattern) {
        val wanted = pattern.channels.mapNotNull { it.sampleId }.distinct()
        if (wanted.isEmpty()) return

        val missing = residencyLock.withLock {
            wanted.filter { id ->
                val slot = slotBySampleId[id]
                if (slot != null) {
                    lastUsed[id] = useCounter.incrementAndGet().toLong()
                    false
                } else {
                    true
                }
            }
        }
        if (missing.isEmpty()) return

        val refs = samples.byIds(missing)
        for (id in missing) {
            val ref = refs[id] ?: continue
            val audio = samples.loadAudio(ref) ?: continue
            residencyLock.withLock {
                if (slotBySampleId.containsKey(id)) return@withLock
                val slot = allocateSlotLocked()
                if (slot < 0) return@withLock
                val loaded = bridge.putSample(
                    slot = slot,
                    left = audio.left,
                    right = audio.right,
                    frames = audio.frames,
                    sampleRate = audio.sampleRate,
                    gain = ref.gain,
                )
                if (loaded) {
                    slotBySampleId[id] = slot
                    sampleIdBySlot[slot] = id
                    lastUsed[id] = useCounter.incrementAndGet().toLong()
                }
            }
        }
        // Buffers displaced by the loads above are freed once no voice can
        // still be reading them; the engine decides when that is.
        bridge.collectGarbage()
    }

    private fun allocateSlotLocked(): Int {
        for (slot in 0 until MAX_SLOTS) {
            if (!sampleIdBySlot.containsKey(slot)) return slot
        }
        // Full: evict the sample nobody has asked for in the longest time.
        val victim = lastUsed.minByOrNull { it.value }?.key ?: return -1
        val slot = slotBySampleId.remove(victim) ?: return -1
        sampleIdBySlot.remove(slot)
        lastUsed.remove(victim)
        bridge.clearSample(slot)
        return slot
    }

    /** Drops a deleted sample out of the engine so its memory comes back. */
    suspend fun evictSample(sampleId: Long) {
        residencyLock.withLock {
            val slot = slotBySampleId.remove(sampleId) ?: return@withLock
            sampleIdBySlot.remove(slot)
            lastUsed.remove(sampleId)
            bridge.clearSample(slot)
        }
        bridge.collectGarbage()
    }

    // ── transport ───────────────────────────────────────────────────────

    fun play() {
        bridge.post(NativeSamplerBridge.CMD_TRANSPORT, NativeSamplerBridge.TRANSPORT_PLAY)
    }

    fun stop() {
        bridge.post(NativeSamplerBridge.CMD_TRANSPORT, NativeSamplerBridge.TRANSPORT_STOP)
    }

    fun setRecording(enabled: Boolean) {
        bridge.post(
            NativeSamplerBridge.CMD_TRANSPORT,
            if (enabled) NativeSamplerBridge.TRANSPORT_RECORD_ON
            else NativeSamplerBridge.TRANSPORT_RECORD_OFF,
        )
        _transport.value = _transport.value.copy(recording = enabled)
    }

    fun setBpm(bpm: Float) {
        val clamped = bpm.coerceIn(20f, 300f)
        bridge.post(NativeSamplerBridge.CMD_SET_BPM, f = clamped)
        _transport.value = _transport.value.copy(bpm = clamped)
    }

    fun setSwing(swing: Float) {
        val clamped = swing.coerceIn(0f, 1f)
        bridge.post(NativeSamplerBridge.CMD_SET_SWING, f = clamped)
        _transport.value = _transport.value.copy(swing = clamped)
    }

    fun setMetronome(enabled: Boolean) {
        bridge.post(NativeSamplerBridge.CMD_SET_METRONOME, if (enabled) 1 else 0)
        _transport.value = _transport.value.copy(metronome = enabled)
    }

    fun setCountIn(bars: Int) {
        val clamped = bars.coerceIn(0, 4)
        bridge.post(NativeSamplerBridge.CMD_SET_COUNT_IN, clamped)
        _transport.value = _transport.value.copy(countInBars = clamped)
    }

    fun setRecordQuantum(quantum: Int) {
        val clamped = quantum.coerceIn(0, 16)
        bridge.post(NativeSamplerBridge.CMD_SET_RECORD_QUANTIZE, clamped)
        _transport.value = _transport.value.copy(recordQuantum = clamped)
    }

    fun setSwitchMode(mode: PatternSwitchMode) {
        _transport.value = _transport.value.copy(switchMode = mode)
    }

    fun setMasterGain(gain: Float) {
        bridge.post(NativeSamplerBridge.CMD_SET_MASTER_GAIN, f = gain.coerceIn(0f, 2f))
    }

    /**
     * Switches to [bankSlot].
     *
     * Honours the user's switch mode: by default the change waits for the
     * current loop to come round, which is what makes switching patterns
     * usable while the loop is running rather than a stumble every time.
     */
    fun queuePattern(bankSlot: Int, immediate: Boolean = false) {
        val useImmediate = immediate ||
            _transport.value.switchMode == PatternSwitchMode.IMMEDIATE ||
            !_transport.value.playing
        bridge.post(
            NativeSamplerBridge.CMD_QUEUE_PATTERN,
            bankSlot.coerceIn(0, PatternLimits.MAX_PATTERNS - 1),
            if (useImmediate) 1 else 0,
        )
        if (useImmediate) {
            _transport.value = _transport.value.copy(currentPattern = bankSlot, queuedPattern = -1)
        } else {
            _transport.value = _transport.value.copy(queuedPattern = bankSlot)
        }
    }

    // ── edits ───────────────────────────────────────────────────────────

    fun toggleStep(bankSlot: Int, channel: Int, step: Int, enabled: Boolean, velocity: Int) {
        bridge.post(
            NativeSamplerBridge.CMD_TOGGLE_STEP,
            bankSlot, channel, step,
            if (enabled) 1 else 0,
            velocity.coerceIn(1, 127) / 127f,
        )
    }

    fun writeStep(bankSlot: Int, channel: Int, index: Int, step: Step) {
        bridge.postStep(
            patternIndex = bankSlot,
            channel = channel,
            stepIndex = index,
            enabled = step.enabled,
            velocity = step.velocity,
            pitch = step.pitch,
            probability = step.probability,
            pan = step.pan,
            sampleStart = step.sampleStart,
            locks = step.locks,
            speed = step.speedCode,
        )
    }

    fun setChannelParam(bankSlot: Int, channel: Int, param: Int, value: Float) {
        bridge.post(NativeSamplerBridge.CMD_SET_CHANNEL_PARAM, bankSlot, channel, param, f = value)
    }

    /**
     * Global time-stretch quality.
     *
     * The engine reads it when a voice starts rather than while one is
     * running, so changing it mid-loop takes effect on the next trig instead of
     * reconfiguring a stretcher part-way through a grain.
     */
    fun setStretchQuality(quality: Int) {
        bridge.post(NativeSamplerBridge.CMD_SET_STRETCH_QUALITY, quality)
    }

    fun setPatternLength(bankSlot: Int, length: Int) {
        bridge.post(
            NativeSamplerBridge.CMD_SET_PATTERN_LENGTH,
            bankSlot,
            length.coerceIn(1, PatternLimits.MAX_STEPS),
        )
    }

    fun clearPattern(bankSlot: Int) {
        bridge.post(NativeSamplerBridge.CMD_CLEAR_PATTERN, bankSlot)
    }

    fun clearChannel(bankSlot: Int, channel: Int) {
        bridge.post(NativeSamplerBridge.CMD_CLEAR_CHANNEL, bankSlot, channel)
    }

    /**
     * Plays a channel now — a pad tap, or an audition from the sound editor.
     *
     * When the transport is running and armed, the engine also writes the hit
     * into the pattern at the quantized step. That decision is the engine's,
     * not this class's, because only the audio thread knows exactly where the
     * playhead was when the tap arrived.
     */
    fun trigger(channel: Int, velocity: Float = 1f) {
        bridge.post(NativeSamplerBridge.CMD_TRIGGER, channel, f = velocity.coerceIn(0.05f, 1f))
    }

    fun stopChannel(channel: Int) {
        bridge.post(NativeSamplerBridge.CMD_STOP_CHANNEL, channel)
    }

    /**
     * Reads a pattern's trigs back out of the engine.
     *
     * The engine is authoritative for exactly one piece of content: the step a
     * live hit quantized to. Only the audio thread knows where the playhead
     * was when the tap arrived, so the alternative — recomputing the
     * quantization from the last published playhead — lands a step early or
     * late whenever a tap falls near a boundary. That is precisely the case
     * live recording exists to get right.
     *
     * Returns one list of steps per channel, or null when the pattern is not
     * resident.
     */
    suspend fun readSteps(bankSlot: Int, channelCount: Int): List<List<Step>>? =
        withContext(Dispatchers.Default) {
            if (!bridge.isReady || channelCount <= 0) return@withContext null
            val stride = bridge.stepStride()
            val buffer = ByteArray(channelCount * PatternLimits.MAX_STEPS * stride)
            val read = bridge.readSteps(bankSlot, buffer)
            if (read <= 0) return@withContext null

            (0 until minOf(read, channelCount)).map { channel ->
                (0 until PatternLimits.MAX_STEPS).map { index ->
                    val b = (channel * PatternLimits.MAX_STEPS + index) * stride
                    Step(
                        enabled = buffer[b].toInt() != 0,
                        // Velocity, probability and sample start are unsigned
                        // on the native side; a plain toInt() on a JVM byte
                        // would read anything above 127 as negative.
                        velocity = buffer[b + 1].toInt() and 0xFF,
                        pitch = buffer[b + 2].toInt(),
                        probability = buffer[b + 3].toInt() and 0xFF,
                        pan = buffer[b + 4].toInt(),
                        sampleStart = buffer[b + 5].toInt() and 0xFF,
                        locks = buffer[b + 6].toInt() and 0xFF,
                        speedCode = buffer[b + 7].toInt() and 0xFF,
                    )
                }
            }
        }

    companion object {
        private const val TAG = "SamplePlaybackEngine"

        /** Matches `kMaxSampleSlots` in `cpp/sampler/sampler_types.h`. */
        private const val MAX_SLOTS = 256

        /** ~60 Hz. The playhead is the only thing that needs this cadence. */
        private const val POLL_INTERVAL_MS = 16L

        private const val UPLOAD_ATTEMPTS = 8
        private const val UPLOAD_RETRY_MS = 4L
    }
}
