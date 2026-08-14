package tf.monochrome.android.ui.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.eq.AutoEqEngine
import tf.monochrome.android.audio.eq.EqDataParser
import tf.monochrome.android.audio.eq.FrequencyTargets
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.EqRepository
import tf.monochrome.android.data.repository.HeadphoneRepository
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.EqPreset
import tf.monochrome.android.domain.model.EqTarget
import tf.monochrome.android.domain.model.FrequencyPoint
import tf.monochrome.android.domain.model.Headphone
import kotlinx.serialization.Serializable
import javax.inject.Inject
import kotlin.math.pow

@Serializable
private data class StoredCustomTarget(
    val id: String,
    val label: String,
    val rawData: String
)

@HiltViewModel
class EqViewModel @Inject constructor(
    private val eqRepository: EqRepository,
    private val headphoneRepository: HeadphoneRepository,
    private val preferences: PreferencesManager
) : ViewModel() {

    // ===== Tutorial State =====

    private val _showTutorial = MutableStateFlow(false)
    val showTutorial: StateFlow<Boolean> = _showTutorial.asStateFlow()

    // ===== UI State =====

    private val _eqEnabled = MutableStateFlow(false)
    val eqEnabled: StateFlow<Boolean> = _eqEnabled.asStateFlow()

    private val _allPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val allPresets: StateFlow<List<EqPreset>> = _allPresets.asStateFlow()

    private val _activePreset = MutableStateFlow<EqPreset?>(null)
    val activePreset: StateFlow<EqPreset?> = _activePreset.asStateFlow()

    private val _currentBands = MutableStateFlow<List<EqBand>>(emptyList())
    val currentBands: StateFlow<List<EqBand>> = _currentBands.asStateFlow()

    // ── 2-channel (per-ear) calibration ────────────────────────────────
    private val _stereoMode = MutableStateFlow(false)
    val stereoMode: StateFlow<Boolean> = _stereoMode.asStateFlow()

    // Right-ear bands. Only applied while stereo mode is on, but retained
    // across the toggle so switching off and back on is non-destructive.
    private val _currentBandsR = MutableStateFlow<List<EqBand>>(emptyList())
    val currentBandsR: StateFlow<List<EqBand>> = _currentBandsR.asStateFlow()

    private val _originalMeasurementR = MutableStateFlow<List<FrequencyPoint>>(emptyList())
    val originalMeasurementR: StateFlow<List<FrequencyPoint>> = _originalMeasurementR.asStateFlow()

    // Which ear band edits and measurement loads target while stereo is on.
    private val _editChannel = MutableStateFlow(EqChannel.LEFT)
    val editChannel: StateFlow<EqChannel> = _editChannel.asStateFlow()

    // Names of the per-ear measurement selections for the channel dropdowns.
    // Session-scoped: the curves themselves are restored across restarts, the
    // labels aren't (they'd need their own keys for marginal value).
    private val _measurementLabelL = MutableStateFlow<String?>(null)
    val measurementLabelL: StateFlow<String?> = _measurementLabelL.asStateFlow()
    private val _measurementLabelR = MutableStateFlow<String?>(null)
    val measurementLabelR: StateFlow<String?> = _measurementLabelR.asStateFlow()

    // Sample stepping (squig.link publishes numbered sweeps: L1/L2/L3…).
    // The loaded measurement is kept per ear so the ▲▼ stepper can re-fetch a
    // different sample; the sample name drives the stepper's label and
    // visibility (null = no steppable measurement loaded on that ear).
    private var selectedMeasL: tf.monochrome.android.domain.model.AutoEqMeasurement? = null
    private var selectedMeasR: tf.monochrome.android.domain.model.AutoEqMeasurement? = null
    private val sampleListCache = mutableMapOf<String, List<String>>()
    private val _measurementSampleL = MutableStateFlow<String?>(null)
    val measurementSampleL: StateFlow<String?> = _measurementSampleL.asStateFlow()
    private val _measurementSampleR = MutableStateFlow<String?>(null)
    val measurementSampleR: StateFlow<String?> = _measurementSampleR.asStateFlow()

    // Measurement smoothing, SeapEngine-style percent (0–100; 0 = off).
    // Session-scoped.
    private val _smoothing = MutableStateFlow(0f)
    val smoothing: StateFlow<Float> = _smoothing.asStateFlow()

    // Fitting algorithm — selectable so shelf-ends and peaking-only stacks
    // can be A/B'd on the same measurement. Applies on the next AutoEQ press.
    private val _algorithm = MutableStateFlow(tf.monochrome.android.audio.eq.AutoEqAlgorithm.PEAKING)
    val algorithm: StateFlow<tf.monochrome.android.audio.eq.AutoEqAlgorithm> = _algorithm.asStateFlow()

    fun setAlgorithm(algorithm: tf.monochrome.android.audio.eq.AutoEqAlgorithm) {
        _algorithm.value = algorithm
    }

    // ONE mutation job at a time. AutoEq presses, sample steps and measurement
    // loads cancel-and-replace each other; the join guarantees the previous
    // job's writes (and its finally) fully complete before the next starts, so
    // per-ear state can never end up torn across two sources.
    private var fitJob: Job? = null

    private fun launchFit(block: suspend CoroutineScope.() -> Unit) {
        val previous = fitJob
        fitJob = viewModelScope.launch {
            previous?.cancelAndJoin()
            block()
        }
    }

    /**
     * Fit settings snapshotted ONCE per user action and threaded through every
     * per-ear fit of that action — a slider moved mid-computation must never
     * leave the two ears corrected at different settings.
     */
    private data class FitSettings(
        val smoothing: Float,
        val bandCount: Int,
        val maxFrequency: Float,
        val algorithm: tf.monochrome.android.audio.eq.AutoEqAlgorithm,
    )

    private fun fitSettings() = FitSettings(
        _smoothing.value,
        _bandCount.value,
        _maxFrequency.value,
        _algorithm.value,
    )

    private val _currentPreamp = MutableStateFlow(0f)
    val currentPreamp: StateFlow<Float> = _currentPreamp.asStateFlow()

    // Automatic preamp: when on, the preamp is derived from the bands (see
    // applyAutoPreamp) and the manual slider is disabled in the UI.
    private val _autoPreamp = MutableStateFlow(false)
    val autoPreamp: StateFlow<Boolean> = _autoPreamp.asStateFlow()

    // Bass/treble tone shelves — the SAME shared preference the player's Audio
    // tools panel edits. Instant local state for smooth knobs, debounced persist.
    private val _toneControls =
        MutableStateFlow(tf.monochrome.android.domain.model.ToneControls.DEFAULT)
    val toneControls: StateFlow<tf.monochrome.android.domain.model.ToneControls> =
        _toneControls.asStateFlow()
    private var tonePersistJob: kotlinx.coroutines.Job? = null

    // Drag-tail persistence. One Json instance rather than one per save (these
    // used to be constructed per call, i.e. per drag frame), and one in-flight
    // job per key so a coalesced write can be superseded by the next edit.
    private val persistJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    private var bandsPersistJob: kotlinx.coroutines.Job? = null
    private var bandsRPersistJob: kotlinx.coroutines.Job? = null
    private var preampPersistJob: kotlinx.coroutines.Job? = null

    private val _availableTargets = MutableStateFlow<List<EqTarget>>(FrequencyTargets.getAllTargets())
    val availableTargets: StateFlow<List<EqTarget>> = _availableTargets.asStateFlow()

    private val _selectedTarget = MutableStateFlow<EqTarget>(FrequencyTargets.getHarmanOverEar2018())
    val selectedTarget: StateFlow<EqTarget> = _selectedTarget.asStateFlow()

    private val _isCalculating = MutableStateFlow(false)
    val isCalculating: StateFlow<Boolean> = _isCalculating.asStateFlow()

    // Fires when a band drag exceeded the AutoEQ gain cap and got clamped, so the
    // UI can surface a transient toast instead of silently discarding the overshoot.
    private val _bandClampEvents = MutableSharedFlow<Float>(extraBufferCapacity = 1)
    val bandClampEvents: SharedFlow<Float> = _bandClampEvents.asSharedFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _customPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val customPresets: StateFlow<List<EqPreset>> = _customPresets.asStateFlow()

    private val _presetCount = MutableStateFlow(0)
    val presetCount: StateFlow<Int> = _presetCount.asStateFlow()

    // ===== Headphone Selection State =====

    private val _availableHeadphones = MutableStateFlow<List<Headphone>>(emptyList())
    val availableHeadphones: StateFlow<List<Headphone>> = _availableHeadphones.asStateFlow()

    private val _selectedHeadphone = MutableStateFlow<Headphone?>(null)
    val selectedHeadphone: StateFlow<Headphone?> = _selectedHeadphone.asStateFlow()

    private val _headphonesLoading = MutableStateFlow(false)
    val headphonesLoading: StateFlow<Boolean> = _headphonesLoading.asStateFlow()

    private val _headphoneSearchQuery = MutableStateFlow("")
    val headphoneSearchQuery: StateFlow<String> = _headphoneSearchQuery.asStateFlow()

    // ===== AutoEQ Parameters =====

    private val _bandCount = MutableStateFlow(10)
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    private val _maxFrequency = MutableStateFlow(16000f)
    val maxFrequency: StateFlow<Float> = _maxFrequency.asStateFlow()


    // ===== Uploaded user measurements =====

    private val _uploadedHeadphones = MutableStateFlow<List<Headphone>>(emptyList())
    val uploadedHeadphones: StateFlow<List<Headphone>> = _uploadedHeadphones.asStateFlow()

    // ===== Rig filter (new index) =====

    private val _selectedRig = MutableStateFlow<tf.monochrome.android.domain.model.MeasurementRig?>(null)
    val selectedRig: StateFlow<tf.monochrome.android.domain.model.MeasurementRig?> = _selectedRig.asStateFlow()

    val availableRigs: StateFlow<List<tf.monochrome.android.domain.model.MeasurementRig>> = _availableHeadphones
        .map { hps ->
            hps.flatMap { hp -> hp.measurements }
                .map { it.rig }
                .toSortedSet(compareBy { it.ordinal })
                .toList()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setRigFilter(rig: tf.monochrome.android.domain.model.MeasurementRig?) {
        _selectedRig.value = rig
    }

    private val _originalMeasurement = MutableStateFlow<List<FrequencyPoint>>(emptyList())
    val originalMeasurement: StateFlow<List<FrequencyPoint>> = _originalMeasurement.asStateFlow()

    private val _customTargets = MutableStateFlow<List<EqTarget>>(emptyList())

    // ===== Initialization =====

    init {
        loadInitialState()
    }

    fun setToneControls(controls: tf.monochrome.android.domain.model.ToneControls) {
        _toneControls.value = controls
        tonePersistJob?.cancel()
        tonePersistJob = viewModelScope.launch {
            kotlinx.coroutines.delay(120)
            preferences.setSystemToneControls(controls)
        }
    }

    private fun loadInitialState() {
        viewModelScope.launch {
            preferences.eqTutorialSeen.collect { seen ->
                _showTutorial.value = !seen
            }
        }

        viewModelScope.launch {
            preferences.systemToneControls.collect { _toneControls.value = it }
        }

        viewModelScope.launch {
            preferences.eqEnabled.collect { enabled ->
                _eqEnabled.value = enabled
            }
        }

        viewModelScope.launch {
            eqRepository.getAllPresets().collect { presets ->
                _allPresets.value = presets
            }
        }

        viewModelScope.launch {
            eqRepository.getCustomPresets().collect { presets ->
                _customPresets.value = presets
            }
        }

        viewModelScope.launch {
            eqRepository.getCustomPresetCount().collect { count ->
                _presetCount.value = count
            }
        }

        // Restore bands + preset + headphone from persistent storage.
        // NOTE: use Flow.first() to await the first DataStore emission;
        // the previous stateIn(...).value pattern raced and almost always
        // returned the null initial value before the prefs read landed,
        // which made every restore here silently no-op.
        viewModelScope.launch {
            val presetId = preferences.eqActivePresetId.first()

            if (presetId != null) {
                val preset = eqRepository.getPresetById(presetId)
                _activePreset.value = preset
                if (preset != null) {
                    _currentBands.value = preset.bands
                    _currentPreamp.value = preset.preamp
                    _selectedTarget.value = FrequencyTargets.getTargetById(preset.targetId)
                        ?: FrequencyTargets.getHarmanOverEar2018()
                }
            } else {
                // No active preset — restore raw bands from DataStore
                val bandsJson = preferences.eqBandsJson.first()
                if (!bandsJson.isNullOrBlank()) {
                    try {
                        val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        val bands = jsonParser.decodeFromString(
                            kotlinx.serialization.builtins.ListSerializer(EqBand.serializer()),
                            bandsJson
                        )
                        _currentBands.value = bands
                    } catch (_: Exception) { }
                }
            }

            // Restore saved headphone
            val headphoneId = preferences.eqSelectedHeadphoneId.first()
            val headphoneName = preferences.eqSelectedHeadphoneName.first()
            if (headphoneId != null && headphoneName != null) {
                _selectedHeadphone.value = Headphone(id = headphoneId, name = headphoneName)
            }

            // Restore user-uploaded headphone measurements so the "Uploaded"
            // chip in HeadphoneSelectScreen has its rows even before any
            // remote source has loaded.
            val uploadedJson = preferences.eqUploadedHeadphonesJson.first()
            try {
                val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                val uploads = jsonParser.decodeFromString(
                    kotlinx.serialization.builtins.ListSerializer(Headphone.serializer()),
                    uploadedJson,
                )
                _uploadedHeadphones.value = uploads
            } catch (_: Exception) { }

            // Restore the cached measurement curve so the FR graph repopulates
            // immediately when the EQ screen reopens, no network round-trip.
            val measurementJson = preferences.eqMeasurementJson.first()
            if (!measurementJson.isNullOrBlank()) {
                try {
                    val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val points = jsonParser.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(FrequencyPoint.serializer()),
                        measurementJson,
                    )
                    _originalMeasurement.value = points
                } catch (_: Exception) { }
            }

            // Restore 2-channel state: the switch, right-ear bands, right-ear
            // measurement. All three survive the switch being off.
            _stereoMode.value = preferences.eqStereoMode.first()
            val bandsRJson = preferences.eqBandsRJson.first()
            if (!bandsRJson.isNullOrBlank()) {
                try {
                    val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    _currentBandsR.value = jsonParser.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(EqBand.serializer()),
                        bandsRJson,
                    )
                } catch (_: Exception) { }
            }
            val measurementRJson = preferences.eqMeasurementRJson.first()
            if (!measurementRJson.isNullOrBlank()) {
                try {
                    val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    _originalMeasurementR.value = jsonParser.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(FrequencyPoint.serializer()),
                        measurementRJson,
                    )
                } catch (_: Exception) { }
            }
        }

        viewModelScope.launch {
            preferences.eqPreamp.collect { preamp ->
                _currentPreamp.value = preamp.toFloat()
            }
        }

        viewModelScope.launch {
            preferences.eqAutoPreamp.collect { _autoPreamp.value = it }
        }

        // Automatic preamp re-tracks through this single collector rather than
        // per-call-site hooks, so EVERY gain mutation path is covered — band
        // drags/sliders, preset loads, AutoEQ runs, imports, reset, restore,
        // AND the tone knobs (the shelves are a serial stage after the EQ, so
        // their boosts eat the same headroom). Toggling auto on also lands
        // here, since _autoPreamp is a source.
        viewModelScope.launch {
            combine(
                _currentBands, _currentBandsR, _stereoMode, _toneControls, _autoPreamp,
            ) { l, r, stereo, tone, auto ->
                PreampSources(l, r, stereo, tone, auto)
            }.collect { src -> if (src.auto) applyAutoPreamp(src) }
        }

        // Resolve the custom-targets list AND the selected target together so
        // the selection restores against freshly-parsed customs. The previous
        // two independent collectors raced: the target id usually emitted
        // before the customs JSON, so a saved custom target resolved to null
        // and the selection silently reverted to the default on every restart.
        viewModelScope.launch {
            combine(
                preferences.eqTargetId,
                preferences.eqCustomTargetsJson
            ) { targetId, customsJson -> targetId to customsJson }
                .collect { (targetId, customsJson) ->
                    val customs = try {
                        val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        jsonParser.decodeFromString<List<StoredCustomTarget>>(customsJson).map { st ->
                            EqTarget(
                                id = st.id,
                                label = st.label,
                                data = EqDataParser.parseRawData(st.rawData),
                                filename = ""
                            )
                        }
                    } catch (_: Exception) {
                        emptyList()
                    }
                    _customTargets.value = customs
                    _availableTargets.value = FrequencyTargets.getAllTargets() + customs
                    val target = FrequencyTargets.getTargetById(targetId)
                        ?: customs.find { it.id == targetId }
                    if (target != null) {
                        _selectedTarget.value = target
                    }
                }
        }
    }

    fun dismissTutorial() {
        _showTutorial.value = false
        viewModelScope.launch {
            preferences.setEqTutorialSeen(true)
        }
    }

    // ===== User Actions =====

    /**
     * Toggle EQ on/off
     */
    fun toggleEq() {
        val newState = !_eqEnabled.value
        viewModelScope.launch {
            preferences.setEqEnabled(newState)
            _eqEnabled.value = newState
        }
    }

    /**
     * Enable EQ
     */
    fun enableEq() {
        viewModelScope.launch {
            preferences.setEqEnabled(true)
            _eqEnabled.value = true
        }
    }

    /**
     * Disable EQ
     */
    fun disableEq() {
        viewModelScope.launch {
            preferences.setEqEnabled(false)
            _eqEnabled.value = false
        }
    }

    /**
     * Load a preset
     */
    fun loadPreset(presetId: String) {
        viewModelScope.launch {
            val preset = eqRepository.getPresetById(presetId) ?: return@launch
            if (preset.isCorrupted) {
                _error.value = "Preset \"${preset.name}\" is corrupted and can't be loaded."
                return@launch
            }
            _activePreset.value = preset
            _currentBands.value = preset.bands
            _currentPreamp.value = preset.preamp

            // A stereo preset restores its right ear and re-enables 2-channel
            // mode; a mono preset mirrors L into R so, if stereo is on, both
            // ears follow the preset instead of R keeping a stale curve.
            val presetR = preset.bandsR
            if (presetR != null) {
                _currentBandsR.value = presetR
                saveBandsRToPreferences(presetR)
                if (!_stereoMode.value) {
                    _stereoMode.value = true
                    preferences.setEqStereoMode(true)
                }
            } else if (_stereoMode.value && _currentBandsR.value.isNotEmpty()) {
                // Only while 2-channel is ON. With the switch off, the stored
                // right-ear bands are dormant by design — overwriting them here
                // would destroy the calibration the toggle promised to keep.
                _currentBandsR.value = preset.bands
                saveBandsRToPreferences(preset.bands)
            }

            // Update preferences
            preferences.setEqActivePreset(presetId)
            persistPreamp(preset.preamp.toDouble())
            preferences.setEqTarget(preset.targetId)

            // Update UI
            val target = FrequencyTargets.getTargetById(preset.targetId)
            if (target != null) {
                _selectedTarget.value = target
            }

            // Serialize and save bands
            saveBandsToPreferences(preset.bands)
        }
    }

    /**
     * Update an individual band
     */
    fun updateBand(bandId: Int, newBand: EqBand) {
        // In 2-channel mode edits land on whichever ear is selected.
        if (_stereoMode.value && _editChannel.value == EqChannel.RIGHT) {
            val updatedR = _currentBandsR.value.toMutableList()
            val indexR = updatedR.indexOfFirst { it.id == bandId }
            if (indexR >= 0) {
                updatedR[indexR] = newBand
                _currentBandsR.value = updatedR
                saveBandsRToPreferences(updatedR, coalesce = true)
            }
            return
        }
        val updatedBands = _currentBands.value.toMutableList()
        val index = updatedBands.indexOfFirst { it.id == bandId }
        if (index >= 0) {
            updatedBands[index] = newBand
            _currentBands.value = updatedBands
            saveBandsToPreferences(updatedBands, coalesce = true)
        }
    }

    /**
     * Update preamp gain. Clamped so |preamp| + peakBandGain stays within the
     * AutoEQ total-headroom budget. Otherwise the filter cascade can clip at
     * resonant peaks before the downstream limiter engages.
     */
    fun setPreamp(preamp: Float) {
        // The slider is disabled while automatic preamp owns the value; this
        // guard is belt-and-braces against a race on the toggle.
        if (_autoPreamp.value) return
        // The shared preamp guards BOTH ears' headroom — clamp against the
        // hotter one, exactly as applyAutoPreamp does.
        val peakL = _currentBands.value.maxOfOrNull { kotlin.math.abs(it.gain) } ?: 0f
        val peakR = if (_stereoMode.value) {
            _currentBandsR.value.maxOfOrNull { kotlin.math.abs(it.gain) } ?: 0f
        } else 0f
        val peakBand = maxOf(peakL, peakR)
        val headroom = (EqLimits.AUTOEQ_MAX_TOTAL_DB - peakBand).coerceAtLeast(0f)
        val clamped = preamp.coerceIn(-headroom, headroom)
        _currentPreamp.value = clamped
        // Same drag-tail treatment as the bands: the slider is continuous and
        // the audio path re-applies the whole chain off this preference.
        persistPreamp(clamped.toDouble(), coalesce = true)
    }

    /**
     * Single writer for the preamp preference, so an immediate write (reset,
     * preset load, auto-preamp) always cancels a drag's pending tail rather
     * than being overwritten by it a moment later.
     */
    private fun persistPreamp(value: Double, coalesce: Boolean = false) {
        preampPersistJob?.cancel()
        preampPersistJob = viewModelScope.launch {
            if (coalesce) kotlinx.coroutines.delay(PERSIST_DEBOUNCE_MS)
            preferences.setEqPreamp(value)
        }
    }

    /**
     * Toggle automatic preamp. While on, the preamp always sits at minus the
     * PEAK of the combined magnitude response of the EQ bands plus the tone
     * shelves, so the summed filter gain can never push the signal above
     * 0 dBFS; it re-tracks on every band or tone change via the combine
     * collector in [loadInitialState]. Turning it off simply leaves the last
     * auto value in place for the slider to take over.
     */
    fun setAutoPreamp(enabled: Boolean) {
        _autoPreamp.value = enabled
        viewModelScope.launch { preferences.setEqAutoPreamp(enabled) }
    }

    /**
     * 2-channel (per-ear) switch. Turning it off is non-destructive: the
     * right-ear bands stay in DataStore and come straight back on re-enable —
     * only the flag changes what the audio path applies.
     */
    fun setStereoMode(enabled: Boolean) {
        _stereoMode.value = enabled
        if (enabled && _currentBandsR.value.isEmpty() && _currentBands.value.isNotEmpty()) {
            // First enable: seed the right ear from the left so the sound is
            // unchanged until a right-channel measurement is picked.
            _currentBandsR.value = _currentBands.value
            saveBandsRToPreferences(_currentBands.value)
        }
        if (!enabled) _editChannel.value = EqChannel.LEFT
        viewModelScope.launch { preferences.setEqStereoMode(enabled) }
    }

    fun setEditChannel(channel: EqChannel) {
        _editChannel.value = channel
    }

    /**
     * Measurement smoothing percent (0–100, SeapEngine's scale). Preview
     * only: the graph's curves smooth immediately, but corrections are NOT
     * re-derived until the AutoEQ button is pressed — SeapEngine's Generate
     * semantics, and it keeps slider drags from thrashing the audio path.
     */
    fun setSmoothing(fraction: Float) {
        _smoothing.value = fraction
    }

    /**
     * Single choke point for the optimizer: every path (database select,
     * stereo pair, file import, sample stepping, manual re-run) funnels
     * through here so smoothing is applied uniformly.
     */
    private suspend fun runEngine(
        measurement: List<FrequencyPoint>,
        target: List<FrequencyPoint>,
        settings: FitSettings,
    ): List<EqBand> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        AutoEqEngine.runAutoEqAlgorithm(
            measurement = AutoEqEngine.smoothCurve(measurement, settings.smoothing),
            target = target,
            bandCount = settings.bandCount,
            maxFrequency = settings.maxFrequency,
            sampleRate = MODEL_SAMPLE_RATE,
            algorithm = settings.algorithm,
        )
    }

    /**
     * Step the loaded squig.link measurement to its next/previous published
     * sample (L1 → L2 → …, wrapping) and recompute that ear's correction from
     * the new sweep. Which samples exist is discovered once per
     * measurement+channel with HEAD probes and cached for the session.
     */
    fun cycleMeasurementSample(channel: EqChannel, forward: Boolean) {
        val meas = (if (channel == EqChannel.RIGHT) selectedMeasR else selectedMeasL) ?: return
        val prefix = if (channel == EqChannel.RIGHT) "R" else "L"
        launchFit {
            try {
                _isCalculating.value = true
                _error.value = null
                val key = meas.host + "|" + meas.fileName + "|" + prefix
                // Cache only non-empty lists: sampleExists maps network
                // failures to false, so an empty result usually means the
                // probe couldn't reach the server, not that no samples exist —
                // caching it would kill the stepper for the whole session.
                val samples = sampleListCache[key]
                    ?: headphoneRepository.listMeasurementSamples(meas, prefix)
                        .also { if (it.isNotEmpty()) sampleListCache[key] = it }
                if (samples.isEmpty()) {
                    _error.value = "Couldn't reach the measurement server — try again"
                    return@launchFit
                }
                if (samples.size == 1) {
                    _error.value = "Only one " + prefix + " sample is published for this measurement"
                    return@launchFit
                }
                val sampleFlow =
                    if (channel == EqChannel.RIGHT) _measurementSampleR else _measurementSampleL
                val idx = samples.indexOf(sampleFlow.value).coerceAtLeast(0)
                val next = samples[(idx + if (forward) 1 else samples.size - 1) % samples.size]

                val text = headphoneRepository.fetchMeasurementSampleText(meas, next)
                if (text == null) {
                    _error.value = "Failed to fetch sample " + next
                    return@launchFit
                }
                val parsed = EqDataParser.parseRawData(text)
                if (parsed.isEmpty()) {
                    _error.value = "Failed to parse sample " + next
                    return@launchFit
                }
                val target = _selectedTarget.value.data
                if (target.isEmpty()) {
                    _error.value = "Target curve not available"
                    return@launchFit
                }
                val bands = runEngine(parsed, target, fitSettings())
                val label = meas.fileName + " " + next + " · " + meas.source
                if (channel == EqChannel.RIGHT) {
                    _originalMeasurementR.value = parsed
                    persistMeasurementR(parsed)
                    _measurementLabelR.value = label
                    _measurementSampleR.value = next
                    _currentBandsR.value = bands
                    saveBandsRToPreferences(bands)
                } else {
                    _originalMeasurement.value = parsed
                    persistMeasurement(parsed)
                    _measurementLabelL.value = label
                    _measurementSampleL.value = next
                    _currentBands.value = bands
                    saveBandsToPreferences(bands)
                }
            } catch (e: Exception) {
                _error.value = "Failed to switch sample: " + e.message
            } finally {
                _isCalculating.value = false
            }
        }
    }

    private data class PreampSources(
        val bandsL: List<EqBand>,
        val bandsR: List<EqBand>,
        val stereo: Boolean,
        val tone: tf.monochrome.android.domain.model.ToneControls,
        val auto: Boolean,
    )

    private fun applyAutoPreamp(src: PreampSources) {
        // Fold the tone shelves in as the same EqBands the audio path runs
        // (toBands() is empty when the tone stage is switched off — a bypass).
        val toneBands = src.tone.toBands()
        // ONE shared preamp sized to the hotter ear. Per-channel preamps would
        // silently re-tilt L/R balance; the curves carry any intended
        // asymmetry, the preamp only guards headroom.
        val peakL = combinedPeakBoostDb(src.bandsL + toneBands, MODEL_SAMPLE_RATE)
        val peakR = if (src.stereo && src.bandsR.isNotEmpty()) {
            combinedPeakBoostDb(src.bandsR + toneBands, MODEL_SAMPLE_RATE)
        } else 0f
        val auto = -maxOf(peakL, peakR)
        // Epsilon gate: skips the DataStore write when nothing changed (e.g.
        // cut-only band edits) and settles the collector after a preset load
        // briefly sets the stored preamp before the recompute lands.
        if (kotlin.math.abs(auto - _currentPreamp.value) < 0.01f) return
        _currentPreamp.value = auto
        persistPreamp(auto.toDouble())
    }

    /**
     * Peak of the summed filter magnitude response in dB, evaluated on a log-
     * frequency grid plus every filter's centre frequency (a peaking filter's
     * maximum sits exactly at its centre, so no narrow peak can fall between
     * grid points). Using the real response — the same RBJ biquad math the
     * audio path runs — means overlapping boosts are compensated by what they
     * actually sum to, and boosts at far-apart frequencies aren't over-
     * compensated the way naive per-stage max-gain addition would.
     * Never negative: a cut-only setup needs no preamp.
     */
    private fun combinedPeakBoostDb(bands: List<EqBand>, sampleRate: Float): Float {
        val active = bands.filter { it.enabled }
        if (active.isEmpty()) return 0f
        val logMin = kotlin.math.log10(20f)
        val logMax = kotlin.math.log10(20_000f)
        val freqs = (0 until AUTO_PREAMP_GRID_POINTS).map { i ->
            10.0.pow(logMin + i * (logMax - logMin) / (AUTO_PREAMP_GRID_POINTS - 1.0)).toFloat()
        } + active.map { it.freq }
        var peak = 0f
        for (freq in freqs) {
            var sum = 0f
            for (band in active) {
                sum += AutoEqEngine.calculateBiquadResponse(freq, band, sampleRate)
            }
            if (sum > peak) peak = sum
        }
        return peak
    }

    private companion object {
        const val AUTO_PREAMP_GRID_POINTS = 96

        /** Drag-tail delay before a continuous edit reaches DataStore. */
        const val PERSIST_DEBOUNCE_MS = 120L

        // Canonical rate for the fit/graph response model. With matched
        // (decramped) coefficients the modeled response is rate-invariant to
        // within a few tenths of a dB — playback always configures its filters
        // at the stream's real rate and both land on the same analog
        // prototype — so the old user-facing sample-rate selector was retired.
        const val MODEL_SAMPLE_RATE = 48000f
    }

    /**
     * Select target curve
     */
    fun selectTarget(targetId: String) {
        val target = FrequencyTargets.getTargetById(targetId)
            ?: _customTargets.value.find { it.id == targetId }
            ?: return
        _selectedTarget.value = target
        viewModelScope.launch {
            preferences.setEqTarget(targetId)
        }
    }

    /**
     * Reset all bands to flat
     */
    fun resetToFlat() {
        val flatBands = _currentBands.value.map { band ->
            band.copy(gain = 0f)
        }
        _currentBands.value = flatBands
        _currentPreamp.value = 0f
        saveBandsToPreferences(flatBands)
        // Flatten the right ear too — reset means silence the whole correction,
        // not just the ear currently selected for editing.
        if (_currentBandsR.value.isNotEmpty()) {
            val flatR = _currentBandsR.value.map { it.copy(gain = 0f) }
            _currentBandsR.value = flatR
            saveBandsRToPreferences(flatR)
        }
        persistPreamp(0.0)
    }

    /**
     * Save current bands as a custom preset
     */
    fun saveAsPreset(presetName: String, description: String = "") {
        viewModelScope.launch {
            try {
                val preset = EqPreset(
                    id = "custom_preset_${System.currentTimeMillis()}",
                    name = presetName,
                    description = description,
                    bands = _currentBands.value,
                    // Only a stereo save carries R — a mono preset stays null
                    // so loading it drives both ears from the left list.
                    bandsR = if (_stereoMode.value) _currentBandsR.value else null,
                    preamp = _currentPreamp.value,
                    targetId = _selectedTarget.value.id,
                    targetName = _selectedTarget.value.label,
                    isCustom = true,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                eqRepository.savePreset(preset)
                _activePreset.value = preset
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Failed to save preset: ${e.message}"
            }
        }
    }

    /**
     * Import parsed EqualizerAPO profiles from the L/R panes: always saved as
     * ONE preset (right ear in bandsR when both panes were filled), optionally
     * applied. A both-panes import switches 2-channel mode on — distinct
     * per-ear stacks are meaningless without it. Preamp is shared, so the
     * safer (more negative) of the two wins.
     */
    fun importApoProfile(
        left: tf.monochrome.android.data.import_.ParsedEqProfile?,
        right: tf.monochrome.android.data.import_.ParsedEqProfile?,
        name: String,
        apply: Boolean = true,
    ) {
        val primary = left ?: right ?: return
        viewModelScope.launch {
            try {
                // Re-id sequentially: band ids key the UI's selection and the
                // file's own filter numbers may repeat or skip.
                val bandsL = primary.bands.mapIndexed { i, b -> b.copy(id = i) }
                val bandsR = if (left != null && right != null) {
                    right.bands.mapIndexed { i, b -> b.copy(id = i) }
                } else null
                // Never trust a file with the headroom budget: clamp exactly
                // as the manual slider does, against the hotter ear's peak. An
                // unbounded file preamp would otherwise reach the audio path
                // directly — and the import force-enables the EQ.
                val rawPreamp = minOf(primary.preamp, right?.preamp ?: primary.preamp)
                val peak = maxOf(
                    bandsL.maxOfOrNull { kotlin.math.abs(it.gain) } ?: 0f,
                    bandsR?.maxOfOrNull { kotlin.math.abs(it.gain) } ?: 0f,
                )
                val headroom = (EqLimits.AUTOEQ_MAX_TOTAL_DB - peak).coerceAtLeast(0f)
                val preamp = rawPreamp.coerceIn(-headroom, headroom)
                val preset = EqPreset(
                    id = "custom_preset_${System.currentTimeMillis()}",
                    name = name,
                    description = "Imported EqualizerAPO profile",
                    // Restart restores the active preset's target; an empty id
                    // would silently reset the user's target choice to Harman.
                    targetId = _selectedTarget.value.id,
                    targetName = _selectedTarget.value.label,
                    bands = bandsL,
                    bandsR = bandsR,
                    preamp = preamp,
                    isCustom = true,
                )
                eqRepository.savePreset(preset)
                if (apply) {
                    _currentBands.value = bandsL
                    saveBandsToPreferences(bandsL)
                    if (bandsR != null) {
                        _currentBandsR.value = bandsR
                        saveBandsRToPreferences(bandsR)
                        if (!_stereoMode.value) {
                            _stereoMode.value = true
                            preferences.setEqStereoMode(true)
                        }
                    } else if (_stereoMode.value && _currentBandsR.value.isNotEmpty()) {
                        // Mono import while stereo is on: both ears follow it,
                        // same rule as loading a mono preset.
                        _currentBandsR.value = bandsL
                        saveBandsRToPreferences(bandsL)
                    }
                    _activePreset.value = preset
                    preferences.setEqActivePreset(preset.id)
                    // Auto-preamp recomputes from the bands on its own; only a
                    // manual preamp adopts the file's value.
                    if (!_autoPreamp.value) {
                        _currentPreamp.value = preamp
                        persistPreamp(preamp.toDouble())
                    }
                    // "Upload" means HEAR it — enabling beats silently
                    // importing into a bypassed EQ.
                    if (!_eqEnabled.value) enableEq()
                }
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Failed to import profile: " + e.message
            }
        }
    }

    /**
     * Delete a custom preset
     */
    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            try {
                eqRepository.deletePreset(presetId)
                if (_activePreset.value?.id == presetId) {
                    _activePreset.value = null
                    _currentBands.value = emptyList()
                    _currentPreamp.value = 0f
                    preferences.setEqActivePreset(null)
                }
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Failed to delete preset: ${e.message}"
            }
        }
    }

    /**
     * Search presets by name
     */
    fun searchPresets(query: String) {
        viewModelScope.launch {
            eqRepository.searchPresets(query).collect { results ->
                _allPresets.value = results
            }
        }
    }

    /**
     * Calculate optimal EQ from headphone measurement
     *
     * @param measurementCsv Raw frequency response measurement (CSV format)
     * @param bandCount Number of EQ bands to generate (typically 10)
     */
    fun calculateAutoEq(measurementCsv: String) {
        launchFit {
            try {
                _isCalculating.value = true
                _error.value = null

                val measurement = EqDataParser.parseRawData(measurementCsv)
                if (measurement.isEmpty()) {
                    _error.value = "Failed to parse measurement data"
                    _isCalculating.value = false
                    return@launchFit
                }

                _originalMeasurement.value = measurement

                val target = _selectedTarget.value.data
                if (target.isEmpty()) {
                    _error.value = "Target curve not available"
                    _isCalculating.value = false
                    return@launchFit
                }

                val bands = runEngine(measurement, target, fitSettings())

                _currentBands.value = bands
                saveBandsToPreferences(bands)
                _error.value = null

            } catch (e: Exception) {
                _error.value = "AutoEQ calculation failed: ${e.message}"
            } finally {
                _isCalculating.value = false
            }
        }
    }

    /**
     * Load available headphones from GitHub AutoEq repository
     */
    fun loadAvailableHeadphones() {
        viewModelScope.launch {
            try {
                _headphonesLoading.value = true
                _error.value = null

                // Seed with uploaded entries immediately so the Uploaded
                // section has rows during the multi-second remote round-trip,
                // not just after it returns.
                _availableHeadphones.value = _uploadedHeadphones.value

                headphoneRepository.getAllHeadphones().collect { headphones ->
                    // Uploaded entries lead the list so they're always
                    // discoverable, even before remote sources finish loading.
                    _availableHeadphones.value = _uploadedHeadphones.value + headphones
                    _headphonesLoading.value = false
                }
            } catch (e: Exception) {
                _error.value = "Failed to load headphones: ${e.message}"
                _headphonesLoading.value = false
            }
        }
    }

    /**
     * Drop a previously-saved upload by id and update the available list.
     */
    fun removeUploadedMeasurement(headphoneId: String) {
        viewModelScope.launch {
            val updated = _uploadedHeadphones.value.filter { it.id != headphoneId }
            _uploadedHeadphones.value = updated
            _availableHeadphones.value = _availableHeadphones.value.filter { it.id != headphoneId }
            if (_selectedHeadphone.value?.id == headphoneId) {
                _selectedHeadphone.value = null
                preferences.clearEqSelectedHeadphone()
            }
            try {
                val jsonParser = kotlinx.serialization.json.Json
                val json = jsonParser.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Headphone.serializer()),
                    updated,
                )
                preferences.setEqUploadedHeadphonesJson(json)
            } catch (_: Exception) { }
        }
    }

    /**
     * Persist a user-uploaded measurement as a named Headphone with embedded
     * FR data, then merge it into _availableHeadphones so it shows up under
     * the "Uploaded" rig chip immediately.
     */
    fun addUploadedMeasurement(name: String, csv: String) {
        viewModelScope.launch {
            val trimmedName = name.trim().ifBlank { return@launch }
            val points = EqDataParser.parseRawData(csv)
            if (points.isEmpty()) return@launch

            val id = "uploaded_${trimmedName.replace(' ', '_').lowercase()}"
            val measurement = tf.monochrome.android.domain.model.AutoEqMeasurement(
                source = "User upload",
                target = "uploaded",
                path = "",
                fileName = trimmedName,
                rig = tf.monochrome.android.domain.model.MeasurementRig.UPLOADED,
                host = "",
            )
            val headphone = Headphone(
                id = id,
                name = trimmedName,
                type = "uploaded",
                data = points,
                measurements = listOf(measurement),
            )

            val updated = (_uploadedHeadphones.value.filter { it.id != id } + headphone)
                .sortedBy { it.name.lowercase() }
            _uploadedHeadphones.value = updated
            _availableHeadphones.value = updated +
                _availableHeadphones.value.filter { it.measurements.firstOrNull()?.rig != tf.monochrome.android.domain.model.MeasurementRig.UPLOADED }

            try {
                val jsonParser = kotlinx.serialization.json.Json
                val json = jsonParser.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(Headphone.serializer()),
                    updated,
                )
                preferences.setEqUploadedHeadphonesJson(json)
            } catch (_: Exception) { }
        }
    }

    /**
     * Search headphones from GitHub AutoEq repository
     */
    fun searchAvailableHeadphones(query: String) {
        _headphoneSearchQuery.value = query
        viewModelScope.launch {
            try {
                _headphonesLoading.value = true
                _error.value = null

                headphoneRepository.searchHeadphones(query).collect { headphones ->
                    _availableHeadphones.value = headphones
                    _headphonesLoading.value = false
                }
            } catch (e: Exception) {
                _error.value = "Search failed: ${e.message}"
                _headphonesLoading.value = false
            }
        }
    }

    /**
     * Select a headphone and load its measurement (legacy path: picks the
     * headphone's first available AutoEq measurement).
     */
    fun selectHeadphone(headphone: Headphone) {
        _selectedHeadphone.value = headphone
        viewModelScope.launch {
            preferences.setEqSelectedHeadphone(headphone.id, headphone.name)
        }
        loadHeadphonePreset(headphone.name)
    }

    /**
     * Select a specific measurement (squig.link or AutoEq) for a headphone.
     * Used by the rig-filtered headphone browser, where each row binds to one
     * concrete measurement rather than the whole headphone.
     */
    fun selectMeasurement(
        headphone: Headphone,
        measurement: tf.monochrome.android.domain.model.AutoEqMeasurement,
        channel: EqChannel = EqChannel.LEFT,
    ) {
        _selectedHeadphone.value = headphone
        launchFit {
            preferences.setEqSelectedHeadphone(headphone.id, headphone.name)
            loadHeadphonePresetForMeasurement(measurement, channel)
        }
    }

    private suspend fun persistMeasurement(points: List<FrequencyPoint>) {
        try {
            val json = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(FrequencyPoint.serializer()),
                points,
            )
            preferences.setEqMeasurementJson(json)
        } catch (_: Exception) { }
    }

    /**
     * Load a squig.link L/R measurement pair and compute BOTH ears' corrections
     * in one go. Returns false when neither channel file exists (caller falls
     * back to the single-file path). A missing single channel borrows the other
     * ear's curve, and the labels say which file each ear actually got.
     */
    private suspend fun loadStereoPair(
        measurement: tf.monochrome.android.domain.model.AutoEqMeasurement,
    ): Boolean {
        val lFetch = headphoneRepository.fetchMeasurementChannel(measurement, "L")
        val rFetch = headphoneRepository.fetchMeasurementChannel(measurement, "R")
        if (lFetch == null && rFetch == null) return false

        val lParsed = lFetch?.first?.let { EqDataParser.parseRawData(it) }.orEmpty()
        val rParsed = rFetch?.first?.let { EqDataParser.parseRawData(it) }.orEmpty()
        val leftCurve = lParsed.ifEmpty { rParsed }
        val rightCurve = rParsed.ifEmpty { lParsed }
        if (leftCurve.isEmpty()) return false

        val target = _selectedTarget.value.data
        if (target.isEmpty()) {
            // Handled here (error surfaced) — returning true stops the caller
            // from re-fetching just to hit the same wall.
            _error.value = "Target curve not available"
            return true
        }

        val settings = fitSettings()
        suspend fun compute(m: List<FrequencyPoint>) = runEngine(m, target, settings)

        val lSample = if (lParsed.isNotEmpty()) lFetch!!.second else rFetch!!.second
        val rSample = if (rParsed.isNotEmpty()) rFetch!!.second else lFetch!!.second

        _originalMeasurement.value = leftCurve
        persistMeasurement(leftCurve)
        _measurementLabelL.value =
            measurement.fileName + " " + lSample + " · " + measurement.source
        selectedMeasL = measurement
        _measurementSampleL.value = lSample
        val bandsL = compute(leftCurve)
        _currentBands.value = bandsL
        saveBandsToPreferences(bandsL)

        _originalMeasurementR.value = rightCurve
        persistMeasurementR(rightCurve)
        _measurementLabelR.value =
            measurement.fileName + " " + rSample + " · " + measurement.source
        selectedMeasR = measurement
        _measurementSampleR.value = rSample
        val bandsR = compute(rightCurve)
        _currentBandsR.value = bandsR
        saveBandsRToPreferences(bandsR)

        _error.value = null
        return true
    }

    private suspend fun persistMeasurementR(points: List<FrequencyPoint>) {
        try {
            val json = kotlinx.serialization.json.Json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(FrequencyPoint.serializer()),
                points,
            )
            preferences.setEqMeasurementRJson(json)
        } catch (_: Exception) { }
    }

    private suspend fun loadHeadphonePresetForMeasurement(
        measurement: tf.monochrome.android.domain.model.AutoEqMeasurement,
        channel: EqChannel = EqChannel.LEFT,
    ) {
        try {
            _isCalculating.value = true
            _error.value = null

            // 2-channel mode + squig.link: the source publishes true per-ear
            // files ("<name> L.txt"/"<name> R.txt"), so one selection from the
            // primary picker fills BOTH ears with their own corrections.
            if (channel == EqChannel.LEFT && _stereoMode.value &&
                measurement.target == "squiglink" &&
                measurement.rig != tf.monochrome.android.domain.model.MeasurementRig.UPLOADED
            ) {
                if (loadStereoPair(measurement)) {
                    _isCalculating.value = false
                    return
                }
                // Neither channel file exists — fall through to the generic path.
            }

            // Uploaded measurements carry their FR data inline on the
            // Headphone — short-circuit the remote fetch.
            var sampleUsed: String? = null
            val parsed = if (measurement.rig == tf.monochrome.android.domain.model.MeasurementRig.UPLOADED) {
                _uploadedHeadphones.value
                    .firstOrNull { it.name == measurement.fileName }
                    ?.data
                    ?: emptyList()
            } else {
                // squig.link: prefer the exact channel file for the ear being
                // loaded — the old L-then-R fallback handed the RIGHT picker
                // the left ear's data whenever an L file existed.
                val csvData = if (measurement.target == "squiglink") {
                    val want = if (channel == EqChannel.RIGHT) "R" else "L"
                    val other = if (channel == EqChannel.RIGHT) "L" else "R"
                    val hit = headphoneRepository.fetchMeasurementChannel(measurement, want)
                        ?: headphoneRepository.fetchMeasurementChannel(measurement, other)
                    sampleUsed = hit?.second
                    hit?.first
                } else {
                    headphoneRepository.fetchMeasurementText(measurement)
                }
                if (csvData == null) {
                    _error.value = "Failed to fetch measurement"
                    _isCalculating.value = false
                    return
                }
                EqDataParser.parseRawData(csvData)
            }
            if (parsed.isEmpty()) {
                _error.value = "Failed to parse headphone measurement"
                _isCalculating.value = false
                return
            }

            val label = measurement.fileName +
                (sampleUsed?.let { " " + it } ?: "") + " · " + measurement.source
            if (channel == EqChannel.RIGHT) {
                _originalMeasurementR.value = parsed
                persistMeasurementR(parsed)
                _measurementLabelR.value = label
                selectedMeasR = if (sampleUsed != null) measurement else null
                _measurementSampleR.value = sampleUsed
            } else {
                _originalMeasurement.value = parsed
                persistMeasurement(parsed)
                _measurementLabelL.value = label
                selectedMeasL = if (sampleUsed != null) measurement else null
                _measurementSampleL.value = sampleUsed
            }

            val target = _selectedTarget.value.data
            if (target.isEmpty()) {
                _error.value = "Target curve not available"
                _isCalculating.value = false
                return
            }

            val bands = runEngine(parsed, target, fitSettings())
            if (channel == EqChannel.RIGHT) {
                _currentBandsR.value = bands
                saveBandsRToPreferences(bands)
            } else {
                _currentBands.value = bands
                saveBandsToPreferences(bands)
            }
            _error.value = null
            _isCalculating.value = false
        } catch (e: Exception) {
            _error.value = "Failed to load measurement: ${e.message}"
            _isCalculating.value = false
        }
    }

    /**
     * Load preset and apply AutoEQ for a specific headphone
     *
     * Fetches measurement from GitHub AutoEq, parses it, and calculates optimal bands
     */
    fun loadHeadphonePreset(headphoneName: String) {
        viewModelScope.launch {
            try {
                _isCalculating.value = true
                _error.value = null

                val headphoneId = headphoneName.replace(" ", "_").lowercase()
                // Pass the original name through so the repo's fallback URLs
                // can hit case-sensitive GitHub paths like "AKG K371".
                val measurementResult = headphoneRepository.loadHeadphoneMeasurement(
                    headphoneId,
                    headphoneName
                )

                measurementResult.collect { result ->
                    result.onSuccess { csvData ->
                        val measurement = EqDataParser.parseRawData(csvData)
                        if (measurement.isEmpty()) {
                            _error.value = "Failed to parse headphone measurement"
                            _isCalculating.value = false
                            return@collect
                        }

                        _originalMeasurement.value = measurement
                        persistMeasurement(measurement)

                        val target = _selectedTarget.value.data
                        if (target.isEmpty()) {
                            _error.value = "Target curve not available"
                            _isCalculating.value = false
                            return@collect
                        }

                        val bands = runEngine(measurement, target, fitSettings())

                        _currentBands.value = bands
                        saveBandsToPreferences(bands)
                        _error.value = null
                        _isCalculating.value = false

                    }.onFailure { error ->
                        _error.value = "Failed to load measurement: ${error.message}"
                        _isCalculating.value = false
                    }
                }

            } catch (e: Exception) {
                _error.value = "Failed to load headphone preset: ${e.message}"
                _isCalculating.value = false
            }
        }
    }

    /**
     * Refresh headphone list from GitHub
     */
    fun refreshHeadphones() {
        viewModelScope.launch {
            headphoneRepository.refreshCache()
            loadAvailableHeadphones()
        }
    }

    /**
     * Clear any error messages
     */
    fun clearError() {
        _error.value = null
    }

    // ===== AutoEQ Parameter Actions =====

    fun setBandCount(count: Int) {
        _bandCount.value = count
    }

    fun setMaxFrequency(freq: Float) {
        _maxFrequency.value = freq
    }

    fun updateBandByDrag(bandId: Int, newFreq: Float, newGain: Float) {
        // Graph drags edit whichever ear is selected in 2-channel mode.
        val editRight = _stereoMode.value && _editChannel.value == EqChannel.RIGHT
        val updatedBands =
            (if (editRight) _currentBandsR.value else _currentBands.value).toMutableList()
        val index = updatedBands.indexOfFirst { it.id == bandId }
        if (index >= 0) {
            val cap = EqLimits.AUTOEQ_MAX_BAND_DB
            val clampedGain = newGain.coerceIn(-cap, cap)
            if (clampedGain != newGain) {
                _bandClampEvents.tryEmit(cap)
            }
            updatedBands[index] = updatedBands[index].copy(
                freq = newFreq.coerceIn(EqLimits.MIN_FREQ_HZ, EqLimits.MAX_FREQ_HZ),
                gain = clampedGain
            )
            if (editRight) {
                _currentBandsR.value = updatedBands
                saveBandsRToPreferences(updatedBands, coalesce = true)
            } else {
                _currentBands.value = updatedBands
                saveBandsToPreferences(updatedBands, coalesce = true)
            }
        }
    }

    fun runAutoEq() {
        if (_isCalculating.value) return
        launchFit {
            try {
                _isCalculating.value = true
                _error.value = null
                // Reprocess is explicit: the smoothing slider only previews,
                // and this press re-derives EVERY ear that has a measurement.
                // Measurements and settings are read INSIDE the serialized job
                // — reading them at press time raced an in-flight sample step,
                // fitting an ear from a measurement it no longer shows.
                val settings = fitSettings()
                val measL = _originalMeasurement.value
                val measR =
                    if (_stereoMode.value) _originalMeasurementR.value
                    else emptyList<FrequencyPoint>()
                if (measL.isEmpty() && measR.isEmpty()) {
                    _error.value = "No headphone measurement loaded"
                    return@launchFit
                }
                val target = _selectedTarget.value.data
                if (target.isEmpty()) {
                    _error.value = "Target curve not available"
                    return@launchFit
                }
                if (measL.isNotEmpty()) {
                    val bands = runEngine(measL, target, settings)
                    _currentBands.value = bands
                    saveBandsToPreferences(bands)
                }
                if (measR.isNotEmpty()) {
                    val bands = runEngine(measR, target, settings)
                    _currentBandsR.value = bands
                    saveBandsRToPreferences(bands)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "AutoEQ calculation failed: " + e.message
            } finally {
                _isCalculating.value = false
            }
        }
    }

    // ===== File Import =====

    /**
     * Import measurement from raw file contents (read by UI via ContentResolver)
     */
    fun importMeasurementData(rawData: String, channel: EqChannel = EqChannel.LEFT) {
        launchFit {
            try {
                _isCalculating.value = true
                _error.value = null

                val measurement = EqDataParser.parseRawData(rawData)
                if (measurement.isEmpty()) {
                    _error.value = "Failed to parse measurement file"
                    _isCalculating.value = false
                    return@launchFit
                }

                if (channel == EqChannel.RIGHT) {
                    if (!_stereoMode.value) {
                        // A right-ear import is meaningless with 2-channel off:
                        // the data would be neither audible nor visible.
                        _stereoMode.value = true
                        preferences.setEqStereoMode(true)
                    }
                    _originalMeasurementR.value = measurement
                    persistMeasurementR(measurement)
                    _measurementLabelR.value = "Imported file"
                    selectedMeasR = null
                    _measurementSampleR.value = null
                } else {
                    _originalMeasurement.value = measurement
                    persistMeasurement(measurement)
                    _measurementLabelL.value = "Imported file"
                    selectedMeasL = null
                    _measurementSampleL.value = null
                }

                val target = _selectedTarget.value.data
                if (target.isEmpty()) {
                    _error.value = "Target curve not available"
                    _isCalculating.value = false
                    return@launchFit
                }

                val bands = runEngine(measurement, target, fitSettings())

                if (channel == EqChannel.RIGHT) {
                    _currentBandsR.value = bands
                    saveBandsRToPreferences(bands)
                } else {
                    _currentBands.value = bands
                    saveBandsToPreferences(bands)
                }
            } catch (e: Exception) {
                _error.value = "Import failed: ${e.message}"
            } finally {
                _isCalculating.value = false
            }
        }
    }

    /**
     * Import a custom target curve from raw file contents
     */
    fun importCustomTarget(rawData: String, label: String) {
        viewModelScope.launch {
            try {
                val points = EqDataParser.parseRawData(rawData)
                if (points.isEmpty()) {
                    _error.value = "Failed to parse target file"
                    return@launch
                }

                val id = "custom_${System.currentTimeMillis()}"
                val newTarget = EqTarget(id = id, label = label, data = points, filename = "")

                val updatedCustoms = _customTargets.value + newTarget
                _customTargets.value = updatedCustoms
                _availableTargets.value = FrequencyTargets.getAllTargets() + updatedCustoms

                // Select the new target
                _selectedTarget.value = newTarget
                preferences.setEqTarget(id)

                // Persist
                saveCustomTargets(updatedCustoms, rawData = mapOf(id to rawData))
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Failed to import target: ${e.message}"
            }
        }
    }

    /**
     * Delete a custom target
     */
    fun deleteCustomTarget(targetId: String) {
        viewModelScope.launch {
            val updatedCustoms = _customTargets.value.filter { it.id != targetId }
            _customTargets.value = updatedCustoms
            _availableTargets.value = FrequencyTargets.getAllTargets() + updatedCustoms

            if (_selectedTarget.value.id == targetId) {
                val fallback = FrequencyTargets.getHarmanOverEar2018()
                _selectedTarget.value = fallback
                preferences.setEqTarget(fallback.id)
            }

            saveCustomTargets(updatedCustoms)
        }
    }

    private suspend fun saveCustomTargets(
        targets: List<EqTarget>,
        rawData: Map<String, String> = emptyMap()
    ) {
        try {
            val jsonParser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

            // Load existing stored data to preserve raw strings. Must await
            // the actual DataStore emission: the previous stateIn(...).value
            // read raced and returned the "[]" initial value, so every save
            // wiped the rawData of all targets not passed in `rawData` —
            // importing or deleting one custom target erased the others.
            val existingJson = preferences.eqCustomTargetsJson.first()
            val existingStored = try {
                jsonParser.decodeFromString<List<StoredCustomTarget>>(existingJson)
            } catch (_: Exception) { emptyList() }
            val existingRawMap = existingStored.associate { it.id to it.rawData }

            val stored = targets.map { t ->
                StoredCustomTarget(
                    id = t.id,
                    label = t.label,
                    rawData = rawData[t.id] ?: existingRawMap[t.id] ?: ""
                )
            }
            val json = jsonParser.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(StoredCustomTarget.serializer()),
                stored
            )
            preferences.setEqCustomTargets(json)
        } catch (e: Exception) {
            _error.value = "Failed to save custom targets: ${e.message}"
        }
    }

    // ===== Private Helpers =====

    /**
     * Persist the band list.
     *
     * [coalesce] is for the continuous edits — a slider or graph drag, which
     * fires dozens of times a second. Each save JSON-encodes every band and
     * writes DataStore, and the audio path listens to that key: without
     * coalescing, one drag rebuilt the entire biquad chain (and re-read the
     * whole preference blob everywhere else watching it) on every frame. The
     * value still lands a beat after the finger stops. Structural changes
     * (presets, AutoEQ results, add/remove, import) leave it off and write
     * straight through — they happen once and must not be lost to a cancel.
     */
    private fun saveBandsToPreferences(bands: List<EqBand>, coalesce: Boolean = false) {
        bandsPersistJob?.cancel()
        bandsPersistJob = viewModelScope.launch {
            try {
                if (coalesce) kotlinx.coroutines.delay(PERSIST_DEBOUNCE_MS)
                preferences.setEqBands(encodeBands(bands))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "Failed to save bands: ${e.message}"
            }
        }
    }

    private fun saveBandsRToPreferences(bands: List<EqBand>, coalesce: Boolean = false) {
        bandsRPersistJob?.cancel()
        bandsRPersistJob = viewModelScope.launch {
            try {
                if (coalesce) kotlinx.coroutines.delay(PERSIST_DEBOUNCE_MS)
                preferences.setEqBandsR(encodeBands(bands))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _error.value = "Failed to save right-channel bands: ${e.message}"
            }
        }
    }

    private fun encodeBands(bands: List<EqBand>): String = persistJson.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(EqBand.serializer()),
        bands,
    )
}

/** Which ear a band edit or measurement load targets in 2-channel mode. */
enum class EqChannel { LEFT, RIGHT }
