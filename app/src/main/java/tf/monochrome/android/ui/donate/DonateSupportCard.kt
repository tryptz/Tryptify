package tf.monochrome.android.ui.donate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import com.stripe.android.paymentsheet.rememberPaymentSheet
import tf.monochrome.android.data.donation.DonationTier

/**
 * Self-contained "become a supporter" card: pick a monthly tier, tap donate, and
 * Stripe's PaymentSheet slides up in-app (card by default; Google Pay once you add
 * a GooglePayConfiguration) — the donor never leaves Tryptify. Ko-fi stays as a
 * zero-config one-time fallback via [onOpenKofi].
 *
 * All Stripe/PaymentSheet wiring lives here; the ViewModel only talks to the
 * backend. `rememberPaymentSheet` must be called unconditionally at the top of
 * composition, which is why the whole card is one composable.
 */
@Composable
fun DonateSupportCard(
    onOpenKofi: () -> Unit,
    modifier: Modifier = Modifier,
    tiers: List<DonationTier> = DonationTier.DEFAULTS,
    viewModel: DonateViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    // rememberPaymentSheet registers an activity-result launcher and so must run
    // on every composition, before any early return.
    @Suppress("DEPRECATION")
    val paymentSheet = rememberPaymentSheet { result ->
        when (result) {
            is PaymentSheetResult.Completed -> viewModel.onCompleted()
            is PaymentSheetResult.Canceled -> viewModel.onCanceled()
            is PaymentSheetResult.Failed ->
                viewModel.onFailed(result.error.message ?: "Payment failed.")
        }
    }

    var selected by remember { mutableStateOf(tiers.getOrElse(1) { tiers.first() }) }

    // When the backend has an incomplete subscription ready, present the sheet.
    LaunchedEffect(state) {
        val ready = state as? DonateUiState.ReadyToPresent ?: return@LaunchedEffect
        val sub = ready.subscription
        // Publishable key comes from the server so the app embeds no Stripe key.
        PaymentConfiguration.init(context, sub.publishableKey)
        viewModel.onPresenting()
        paymentSheet.presentWithPaymentIntent(
            paymentIntentClientSecret = sub.paymentIntentClientSecret,
            configuration = PaymentSheet.Configuration(
                merchantDisplayName = "Tryptify",
                customer = PaymentSheet.CustomerConfiguration(
                    id = sub.customerId,
                    ephemeralKeySecret = sub.ephemeralKey,
                ),
                allowsDelayedPaymentMethods = false,
            ),
        )
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (val s = state) {
            is DonateUiState.Completed -> {
                Text(
                    text = "You're a supporter now 💜",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Thank you — your monthly support keeps Tryptify going. "
                        + "You can cancel anytime from the receipt Stripe emailed you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(16.dp))
                OutlinedButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) {
                    Text("Done")
                }
            }

            else -> {
                // Tier chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                ) {
                    tiers.forEach { tier ->
                        FilterChip(
                            selected = tier == selected,
                            onClick = { selected = tier },
                            enabled = s !is DonateUiState.Loading && s !is DonateUiState.Presenting,
                            label = { Text(tier.label) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                val busy = s is DonateUiState.Loading || s is DonateUiState.Presenting
                Button(
                    onClick = { viewModel.donate(selected) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Support with ${selected.label}")
                    }
                }

                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Recurring donation · secure checkout by Stripe · cancel anytime",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                if (s is DonateUiState.Failed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onOpenKofi, modifier = Modifier.fillMaxWidth()) {
                    Text("Or tip once on Ko-fi")
                }
            }
        }
    }
}
