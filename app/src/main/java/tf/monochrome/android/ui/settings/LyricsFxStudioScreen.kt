package tf.monochrome.android.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import tf.monochrome.android.data.preferences.PreferencesManager
import androidx.compose.ui.res.painterResource
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.LyricsFxPreset
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerActionDock
import tf.monochrome.android.ui.player.GlassDropShadow
import tf.monochrome.android.ui.player.GlassProgressTube
import tf.monochrome.android.ui.player.PlayerDesignTokens
import tf.monochrome.android.ui.player.TransportIcon
import tf.monochrome.android.ui.player.drawGlassPlayPauseDisc
import tf.monochrome.android.ui.player.playerGlass
import tf.monochrome.android.ui.player.Letters3DLine
import tf.monochrome.android.ui.player.LocalBeatPulse
import tf.monochrome.android.ui.player.LocalLyricGlyphAnchors
import tf.monochrome.android.ui.player.LocalLyricsFx
import tf.monochrome.android.ui.player.LyricGlyphAnchors
import tf.monochrome.android.ui.player.LyricsFxLayer
import tf.monochrome.android.ui.player.SyncedLyricsView
import tf.monochrome.android.ui.player.bassBeat
import tf.monochrome.android.ui.player.fxaa
import tf.monochrome.android.ui.player.liquidGlass
import tf.monochrome.android.ui.player.rememberLyricFontFamily
import tf.monochrome.android.ui.player.withLyricFont
import java.util.Locale
import javax.inject.Inject
import kotlin.math.exp

