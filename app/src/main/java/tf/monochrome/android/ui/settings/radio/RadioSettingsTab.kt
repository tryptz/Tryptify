package tf.monochrome.android.ui.settings.radio

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
            GroupHeader("Recommendation weights")
            Text(
                text = "1.00x is neutral. Lower values down-rank a signal, higher values strengthen it. Every one of them is scored on-device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            WeightSlider("Local library", "Prefer tracks already available on-device.", weights.localLibrary) {
                viewModel.updateWeights(weights.copy(localLibrary = it))
            }
            WeightSlider("Qobuz", "Preference for Qobuz candidates.", weights.qobuz) {
                viewModel.updateWeights(weights.copy(qobuz = it))
            }
            WeightSlider("Discovery expansion", "How much search/discovery widens the pool.", weights.spotifyDiscovery) {
                viewModel.updateWeights(weights.copy(spotifyDiscovery = it))
            }
            WeightSlider("Novelty", "Prefer new or unheard tracks.", weights.novelty) {
                viewModel.updateWeights(weights.copy(novelty = it))
            }
            WeightSlider("Familiarity", "How much familiar material repeats.", weights.familiarity) {
                viewModel.updateWeights(weights.copy(familiarity = it))
            }
            WeightSlider("Artist similarity", "Keep artist relationships close to the seed.", weights.artistSimilarity) {
                viewModel.updateWeights(weights.copy(artistSimilarity = it))
            }
            WeightSlider("Genre / tag similarity", "Genre and tag continuity.", weights.genreTagSimilarity) {
                viewModel.updateWeights(weights.copy(genreTagSimilarity = it))
            }
            WeightSlider("Era consistency", "Prefer similar release periods.", weights.eraConsistency) {
                viewModel.updateWeights(weights.copy(eraConsistency = it))
            }
            WeightSlider("Avoid recently played", "How strongly repeats are avoided.", weights.avoidRecentlyPlayed) {
                viewModel.updateWeights(weights.copy(avoidRecentlyPlayed = it))
            }
            WeightSlider("Discovery distance", "How far recommendations may drift from the seed.", weights.discoveryDistance) {
                viewModel.updateWeights(weights.copy(discoveryDistance = it))
            }
            WeightSlider("Canonical version bias", "Prefer original recordings over remasters, live takes and edits.", weights.canonicalVersionBias) {
                viewModel.updateWeights(weights.copy(canonicalVersionBias = it))
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = viewModel::resetDefaults,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Reset defaults")
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
                text = "%.2fx".format(value),
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
