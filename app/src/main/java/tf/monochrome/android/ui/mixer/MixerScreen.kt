package tf.monochrome.android.ui.mixer

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SpatialAudio
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import tf.monochrome.android.audio.dsp.model.BusConfig
import tf.monochrome.android.audio.dsp.model.BusLevels
import tf.monochrome.android.audio.dsp.model.MixPreset
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.player.AlbumColors
import tf.monochrome.android.ui.player.DynamicAlbumGlow
import tf.monochrome.android.ui.player.PlayerBlurredArtBackground
import tf.monochrome.android.ui.player.PlayerDesignTokens
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.player.dithered
import tf.monochrome.android.ui.player.dynamicPlayerBackground
import tf.monochrome.android.ui.player.rememberAlbumColors
import tf.monochrome.android.ui.theme.ColorBlend
import tf.monochrome.android.ui.theme.MonoDimens

/** Curated per-bus channel colours (master keeps the album-derived primary).
 *  Replaces the muted theme `secondary`, which rendered bus strips as washed
 *  grey ghosts against the dynamic background. */
private val BusAccentPalette = listOf(
    Color(0xFF6EA8FF), // blue
    Color(0xFF49E0B0), // teal
    Color(0xFFB98CFF), // violet
    Color(0xFFFF8A6B), // coral
    Color(0xFFFFC857), // amber
)

/**
 * Dynamic per-bus accent derived from the current player/theme color [base]:
 * each channel is the base hue rotated by a fixed step, so the strips track
 * the album-dynamic color while staying distinguishable. Falls back to the
 * curated palette when [base] is essentially greyscale (e.g. the Monochrome
 * theme with album colors off), where there is no hue to vary.
 */
