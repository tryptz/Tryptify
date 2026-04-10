package tf.monochrome.android.ui.carmode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.EqRepository
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.pow
import javax.inject.Inject

@HiltViewModel
class CarModeViewModel @Inject constructor(
    private val eqRepository: EqRepository,
    private val preferences: PreferencesManager
) : ViewModel() {

    private val _bandCount = MutableStateFlow(10)
    val bandCount: StateFlow<Int> = _bandCount.asStateFlow()

    private val _eqBands = MutableStateFlow<List<EqBand>>(emptyList())
    val eqBands: StateFlow<List<EqBand>> = _eqBands.asStateFlow()

    init {
        loadCarModeState()
    }

    private fun loadCarModeState() {
        viewModelScope.launch {
            preferences.carModeBandCount.collect { count ->
                _bandCount.value = count
                generateEqBands(count)
            }
        }
    }

    fun setBandCount(count: Int) {
        _bandCount.value = count.coerceIn(3, 32)
        generateEqBands(_bandCount.value)
        viewModelScope.launch {
            preferences.setCarModeBandCount(_bandCount.value)
        }
    }

    fun updateBand(index: Int, gain: Float) {
        val current = _eqBands.value.toMutableList()
        if (index in current.indices) {
            current[index] = current[index].copy(gain = gain.coerceIn(-12f, 12f))
            _eqBands.value = current
        }
    }

    private fun generateEqBands(count: Int) {
        val bands = mutableListOf<EqBand>()
        val minFreq = 20f
        val maxFreq = 20000f

        for (i in 0 until count) {
            val ratio = i.toFloat() / (count - 1)
            val freq = minFreq * (maxFreq / minFreq).pow(ratio)
            bands.add(
                EqBand(
                    id = i,
                    type = FilterType.PEAKING,
                    freq = freq,
                    gain = 0f,
                    q = 1f
                )
            )
        }

        _eqBands.value = bands
    }
}
