package tf.monochrome.android.ui.eq.dac

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.audio.eq.FrequencyTargets
import tf.monochrome.android.audio.usb.daceq.DacEqDevice
import tf.monochrome.android.data.repository.EqRepository
import tf.monochrome.android.data.repository.ParametricEqRepository
import tf.monochrome.android.domain.model.EqPreset
import javax.inject.Inject

/** A preset of the app's, as the "start from" list shows it. */
data class DacEqSource(val preset: EqPreset, val parametric: Boolean)

/** The page's [DacEqSession], and the app's presets to start from. */
@HiltViewModel
class DacEqViewModel @Inject constructor(
    device: DacEqDevice,
    autoEqPresets: EqRepository,
    parametricPresets: ParametricEqRepository,
) : ViewModel() {

    val session = DacEqSession(device, viewModelScope)

    /** The listener's own presets first, AutoEQ's and Parametric EQ's together. */
    val sources: StateFlow<List<DacEqSource>> =
        combine(autoEqPresets.getAllPresets(), parametricPresets.getAllPresets()) { auto, parametric ->
            (auto.map { DacEqSource(it, parametric = false) } + parametric.map { DacEqSource(it, parametric = true) })
                .filter { !it.preset.isCorrupted && it.preset.bands.isNotEmpty() }
                .sortedWith(compareBy({ !it.preset.isCustom }, { -it.preset.updatedAt }))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * A preset into the editor. One that kept its headphone's measurement
     * brings it along, so an EQ with more bands than the DAC has is fitted
     * from the measurement, as the AutoEQ screen would fit it.
     */
    fun loadPreset(source: DacEqSource) {
        val preset = source.preset
        val ear = preset.headphone?.left?.curve.orEmpty()
        val aim = preset.targetId.takeIf { it.isNotEmpty() }?.let { FrequencyTargets.getTargetById(it)?.data }.orEmpty()
        session.load(preset.name, preset.bands, ear, aim)
    }
}
