package tf.monochrome.android.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.subsonic.SubsonicConnectResult
import tf.monochrome.android.data.subsonic.SubsonicRepository
import tf.monochrome.android.data.subsonic.SubsonicServer
import javax.inject.Inject

/**
 * Settings › Connections › Music server. Its own view model rather than more
 * of SettingsViewModel.
 */
@HiltViewModel
class SubsonicSettingsViewModel @Inject constructor(
    private val repository: SubsonicRepository,
    private val preferences: tf.monochrome.android.data.preferences.PreferencesManager,
) : ViewModel() {

    /** The mobile-data cap, kbps; 0 streams the original file. */
    val cellularMaxBitrate: StateFlow<Int> =
        preferences.subsonicCellularMaxBitrate.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setCellularMaxBitrate(kbps: Int) {
        viewModelScope.launch { preferences.setSubsonicCellularMaxBitrate(kbps) }
    }

    sealed interface ConnectState {
        data object Idle : ConnectState
        data object Connecting : ConnectState
        data class Failed(val result: SubsonicConnectResult.Failed) : ConnectState
    }

    val server: StateFlow<SubsonicServer?> =
        repository.server.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _connect = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connect: StateFlow<ConnectState> = _connect.asStateFlow()

    /** [onConnected] runs once the server answered and is saved, to close the dialog. */
    fun connect(address: String, username: String, password: String, onConnected: () -> Unit) {
        if (_connect.value == ConnectState.Connecting) return
        _connect.value = ConnectState.Connecting
        viewModelScope.launch {
            when (val result = repository.connect(address, username, password)) {
                is SubsonicConnectResult.Connected -> {
                    _connect.value = ConnectState.Idle
                    onConnected()
                }
                is SubsonicConnectResult.Failed -> _connect.value = ConnectState.Failed(result)
            }
        }
    }

    fun resetConnect() {
        if (_connect.value != ConnectState.Connecting) _connect.value = ConnectState.Idle
    }

    fun disconnect() {
        viewModelScope.launch { repository.disconnect() }
    }
}
