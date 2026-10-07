package tf.monochrome.android.ui.settings

import tf.monochrome.android.ui.navigation.popBackStackSafe
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassSearchBar
import tf.monochrome.android.ui.navigation.AppTab
import tf.monochrome.android.ui.navigation.GlassTabBar
import tf.monochrome.android.ui.navigation.TabBarHeight
import tf.monochrome.android.ui.theme.DynamicColorScope
import tf.monochrome.android.ui.theme.glassTint
import dev.chrisbanes.haze.HazeState
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.style.TextOverflow
import dev.chrisbanes.haze.hazeSource
import tf.monochrome.android.ui.player.VisualizerPresetPanel
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Lyrics
import tf.monochrome.android.domain.model.LyricsFxPreset
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.domain.model.PlayerGlassPreset
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.components.MiniPlayer
import tf.monochrome.android.visualizer.AmbientVisualizerSettings
import tf.monochrome.android.visualizer.VisualizerBlendMode
import tf.monochrome.android.ui.components.buttonSemantics
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.LocalPlayerGlassGround
import tf.monochrome.android.ui.player.LocalPlayerHaze
import tf.monochrome.android.ui.player.PlayerGlassHaze
import tf.monochrome.android.ui.player.PlayerActionDock
import tf.monochrome.android.ui.player.GlassDropShadow
import tf.monochrome.android.ui.player.GlassProgressTube
import tf.monochrome.android.ui.player.PlayerDesignTokens
import tf.monochrome.android.ui.player.TransportIcon
import tf.monochrome.android.ui.player.drawGlassPlayPauseDisc
import tf.monochrome.android.ui.player.playerGlass
import tf.monochrome.android.ui.player.rememberPlayerLiveLens
import tf.monochrome.android.ui.player.Letters3DRow
import tf.monochrome.android.ui.player.LocalBeatPulse
import tf.monochrome.android.ui.player.rememberFrameSeconds
import tf.monochrome.android.ui.player.LocalLyricGlyphAnchors
import tf.monochrome.android.ui.player.LocalLyricsFx
import tf.monochrome.android.ui.player.LyricGlyphAnchors
import tf.monochrome.android.ui.player.LyricsFxLayer
import tf.monochrome.android.ui.player.SyncedLyricsView
import tf.monochrome.android.ui.player.bassBeat
import tf.monochrome.android.ui.player.fxaa
import tf.monochrome.android.ui.player.liquidGlass
import tf.monochrome.android.ui.player.lyricGodRays
import tf.monochrome.android.ui.player.lyricShadow
import tf.monochrome.android.ui.player.rememberLyricRayLight
import tf.monochrome.android.ui.player.rememberLyricFontFamily
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.layout.onGloballyPositioned
import tf.monochrome.android.ui.player.withLyricFont
import java.util.Locale
import javax.inject.Inject
import kotlin.math.exp
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import androidx.compose.material3.RadioButton
import kotlinx.coroutines.flow.SharingStarted
import androidx.compose.ui.res.stringResource

