package tf.monochrome.android.ui.settings.radio

import androidx.compose.foundation.layout.Box
import tf.monochrome.android.ui.settings.settingsAnchor
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.radio.PLANNER_WEIGHT_MAX
import tf.monochrome.android.radio.PLANNER_WEIGHT_MIN
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

/**
 * Settings › Radio: the weights radio ranks candidates with.
 *
 * All of them are scored on-device by LocalRadioPlanner. There used to be a
 * remote planner above them and three more weights below describing datasets
 * that never lived on the device; both went when the service did, so every
 * slider on this tab now moves something.
 */
@Composable
fun RadioSettingsTab(viewModel: RadioSettingsViewModel = hiltViewModel()) {
    val weights by viewModel.weights.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            // The Radio tab is the AI radio's tuning, so a search for either lands here.
            Box(
                Modifier
                    .settingsAnchor(stringResource(R.string.search_ai_radio))
                    .settingsAnchor(stringResource(R.string.search_radio_weights))
            ) {
                GroupHeader(stringResource(R.string.radio_settings_recommendation_weights))
            }
            Text(
                text = stringResource(R.string.radio_settings_neutral),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            WeightSlider(stringResource(R.string.radio_settings_local_library), stringResource(R.string.radio_settings_local_library_desc), weights.localLibrary) {
                viewModel.updateWeights(weights.copy(localLibrary = it))
            }
            WeightSlider("Qobuz", stringResource(R.string.radio_settings_qobuz_desc), weights.qobuz) {
                viewModel.updateWeights(weights.copy(qobuz = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_discovery_expansion), stringResource(R.string.radio_settings_discovery_expansion_desc), weights.spotifyDiscovery) {
                viewModel.updateWeights(weights.copy(spotifyDiscovery = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_novelty), stringResource(R.string.radio_settings_novelty_desc), weights.novelty) {
                viewModel.updateWeights(weights.copy(novelty = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_familiarity), stringResource(R.string.radio_settings_familiarity_desc), weights.familiarity) {
                viewModel.updateWeights(weights.copy(familiarity = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_artist_similarity), stringResource(R.string.radio_settings_artist_similarity_desc), weights.artistSimilarity) {
                viewModel.updateWeights(weights.copy(artistSimilarity = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_genre_similarity), stringResource(R.string.radio_settings_genre_similarity_desc), weights.genreTagSimilarity) {
                viewModel.updateWeights(weights.copy(genreTagSimilarity = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_era_consistency), stringResource(R.string.radio_settings_era_consistency_desc), weights.eraConsistency) {
                viewModel.updateWeights(weights.copy(eraConsistency = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_avoid_recent), stringResource(R.string.radio_settings_avoid_recent_desc), weights.avoidRecentlyPlayed) {
                viewModel.updateWeights(weights.copy(avoidRecentlyPlayed = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_discovery_distance), stringResource(R.string.radio_settings_discovery_distance_desc), weights.discoveryDistance) {
                viewModel.updateWeights(weights.copy(discoveryDistance = it))
            }
            WeightSlider(stringResource(R.string.radio_settings_canonical_bias), stringResource(R.string.radio_settings_canonical_bias_desc), weights.canonicalVersionBias) {
                viewModel.updateWeights(weights.copy(canonicalVersionBias = it))
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = viewModel::resetDefaults,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.radio_settings_reset_defaults))
            }
        }
    }
}

@Composable
private fun GroupHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp, top = 4.dp)
    )
}

@Composable
private fun WeightSlider(
    title: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.radio_settings_weight_value, value),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = { onValueChange(it.coerceIn(PLANNER_WEIGHT_MIN, PLANNER_WEIGHT_MAX)) },
            valueRange = PLANNER_WEIGHT_MIN..PLANNER_WEIGHT_MAX,
            steps = 11,
        )
    }
}
