package tf.monochrome.android.ui.settings.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    val weights: StateFlow<RadioPlannerWeights> = preferences.radioPlannerWeights
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RadioPlannerWeights.DEFAULT)

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
        viewModelScope.launch { preferences.setRadioPlannerWeights(weights) }
    }

    fun resetDefaults() {
        viewModelScope.launch { preferences.resetRadioPlannerWeights() }
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

    fun testConnection() {
        _connectionStatus.value = "Testing…"
        viewModelScope.launch {
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
        }
    }
}
