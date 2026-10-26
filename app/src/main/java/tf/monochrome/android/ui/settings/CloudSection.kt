package tf.monochrome.android.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.R
import tf.monochrome.android.data.cloud.CloudAccount
import tf.monochrome.android.data.cloud.CloudError
import tf.monochrome.android.data.cloud.CloudPlan
import tf.monochrome.android.data.cloud.CloudPlans
import tf.monochrome.android.data.cloud.CloudProvider
import tf.monochrome.android.ui.components.materialSurface
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Currency

/**
 * Settings › Connections › Tryptify Cloud: the paid personal Subsonic-API
 * music storage, for a listener who would rather not run a server. Plans and
 * the trial, the storage used, connecting the player, and the uploads.
 * Unwrapped from any scroll container, like [SubsonicSection].
 *
 * Folded by default under its title, which opens it: most people on
 * Connections are there for something else, and three plan cards made the
 * page long. Folded, it does not ask the server for the account either.
 */
@Composable
internal fun CloudSection(viewModel: CloudSettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val state by viewModel.account.collectAsStateWithLifecycle()
    val plans by viewModel.plans.collectAsStateWithLifecycle()
    val openUrl by viewModel.openUrl.collectAsStateWithLifecycle()
    var open by rememberSaveable { mutableStateOf(false) }

    // Back from the payment page: the webhook has usually landed by now.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (open) viewModel.refresh() }
    LaunchedEffect(signedIn, open) { if (open) viewModel.refresh() }
    LaunchedEffect(openUrl) {
        openUrl?.let { openDonationUrl(context, it); viewModel.urlOpened() }
    }

    val title = stringResource(R.string.cloud_title)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .settingsAnchor(title)
            .clickable(role = Role.Button) { open = !open }
            .padding(top = 4.dp, bottom = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    if (!open) return
    Text(
        text = stringResource(R.string.cloud_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    when (val s = state) {
        CloudSettingsViewModel.AccountState.SignedOut -> Note(stringResource(R.string.cloud_sign_in_first))
        CloudSettingsViewModel.AccountState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        is CloudSettingsViewModel.AccountState.Failed -> {
            Note(errorText(s.error), error = true)
            TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.cloud_retry)) }
        }
        is CloudSettingsViewModel.AccountState.Ready -> Ready(viewModel, s.account, plans)
    }
}

@Composable
private fun Ready(viewModel: CloudSettingsViewModel, account: CloudAccount, plans: CloudPlans?) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val actionError by viewModel.actionError.collectAsStateWithLifecycle()

    if (account.access != "none") {
        StorageCard(account)
        Spacer(Modifier.height(8.dp))
    }
    if (account.access == "read_only") {
        Note(stringResource(R.string.cloud_read_only, dateOf(account.deleteAfter)), error = true)
        Spacer(Modifier.height(8.dp))
    }
    if (account.access == "full") {
        Player(viewModel, busy)
        Uploads(viewModel)
        // Creem requires that a subscriber can cancel from inside the product.
        // The server opens whichever provider the account pays through.
        // Unlimited storage is given, not bought: there is nothing to manage.
        if (!account.unlimited) {
            TextButton(onClick = viewModel::manageSubscription, enabled = !busy) {
                Text(stringResource(R.string.cloud_manage_subscription))
            }
        }
    } else {
        PlanPicker(plans, account.trialAvailable, busy) { plan, period, trial, provider ->
            viewModel.checkout(plan, period, trial, provider)
        }
    }
    if (busy) {
        Spacer(Modifier.height(6.dp))
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    actionError?.let { Note(errorText(it), error = true) }
}

@Composable
private fun StorageCard(account: CloudAccount) {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .materialSurface(shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (account.unlimited) {
                Text(stringResource(R.string.cloud_unlimited_title), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.cloud_unlimited_used, Formatter.formatShortFileSize(context, account.usedBytes)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val plan = planName(account.plan)
            Text(
                if (account.trial) stringResource(R.string.cloud_trial_until, dateOf(account.paidUntil))
                else stringResource(R.string.cloud_plan_until, plan, dateOf(account.paidUntil)),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(8.dp))
            val fraction = if (account.quotaBytes > 0) (account.usedBytes.toFloat() / account.quotaBytes).coerceIn(0f, 1f) else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(
                    R.string.cloud_used_of,
                    Formatter.formatShortFileSize(context, account.usedBytes),
                    Formatter.formatShortFileSize(context, account.quotaBytes),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Connects the player: the cloud becomes this device's Subsonic server. */
@Composable
private fun Player(viewModel: CloudSettingsViewModel, busy: Boolean) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    if (viewModel.isCloud(server)) {
        Note(stringResource(R.string.cloud_player_connected))
        return
    }
    OutlinedButton(onClick = viewModel::connectPlayer, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cloud_connect_player))
    }
    if (server != null) Note(stringResource(R.string.cloud_replaces_server, server!!.displayHost))
}

@Composable
private fun Uploads(viewModel: CloudSettingsViewModel) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val last by viewModel.lastUpload.collectAsStateWithLifecycle()

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.CloudUpload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.cloud_upload_howto), style = MaterialTheme.typography.bodyMedium)
    }
    val p = progress
    if (p != null || pending > 0) {
        Spacer(Modifier.height(8.dp))
        if (p != null) {
            Text(
                stringResource(R.string.cloud_uploading_song, p.title, (p.fraction * 100).toInt()),
                style = MaterialTheme.typography.bodySmall,
            )
            LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }
        val waiting = (pending - if (p != null) 1 else 0).coerceAtLeast(0)
        if (waiting > 0) {
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.cloud_uploads_waiting, waiting, waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = viewModel::cancelUploads) {
            Text(stringResource(R.string.cloud_cancel_uploads), color = MaterialTheme.colorScheme.error)
        }
    }
    last?.let { r ->
        val e = r.error
        Note(
            if (e == null) stringResource(R.string.cloud_uploaded, r.title)
            else stringResource(R.string.cloud_upload_failed, r.title, errorText(e)),
            error = e != null,
        )
    }
}

