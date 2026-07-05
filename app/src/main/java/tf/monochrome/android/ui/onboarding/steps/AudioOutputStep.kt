package tf.monochrome.android.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import tf.monochrome.android.ui.onboarding.OnboardingStepScaffold
import tf.monochrome.android.ui.onboarding.OnboardingViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Optional USB DAC intro. When a DAC is attached right now the toggle
 * enables bit-perfect routing on the spot; otherwise it's a short explainer
 * and a skip.
 */
@Composable
fun AudioOutputStep(viewModel: OnboardingViewModel) {
    val usbDevice by viewModel.usbDevice.collectAsState()
    val bitPerfectEnabled by viewModel.usbBitPerfectEnabled.collectAsState()

    OnboardingStepScaffold(
        title = "Bit-perfect out",
        subtitle = "Send audio to a USB DAC untouched — no resampling, no system mixer.",
        primaryLabel = "Continue",
        onPrimary = { viewModel.next() },
        secondaryLabel = if (usbDevice == null) "Maybe later" else null,
        onSecondary = if (usbDevice == null) ({ viewModel.next() }) else null
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
                    imageVector = Icons.Default.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MonoDimens.iconMd)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = MonoDimens.spacingMd)
                ) {
                    val device = usbDevice
                    if (device != null) {
                        Text(
                            text = "Detected: ${viewModel.usbDeviceLabel(device)}",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Enable bit-perfect output?",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "No DAC connected",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Plug one in any time — Tryptify picks it up automatically, " +
                                "and bit-perfect mode lives in Settings → Audio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (usbDevice != null) {
                    Switch(
                        checked = bitPerfectEnabled,
                        onCheckedChange = { viewModel.setBitPerfect(it) }
                    )
                }
            }
        }
    }
}