private fun dynamicBusColor(base: Color, index: Int): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    if (hsv[1] < 0.12f) return BusAccentPalette[index % BusAccentPalette.size]
    hsv[0] = (hsv[0] + 24f + index * 34f) % 360f
    hsv[1] = hsv[1].coerceIn(0.50f, 0.95f)
    hsv[2] = hsv[2].coerceIn(0.62f, 0.92f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun busAccent(dynamic: Boolean, base: Color, index: Int): Color =
    if (dynamic) dynamicBusColor(base, index)
    else BusAccentPalette[index % BusAccentPalette.size]

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MixerScreen(
    navController: NavController,
    viewModel: MixerViewModel,
    /**
     * Only for what is playing — the cover the backdrop blurs, the palette it
     * darkens with, and the two settings that govern both. The console itself
     * is driven entirely by [viewModel].
     */
    playerViewModel: PlayerViewModel
) {
    // Frame-synced meter/tap polling: one native read per display frame, so
    // the VU meters and FX visuals update at the panel's native refresh rate
    // (120 Hz where the device permits) and stop while the mixer is off
    // screen. Replaces the old fixed 16 ms ViewModel loop that capped
    // everything at 60 Hz.
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { }
            viewModel.pollTick()
        }
    }

    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val buses by viewModel.buses.collectAsStateWithLifecycle()
    val selectedBusIndex by viewModel.selectedBusIndex.collectAsStateWithLifecycle()
    val spreadChannels by viewModel.spreadChannels.collectAsStateWithLifecycle()
    val showPluginPicker by viewModel.showPluginPicker.collectAsStateWithLifecycle()
    val editingPlugin by viewModel.editingPlugin.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val currentPresetName by viewModel.currentPresetName.collectAsStateWithLifecycle()
    val channelDynamicColor by viewModel.channelDynamicColor.collectAsStateWithLifecycle()

    val selectedBus = buses.getOrNull(selectedBusIndex)
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val colorScheme = MaterialTheme.colorScheme
    val accent = colorScheme.primary
    val headerShape = RoundedCornerShape(
        bottomStart = PlayerDesignTokens.GlassCornerLarge,
        bottomEnd = PlayerDesignTokens.GlassCornerLarge
    )

    var showInsertRack by remember { mutableStateOf(false) }
    var showSpatialMap by remember { mutableStateOf(false) }
    val spatialPlacement by viewModel.spatialPlacement.collectAsStateWithLifecycle()
    // The detector measures only while the map is up.
    LaunchedEffect(showSpatialMap) {
        if (showSpatialMap) viewModel.openSpatialMap() else viewModel.closeSpatialMap()
    }
    androidx.activity.compose.BackHandler(enabled = showSpatialMap) { showSpatialMap = false }
    var showResetConfirm by remember { mutableStateOf(false) }

    // ── The backdrop the console's glass stands on ──────────────────────
    // The same blurred, stretched album art the player shows, behind the same
    // Appearance switch: a mixer full of glass with nothing but a flat wash
    // behind it has nothing to be glass *of*.
    val currentTrack by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val blurredBackground by playerViewModel.playerBlurredBackground.collectAsStateWithLifecycle()
    val dynamicColors by playerViewModel.dynamicColors.collectAsStateWithLifecycle()
    val playerDynamicColor by playerViewModel.playerDynamicColor.collectAsStateWithLifecycle()
    // The player's own rule for whether artwork is allowed to colour anything:
    // both the master switch and the player-specific one. With either off the
    // scrim over the art takes the theme accent, like the rest of this screen.
    val artColors = if (dynamicColors && playerDynamicColor) {
        rememberAlbumColors(currentTrack?.coverUrl)
    } else {
        AlbumColors(dominant = accent, vibrant = accent)
    }
    // The cover dissolves between tracks over the Color transition length, so
    // the mixer's backdrop changes track at the speed the player's does.
    val colorTransitionMs by playerViewModel.colorTransitionMs.collectAsStateWithLifecycle()
    val colorBlendMs = tf.monochrome.android.ui.theme.motionMillis(
        ColorBlend.millisFor(colorTransitionMs)
    )
    val blurBgAlpha by animateFloatAsState(
        targetValue = if (blurredBackground) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "mixerBlurredBg"
    )
    // What the console's glass blurs. The backdrop below is marked as the
    // source and everything else on this screen is a SIBLING above it — a haze
    // effect cannot sample a layer it is drawn inside, and one that tries
    // paints the source's flat colour instead of a blur.
    val mixerHaze = rememberHazeState()

    // ── Mixer ⇆ DSP-canvas drag-to-reveal transition ────────────────────
    // progress 0 = mixer fully shown, 1 = canvas fully shown. The two pages
    // are stacked as a filmstrip and translated by `progress`; a header-only
    // vertical drag writes `progress` synchronously (zero-lag tracking) and on
    // release it settles to 0/1 by fling velocity (else position). `progress`
    // is read ONLY inside graphicsLayer{} (draw phase) and derivedStateOf, so
    // sliding never recomposes the page content.
    val scope = rememberCoroutineScope()
    var progress by remember { mutableFloatStateOf(0f) }
    var heightPx by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val settleSpec = remember {
        spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    }
    val animateProgressTo: (Float, Float) -> Unit = { target, initialVel ->
        settleJob?.cancel()
        settleJob = scope.launch {
            // Coerce the animated value: a fast fling into the critically-damped
            // spring can overshoot the endpoint, which would briefly expose a
            // background gap at the top/bottom edge.
            animate(progress, target, initialVel, settleSpec) { value, _ -> progress = value.coerceIn(0f, 1f) }
        }
    }
    val dragState = rememberDraggableState { delta ->
        if (heightPx > 0f) progress = (progress + delta / heightPx).coerceIn(0f, 1f)
    }
    val onDragStarted: suspend CoroutineScope.(Offset) -> Unit = {
        settleJob?.cancel()
        dragging = true
    }
    val onDragStopped: suspend CoroutineScope.(Float) -> Unit = { velocity ->
        val vNorm = if (heightPx > 0f) velocity / heightPx else 0f
        val target = when {
            vNorm > 0.8f -> 1f           // flick down → canvas
            vNorm < -0.8f -> 0f          // flick up → mixer
            progress > 0.5f -> 1f        // dragged past halfway → canvas
            else -> 0f
        }
        // Only carry velocity that agrees with the target, so a gentle
        // sub-threshold flick the "wrong" way doesn't lurch before settling.
        val settleVel = if ((target == 1f) == (vNorm > 0f)) vNorm else 0f
        dragging = false
        animateProgressTo(target, settleVel)
    }
    // Coarse, boundary-only flags (derivedStateOf recomposes only when the bool
    // flips). `|| dragging` keeps BOTH pages composed for the whole gesture, so
    // the page that owns the active drag handle is never disposed mid-drag
    // (which would cancel its scope and skip the settle).
    val composeCanvas by remember { derivedStateOf { progress > 0.0001f || dragging } }
    val composeMixer by remember { derivedStateOf { progress < 0.9999f || dragging } }

    // ── Preset import / export (SAF document pickers) ───────────────────
    val context = LocalContext.current
    var pendingExport by remember { mutableStateOf<MixPreset?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val preset = pendingExport
        pendingExport = null
        if (uri != null && preset != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(viewModel.exportPayload(preset).toByteArray())
                }
                Toast.makeText(context, "Exported \"${preset.name}\"", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
                if (text.isNullOrBlank()) error("Empty file")
                viewModel.importPreset(text) { ok ->
                    Toast.makeText(
                        context,
                        if (ok) "Preset imported" else "Import failed: not a valid preset file",
                        if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                    ).show()
                }
            }.onFailure {
                Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { heightPx = it.height.toFloat() }
    ) {
        // The whole backdrop — wash, artwork and glow — as one haze source, so a
        // strip blurs all three together instead of one of them.
        Box(Modifier.matchParentSize().hazeSource(mixerHaze)) {
            // Background on its own node so the dither layer wraps just the
            // gradient, not the whole screen's content.
            Box(
                Modifier
                    .matchParentSize()
                    .dithered()
                    .background(dynamicPlayerBackground(accent)),
            )
            if (blurBgAlpha > 0.001f) {
                PlayerBlurredArtBackground(
                    coverUrl = currentTrack?.coverUrl,
                    albumColors = artColors,
                    blendMillis = colorBlendMs,
                    alpha = { blurBgAlpha },
                )
            }
            DynamicAlbumGlow(accent)
        }
        if (composeCanvas) {
            // ── DSP Canvas View (slides down from the top) ───────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = (progress - 1f) * heightPx }
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 18.dp, shape = headerShape, clip = false)
                        .background(colorScheme.surface.copy(alpha = 0.90f), headerShape)
                        .liquidGlass(
                            shape = headerShape,
                            tintAlpha = PlayerDesignTokens.GlassTintMedium,
                            borderAlpha = PlayerDesignTokens.GlassTintSoft
                        )
                        .padding(top = statusBarPadding)
                        // Swipe up here to slide the mixer back over the canvas.
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = onDragStarted,
                            onDragStopped = onDragStopped
                        )
                        .padding(horizontal = MonoDimens.spacingMd, vertical = MonoDimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { animateProgressTo(0f, 0f) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = colorScheme.onSurface, modifier = Modifier.size(20.dp))
                        }
                        Text("FX Chain", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                    }
                }
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val fxTap by viewModel.fxTap.collectAsStateWithLifecycle()
                    tf.monochrome.android.ui.mixer.fxchain.FxChainPage(
                        buses = buses,
                        selectedBusIndex = selectedBusIndex,
                        enabled = enabled,
                        fxTap = fxTap,
                        busAccent = { idx ->
                            if (buses.getOrNull(idx)?.isMaster == true) accent
                            else busAccent(channelDynamicColor, accent, idx)
                        },
                        onSelectBus = { viewModel.selectBus(it) },
                        onAddEffect = { viewModel.showAddPlugin() },
                        onBypass = { b, s -> viewModel.togglePluginBypass(b, s) },
                        onRemove = { b, s -> viewModel.removePlugin(b, s) },
                        onDryWet = { b, s, dw -> viewModel.setPluginDryWet(b, s, dw) },
                        onParam = { b, s, p, v -> viewModel.setParameter(b, s, p, v) },
                        onOversample = { b, s, f -> viewModel.setPluginOversampling(b, s, f) },
                        onPreset = { b, s, p -> viewModel.applyFxPreset(b, s, p) },
                        onMove = { b, from, to -> viewModel.movePlugin(b, from, to) },
                    )
                }
            }
            }
        }
        if (composeMixer) {
            // ── FL Studio Console View ──────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = progress * heightPx }
            ) {
            Column(modifier = Modifier.fillMaxSize()) {

                // ── Header (swipe down anywhere to reveal the DSP canvas) ───
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(elevation = 22.dp, shape = headerShape, clip = false)
                        .background(colorScheme.surface.copy(alpha = 0.90f), headerShape)
                        .liquidGlass(
                            shape = headerShape,
                            tintAlpha = PlayerDesignTokens.GlassTintMedium,
                            borderAlpha = PlayerDesignTokens.GlassTintSoft
                        )
                        // Inset first so the drag area excludes the status-bar
                        // strip and doesn't fight the system notification shade.
                        .padding(top = statusBarPadding)
                        // Drag down here to slide the DSP canvas in from the top.
                        .draggable(
                            state = dragState,
                            orientation = Orientation.Vertical,
                            onDragStarted = onDragStarted,
                            onDragStopped = onDragStopped
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MonoDimens.spacingSm, vertical = MonoDimens.spacingXs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NavIconButton(
                            icon = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            onClick = { navController.popBackStack() }
                        )

                        Text(
                            text = "Mixer",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                            modifier = Modifier.padding(start = 2.dp)
                        )

                        Box(modifier = Modifier.weight(1f))

                        // The spatial map: lit while it is open or placing.
                        NavIconButton(
                            icon = Icons.Default.SpatialAudio,
                            contentDescription = "Spatial map",
                            active = showSpatialMap || spatialPlacement.enabled,
                            accent = accent,
                            onClick = { showSpatialMap = !showSpatialMap }
                        )
                        NavIconButton(
                            icon = Icons.Default.Tune,
                            contentDescription = "Insert Rack",
                            active = showInsertRack,
                            accent = accent,
                            onClick = { showInsertRack = !showInsertRack }
                        )
                        NavIconButton(
                            icon = Icons.Default.Palette,
                            contentDescription = if (channelDynamicColor) "Channel color: dynamic" else "Channel color: palette",
                            active = channelDynamicColor,
                            accent = accent,
                            onClick = { viewModel.setChannelDynamicColor(!channelDynamicColor) }
                        )
                        NavIconButton(
                            icon = Icons.Default.AccountTree,
                            contentDescription = "FX Chain",
                            onClick = { animateProgressTo(1f, 0f) }
                        )
                        NavIconButton(
                            icon = Icons.Default.SettingsBackupRestore,
                            contentDescription = "Reset mixer to defaults",
                            onClick = { showResetConfirm = true }
                        )

                        DspPowerToggle(
                            enabled = enabled,
                            accent = accent,
                            onToggle = { viewModel.setEnabled(!enabled) }
                        )
                    }

                    // Preset bar
                    tf.monochrome.android.devedit.DevEditable("preset_bar", Modifier.fillMaxWidth()) {
                        PresetBar(
                            currentPresetName = currentPresetName,
                            presets = presets,
                            onSave = { viewModel.savePreset(it) },
                            onLoad = { viewModel.loadPreset(it) },
                            onDelete = { viewModel.deletePreset(it) },
                            onExport = { preset ->
                                pendingExport = preset
                                val safeName = preset.name
                                    .replace(Regex("[^A-Za-z0-9 _-]"), "_")
                                    .ifBlank { "preset" }
                                exportLauncher.launch("$safeName.json")
                            },
                            onImport = { importLauncher.launch("application/json") }
                        )
                    }

                    // Pull-down handle — tap or swipe down to open the DSP canvas
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { animateProgressTo(1f, 0f) }
                            .padding(top = 2.dp, bottom = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(colorScheme.onSurfaceVariant.copy(alpha = 0.40f))
                        )
                    }
                }

                // ── Channel strips + insert rack ────────────────────────
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Row(modifier = Modifier.fillMaxSize()) {

                    // Horizontal-scrolling channel strips. Hoisted into its own
                    // composable that collects the 60Hz busLevels flow LOCALLY,
                    // so meter frames recompose only the strips — never the
                    // header, the DSP-canvas page, or the transition gating.
                    ChannelStripRow(
                        viewModel = viewModel,
                        buses = buses,
                        selectedBusIndex = selectedBusIndex,
                        accent = accent,
                        channelDynamicColor = channelDynamicColor,
                        hazeState = mixerHaze,
                        onSelectBus = { index ->
                            viewModel.selectBus(index)
                            showInsertRack = true
                        },
                        modifier = Modifier.weight(1f)
                    )

                    // Insert rack — right panel
                    AnimatedVisibility(
                        visible = showInsertRack,
                        enter = slideInHorizontally(initialOffsetX = { it }),
                        exit = slideOutHorizontally(targetOffsetX = { it })
                    ) {
                        tf.monochrome.android.devedit.DevEditable("insert_rack", Modifier) {
                            InsertRack(
                                bus = selectedBus,
                                busIndex = selectedBusIndex,
                                editingPlugin = editingPlugin,
                                allBuses = buses,
                                onSlotTap = { slotIdx -> viewModel.editPlugin(selectedBusIndex, slotIdx) },
                                onAddPlugin = { viewModel.showAddPlugin() },
                                onPluginReplace = { busIdx, slotIdx -> viewModel.showReplacePlugin(busIdx, slotIdx) },
                                onPluginBypass = { busIdx, slotIdx -> viewModel.togglePluginBypass(busIdx, slotIdx) },
                                onPluginRemove = { busIdx, slotIdx -> viewModel.removePlugin(busIdx, slotIdx) },
                                onParameterChange = { busIdx, slotIdx, paramIdx, value ->
                                    viewModel.setParameter(busIdx, slotIdx, paramIdx, value)
                                },
                                onApplyPreset = { busIdx, slotIdx, preset ->
                                    viewModel.applyFxPreset(busIdx, slotIdx, preset)
                                },
                                onPluginDryWet = { busIdx, slotIdx, dw ->
                                    viewModel.setPluginDryWet(busIdx, slotIdx, dw)
                                },
                                onBusInputToggle = { busIdx, enabled ->
                                    viewModel.setBusInputEnabled(busIdx, enabled)
                                },
                                spreadChannels = spreadChannels,
                                onSpreadChannelsChange = { viewModel.setSpreadChannels(it) },
                                onDismissEditor = { viewModel.dismissPluginEditor() },
                                onClose = { showInsertRack = false }
                            )
                        }
                    }
                }

                // ── Spatial map, over the strips ────────────────────────
                // In this window, not a dialog: glass cannot sample across
                // windows (docs/ui-invariants.md). Qualified: the enclosing
                // Column's scoped overload would otherwise be picked.
                androidx.compose.animation.AnimatedVisibility(
                    visible = showSpatialMap,
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.96f),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.96f),
                ) {
                    val channelState by viewModel.channelState.collectAsStateWithLifecycle()
                    val stereoFold by viewModel.stereoFoldEnabled.collectAsStateWithLifecycle()
                    val atmosObjects by viewModel.atmosRenderingObjects.collectAsStateWithLifecycle()
                    tf.monochrome.android.ui.mixer.spatial.SpatialMapPanel(
                        placement = spatialPlacement,
                        channelState = channelState,
                        stereoFoldEnabled = stereoFold,
                        atmosRenderingObjects = atmosObjects,
                        accent = accent,
                        onEnabledChange = { viewModel.setSpatialEnabled(it) },
                        onBinauralChange = { viewModel.setSpatialBinaural(it) },
                        onMove = { count, index, p -> viewModel.moveChannel(count, index, p) },
                        onResetLayout = { viewModel.resetSpatialLayout(it) },
                        headphoneTargets = viewModel.headphoneTargets,
                        onTargetChange = { viewModel.setSpatialTarget(it) },
                        onClose = { showSpatialMap = false },
                        modifier = Modifier.fillMaxSize().padding(MonoDimens.spacingSm),
                        hazeState = mixerHaze,
                    )
                }
                }
            }
            }
        }
    }

    // ── Plugin picker dialog ────────────────────────────────────────────
    if (showPluginPicker) {
        PluginPickerDialog(
            onDismiss = { viewModel.dismissPluginPicker() },
            onSelect = { viewModel.addPlugin(it) }
        )
    }

    // Confirmed rather than immediate: this throws away every plugin on every
    // bus, and a chain someone spent an evening building is not something to
    // lose to a mis-tap next to the FX Chain button.
    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset the mixer?") },
            text = {
                Text(
                    "Removes every plugin and every bus after Bus 4, and returns " +
                        "the rest to unity gain, centred, unmuted. Saved presets " +
                        "are untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefaults()
                    showResetConfirm = false
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }

}

