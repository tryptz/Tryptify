package tf.monochrome.android.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Optional streaming hookups. Spotify runs the existing PKCE flow in a
 * Custom Tab — the singleTop activity survives the round-trip, so
 * `isConnected` flips live when the callback lands. Qobuz is a self-hosted
 * instance URL, so the card just points at Settings → Instances.
 */
@Composable
fun StreamingStep(
    viewModel: OnboardingViewModel,
    onQobuzSetup: () -> Unit,
) {
    val context = LocalContext.current
    val spotifyConnected by viewModel.spotifyConnected.collectAsState()
    val spotifyConnecting by viewModel.spotifyConnecting.collectAsState()
    val spotifyUserName by viewModel.spotifyUserName.collectAsState()
    val spotifyError by viewModel.spotifyError.collectAsState()

    OnboardingStepScaffold(
        title = "Streaming",
        subtitle = "Optional — connect services now or any time in Settings.",
        primaryLabel = "Continue",
        onPrimary = { viewModel.next() },
        secondaryLabel = "Skip for now",
        onSecondary = { viewModel.next() }
    ) {
        ServiceCard(
            icon = Icons.Default.Radio,
            title = "Spotify",
            description = when {
                spotifyConnected -> "Connected" +
                    (spotifyUserName?.let { " as $it" } ?: "") +
                    " — radio & recommendations, resolved to tracks you own."
                spotifyConnecting -> "Waiting for Spotify…"
                else -> "Radio & recommendations, resolved to your local and Qobuz tracks."
            },
            connected = spotifyConnected,
            buttonLabel = if (spotifyConnected) null else "Connect Spotify",
            buttonEnabled = !spotifyConnecting,
            onButtonClick = { viewModel.connectSpotify(context) },
            errorText = spotifyError
        )
        ServiceCard(
            icon = Icons.Default.GraphicEq,
            title = "Qobuz HiFi",
            description = "Lossless & hi-res streaming through your TrypT HiFi instance. " +
                "Setup happens in Settings → Instances — this finishes onboarding and takes you there.",
            connected = false,
            buttonLabel = "Set up in Settings",
            buttonEnabled = true,
            onButtonClick = onQobuzSetup,
            errorText = null
        )
    }
}

@Composable
private fun ServiceCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    connected: Boolean,
    buttonLabel: String?,
    buttonEnabled: Boolean,
    onButtonClick: () -> Unit,
    errorText: String?,
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
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MonoDimens.iconMd)
                )
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
                        contentDescription = "Connected",
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
