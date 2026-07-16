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
import tf.monochrome.android.data.donation.DonationStore
import tf.monochrome.android.data.donation.DonationSubscription
import tf.monochrome.android.data.donation.DonationTier
import tf.monochrome.android.data.donation.SavedSubscription
import javax.inject.Inject

/**
 * Drives the recurring-donation flow. The ViewModel never touches Stripe UI
 * types — it prepares the subscription on the backend and hands the resulting
 * [DonationSubscription] to the composable, which owns the PaymentSheet and maps
 * its result back in via [onCompleted] / [onCanceled] / [onFailed].
 *
 * [active] is the persisted "is this device subscribed?" signal that drives the
 * cancel button; it survives restarts via [DonationStore].
 */
@HiltViewModel
class DonateViewModel @Inject constructor(
    private val backend: DonationBackend,
    private val authManager: SupabaseAuthManager,
    private val store: DonationStore,
) : ViewModel() {

    private val _state = MutableStateFlow<DonateUiState>(DonateUiState.Idle)
    val state: StateFlow<DonateUiState> = _state.asStateFlow()

    /** The active subscription remembered on this device, or null if not subscribed. */
    val active: StateFlow<SavedSubscription?> = store.active

    // Held between "prepared on backend" and "PaymentSheet completed" so we can
    // persist the subscription once payment actually succeeds.
    private var pending: DonationSubscription? = null

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
                onSuccess = {
                    pending = it
                    DonateUiState.ReadyToPresent(it)
                },
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
        // Payment succeeded — remember the subscription so we can offer a cancel button.
        pending?.let { sub ->
            val subscriptionId = sub.subscriptionId
            if (subscriptionId != null) store.save(subscriptionId, sub.customerId)
        }
        pending = null
        _state.value = DonateUiState.Completed
    }

    /** Donor dismissed the sheet — quietly return to the tier picker. */
    fun onCanceled() {
        pending = null
        _state.value = DonateUiState.Idle
    }

    fun onFailed(message: String) {
        pending = null
        _state.value = DonateUiState.Failed(message)
    }

    /** Cancel the active subscription (stops future charges). Idempotent on the
     *  backend, so a subscription already cancelled elsewhere still clears here. */
    fun cancelSubscription() {
        val sub = store.active.value ?: return
        if (_state.value is DonateUiState.Canceling) return
        _state.value = DonateUiState.Canceling
        viewModelScope.launch {
            val result = backend.cancelSubscription(sub.subscriptionId)
            _state.value = result.fold(
                onSuccess = {
                    store.clear()
                    DonateUiState.Canceled
                },
                onFailure = {
                    DonateUiState.Failed(it.message ?: "Couldn't cancel the donation.")
                },
            )
        }
    }

    /** Return to the initial state (e.g. after showing a thank-you / cancelled note). */
    fun reset() { _state.value = DonateUiState.Idle }
}

sealed interface DonateUiState {
    data object Idle : DonateUiState
    data object Loading : DonateUiState
    data class ReadyToPresent(val subscription: DonationSubscription) : DonateUiState
    data object Presenting : DonateUiState
    data object Completed : DonateUiState
    data object Canceling : DonateUiState
    data object Canceled : DonateUiState
    data class Failed(val message: String) : DonateUiState
}
