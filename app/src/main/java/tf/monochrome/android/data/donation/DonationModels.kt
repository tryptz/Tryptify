package tf.monochrome.android.data.donation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request sent to the `create-donation-payment` Supabase Edge Function.
 *
 * The amount is always in the currency's minor unit (cents) so no floating-point
 * money ever crosses the wire. The backend is the only place a Stripe secret key
 * lives — the app just names an amount and the server builds the PaymentIntent.
 */
@Serializable
data class CreateDonationRequest(
    val amount: Int,
    val currency: String = "usd",
    val email: String? = null,
)

/**
 * Everything the Stripe [com.stripe.android.paymentsheet.PaymentSheet] needs to
 * present an in-app checkout for the one-time PaymentIntent the backend just
 * created. `publishableKey` is returned by the server so the app never has to
 * embed any Stripe key of its own — the account is defined entirely server-side.
 */
@Serializable
data class DonationCheckout(
    @SerialName("paymentIntentClientSecret")
    val paymentIntentClientSecret: String,
    @SerialName("ephemeralKey")
    val ephemeralKey: String,
    @SerialName("customerId")
    val customerId: String,
    @SerialName("publishableKey")
    val publishableKey: String,
)

/** A selectable one-time donation amount shown in the UI. */
data class DonationTier(
    /** Minor units (cents). e.g. 500 == $5.00. */
    val amount: Int,
    val currency: String = "usd",
) {
    /** "$5" style label for chips and buttons. */
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
            return if (cents == 0) "$symbol$whole"
            else "$symbol$whole.${cents.toString().padStart(2, '0')}"
        }

    companion object {
        /** Default one-time tip amounts: $3, $5, $10. */
        val DEFAULTS = listOf(
            DonationTier(amount = 300),
            DonationTier(amount = 500),
            DonationTier(amount = 1000),
        )
    }
}