@HiltViewModel
class LyricsFxStudioViewModel @Inject constructor(
    private val preferences: PreferencesManager,
    @ApplicationContext private val context: Context,
    nowPlayingLyrics: tf.monochrome.android.player.NowPlayingLyricsHolder,
    queueManager: tf.monochrome.android.player.QueueManager,
) : ViewModel() {
    /** The currently-playing lyrics + position, so the preview can show them live. */
    val currentLyrics: StateFlow<tf.monochrome.android.domain.model.Lyrics?> = nowPlayingLyrics.lyrics
    val currentPositionMs: StateFlow<Long> = nowPlayingLyrics.positionMs
    /**
     * The track playing now, for the UI panels preview's mini player. Its cover
     * is what the real bar fits into its glass, so the preview shows the bar
     * the listener actually has rather than a placeholder note.
     */
    val currentTrack: StateFlow<Track?> = queueManager.currentTrack
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
    private val _playerGlass = MutableStateFlow(tf.monochrome.android.domain.model.PlayerGlassSettings.INITIAL)
    val playerGlass: StateFlow<tf.monochrome.android.domain.model.PlayerGlassSettings> = _playerGlass.asStateFlow()
    private var playerGlassTouched = false
    private var playerGlassPersistJob: Job? = null

    /** The "UI panels" tab's glass — the mini player bar and every floating panel that shares its material (its own blob, same shape as the player's). */
    private val _miniPlayerGlass = MutableStateFlow(tf.monochrome.android.domain.model.PlayerGlassSettings.INITIAL)
    val miniPlayerGlass: StateFlow<tf.monochrome.android.domain.model.PlayerGlassSettings> = _miniPlayerGlass.asStateFlow()
    private var miniPlayerGlassTouched = false
    private var miniPlayerGlassPersistJob: Job? = null

    /**
     * The ambient MilkDrop overlay's controls.
     *
     * Read straight from preferences with no working copy, unlike the glass
     * blobs above: these are written on the slider's *release*, not on every
     * frame, so there is no drag to debounce. There is nothing to preview in
     * the Studio either — the engine allows one attached surface at a time, so
     * a preview here would be a second one — and the values are cheap enough
     * to round-trip through DataStore once per gesture.
     */
    val ambient: StateFlow<AmbientVisualizerSettings> = preferences.ambientVisualizer
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AmbientVisualizerSettings())

    fun setAmbientEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setAmbientVisualizerEnabled(enabled) }
    }

    fun setAmbientOpacity(percent: Int) {
        viewModelScope.launch { preferences.setAmbientVisualizerOpacity(percent) }
    }

    fun setAmbientBlackPoint(percent: Int) {
        viewModelScope.launch { preferences.setAmbientVisualizerBlackPoint(percent) }
    }

    fun setAmbientBlend(mode: VisualizerBlendMode) {
        viewModelScope.launch { preferences.setAmbientVisualizerBlend(mode) }
    }

    fun setAmbientHideCover(hide: Boolean) {
        viewModelScope.launch { preferences.setAmbientVisualizerHideCover(hide) }
    }

    /** Fonts the user has imported (Settings › Appearance copies them here). */
    private val _availableFonts = MutableStateFlow<List<File>>(emptyList())
    val availableFonts: StateFlow<List<File>> = _availableFonts.asStateFlow()

    /** The user's own saved presets, alongside the built-in ones. */
    val customPresets: StateFlow<List<tf.monochrome.android.domain.model.LyricsFxPreset>> =
        preferences.customLyricsFxPresets.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList(),
        )

    /** The user's own saved Player Glass themes, alongside the built-in ones. */
    val customPlayerGlassPresets: StateFlow<List<tf.monochrome.android.domain.model.PlayerGlassPreset>> =
        preferences.customPlayerGlassPresets.stateIn(
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
        viewModelScope.launch {
            val mpg = preferences.miniPlayerGlass.first()
            if (!miniPlayerGlassTouched) _miniPlayerGlass.value = mpg
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

    /** Apply a Player Glass theme — changes the material, keeps the user's colour/perf. */
    fun applyPlayerGlassPreset(preset: tf.monochrome.android.domain.model.PlayerGlassSettings) {
        playerGlassTouched = true
        val next = preset.withPersonalFrom(_playerGlass.value).clamped()
        _playerGlass.value = next
        playerGlassPersistJob?.cancel()
        viewModelScope.launch { preferences.setPlayerGlass(next) }
    }

    /** Save the current Player Glass as a named theme (blank ignored, same name overwrites). */
    fun savePlayerGlassPreset(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val preset = tf.monochrome.android.domain.model.PlayerGlassPreset(clean, _playerGlass.value.clamped())
        viewModelScope.launch {
            val current = customPlayerGlassPresets.value.filterNot { it.name.equals(clean, ignoreCase = true) }
            preferences.setCustomPlayerGlassPresets(current + preset)
        }
    }

    fun deletePlayerGlassPreset(name: String) {
        viewModelScope.launch {
            preferences.setCustomPlayerGlassPresets(customPlayerGlassPresets.value.filterNot { it.name == name })
        }
    }

    /** The shareable code for a Player Glass theme (prefix + compact JSON). */
    fun exportPlayerGlassPreset(preset: tf.monochrome.android.domain.model.PlayerGlassPreset): String =
        tf.monochrome.android.domain.model.PlayerGlassPreset.encode(preset)

    /** Import a Player Glass theme from a shared code; returns the name or null. */
    fun importPlayerGlassPresetCode(code: String): String? {
        val decoded = tf.monochrome.android.domain.model.PlayerGlassPreset.decode(code) ?: return null
        val existing = customPlayerGlassPresets.value.map { it.name }.toSet()
        var name = decoded.name
        var n = 2
        while (name in existing) { name = "${decoded.name} ($n)"; n++ }
        val toAdd = decoded.copy(name = name)
        viewModelScope.launch { preferences.setCustomPlayerGlassPresets(customPlayerGlassPresets.value + toAdd) }
        return name
    }

    // ── Mini-player glass (same controls + shared theme pool as Player Glass) ──

    fun updateMiniPlayerGlass(transform: (tf.monochrome.android.domain.model.PlayerGlassSettings) -> tf.monochrome.android.domain.model.PlayerGlassSettings) {
        miniPlayerGlassTouched = true
        _miniPlayerGlass.value = transform(_miniPlayerGlass.value).clamped()
        miniPlayerGlassPersistJob?.cancel()
        miniPlayerGlassPersistJob = viewModelScope.launch {
            delay(200)
            preferences.setMiniPlayerGlass(_miniPlayerGlass.value)
        }
    }

    /** Apply a theme to the mini player — changes the material, keeps colour/perf. */
    fun applyMiniPlayerGlassPreset(preset: tf.monochrome.android.domain.model.PlayerGlassSettings) {
        miniPlayerGlassTouched = true
        val next = preset.withPersonalFrom(_miniPlayerGlass.value).clamped()
        _miniPlayerGlass.value = next
        miniPlayerGlassPersistJob?.cancel()
        viewModelScope.launch { preferences.setMiniPlayerGlass(next) }
    }

    /** Save the current mini-player glass as a named theme (into the shared pool). */
    fun saveMiniPlayerGlassPreset(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val preset = tf.monochrome.android.domain.model.PlayerGlassPreset(clean, _miniPlayerGlass.value.clamped())
        viewModelScope.launch {
            val current = customPlayerGlassPresets.value.filterNot { it.name.equals(clean, ignoreCase = true) }
            preferences.setCustomPlayerGlassPresets(current + preset)
        }
    }

    /**
     * Make the lyric letters from the player's own glass ("Match player glass").
     * The player blob is the one the transport is drawn in, so this is the
     * material the listener sees right under the lyrics.
     */
    fun matchPlayerGlass() {
        update { it.withGlassOpticsFrom(_playerGlass.value) }
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
 * Player Visuals Studio — live sliders for the lyric renderer (typography, 3D
 * wave, beat engine, glow) plus the player and mini-player glass, over a
 * self-animating preview. The preview drives the same visual pipeline the player
 * uses from a synthetic beat, so tuning works without any music playing; the
 * readout reports the current beat reactivity rather than a fixed tempo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LyricsFxStudioScreen(
    navController: NavController,
    viewModel: LyricsFxStudioViewModel = hiltViewModel(),
) {
    val fx by viewModel.fx.collectAsStateWithLifecycle()
    val fonts by viewModel.availableFonts.collectAsStateWithLifecycle()
    val currentLyrics by viewModel.currentLyrics.collectAsStateWithLifecycle()
    val customPresets by viewModel.customPresets.collectAsStateWithLifecycle()
    val playerGlass by viewModel.playerGlass.collectAsStateWithLifecycle()
    val miniPlayerGlass by viewModel.miniPlayerGlass.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Which studio tab: 0 = Player, 1 = UI panels, 2 = Lyrics.
    // rememberSaveable so it doesn't snap back to Lyrics after background
    // process death.
    var selectedTab by rememberSaveable { mutableStateOf(0) }

    // Preset save / import / share dialog state.
    var showSaveDialog by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var presetAction by remember { mutableStateOf<LyricsFxPreset?>(null) }

    // Re-list imported fonts whenever the custom-font toggle turns on, so a font
    // imported in Settings › Appearance since this screen opened shows up.
    LaunchedEffect(fx.customFont) { if (fx.customFont) viewModel.refreshFonts() }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.fx_player_visuals_studio)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.Transparent,
        ) {
            // Player chrome first, then the glass shared by every floating
            // panel, then lyrics. "UI panels" rather than "Mini Player"
            // because this blob stopped being just the bar: it is the material
            // for the audio-tools sheet, the speed panel, the nav pill, the
            // search bar and the map panels too.
            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text(stringResource(R.string.fx_player)) })
            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text(stringResource(R.string.fx_ui_panels)) })
            Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text(stringResource(R.string.mode_lyrics)) })
            // Not glass at all, unlike the other three — this is the player's
            // background and its visualizer engine. It lives here because the
            // screen is the Player Visuals Studio, and because there is nowhere
            // else that a change to what the player looks like belongs.
            Tab(selected = selectedTab == 3, onClick = { selectedTab = 3 }, text = { Text(stringResource(R.string.visualizer)) })
        }

        // With the glass switched off app-wide, every control on these tabs still
        // edits and saves, but nothing renders it. Say so, rather than letting
        // the Studio read as broken.
        if (tf.monochrome.android.performance.LocalLowPerformance.current.disableLiquidGlass) {
            Text(
                text = stringResource(R.string.fx_liquid_glass_is_off_in_settings_system),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (selectedTab == 3) {
            val ambient by viewModel.ambient.collectAsStateWithLifecycle()
            // The rest of the visualizer's controls (spectrum, engine,
            // graphics, preset rotation) read the main SettingsViewModel —
            // this tab hosts them now, so pull that VM in alongside.
            val settingsViewModel: SettingsViewModel = hiltViewModel()
            // Reserve the mini player's height inside the scroll, the same way
            // the Lyrics tab does below. Without it the last control on this
            // tab sits under the bar permanently: the scroll ends level with
            // the screen, so there is nothing left to scroll it clear with.
            val visualizerNavBar =
                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
            // The preset library opens over this tab as the player's glass
            // browser. It is drawn here, beside the scroll it covers rather
            // than inside it, so the sheet frosts this tab's own content: the
            // column below is the haze source and the panel its sibling.
            var showPresetBrowser by rememberSaveable { mutableStateOf(false) }
            val browserHaze = rememberHazeState()
            Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(browserHaze)
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 8.dp + LocalBottomChromeInset.current + visualizerNavBar,
                    ),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.fx_ambient_visualizer),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            stringResource(R.string.fx_milkdrop_drawn_into_the_album_background_over),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ambient.enabled, onCheckedChange = viewModel::setAmbientEnabled)
                }

                if (ambient.enabled) {
                    // Sliders write on release: each change is a DataStore
                    // round trip, and the preview lives on the player, not
                    // here — the engine can only drive one surface at a time.
                    var opacity by remember(ambient.opacityPercent) {
                        mutableFloatStateOf(ambient.opacityPercent.toFloat())
                    }
                    var blackPoint by remember(ambient.blackPointPercent) {
                        mutableFloatStateOf(ambient.blackPointPercent.toFloat())
                    }
                    FxSlider(
                        label = stringResource(R.string.fx_visualizer_opacity),
                        valueLabel = "${opacity.toInt()}%",
                        value = opacity,
                        range = 0f..100f,
                        description = stringResource(R.string.fx_how_much_of_the_preset_reaches_the_screen_at_all),
                        onChange = { opacity = it },
                        onChangeFinished = { viewModel.setAmbientOpacity(opacity.toInt()) },
                    )
                    FxSlider(
                        label = stringResource(R.string.fx_black_transparency),
                        valueLabel = "${blackPoint.toInt()}%",
                        value = blackPoint,
                        range = 0f..AmbientVisualizerSettings.MAX_BLACK_POINT.toFloat(),
                        description = stringResource(R.string.fx_how_much_of_the_dark_end_disappears_higher_hides),
                        onChange = { blackPoint = it },
                        onChangeFinished = { viewModel.setAmbientBlackPoint(blackPoint.toInt()) },
                    )

                    Text(
                        stringResource(R.string.fx_blend_mode),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        stringResource(R.string.fx_screen_is_the_default_black_contributes_nothing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(modifier = Modifier.padding(top = 8.dp)) {
                        VisualizerBlendMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.setAmbientBlend(mode) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = ambient.blend == mode,
                                    // The whole row is the target; a radio that
                                    // also handles the click double-fires on some
                                    // versions.
                                    onClick = null,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(stringResource(blendLabel(mode)), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 16.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.fx_remove_album_cover),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                stringResource(R.string.fx_hide_the_square_artwork_while_ambient_is_on),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = ambient.hideCover,
                            onCheckedChange = viewModel::setAmbientHideCover,
                        )
                    }
                }

                VisualizerSettings(
                    settingsViewModel,
                    onOpenPresetBrowser = { showPresetBrowser = true },
                )
            }
            val presets by settingsViewModel.visualizerPresets.collectAsStateWithLifecycle()
            val presetId by settingsViewModel.visualizerPresetId.collectAsStateWithLifecycle()
            val favorites by settingsViewModel.visualizerFavoritePresetIds.collectAsStateWithLifecycle()
            val flagged by settingsViewModel.visualizerFlaggedPresetIds.collectAsStateWithLifecycle()
            VisualizerPresetPanel(
                visible = showPresetBrowser,
                presets = presets,
                selectedPresetId = presetId,
                favoritePresetIds = favorites,
                flaggedPresetIds = flagged,
                onPresetSelected = { settingsViewModel.setVisualizerPresetId(it.id) },
                onToggleFavorite = settingsViewModel::toggleVisualizerFavoritePreset,
                // Already in the settings it would open.
                onSettingsClick = null,
                onDismiss = { showPresetBrowser = false },
                hazeState = browserHaze,
                title = stringResource(R.string.fx_default_preset),
                autoOption = tf.monochrome.android.ui.player.PresetAutoOption(
                    label = stringResource(R.string.fx_auto_select),
                    description = stringResource(R.string.fx_let_the_visualizer_pick_a_bundled_preset_when_it),
                    onSelect = { settingsViewModel.setVisualizerPresetId(null) },
                ),
            )
            }
            return@Column
        }

        if (selectedTab == 0 || selectedTab == 1) {
            // Both glass tabs share the same controls, preview, and theme pool;
            // only which settings blob they edit differs.
            val customGlassPresets by viewModel.customPlayerGlassPresets.collectAsStateWithLifecycle()
            if (selectedTab == 0) {
                PlayerGlassTab(
                    glass = playerGlass,
                    customPresets = customGlassPresets,
                    onUpdate = { viewModel.updatePlayerGlass(it) },
                    onApplyPreset = { viewModel.applyPlayerGlassPreset(it) },
                    onSavePreset = { viewModel.savePlayerGlassPreset(it) },
                    onDeletePreset = { viewModel.deletePlayerGlassPreset(it) },
                    onExportPreset = { viewModel.exportPlayerGlassPreset(it) },
                    onImportPreset = { viewModel.importPlayerGlassPresetCode(it) },
                )
            } else {
                val previewTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
                PlayerGlassTab(
                    glass = miniPlayerGlass,
                    customPresets = customGlassPresets,
                    onUpdate = { viewModel.updateMiniPlayerGlass(it) },
                    onApplyPreset = { viewModel.applyMiniPlayerGlassPreset(it) },
                    onSavePreset = { viewModel.saveMiniPlayerGlassPreset(it) },
                    onDeletePreset = { viewModel.deletePlayerGlassPreset(it) },
                    onExportPreset = { viewModel.exportPlayerGlassPreset(it) },
                    onImportPreset = { viewModel.importPlayerGlassPresetCode(it) },
                    previewMini = true,
                    previewTrack = previewTrack,
                )
            }
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
                    stringResource(R.string.visualizer_presets),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showSaveDialog = true }) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_save))
                }
                TextButton(onClick = { showImportDialog = true }) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.action_import))
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
                                contentDescription = null,
                                // Chip height caps the target, but padding
                                // before the click enlarges it past the bare
                                // 16dp glyph, and the label/role make it a
                                // findable button for TalkBack.
                                modifier = Modifier
                                    .buttonSemantics(label = stringResource(R.string.fx_manage_named, saved.name))
                                    .clickable { presetAction = saved }
                                    .padding(4.dp)
                                    .size(16.dp),
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
                title = { Text(stringResource(R.string.fx_save_preset)) },
                text = {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.fx_preset_name)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = name.isNotBlank(),
                        onClick = {
                            viewModel.saveCurrentAsPreset(name)
                            showSaveDialog = false
                            android.widget.Toast.makeText(context, context.getString(R.string.fx_saved_named, name.trim()), android.widget.Toast.LENGTH_SHORT).show()
                        },
                    ) { Text(stringResource(R.string.action_save)) }
                },
                dismissButton = { TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }

        // ── Import a shared preset code ──────────────────────────────────────
        if (showImportDialog) {
            var code by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showImportDialog = false },
                title = { Text(stringResource(R.string.fx_import_preset)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.fx_paste_a_preset_code_someone_shared_with_you),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = code,
                            onValueChange = { code = it },
                            label = { Text(stringResource(R.string.fx_preset_code)) },
                            modifier = Modifier.fillMaxWidth().height(140.dp),
                        )
                        TextButton(onClick = {
                            val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                            val primary = clip?.primaryClip
                            if (primary != null && primary.itemCount > 0) {
                                primary.getItemAt(0).coerceToText(context)?.let { code = it.toString() }
                            }
                        }) { Text(stringResource(R.string.fx_paste_from_clipboard)) }
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
                                if (imported != null) context.getString(R.string.fx_imported_named, imported) else context.getString(R.string.fx_invalid_preset_code),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        },
                    ) { Text(stringResource(R.string.action_import)) }
                },
                dismissButton = { TextButton(onClick = { showImportDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
            )
        }

        // ── Share / delete a saved preset ────────────────────────────────────
        presetAction?.let { target ->
            AlertDialog(
                onDismissRequest = { presetAction = null },
                title = { Text(target.name) },
                text = { Text(stringResource(R.string.fx_share_this_preset_or_remove_it_from_your_list)) },
                confirmButton = {
                    TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.fx_preset_subject, target.name))
                            putExtra(android.content.Intent.EXTRA_TEXT, viewModel.exportPreset(target))
                        }
                        context.startActivity(android.content.Intent.createChooser(send, context.getString(R.string.fx_share_preset)))
                        presetAction = null
                    }) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.fx_share))
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
                            Text(stringResource(R.string.action_delete))
                        }
                        TextButton(onClick = { presetAction = null }) { Text(stringResource(R.string.action_close)) }
                    }
                },
            )
        }

        // The tail reserves the mini player's height inside the scroll rather
        // than outside the screen. Reserved outside, the strip behind the bar
        // was flat theme background and the bar's glass had nothing but a solid
        // colour to lens; reserved here it is scrollable, so the sliders pass
        // behind the glass and the last one still comes clear of it.
        val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 48.dp + LocalBottomChromeInset.current + navBar,
            ),
        ) {
            item {
                StudioSection(stringResource(R.string.fx_typography))
                FxSlider(stringResource(R.string.fx_font_size), "%.0f sp".format(fx.fontSizeSp), fx.fontSizeSp, 14f..34f) {
                    viewModel.update { s -> s.copy(fontSizeSp = it) }
                }
                FxSlider(stringResource(R.string.fx_letter_spacing), "%.2f sp".format(fx.letterSpacingSp), fx.letterSpacingSp, -1f..1f) {
                    viewModel.update { s -> s.copy(letterSpacingSp = it) }
                }
                FxSlider(
                    stringResource(R.string.fx_edge_margin), "%.0f dp".format(fx.edgeMarginDp), fx.edgeMarginDp, 0f..48f,
                    description = stringResource(R.string.fx_side_spacing_between_the_lyrics_and_the_screen),
                ) { viewModel.update { s -> s.copy(edgeMarginDp = it) } }
                FxSlider(
                    stringResource(R.string.fx_lines_per_block), "${fx.maxWrapLines}" + if (fx.maxWrapLines == 1) " (no wrap)" else "",
                    fx.maxWrapLines.toFloat(), 1f..3f, steps = 1,
                    description = stringResource(R.string.fx_how_many_rows_a_long_line_may_wrap_to_before_it),
                ) { viewModel.update { s -> s.copy(maxWrapLines = it.toInt()) } }
            }

            item {
                StudioSection(stringResource(R.string.fx_font))
                FxToggle(
                    stringResource(R.string.fx_custom_lyrics_font), fx.customFont,
                    description = stringResource(R.string.fx_use_one_of_your_imported_fonts_for_the_lyrics),
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
                StudioSection(stringResource(R.string.fx_playback_sync))
                FxSlider(
                    stringResource(R.string.fx_bluetooth_sync_delay),
                    "%+d ms".format(fx.bluetoothDelayMs.toInt()),
                    fx.bluetoothDelayMs, -500f..1500f, steps = 39,
                    description = stringResource(R.string.fx_delays_synced_lyrics_to_line_up_with_bluetooth),
                ) { viewModel.update { s -> s.copy(bluetoothDelayMs = it) } }
            }

            item {
                StudioSection(stringResource(R.string.fx_3d_letter_wave))
                FxSlider(
                    stringResource(R.string.fx_tilt), "%.0f°".format(fx.rotationDegrees) + if (fx.rotationDegrees < 0.5f) " (off)" else "",
                    fx.rotationDegrees, 0f..25f,
                ) { viewModel.update { s -> s.copy(rotationDegrees = it) } }
                FxSlider(stringResource(R.string.fx_wave_speed), "%.2fx".format(fx.waveSpeed), fx.waveSpeed, 0.25f..3f) {
                    viewModel.update { s -> s.copy(waveSpeed = it) }
                }
                FxSlider(
                    stringResource(R.string.fx_wave_tightness), "%.2f rad/letter".format(fx.wavePhaseStep),
                    fx.wavePhaseStep, 0.05f..0.9f,
                    description = stringResource(R.string.fx_low_one_smooth_ribbon_high_choppy_per_letter),
                ) { viewModel.update { s -> s.copy(wavePhaseStep = it) } }
                FxSlider(stringResource(R.string.fx_wave_travel), "%.1f dp".format(fx.waveTravelDp), fx.waveTravelDp, 0f..8f) {
                    viewModel.update { s -> s.copy(waveTravelDp = it) }
                }
                FxSlider(
                    stringResource(R.string.fx_shadow_depth), "${(fx.shadowDepth * 100).toInt()}%", fx.shadowDepth, 0f..1f,
                    description = stringResource(R.string.fx_lyric_shadow_desc),
                ) { viewModel.update { s -> s.copy(shadowDepth = it) } }
            }

            item {
                StudioSection(stringResource(R.string.fx_beat_engine))
                FxSlider(
                    stringResource(R.string.fx_bass_reaction), "${(fx.bassReact * 100).toInt()}%" + if (fx.bassReact < 0.01f) " (off)" else "",
                    fx.bassReact, 0f..1f,
                    description = stringResource(R.string.fx_master_intensity_for_pump_and_glow),
                ) { viewModel.update { s -> s.copy(bassReact = it) } }
                FxSlider(stringResource(R.string.fx_pump_amount), "+${(fx.pumpAmount * 100).toInt()}%", fx.pumpAmount, 0f..0.25f) {
                    viewModel.update { s -> s.copy(pumpAmount = it) }
                }
                FxSlider(
                    stringResource(R.string.fx_attack), "%.0f ms".format(fx.attackMs), fx.attackMs, 4f..60f,
                    description = stringResource(R.string.fx_how_fast_the_pulse_snaps_onto_a_kick),
                ) { viewModel.update { s -> s.copy(attackMs = it) } }
                FxSlider(
                    stringResource(R.string.fx_release), "%.0f ms".format(fx.releaseMs), fx.releaseMs, 40f..500f,
                    description = stringResource(R.string.fx_how_long_the_pulse_holds_through_a_kick),
                ) { viewModel.update { s -> s.copy(releaseMs = it) } }
                FxSlider(
                    stringResource(R.string.fx_bounce), "${(fx.bounce * 100).toInt()}%", fx.bounce, 0f..1f,
                    description = stringResource(R.string.fx_spring_overshoot_0_tracks_stiffly_100_rings_like),
                ) { viewModel.update { s -> s.copy(bounce = it) } }
            }

            item {
                StudioSection(stringResource(R.string.fx_glow))
                FxSlider(stringResource(R.string.settings_glow_radius), "+%.0f dp".format(fx.glowRadiusDp), fx.glowRadiusDp, 0f..160f) {
                    viewModel.update { s -> s.copy(glowRadiusDp = it) }
                }
                FxSlider(stringResource(R.string.settings_glow_brightness), "${(fx.glowBrightness * 100).toInt()}%", fx.glowBrightness, 0f..0.6f) {
                    viewModel.update { s -> s.copy(glowBrightness = it) }
                }
                // "Glow behind album art" is a personal toggle, not a theme
                // material knob — it lives in Settings › Appearance under
                // Dynamic Colors now.
            }

            item {
                StudioSection(stringResource(R.string.fx_liquid_glass))
                FxToggle(
                    stringResource(R.string.fx_liquid_glass_toggle), fx.liquidGlass,
                    description = stringResource(R.string.fx_refractive_glass_relight_of_the_lyric_surface),
                ) { viewModel.update { s -> s.copy(liquidGlass = it) } }
                FxSlider(
                    stringResource(R.string.fx_glass_opacity), "${(fx.glassBodyOpacity * 100).toInt()}%",
                    fx.glassBodyOpacity, 0.2f..1f,
                    description = stringResource(R.string.fx_lower_lets_more_of_the_backdrop_read_through_the),
                ) { viewModel.update { s -> s.copy(glassBodyOpacity = it) } }
                FxSlider(
                    stringResource(R.string.fx_refraction), "%.2f".format(fx.glassRefraction), fx.glassRefraction, 0f..0.4f,
                    description = stringResource(R.string.fx_how_hard_the_beveled_edges_lens_the_backdrop),
                ) { viewModel.update { s -> s.copy(glassRefraction = it) } }
                FxSlider(
                    stringResource(R.string.fx_edge_highlight), "${(fx.glassRimBrightness * 100).toInt()}%",
                    fx.glassRimBrightness, 0f..2f,
                    description = stringResource(R.string.fx_brightness_of_the_specular_glass_rim),
                ) { viewModel.update { s -> s.copy(glassRimBrightness = it) } }
                FxSlider(
                    stringResource(R.string.fx_chromatic_aberration), "${(fx.glassDispersion * 100).toInt()}%",
                    fx.glassDispersion, 0f..2f,
                    description = stringResource(R.string.fx_colour_fringing_where_the_edges_refract),
                ) { viewModel.update { s -> s.copy(glassDispersion = it) } }
                FxSlider(
                    stringResource(R.string.fx_bevel_samples), "${1 + 4 * fx.glassSampleRings} taps/px",
                    fx.glassSampleRings.toFloat(), 1f..3f, steps = 1,
                    description = stringResource(R.string.fx_shader_taps_per_pixel_higher_smoother_glass),
                ) { viewModel.update { s -> s.copy(glassSampleRings = it.toInt()) } }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.matchPlayerGlass() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.fx_match_player_glass)) }
                Text(
                    text = stringResource(R.string.fx_match_player_glass_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // The Player Glass optics, on the letters. Same labels and ranges
            // as that tab, so a value means the same thing on both.
            item {
                StudioSection(stringResource(R.string.fx_shape_bevel))
                FxSlider(
                    stringResource(R.string.fx_roundness), "%.2f".format(fx.glassRoundness), fx.glassRoundness, 0.5f..2f,
                    description = stringResource(R.string.fx_rolls_the_glass_edge_from_a_sharp_bevel_to_a),
                ) { viewModel.update { s -> s.copy(glassRoundness = it) } }
                FxSlider(
                    stringResource(R.string.fx_depth_profondeur), "%.2f".format(fx.glassDepth), fx.glassDepth, 0.5f..2f,
                    description = stringResource(R.string.fx_lyric_depth_desc),
                ) { viewModel.update { s -> s.copy(glassDepth = it) } }
                FxSlider(
                    stringResource(R.string.fx_frosted_blur), "${(fx.glassFrost * 100).toInt()}%", fx.glassFrost, 0f..1f,
                    description = stringResource(R.string.fx_frosts_the_glass_from_clear_to_misted),
                ) { viewModel.update { s -> s.copy(glassFrost = it) } }
            }

            item {
                StudioSection(stringResource(R.string.fx_light_reflections))
                FxSlider(
                    stringResource(R.string.fx_light_angle), "${fx.glassLightAngleDeg.toInt()}°", fx.glassLightAngleDeg, 0f..360f,
                    description = stringResource(R.string.fx_direction_the_key_light_comes_from_and_where_the),
                ) { viewModel.update { s -> s.copy(glassLightAngleDeg = it) } }
                FxSlider(
                    stringResource(R.string.fx_tilt_reactivity), "${(fx.glassTiltReactivity * 100).toInt()}%",
                    fx.glassTiltReactivity, 0f..1.5f,
                    description = stringResource(R.string.fx_how_strongly_tilting_the_phone_moves_the_light),
                ) { viewModel.update { s -> s.copy(glassTiltReactivity = it) } }
                FxSlider(
                    stringResource(R.string.fx_edge_width), "${(fx.glassEdgeWidth * 100).toInt()}%", fx.glassEdgeWidth, 0f..1f,
                    description = stringResource(R.string.fx_reflective_rim_thin_crisp_edge_to_a_broad_glassy),
                ) { viewModel.update { s -> s.copy(glassEdgeWidth = it) } }
                FxSlider(
                    stringResource(R.string.fx_reflection), "${(fx.glassReflection * 100).toInt()}%", fx.glassReflection, 0f..2f,
                    description = stringResource(R.string.fx_how_much_of_the_room_environment_reflection),
                ) { viewModel.update { s -> s.copy(glassReflection = it) } }
                FxSlider(
                    stringResource(R.string.fx_gloss), "${(fx.glassGloss * 100).toInt()}%", fx.glassGloss, 0f..1f,
                    description = stringResource(R.string.fx_highlight_polish_soft_frosted_wide_glint_to_a),
                ) { viewModel.update { s -> s.copy(glassGloss = it) } }
                FxSlider(
                    stringResource(R.string.fx_surface_motion), "${(fx.glassSurfaceMotion * 100).toInt()}%",
                    fx.glassSurfaceMotion, 0f..1f,
                    description = stringResource(R.string.fx_swell_edge_ripple_and_glint_on_the_glass_surface),
                ) { viewModel.update { s -> s.copy(glassSurfaceMotion = it) } }
            }

            item {
                GodRaysControls(fx = fx, onUpdate = { viewModel.update(it) })
            }

            item {
                StudioSection(stringResource(R.string.fx_anti_aliasing))
                FxToggle(
                    stringResource(R.string.fx_anti_aliasing_fxaa), fx.fxaa,
                    description = stringResource(R.string.fx_smooths_jagged_edges_on_the_3d_letters_and_glass),
                ) { viewModel.update { s -> s.copy(fxaa = it) } }
                if (fx.fxaa) {
                    FxSlider(
                        stringResource(R.string.fx_aa_strength), "${(fx.fxaaStrength * 100).toInt()}%",
                        fx.fxaaStrength, 0f..1f,
                        description = stringResource(R.string.fx_how_hard_edges_are_smoothed_higher_softens_more),
                    ) { viewModel.update { s -> s.copy(fxaaStrength = it) } }
                }
            }

            item {
                Spacer(Modifier.height(20.dp))
                OutlinedButton(
                    onClick = { viewModel.applyPreset(LyricsFxSettings.DEFAULT) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.fx_reset_to_defaults)) }
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
    customPresets: List<PlayerGlassPreset>,
    onUpdate: ((PlayerGlassSettings) -> PlayerGlassSettings) -> Unit,
    onApplyPreset: (PlayerGlassSettings) -> Unit,
    onSavePreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit,
    onExportPreset: (PlayerGlassPreset) -> String,
    onImportPreset: (String) -> String?,
    previewMini: Boolean = false,
    /** The playing track, for the UI panels preview's mini player; null shows a sample. */
    previewTrack: Track? = null,
) {
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    // Custom preview colours (0 = use the current album colour).
    val previewTint = if (glass.tintColor != 0) Color(glass.tintColor) else accent
    val previewBgBrush = if (glass.previewBg != 0) SolidColor(Color(glass.previewBg)) else previewBackground(accent)
    // The swatch above as ONE colour, for the frost to ask which way to wash.
    // Here the chrome is over whatever the listener picked — possibly white —
    // so the player's own near-black ground would lay dark frost on a light
    // backdrop. The gradient runs accent 0.34 to 0.10 out of black; its
    // midpoint stands for it.
    val previewGround = if (glass.previewBg != 0) Color(glass.previewBg)
        else lerp(Color.Black, accent, 0.22f)
    // The page under the UI panels preview. Those panes float over the app's
    // own pages, not the player, so unless the listener picked a colour it is
    // the theme's page colour rather than the player's accent wash.
    val panelsPage = if (glass.previewBg != 0) Color(glass.previewBg)
        else MaterialTheme.colorScheme.background
    var showBgPicker by remember { mutableStateOf(false) }
    var showTintPicker by remember { mutableStateOf(false) }
    // Theme save / import / share dialog state (mirrors the Lyrics preset system).
    var showGlassSaveDialog by remember { mutableStateOf(false) }
    var showGlassImportDialog by remember { mutableStateOf(false) }
    var glassPresetAction by remember { mutableStateOf<PlayerGlassPreset?>(null) }
    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned preview — stays locked in view while you tune the sliders,
        // just like the Lyrics editor.
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(12.dp))
        val previewHaze = rememberHazeState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (previewMini) UiPanelsPreviewHeight else 288.dp)
                .clip(RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (previewMini) {
                // This tab's material has two faces and the preview shows both:
                // the floating pane (search bars, sheets, the map panels) and the
                // bottom chrome (the mini player and the tab bar). The pane used
                // to be previewed on the Player tab, wrapped around the transport,
                // which put the one thing those sliders do NOT control behind
                // everything they do. UiPanelsPreview says how it is built now.
                UiPanelsPreview(
                    glass = glass,
                    hazeState = previewHaze,
                    page = panelsPage,
                    track = previewTrack,
                )
            } else {
            // Live preview: the real transport buttons AND the action dock under the
            // current button glass — the dock is the same hollowed-slab glass, so it
            // tunes with these sliders exactly like the play button.
            // The backdrop is a sibling of the pane above it, not its parent, so the
            // pane can actually blur it — a haze effect cannot sample a layer it is
            // drawn inside, and one that tries paints the source's flat colour.
            Box(
                Modifier
                    .matchParentSize()
                    .hazeSource(previewHaze)
                    .background(previewBgBrush),
            )
            // The preview's own backdrop, published to the chrome inside it,
            // so the dock and the play disc frost something here exactly as
            // they frost the player's background on the real screen. Without
            // it Backdrop blur and Backdrop tint were two sliders whose effect
            // you had to leave the Studio to see.
            //
            // Safe from the sample-your-own-layer trap: the source is the
            // swatch above, which is a sibling of this content and not an
            // ancestor of it.
            CompositionLocalProvider(
                LocalPlayerGlass provides glass,
                LocalPlayerHaze provides previewHaze,
                // Beside the haze it belongs to, so the dock's own frost
                // gets it too.
                LocalPlayerGlassGround provides previewGround,
            ) {
                // The transport as it actually sits on the player screen: straight
                // over the backdrop, with nothing between. There was a GlassPanel
                // here, on the argument that glass should be previewed over
                // something rather than on a flat swatch — but the real player has
                // no pane behind its transport (the invariants forbid one under a
                // punched slab), and the pane it drew was the UI panels blob,
                // tuned on the other tab. The swatch behind is the something.
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
                            painterResource(R.drawable.ic_glass_skip_previous_chevron), stringResource(R.string.action_previous), previewTint, {},
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
                            // The same frost and live lens the real disc gets
                            // (PlayerTransportControls), so Backdrop blur, tint
                            // and the lens move something here too, and the
                            // disc reads the same as the dock beside it.
                            PlayerGlassHaze(
                                modifier = Modifier.matchParentSize(),
                                shape = CircleShape,
                                lensCorner = Dp.Infinity,
                            )
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Canvas(
                                    Modifier
                                        .fillMaxSize()
                                        .playerGlass(
                                            previewTint,
                                            lensCorner = Dp.Infinity,
                                            liveUnder = rememberPlayerLiveLens(),
                                        )
                                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                                ) {
                                    drawGlassPlayPauseDisc(morph = 0f, fill = previewTint)
                                }
                            }
                        }
                        TransportIcon(
                            painterResource(R.drawable.ic_glass_skip_next_chevron), stringResource(R.string.action_next), previewTint, {},
                            size = PlayerDesignTokens.SkipIconSize,
                        )
                    }
                    // The real hollowed-slab dock, previewed under the same glass.
                    PlayerActionDock(
                        accent = accent,
                        lyricsActive = false,
                        shuffleActive = false,
                        onLyrics = {},
                        onShuffle = {},
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
                text = stringResource(R.string.fx_preview),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.35f),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            )
            }
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
        // ── Themes: built-in glass materials + the user's saved ones ─────────
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.fx_themes),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { showGlassSaveDialog = true }) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_save))
            }
            TextButton(onClick = { showGlassImportDialog = true }) {
                Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.action_import))
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlayerGlassSettings.PRESETS.forEach { (name, preset) ->
                FilterChip(
                    selected = glass.matchesPreset(preset),
                    onClick = { onApplyPreset(preset) },
                    label = { Text(name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
            // The user's own saved themes. Tapping applies; the trailing icon
            // opens a Share / Delete sheet for that theme.
            customPresets.forEach { saved ->
                FilterChip(
                    selected = glass.matchesPreset(saved.settings),
                    onClick = { onApplyPreset(saved.settings) },
                    label = { Text(saved.name) },
                    trailingIcon = {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = stringResource(R.string.fx_manage_named, saved.name),
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { glassPresetAction = saved },
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                )
            }
        }

        // Colour swatches: preview background + button glass tint, each "current"
        // (album) or a custom colour picked from the bubble.
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            ColorSwatch(
                label = stringResource(R.string.fx_background),
                color = when {
                    previewMini -> panelsPage
                    glass.previewBg != 0 -> Color(glass.previewBg)
                    else -> lerp(Color.Black, accent, 0.34f)
                },
                isCustom = glass.previewBg != 0,
                onClick = { showBgPicker = true },
            )
            ColorSwatch(
                label = stringResource(R.string.fx_button_tint),
                color = previewTint,
                isCustom = glass.tintColor != 0,
                onClick = { showTintPicker = true },
            )
        }
        Spacer(Modifier.height(14.dp))
        // Controls are grouped by what they affect, in paint order: what the
        // glass IS (body), its 3D form (shape & bevel), how light plays on it
        // (light & reflections), what it casts (drop shadow), then render cost.
        StudioSection(if (previewMini) stringResource(R.string.fx_ui_panels) else stringResource(R.string.fx_player_glass))
        FxToggle(
            stringResource(R.string.fx_button_liquid_glass), glass.enabled,
            description = stringResource(R.string.fx_3d_refractive_glass_on_the_transport_buttons),
        ) { onUpdate { g -> g.copy(enabled = it) } }
        if (previewMini) {
            // The mini player's progress line doubles as the bar's top border,
            // so this is as much a framing choice as a readout one — off, the
            // bar loses that hairline edge and closes up by its 2dp.
            FxToggle(
                stringResource(R.string.fx_mini_player_progress_bar), glass.miniProgressBar,
                description = stringResource(R.string.fx_thin_progress_line_along_the_top_edge_of_the),
            ) { onUpdate { g -> g.copy(miniProgressBar = it) } }
        } else {
            FxToggle(
                stringResource(R.string.fx_glass_progress_bar), glass.progressGlass,
                description = stringResource(R.string.fx_thin_glass_tube_scrubber_that_fills_up_with_a),
            ) { onUpdate { g -> g.copy(progressGlass = it) } }
        }

        StudioSection(stringResource(R.string.fx_glass_body))
        FxSlider(
            stringResource(R.string.fx_glass_opacity), "${(glass.bodyOpacity * 100).toInt()}%", glass.bodyOpacity, 0f..1f,
            description = stringResource(R.string.fx_lower_makes_the_buttons_more_see_through_0_body),
        ) { onUpdate { g -> g.copy(bodyOpacity = it) } }
        FxSlider(
            stringResource(R.string.fx_frosted_blur), "${(glass.frost * 100).toInt()}%", glass.frost, 0f..1f,
            description = stringResource(R.string.fx_frosts_the_glass_from_clear_to_misted),
        ) { onUpdate { g -> g.copy(frost = it) } }
        // Haze backdrop frost. Each tab's copy drives its own surfaces: the UI
        // blob frosts the mini player bar, the audio-tools sheet, the speed
        // panel, the nav pill, the search bar and the map panels; the player
        // blob frosts the now-playing chrome -- the action dock and the play
        // disc, whose punched glyphs open onto this blur.
        //
        // These lived on the UI tab alone for as long as the player's own copy
        // drove nothing, which made them look like panel settings that the
        // player was simply not entitled to. It has surfaces of its own now, so
        // it gets the sliders that aim them.
        FxSlider(
            stringResource(R.string.fx_backdrop_blur), "%.0f dp".format(glass.hazeBlurDp), glass.hazeBlurDp, 0f..80f,
            description = if (previewMini) {
                stringResource(R.string.fx_backdrop_blur_panels)
            } else {
                stringResource(R.string.fx_backdrop_blur_player)
            },
        ) { onUpdate { g -> g.copy(hazeBlurDp = it) } }
        FxSlider(
            stringResource(R.string.fx_backdrop_tint), "${(glass.hazeTint * 100).toInt()}%", glass.hazeTint, 0f..2f,
            description = stringResource(R.string.fx_strength_of_the_frost_layer_s_darkening),
        ) { onUpdate { g -> g.copy(hazeTint = it) } }
        FxSlider(
            stringResource(R.string.fx_surface_motion), "${(glass.surfaceMotion * 100).toInt()}%", glass.surfaceMotion, 0f..1f,
            description = stringResource(R.string.fx_swell_edge_ripple_and_glint_on_the_glass_surface),
        ) { onUpdate { g -> g.copy(surfaceMotion = it) } }

        StudioSection(stringResource(R.string.fx_shape_bevel))
        FxSlider(
            stringResource(R.string.fx_roundness), "%.2f".format(glass.roundness), glass.roundness, 0.5f..2f,
            description = stringResource(R.string.fx_rolls_the_glass_edge_from_a_sharp_bevel_to_a),
        ) { onUpdate { g -> g.copy(roundness = it) } }
        FxSlider(
            stringResource(R.string.fx_depth_profondeur), "%.2f".format(glass.depth), glass.depth, 0.5f..2f,
            description = stringResource(R.string.fx_how_thick_and_deep_the_relief_reads_higher_pops),
        ) { onUpdate { g -> g.copy(depth = it) } }
        FxSlider(
            stringResource(R.string.fx_refraction), "%.2f".format(glass.refraction), glass.refraction, 0f..0.4f,
            description = stringResource(R.string.fx_how_hard_the_beveled_edges_lens_the_backdrop),
        ) { onUpdate { g -> g.copy(refraction = it) } }
        FxSlider(
            stringResource(R.string.fx_chromatic_aberration), "${(glass.dispersion * 100).toInt()}%", glass.dispersion, 0f..2f,
            description = stringResource(R.string.fx_colour_fringing_at_the_refracting_edges),
        ) { onUpdate { g -> g.copy(dispersion = it) } }

        StudioSection(stringResource(R.string.fx_light_reflections))
        FxSlider(
            stringResource(R.string.fx_light_angle), "${glass.lightAngleDeg.toInt()}°", glass.lightAngleDeg, 0f..360f,
            description = stringResource(R.string.fx_direction_the_key_light_comes_from_and_where_the),
        ) { onUpdate { g -> g.copy(lightAngleDeg = it) } }
        FxSlider(
            stringResource(R.string.fx_tilt_reactivity), "${(glass.tiltReactivity * 100).toInt()}%", glass.tiltReactivity, 0f..1.5f,
            description = stringResource(R.string.fx_how_strongly_tilting_the_phone_moves_the_light),
        ) { onUpdate { g -> g.copy(tiltReactivity = it) } }
        FxSlider(
            stringResource(R.string.fx_edge_highlight), "${(glass.rimBrightness * 100).toInt()}%", glass.rimBrightness, 0f..2f,
            description = stringResource(R.string.fx_brightness_of_the_specular_glass_rim),
        ) { onUpdate { g -> g.copy(rimBrightness = it) } }
        FxSlider(
            stringResource(R.string.fx_edge_width), "${(glass.edgeWidth * 100).toInt()}%", glass.edgeWidth, 0f..1f,
            description = stringResource(R.string.fx_reflective_rim_thin_crisp_edge_to_a_broad_glassy),
        ) { onUpdate { g -> g.copy(edgeWidth = it) } }
        FxSlider(
            stringResource(R.string.fx_reflection), "${(glass.reflection * 100).toInt()}%", glass.reflection, 0f..2f,
            description = stringResource(R.string.fx_how_much_of_the_room_environment_reflection),
        ) { onUpdate { g -> g.copy(reflection = it) } }
        FxSlider(
            stringResource(R.string.fx_gloss), "${(glass.gloss * 100).toInt()}%", glass.gloss, 0f..1f,
            description = stringResource(R.string.fx_highlight_polish_soft_frosted_wide_glint_to_a),
        ) { onUpdate { g -> g.copy(gloss = it) } }

        StudioSection(stringResource(R.string.fx_drop_shadow))
        FxSlider(
            stringResource(R.string.fx_shadow_depth), "${(glass.shadowDepth * 100).toInt()}%", glass.shadowDepth, 0f..1f,
            description = stringResource(R.string.fx_drop_shadow_under_the_glass_buttons_and_dock),
        ) { onUpdate { g -> g.copy(shadowDepth = it) } }
        FxSlider(
            stringResource(R.string.fx_shadow_softness), "${(glass.shadowSoftness * 100).toInt()}%", glass.shadowSoftness, 0f..1f,
            description = stringResource(R.string.fx_blur_spread_of_the_drop_shadow),
        ) { onUpdate { g -> g.copy(shadowSoftness = it) } }
        FxSlider(
            stringResource(R.string.fx_shadow_tint), "${(glass.shadowTint * 100).toInt()}%", glass.shadowTint, 0f..1f,
            description = stringResource(R.string.fx_colour_of_the_drop_shadow_neutral_black_to),
        ) { onUpdate { g -> g.copy(shadowTint = it) } }

        StudioSection(stringResource(R.string.fx_quality))
        FxSlider(
            stringResource(R.string.fx_per_pixel_samples),
            stringResource(R.string.fx_samples_value, glass.sampleRings, when (glass.sampleRings) { 1 -> 5; 2 -> 9; else -> 13 }),
            glass.sampleRings.toFloat(), 1f..3f, steps = 1,
            description = stringResource(R.string.fx_bevel_quality_vs_gpu_cost),
        ) { onUpdate { g -> g.copy(sampleRings = it.toInt()) } }
        Spacer(Modifier.height(20.dp))
        OutlinedButton(
            onClick = { onApplyPreset(PlayerGlassSettings.INITIAL) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.fx_reset_to_defaults)) }
        // Same reservation as the other two tabs. A flat 48dp was short of the
        // bar plus the navigation inset under it, leaving the reset button
        // half-covered.
        Spacer(
            Modifier.height(
                48.dp + LocalBottomChromeInset.current +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
            ),
        )
        }
    }

    if (showBgPicker) {
        GlassColorPickerDialog(
            title = stringResource(R.string.fx_preview_background),
            initial = glass.previewBg,
            onPick = { c -> onUpdate { it.copy(previewBg = c) }; showBgPicker = false },
            onDismiss = { showBgPicker = false },
        )
    }
    if (showTintPicker) {
        GlassColorPickerDialog(
            title = stringResource(R.string.fx_button_glass_tint),
            initial = glass.tintColor,
            onPick = { c -> onUpdate { it.copy(tintColor = c) }; showTintPicker = false },
            onDismiss = { showTintPicker = false },
        )
    }

    // ── Save the current glass as a named theme ──────────────────────────────
    if (showGlassSaveDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGlassSaveDialog = false },
            title = { Text(stringResource(R.string.fx_save_theme)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.fx_theme_name)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        onSavePreset(name)
                        showGlassSaveDialog = false
                        android.widget.Toast.makeText(context, context.getString(R.string.fx_saved_named, name.trim()), android.widget.Toast.LENGTH_SHORT).show()
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { showGlassSaveDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // ── Import a shared theme code ────────────────────────────────────────────
    if (showGlassImportDialog) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showGlassImportDialog = false },
            title = { Text(stringResource(R.string.fx_import_theme)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.fx_paste_a_player_glass_theme_code_someone_shared),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text(stringResource(R.string.fx_theme_code)) },
                        modifier = Modifier.fillMaxWidth().height(140.dp),
                    )
                    TextButton(onClick = {
                        val clip = context.getSystemService(android.content.ClipboardManager::class.java)
                        val primary = clip?.primaryClip
                        if (primary != null && primary.itemCount > 0) {
                            primary.getItemAt(0).coerceToText(context)?.let { code = it.toString() }
                        }
                    }) { Text(stringResource(R.string.fx_paste_from_clipboard)) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = code.isNotBlank(),
                    onClick = {
                        val imported = onImportPreset(code)
                        showGlassImportDialog = false
                        android.widget.Toast.makeText(
                            context,
                            if (imported != null) context.getString(R.string.fx_imported_named, imported) else context.getString(R.string.fx_invalid_theme_code),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    },
                ) { Text(stringResource(R.string.action_import)) }
            },
            dismissButton = { TextButton(onClick = { showGlassImportDialog = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    // ── Share / delete a saved theme ──────────────────────────────────────────
    glassPresetAction?.let { target ->
        AlertDialog(
            onDismissRequest = { glassPresetAction = null },
            title = { Text(target.name) },
            text = { Text(stringResource(R.string.fx_share_this_theme_or_remove_it_from_your_list)) },
            confirmButton = {
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.fx_theme_subject, target.name))
                        putExtra(android.content.Intent.EXTRA_TEXT, onExportPreset(target))
                    }
                    context.startActivity(android.content.Intent.createChooser(send, context.getString(R.string.fx_share_theme)))
                    glassPresetAction = null
                }) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.fx_share))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        onDeletePreset(target.name)
                        glassPresetAction = null
                    }) {
                        Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_delete))
                    }
                    TextButton(onClick = { glassPresetAction = null }) { Text(stringResource(R.string.action_close)) }
                }
            },
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
                // Theme colors so the swatch labels/border stay visible on the
                // White (light) theme (were hardcoded white → white-on-white).
                .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(onClick = onClick),
        )
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            if (isCustom) stringResource(R.string.fx_custom) else stringResource(R.string.current),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(stringResource(R.string.fx_hue), style = MaterialTheme.typography.labelSmall)
                Slider(value = h, onValueChange = { h = it }, valueRange = 0f..360f)
                Text(stringResource(R.string.fx_saturation), style = MaterialTheme.typography.labelSmall)
                Slider(value = s, onValueChange = { s = it }, valueRange = 0f..1f)
                Text(stringResource(R.string.fx_brightness), style = MaterialTheme.typography.labelSmall)
                Slider(value = v, onValueChange = { v = it }, valueRange = 0f..1f)
                TextButton(onClick = { onPick(0) }) { Text(stringResource(R.string.fx_use_current_album_colour)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))) }) {
                Text(stringResource(R.string.action_select))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
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

/** Room for the search bar, a strip of open page, and the bottom chrome. */
private val UiPanelsPreviewHeight = 312.dp

/** The nav bar as it ships: Home, the two default middle pages, Library. */
private val PreviewTabs = listOf(AppTab.HOME, AppTab.DISCOVER, AppTab.RADIO, AppTab.LIBRARY)

/**
 * The UI panels preview: a small screen of the app, put together the way the
 * real one is.
 *
 * A page at the back, marked as the haze source, and the chrome floating over
 * it as siblings of that source: the search bar at the top — a [GlassPanel],
 * so it stands for every sheet and panel as well — and at the bottom the mini
 * player stacked over the tab pill and the round Search button, as the nav
 * host stacks them. Every pane is handed the source, so each one frosts and
 * lenses the page under it exactly as it does on a real screen.
 *
 * Each part of this used to be missing something. The backdrop was a smooth
 * gradient, which gives a lens nothing to bend and a blur nothing to soften,
 * so Backdrop blur, Refraction and Chromatic aberration moved nothing anyone
 * could see. The mini player was handed no backdrop, so it drew neither its
 * live lens nor its shadow and came out a flat pill beside the real bar at the
 * bottom of the same screen. And the pane was a caption card with no vertical
 * padding, whose text ran into its own rim.
 *
 * The page scrolls both ways, so it can be dragged under the glass to watch
 * the rim bend it.
 */
@Composable
private fun UiPanelsPreview(
    glass: PlayerGlassSettings,
    hazeState: HazeState,
    /** The page colour: the theme's, or the Background swatch's. */
    page: Color,
    /** The playing track; null shows a sample one. */
    track: Track?,
) {
    // The page's own ink, picked against the page: the swatch can make it any
    // colour, and theme text on a page it was not chosen for can vanish.
    val ink = if (page.luminance() > 0.5f) Color.Black else Color.White
    val accent = MaterialTheme.colorScheme.primary
    val sampleTrack = remember {
        Track(
            id = 0L,
            title = "The Business",
            artist = Artist(id = 0L, name = "Tiësto"),
        )
    }
    var selectedTab by remember { mutableStateOf(AppTab.HOME) }

    Box(Modifier.fillMaxSize()) {
        PreviewPage(
            ink = ink,
            modifier = Modifier
                .matchParentSize()
                .hazeSource(hazeState)
                .background(page),
        )

        // Everything below is a sibling of the page, never inside it: a pane
        // drawn inside its own haze source samples a picture it is part of and
        // paints a flat slab.
        //
        // The mini player and the tab bar read their glass from LocalPlayerGlass;
        // the nav host provides the mini player's settings there the same way.
        CompositionLocalProvider(LocalPlayerGlass provides glass) {
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.fx_preview_drag_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = ink.copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp),
                )
                GlassSearchBar(
                    query = "",
                    onQueryChange = {},
                    placeholder = stringResource(R.string.search_hint),
                    hazeState = hazeState,
                    glass = glass,
                    enabled = false,
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Taken out here, outside the album's colours, as the nav host
                // takes them: inside DynamicColorScope `primary` is the cover's,
                // and the bar would come out a different hue from the tab bar
                // under it.
                val chromeTint = glassTint(glass.tintColor)
                val chromeGround = MaterialTheme.colorScheme.background
                DynamicColorScope {
                    MiniPlayer(
                        track = track ?: sampleTrack,
                        isPlaying = false,
                        progressProvider = { 0.4f },
                        onPlayPauseClick = {},
                        onSkipNextClick = {},
                        onSkipPreviousClick = {},
                        onClick = {},
                        hazeState = hazeState,
                        glassTintColor = chromeTint,
                        glassGround = chromeGround,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Live, so a tap lights the tab and swells the glass under
                    // the finger as the real bar does.
                    GlassTabBar(
                        tabs = PreviewTabs,
                        selected = selectedTab,
                        onSelect = { selectedTab = it },
                        accent = accent,
                        hazeState = hazeState,
                        modifier = Modifier.weight(1f),
                    )
                    GlassTabBar(
                        tabs = listOf(AppTab.SEARCH),
                        selected = selectedTab,
                        onSelect = { selectedTab = it },
                        accent = accent,
                        hazeState = hazeState,
                        modifier = Modifier.width(TabBarHeight),
                    )
                }
            }
        }
    }
}

