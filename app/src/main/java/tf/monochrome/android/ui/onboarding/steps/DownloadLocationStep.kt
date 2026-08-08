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
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.ui.platform.LocalContext
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Choose where downloaded tracks land. Default is app-scoped storage
 * (no permissions needed, removed on uninstall); a custom SAF folder takes
 * a persisted read+write grant, matching the Settings → Downloads picker.
 */
@Composable
fun DownloadLocationStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val downloadUri by viewModel.downloadFolderUri.collectAsState()

    val downloadFolderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadFolder(uri.toString())
        }
    }

    OnboardingStepScaffold(
        title = "Downloads",
        subtitle = "Qobuz downloads and saved tracks land here.",
        primaryLabel = "Continue",
        onPrimary = { viewModel.next() }
    ) {
        LocationOption(
            selected = downloadUri == null,
            title = "App storage (default)",
            description = "Private to Tryptify; removed if you uninstall the app.",
            onClick = { viewModel.setDownloadFolder(null) }
        )
        LocationOption(
            selected = downloadUri != null,
            title = "Custom folder",
            description = downloadUri?.let { friendlyTreeUriLabel(it) }
                ?: "Pick any folder — downloads stay after uninstall and are visible to other apps.",
            onClick = { downloadFolderPicker.launch(null) }
        )
    }
}

@Composable
private fun LocationOption(
    selected: Boolean,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MonoDimens.cardAlpha)
            }
        ),
        shape = MonoDimens.shapeMd,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MonoDimens.spacingSm)
            .bounceClick(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(MonoDimens.spacingLg)
        ) {
            Icon(
                imageVector = if (selected) Icons.Default.RadioButtonChecked
                else Icons.Default.RadioButtonUnchecked,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MonoDimens.iconSm)
            )
            Column(modifier = Modifier.padding(start = MonoDimens.spacingMd)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "content://…/tree/primary%3AMusic" → "Music" for display. */
private fun friendlyTreeUriLabel(uriString: String): String = runCatching {
    val decoded = Uri.decode(uriString)
    decoded.substringAfterLast(':').substringAfterLast('/').ifBlank { decoded }
}.getOrDefault(uriString)
