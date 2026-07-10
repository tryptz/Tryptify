package tf.monochrome.android.ui.onboarding.steps

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/** Wrap-up: quick recap of what was set, then hand off to the library. */
@Composable
fun DoneStep(
    viewModel: OnboardingViewModel,
    onStartListening: () -> Unit,
) {
    val folders by viewModel.folders.collectAsState()
    val downloadUri by viewModel.downloadFolderUri.collectAsState()
    val spotifyConnected by viewModel.spotifyConnected.collectAsState()
    val bitPerfect by viewModel.usbBitPerfectEnabled.collectAsState()

    val trackTotal = folders.mapNotNull { it.trackCount }.sum()

    OnboardingStepScaffold(
        title = "You're all set",
        subtitle = "Tryptify will scan your library in the background.",
        primaryLabel = "Start listening",
        onPrimary = onStartListening
    ) {
        SummaryRow(
            text = when {
                folders.isEmpty() -> "No library folders picked yet"
                trackTotal > 0 ->
                    "${folders.size} ${if (folders.size == 1) "folder" else "folders"} · " +
                        "${"%,d".format(trackTotal)} tracks found"
                else -> "${folders.size} ${if (folders.size == 1) "folder" else "folders"} picked"
            }
        )
        SummaryRow(
            text = if (downloadUri == null) "Downloads → app storage"
            else "Downloads → custom folder"
        )
        if (spotifyConnected) SummaryRow(text = "Spotify connected")
        if (bitPerfect) SummaryRow(text = "USB bit-perfect output enabled")
    }
}

@Composable
private fun SummaryRow(text: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                alpha = MonoDimens.cardAlpha
            )
        ),
        shape = MonoDimens.shapeMd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MonoDimens.spacingSm)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(MonoDimens.spacingLg)
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MonoDimens.iconSm)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = MonoDimens.spacingMd)
            )
        }
    }
}
