package tf.monochrome.android.ui.settings.radio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.R
import tf.monochrome.android.radio.RadioPreset
import tf.monochrome.android.radio.RadioStyle
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.settings.LocalSettingsSearchInset
import tf.monochrome.android.ui.settings.settingsAnchor

/**
 * Settings › Radio: how a station started from a song sounds.
 *
 * A style to start from, three dials with words at both ends, and three
 * switches. It used to be eleven unlabelled 0–3 sliders, of which one did
 * nothing, four were two settings split in half, and one did the opposite of
 * its name; [RadioStyle] says how each old weight maps onto what is here now.
 * Every control is scored on-device by LocalRadioPlanner.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsTab(viewModel: RadioSettingsViewModel = hiltViewModel()) {
    val style by viewModel.style.collectAsStateWithLifecycle()
    val preset = style.preset()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 16.dp + LocalSettingsSearchInset.current,
            bottom = 16.dp + LocalBottomChromeInset.current +
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        ),
    ) {
        item(key = "style") {
            // The Radio tab is the AI radio's tuning, so a search for either lands here.
            Box(
                Modifier
                    .settingsAnchor(stringResource(R.string.search_ai_radio))
                    .settingsAnchor(stringResource(R.string.search_radio_weights)),
            ) {
                GroupHeader(stringResource(R.string.radio_style_title))
            }
            Text(
                text = stringResource(R.string.radio_style_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                RadioPreset.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == preset,
                        onClick = { viewModel.choose(option) },
                        shape = SegmentedButtonDefaults.itemShape(index, RadioPreset.entries.size),
                        label = {
                            Text(
                                text = stringResource(option.label()),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
            // Said rather than shown as a fourth button: "Custom" is where the
            // dials below put you, not something to pick.
            Text(
                text = if (preset == null) stringResource(R.string.radio_style_custom) else "",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }

        item(key = "dials") {
            Dial(
                title = stringResource(R.string.radio_newness_title),
                low = stringResource(R.string.radio_newness_low),
                high = stringResource(R.string.radio_newness_high),
                value = style.newness,
                range = -RadioStyle.NEWNESS_RANGE..RadioStyle.NEWNESS_RANGE,
                onChange = { viewModel.update(style.copy(newness = it)) },
            )
            Dial(
                title = stringResource(R.string.radio_reach_title),
                low = stringResource(R.string.radio_reach_low),
                high = stringResource(R.string.radio_reach_high),
                value = style.reach,
                range = 0f..1f,
                onChange = { viewModel.update(style.copy(reach = it)) },
            )
            Dial(
                title = stringResource(R.string.radio_sound_title),
                low = stringResource(R.string.radio_sound_low),
                high = stringResource(R.string.radio_sound_high),
                value = style.sound,
                range = 0f..1f,
                note = stringResource(R.string.radio_sound_note),
                onChange = { viewModel.update(style.copy(sound = it)) },
            )
        }

        item(key = "switches") {
            Spacer(Modifier.height(8.dp))
            Toggle(
                title = stringResource(R.string.radio_library_title),
                description = stringResource(R.string.radio_library_desc),
                checked = style.preferLibrary,
                onChange = { viewModel.update(style.copy(preferLibrary = it)) },
            )
            Toggle(
                title = stringResource(R.string.radio_recent_title),
                description = stringResource(R.string.radio_recent_desc),
                checked = style.avoidRecent,
                onChange = { viewModel.update(style.copy(avoidRecent = it)) },
            )
            Toggle(
                title = stringResource(R.string.radio_originals_title),
                description = stringResource(R.string.radio_originals_desc),
                checked = style.preferOriginals,
                onChange = { viewModel.update(style.copy(preferOriginals = it)) },
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(
                onClick = viewModel::resetDefaults,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.radio_settings_reset_defaults))
            }
        }
    }
}

private fun RadioPreset.label(): Int = when (this) {
    RadioPreset.FAMILIAR -> R.string.radio_style_familiar
    RadioPreset.BALANCED -> R.string.radio_style_balanced
    RadioPreset.ADVENTUROUS -> R.string.radio_style_adventurous
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp),
    )
}

/**
 * A slider with what each end means written under it, instead of a number.
 * "1.30" told nobody anything; "Songs you know … Songs you haven't heard" does.
 */
@Composable
private fun Dial(
    title: String,
    low: String,
    high: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    note: String? = null,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = "$title: $low – $high" },
        )
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = low,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = high,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** A switch row, toggled by tapping anywhere on it. */
@Composable
private fun Toggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The row is the control; the switch only shows its state.
        Switch(checked = checked, onCheckedChange = null)
    }
}
