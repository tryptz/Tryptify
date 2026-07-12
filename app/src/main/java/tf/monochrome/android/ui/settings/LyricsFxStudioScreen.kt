package tf.monochrome.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.ui.player.Letters3DLine
import tf.monochrome.android.ui.player.LocalLyricGlyphAnchors
import tf.monochrome.android.ui.player.LocalLyricsFx
import tf.monochrome.android.ui.player.LyricGlyphAnchors
import tf.monochrome.android.ui.player.LyricsFxLayer
import tf.monochrome.android.ui.player.bassBeat
import tf.monochrome.android.ui.player.liquidGlass
import java.util.Locale
import javax.inject.Inject
import kotlin.math.exp

@HiltViewModel
class LyricsFxStudioViewModel @Inject constructor(
    private val preferences: PreferencesManager,
) : ViewModel() {
    // An in-memory working copy is the source of truth for the Studio UI and the
    // live preview, so every slider frame updates instantly with no I/O. A slider
    // drag fires dozens of times a second; persisting each frame — JSON-encode +
    // DataStore write, then reading the value back through the flow — was the
    // studio's performance cliff. Persistence is debounced to the drag's tail.
    private val _fx = MutableStateFlow(LyricsFxSettings.DEFAULT)
    val fx: StateFlow<LyricsFxSettings> = _fx.asStateFlow()

    private var userTouched = false
    private var persistJob: Job? = null

    init {
        viewModelScope.launch {
            // Seed once from the persisted settings; after the user starts tuning,
            // the in-memory copy leads so our own debounced writes never echo back.
            val persisted = preferences.lyricsFx.first()
            if (!userTouched) _fx.value = persisted
        }
    }

    fun update(transform: (LyricsFxSettings) -> LyricsFxSettings) {
        userTouched = true
        _fx.value = transform(_fx.value).clamped()
        schedulePersist()
    }

    fun applyPreset(preset: LyricsFxSettings) {
        userTouched = true
        _fx.value = preset.clamped()
        persistJob?.cancel()
        viewModelScope.launch { preferences.setLyricsFx(preset) }
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(200)
            preferences.setLyricsFx(_fx.value)
        }
    }
}