// The sample page's songs. Made up, so nobody mistakes them for their library.
private val PreviewMixes = listOf("Low Orbit", "Glass Harbour", "Paper Satellites", "Velvet Signal", "Night Ferry")
private val PreviewSongs = listOf(
    "Midnight Static" to "Aurora Lane",
    "Neon Driftwood" to "The Halyards",
    "Slow Lightning" to "Kasimir",
    "Coastal Frequencies" to "Mira Sol",
    "After the Rain" to "Northbound",
    "Echo Valley" to "Juno & the Tides",
    "Silver Hours" to "Odessa Park",
    "Hollow Moon" to "Static Bloom",
)

/**
 * What the UI panels preview's glass sits over: a page shaped like Home, a rail
 * of covers over a list of songs.
 *
 * The covers are loud on purpose. Hard colour edges are what refraction bends
 * and chromatic aberration splits, and text is what a blur visibly softens. They
 * are drawn rather than loaded, so the preview looks the same with an empty
 * library and costs no I/O.
 */
@Composable
private fun PreviewPage(ink: Color, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState())) {
        // Starts under the search bar, so its glass has covers behind it at rest.
        Spacer(Modifier.height(30.dp))
        PreviewHeading(stringResource(R.string.for_you), ink)
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PreviewMixes.forEachIndexed { i, title ->
                Column(Modifier.width(104.dp)) {
                    PreviewCover(i, Modifier.size(104.dp), corner = 12.dp)
                    Text(
                        title,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        PreviewHeading(stringResource(R.string.recently_played), ink)
        PreviewSongs.forEachIndexed { i, (title, artist) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PreviewCover(PreviewMixes.size + i, Modifier.size(40.dp), corner = 6.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = ink.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Room to drag the last songs up clear of the bottom chrome.
        Spacer(Modifier.height(160.dp))
    }
}

@Composable
private fun PreviewHeading(text: String, ink: Color) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A drawn stand-in for a cover: a two-tone gradient with a disc on it. */
@Composable
private fun PreviewCover(index: Int, modifier: Modifier, corner: Dp) {
    Spacer(
        modifier
            .clip(RoundedCornerShape(corner))
            .drawWithCache {
                // Golden-angle steps, so no two neighbours land on similar hues.
                val hue = (index * 137.5f + 12f) % 360f
                val wash = Brush.linearGradient(
                    listOf(
                        Color.hsv(hue, 0.72f, 0.92f),
                        Color.hsv((hue + 48f) % 360f, 0.85f, 0.42f),
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                )
                val disc = Color.hsv((hue + 180f) % 360f, 0.5f, 0.98f)
                onDrawBehind {
                    drawRect(wash)
                    drawCircle(
                        color = disc,
                        radius = size.minDimension * 0.28f,
                        center = Offset(size.width * 0.66f, size.height * 0.36f),
                    )
                }
            },
    )
}

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
            // The glow FX layer blooms behind the active line's reported bounds.
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
                // The rays need a layer as big as the preview to draw their
                // shafts in, so they wrap a box around the sample row rather
                // than the row itself, and are told where the row sits in it.
                val sample = stringResource(R.string.fx_feel_the_beat_tonight)
                val sampleStyle = MaterialTheme.typography.titleMedium.copy(
                    fontSize = fx.fontSizeSp.sp,
                    lineHeight = (fx.fontSizeSp * 1.26f).sp,
                    letterSpacing = fx.letterSpacingSp.sp,
                    fontWeight = FontWeight.ExtraBold,
                ).withLyricFont(rememberLyricFontFamily(fx))
                val clock = rememberFrameSeconds()
                // The sample has no word timings, so "Follow the sung word" is
                // shown by singing it one word per beat of the synthetic kick.
                val measurer = rememberTextMeasurer()
                val wordSpans = remember(sample, sampleStyle) { sampleWordSpans(measurer, sample, sampleStyle) }
                val followWord = fx.godRaysFollowWord
                var rowBand by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
                val rayLight = rememberLyricRayLight(
                    accent = accent,
                    pulse = pulse,
                    band = {
                        val row = rowBand
                        if (row == null || !followWord || wordSpans.isEmpty()) {
                            row
                        } else {
                            val (from, to) = wordSpans[(clock.value * 2f).toInt().mod(wordSpans.size)]
                            androidx.compose.ui.geometry.Rect(row.left + from, row.top, row.left + to, row.bottom)
                        }
                    },
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .lyricGodRays(rayLight)
                        .lyricShadow(rayLight),
                    contentAlignment = Alignment.Center,
                ) {
                    Letters3DRow(
                        text = sample,
                        style = sampleStyle,
                        color = accent,
                        time = clock,
                        modifier = Modifier
                            .onGloballyPositioned { rowBand = it.boundsInParent() }
                            .fxaa()
                            .liquidGlass(tint = accent, rayLight = rayLight)
                            .bassBeat(pulse, fx, anchors),
                    )
                }
            }
        }
        Text(
            // Report the current beat reactivity (Bass reaction) rather than a
            // fixed tempo — the synthetic beat only exists to drive the FX, and
            // what the preview is actually demonstrating is how reactive the
            // current settings are.
            text = stringResource(R.string.fx_preview_reactivity, (fx.bassReact * 100).toInt()) +
                if (playing != null) stringResource(R.string.fx_now_playing_suffix) else "",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.35f),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp),
        )
    }
}

