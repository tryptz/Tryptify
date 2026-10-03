package tf.monochrome.android.ui.onboarding.steps

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.R
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource

/**
 * Optional streaming hookups. The catalog sources — TIDAL and Qobuz — both
 * resolve through the self-hosted servers added under
 * Settings → Connections → APIs. Spotify is a separate radio/recommendations connector
 * that runs the PKCE flow in a Custom Tab; its singleTop activity survives the
 * round-trip, so `isConnected` flips live when the callback lands.
 */
@Composable
fun StreamingStep(
    viewModel: OnboardingViewModel,
    onQobuzSetup: () -> Unit,
) {
    val context = LocalContext.current
    val spotifyConnected by viewModel.spotifyConnected.collectAsStateWithLifecycle()
    val spotifyConnecting by viewModel.spotifyConnecting.collectAsStateWithLifecycle()
    val spotifyUserName by viewModel.spotifyUserName.collectAsStateWithLifecycle()
    val spotifyError by viewModel.spotifyError.collectAsStateWithLifecycle()

    OnboardingStepScaffold(
        title = stringResource(R.string.streaming),
        subtitle = stringResource(R.string.streaming_subtitle),
        primaryLabel = stringResource(R.string.action_continue),
        onPrimary = { viewModel.next() },
        secondaryLabel = stringResource(R.string.skip_for_now),
        onSecondary = { viewModel.next() }
    ) {
        ServiceCard(
            iconRes = R.drawable.logo_tidal,
            // TIDAL's mark is monochrome — tint it so it reads in light and dark themes.
            tinted = true,
            title = "TIDAL",
            description = stringResource(R.string.tidal_service_detail),
            connected = false,
            buttonLabel = stringResource(R.string.set_up_in_settings),
            buttonEnabled = true,
            onButtonClick = onQobuzSetup,
            errorText = null
        )
        ServiceCard(
            iconRes = R.drawable.logo_qobuz,
            title = "Qobuz",
            description = stringResource(R.string.qobuz_service_detail),
            connected = false,
            buttonLabel = stringResource(R.string.set_up_in_settings),
            buttonEnabled = true,
            onButtonClick = onQobuzSetup,
            errorText = null
        )
        ServiceCard(
            iconRes = R.drawable.logo_spotify,
            title = "Spotify",
            description = when {
                spotifyConnected -> spotifyUserName?.let { stringResource(R.string.spotify_connected_as, it) }
                    ?: stringResource(R.string.spotify_connected_plain)
                spotifyConnecting -> stringResource(R.string.waiting_for_spotify)
                else -> stringResource(R.string.spotify_service_detail)
            },
            connected = spotifyConnected,
            buttonLabel = if (spotifyConnected) null else stringResource(R.string.connect_spotify),
            buttonEnabled = !spotifyConnecting,
            onButtonClick = { viewModel.connectSpotify(context) },
            errorText = spotifyError
        )
    }
}

@Composable
private fun ServiceCard(
    @DrawableRes iconRes: Int,
    title: String,
    description: String,
    connected: Boolean,
    buttonLabel: String?,
    buttonEnabled: Boolean,
    onButtonClick: () -> Unit,
    errorText: String?,
    tinted: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = MonoDimens.cardAlpha
            )
        ),
        shape = MonoDimens.shapeMd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MonoDimens.spacingMd)
    ) {
        Column(modifier = Modifier.padding(MonoDimens.spacingLg)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (tinted) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(MonoDimens.iconMd)
                    )
                } else {
                    Image(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(MonoDimens.iconMd)
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = MonoDimens.spacingMd)
                )
                if (connected) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = stringResource(R.string.connected),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MonoDimens.iconSm)
                    )
                }
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MonoDimens.spacingSm)
            )
            if (errorText != null) {
                Text(
                    text = errorText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = MonoDimens.spacingXs)
                )
            }
            if (buttonLabel != null) {
                OutlinedButton(
                    onClick = onButtonClick,
                    enabled = buttonEnabled,
                    shape = MonoDimens.shapePill,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = MonoDimens.spacingMd)
                ) {
                    Text(buttonLabel)
                }
            }
        }
    }
}