/**
 * Lyrics FX Studio — every parameter of the lyric renderer (typography, 3D
 * wave, beat engine, god rays) as live sliders over a self-animating preview.
 * The preview runs a synthetic 120 BPM kick through the same visual pipeline
 * the player uses, so tuning works without any music playing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsFxStudioScreen(
    navController: NavController,
    viewModel: LyricsFxStudioViewModel = hiltViewModel(),
) {
    val fx by viewModel.fx.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Lyrics FX Studio") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        // Preview + presets are pinned above the scrolling sliders, so the
        // live example stays locked in view while you tune every parameter.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            StudioPreview(fx)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LyricsFxSettings.PRESETS.forEach { (name, preset) ->
                    FilterChip(
                        selected = fx == preset,
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 48.dp),
        ) {
            item {
                StudioSection("Typography")
                FxSlider("Font size", "%.0f sp".format(fx.fontSizeSp), fx.fontSizeSp, 14f..34f) {
                    viewModel.update { s -> s.copy(fontSizeSp = it) }
                }
                FxSlider("Letter spacing", "%.2f sp".format(fx.letterSpacingSp), fx.letterSpacingSp, -1f..1f) {
                    viewModel.update { s -> s.copy(letterSpacingSp = it) }
                }
            }

            item {
                StudioSection("Playback Sync")
                FxSlider(
                    "Bluetooth sync delay",
                    "%+d ms".format(fx.bluetoothDelayMs.toInt()),
                    fx.bluetoothDelayMs, -500f..1500f, steps = 39,
                    description = "Delays synced lyrics to line up with Bluetooth audio latency. " +
                        "Raise it until the words land with what you hear; negative pulls them earlier.",
                ) { viewModel.update { s -> s.copy(bluetoothDelayMs = it) } }
            }

            item {
                StudioSection("3D Letter Wave")
                FxSlider(
                    "Tilt", "%.0f°".format(fx.rotationDegrees) + if (fx.rotationDegrees < 0.5f) " (off)" else "",
                    fx.rotationDegrees, 0f..25f,
                ) { viewModel.update { s -> s.copy(rotationDegrees = it) } }
                FxSlider("Wave speed", "%.2fx".format(fx.waveSpeed), fx.waveSpeed, 0.25f..3f) {
                    viewModel.update { s -> s.copy(waveSpeed = it) }
                }
                FxSlider(
                    "Wave tightness", "%.2f rad/letter".format(fx.wavePhaseStep),
                    fx.wavePhaseStep, 0.05f..0.9f,
                    description = "Low = one smooth ribbon; high = choppy per-letter motion.",
                ) { viewModel.update { s -> s.copy(wavePhaseStep = it) } }
                FxSlider("Wave travel", "%.1f dp".format(fx.waveTravelDp), fx.waveTravelDp, 0f..8f) {
                    viewModel.update { s -> s.copy(waveTravelDp = it) }
                }
                FxSlider("Shadow depth", "${(fx.shadowDepth * 100).toInt()}%", fx.shadowDepth, 0f..1f) {
                    viewModel.update { s -> s.copy(shadowDepth = it) }
                }
            }

            item {
                StudioSection("Beat Engine")
                FxSlider(
                    "Bass reaction", "${(fx.bassReact * 100).toInt()}%" + if (fx.bassReact < 0.01f) " (off)" else "",
                    fx.bassReact, 0f..1f,
                    description = "Master intensity for pump, pop-in, glow, and rays.",
                ) { viewModel.update { s -> s.copy(bassReact = it) } }
                FxSlider("Pump amount", "+${(fx.pumpAmount * 100).toInt()}%", fx.pumpAmount, 0f..0.25f) {
                    viewModel.update { s -> s.copy(pumpAmount = it) }
                }
                FxSlider(
                    "Attack", "%.0f ms".format(fx.attackMs), fx.attackMs, 4f..60f,
                    description = "How fast the pulse snaps onto a kick.",
                ) { viewModel.update { s -> s.copy(attackMs = it) } }
                FxSlider(
                    "Release", "%.0f ms".format(fx.releaseMs), fx.releaseMs, 40f..500f,
                    description = "How long the pulse holds through a kick.",
                ) { viewModel.update { s -> s.copy(releaseMs = it) } }
                FxSlider(
                    "Bounce", "${(fx.bounce * 100).toInt()}%", fx.bounce, 0f..1f,
                    description = "Spring overshoot — 0 tracks stiffly, 100 rings like rubber.",
                ) { viewModel.update { s -> s.copy(bounce = it) } }
                FxSlider("Pop-in amount", "${(fx.popAmount * 100).toInt()}%", fx.popAmount, 0f..0.2f) {
                    viewModel.update { s -> s.copy(popAmount = it) }
                }
            }

            item {
                StudioSection("God Rays & Glow")
                FxSlider(
                    "Ray count", "${fx.rayCount}" + if (fx.rayCount == 0) " (off)" else "",
                    fx.rayCount.toFloat(), 0f..24f, steps = 23,
                ) { viewModel.update { s -> s.copy(rayCount = it.toInt()) } }
                FxSlider("Ray length", "${(fx.rayLength * 100).toInt()}% of screen", fx.rayLength, 0.1f..1f) {
                    viewModel.update { s -> s.copy(rayLength = it) }
                }
                FxSlider("Ray width", "%.0f dp".format(fx.rayWidthDp), fx.rayWidthDp, 1f..16f) {
                    viewModel.update { s -> s.copy(rayWidthDp = it) }
                }
                FxSlider("Ray brightness", "${(fx.rayBrightness * 100).toInt()}%", fx.rayBrightness, 0f..0.6f) {
                    viewModel.update { s -> s.copy(rayBrightness = it) }
                }
                FxSlider(
                    "Ray spin", "%.0f°/s".format(fx.raySpinDegPerSec), fx.raySpinDegPerSec, -60f..60f,
                    description = "Orbit speed of the beam fan; negative spins the other way.",
                ) { viewModel.update { s -> s.copy(raySpinDegPerSec = it) } }
                FxSlider("Glow radius", "+%.0f dp".format(fx.glowRadiusDp), fx.glowRadiusDp, 0f..160f) {
                    viewModel.update { s -> s.copy(glowRadiusDp = it) }
                }
                FxSlider("Glow brightness", "${(fx.glowBrightness * 100).toInt()}%", fx.glowBrightness, 0f..0.6f) {
                    viewModel.update { s -> s.copy(glowBrightness = it) }
                }
            }

            item {
                StudioSection("Liquid Glass")
                FxToggle(
                    "Liquid glass", fx.liquidGlass,
                    description = "Refractive glass relight of the lyric surface (needs Android 13+).",
                ) { viewModel.update { s -> s.copy(liquidGlass = it) } }
                FxSlider(
                    "Glass opacity", "${(fx.glassBodyOpacity * 100).toInt()}%",
                    fx.glassBodyOpacity, 0.2f..1f,
                    description = "Lower lets more of the backdrop read through the letters.",
                ) { viewModel.update { s -> s.copy(glassBodyOpacity = it) } }
                FxSlider(
                    "Refraction", "%.2f".format(fx.glassRefraction), fx.glassRefraction, 0f..0.4f,
                    description = "How hard the beveled edges lens the backdrop behind them.",
                ) { viewModel.update { s -> s.copy(glassRefraction = it) } }
                FxSlider(
                    "Edge highlight", "${(fx.glassRimBrightness * 100).toInt()}%",
                    fx.glassRimBrightness, 0f..2f,
                    description = "Brightness of the specular glass rim.",
                ) { viewModel.update { s -> s.copy(glassRimBrightness = it) } }
                FxSlider(
                    "Chromatic aberration", "${(fx.glassDispersion * 100).toInt()}%",
                    fx.glassDispersion, 0f..2f,
                    description = "Colour fringing where the edges refract.",
                ) { viewModel.update { s -> s.copy(glassDispersion = it) } }
            }

            item {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { viewModel.applyPreset(LyricsFxSettings.DEFAULT) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Reset to defaults") }
            }
        }
    }
}

/**
 * Live preview: the production line renderer (3D letters + the bass-beat
 * element FX) driven by a synthetic 120 BPM kick pushed through the same
 * attack/release envelope + spring as the real bass pulse, so every slider
 * change is visible immediately, music or not. The god ray FX is applied to
 * the font element via [bassBeat] — exactly as it is on the active lyric line.
 */
