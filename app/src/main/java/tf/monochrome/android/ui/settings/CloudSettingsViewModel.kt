package tf.monochrome.android.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.cloud.CloudAccount
import tf.monochrome.android.data.cloud.CloudError
import tf.monochrome.android.data.cloud.CloudPlans
import tf.monochrome.android.data.cloud.CloudRepository
import tf.monochrome.android.data.cloud.CloudUploadProgress
import tf.monochrome.android.data.cloud.CloudUploadResult
import tf.monochrome.android.data.cloud.CloudUploads
import tf.monochrome.android.data.subsonic.SubsonicRepository
import tf.monochrome.android.data.subsonic.SubsonicServer
import javax.inject.Inject

/**
 * Settings › Connections › Tryptify Cloud: the plan, the storage used, the
 * player's connection to the cloud and the uploads in progress.
 */
@HiltViewModel
class CloudSettingsViewModel @Inject constructor(
    private val cloud: CloudRepository,
    private val uploads: CloudUploads,
    subsonic: SubsonicRepository,
) : ViewModel() {

    sealed interface AccountState {
        data object SignedOut : AccountState
        data object Loading : AccountState
        data class Failed(val error: CloudError) : AccountState
        data class Ready(val account: CloudAccount) : AccountState
    }

    val signedIn: StateFlow<Boolean> = cloud.user.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, cloud.user.value != null)

    private val _account = MutableStateFlow<AccountState>(AccountState.Loading)
    val account: StateFlow<AccountState> = _account.asStateFlow()

    private val _plans = MutableStateFlow<CloudPlans?>(null)
    val plans: StateFlow<CloudPlans?> = _plans.asStateFlow()

    /** The Subsonic server this device plays from, to tell whether it is the cloud. */
    val server: StateFlow<SubsonicServer?> =
        subsonic.server.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val progress: StateFlow<CloudUploadProgress?> = uploads.progress
    val lastUpload: StateFlow<CloudUploadResult?> = uploads.last
    val pending: StateFlow<Int> = uploads.pending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** A link the screen opens once, then clears. */
    private val _openUrl = MutableStateFlow<String?>(null)
    val openUrl: StateFlow<String?> = _openUrl.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /** The last action's failure (checkout, connecting the player), shown under the buttons. */
    private val _actionError = MutableStateFlow<CloudError?>(null)
    val actionError: StateFlow<CloudError?> = _actionError.asStateFlow()

    init {
        viewModelScope.launch { cloud.plans().onSuccess { _plans.value = it } }
        refresh()
    }

    /** Re-reads the account: on resume, which is how a finished checkout shows up. */
    fun refresh() {
        if (cloud.user.value == null) {
            _account.value = AccountState.SignedOut
            return
        }
        if (_account.value !is AccountState.Ready) _account.value = AccountState.Loading
        viewModelScope.launch {
            cloud.account()
                .onSuccess { _account.value = AccountState.Ready(it) }
                .onFailure { e ->
                    _account.value = when (e) {
                        is CloudError.SignedOut -> AccountState.SignedOut
                        is CloudError -> if (_account.value is AccountState.Ready) _account.value else AccountState.Failed(e)
                        else -> AccountState.Failed(CloudError.Network(e))
                    }
                }
            if (_plans.value == null) cloud.plans().onSuccess { _plans.value = it }
        }
    }

    fun checkout(plan: String, period: String, trial: Boolean, provider: String?) = act {
        cloud.checkoutUrl(plan, period, trial, provider).onSuccess { _openUrl.value = it }
    }

    /** Opens the payment provider's page for changing or cancelling the plan. */
    fun manageSubscription() = act {
        cloud.portalUrl().onSuccess { _openUrl.value = it }
    }

    fun connectPlayer() = act {
        cloud.connectPlayer("${Build.MANUFACTURER} ${Build.MODEL}".trim())
    }

    fun urlOpened() {
        _openUrl.value = null
    }

    fun cancelUploads() = uploads.cancelAll()

    private fun act(block: suspend () -> Result<*>) {
        if (_busy.value) return
        _busy.value = true
        _actionError.value = null
        viewModelScope.launch {
            block().onFailure { e -> _actionError.value = e as? CloudError ?: CloudError.Network(e) }
            _busy.value = false
        }
    }

    fun isCloud(server: SubsonicServer?) = cloud.isCloud(server)
}
