package tf.monochrome.android.ui.donate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.donation.CreateDonationRequest
import tf.monochrome.android.data.donation.DonationBackend
import tf.monochrome.android.data.donation.DonationSubscription
import tf.monochrome.android.data.donation.DonationTier
import javax.inject.Inject

/**
 * Drives the recurring-donation flow. The ViewModel never touches Stripe UI
 * types — it prepares the subscription on the backend and hands the resulting
 * [DonationSubscription] to the composable, which owns the PaymentSheet and maps
 * its result back in via [onCompleted] / [onCanceled] / [onFailed].
 */
@HiltViewModel
class DonateViewModel @Inject constructor(
    private val backend: DonationBackend,
    private val authManager: SupabaseAuthManager,
) : ViewModel() {

    private val _state = MutableStateFlow<DonateUiState>(DonateUiState.Idle)
    val state: StateFlow<DonateUiState> = _state.asStateFlow()

    /** Kick off a subscription for [tier]; on success the UI presents the sheet. */
    fun donate(tier: DonationTier) {
        if (_state.value is DonateUiState.Loading) return
        _state.value = DonateUiState.Loading
        viewModelScope.launch {
            val email = authManager.userProfile.value?.email
            val result = backend.createSubscription(
                CreateDonationRequest(
                    amount = tier.amount,
                    currency = tier.currency,
                    interval = tier.interval,
                    email = email,
                )
            )
            _state.value = result.fold(
                onSuccess = { DonateUiState.ReadyToPresent(it) },
                onFailure = {
                    DonateUiState.Failed(
                        it.message ?: "Something went wrong starting the donation."
                    )
                },
            )
        }
    }

    /** Called by the UI immediately after it presents the sheet, so a recomposition
     *  can't present it a second time. */
    fun onPresenting() {
        if (_state.value is DonateUiState.ReadyToPresent) {
            _state.value = DonateUiState.Presenting
        }
    }

    fun onCompleted() { _state.value = DonateUiState.Completed }

    /** Donor dismissed the sheet — quietly return to the tier picker. */
    fun onCanceled() { _state.value = DonateUiState.Idle }

    fun onFailed(message: String) { _state.value = DonateUiState.Failed(message) }

    /** Return to the initial tier-picker state (e.g. after showing a thank-you). */
    fun reset() { _state.value = DonateUiState.Idle }
}

sealed interface DonateUiState {
    data object Idle : DonateUiState
    data object Loading : DonateUiState
    data class ReadyToPresent(val subscription: DonationSubscription) : DonateUiState
    data object Presenting : DonateUiState
    data object Completed : DonateUiState
    data class Failed(val message: String) : DonateUiState
}