@Composable
private fun StudioPreview(fx: LyricsFxSettings) {
    val pulse = rememberSyntheticKickPulse(fx)
    val anchors = remember { LyricGlyphAnchors() }
    val accent = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121016)),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalLyricsFx provides fx,
            LocalLyricGlyphAnchors provides anchors,
        ) {
            // Same pipeline as the player: the ray/glow FX layer fills the
            // frame and draws at each glyph's reported position, while the
            // text element pumps and publishes its glyphs.
            LyricsFxLayer(anchors = anchors, pulse = pulse, accent = accent, fx = fx)
            Letters3DLine(
                text = "Feel the beat tonight",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = fx.fontSizeSp.sp,
                    lineHeight = (fx.fontSizeSp * 1.26f).sp,
                    letterSpacing = fx.letterSpacingSp.sp,
                    fontWeight = FontWeight.ExtraBold,
                ),
                color = accent,
                modifier = Modifier
                    .liquidGlass(tint = accent)
                    .bassBeat(pulse, { 1f }, fx, anchors),
                anchors = anchors,
            )
        }
        Text(
            text = "PREVIEW · 120 BPM",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        )
    }
}

/**
 * Synthetic kick: an instant-on, exponential-decay envelope at 120 BPM pushed
 * through the SAME attack/release + underdamped-spring math as the live bass
 * pulse, so bounce/attack/release sliders behave in the preview exactly as
 * they will with real music.
 */
@Composable
private fun rememberSyntheticKickPulse(fx: LyricsFxSettings): State<Float> {
    val pulse = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(fx.attackMs, fx.releaseMs, fx.bounce) {
        val attackSec = (fx.attackMs / 1000f).coerceAtLeast(0.001f)
        val releaseSec = (fx.releaseMs / 1000f).coerceAtLeast(0.01f)
        val stiffness = 300f
        val damping = 2f * fx.springDampingRatio * kotlin.math.sqrt(stiffness)
        var env = 0f
        var pos = 0f
        var vel = 0f
        var lastNanos = -1L
        var t = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (lastNanos < 0) 0.016f
                else ((now - lastNanos) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
                lastNanos = now
                t += dt
                // 120 BPM kick train: full level at each beat, fast decay.
                val beatPhase = (t * 2f) % 1f
                val raw = exp(-6f * beatPhase)
                val coef = if (raw > env) 1f - exp(-dt / attackSec) else 1f - exp(-dt / releaseSec)
                env += (raw - env) * coef
                vel += ((env - pos) * stiffness - vel * damping) * dt
                pos += vel * dt
                pulse.floatValue = pos.coerceIn(0f, 1.6f)
            }
        }
    }
    return pulse
}

@Composable
private fun StudioSection(title: String) {
    Spacer(Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun FxToggle(
    label: String,
    checked: Boolean,
    description: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = checked, onCheckedChange = onChange)
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FxSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    description: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun String.format(vararg args: Any?): String = String.format(Locale.US, this, *args)
