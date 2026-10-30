package tf.monochrome.android.ui.musiclinks

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.model.MusicLinkPresets
import tf.monochrome.android.domain.model.MusicLinkPreset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.MusicLinksRepository
import tf.monochrome.android.domain.model.LinkArea
import tf.monochrome.android.domain.model.StudioLook
import tf.monochrome.android.domain.model.VisualLink
import tf.monochrome.android.domain.model.VisualLinkTargets
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.ui.player.rememberVisualLinkDriver
import tf.monochrome.android.ui.settings.StudioLinkUi
import tf.monochrome.android.ui.settings.UndoBar
import tf.monochrome.android.ui.settings.VisualLinkEditing
import tf.monochrome.android.ui.settings.fullLabel
import tf.monochrome.android.ui.settings.info
import tf.monochrome.android.ui.settings.needsText
import javax.inject.Inject

@HiltViewModel
class MusicLinksViewModel @Inject constructor(
    private val repository: MusicLinksRepository,
    preferences: PreferencesManager,
    val spectrumAnalyzer: SpectrumAnalyzerTap,
    private val dsp: tf.monochrome.android.audio.dsp.DspEngineManager,
    private val presetStore: tf.monochrome.android.data.repository.MusicLinkPresetStore,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
) : ViewModel() {
    val editing = VisualLinkEditing(viewModelScope, repository)

    // ── presets ─────────────────────────────────────────────────────────

    /** The listener's saved sets of links. */
    val presets: StateFlow<List<MusicLinkPreset>> = presetStore.presets

    /** A preset just loaded and the links it replaced, while its undo is on offer. */
    val loaded = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, List<VisualLink>>?>(null)

    /** What the last save, export or import said; null when there is nothing to say. */
    val message = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Keeps the links on the page under [name]; one of that name is replaced. */
    fun saveAs(name: String) {
        viewModelScope.launch {
            val links = repository.visualLinks.first()
            presetStore.save(MusicLinkPreset(name.trim().take(MusicLinkPresets.MAX_NAME), links))
            message.value = null
        }
    }

    /**
     * Puts [preset]'s links in place of every link on the page, in one write,
     * with the ones it replaced kept for Undo. An editor left open is closed
     * first, so it cannot save a link back over the preset.
     */
    fun load(preset: MusicLinkPreset) {
        viewModelScope.launch {
            val before = repository.visualLinks.first()
            repository.editing.value = null
            repository.replaceAll(preset.links)
            loaded.value = preset.name to before
            message.value = null
        }
    }

    fun undoLoad() {
        val (_, before) = loaded.value ?: return
        loaded.value = null
        viewModelScope.launch { repository.replaceAll(before) }
    }

    fun delete(preset: MusicLinkPreset) {
        viewModelScope.launch { presetStore.delete(preset.name) }
    }

    /** Writes [preset] to the file at [uri]. */
    fun export(preset: MusicLinkPreset, uri: android.net.Uri) {
        viewModelScope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(MusicLinkPresets.export(preset).toByteArray(Charsets.UTF_8))
                    } ?: error("no stream")
                }.isSuccess
            }
            message.value = context.getString(
                if (ok) R.string.crt_preset_exported else R.string.crt_preset_export_failed,
                preset.name,
            )
        }
    }

    /** Reads the preset file at [uri], keeps it with the listener's presets and loads it; or says why not. */
    fun import(uri: android.net.Uri) {
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { stream ->
                        // A few kilobytes at most: anything big is not a preset.
                        val bytes = stream.readNBytesUpTo(MAX_PRESET_BYTES + 1)
                        require(bytes.size <= MAX_PRESET_BYTES) { "too big to be a preset" }
                        String(bytes, Charsets.UTF_8)
                    } ?: error("could not open the file")
                }.mapCatching { MusicLinkPresets.import(it).getOrThrow() }
            }
            result
                .onSuccess { preset ->
                    presetStore.save(preset)
                    load(preset)
                }
                .onFailure {
                    message.value = context.getString(R.string.crt_preset_import_failed, it.message ?: "unreadable")
                }
        }
    }

    private fun java.io.InputStream.readNBytesUpTo(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /** The mixer's knob links, each with the name of its effect, knob and bus. */
    val knobLinks: StateFlow<List<KnobLinkRowModel>> =
        kotlinx.coroutines.flow.combine(dsp.knobLinks, dsp.buses) { links, buses ->
            links.mapNotNull { link ->
                val bus = buses.firstOrNull { it.index == link.bus } ?: return@mapNotNull null
                val plugin = bus.plugins.getOrNull(link.slot) ?: return@mapNotNull null
                val def = tf.monochrome.android.ui.mixer.getParamDefs(plugin.type).getOrNull(link.param)
                    ?: return@mapNotNull null
                KnobLinkRowModel(link, "${plugin.displayName} · ${def.name}", bus.name, def)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setKnobPaused(key: String, paused: Boolean) = dsp.setKnobLinkPaused(key, paused)

    fun removeKnob(key: String): tf.monochrome.android.audio.dsp.model.KnobLink? = dsp.removeKnobLink(key)

    fun restoreKnob(link: tf.monochrome.android.audio.dsp.model.KnobLink) = dsp.putKnobLink(link)

    /** The listener's own look: where a new link rests, and what a setting needs to show. */
    val look: StateFlow<StudioLook> =
        kotlinx.coroutines.flow.combine(
            kotlinx.coroutines.flow.combine(
                preferences.lyricsFx,
                preferences.playerGlass,
                preferences.miniPlayerGlass,
                preferences.appTvTuning,
                preferences.appTvShader,
            ) { lyrics, player, panels, tv, tvLook -> StudioLook(lyrics, player, panels, tv, tvLook) },
            preferences.ambientVisualizer,
        ) { look, ambient -> look.copy(ambient = ambient) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StudioLook())
}

/** The largest preset file read: every target linked is a few kilobytes. */
private const val MAX_PRESET_BYTES = 256 * 1024

/** A knob link as the Music links page lists it. */
data class KnobLinkRowModel(
    val link: tf.monochrome.android.audio.dsp.model.KnobLink,
    val title: String,
    val busName: String,
    val def: tf.monochrome.android.ui.mixer.ParamDef,
)

/**
 * Every music link in one place: a master switch that pauses them all
 * without forgetting any, each link with what it follows and how far it
 * goes, and pause, change and remove on each. Links are made where their
 * setting lives — the Visual Studio, the mixer — so an empty page says where
 * to go rather than offering a list of settings out of context.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicLinksScreen(
    navController: NavController,
    viewModel: MusicLinksViewModel = hiltViewModel(),
) {
    val editing = viewModel.editing
    val links by editing.links.collectAsStateWithLifecycle()
    val allPaused by editing.allPaused.collectAsStateWithLifecycle()
    val draft by editing.draft.collectAsStateWithLifecycle()
    val look by viewModel.look.collectAsStateWithLifecycle()
    val knobs by viewModel.knobLinks.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val loadedPreset by viewModel.loaded.collectAsStateWithLifecycle()
    val presetMessage by viewModel.message.collectAsStateWithLifecycle()
    val ui = remember { StudioLinkUi() }
    var naming by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // The preset these links are, if they are one; else what Export writes.
    val inUse = presets.firstOrNull { MusicLinkPresets.matches(it, links) }
    val current = inUse ?: MusicLinkPreset(context.getString(R.string.music_links_preset_default_name), links)
    val exporter = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.export(current, uri) }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) viewModel.import(uri) }
    // The knob link just removed and its name, while its undo is on offer.
    var removedKnob by remember { mutableStateOf<Pair<tf.monochrome.android.audio.dsp.model.KnobLink, String>?>(null) }

    // Only the link in the editor runs here: there is no preview on this
    // page, and the meter is all that needs it.
    val live = rememberVisualLinkDriver(
        tap = viewModel.spectrumAnalyzer,
        links = listOfNotNull(draft?.link),
        active = draft != null,
        delayMs = 0f,
    )

    val backdrop = rememberHazeState()
    val overlay = rememberHazeState()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(overlay)) {
            LinksBackdrop(Modifier.fillMaxSize().hazeSource(backdrop))
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(stringResource(R.string.music_links_title)) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStackSafe() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 32.dp + LocalBottomChromeInset.current + navBar),
                ) {
                    item(key = "intro") {
                        Text(
                            stringResource(R.string.music_links_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                    item(key = "master") {
                        LinkCard(backdrop) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.music_links_on),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(R.string.music_links_on_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = LocalContentColor.current.copy(alpha = 0.8f),
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(checked = !allPaused, onCheckedChange = { editing.setAllPaused(!it) })
                            }
                        }
                    }
                    item(key = "presets") {
                        LinkCard(backdrop) {
                            PresetsCard(
                                presets = presets,
                                inUse = inUse,
                                hasLinks = links.isNotEmpty(),
                                message = presetMessage,
                                hazeState = backdrop,
                                onLoad = viewModel::load,
                                onSave = { naming = true },
                                onImport = { importer.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                                onExport = { exporter.launch(MusicLinkPresets.fileName(current.name)) },
                                onDelete = viewModel::delete,
                            )
                        }
                    }
                    if (links.isEmpty() && knobs.isEmpty()) {
                        item(key = "empty") {
                            LinkCard(backdrop) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(
                                        Icons.Default.MusicNote,
                                        contentDescription = null,
                                        tint = musicLinkColor(),
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Text(
                                        stringResource(R.string.music_links_empty_title),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        stringResource(R.string.music_links_empty_desc),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = LocalContentColor.current.copy(alpha = 0.85f),
                                    )
                                    GlassPill(
                                        onClick = { navController.navigateTool(Screen.LyricsFxStudio) },
                                        hazeState = backdrop,
                                        height = 48.dp,
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    ) {
                                        Text(stringResource(R.string.music_links_open_studio), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                    // One group per part of the app, in the Studio's order.
                    for (area in LinkArea.entries) {
                        val inArea = links.filter { VisualLinkTargets.byKey(it.target)?.area == area }
                        if (inArea.isEmpty()) continue
                        item(key = "header:" + area.prefix) {
                            Text(
                                stringResource(if (area == LinkArea.LYRICS) R.string.music_links_group_lyrics else area.title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp),
                            )
                        }
                        items(inArea, key = { it.target }) { link ->
                            LinkRow(
                                link = link,
                                allPaused = allPaused,
                                hazeState = backdrop,
                                onPause = { editing.setPaused(link.target, !link.paused) },
                                onChange = {
                                    val target = VisualLinkTargets.byKey(link.target) ?: return@LinkRow
                                    editing.start(link.target, target.read(look))
                                },
                                onRemove = { ui.removed = editing.remove(link.target) },
                            )
                        }
                    }
                    if (knobs.isNotEmpty()) {
                        item(key = "mixer_header") {
                            Text(
                                stringResource(R.string.music_links_group_mixer),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 2.dp),
                            )
                        }
                        items(knobs, key = { "knob:" + it.link.key }) { row ->
                            KnobRow(
                                row = row,
                                allPaused = allPaused,
                                hazeState = backdrop,
                                onPause = { viewModel.setKnobPaused(row.link.key, !row.link.paused) },
                                onRemove = { viewModel.removeKnob(row.link.key)?.let { removedKnob = it to row.title } },
                            )
                        }
                    }
                    item(key = "mixer_note") {
                        Text(
                            stringResource(R.string.music_links_mixer_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        )
                    }
                }
            }
        }

        val d = draft
        val target = d?.let { VisualLinkTargets.byKey(it.link.target) }
        if (d != null && target != null) {
            val spectrum = rememberEditorSpectrum(viewModel.spectrumAnalyzer)
            val key = d.link.target
            val info = target.info()
            MusicLinkEditor(
                title = stringResource(R.string.music_link_editor_title, info.label),
                target = info,
                shape = d.link.shape,
                onShapeChange = editing::update,
                spectrum = spectrum,
                strength = { live.strengths[key] ?: 0f },
                note = target.needsText(look),
                isNew = d.isNew,
                hazeState = overlay,
                onDone = editing::commit,
                onCancel = editing::cancel,
                onRemove = { ui.removed = editing.remove(key) },
                listensTo = { WhatYouHearRow() },
            )
        }

        val justLoaded = loadedPreset
        if (justLoaded != null && d == null) {
            androidx.compose.runtime.LaunchedEffect(justLoaded) {
                kotlinx.coroutines.delay(6000)
                if (viewModel.loaded.value == justLoaded) viewModel.loaded.value = null
            }
            UndoBar(
                text = stringResource(R.string.music_links_preset_loaded, justLoaded.first),
                hazeState = overlay,
                onUndo = viewModel::undoLoad,
            )
        }

        val goneKnob = removedKnob
        if (goneKnob != null && d == null) {
            androidx.compose.runtime.LaunchedEffect(goneKnob) {
                kotlinx.coroutines.delay(6000)
                if (removedKnob == goneKnob) removedKnob = null
            }
            UndoBar(
                text = stringResource(R.string.music_link_removed, goneKnob.second),
                hazeState = overlay,
                onUndo = {
                    viewModel.restoreKnob(goneKnob.first)
                    removedKnob = null
                },
            )
        }

        val removed = ui.removed
        if (removed != null && d == null) {
            androidx.compose.runtime.LaunchedEffect(removed) {
                kotlinx.coroutines.delay(6000)
                if (ui.removed == removed) ui.removed = null
            }
            val label = VisualLinkTargets.byKey(removed.target)?.fullLabel() ?: removed.target
            UndoBar(
                text = stringResource(R.string.music_link_removed, label),
                hazeState = overlay,
                onUndo = {
                    editing.restore(removed)
                    ui.removed = null
                },
            )
        }
    }

    if (naming) {
        // A dialog, so opaque (see Glass): a name is typed, nothing behind it is read.
        var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(inUse?.name.orEmpty()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text(stringResource(R.string.music_links_preset_save_title)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MusicLinkPresets.MAX_NAME) },
                    label = { Text(stringResource(R.string.crt_preset_name)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    enabled = name.isNotBlank(),
                    onClick = {
                        viewModel.saveAs(name)
                        naming = false
                    },
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { naming = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/**
 * The page's saved sets of links: a chip each, tapped to load (every link on
 * the page replaced at once, with Undo); Save keeps the links on the page
 * under a name; Import and Export move one through a file. The one in use is
 * marked, and only it can be deleted, so a delete never names the wrong one.
 */
@Composable
private fun PresetsCard(
    presets: List<MusicLinkPreset>,
    inUse: MusicLinkPreset?,
    hasLinks: Boolean,
    message: String?,
    hazeState: HazeState,
    onLoad: (MusicLinkPreset) -> Unit,
    onSave: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onDelete: (MusicLinkPreset) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.crt_presets), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (presets.isEmpty()) R.string.music_links_presets_empty else R.string.music_links_presets_hint),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.8f),
        )
        if (presets.isNotEmpty()) {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                presets.forEach { preset ->
                    tf.monochrome.android.ui.mixer.GlassChoiceChip(
                        label = preset.name,
                        selected = preset == inUse,
                        accent = musicLinkColor(),
                        onClick = { onLoad(preset) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PresetPill(stringResource(R.string.action_save), hazeState, enabled = hasLinks, onClick = onSave)
            PresetPill(stringResource(R.string.crt_preset_import), hazeState, onClick = onImport)
            PresetPill(stringResource(R.string.crt_preset_export), hazeState, enabled = hasLinks, onClick = onExport)
        }
        if (inUse != null) {
            androidx.compose.material3.TextButton(onClick = { onDelete(inUse) }) {
                Text(stringResource(R.string.crt_preset_delete, inUse.name), color = MaterialTheme.colorScheme.error)
            }
        }
        if (message != null) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.85f))
        }
    }
}

