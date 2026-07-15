package tf.monochrome.android.data.donation

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The active recurring donation the user has, as remembered on this device. */
data class SavedSubscription(
    val subscriptionId: String,
    val customerId: String,
)

/**
 * Remembers the user's active donation subscription so the Support screen can show
 * a "Cancel" button across app restarts. Backed by SharedPreferences (the same
 * lightweight pattern SupabaseAuthManager already uses) and exposed as a
 * [StateFlow] so the UI reacts the moment a donation completes or is cancelled.
 *
 * This is device-local memory only — Stripe remains the source of truth. If a
 * donor cancels elsewhere (Stripe receipt / customer portal), the cancel call is
 * idempotent and this record is cleared on the next cancel tap.
 */
@Singleton
class DonationStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("tryptify_donation", Context.MODE_PRIVATE)

    private val _active = MutableStateFlow(load())
    val active: StateFlow<SavedSubscription?> = _active.asStateFlow()

    fun save(subscriptionId: String, customerId: String) {
        prefs.edit()
            .putString(KEY_SUBSCRIPTION_ID, subscriptionId)
            .putString(KEY_CUSTOMER_ID, customerId)
            .apply()
        _active.value = SavedSubscription(subscriptionId, customerId)
    }

    fun clear() {
        prefs.edit()
            .remove(KEY_SUBSCRIPTION_ID)
            .remove(KEY_CUSTOMER_ID)
            .apply()
        _active.value = null
    }

    private fun load(): SavedSubscription? {
        val sub = prefs.getString(KEY_SUBSCRIPTION_ID, null) ?: return null
        val cus = prefs.getString(KEY_CUSTOMER_ID, null) ?: return null
        return SavedSubscription(sub, cus)
    }

    private companion object {
        const val KEY_SUBSCRIPTION_ID = "subscription_id"
        const val KEY_CUSTOMER_ID = "customer_id"
    }
}
