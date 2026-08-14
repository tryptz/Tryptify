package tf.monochrome.android.ui.settings.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.radio.RadioPlannerClient
import tf.monochrome.android.radio.RadioPlannerWeights
import javax.inject.Inject

@HiltViewModel
class RadioSettingsViewModel @Inject constructor(
    private val preferences: PreferencesManager,
    private val plannerClient: RadioPlannerClient,
) : ViewModel() {

    // An in-memory working copy drives the weight sliders, so a drag updates
    // instantly with no I/O. Persisting each frame wrote all fourteen float
    // keys to DataStore, and the flow those sliders read from is DataStore's
    // own — so every frame of a drag also re-emitted and recomposed the whole
    // tab. Writes are debounced to the tail of the gesture instead.
    private val _weights = MutableStateFlow(RadioPlannerWeights.DEFAULT)
    val weights: StateFlow<RadioPlannerWeights> = _weights.asStateFlow()

    private var userTouched = false
    private var weightsPersistJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            // Seed once; after the user starts tuning, the in-memory copy leads
            // so our own debounced writes never echo back over a live drag.
            val stored = preferences.radioPlannerWeights.first()
            if (!userTouched) _weights.value = stored
        }
    }

    val plannerEnabled: StateFlow<Boolean> = preferences.radioPlannerEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val plannerUrl: StateFlow<String> = preferences.radioPlannerUrl
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PreferencesManager.DEFAULT_RADIO_PLANNER_URL
        )

    val plannerApiKey: StateFlow<String?> = preferences.radioPlannerApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _connectionStatus = MutableStateFlow<String?>(null)
    val connectionStatus: StateFlow<String?> = _connectionStatus.asStateFlow()

    fun updateWeights(weights: RadioPlannerWeights) {
        userTouched = true
        _weights.value = weights.clamped()
        weightsPersistJob?.cancel()
        weightsPersistJob = viewModelScope.launch {
            kotlinx.coroutines.delay(WEIGHTS_PERSIST_DEBOUNCE_MS)
            preferences.setRadioPlannerWeights(_weights.value)
        }
    }

    fun resetDefaults() {
        userTouched = true
        _weights.value = RadioPlannerWeights.DEFAULT
        // A tap, not a drag — write it straight through, and drop any pending
        // drag tail that would otherwise land on top of the reset.
        weightsPersistJob?.cancel()
        weightsPersistJob = viewModelScope.launch { preferences.resetRadioPlannerWeights() }
    }

    fun setPlannerEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setRadioPlannerEnabled(enabled) }
    }

    fun setPlannerUrl(url: String?) {
        viewModelScope.launch { preferences.setRadioPlannerUrl(url) }
    }

    fun setPlannerApiKey(key: String?) {
        viewModelScope.launch { preferences.setRadioPlannerApiKey(key) }
    }

    private var testJob: kotlinx.coroutines.Job? = null
    private val _isTesting = MutableStateFlow(false)
    val isTesting: StateFlow<Boolean> = _isTesting.asStateFlow()

    fun testConnection() {
        // Cancel any in-flight test so a stale earlier result can't overwrite
        // the current one, and expose isTesting so the button disables.
        testJob?.cancel()
        _connectionStatus.value = "Testing…"
        testJob = viewModelScope.launch {
            _isTesting.value = true
            try {
            _connectionStatus.value = plannerClient.health().fold(
                onSuccess = { health ->
                    buildString {
                        append("Connected — planner: ${health.planner ?: "unknown"}")
                        if (health.modelLoaded) append(", model loaded")
                        if (health.metabrainzEnabled) {
                            append(
                                if (health.metabrainzIndexExists) ", MetaBrainz index ready"
                                else ", MetaBrainz enabled (no index yet)"
                            )
                        }
                    }
                },
                onFailure = { "Connection failed: ${it.message}" }
            )
            } finally {
                _isTesting.value = false
            }
        }
    }

    private companion object {
        /** Drag-tail delay before weight edits reach DataStore. */
        const val WEIGHTS_PERSIST_DEBOUNCE_MS = 150L
    }
}
