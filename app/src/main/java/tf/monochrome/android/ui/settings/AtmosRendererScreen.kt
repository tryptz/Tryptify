package tf.monochrome.android.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.ChannelLayout
import tf.monochrome.android.domain.model.DrcMode
import tf.monochrome.android.domain.model.RendererMode
import tf.monochrome.android.domain.model.RendererProfile
import tf.monochrome.android.domain.model.SpeakerChannel
import tf.monochrome.android.domain.model.StereoDownmixMode
import tf.monochrome.android.domain.model.speakers
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

@HiltViewModel
class AtmosRendererViewModel @Inject constructor(
    private val preferences: PreferencesManager,
) : ViewModel() {

    // In-memory working copy so slider drags update the UI instantly; the
    // DataStore write is debounced to the drag's tail (same approach the Player
    // Visuals Studio uses for its glass sliders).
    private val _profile = MutableStateFlow(RendererProfile.DEFAULT)
    val profile: StateFlow<RendererProfile> = _profile.asStateFlow()
    private var persistJob: Job? = null

    init {
        viewModelScope.launch { _profile.value = preferences.rendererProfile.first() }
    }

    fun update(profile: RendererProfile) {
        val clamped = profile.clamped()
        _profile.value = clamped
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(120)
            preferences.setRendererProfile(clamped)
        }
    }

    fun reset() = update(RendererProfile.DEFAULT)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmosRendererScreen(
    navController: NavController,
    viewModel: AtmosRendererViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Atmos Renderer Configuration") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            // ── Channel map ────────────────────────────────────────────────
            SectionHeader("Channel Map")
            Text(
                "Speakers the current mix drives, lit on their room positions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            SpeakerLayoutMap(
                layout = profile.layout,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.15f),
            )
            Spacer(Modifier.height(20.dp))

            // ── Renderer mode ──────────────────────────────────────────────
            SectionHeader("Renderer Mode")
            ChoiceChips(
                options = RendererMode.entries,
                selected = profile.mode,
                label = { it.displayName },
                onSelect = { viewModel.update(profile.copy(mode = it)) },
            )
            Text(
                profile.mode.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            // ── Output layout ──────────────────────────────────────────────
            SectionHeader("Output Layout")
            SettingSwitchItem(
                title = "Auto-detect from DAC",
                subtitle = "Follow the connected DAC's channel count",
                checked = profile.autoDetectLayout,
                onCheckedChange = { viewModel.update(profile.copy(autoDetectLayout = it)) },
            )
            ChoiceChips(
                options = ChannelLayout.entries,
                selected = profile.layout,
                enabled = !profile.autoDetectLayout,
                label = { it.label },
                onSelect = { viewModel.update(profile.copy(layout = it)) },
            )
            Spacer(Modifier.height(20.dp))

            // ── Stereo downmix ─────────────────────────────────────────────
            SectionHeader("Stereo Downmix")
            ChoiceChips(
                options = StereoDownmixMode.entries,
                selected = profile.stereoDownmix,
                label = { it.displayName },
                onSelect = { viewModel.update(profile.copy(stereoDownmix = it)) },
            )
            Text(
                profile.stereoDownmix.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            // ── Binaural / headphones ──────────────────────────────────────
            SectionHeader("Binaural (Headphones)")
            ChoiceChips(
                options = listOf(false, true),
                selected = profile.hrtfProfileId != null,
                label = { if (it) "My AutoEQ measurement" else "Built-in HRTF" },
                onSelect = { useOwn ->
                    viewModel.update(profile.copy(hrtfProfileId = if (useOwn) "autoeq" else null))
                },
            )
            LabeledSlider(
                title = "Binaural strength",
                valueText = "${(profile.binauralStrength * 100).toInt()}%",
                value = profile.binauralStrength,
                range = 0f..1f,
                onValueChange = { viewModel.update(profile.copy(binauralStrength = it)) },
            )
            SettingSwitchItem(
                title = "Height virtualization",
                subtitle = "Virtualize overhead objects on layouts without top speakers",
                checked = profile.heightVirtualization,
                onCheckedChange = { viewModel.update(profile.copy(heightVirtualization = it)) },
            )
            Spacer(Modifier.height(20.dp))

            // ── Bass management ────────────────────────────────────────────
            SectionHeader("Bass Management")
            SettingSwitchItem(
                title = "Bass management",
                subtitle = "Send full-range objects' low end to the LFE / subwoofer",
                checked = profile.bassManagement,
                onCheckedChange = { viewModel.update(profile.copy(bassManagement = it)) },
            )
            LabeledSlider(
                title = "Crossover",
                valueText = "${profile.crossoverHz} Hz",
                value = profile.crossoverHz.toFloat(),
                range = 40f..200f,
                steps = 15,
                enabled = profile.bassManagement,
                onValueChange = { viewModel.update(profile.copy(crossoverHz = it.toInt())) },
            )
            LabeledSlider(
                title = "LFE gain",
                valueText = "%+.1f dB".format(profile.lfeGainDb),
                value = profile.lfeGainDb,
                range = -10f..10f,
                onValueChange = { viewModel.update(profile.copy(lfeGainDb = it)) },
            )
            Spacer(Modifier.height(20.dp))

            // ── Loudness ───────────────────────────────────────────────────
            SectionHeader("Loudness & Dynamics")
            Text(
                "Dynamic range control",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            ChoiceChips(
                options = DrcMode.entries,
                selected = profile.drc,
                label = { it.displayName },
                onSelect = { viewModel.update(profile.copy(drc = it)) },
            )
            SettingSwitchItem(
                title = "Dialogue normalization",
                subtitle = "Align loudness to the stream's dialnorm reference",
                checked = profile.dialogNormalization,
                onCheckedChange = { viewModel.update(profile.copy(dialogNormalization = it)) },
            )
            Spacer(Modifier.height(16.dp))

            TextButton(onClick = { viewModel.reset() }) { Text("Reset to defaults") }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChoiceChips(
    options: List<T>,
    selected: T,
    enabled: Boolean = true,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                enabled = enabled,
                onClick = { onSelect(option) },
                label = { Text(label(option)) },
            )
        }
    }
}

