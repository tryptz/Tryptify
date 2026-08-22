package tf.monochrome.android.ui.settings.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.radio.RadioPlannerWeights
import javax.inject.Inject

@HiltViewModel
class RadioSettingsViewModel @Inject constructor(
    private val preferences: PreferencesManager,
) : ViewModel() {

    // An in-memory working copy drives the weight sliders, so a drag updates
    // instantly with no I/O. Persisting each frame wrote all eleven float
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

    private companion object {
        /** Drag-tail delay before weight edits reach DataStore. */
        const val WEIGHTS_PERSIST_DEBOUNCE_MS = 150L
    }
}
