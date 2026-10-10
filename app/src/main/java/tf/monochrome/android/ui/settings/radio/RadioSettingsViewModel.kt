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
import tf.monochrome.android.radio.RadioPreset
import tf.monochrome.android.radio.RadioStyle
import javax.inject.Inject

@HiltViewModel
class RadioSettingsViewModel @Inject constructor(
    private val preferences: PreferencesManager,
) : ViewModel() {

    // An in-memory working copy drives the dials, so a drag updates instantly
    // with no I/O. Persisting each frame wrote every weight key to DataStore,
    // and the flow the dials read from is DataStore's own — so every frame of
    // a drag also re-emitted and recomposed the whole tab. Writes are
    // debounced to the tail of the gesture instead.
    private val _style = MutableStateFlow(RadioStyle.BALANCED)
    val style: StateFlow<RadioStyle> = _style.asStateFlow()

    private var userTouched = false
    private var persistJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            // Seed once; after the user starts tuning, the in-memory copy leads
            // so our own debounced writes never echo back over a live drag.
            // Weights set with the old sliders are read back as dials here.
            val stored = RadioStyle.fromWeights(preferences.radioPlannerWeights.first())
            if (!userTouched) _style.value = stored
        }
    }

    fun update(style: RadioStyle) {
        userTouched = true
        _style.value = style.clamped()
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            kotlinx.coroutines.delay(PERSIST_DEBOUNCE_MS)
            preferences.setRadioPlannerWeights(_style.value.toWeights())
        }
    }

    /** A preset's dials, keeping the switches as they are. */
    fun choose(preset: RadioPreset) = update(_style.value.withPreset(preset))

    fun resetDefaults() {
        userTouched = true
        _style.value = RadioStyle.BALANCED
        // A tap, not a drag — write it straight through, and drop any pending
        // drag tail that would otherwise land on top of the reset.
        persistJob?.cancel()
        persistJob = viewModelScope.launch { preferences.resetRadioPlannerWeights() }
    }

    private companion object {
        /** Drag-tail delay before dial edits reach DataStore. */
        const val PERSIST_DEBOUNCE_MS = 150L
    }
}
