package tf.monochrome.android.ui.eq

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.ParametricEqRepository
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.EqPreset
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.player.QueueManager
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class ParametricEqViewModel @Inject constructor(
    private val repository: ParametricEqRepository,
    private val preferences: PreferencesManager,
    val spectrumAnalyzer: SpectrumAnalyzerTap,
    queueManager: QueueManager
) : ViewModel() {

    val currentCoverUrl: StateFlow<String?> = queueManager.currentTrack
        .map { it?.coverUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val json = Json { ignoreUnknownKeys = true }

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _currentBands = MutableStateFlow<List<EqBand>>(defaultBands())
    val currentBands: StateFlow<List<EqBand>> = _currentBands.asStateFlow()

    private val _currentPreamp = MutableStateFlow(0f)
    val currentPreamp: StateFlow<Float> = _currentPreamp.asStateFlow()

    private val _activePreset = MutableStateFlow<EqPreset?>(null)
    val activePreset: StateFlow<EqPreset?> = _activePreset.asStateFlow()

    private val _allPresets = MutableStateFlow<List<EqPreset>>(emptyList())
    val allPresets: StateFlow<List<EqPreset>> = _allPresets.asStateFlow()

    private val _selectedBandId = MutableStateFlow(-1)
    val selectedBandId: StateFlow<Int> = _selectedBandId.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _fftSize = MutableStateFlow(SpectrumAnalyzerTap.FFT_SIZE_LOW)
    val fftSize: StateFlow<Int> = _fftSize.asStateFlow()

    // --- Spectrum preferences exposed for the preview overlay ---
    val spectrumAnalyzerEnabled: StateFlow<Boolean> = preferences.spectrumAnalyzerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val spectrumColorMode: StateFlow<String> = preferences.spectrumColorMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "DYNAMIC")

    init {
        viewModelScope.launch {
            preferences.paramEqEnabled.collect { _enabled.value = it }
        }
        viewModelScope.launch {
            preferences.paramEqPreamp.collect { _currentPreamp.value = it.toFloat() }
        }
        viewModelScope.launch {
            repository.getAllPresets().collect { _allPresets.value = it }
        }
        viewModelScope.launch {
            // Restore bands from persistent storage
            val bandsJson = preferences.paramEqBandsJson.stateIn(
                viewModelScope, SharingStarted.Eagerly, null
            ).value
            if (!bandsJson.isNullOrBlank()) {
                try {
                    val bands = json.decodeFromString<List<EqBand>>(bandsJson)
                    if (bands.isNotEmpty()) _currentBands.value = bands
                } catch (_: Exception) { }
            }
            val activeId = preferences.paramEqActivePresetId.stateIn(
                viewModelScope, SharingStarted.Eagerly, null
            ).value
            if (activeId != null) {
                _activePreset.value = repository.getPresetById(activeId)
            }
            if (_currentBands.value.isNotEmpty() && _selectedBandId.value < 0) {
                _selectedBandId.value = _currentBands.value.first().id
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        viewModelScope.launch { preferences.setParamEqEnabled(enabled) }
    }

    fun toggle() = setEnabled(!_enabled.value)

    fun selectBand(id: Int) {
        _selectedBandId.value = id
    }

    fun updateBand(band: EqBand) {
        val updated = _currentBands.value.map { if (it.id == band.id) band else it }
        _currentBands.value = updated
        saveBands(updated)
    }

    fun updateBandByDrag(bandId: Int, newFreq: Float, newGain: Float) {
        val updated = _currentBands.value.map { b ->
            if (b.id == bandId) b.copy(
                freq = newFreq.coerceIn(EqLimits.MIN_FREQ_HZ, EqLimits.MAX_FREQ_HZ),
                gain = newGain.coerceIn(
                    -EqLimits.PARAMETRIC_MAX_BAND_DB,
                    EqLimits.PARAMETRIC_MAX_BAND_DB
                )
            ) else b
        }
        _currentBands.value = updated
        saveBands(updated)
    }

    fun setPreamp(preamp: Float) {
        _currentPreamp.value = preamp
        viewModelScope.launch { preferences.setParamEqPreamp(preamp.toDouble()) }
    }

    fun addBand() {
        val bands = _currentBands.value
        // Place new band at midpoint of widest gap on the log-frequency axis
        val sorted = bands.sortedBy { it.freq }
        var newFreq = 1000f
        if (sorted.size >= 2) {
            var bestGap = 0f
            var bestMid = 1000f
            for (i in 0 until sorted.size - 1) {
                val a = Math.log10(sorted[i].freq.toDouble())
                val b = Math.log10(sorted[i + 1].freq.toDouble())
                val gap = (b - a).toFloat()
                if (gap > bestGap) {
                    bestGap = gap
                    bestMid = Math.pow(10.0, (a + b) / 2).toFloat()
                }
            }
            newFreq = bestMid.coerceIn(20f, 20000f)
        } else if (sorted.size == 1) {
            newFreq = (sorted[0].freq * 2f).coerceIn(20f, 20000f)
        }
        val newId = (bands.maxOfOrNull { it.id } ?: -1) + 1
        val newBand = EqBand(
            id = newId,
            type = FilterType.PEAKING,
            freq = newFreq,
            gain = 0f,
            q = 1.0f,
            enabled = true
        )
        val updated = bands + newBand
        _currentBands.value = updated
        _selectedBandId.value = newId
        saveBands(updated)
    }

    fun removeBand(bandId: Int) {
        val updated = _currentBands.value.filterNot { it.id == bandId }
        _currentBands.value = updated
        if (_selectedBandId.value == bandId) {
            _selectedBandId.value = updated.firstOrNull()?.id ?: -1
        }
        saveBands(updated)
    }

    fun resetToFlat() {
        val flat = _currentBands.value.map { it.copy(gain = 0f) }
        _currentBands.value = flat
        _currentPreamp.value = 0f
        saveBands(flat)
        viewModelScope.launch { preferences.setParamEqPreamp(0.0) }
    }

    fun loadPreset(presetId: String) {
        viewModelScope.launch {
            val preset = repository.getPresetById(presetId) ?: return@launch
            if (preset.isCorrupted) {
                _error.value = "Preset \"${preset.name}\" is corrupted and can't be loaded."
                return@launch
            }
            _activePreset.value = preset
            _currentBands.value = preset.bands
            _currentPreamp.value = preset.preamp
            _selectedBandId.value = preset.bands.firstOrNull()?.id ?: -1
            preferences.setParamEqActivePreset(presetId)
            preferences.setParamEqPreamp(preset.preamp.toDouble())
            saveBands(preset.bands)
        }
    }

    fun saveAsPreset(name: String, description: String = "") {
        viewModelScope.launch {
            try {
                val preset = EqPreset(
                    id = "custom_paramEq_${System.currentTimeMillis()}",
                    name = name,
                    description = description,
                    bands = _currentBands.value,
                    preamp = _currentPreamp.value,
                    targetId = "",
                    targetName = "",
                    isCustom = true,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
                repository.savePreset(preset)
                _activePreset.value = preset
                preferences.setParamEqActivePreset(preset.id)
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Failed to save preset: ${e.message}"
            }
        }
    }

    fun deletePreset(presetId: String) {
        viewModelScope.launch {
            try {
                repository.deletePreset(presetId)
                if (_activePreset.value?.id == presetId) {
                    _activePreset.value = null
                    preferences.setParamEqActivePreset(null)
                }
            } catch (e: Exception) {
                _error.value = "Failed to delete preset: ${e.message}"
            }
        }
    }

    fun setFftSize(size: Int) {
        val clamped = when {
            size <= SpectrumAnalyzerTap.FFT_SIZE_4K -> SpectrumAnalyzerTap.FFT_SIZE_4K
            size <= SpectrumAnalyzerTap.FFT_SIZE_8K -> SpectrumAnalyzerTap.FFT_SIZE_8K
            else -> SpectrumAnalyzerTap.FFT_SIZE_16K
        }
        _fftSize.value = clamped
        spectrumAnalyzer.fftSize = clamped
    }

    fun clearError() {
        _error.value = null
    }

    private fun saveBands(bands: List<EqBand>) {
        viewModelScope.launch {
            try {
                val bandsJson = json.encodeToString(bands)
                preferences.setParamEqBands(bandsJson)
            } catch (e: Exception) {
                _error.value = "Failed to save bands: ${e.message}"
            }
        }
    }

    private fun defaultBands(): List<EqBand> = listOf(
        EqBand(id = 0, type = FilterType.PEAKING, freq = 60f, gain = 0f, q = 1.0f),
        EqBand(id = 1, type = FilterType.PEAKING, freq = 250f, gain = 0f, q = 1.0f),
        EqBand(id = 2, type = FilterType.PEAKING, freq = 1000f, gain = 0f, q = 1.0f),
        EqBand(id = 3, type = FilterType.PEAKING, freq = 4000f, gain = 0f, q = 1.0f),
        EqBand(id = 4, type = FilterType.PEAKING, freq = 12000f, gain = 0f, q = 1.0f)
    )
}
