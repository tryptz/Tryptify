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

/**
 * The "?" tutorial for the AutoEQ screen — same shape as the Oxford
 * Inflator/Compressor instruction sheets: a bottom sheet of short
 * heading + body sections, opened from a HelpOutline button in the header.
 */
private data class HelpSection(val heading: String, val body: String)

private fun autoEqHelpSections(): List<HelpSection> = listOf(
    HelpSection(
        "What this does",
        "AutoEQ measures how far your headphone's frequency response deviates " +
            "from a target curve and builds a stack of correction filters that " +
            "pull it onto the target. Pick your headphone from the database (or " +
            "import a measurement) and the filters are computed for you.",
    ),
    HelpSection(
        "Reading the graph",
        "Blue is your headphone as measured; the dashed line is the target; the " +
            "red filled curve is the corrected result — what you should hear. " +
            "The shaded region between measurement and target is the error the " +
            "correction mirrors. The axis is relative: 0 dB means \"unchanged\", " +
            "+5 means five decibels louder than neutral. Tap a label in the " +
            "legend to hide or show that curve.",
    ),
    HelpSection(
        "Band dots",
        "Each numbered dot is one filter, sitting on the corrected curve at its " +
            "centre frequency. Green boosts, red cuts, grey is near-flat. Drag a " +
            "dot to move its frequency and gain — a readout shows Hz, dB and Q " +
            "while you drag. The numbers match the band list further down, where " +
            "each band also has PEAK / LOW-S / HIGH-S type buttons.",
    ),
    HelpSection(
        "2-channel (per-ear) calibration",
        "Real units rarely measure identically left and right. Switch on " +
            "2-channel calibration to give each ear its own measurement and its " +
            "own correction. squig.link sources publish true L/R files, so " +
            "selecting one measurement fills both ears in a single tap; the L|R " +
            "chips above the graph choose which ear you're editing, and the " +
            "other ear stays visible as a thin amber curve. Switching the toggle " +
            "off is non-destructive — the right ear comes back when you " +
            "re-enable it.",
    ),
    HelpSection(
        "Importing profiles",
        "\"Import EQ profile\" accepts EqualizerAPO / AutoEq ParametricEQ.txt " +
            "files or pasted text — including profiles copied from chats or web " +
            "pages with mangled line breaks. You get a preview of the curve, the " +
            "filter count and any warnings before anything is saved or applied. " +
            "In 2-channel mode a profile can target both ears or just one.",
    ),
    HelpSection(
        "Preamp and headroom",
        "Boosting frequencies eats headroom: without compensation the loudest " +
            "peaks would clip. The preamp under the graph pulls overall level " +
            "down to make room. Leave \"Automatic preamp\" on and it tracks the " +
            "real summed peak of your filters (including the tone shelves) so " +
            "clipping can't happen; switch it off to set the trade-off yourself.",
    ),
    HelpSection(
        "Targets and presets",
        "The target menu switches the goal curve (Harman is the safe default; " +
            "custom targets can be imported). Save the current state as a preset " +
            "to switch between headphones quickly — presets saved in 2-channel " +
            "mode keep both ears. Export writes an EqualizerAPO-compatible " +
            "ParametricEQ.txt you can use anywhere.",
    ),
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
                text = "Precision AutoEQ",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Measurement-driven headphone correction — what the " +
                    "curves mean and how to drive it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            )
            Spacer(Modifier.height(18.dp))
            val sections = autoEqHelpSections()
            sections.forEachIndexed { i, section ->
                Text(
                    text = section.heading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = section.body,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (i != sections.lastIndex) Spacer(Modifier.height(14.dp))
            }
        }
    }
}