@HiltViewModel
class LyricsFxStudioViewModel @Inject constructor(
    private val preferences: PreferencesManager,
    @ApplicationContext private val context: Context,
    nowPlayingLyrics: tf.monochrome.android.player.NowPlayingLyricsHolder,
) : ViewModel() {
    /** The currently-playing lyrics + position, so the preview can show them live. */
    val currentLyrics: StateFlow<tf.monochrome.android.domain.model.Lyrics?> = nowPlayingLyrics.lyrics
    val currentPositionMs: StateFlow<Long> = nowPlayingLyrics.positionMs
    // An in-memory working copy is the source of truth for the Studio UI and the
    // live preview, so every slider frame updates instantly with no I/O. A slider
    // drag fires dozens of times a second; persisting each frame — JSON-encode +
    // DataStore write, then reading the value back through the flow — was the
    // studio's performance cliff. Persistence is debounced to the drag's tail.
    private val _fx = MutableStateFlow(LyricsFxSettings.DEFAULT)
    val fx: StateFlow<LyricsFxSettings> = _fx.asStateFlow()

    private var userTouched = false
    private var persistJob: Job? = null

    /** Player-chrome (transport button) glass settings — the "Player Glass" tab. */
    private val _playerGlass = MutableStateFlow(tf.monochrome.android.domain.model.PlayerGlassSettings.DEFAULT)
    val playerGlass: StateFlow<tf.monochrome.android.domain.model.PlayerGlassSettings> = _playerGlass.asStateFlow()
    private var playerGlassTouched = false
    private var playerGlassPersistJob: Job? = null

    /** Fonts the user has imported (Settings › Appearance copies them here). */
    private val _availableFonts = MutableStateFlow<List<File>>(emptyList())
    val availableFonts: StateFlow<List<File>> = _availableFonts.asStateFlow()

    /** The user's own saved presets, alongside the built-in ones. */
    val customPresets: StateFlow<List<tf.monochrome.android.domain.model.LyricsFxPreset>> =
        preferences.customLyricsFxPresets.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList(),
        )

    init {
        viewModelScope.launch {
            // Seed once from the persisted settings; after the user starts tuning,
            // the in-memory copy leads so our own debounced writes never echo back.
            val persisted = preferences.lyricsFx.first()
            if (!userTouched) _fx.value = persisted
        }
        viewModelScope.launch {
            val pg = preferences.playerGlass.first()
            if (!playerGlassTouched) _playerGlass.value = pg
        }
        refreshFonts()
    }

    /** Re-list the imported font files off the main thread (directory I/O). */
    fun refreshFonts() {
        viewModelScope.launch(Dispatchers.IO) {
            _availableFonts.value = File(context.filesDir, "custom_fonts").listFiles()
                ?.filter { it.isFile && (it.extension.equals("ttf", true) || it.extension.equals("otf", true)) }
                ?.sortedBy { it.name.lowercase(java.util.Locale.US) }
                ?: emptyList()
        }
    }

    fun update(transform: (LyricsFxSettings) -> LyricsFxSettings) {
        userTouched = true
        _fx.value = transform(_fx.value).clamped()
        schedulePersist()
    }

    fun updatePlayerGlass(transform: (tf.monochrome.android.domain.model.PlayerGlassSettings) -> tf.monochrome.android.domain.model.PlayerGlassSettings) {
        playerGlassTouched = true
        _playerGlass.value = transform(_playerGlass.value).clamped()
        playerGlassPersistJob?.cancel()
        playerGlassPersistJob = viewModelScope.launch {
            delay(200)
            preferences.setPlayerGlass(_playerGlass.value)
        }
    }

    fun applyPreset(preset: LyricsFxSettings) {
        userTouched = true
        // A theme changes only the look — keep the user's font, Bluetooth delay,
        // and glass sample count (personal/device settings) across the switch.
        val next = preset.withPersonalFrom(_fx.value).clamped()
        _fx.value = next
        persistJob?.cancel()
        viewModelScope.launch { preferences.setLyricsFx(next) }
    }

    /**
     * Save the current settings as a named preset. A blank name is ignored; an
     * existing name is overwritten so re-saving updates in place.
     */
    fun saveCurrentAsPreset(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val preset = tf.monochrome.android.domain.model.LyricsFxPreset(clean, _fx.value.clamped())
        viewModelScope.launch {
            val current = customPresets.value.filterNot { it.name.equals(clean, ignoreCase = true) }
            preferences.setCustomLyricsFxPresets(current + preset)
        }
    }

    fun deletePreset(name: String) {
        viewModelScope.launch {
            preferences.setCustomLyricsFxPresets(customPresets.value.filterNot { it.name == name })
        }
    }

    /** The shareable code for a preset (prefix + compact JSON). */
    fun exportPreset(preset: tf.monochrome.android.domain.model.LyricsFxPreset): String =
        tf.monochrome.android.domain.model.LyricsFxPreset.encode(preset)

    /**
     * Import a preset from a shared code. Returns the imported name on success,
     * or null if the text wasn't a valid preset. A clashing name is de-duped
     * with a numeric suffix so an import never silently overwrites.
     */
    fun importPresetCode(code: String): String? {
        val decoded = tf.monochrome.android.domain.model.LyricsFxPreset.decode(code) ?: return null
        val existing = customPresets.value.map { it.name }.toSet()
        var name = decoded.name
        var n = 2
        while (name in existing) { name = "${decoded.name} ($n)"; n++ }
        val toAdd = decoded.copy(name = name)
        viewModelScope.launch { preferences.setCustomLyricsFxPresets(customPresets.value + toAdd) }
        return name
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
    val fonts by viewModel.availableFonts.collectAsState()
    val currentLyrics by viewModel.currentLyrics.collectAsState()
    val customPresets by viewModel.customPresets.collectAsState()
    val playerGlass by viewModel.playerGlass.collectAsState()
    val context = LocalContext.current

    // Which studio tab: 0 = Lyrics, 1 = Player Glass.
    var selectedTab by remember { mutableStateOf(0) }

    // Preset save / import / share dialog state.
    var showSaveDialog by remember { mutableStateOf(false) }
    var showImportDialog by remember { mutableStateOf(false) }
    var presetAction by remember { mutableStateOf<LyricsFxPreset?>(null) }

    // Re-list imported fonts whenever the custom-font toggle turns on, so a font
    // imported in Settings › Appearance since this screen opened shows up.
    LaunchedEffect(fx.customFont) { if (fx.customFont) viewModel.refreshFonts() }

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

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
        ) {
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Lyrics") })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Player Glass") })
        }

        if (selectedTab == 1) {
            PlayerGlassTab(playerGlass) { viewModel.updatePlayerGlass(it) }
            return@Column
        }

        // Preview + presets are pinned above the scrolling sliders, so the
        // live example stays locked in view while you tune every parameter.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            StudioPreview(fx, currentLyrics, viewModel.currentPositionMs)
            Spacer(Modifier.height(12.dp))

            // Preset bar header: a Save button (store the current look) and an
            // Import button (paste a shared preset code) beside the "Presets" label.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Presets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showSaveDialog = true }) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Save")
                }
                TextButton(onClick = { showImportDialog = true }) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import")
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LyricsFxSettings.PRESETS.forEach { (name, preset) ->
                    FilterChip(
                        selected = fx.matchesPreset(preset),
                        onClick = { viewModel.applyPreset(preset) },
                        label = { Text(name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
                // The user's own saved presets. Tapping applies; the trailing
                // icon opens a Share / Delete sheet for that preset.
                customPresets.forEach { saved ->
                    FilterChip(
                        selected = fx.matchesPreset(saved.settings),
                        onClick = { viewModel.applyPreset(saved.settings) },
                        label = { Text(saved.name) },
                        trailingIcon = {
                            Icon(
                                Icons.Default.Share,
                                contentDescription = "Manage ${saved.name}",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { presetAction = saved },
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    )
                }
            }
        }

        // ── Save current settings as a named preset ─────────────────────────
        if (showSaveDialog) {
            var name by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text("Save preset") },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text("Preset name") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            viewModel.saveCurrentAsPreset(name)
                            showSaveDialog = false
                            android.widget.Toast.makeText(context, "Saved \"${name.trim()}\"", android.widget.Toast.LENGTH_SHORT).show()
                        },
                    ) { Text("Save") }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") } },
            )
        }

        // ── Import a shared preset code ──────────────────────────────────────
        if (showImportDialog) {
            var code by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text("Import preset") },
                text = {
                    Column {
                        Text(
                            "Paste a preset code someone shared with you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text("Preset code") },
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )
                        TextButton(onClick = {
                            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                            val primary = clip?.primaryClip
                            if (primary != null && primary.itemCount > 0) {
                                primary.getItemAt(0).coerceToText(context)?.let { code = it.toString() }
                            }
                        }) { Text("Paste from clipboard") }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = code.isNotBlank(),
                        onClick = {
                            val imported = viewModel.importPresetCode(code)
                            showImportDialog = false
                            android.widget.Toast.makeText(
                                context,
                                if (imported != null) "Imported \"$imported\"" else "That doesn't look like a valid preset code",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) { Text("Import") }
                },
                dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text("Cancel") } },
            )
        }

        // ── Share / delete a saved preset ────────────────────────────────────
        presetAction?.let { target ->
            AlertDialog(
                onDismissRequest = { presetAction = null },
                title = { Text(target.name) },
                text = { Text("Share this preset or remove it from your list.") },
                confirmButton = {
                    TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, "Lyrics FX preset: ${target.name}")
                            putExtra(android.content.Intent.EXTRA_TEXT, viewModel.exportPreset(target))
                        }
                        context.startActivity(android.content.Intent.createChooser(send, "Share preset"))
                        presetAction = null
                    }) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            viewModel.deletePreset(target.name)
                            presetAction = null
                        }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Delete")
                        }
                        TextButton(onClick = { presetAction = null }) { Text("Close") }
                    }
                },
            )
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
                FxSlider(
                    "Edge margin", "%.0f dp".format(fx.edgeMarginDp), fx.edgeMarginDp, 0f..48f,
                    description = "Side spacing between the lyrics and the screen edges.",
                ) { viewModel.update { s -> s.copy(edgeMarginDp = it) } }
                FxSlider(
                    "Lines per block", "${fx.maxWrapLines}" + if (fx.maxWrapLines == 1) " (no wrap)" else "",
                    fx.maxWrapLines.toFloat(), 1f..3f, steps = 1,
                    description = "How many rows a long line may wrap to before it shrinks to fit.",
                ) { viewModel.update { s -> s.copy(maxWrapLines = it.toInt()) } }
            }

            item {
                StudioSection("Font")
                FxToggle(
                    "Custom lyrics font", fx.customFont,
                    description = "Use one of your imported fonts for the lyrics only.",
                ) { viewModel.update { s -> s.copy(customFont = it) } }
                if (fx.customFont) {
                    FontPicker(
                        fonts = fonts,
                        selectedPath = fx.customFontPath,
                        onSelect = { viewModel.update { s -> s.copy(customFontPath = it) } },
                    )
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
                    description = "Master intensity for pump, pop-in, and glow.",
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
                StudioSection("Glow")
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
                FxSlider(
                    "Bevel samples", "${1 + 4 * fx.glassSampleRings} taps/px",
                    fx.glassSampleRings.toFloat(), 1f..3f, steps = 1,
                    description = "Shader taps per pixel: higher = smoother glass, heavier GPU.",
                ) { viewModel.update { s -> s.copy(glassSampleRings = it.toInt()) } }
            }

            item {
                StudioSection("Anti-aliasing")
                FxToggle(
                    "Anti-aliasing (FXAA)", fx.fxaa,
                    description = "Smooths jagged edges on the 3D letters and glass (needs Android 13+).",
                ) { viewModel.update { s -> s.copy(fxaa = it) } }
                if (fx.fxaa) {
                    FxSlider(
                        "AA strength", "${(fx.fxaaStrength * 100).toInt()}%",
                        fx.fxaaStrength, 0f..1f,
                        description = "How hard edges are smoothed; higher softens more.",
                    ) { viewModel.update { s -> s.copy(fxaaStrength = it) } }
                }
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
 * The "Player Glass" tab: the same refractive-glass controls the lyrics have,
 * for the player's transport buttons, over a live preview of the glass icons.
 */
@Composable
private fun PlayerGlassTab(
    glass: PlayerGlassSettings,
    onUpdate: ((PlayerGlassSettings) -> PlayerGlassSettings) -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    // Custom preview colours (0 = use the current album colour).
    val previewTint = if (glass.tintColor != 0) Color(glass.tintColor) else accent
    val previewBgBrush = if (glass.previewBg != 0) SolidColor(Color(glass.previewBg)) else previewBackground(accent)
    var showBgPicker by remember { mutableStateOf(false) }
    var showTintPicker by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned preview — stays locked in view while you tune the sliders,
        // just like the Lyrics editor.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        // Live preview: the real transport buttons AND the action dock under the
        // current button glass — the dock is the same hollowed-slab glass, so it
        // tunes with these sliders exactly like the play button.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(288.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(previewBgBrush),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(LocalPlayerGlass provides glass) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Real transport icon: bigger skip + shape-accurate shadow,
                        // exactly like the player.
                        TransportIcon(
                            painterResource(R.drawable.ic_glass_skip_previous), "Previous", previewTint, {},
                            size = PlayerDesignTokens.SkipIconSize,
                        )
                        // Solid glass disc with the play symbol punched out, plus the
                        // same custom round drop shadow as the real play button.
                        Box(
                            Modifier.size(64.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            GlassDropShadow(
                                color = lerp(Color.Black, previewTint, glass.shadowTint)
                                    .copy(alpha = 0.28f + 0.55f * glass.shadowDepth),
                                softness = glass.shadowSoftness,
                                depth = glass.shadowDepth,
                            )
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(
                                    Modifier
                                        .fillMaxSize()
                                        .playerGlass(previewTint)
                                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                                ) {
                                    drawGlassPlayPauseDisc(isPlaying = false, fill = previewTint)
                                }
                            }
                        }
                        TransportIcon(
                            painterResource(R.drawable.ic_glass_skip_next), "Next", previewTint, {},
                            size = PlayerDesignTokens.SkipIconSize,
                        )
                    }
                    // The real hollowed-slab dock, previewed under the same glass.
                    PlayerActionDock(
                        accent = accent,
                        lyricsActive = false,
                        onLyrics = {},
                        onTimer = {},
                        onMixer = {},
                        onPlaylist = {},
                    )
                    // The glass thermometer scrubber, previewed at ~40%.
                    GlassProgressTube(
                        fraction = 0.4f,
                        tint = previewTint,
                        onSeek = {},
                        onSeekFinished = {},
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Text(
                text = "PREVIEW",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
        }
        }

        // Controls scroll below the pinned preview.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
        // Colour swatches: preview background + button glass tint, each "current"
        // (album) or a custom colour picked from the bubble.
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            ColorSwatch(
                label = "Background",
                color = if (glass.previewBg != 0) Color(glass.previewBg) else lerp(Color.Black, accent, 0.34f),
                isCustom = glass.previewBg != 0,
                onClick = { showBgPicker = true },
            )
            ColorSwatch(
                label = "Button tint",
                color = previewTint,
                isCustom = glass.tintColor != 0,
                onClick = { showTintPicker = true },
            )
        }
        Spacer(Modifier.height(14.dp))
        StudioSection("Player Glass")
        FxToggle(
            "Button liquid glass", glass.enabled,
            description = "3D refractive glass on the transport buttons (needs Android 13+).",
        ) { onUpdate { g -> g.copy(enabled = it) } }
        FxToggle(
            "Glass progress bar", glass.progressGlass,
            description = "Thin glass tube scrubber that fills up, with a sine-wave bulge at the playhead dot.",
        ) { onUpdate { g -> g.copy(progressGlass = it) } }
        FxSlider(
            "Glass opacity", "${(glass.bodyOpacity * 100).toInt()}%", glass.bodyOpacity, 0.2f..1f,
            description = "Lower makes the buttons more see-through.",
        ) { onUpdate { g -> g.copy(bodyOpacity = it) } }
        FxSlider(
            "Refraction", "%.2f".format(glass.refraction), glass.refraction, 0f..0.4f,
            description = "How hard the beveled edges lens the backdrop behind them.",
        ) { onUpdate { g -> g.copy(refraction = it) } }
        FxSlider(
            "Edge highlight", "${(glass.rimBrightness * 100).toInt()}%", glass.rimBrightness, 0f..2f,
            description = "Brightness of the specular glass rim.",
        ) { onUpdate { g -> g.copy(rimBrightness = it) } }
        FxSlider(
            "Chromatic aberration", "${(glass.dispersion * 100).toInt()}%", glass.dispersion, 0f..2f,
            description = "Colour fringing at the refracting edges.",
        ) { onUpdate { g -> g.copy(dispersion = it) } }
        FxSlider(
            "Roundness", "%.2f".format(glass.roundness), glass.roundness, 0.5f..2f,
            description = "Rolls the glass edge from a sharp bevel to a round, pillowy shoulder.",
        ) { onUpdate { g -> g.copy(roundness = it) } }
        FxSlider(
            "Depth (profondeur)", "%.2f".format(glass.depth), glass.depth, 0.5f..2f,
            description = "How thick and deep the relief reads — higher pops the buttons more in 3D.",
        ) { onUpdate { g -> g.copy(depth = it) } }
        FxSlider(
            "Shadow depth", "${(glass.shadowDepth * 100).toInt()}%", glass.shadowDepth, 0f..1f,
            description = "Drop shadow under the round play button — lifts it off the surface.",
        ) { onUpdate { g -> g.copy(shadowDepth = it) } }
        FxSlider(
            "Per-pixel samples",
            "${glass.sampleRings} (${when (glass.sampleRings) { 1 -> 5; 2 -> 9; else -> 13 }} taps)",
            glass.sampleRings.toFloat(), 1f..3f, steps = 1,
            description = "Bevel quality vs GPU cost.",
        ) { onUpdate { g -> g.copy(sampleRings = it.toInt()) } }
        FxSlider(
            "Reflection", "${(glass.reflection * 100).toInt()}%", glass.reflection, 0f..2f,
            description = "How much of the room/environment reflection shows on the glass.",
        ) { onUpdate { g -> g.copy(reflection = it) } }
        FxSlider(
            "Gloss", "${(glass.gloss * 100).toInt()}%", glass.gloss, 0f..1f,
            description = "Highlight polish: soft frosted-wide glint to a tight mirror.",
        ) { onUpdate { g -> g.copy(gloss = it) } }
        FxSlider(
            "Surface motion", "${(glass.surfaceMotion * 100).toInt()}%", glass.surfaceMotion, 0f..1f,
            description = "Living-liquid shimmer on the glass surface (0 = still).",
        ) { onUpdate { g -> g.copy(surfaceMotion = it) } }
        FxSlider(
            "Frosted blur", "${(glass.frost * 100).toInt()}%", glass.frost, 0f..1f,
            description = "Frosts the glass, from clear to misted.",
        ) { onUpdate { g -> g.copy(frost = it) } }

        StudioSection("Light & shadow")
        FxSlider(
            "Tilt reactivity", "${(glass.tiltReactivity * 100).toInt()}%", glass.tiltReactivity, 0f..1.5f,
            description = "How strongly tilting the phone moves the light and reflection.",
        ) { onUpdate { g -> g.copy(tiltReactivity = it) } }
        FxSlider(
            "Light angle", "${glass.lightAngleDeg.toInt()}°", glass.lightAngleDeg, 0f..360f,
            description = "Direction the key light comes from — where the highlights sit.",
        ) { onUpdate { g -> g.copy(lightAngleDeg = it) } }
        FxSlider(
            "Edge width", "${(glass.edgeWidth * 100).toInt()}%", glass.edgeWidth, 0f..1f,
            description = "Reflective rim: thin crisp edge to a broad glassy shoulder.",
        ) { onUpdate { g -> g.copy(edgeWidth = it) } }
        FxSlider(
            "Shadow softness", "${(glass.shadowSoftness * 100).toInt()}%", glass.shadowSoftness, 0f..1f,
            description = "Blur / spread of the drop shadow under the play button.",
        ) { onUpdate { g -> g.copy(shadowSoftness = it) } }
        FxSlider(
            "Shadow tint", "${(glass.shadowTint * 100).toInt()}%", glass.shadowTint, 0f..1f,
            description = "Colour of the drop shadow: neutral black to accent glow.",
        ) { onUpdate { g -> g.copy(shadowTint = it) } }
        Spacer(Modifier.height(48.dp))
        }
    }

    if (showBgPicker) {
        GlassColorPickerDialog(
            title = "Preview background",
            initial = glass.previewBg,
            onPick = { c -> onUpdate { it.copy(previewBg = c) }; showBgPicker = false },
            onDismiss = { showBgPicker = false },
        )
    }
    if (showTintPicker) {
        GlassColorPickerDialog(
            title = "Button glass tint",
            initial = glass.tintColor,
            onPick = { c -> onUpdate { it.copy(tintColor = c) }; showTintPicker = false },
            onDismiss = { showTintPicker = false },
        )
    }
}

