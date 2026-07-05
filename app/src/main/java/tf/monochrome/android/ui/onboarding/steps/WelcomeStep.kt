package tf.monochrome.android.ui.onboarding.steps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.theme.MonoDimens

@Composable
fun WelcomeStep(
    onGetStarted: () -> Unit,
    onSkipSetup: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MonoDimens.spacingXl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(72.dp)
            )
            Text(
                text = "Tryptify",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = MonoDimens.spacingLg)
            )
            Text(
                text = "Bit-perfect, high-res playback — your library, your way.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(
                    top = MonoDimens.spacingSm,
                    start = MonoDimens.spacingLg,
                    end = MonoDimens.spacingLg
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MonoDimens.spacingLg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = onGetStarted,
                shape = MonoDimens.shapePill,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text("Get started", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(
                onClick = onSkipSetup,
                modifier = Modifier.padding(top = MonoDimens.spacingXs)
            ) {
                Text("Skip setup", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
