package tf.monochrome.android.ui.settings

import androidx.annotation.StringRes
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tf.monochrome.android.R

/**
 * The "?" of the Atmos renderer's page: what each part of it does and how to
 * use it, in the page's own order and in plain words. The same shape as the
 * Inflator's and AutoEQ's instruction sheets (`AutoEqHelpSheet`): a bottom
 * sheet of short heading and body sections, opened from a HelpOutline button
 * in the top bar.
 *
 * A section names a control exactly as the page labels it, in each language:
 * when a label changes, its section changes with it.
 */
internal class AtmosHelpSection(@StringRes val heading: Int, @StringRes val body: Int)

internal val ATMOS_HELP_SECTIONS: List<AtmosHelpSection> = listOf(
    AtmosHelpSection(R.string.atmos_help_0_title, R.string.atmos_help_0_body),
    AtmosHelpSection(R.string.atmos_help_1_title, R.string.atmos_help_1_body),
    AtmosHelpSection(R.string.atmos_help_2_title, R.string.atmos_help_2_body),
    AtmosHelpSection(R.string.atmos_help_3_title, R.string.atmos_help_3_body),
    AtmosHelpSection(R.string.atmos_help_4_title, R.string.atmos_help_4_body),
    AtmosHelpSection(R.string.atmos_help_5_title, R.string.atmos_help_5_body),
    AtmosHelpSection(R.string.atmos_help_6_title, R.string.atmos_help_6_body),
    AtmosHelpSection(R.string.atmos_help_7_title, R.string.atmos_help_7_body),
    AtmosHelpSection(R.string.atmos_help_8_title, R.string.atmos_help_8_body),
    AtmosHelpSection(R.string.atmos_help_9_title, R.string.atmos_help_9_body),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AtmosHelpSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.atmos_help_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.atmos_help_summary),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(18.dp))
            ATMOS_HELP_SECTIONS.forEachIndexed { i, section ->
                Text(
                    stringResource(section.heading),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(section.body), style = MaterialTheme.typography.bodyMedium)
                if (i != ATMOS_HELP_SECTIONS.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }
    }
}