@Composable
private fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChange: (Float) -> Unit,
) {
    val fade = if (enabled) 1f else 0.4f
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = fade),
            modifier = Modifier.weight(1f),
        )
        Text(
            valueText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = fade),
        )
    }
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = range,
        steps = steps,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Top-down room plan: the listener at centre, each speaker of [layout] placed at
 * its azimuth and lit with a soft radial bloom (a gentle pulse = "light fx") so
 * the channels the mix uses read at a glance. Height speakers sit on an inner
 * ring in a warmer tint; the LFE is a small dot below the listener.
 */
@Composable
private fun SpeakerLayoutMap(
    layout: ChannelLayout,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val speakers = layout.speakers()
    val textMeasurer = rememberTextMeasurer()
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val heightTint = MaterialTheme.colorScheme.tertiary

    val pulse by rememberInfiniteTransition(label = "map-pulse").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val ringR = min(cx, cy) * 0.82f

            // Room boundary + listener.
            drawCircle(color = outline.copy(alpha = 0.25f), radius = ringR, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f))
            drawCircle(color = onSurface.copy(alpha = 0.5f), radius = ringR * 0.05f, center = Offset(cx, cy))

            speakers.forEach { s ->
                val pos = speakerPosition(s, cx, cy, ringR)
                val tint = if (s.isHeight) heightTint else accent
                if (s.isLfe) {
                    // LFE: directionless, drawn just below the listener.
                    val lfePos = Offset(cx, cy + ringR * 0.28f)
                    drawSpeaker(lfePos, tint, pulse, dotScale = 0.85f, textMeasurer, s, onSurface)
                } else {
                    drawSpeaker(pos, tint, pulse, dotScale = if (s.isHeight) 0.9f else 1f, textMeasurer, s, onSurface)
                }
            }
        }
    }
}

/** Screen position for a speaker: azimuth 0 = front (up), growing clockwise. */
private fun speakerPosition(s: SpeakerChannel, cx: Float, cy: Float, ringR: Float): Offset {
    // Height speakers pulled onto an inner ring so they read as "above".
    val r = ringR * if (s.isHeight) 0.55f else 1f
    val az = Math.toRadians(s.azimuthDeg.toDouble())
    val x = cx + r * sin(az).toFloat()
    val y = cy - r * cos(az).toFloat()
    return Offset(x, y)
}

private fun DrawScope.drawSpeaker(
    pos: Offset,
    tint: Color,
    pulse: Float,
    dotScale: Float,
    textMeasurer: androidx.compose.ui.text.TextMeasurer,
    speaker: SpeakerChannel,
    labelColor: Color,
) {
    val glowR = 26f * dotScale
    // Soft bloom.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(tint.copy(alpha = 0.45f * pulse), Color.Transparent),
            center = pos,
            radius = glowR,
        ),
        radius = glowR,
        center = pos,
    )
    // Solid speaker dot.
    drawCircle(color = tint.copy(alpha = 0.9f), radius = 7f * dotScale, center = pos)

    // Label just below the dot.
    val measured = textMeasurer.measure(
        speaker.label,
        style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = labelColor.copy(alpha = 0.85f)),
    )
    drawText(
        measured,
        topLeft = Offset(pos.x - measured.size.width / 2f, pos.y + 9f * dotScale),
    )
}
