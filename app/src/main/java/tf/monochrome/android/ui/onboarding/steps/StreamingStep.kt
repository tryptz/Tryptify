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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import tf.monochrome.android.R
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Optional streaming hookups. The catalog sources — TIDAL, Qobuz and Apple Music
 * — all resolve through the self-hosted TrypT HiFi instance (set up in
 * Settings → Instances). Spotify is a separate radio/recommendations connector
 * that runs the PKCE flow in a Custom Tab; its singleTop activity survives the
 * round-trip, so `isConnected` flips live when the callback lands.
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
            iconRes = R.drawable.logo_tidal,
            // TIDAL's mark is monochrome — tint it so it reads in light and dark themes.
            tinted = true,
            title = "TIDAL",
            description = "Hi-res & lossless catalog through your TrypT HiFi instance — " +
                "the default source, ready once your instance is connected.",
            connected = false,
            buttonLabel = "Set up in Settings",
            buttonEnabled = true,
            onButtonClick = onQobuzSetup,
            errorText = null
        )
        ServiceCard(
            iconRes = R.drawable.logo_qobuz,
            title = "Qobuz",
            description = "Lossless & hi-res streaming through your TrypT HiFi instance. " +
                "Setup happens in Settings → Instances — this finishes onboarding and takes you there.",
            connected = false,
            buttonLabel = "Set up in Settings",
            buttonEnabled = true,
            onButtonClick = onQobuzSetup,
            errorText = null
        )
        ServiceCard(
            iconRes = R.drawable.logo_apple_music,
            title = "Apple Music",
            description = "Dolby Atmos capable — spatial audio plus lossless ALAC, served through the " +
                "same TrypT HiFi instance and rendered on-device by Tryptify's Atmos engine. " +
                "Set up in Settings → Instances, alongside Qobuz.",
            connected = false,
            buttonLabel = "Set up in Settings",
            buttonEnabled = true,
            onButtonClick = onQobuzSetup,
            errorText = null
        )
        ServiceCard(
            iconRes = R.drawable.logo_spotify,
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