/**
 * The plans, monthly or yearly, and the trial when the account may still take
 * it. With more than one way to pay on the server, a "Pay with" row picks it,
 * the server's default first; a plan that provider has no product for keeps
 * its card, its buttons off.
 */
@Composable
private fun PlanPicker(
    plans: CloudPlans?,
    trialAvailable: Boolean,
    busy: Boolean,
    onPick: (plan: String, period: String, trial: Boolean, provider: String?) -> Unit,
) {
    if (plans == null) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        return
    }
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val providers = plans.providers
    val provider = providers.firstOrNull { it.name == chosen }
        ?: providers.firstOrNull { it.default }
        ?: providers.firstOrNull()
    if (trialAvailable) {
        Note(stringResource(R.string.cloud_trial_offer, plans.trial.days, plans.trial.storageGb))
    }
    if (providers.size > 1) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            Text(
                stringResource(R.string.cloud_pay_with),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            providers.forEach { p ->
                GlassChoiceChip(
                    label = providerName(p.name),
                    selected = p == provider,
                    accent = MaterialTheme.colorScheme.primary,
                    onClick = { chosen = p.name },
                )
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        plans.plans.forEach { plan ->
            PlanCard(plan, trialAvailable, busy, provider) { id, period, trial -> onPick(id, period, trial, provider?.name) }
        }
    }
    Note(stringResource(R.string.cloud_plan_terms, plans.readOnlyDays))
}

@Composable
private fun PlanCard(
    plan: CloudPlan,
    trialAvailable: Boolean,
    busy: Boolean,
    /** The chosen way to pay; null from a server that picks it itself. */
    provider: CloudProvider?,
    onPick: (String, String, Boolean) -> Unit,
) {
    // A server with no list sells every plan through its own provider.
    val sellsFirst = provider?.sells(plan.id, "monthly", trialAvailable) ?: true
    val sellsYearly = provider?.sells(plan.id, "yearly", false) ?: true
    Card(
        modifier = Modifier.fillMaxWidth().materialSurface(shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                // 1 TB and up read in TB (1,000 GB each, as the server counts).
                if (plan.storageGb >= 1000 && plan.storageGb % 1000 == 0) {
                    stringResource(R.string.cloud_plan_storage_tb, planName(plan.id), plan.storageGb / 1000)
                } else {
                    stringResource(R.string.cloud_plan_storage, planName(plan.id), plan.storageGb)
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(R.string.cloud_plan_prices, usd(plan.monthly), usd(plan.yearly)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (provider != null && !sellsFirst && !sellsYearly) {
                Text(
                    stringResource(R.string.cloud_not_with_provider, providerName(provider.name)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                if (trialAvailable) {
                    OutlinedButton(onClick = { onPick(plan.id, "monthly", true) }, enabled = !busy && sellsFirst) {
                        Text(stringResource(R.string.cloud_start_trial))
                    }
                } else {
                    OutlinedButton(onClick = { onPick(plan.id, "monthly", false) }, enabled = !busy && sellsFirst) {
                        Text(stringResource(R.string.cloud_monthly))
                    }
                }
                OutlinedButton(onClick = { onPick(plan.id, "yearly", false) }, enabled = !busy && sellsYearly) {
                    Text(stringResource(R.string.cloud_yearly))
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

/** Payment providers go by their brand names, untranslated. */
private fun providerName(name: String): String = when (name) {
    "paypal" -> "PayPal"
    "creem" -> "Creem"
    else -> name.replaceFirstChar { it.uppercase() }
}

@Composable
private fun planName(id: String?): String = when (id) {
    "mini" -> stringResource(R.string.cloud_plan_mini)
    "lite" -> stringResource(R.string.cloud_plan_lite)
    "plus" -> stringResource(R.string.cloud_plan_plus)
    "max" -> stringResource(R.string.cloud_plan_max)
    "pro" -> stringResource(R.string.cloud_plan_pro)
    "ultra" -> stringResource(R.string.cloud_plan_ultra)
    "xtra" -> stringResource(R.string.cloud_plan_xtra)
    else -> id.orEmpty()
}

@Composable
private fun errorText(e: CloudError): String = when (e) {
    is CloudError.SignedOut -> stringResource(R.string.cloud_sign_in_first)
    is CloudError.NoPlan -> stringResource(R.string.cloud_error_no_plan)
    is CloudError.ReadOnly -> stringResource(R.string.cloud_error_read_only)
    is CloudError.OverQuota -> stringResource(R.string.cloud_error_over_quota)
    is CloudError.NotAudio -> stringResource(R.string.cloud_error_not_audio)
    is CloudError.TooBig -> stringResource(R.string.cloud_error_too_big)
    is CloudError.TrialUsed -> stringResource(R.string.cloud_error_trial_used)
    is CloudError.Busy -> stringResource(R.string.cloud_error_busy)
    is CloudError.NotSetUp -> stringResource(R.string.cloud_error_not_set_up)
    is CloudError.Network -> stringResource(R.string.cloud_error_network)
    is CloudError.Server -> stringResource(R.string.cloud_error_other, e.code)
}

private fun usd(amount: Double): String =
    NumberFormat.getCurrencyInstance().apply { currency = Currency.getInstance("USD") }.format(amount)

private fun dateOf(iso: String?): String = iso?.let {
    runCatching {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.parse(it))
    }.getOrNull()
} ?: "?"