/** Consistent circular glass action button for the mixer header. */
@Composable
private fun NavIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(
                if (active) accent.copy(alpha = 0.20f)
                else colors.surfaceContainerHighest.copy(alpha = 0.40f)
            )
            .border(
                width = 1.dp,
                color = if (active) accent.copy(alpha = 0.55f) else colors.outline.copy(alpha = 0.14f),
                shape = CircleShape
            )
            .bounceClick(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (active) accent else colors.onSurfaceVariant,
            modifier = Modifier.size(19.dp)
        )
    }
}

/** Polished pill replacing the stock DSP on/off switch. */
@Composable
private fun DspPowerToggle(
    enabled: Boolean,
    accent: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val onAccent = if (accent.luminance() > 0.55f) Color.Black else Color.White
    val contentColor = if (enabled) onAccent else colors.onSurfaceVariant
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(if (enabled) accent else colors.surfaceContainerHighest.copy(alpha = 0.50f))
            .border(
                width = 1.dp,
                color = if (enabled) Color.White.copy(alpha = 0.25f) else colors.outline.copy(alpha = 0.18f),
                shape = CircleShape
            )
            .bounceClick(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = Icons.Default.PowerSettingsNew,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(15.dp)
        )
        Text(
            text = if (enabled) "ON" else "OFF",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = contentColor
        )
    }
}

