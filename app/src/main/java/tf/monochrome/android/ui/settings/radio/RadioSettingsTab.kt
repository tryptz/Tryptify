package tf.monochrome.android.ui.settings.radio

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.radio.PLANNER_WEIGHT_MAX
import tf.monochrome.android.radio.PLANNER_WEIGHT_MIN

/**
 * Settings › Radio: the recommendation weights radio ranks with, and the
 * connection to the optional Tryptify-Playlist planner.
 *
 * The weights are scored on-device by LocalRadioPlanner and work with no
 * planner configured. The three under "Needs a planner" describe datasets
 * that do not live on the device; they are only sent to the service.
 */
@Composable
fun RadioSettingsTab(viewModel: RadioSettingsViewModel = hiltViewModel()) {
    val weights by viewModel.weights.collectAsStateWithLifecycle()
    val plannerEnabled by viewModel.plannerEnabled.collectAsStateWithLifecycle()
    val plannerUrl by viewModel.plannerUrl.collectAsStateWithLifecycle()
    val plannerApiKey by viewModel.plannerApiKey.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val isTesting by viewModel.isTesting.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            GroupHeader("Radio planner")
            tf.monochrome.android.ui.settings.SettingSwitchItem(
                title = "Use remote planner",
                subtitle = "Optional. Asks the Tryptify-Playlist service for extra track suggestions. Radio and every weight below work without it. This only widens where candidates come from.",
                checked = plannerEnabled,
                onCheckedChange = viewModel::setPlannerEnabled
            )

            Spacer(Modifier.height(8.dp))

            PlannerTextField(
                title = "Planner URL",
                subtitle = "The Tryptify-Playlist deployment radio requests go to.",
                value = plannerUrl,
                placeholder = "planner URL",
                onSave = viewModel::setPlannerUrl
            )

            PlannerTextField(
                title = "API key",
                subtitle = "Bearer token for the planner. Required for planner hints.",
                value = plannerApiKey.orEmpty(),
                placeholder = "API_KEY",
                masked = true,
                onSave = viewModel::setPlannerApiKey
            )

            Spacer(Modifier.height(8.dp))

            OutlinedButton(
                onClick = viewModel::testConnection,
                enabled = !isTesting,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isTesting) "Testing…" else "Test connection")
            }
            connectionStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            GroupHeader("Recommendation weights")
            Text(
                text = "1.00x is neutral. Lower values down-rank a signal, higher values strengthen it. These are scored on-device, no planner required.",
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

            Spacer(Modifier.height(24.dp))
            GroupHeader("Needs a planner")
            Text(
                text = "These describe data that isn't on the device, so they only take effect when a remote planner is configured and reachable.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            WeightSlider("MetaBrainz metadata", "MusicBrainz identity, tags, aliases, relationships.", weights.metabrainzMetadata) {
                viewModel.updateWeights(weights.copy(metabrainzMetadata = it))
            }
            WeightSlider("ListenBrainz graph", "Co-listening patterns from ListenBrainz.", weights.listenbrainzGraph) {
                viewModel.updateWeights(weights.copy(listenbrainzGraph = it))
            }
            WeightSlider("Mood continuity", "Keeps the station's mood steady. Needs audio features the app doesn't compute.", weights.moodContinuity) {
                viewModel.updateWeights(weights.copy(moodContinuity = it))
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
private fun PlannerTextField(
    title: String,
    subtitle: String,
    value: String,
    placeholder: String,
    onSave: (String?) -> Unit,
    masked: Boolean = false,
) {
    var input by remember(value) { mutableStateOf(value) }
    val latestInput = rememberUpdatedState(input)
    val latestSaved = rememberUpdatedState(value)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            singleLine = true,
            visualTransformation =
                if (masked) PasswordVisualTransformation()
                else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                onSave(latestInput.value.trim().ifBlank { null })
            }),
            modifier = Modifier
                .widthIn(max = 240.dp)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) {
                        val trimmed = latestInput.value.trim()
                        if (trimmed != latestSaved.value) {
                            onSave(trimmed.ifBlank { null })
                        }
                    }
                }
        )
    }
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
