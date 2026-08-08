package tf.monochrome.android.audio.input

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.model.BusInputSource
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ties the capture engine, the mixer's bus routing and the playback carrier
 * together, so the UI has one thing to talk to and `PlaybackService` has one
 * flow to watch.
 *
 * The service can't be called directly from a ViewModel, so the Live-mode
 * carrier is requested rather than invoked: [carrierRequest] publishes the
 * device label while a silent carrier stream is wanted, and `PlaybackService`
 * collects it. That is the same shape as the rest of the service's inputs,
 * which are all preference flows.
 */
@Singleton
class StereoInputController @Inject constructor(
    private val engine: StereoInputEngine,
    private val dspManager: DspEngineManager,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _active = MutableStateFlow(false)
    /** True between [start] and [stop], independent of whether a device opened. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _mode = MutableStateFlow(StereoInputMode.Live)
    val mode: StateFlow<StereoInputMode> = _mode.asStateFlow()

    private val _enabled = MutableStateFlow(false)
    /** The Settings master switch. Line-in cannot start while this is off. */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _carrierRequest = MutableStateFlow<String?>(null)
    /** Device label while `PlaybackService` should be playing the silent carrier. */
    val carrierRequest: StateFlow<String?> = _carrierRequest.asStateFlow()

    val status: StateFlow<StereoInputStatus> get() = engine.status
    val lastError: StateFlow<StereoInputFailure?> get() = engine.lastError
    val activeFormat: StateFlow<StereoInputFormat?> get() = engine.activeFormat

    init {
        scope.launch { restorePreferences() }
    }

    private suspend fun restorePreferences() {
        _enabled.value = preferences.stereoInputEnabled.first()
        _mode.value = StereoInputMode.fromKey(preferences.stereoInputMode.first())
        engine.selectDevice(preferences.stereoInputDeviceId.first())
        engine.setTrimDb(preferences.stereoInputTrimDb.first())
        engine.setChannelMode(
            runCatching { StereoInputChannelMode.valueOf(preferences.stereoInputChannelMode.first()) }
                .getOrDefault(StereoInputChannelMode.Stereo)
        )
    }

    // ── Settings ─────────────────────────────────────────────────────────

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        scope.launch { preferences.setStereoInputEnabled(enabled) }
        if (!enabled) stop()
    }

    fun setMode(mode: StereoInputMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        scope.launch { preferences.setStereoInputMode(mode.key) }
        if (!_active.value) return
        // Switching mode live means the carrier has to appear or disappear;
        // capture itself keeps running either way.
        _carrierRequest.value = if (mode == StereoInputMode.Live) currentDeviceLabel() else null
    }

    fun selectDevice(deviceId: Int?) {
        engine.selectDevice(deviceId)
        scope.launch { preferences.setStereoInputDeviceId(deviceId) }
        if (_active.value && _mode.value == StereoInputMode.Live) {
            _carrierRequest.value = currentDeviceLabel()
        }
    }

    fun setChannelMode(mode: StereoInputChannelMode) {
        engine.setChannelMode(mode)
        scope.launch { preferences.setStereoInputChannelMode(mode.name) }
    }

    fun setTrimDb(db: Float) {
        engine.setTrimDb(db)
        scope.launch { preferences.setStereoInputTrimDb(db) }
    }

    // ── Start / stop ─────────────────────────────────────────────────────

    fun toggle() {
        if (_active.value) stop() else start()
    }

    /**
     * Begin capture, auto-routing a bus to the input if nothing reads it yet,
     * and request the silent carrier when in Live mode.
     */
    fun start() {
        if (!_enabled.value || _active.value) return
        _active.value = true
        ensureRouted()
        engine.start()
        if (_mode.value == StereoInputMode.Live) {
            _carrierRequest.value = currentDeviceLabel()
        }
    }

    fun stop() {
        if (!_active.value) return
        _active.value = false
        _carrierRequest.value = null
        engine.stop()
        restoreAutoRouting()
    }

    /**
     * Make sure the input is audible on first use without overriding a routing
     * the user set up deliberately.
     *
     * If any bus already reads line-in we change nothing — that is someone's
     * console layout. Otherwise: Live mode points every enabled bus at the
     * input (there is no playback to hear anyway), and Mix mode claims one
     * spare bus for it so the track keeps playing through its own chain.
     */
    private fun ensureRouted() {
        val buses = dspManager.buses.value.filter { !it.isMaster }
        val alreadyRouted = buses.any {
            it.inputEnabled && it.inputSource != BusInputSource.Playback
        }
        if (alreadyRouted) return

        val changes = mutableListOf<AutoRoute>()
        fun route(bus: tf.monochrome.android.audio.dsp.model.BusConfig, to: BusInputSource) {
            changes += AutoRoute(bus.index, bus.inputEnabled, bus.inputSource, to)
            dspManager.setBusInputEnabled(bus.index, true)
            dspManager.setBusInputSource(bus.index, to)
        }

        when (_mode.value) {
            StereoInputMode.Live -> {
                val enabledBuses = buses.filter { it.inputEnabled }
                val targets = enabledBuses.ifEmpty { buses.take(1) }
                targets.forEach { route(it, BusInputSource.LineIn) }
            }
            StereoInputMode.Mix -> {
                // Prefer a bus that is not already carrying playback; only fall
                // back to doubling one up if all four are in use.
                val spare = buses.firstOrNull { !it.inputEnabled }
                if (spare != null) route(spare, BusInputSource.LineIn)
                else route(buses.last(), BusInputSource.Both)
            }
        }
        autoRouted = changes
    }

    /**
     * Put back any routing [ensureRouted] applied on our behalf.
     *
     * Without this, stopping Live mode would leave every bus pointed at an
     * input that is no longer running — and the library would play in silence
     * with no obvious cause. Buses the user re-routed in the meantime are left
     * alone: a bus is only reverted if it still holds exactly the value we set.
     */
    private fun restoreAutoRouting() {
        val snapshot = autoRouted
        autoRouted = emptyList()
        if (snapshot.isEmpty()) return
        val current = dspManager.buses.value
        snapshot.forEach { change ->
            val bus = current.firstOrNull { it.index == change.busIndex } ?: return@forEach
            if (bus.inputSource != change.appliedSource) return@forEach
            dspManager.setBusInputSource(change.busIndex, change.previousSource)
            dspManager.setBusInputEnabled(change.busIndex, change.previousEnabled)
        }
    }

    /** One bus's routing as it was before [ensureRouted] touched it. */
    private data class AutoRoute(
        val busIndex: Int,
        val previousEnabled: Boolean,
        val previousSource: BusInputSource,
        val appliedSource: BusInputSource,
    )

    private var autoRouted: List<AutoRoute> = emptyList()

    private fun currentDeviceLabel(): String {
        val id = engine.selectedDeviceId.value
        val device = engine.devices.value.let { list ->
            list.firstOrNull { it.id == id } ?: list.firstOrNull()
        }
        return device?.let { engine.describe(it) } ?: "No input device"
    }
}
