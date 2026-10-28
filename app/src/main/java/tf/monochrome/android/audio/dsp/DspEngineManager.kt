package tf.monochrome.android.audio.dsp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.audio.dsp.model.FxTapFrame
import tf.monochrome.android.audio.dsp.model.KnobLink
import tf.monochrome.android.audio.dsp.model.KnobLinkCodec
import tf.monochrome.android.audio.dsp.model.MixUpmix
import tf.monochrome.android.audio.dsp.model.PluginInstance
import tf.monochrome.android.data.preferences.PreferencesManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DspEngineManager @Inject constructor(
    private val processor: MixBusProcessor,
    private val preferences: PreferencesManager,
    private val upmixProcessor: UpmixProcessor,
) {
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _buses = MutableStateFlow(BusConfig.defaultBuses())
    val buses: StateFlow<List<BusConfig>> = _buses.asStateFlow()

    // Meter levels — polled from the UI once per display frame
    // [peakL, peakR, holdL, holdR] per bus = 4 floats each, indexed by bus
    // index; sized for the most buses a mix can hold.
    private val levelsBuffer = FloatArray(BusConfig.MAX_TOTAL_BUSES * 4)
    private val _busLevels = MutableStateFlow(List(BusConfig.defaultBuses().size) { BusLevels() })
    val busLevels: StateFlow<List<BusLevels>> = _busLevels.asStateFlow()

    /**
     * The mix's Atmos upmix: stereo spread to 9.1.6 ahead of the mixer
     * ([UpmixProcessor]). Part of the mix, so it saves and loads with it
     * ([MixUpmix]); it starts with the next track and stops at once.
     */
    private val _upmix = MutableStateFlow(false)
    val upmix: StateFlow<Boolean> = _upmix.asStateFlow()

    /**
     * The knobs that follow the music. Saved with the mix ([KnobLinkCodec]),
     * handed to the engine whenever they change or a mix is loaded, and moved
     * with their effects by the chain edits below exactly as the engine moves
     * its own copy.
     */
    private val _knobLinks = MutableStateFlow<List<KnobLink>>(emptyList())
    val knobLinks: StateFlow<List<KnobLink>> = _knobLinks.asStateFlow()
    private val modValuesBuffer = FloatArray(KnobLinkCodec.MAX_LINKS * 4)

    // Every music link paused from the Music links page; the knob being
    // shaped in the editor plays regardless, or there would be nothing to hear.
    @Volatile private var allKnobLinksPaused = false
    @Volatile private var knobPreviewKey: String? = null

    /** The knob whose link the editor has open, which plays even while all links are paused. */
    fun setKnobLinkPreview(key: String?) {
        knobPreviewKey = key
        pushKnobLinks()
    }

    private val _clipped = MutableStateFlow(false)
    val clipped: StateFlow<Boolean> = _clipped.asStateFlow()

    // Live audio tap for the FX-chain visualizations. Buffers are reused every
    // poll (see FxTapFrame docs); only the frame wrapper is allocated, and only
    // for a frame that differs from the last one.
    private val fxMetersRaw = FloatArray(MAX_PLUGINS_PER_BUS * 2)
    private val fxMetersSmoothed = FloatArray(MAX_PLUGINS_PER_BUS * 2) { -60f }
    private var fxMetersBus = -1
    private val fxWaveBuffer = FloatArray(FX_WAVE_SAMPLES)
    private var fxWaveLen = 0
    private var fxTapSeq = 0L
    private var fxLastPollNanos = 0L
    // What the last native read of the tap saw, for [pollFxTap] to tell when a
    // read would hand back exactly what it already holds.
    private var fxReadPtr = 0L
    private var fxReadBlocks = -1L
    // Set by every edit too: removing or moving a bus shifts what the
    // engine holds under an index, paused or not.
    @Volatile private var fxReadAgain = true
    private val _fxTap = MutableStateFlow<FxTapFrame?>(null)
    val fxTap: StateFlow<FxTapFrame?> = _fxTap.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    init {
        // The Music links page's master switch pauses the knobs too.
        scope.launch {
            preferences.musicLinksPaused.collect { paused ->
                allKnobLinksPaused = paused
                pushKnobLinks()
            }
        }
        @OptIn(FlowPreview::class)
        scope.launch {
            saveSignal.debounce(500L).collect {
                val json = getStateJson()
                if (json != "{}") preferences.setDspStateJson(json)
            }
        }
        // Push the user-selected DSP block size into the processor whenever
        // it changes. The processor honours it on the next queueInput call.
        scope.launch {
            preferences.dspBlockSize.collect { processor.setBlockSize(it) }
        }
        // A wide stream spreads its channel groups one per bus, adding buses
        // as it needs them; a narrower one hands back the ones nobody touched.
        // Either way the engine changed the mixer, so the mirror re-reads it.
        scope.launch {
            preferences.dspSpreadChannels.collect { processor.setSpreadChannels(it) }
        }
        scope.launch {
            processor.channelGroups.collect {
                if (processor.getEnginePtr() != 0L) syncFromEngine()
                else _buses.value = labelled(_buses.value)
            }
        }
        // Master "DSP mixer off" toggle becomes a true bypass on the audio
        // thread — no deinterleave, no nativeProcess, no Oxford, no
        // interleave — so flipping it off should leave audio identical to
        // a build with no DSP wired in at all.
        scope.launch {
            preferences.dspEnabled.collect { enabled ->
                processor.setBypassed(!enabled)
            }
        }
    }

    /**
     * The live state, kept close behind every change.
     *
     * The DataStore write below is debounced by half a second, which is right
     * for flash but wrong as a source of truth: the native engine is
     * reconfigured whenever the audio format changes — a track at a different
     * sample rate is enough — and whatever reapplies state afterwards has to
     * reapply what the user *has*, not what was last written. Reading
     * the preference there meant a knob moved less than 500 ms before a track
     * change was reverted to its previous value, and then saved in that
     * reverted state, which made it permanent.
     *
     * Serialising is not free either: the engine builds the JSON while holding
     * the chain lock the audio thread takes every block, for a few hundred
     * microseconds, and a knob being dragged is an edit per frame. It used to
     * be taken on every one of them. Now an edit only marks the
     * copy stale, and it is brought up to date at most every
     * [LIVE_STATE_INTERVAL_MS] while edits keep coming, once more when they
     * stop, and at once by anything about to read it ([refreshLiveState]).
     * Nothing is lost by the wait: the engine itself holds every edit the
     * moment it is made, and lives as long as the process
     * ([MixBusProcessor.reset]), so the copy taken late is the one the edit
     * would have taken.
     */
    @Volatile private var liveStateJson: String? = null

    // Set by the first edit, so the saved mix loaded at startup (below) never
    // lands on top of a change the user has already made.
    @Volatile private var edited = false

    // An edit has reached the engine that [liveStateJson] does not have yet.
    private val liveStateStale = AtomicBoolean(false)
    // Held across the capture, so an older copy can never land after a newer one.
    private val liveStateLock = Any()
    private val liveStateSignal = Channel<Unit>(Channel.CONFLATED)

    private fun requestSave() {
        edited = true
        fxReadAgain = true
        liveStateStale.set(true)
        // Without an engine the mix is the Kotlin mirror: no lock to wait on,
        // and it has to be caught before an engine is built and would be read
        // in its place. Taken at once, as every edit used to be.
        if (processor.getEnginePtr() == 0L) refreshLiveState() else liveStateSignal.trySend(Unit)
        saveSignal.tryEmit(Unit)
    }

    /** Brings [liveStateJson] up to date when an edit has not reached it yet. */
    private fun refreshLiveState() {
        synchronized(liveStateLock) {
            if (!liveStateStale.getAndSet(false)) return
            getStateJson().takeIf { it != "{}" }?.let { liveStateJson = it }
        }
    }

    // Placed after the fields it touches: Kotlin runs initialisers and init
    // blocks in source order, and this coroutine may run at once on another
    // thread — started any earlier, a field initialiser could reset what it set.
    init {
        // The live copy's catch-up: the first edit at once, then at most one
        // capture per interval for as long as they keep coming, the last of
        // them included (a conflated signal sent during the wait is taken
        // when it ends).
        scope.launch {
            while (true) {
                liveStateSignal.receive()
                refreshLiveState()
                delay(LIVE_STATE_INTERVAL_MS)
            }
        }
        // The engine is only built when audio first plays, but the mixer can
        // be opened before that. Show — and edit — the saved mix meanwhile,
        // not a blank one: a blank mirror saved over the file would lose it.
        scope.launch {
            val json = preferences.dspStateJson.first()
            if (!json.isNullOrEmpty() && json != "{}" &&
                processor.getEnginePtr() == 0L && !edited
            ) {
                _buses.value = labelled(parseBusConfigsFromJson(engineOnly(json)))
                _knobLinks.value = KnobLinkCodec.read(json)
                // Before the first track configures the pipeline, or the
                // first song after launch would play without it.
                applyUpmix(MixUpmix.isOn(json))
                if (liveStateJson == null) liveStateJson = json
            }
        }
    }

    /**
     * Push the state back into a freshly rebuilt engine.
     *
     * Prefers the in-memory copy over the persisted one for the reason above,
     * and falls back to the preference only when there is no in-memory state
     * yet — which is startup, and startup goes through [restoreState] anyway.
     */
    suspend fun reapplyAfterEngineRecreated() {
        refreshLiveState()
        val json = liveStateJson ?: preferences.dspStateJson.first()
        if (!json.isNullOrEmpty() && json != "{}") loadStateJson(json)
        processor.setMixBypassed(!_enabled.value)
    }

    /**
     * Back to a bare mixer: no plugins anywhere, every bus at unity and centre,
     * nothing muted or soloed, and input on bus 1 alone.
     *
     * Driven through the ordinary setters rather than by loading a hand-written
     * default JSON, so native, the Kotlin mirror and the save all move together
     * through paths that are already exercised — a reset that half-applied
     * would be worse than no reset button.
     */
    fun resetToDefaults() {
        // Back to four buses first, highest first so no index moves under us.
        for (bus in _buses.value.filter { it.isRemovable }.sortedByDescending { it.index }) {
            removeBus(bus.index)
        }
        for (bus in _buses.value) {
            for (slot in bus.plugins.indices.reversed()) removePlugin(bus.index, slot)
        }
        for (default in BusConfig.defaultBuses()) {
            setBusGain(default.index, default.gainDb)
            setBusPan(default.index, default.pan)
            setBusMute(default.index, default.muted)
            setBusSolo(default.index, default.soloed)
            setBusInputEnabled(default.index, default.inputEnabled)
        }
        // Every bus back to the master alone.
        for (bus in _buses.value.filter { it.hasCustomSends }) {
            for (dst in bus.sends.keys - BusConfig.DEFAULT_SENDS.keys) setSend(bus.index, dst, 0f)
            setSend(bus.index, BusConfig.MASTER_INDEX, 1f)
        }
        // And plain stereo: a bare mixer has no upmix.
        if (_upmix.value) setUpmix(false)
    }

    // What the meter poll last saw of the engine's processing, for [pollLevels].
    private var lastProcessedBlocks = -1L
    private var lastBlocksChangedNanos = 0L
    private var lastPollNanos = 0L
    private var metersIdle = false

    fun pollLevels() {
        val ptr = processor.getEnginePtr()
        val now = System.nanoTime()
        val dtSec = if (lastPollNanos == 0L) 0f else ((now - lastPollNanos) / 1e9f).coerceAtMost(0.25f)
        lastPollNanos = now
        val blocks = processor.processedBlocks
        if (blocks != lastProcessedBlocks) {
            lastProcessedBlocks = blocks
            lastBlocksChangedNanos = now
        }
        // The player feeds audio in bursts, so many polls see no new block
        // even mid-song; only a quarter second without one means it stopped.
        val processing = ptr != 0L && now - lastBlocksChangedNanos < IDLE_AFTER_NANOS

        if (!processing) {
            // Paused, stopped, bypassed or no engine: nothing moves the
            // engine's meters, which fall only as audio is processed. Let the
            // shown levels fall here instead, at the engine's own 20 dB/s, and
            // clear the engine's so they do not jump back up on resume. (They
            // used to freeze at whatever was last playing.)
            if (!metersIdle && ptr != 0L) processor.nativeResetMeters(ptr)
            metersIdle = true
            val fall = METER_FALL_DB_PER_SEC * dtSec
            _busLevels.value = _busLevels.value.map {
                BusLevels(
                    peakDbL = (it.peakDbL - fall).coerceAtLeast(METER_FLOOR_DB),
                    peakDbR = (it.peakDbR - fall).coerceAtLeast(METER_FLOOR_DB),
                    holdDbL = (it.holdDbL - fall).coerceAtLeast(METER_FLOOR_DB),
                    holdDbR = (it.holdDbR - fall).coerceAtLeast(METER_FLOOR_DB),
                )
            }
            return
        }
        metersIdle = false
        processor.nativeGetBusLevels(ptr, levelsBuffer)
        val count = _buses.value.size.coerceAtMost(BusConfig.MAX_TOTAL_BUSES)
        _busLevels.value = List(count) { b ->
            BusLevels(
                peakDbL = levelsBuffer[b * 4],
                peakDbR = levelsBuffer[b * 4 + 1],
                holdDbL = levelsBuffer[b * 4 + 2],
                holdDbR = levelsBuffer[b * 4 + 3]
            )
        }
        // Check clipping
        if (processor.nativeGetAndResetClipped(ptr)) {
            _clipped.value = true
        }
    }

    /**
     * Poll the per-plugin tap meters and post-fader waveform for [busIndex]
     * into [fxTap]. Called once per display frame (60 or 120 Hz); meters get
     * instant attack and a wall-clock ~45 dB/s release, so the fall speed is
     * the same at any poll rate and short transients stay visible.
     */
    fun pollFxTap(busIndex: Int) {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return
        // The tap's meters and waveform are written only as the engine
        // processes audio, so while no block has gone through since the last
        // read — paused, stopped, or between the player's bursts — a read
        // returns what is already here. One more read after the count stops,
        // in case the last one landed while a block was still being processed.
        val blocks = processor.processedBlocks
        val sameSource = ptr == fxReadPtr && busIndex == fxMetersBus
        val read = !sameSource || blocks != fxReadBlocks || fxReadAgain
        if (read) {
            fxReadAgain = !sameSource || blocks != fxReadBlocks
            fxReadPtr = ptr
            fxReadBlocks = blocks
            processor.nativeGetPluginMeters(ptr, busIndex, fxMetersRaw)
            fxWaveLen = processor.nativeGetBusWaveform(ptr, busIndex, fxWaveBuffer)
        }
        if (fxMetersBus != busIndex) {
            fxMetersRaw.copyInto(fxMetersSmoothed)
            fxMetersBus = busIndex
        }
        val now = System.nanoTime()
        val dt = if (fxLastPollNanos == 0L) 0.016f
                 else ((now - fxLastPollNanos) / 1e9f).coerceIn(0f, 0.1f)
        fxLastPollNanos = now
        val release = FX_METER_RELEASE_DB_PER_SEC * dt
        var moved = false
        for (i in fxMetersSmoothed.indices) {
            val raw = fxMetersRaw[i]
            val before = fxMetersSmoothed[i]
            val after = if (raw >= before) raw else (before - release).coerceAtLeast(-60f)
            if (after != before) moved = true
            fxMetersSmoothed[i] = after
        }
        // Nothing new read and every meter settled (at the floor, or on a
        // level the engine is holding): the frame would be the last one again,
        // and publishing it would only recompose the FX page for nothing.
        if (!read && !moved) return
        _fxTap.value = FxTapFrame(
            seq = ++fxTapSeq,
            busIndex = busIndex,
            meters = fxMetersSmoothed,
            wave = fxWaveBuffer,
            waveLen = fxWaveLen
        )
    }

    companion object {
        /** The engine's meter release (dsp_engine.cpp meterDecayPerSample_), and its floor. */
        private const val METER_FALL_DB_PER_SEC = 20f
        private const val METER_FLOOR_DB = -60f
        private const val IDLE_AFTER_NANOS = 250_000_000L
        /** The longest the live copy of the mix trails a run of edits (see [liveStateJson]). */
        private const val LIVE_STATE_INTERVAL_MS = 250L
        // Mirrors MAX_PLUGINS_PER_BUS in dsp_engine.h — native refuses inserts past this.
        const val MAX_PLUGINS_PER_BUS = 16

        // FX-chain scope tap: samples fetched per poll (~21 ms at 48 kHz).
        // Must be <= WAVE_TAP_SIZE in dsp_engine.h.
        const val FX_WAVE_SAMPLES = 1024

        // Tap meter release in dB/s of wall-clock time — poll-rate independent.
        private const val FX_METER_RELEASE_DB_PER_SEC = 45f

        // Parameter bounds — mirror the native clamps in dsp_engine.cpp / snapin_processor.h.
        // Clamping in Kotlin keeps the StateFlow value in sync with what native actually stores.
        const val MIN_BUS_GAIN_DB = -60f
        const val MAX_BUS_GAIN_DB = 12f
        const val MIN_PAN = -1f
        const val MAX_PAN = 1f
        const val MIN_DRY_WET = 0f
        const val MAX_DRY_WET = 1f
    }

    private fun sanitizeParam(value: Float): Float {
        // Plugin parameter ranges vary per processor; the native side clamps to its own range.
        // Here we only reject NaN/Inf so they never reach the native atomics or StateFlow.
        return if (value.isFinite()) value else 0f
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        // Bypass mix bus plugins when mixer DSP is off; master bus (AutoEQ) keeps running
        processor.setMixBypassed(!enabled)
        scope.launch { preferences.setDspEnabled(enabled) }
    }

    suspend fun restoreState() {
        val enabled = preferences.dspEnabled.first()
        // Edits made before the engine existed are newer than the file, which
        // is only written half a second after them.
        refreshLiveState()
        val stateJson = liveStateJson ?: preferences.dspStateJson.first()

        if (!stateJson.isNullOrEmpty() && stateJson != "{}") {
            loadStateJson(stateJson)
            liveStateJson = stateJson
        }

        _enabled.value = enabled
        processor.setMixBypassed(!enabled)
    }

    // ── Adding and removing buses ───────────────────────────────────────

    /**
     * Adds a bus after the last one: unity, centre, input off, no plugins.
     * Returns its index, or null at [BusConfig.MAX_MIX_BUSES] or with no
     * engine.
     */
    fun addBus(): Int? {
        val ptr = processor.getEnginePtr()
        val index = if (ptr != 0L) {
            processor.nativeAddBus(ptr)
        } else {
            // No engine yet: the mirror is the mix. Same rule as the engine:
            // buses 1–4 and the master are indices 0–4, the next is count + 1.
            val count = BusConfig.mixBusCount(_buses.value)
            if (count >= BusConfig.MAX_MIX_BUSES) -1 else count + 1
        }
        if (index < 0) return null
        _buses.value = labelled(
            (_buses.value + BusConfig(index = index, name = BusConfig.nameFor(index)))
                .sortedBy { it.index }
        )
        requestSave()
        return index
    }

    /**
     * Removes bus [busIndex] (bus 5 and up). The buses above it move down one
     * index in the engine, so the Kotlin mirror is re-read from the engine's
     * own state rather than patched by hand.
     */
    fun removeBus(busIndex: Int): Boolean {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) {
            if (!processor.nativeRemoveBus(ptr, busIndex)) return false
            _knobLinks.value = KnobLinkCodec.afterRemoveBus(_knobLinks.value, busIndex)
            syncFromEngine()
        } else {
            // No engine yet: do to the mirror what the engine would do —
            // drop the bus and move every bus above it down one index.
            val bus = _buses.value.firstOrNull { it.index == busIndex }
            if (bus == null || !bus.isRemovable) return false
            _knobLinks.value = KnobLinkCodec.afterRemoveBus(_knobLinks.value, busIndex)
            _buses.value = labelled(
                _buses.value.filter { it.index != busIndex }.map {
                    val moved = if (it.index > busIndex) it.copy(index = it.index - 1) else it
                    // Routes into the removed bus go; routes above it move down.
                    moved.copy(sends = moved.sends.mapNotNull { (dst, lv) ->
                        when {
                            dst == busIndex -> null
                            dst > busIndex -> dst - 1 to lv
                            else -> dst to lv
                        }
                    }.toMap())
                }.sortedBy { it.index }
            )
        }
        requestSave()
        return true
    }

    // ── Routing ─────────────────────────────────────────────────────────

    /**
     * Sends bus [src]'s post-fader output to bus [dst] (another mix bus or
     * [BusConfig.MASTER_INDEX]) at linear [level]; 0 removes the route.
     * Returns false when refused — the master as a source, a bus that isn't
     * there, or a route that would feed a bus back into itself — and the
     * mirror is left as it was.
     */
    fun setSend(src: Int, dst: Int, level: Float): Boolean {
        val clamped = (if (level.isFinite()) level else 0f).coerceIn(0f, 1f)
        val buses = _buses.value
        if (src == dst || src == BusConfig.MASTER_INDEX) return false
        if (buses.none { it.index == src } || buses.none { it.index == dst }) return false
        if (clamped > 0f && dst != BusConfig.MASTER_INDEX && routeReaches(dst, src)) return false
        val ptr = processor.getEnginePtr()
        if (ptr != 0L && !processor.nativeSetSend(ptr, src, dst, clamped)) return false
        updateBus(src) { bus ->
            bus.copy(sends = if (clamped > 0f) bus.sends + (dst to clamped) else bus.sends - dst)
        }
        requestSave()
        return true
    }

    /** Whether bus [from] already feeds [to], directly or through other buses. */
    fun routeReaches(from: Int, to: Int): Boolean {
        val byIndex = _buses.value.associateBy { it.index }
        val seen = HashSet<Int>()
        val stack = ArrayDeque(listOf(from))
        while (stack.isNotEmpty()) {
            val b = stack.removeLast()
            if (b == to) return true
            if (!seen.add(b)) continue
            byIndex[b]?.sends?.forEach { (d, lv) ->
                if (lv > 0f && d != BusConfig.MASTER_INDEX && d !in seen) stack.addLast(d)
            }
        }
        return false
    }

    // ── Bus controls ────────────────────────────────────────────────────

    fun setBusGain(busIndex: Int, gainDb: Float) {
        val clamped = (if (gainDb.isFinite()) gainDb else 0f)
            .coerceIn(MIN_BUS_GAIN_DB, MAX_BUS_GAIN_DB)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusGain(ptr, busIndex, clamped)
        updateBus(busIndex) { it.copy(gainDb = clamped) }
        requestSave()
    }

    fun setBusPan(busIndex: Int, pan: Float) {
        val clamped = (if (pan.isFinite()) pan else 0f).coerceIn(MIN_PAN, MAX_PAN)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusPan(ptr, busIndex, clamped)
        updateBus(busIndex) { it.copy(pan = clamped) }
        requestSave()
    }

    fun setBusMute(busIndex: Int, muted: Boolean) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusMute(ptr, busIndex, muted)
        updateBus(busIndex) { it.copy(muted = muted) }
        requestSave()
    }

    fun setBusInputEnabled(busIndex: Int, enabled: Boolean) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusInputEnabled(ptr, busIndex, enabled)
        updateBus(busIndex) { it.copy(inputEnabled = enabled) }
        requestSave()
    }

    fun setBusSolo(busIndex: Int, soloed: Boolean) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetBusSolo(ptr, busIndex, soloed)
        updateBus(busIndex) { it.copy(soloed = soloed) }
        requestSave()
    }

    // ── Plugin chain ────────────────────────────────────────────────────

    fun addPlugin(busIndex: Int, slotIndex: Int, type: SnapinType): Int {
        val ptr = processor.getEnginePtr()
        val resultSlot = if (ptr != 0L) {
            processor.nativeAddPlugin(ptr, busIndex, slotIndex, type.ordinal)
        } else {
            // No engine yet (nothing has played): add it to the mirror, which
            // is saved and handed to the engine when it is built. This used to
            // return -1 and the effect silently never appeared.
            val bus = _buses.value.firstOrNull { it.index == busIndex }
            if (bus == null || bus.plugins.size >= MAX_PLUGINS_PER_BUS) -1
            else slotIndex.coerceIn(0, bus.plugins.size)
        }
        if (resultSlot >= 0) {
            _knobLinks.value = KnobLinkCodec.afterInsert(_knobLinks.value, busIndex, resultSlot)
            updateBus(busIndex) { bus ->
                val plugins = bus.plugins.toMutableList()
                plugins.add(resultSlot, PluginInstance(
                    slotIndex = resultSlot,
                    typeOrdinal = type.ordinal
                ))
                // Re-index
                bus.copy(plugins = plugins.mapIndexed { i, p -> p.copy(slotIndex = i) })
            }
            requestSave()
        }
        return resultSlot
    }

    fun removePlugin(busIndex: Int, slotIndex: Int) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeRemovePlugin(ptr, busIndex, slotIndex)
        _knobLinks.value = KnobLinkCodec.afterRemove(_knobLinks.value, busIndex, slotIndex)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins.removeAt(slotIndex)
            }
            bus.copy(plugins = plugins.mapIndexed { i, p -> p.copy(slotIndex = i) })
        }
        requestSave()
    }

    fun movePlugin(busIndex: Int, fromSlot: Int, toSlot: Int) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeMovePlugin(ptr, busIndex, fromSlot, toSlot)
        val chain = _buses.value.firstOrNull { it.index == busIndex }?.plugins?.size ?: 0
        if (fromSlot != toSlot && fromSlot in 0 until chain && toSlot in 0 until chain) {
            _knobLinks.value = KnobLinkCodec.afterMove(_knobLinks.value, busIndex, fromSlot, toSlot)
        }
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (fromSlot in plugins.indices && toSlot in plugins.indices) {
                val plugin = plugins.removeAt(fromSlot)
                plugins.add(toSlot, plugin)
            }
            bus.copy(plugins = plugins.mapIndexed { i, p -> p.copy(slotIndex = i) })
        }
        requestSave()
    }

    fun setParameter(busIndex: Int, slotIndex: Int, paramIndex: Int, value: Float) {
        val sanitized = sanitizeParam(value)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetParameter(ptr, busIndex, slotIndex, paramIndex, sanitized)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                val plugin = plugins[slotIndex]
                plugins[slotIndex] = plugin.copy(
                    parameters = plugin.parameters + (paramIndex to sanitized)
                )
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    /**
     * A whole preset at once: every parameter and the dry/wet, one update of
     * the mirror and one save. (Through [setParameter] a 12-parameter preset
     * would serialise the whole mixer twelve times.)
     */
    fun setParameters(busIndex: Int, slotIndex: Int, values: FloatArray, dryWet: Float) {
        val sanitized = FloatArray(values.size) { sanitizeParam(values[it]) }
        val dw = (if (dryWet.isFinite()) dryWet else MAX_DRY_WET).coerceIn(MIN_DRY_WET, MAX_DRY_WET)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) {
            sanitized.forEachIndexed { i, v -> processor.nativeSetParameter(ptr, busIndex, slotIndex, i, v) }
            processor.nativeSetPluginDryWet(ptr, busIndex, slotIndex, dw)
        }
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                val plugin = plugins[slotIndex]
                plugins[slotIndex] = plugin.copy(
                    parameters = plugin.parameters + sanitized.withIndex().associate { it.index to it.value },
                    dryWet = dw,
                )
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    fun setPluginBypassed(busIndex: Int, slotIndex: Int, bypassed: Boolean) {
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetPluginBypassed(ptr, busIndex, slotIndex, bypassed)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins[slotIndex] = plugins[slotIndex].copy(bypassed = bypassed)
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    fun setPluginDryWet(busIndex: Int, slotIndex: Int, dryWet: Float) {
        val clamped = (if (dryWet.isFinite()) dryWet else MAX_DRY_WET)
            .coerceIn(MIN_DRY_WET, MAX_DRY_WET)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetPluginDryWet(ptr, busIndex, slotIndex, clamped)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins[slotIndex] = plugins[slotIndex].copy(dryWet = clamped)
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    /** Per-plugin oversampling: 1 (off), 2, or 4. Snaps other values down. */
    fun setPluginOversampling(busIndex: Int, slotIndex: Int, factor: Int) {
        val snapped = if (factor >= 4) 4 else if (factor >= 2) 2 else 1
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeSetPluginOversampling(ptr, busIndex, slotIndex, snapped)
        updateBus(busIndex) { bus ->
            val plugins = bus.plugins.toMutableList()
            if (slotIndex in plugins.indices) {
                plugins[slotIndex] = plugins[slotIndex].copy(oversampling = snapped)
            }
            bus.copy(plugins = plugins)
        }
        requestSave()
    }

    // ── State serialization ─────────────────────────────────────────────

    /**
     * The mix as the engine's state JSON: the engine's own when it exists,
     * otherwise the mirror written in the same format ([DspStateJson]), so
     * saving, presets and export all work before anything has played.
     */
    fun getStateJson(): String {
        val ptr = processor.getEnginePtr()
        val buses = if (ptr == 0L) DspStateJson.encode(_buses.value) else processor.nativeGetStateJson(ptr)
        return KnobLinkCodec.attach(MixUpmix.attach(buses, _upmix.value), _knobLinks.value)
    }

    /**
     * Loads a mix the listener chose (a preset, an import) and saves it as
     * theirs. Through [loadStateJson] alone it was not saved until the next
     * edit, so a restart, or the engine being rebuilt for a new format, put
     * the mix from before it back.
     */
    fun loadChosenMix(json: String) {
        loadStateJson(json)
        requestSave()
    }

    fun loadStateJson(json: String) {
        // A mix without the key turns the upmix off: loading a preset is
        // loading all of it.
        applyUpmix(MixUpmix.isOn(json))
        val engineJson = engineOnly(json)
        val ptr = processor.getEnginePtr()
        if (ptr != 0L) processor.nativeLoadStateJson(ptr, engineJson)
        // Sync Kotlin state from what the engine made of it — which, while a
        // wide stream plays, can be more buses than the JSON had.
        if (ptr != 0L) syncFromEngine() else _buses.value = labelled(parseBusConfigsFromJson(engineJson))
        // Loading drops the engine's links; the mix's own go back on, those
        // that still have a knob under them.
        _knobLinks.value = KnobLinkCodec.read(json).filter { link ->
            val bus = _buses.value.firstOrNull { it.index == link.bus }
            bus != null && link.slot < bus.plugins.size
        }
        pushKnobLinks()
    }

    // ── Knobs that follow the music ─────────────────────────────────────

    /** Adds [link], or replaces the link on the same knob. */
    fun putKnobLink(link: KnobLink) {
        _knobLinks.value = KnobLinkCodec.sanitize(_knobLinks.value.filterNot { it.key == link.key } + link)
        pushKnobLinks()
        requestSave()
    }

    /** Removes the link on knob [key]; returns it, for an undo. */
    fun removeKnobLink(key: String): KnobLink? {
        val gone = _knobLinks.value.firstOrNull { it.key == key } ?: return null
        _knobLinks.value = _knobLinks.value - gone
        pushKnobLinks()
        requestSave()
        return gone
    }

    fun setKnobLinkPaused(key: String, paused: Boolean) {
        _knobLinks.value = _knobLinks.value.map { if (it.key == key) it.copy(paused = paused) else it }
        pushKnobLinks()
        requestSave()
    }

    /** An id no link in this mix has. */
    fun newKnobLinkId(): Int = (_knobLinks.value.maxOfOrNull { it.id } ?: -1) + 1

    /**
     * Where the music has each linked knob now, and the band's strength,
     * by knob key. Lock-free on the native side; empty without an engine.
     */
    fun knobLinkValues(): Pair<Map<String, Float>, Map<String, Float>> {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L || _knobLinks.value.isEmpty()) return emptyMap<String, Float>() to emptyMap()
        val n = processor.nativeGetModValues(ptr, modValuesBuffer)
        val byId = _knobLinks.value.associateBy { it.id }
        val values = HashMap<String, Float>()
        val strengths = HashMap<String, Float>()
        var i = 0
        while (i + 3 < n) {
            byId[modValuesBuffer[i].toInt()]?.let { link ->
                values[link.key] = modValuesBuffer[i + 2]
                strengths[link.key] = modValuesBuffer[i + 3]
            }
            i += 4
        }
        return values to strengths
    }

    private fun pushKnobLinks() {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return
        val links = if (allKnobLinksPaused) {
            val preview = knobPreviewKey
            _knobLinks.value.map { if (it.key == preview) it else it.copy(paused = true) }
        } else {
            _knobLinks.value
        }
        processor.nativeSetModRoutes(ptr, KnobLinkCodec.toNative(links))
    }

    /** [json] with the keys only Kotlin reads taken out, for the engine. */
    private fun engineOnly(json: String): String = KnobLinkCodec.strip(MixUpmix.strip(json))

    /** Turns the Atmos upmix on or off, as an edit to the mix. */
    fun setUpmix(on: Boolean) {
        applyUpmix(on)
        requestSave()
    }

    private fun applyUpmix(on: Boolean) {
        _upmix.value = on
        upmixProcessor.setEnabled(on)
    }

    val spreadChannels = preferences.dspSpreadChannels

    fun setSpreadChannels(spread: Boolean) {
        processor.setSpreadChannels(spread)  // at once, not after the write
        scope.launch { preferences.setDspSpreadChannels(spread) }
    }

    /** The channel groups of the stream playing, in bus order; empty for stereo. */
    val channelGroups: StateFlow<List<String>> get() = processor.channelGroups

    /** Re-reads the whole mixer from the engine, grown buses included. */
    private fun syncFromEngine() {
        val ptr = processor.getEnginePtr()
        if (ptr == 0L) return
        _buses.value = labelled(parseBusConfigsFromJson(processor.nativeGetLiveStateJson(ptr)))
    }

    /**
     * Names the buses a multichannel stream is spread onto after the channels
     * they carry — "Front", "Centre", "LFE"… — and clears the names off the
     * rest. Only the mirror is renamed; nothing here is saved.
     */
    private fun labelled(buses: List<BusConfig>): List<BusConfig> {
        val groups = processor.channelGroups.value
        return buses.map { bus ->
            val group = if (bus.isMaster) null else groups.getOrNull(bus.number - 1)
            bus.copy(
                name = group ?: BusConfig.nameFor(bus.index),
                channelGroup = group,
            )
        }
    }

    // ── Internal ────────────────────────────────────────────────────────

    private fun updateBus(busIndex: Int, transform: (BusConfig) -> BusConfig) {
        _buses.value = _buses.value.map { bus ->
            if (bus.index == busIndex) transform(bus) else bus
        }
    }

    private fun parseBusConfigsFromJson(json: String): List<BusConfig> {
        return try {
            val jsonParser = Json { ignoreUnknownKeys = true }
            val root = jsonParser.parseToJsonElement(json).jsonObject
            val busesArray = root["buses"]?.jsonArray ?: return BusConfig.defaultBuses()

            // The engine takes at most 48 mix buses and the master; a longer
            // (hand-edited) file is cut to what it will actually run.
            val parsed = busesArray.take(BusConfig.MAX_TOTAL_BUSES).mapIndexed { index, element ->
                val obj = element.jsonObject
                val plugins = obj["plugins"]?.jsonArray?.mapIndexed { slotIdx, plugEl ->
                    val plugObj = plugEl.jsonObject
                    val typeOrd = plugObj["type"]?.jsonPrimitive?.int ?: 0
                    val bypassed = plugObj["bypassed"]?.jsonPrimitive?.boolean ?: false
                    val dryWet = (plugObj["dryWet"]?.jsonPrimitive?.float ?: 1f)
                        .let { if (it.isFinite()) it else 1f }
                        .coerceIn(MIN_DRY_WET, MAX_DRY_WET)
                    val params = plugObj["params"]?.jsonArray
                        ?.mapIndexed { pi, pv -> pi to sanitizeParam(pv.jsonPrimitive.float) }
                        ?.toMap() ?: emptyMap()
                    val os = when (plugObj["os"]?.jsonPrimitive?.int ?: 1) {
                        4 -> 4; 2 -> 2; else -> 1
                    }
                    PluginInstance(slotIdx, typeOrd, bypassed, dryWet, params, os)
                } ?: emptyList()

                val rawGain = obj["gain"]?.jsonPrimitive?.float ?: 0f
                val rawPan = obj["pan"]?.jsonPrimitive?.float ?: 0f
                // [dst, level, ...]; absent — every save before routing — is
                // the master alone.
                val sends = when {
                    index == BusConfig.MASTER_INDEX -> emptyMap()
                    obj["sends"] == null -> BusConfig.DEFAULT_SENDS
                    else -> obj["sends"]!!.jsonArray.chunked(2).mapNotNull { pair ->
                        if (pair.size < 2) return@mapNotNull null
                        val d = pair[0].jsonPrimitive.float
                        val lv = pair[1].jsonPrimitive.float
                        if (!d.isFinite() || !lv.isFinite() || lv <= 0f) return@mapNotNull null
                        val dst = d.toInt()
                        if (dst == index || dst !in 0 until busesArray.size.coerceAtMost(BusConfig.MAX_TOTAL_BUSES)) null
                        else dst to lv.coerceAtMost(1f)
                    }.toMap()
                }
                BusConfig(
                    index = index,
                    name = BusConfig.nameFor(index),
                    gainDb = (if (rawGain.isFinite()) rawGain else 0f)
                        .coerceIn(MIN_BUS_GAIN_DB, MAX_BUS_GAIN_DB),
                    pan = (if (rawPan.isFinite()) rawPan else 0f).coerceIn(MIN_PAN, MAX_PAN),
                    muted = obj["muted"]?.jsonPrimitive?.boolean ?: false,
                    soloed = obj["soloed"]?.jsonPrimitive?.boolean ?: false,
                    inputEnabled = obj["inputEnabled"]?.jsonPrimitive?.boolean ?: (index == 0),
                    plugins = plugins,
                    sends = sends
                )
            }
            // A short list still loads as buses 1–4 plus the master on the
            // native side (the entries it lacks stay empty), so the mirror
            // fills in the same buses rather than showing a mixer with no master.
            parsed + BusConfig.defaultBuses().drop(parsed.size)
        } catch (e: Exception) {
            Log.w("DspEngineManager", "Failed to parse DSP state JSON, using defaults", e)
            BusConfig.defaultBuses()
        }
    }
}
