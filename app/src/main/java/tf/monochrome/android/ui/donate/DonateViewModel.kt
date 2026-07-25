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
import tf.monochrome.android.data.donation.DonationCheckout
import tf.monochrome.android.data.donation.DonationTier
import javax.inject.Inject

/**
 * Drives the one-time donation flow. The ViewModel never touches Stripe UI
 * types — it prepares the PaymentIntent on the backend and hands the resulting
 * [DonationCheckout] to the composable, which owns the PaymentSheet and maps
 * its result back in via [onCompleted] / [onCanceled] / [onFailed].
 *
 * One-time payments leave nothing to manage afterwards: no subscription record,
 * no cancel path — Stripe emails the receipt and that's the whole lifecycle.
 */
@HiltViewModel
class DonateViewModel @Inject constructor(
    private val backend: DonationBackend,
    private val authManager: SupabaseAuthManager,
) : ViewModel() {

    private val _state = MutableStateFlow<DonateUiState>(DonateUiState.Idle)
    val state: StateFlow<DonateUiState> = _state.asStateFlow()

    /** Kick off a one-time payment for [tier]; on success the UI presents the sheet. */
    fun donate(tier: DonationTier) {
        if (_state.value is DonateUiState.Loading) return
        _state.value = DonateUiState.Loading
        viewModelScope.launch {
            val email = authManager.userProfile.value?.email
            val result = backend.createPayment(
                CreateDonationRequest(
                    amount = tier.amount,
                    currency = tier.currency,
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

    fun onCompleted() {
        _state.value = DonateUiState.Completed
    }

    /** Donor dismissed the sheet — quietly return to the amount picker. */
    fun onCanceled() {
        _state.value = DonateUiState.Idle
    }

    fun onFailed(message: String) {
        _state.value = DonateUiState.Failed(message)
    }

    /** Return to the initial state (e.g. after showing the thank-you note). */
    fun reset() { _state.value = DonateUiState.Idle }
}

sealed interface DonateUiState {
    data object Idle : DonateUiState
    data object Loading : DonateUiState
    data class ReadyToPresent(val checkout: DonationCheckout) : DonateUiState
    data object Presenting : DonateUiState
    data object Completed : DonateUiState
    data class Failed(val message: String) : DonateUiState
}