/** A round colour bubble + label, showing "Current" (album) or "Custom". */
@Composable
private fun ColorSwatch(label: String, color: Color, isCustom: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .border(2.dp, Color.White.copy(alpha = 0.55f), CircleShape)
                .clickable(onClick = onClick),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
        Text(
            if (isCustom) "Custom" else "Current",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.45f),
        )
    }
}

/**
 * A compact HSV colour picker dialog. [initial] is an ARGB int (0 = current
 * album colour); [onPick] returns the chosen ARGB int, or 0 for "use current".
 */
@Composable
private fun GlassColorPickerDialog(
    title: String,
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val hsv = remember {
        FloatArray(3).also { a ->
            if (initial != 0) android.graphics.Color.colorToHSV(initial, a)
            else { a[0] = 210f; a[1] = 0.55f; a[2] = 0.95f }
        }
    }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val preview = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(preview),
                )
                Text("Hue", style = MaterialTheme.typography.labelSmall)
                Slider(value = h, onValueChange = { h = it }, valueRange = 0f..360f)
                Text("Saturation", style = MaterialTheme.typography.labelSmall)
                Slider(value = s, onValueChange = { s = it }, valueRange = 0f..1f)
                Text("Brightness", style = MaterialTheme.typography.labelSmall)
                Slider(value = v, onValueChange = { v = it }, valueRange = 0f..1f)
                TextButton(onClick = { onPick(0) }) { Text("Use current album colour") }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))) }) {
                Text("Select")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Preview backdrop tinted with the CURRENT theme/album [accent] — a dark
 * vertical wash of the live colour, so the Studio previews sit on the same
 * colour the player does instead of a fixed near-black.
 */
private fun previewBackground(accent: Color): Brush =
    Brush.verticalGradient(
        listOf(
            lerp(Color.Black, accent, 0.34f),
            lerp(Color.Black, accent, 0.10f),
        ),
    )

@Composable
private fun StudioPreview(
    fx: LyricsFxSettings,
    lyrics: Lyrics?,
    positionMs: kotlinx.coroutines.flow.StateFlow<Long>,
) {
    val pulse = rememberSyntheticKickPulse(fx)
    val anchors = remember { LyricGlyphAnchors() }
    val accent = MaterialTheme.colorScheme.primary
    // Show the real currently-playing lyrics when there are synced lines; else a
    // synthetic sample so the preview is never empty.
    val playing = lyrics?.takeIf { it.isSynced && it.lines.isNotEmpty() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(previewBackground(accent)),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalLyricsFx provides fx,
            LocalLyricGlyphAnchors provides anchors,
            // Drive the beat FX from the synthetic kick even for real lyrics
            // (there's no live audio analyzer on this screen).
            LocalBeatPulse provides pulse,
        ) {
            // The ray/glow FX layer draws at each active glyph's reported position.
            LyricsFxLayer(anchors = anchors, pulse = pulse, accent = accent, fx = fx)
            if (playing != null) {
                // Exactly the production renderer, on the real lyric lines.
                SyncedLyricsView(
                    lines = playing.lines,
                    positionMs = positionMs,
                    accent = accent,
                    onSeekTo = {},
                )
            } else {
                Letters3DLine(
                    text = "Feel the beat tonight",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = fx.fontSizeSp.sp,
                        lineHeight = (fx.fontSizeSp * 1.26f).sp,
                        letterSpacing = fx.letterSpacingSp.sp,
                        fontWeight = FontWeight.ExtraBold,
                    ).withLyricFont(rememberLyricFontFamily(fx)),
                    color = accent,
                    modifier = Modifier
                        .fxaa()
                        .liquidGlass(tint = accent)
                        .bassBeat(pulse, { 1f }, fx, anchors),
                    anchors = anchors,
                    fontSizeSp = fx.fontSizeSp,
                )
            }
        }
        Text(
            text = if (playing != null) "PREVIEW · now playing" else "PREVIEW · 120 BPM",
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

/** Collapsible picker over the fonts the user imported (Settings › Appearance). */
@Composable
private fun FontPicker(
    fonts: List<File>,
    selectedPath: String,
    onSelect: (String) -> Unit,
) {
    if (fonts.isEmpty()) {
        Text(
            text = "No imported fonts yet — add them in Settings › Appearance › Font Library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val selectedName = fonts.firstOrNull { it.absolutePath == selectedPath }?.nameWithoutExtension
    Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Font: " + (selectedName ?: "choose…"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.fillMaxWidth()) {
                fonts.forEach { file ->
                    val isSelected = file.absolutePath == selectedPath
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(file.absolutePath) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            modifier = Modifier.padding(end = 12.dp),
                        )
                        Text(
                            text = file.nameWithoutExtension,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
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
