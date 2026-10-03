package tf.monochrome.android.ui.eq

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

/**
 * The "?" tutorial for the AutoEQ screen — same shape as the Oxford
 * Inflator/Compressor instruction sheets: a bottom sheet of short
 * heading + body sections, opened from a HelpOutline button in the header.
 */
private data class HelpSection(@androidx.annotation.StringRes val heading: Int, @androidx.annotation.StringRes val body: Int)

private fun autoEqHelpSections(): List<HelpSection> = listOf(
    HelpSection(R.string.eq_help_0_title, R.string.eq_help_0_body),
    HelpSection(R.string.eq_help_1_title, R.string.eq_help_1_body),
    HelpSection(R.string.eq_help_2_title, R.string.eq_help_2_body),
    HelpSection(R.string.eq_help_3_title, R.string.eq_help_3_body),
    HelpSection(R.string.eq_help_4_title, R.string.eq_help_4_body),
    HelpSection(R.string.eq_help_5_title, R.string.eq_help_5_body),
    HelpSection(R.string.eq_help_6_title, R.string.eq_help_6_body),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoEqHelpSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.eq_precision_autoeq),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.eq_measurement_driven_headphone_correction_what_the),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(18.dp))
            val sections = autoEqHelpSections()
            sections.forEachIndexed { i, section ->
                Text(
                    text = stringResource(section.heading),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(section.body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (i != sections.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }
    }
}