/**
 * The row of channel strips: buses 1 to 16 in number order, the master last,
 * then a + tile that adds a bus while there is room for one. The 60 Hz
 * `busLevels` meter flow is collected HERE rather than in [MixerScreen] so a
 * new meter frame recomposes only the strips — the header, the DSP-canvas
 * page, and the drag-transition gating all stay out of the per-frame path
 * (mirrors the local `audioAmplitude` pattern).
 *
 * Every callback is by bus index, not by position in the row: the master sits
 * at index 4 but is drawn last, so the two differ for every bus past 4.
 * Long-press on bus 5 or later asks to remove it.
 */
@Composable
private fun ChannelStripRow(
    viewModel: MixerViewModel,
    buses: List<BusConfig>,
    selectedBusIndex: Int,
    accent: Color,
    channelDynamicColor: Boolean,
    /** The screen's backdrop, for the strips to frost. */
    hazeState: HazeState,
    onSelectBus: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val busLevels by viewModel.busLevels.collectAsStateWithLifecycle()
    val ordered = remember(buses) { BusConfig.displayOrder(buses) }
    val canAdd = BusConfig.mixBusCount(buses) < BusConfig.MAX_MIX_BUSES
    var pendingRemoval by remember { mutableStateOf<BusConfig?>(null) }
    // The + tile takes the strips' measured height, so it lines up with them.
    val density = LocalDensity.current
    var stripHeight by remember { mutableStateOf(0.dp) }
    LazyRow(
        modifier = modifier.fillMaxHeight(),
        contentPadding = PaddingValues(horizontal = MonoDimens.spacingMd, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items(ordered, key = { it.index }) { bus ->
            val index = bus.index
            tf.monochrome.android.devedit.DevEditable("channel_strip_$index", Modifier) {
                FLChannelStrip(
                    modifier = Modifier.onSizeChanged {
                        if (!bus.isMaster) stripHeight = with(density) { it.height.toDp() }
                    },
                    bus = bus,
                    isSelected = index == selectedBusIndex,
                    levels = busLevels.getOrNull(index) ?: BusLevels(),
                    accentColor = if (bus.isMaster) accent else busAccent(channelDynamicColor, accent, index),
                    hazeState = hazeState,
                    onSelect = { onSelectBus(index) },
                    onLongPress = if (bus.isRemovable) ({ pendingRemoval = bus }) else null,
                    onGainChange = { viewModel.setBusGain(index, it) },
                    onPanChange = { viewModel.setBusPan(index, it) },
                    onToggleMute = { viewModel.toggleMute(index) },
                    onToggleSolo = { viewModel.toggleSolo(index) }
                )
            }
        }
        if (canAdd) {
            item(key = "add_bus") {
                AddBusTile(
                    height = if (stripHeight > 0.dp) stripHeight else 320.dp,
                    accent = accent,
                    onClick = {
                        viewModel.addBus()
                    }
                )
            }
        }
    }

    pendingRemoval?.let { bus ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${bus.name}?") },
            text = {
                Text(
                    "Its effects and settings go with it. Buses after it move " +
                        "down one number. Saved presets are untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeBus(bus.index)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") }
            }
        )
    }
}

/** The empty slot after the last strip: tap to add a bus. */
@Composable
private fun AddBusTile(
    height: Dp,
    accent: Color,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(PlayerDesignTokens.GlassCornerSmall)
    Box(
        modifier = Modifier
            .width(60.dp)
            .height(height)
            .clip(shape)
            .border(1.dp, colors.outline.copy(alpha = 0.30f), shape)
            .bounceClick(onClick = onClick)
            .semantics { contentDescription = "Add bus" },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = "Add bus",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant
            )
        }
    }
}
