package tf.monochrome.android.ui.onboarding.steps

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LibraryMusic
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
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Media permission gate. Audio access is required to continue — the next
 * step's track counts query MediaStore, which returns nothing without the
 * grant. The image permission (API 33+) rides along for sidecar cover art
 * but never blocks.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun PermissionsStep(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val hasRequested by viewModel.hasRequestedMediaPermission.collectAsState()

    // Same permission set as the Library tab (LocalLibraryTab): audio is the
    // gate, images are best-effort for sidecar covers on 33+.
    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionState = rememberMultiplePermissionsState(mediaPermissions)

    val audioGranted = permissionState.permissions.firstOrNull {
        it.permission == Manifest.permission.READ_MEDIA_AUDIO ||
            it.permission == Manifest.permission.READ_EXTERNAL_STORAGE
    }?.status?.isGranted == true

    val showRationale = permissionState.shouldShowRationale
    // Accompanist reports !shouldShowRationale both before the first request
    // and after a permanent denial; hasRequested disambiguates.
    val permanentlyDenied = hasRequested && !audioGranted && !showRationale

    OnboardingStepScaffold(
        title = "Your music, found",
        subtitle = "Tryptify needs access to your audio files to build your library.",
        primaryLabel = when {
            audioGranted -> "Continue"
            permanentlyDenied -> "Open app settings"
            else -> "Allow access"
        },
        onPrimary = {
            when {
                audioGranted -> viewModel.next()
                permanentlyDenied -> {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null)
                        )
                    )
                }
                else -> {
                    viewModel.markMediaPermissionRequested()
                    permissionState.launchMultiplePermissionRequest()
                }
            }
        }
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(
                    alpha = MonoDimens.cardAlpha
                )
            ),
            shape = MonoDimens.shapeMd,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(MonoDimens.spacingLg)
            ) {
                Icon(
                    imageVector = if (audioGranted) Icons.Default.CheckCircle
                    else Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = if (audioGranted) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MonoDimens.iconMd)
                )
                Text(
                    text = when {
                        audioGranted ->
                            "Access granted. Your files stay on your device — nothing is uploaded."
                        permanentlyDenied ->
                            "Access was denied. Enable the music & audio permission in app settings to continue."
                        showRationale ->
                            "Without this permission Tryptify can't see any of your music. It's only used to read audio files and cover art."
                        else ->
                            "Your files stay on your device — the permission is only used to read audio files and cover art."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = MonoDimens.spacingMd)
                )
            }
        }
    }
}
