package tf.monochrome.android.ui.onboarding.steps

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.onboarding.FolderEntry
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Required step: pick at least one library folder. Each pick takes a
 * persistable read grant and is stored immediately; the scanner restricts
 * itself to these roots.
 */
@Composable
fun FoldersStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val folders by viewModel.folders.collectAsState()
    val folderError by viewModel.folderError.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.addFolder(uri)
        }
    }

    OnboardingStepScaffold(
        title = "Where's your music?",
        subtitle = "Pick the folder(s) that hold your library. Only these are scanned.",
        primaryLabel = "Continue",
        onPrimary = { viewModel.next() },
        primaryEnabled = folders.isNotEmpty()
    ) {
        folders.forEach { entry ->
            FolderCard(
                entry = entry,
                onRemove = { viewModel.removeFolder(entry.path) }
            )
        }

        if (folderError != null) {
            Text(
                text = folderError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = MonoDimens.spacingSm)
            )
        }

        OutlinedButton(
            onClick = { folderPicker.launch(null) },
            shape = MonoDimens.shapePill,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.CreateNewFolder,
                contentDescription = null,
                modifier = Modifier.size(MonoDimens.iconSm)
            )
            Text(
                text = if (folders.isEmpty()) "Add a music folder" else "Add another folder",
                modifier = Modifier.padding(start = MonoDimens.spacingSm)
            )
        }
    }
}

@Composable
private fun FolderCard(entry: FolderEntry, onRemove: () -> Unit) {
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
            modifier = Modifier.padding(
                start = MonoDimens.spacingLg,
                end = MonoDimens.spacingXs,
                top = MonoDimens.spacingMd,
                bottom = MonoDimens.spacingMd
            )
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MonoDimens.iconMd)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MonoDimens.spacingMd)
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                when (entry.trackCount) {
                    null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            strokeWidth = 1.5.dp,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Counting tracks…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = MonoDimens.spacingXs)
                        )
                    }
                    0 -> Text(
                        text = "No tracks found here yet — pick another folder or continue anyway.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Text(
                        text = "Found ${"%,d".format(entry.trackCount)} tracks in this folder",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(onClick = onRemove) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove folder",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
