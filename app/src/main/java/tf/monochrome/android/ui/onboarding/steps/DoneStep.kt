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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import tf.monochrome.android.R

/** Wrap-up: quick recap of what was set, then hand off to the library. */
@Composable
fun DoneStep(
    viewModel: OnboardingViewModel,
    onStartListening: () -> Unit,
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val downloadUri by viewModel.downloadFolderUri.collectAsStateWithLifecycle()
    val spotifyConnected by viewModel.spotifyConnected.collectAsStateWithLifecycle()
    val bitPerfect by viewModel.usbBitPerfectEnabled.collectAsStateWithLifecycle()

    val trackTotal = folders.mapNotNull { it.trackCount }.sum()

    OnboardingStepScaffold(
        title = stringResource(R.string.all_set),
        subtitle = stringResource(R.string.all_set_subtitle),
        primaryLabel = stringResource(R.string.start_listening),
        onPrimary = onStartListening
    ) {
        SummaryRow(
            text = when {
                folders.isEmpty() -> stringResource(R.string.no_folders_picked)
                trackTotal > 0 ->
                    stringResource(
                        R.string.separator_dot,
                        pluralStringResource(R.plurals.folders_count, folders.size, folders.size),
                        pluralStringResource(R.plurals.tracks_found, trackTotal, java.text.NumberFormat.getIntegerInstance().format(trackTotal.toLong())),
                    )
                else -> pluralStringResource(R.plurals.folders_picked, folders.size, folders.size)
            }
        )
        SummaryRow(
            text = if (downloadUri == null) stringResource(R.string.downloads_to_app_storage)
            else stringResource(R.string.downloads_to_custom_folder)
        )
        if (spotifyConnected) SummaryRow(text = stringResource(R.string.spotify_connected))
        if (bitPerfect) SummaryRow(text = stringResource(R.string.usb_bitperfect_enabled))
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