/** One of the presets card's three glass buttons: equal widths, a label that shrinks before it is cut. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.PresetPill(
    label: String,
    hazeState: HazeState,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    GlassPill(onClick = onClick, hazeState = hazeState, height = 44.dp, enabled = enabled, modifier = Modifier.weight(1f)) {
        PillLabel(label)
    }
}

/**
 * A pill's label on one line: smaller before it is cut, and cut with an
 * ellipsis, never at a word break. A plain Text with maxLines = 1 wrapped
 * "Resume the link" after "the" and showed only that line.
 */
@Composable
private fun PillLabel(label: String) {
    val style = androidx.compose.material3.LocalTextStyle.current
    Text(
        label,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
            minFontSize = 9.sp,
            maxFontSize = style.fontSize,
            stepSize = 0.5.sp,
        ),
    )
}

@Composable
private fun LinkCard(hazeState: HazeState, content: @Composable () -> Unit) {
    GlassPanel(
        hazeState = hazeState,
        glass = LocalMiniPlayerGlass.current,
        avoidNavigationBar = false,
        // In a scrolling list: a swipe on the pane scrolls it.
        blockTouchesBelow = false,
    ) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun LinkRow(
    link: VisualLink,
    allPaused: Boolean,
    hazeState: HazeState,
    onPause: () -> Unit,
    onChange: () -> Unit,
    onRemove: () -> Unit,
) {
    val target = VisualLinkTargets.byKey(link.target) ?: return
    val green = musicLinkColor()
    LinkCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (link.paused || allPaused) LocalContentColor.current.copy(alpha = 0.5f) else green,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(target.label), style = MaterialTheme.typography.titleMedium)
                    Text(
                        followsText(link.shape, link.paused) + " · " + stringResource(
                            R.string.music_link_range_value,
                            target.format(link.shape.lowest),
                            target.format(link.shape.highest),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(onClick = onChange, hazeState = hazeState, height = 44.dp, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.music_links_change), fontWeight = FontWeight.Bold)
                }
                GlassPill(
                    onClick = onPause,
                    hazeState = hazeState,
                    height = 44.dp,
                    modifier = Modifier.weight(1f),
                    onClickLabel = stringResource(if (link.paused) R.string.music_link_resume else R.string.music_link_pause),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (link.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        PillLabel(stringResource(if (link.paused) R.string.music_link_resume else R.string.music_link_pause))
                    }
                }
                GlassPill(
                    onClick = onRemove,
                    hazeState = hazeState,
                    height = 44.dp,
                    onClickLabel = stringResource(R.string.music_link_remove),
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.music_link_remove),
                        modifier = Modifier.padding(horizontal = 14.dp).size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun KnobRow(
    row: KnobLinkRowModel,
    allPaused: Boolean,
    hazeState: HazeState,
    onPause: () -> Unit,
    onRemove: () -> Unit,
) {
    val link = row.link
    val green = musicLinkColor()
    LinkCard(hazeState) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = if (link.paused || allPaused) LocalContentColor.current.copy(alpha = 0.5f) else green,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        row.busName + " · " + followsText(link.shape, link.paused) + " · " + stringResource(
                            R.string.music_link_range_value,
                            tf.monochrome.android.ui.mixer.formatParamValue(link.shape.lowest, row.def),
                            tf.monochrome.android.ui.mixer.formatParamValue(link.shape.highest, row.def),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.8f),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill(
                    onClick = onPause,
                    hazeState = hazeState,
                    height = 44.dp,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (link.paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        PillLabel(stringResource(if (link.paused) R.string.music_link_resume else R.string.music_link_pause))
                    }
                }
                GlassPill(onClick = onRemove, hazeState = hazeState, height = 44.dp, onClickLabel = stringResource(R.string.music_link_remove)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.music_link_remove),
                        modifier = Modifier.padding(horizontal = 14.dp).size(20.dp),
                    )
                }
            }
        }
    }
}

/** Two soft glows on the page colour: something real for the cards' glass to frost. */
@Composable
fun LinksBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val green = musicLinkColor()
    Box(modifier.background(scheme.background)) {
        Box(
            Modifier
                .offset(x = (-80).dp, y = 60.dp)
                .size(360.dp)
                .background(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.32f), Color.Transparent)), CircleShape),
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 90.dp, y = (-120).dp)
                .size(380.dp)
                .background(Brush.radialGradient(listOf(green.copy(alpha = 0.22f), Color.Transparent)), CircleShape),
        )
    }
}
