package tf.monochrome.android.data.donation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request sent to the `create-donation-subscription` Supabase Edge Function.
 *
 * The amount is always in the currency's minor unit (cents) so no floating-point
 * money ever crosses the wire. The backend is the only place a Stripe secret key
 * lives — the app just names a tier and the server builds the subscription.
 */
@Serializable
data class CreateDonationRequest(
    val amount: Int,
    val currency: String = "usd",
    val interval: String = "month",
    val email: String? = null,
)

/**
 * Everything the Stripe [com.stripe.android.paymentsheet.PaymentSheet] needs to
 * present an in-app checkout for the incomplete subscription the backend just
 * created. `publishableKey` is returned by the server so the app never has to
 * embed any Stripe key of its own — the account is defined entirely server-side.
 */
@Serializable
data class DonationSubscription(
    @SerialName("paymentIntentClientSecret")
    val paymentIntentClientSecret: String,
    @SerialName("ephemeralKey")
    val ephemeralKey: String,
    @SerialName("customerId")
    val customerId: String,
    @SerialName("publishableKey")
    val publishableKey: String,
    @SerialName("subscriptionId")
    val subscriptionId: String? = null,
)

/** Request sent to the `cancel-donation-subscription` Edge Function. */
@Serializable
data class CancelDonationRequest(
    val subscriptionId: String,
)

/** A selectable recurring donation tier shown in the UI. */
data class DonationTier(
    /** Minor units (cents). e.g. 500 == $5.00. */
    val amount: Int,
    val currency: String = "usd",
    val interval: String = "month",
) {
    /** "$5 / mo" style label for chips and buttons. */
    val label: String
        get() {
            val symbol = when (currency.lowercase()) {
                "usd", "aud", "cad" -> "$"
                "eur" -> "€"
                "gbp" -> "£"
                else -> ""
            }
            val whole = amount / 100
            val cents = amount % 100
            val money = if (cents == 0) "$symbol$whole" else "$symbol$whole.${cents.toString().padStart(2, '0')}"
            val period = when (interval) {
                "month" -> "mo"
                "year" -> "yr"
                "week" -> "wk"
                else -> interval
            }
            return "$money / $period"
        }

    companion object {
        /** Default monthly tip tiers: $3, $5, $10. */
        val DEFAULTS = listOf(
            DonationTier(amount = 300),
            DonationTier(amount = 500),
            DonationTier(amount = 1000),
        )
    }
}