/**
 * Where each word of [text] runs along a [Letters3DRow], in px from its start.
 * Measured one letter at a time, because that is how the row lays them out:
 * each glyph is its own Text at its natural advance, so a space measures the
 * same here as it draws there, trimmed or not.
 */
private fun sampleWordSpans(measurer: TextMeasurer, text: String, style: TextStyle): List<Pair<Float, Float>> {
    val spans = mutableListOf<Pair<Float, Float>>()
    var x = 0f
    var start = -1f
    text.forEach { ch ->
        if (ch == ' ') {
            if (start >= 0f) spans += start to x
            start = -1f
        } else if (start < 0f) {
            start = x
        }
        x += measurer.measure(ch.toString(), style = style).size.width.toFloat()
    }
    if (start >= 0f) spans += start to x
    return spans
}

/**
 * Synthetic beat: an instant-on, exponential-decay envelope pushed through the
 * SAME attack/release + underdamped-spring math as the live bass pulse, so
 * bounce/attack/release sliders behave in the preview exactly as they will with
 * real music. The Bass-reaction (reactivity) setting scales this downstream in
 * [bassBeat] / [LyricsFxLayer], so the preview's intensity tracks reactivity.
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
                // Steady demo beat: full level at each kick, fast decay. Tempo
                // is just a vehicle for the FX — reactivity is applied later.
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
internal fun StudioSection(title: String) {
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
internal fun FxToggle(
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
            text = stringResource(R.string.fx_no_imported_fonts_yet_add_them_in_settings),
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
                text = stringResource(R.string.fx_font_value, selectedName ?: stringResource(R.string.fx_choose)),
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
internal fun FxSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    description: String? = null,
    /**
     * Called when the finger lifts. The glass tabs debounce persistence
     * instead and leave this null; release-write settings use it to write
     * once per gesture rather than once per frame.
     */
    onChangeFinished: (() -> Unit)? = null,
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
            onValueChangeFinished = onChangeFinished ?: {},
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun String.format(vararg args: Any?): String = String.format(Locale.US, this, *args)

/** A blend mode's name as image editors in the reader's language call it. */
@androidx.annotation.StringRes
private fun blendLabel(mode: VisualizerBlendMode): Int = when (mode) {
    VisualizerBlendMode.NORMAL -> R.string.blend_normal
    VisualizerBlendMode.SCREEN -> R.string.blend_screen
    VisualizerBlendMode.ADDITIVE -> R.string.blend_additive
    VisualizerBlendMode.SOFT_LIGHT -> R.string.blend_soft_light
}
