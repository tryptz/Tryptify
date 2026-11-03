package tf.monochrome.android.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.repository.MusicLinksRepository
import tf.monochrome.android.domain.model.LinkArea
import tf.monochrome.android.domain.model.LinkShape
import tf.monochrome.android.domain.model.StudioLook
import tf.monochrome.android.domain.model.VisualLink
import tf.monochrome.android.domain.model.VisualLinkTarget
import tf.monochrome.android.domain.model.VisualLinkTargets
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.musiclinks.LinkTargetInfo
import tf.monochrome.android.ui.musiclinks.MusicLinkEditor
import tf.monochrome.android.ui.musiclinks.MusicLinkHost
import tf.monochrome.android.ui.musiclinks.MusicLinkOptionsSheet
import tf.monochrome.android.ui.musiclinks.WhatYouHearRow
import tf.monochrome.android.ui.musiclinks.followsText
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.player.VisualLinkLive

/** A link being made or changed: it runs in the preview before it is saved. */
data class LinkDraft(val link: VisualLink, val isNew: Boolean)

/**
 * Making, changing and removing visual music links, for a view model that
 * shows them. Owns the draft, the one link the editor has open, so the
 * preview can run it as it is shaped and Cancel can drop it without a trace.
 */
class VisualLinkEditing(
    private val scope: CoroutineScope,
    private val repo: MusicLinksRepository,
) {
    val links: StateFlow<List<VisualLink>> =
        repo.visualLinks.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val allPaused: StateFlow<Boolean> =
        repo.allPaused.stateIn(scope, SharingStarted.Eagerly, false)

    private val _draft = MutableStateFlow<LinkDraft?>(null)
    val draft: StateFlow<LinkDraft?> = _draft.asStateFlow()

    init {
        // A screen closed with its editor open leaves nothing running.
        scope.coroutineContext[Job]?.invokeOnCompletion {
            val open = _draft.value?.link
            if (open != null) repo.editing.compareAndSet(open, null)
        }
    }

    /** The draft, here and in [MusicLinksRepository.editing], where the app runs it too. */
    private fun setDraft(d: LinkDraft?) {
        _draft.value = d
        repo.editing.value = d?.link
    }

    /** What a preview runs: [MusicLinksRepository.withDraft] on this editor's draft. */
    val previewLinks: StateFlow<List<VisualLink>> =
        combine(links, allPaused, _draft) { saved, paused, draft ->
            MusicLinksRepository.withDraft(saved, paused, draft?.link)
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun linkFor(key: String): VisualLink? = links.value.firstOrNull { it.target == key }

    /**
     * Opens the editor on [key]'s link, or on a new one resting at [current],
     * the setting's value now, and reaching halfway to its roomier end.
     */
    fun start(key: String, current: Float) {
        val target = VisualLinkTargets.byKey(key) ?: return
        val saved = linkFor(key)
        setDraft(
            if (saved != null) {
                LinkDraft(saved, isNew = false)
            } else {
                val (lowest, highest) = LinkShape.startingRange(current, target.min, target.max)
                LinkDraft(VisualLink(key, LinkShape(lowest = lowest, highest = highest)), isNew = true)
            },
        )
    }

    fun update(shape: LinkShape) {
        val d = _draft.value ?: return
        val target = VisualLinkTargets.byKey(d.link.target) ?: return
        setDraft(d.copy(link = d.link.copy(shape = shape.clamped(target.min, target.max))))
    }

    /** Saves the draft. A link that was paused stays paused. */
    fun commit() {
        val d = _draft.value ?: return
        _draft.value = null
        scope.launch {
            repo.put(d.link)
            // Held until the saved one is in place, so nothing snaps back for a frame.
            repo.editing.compareAndSet(d.link, null)
        }
    }

    fun cancel() {
        setDraft(null)
    }

    /** Removes [key]'s link and hands it back, for an undo. */
    fun remove(key: String): VisualLink? {
        val saved = linkFor(key)
        if (_draft.value?.link?.target == key) setDraft(null)
        if (saved != null) scope.launch { repo.remove(key) }
        return saved
    }

    fun restore(link: VisualLink) {
        scope.launch { repo.put(link) }
    }

    fun setPaused(key: String, paused: Boolean) {
        scope.launch { repo.setPaused(key, paused) }
    }

    fun setAllPaused(paused: Boolean) {
        scope.launch { repo.setAllPaused(paused) }
    }
}

/** The editor's view of a visual setting. */
@Composable
fun VisualLinkTarget.info(): LinkTargetInfo =
    LinkTargetInfo(label = fullLabel(), min = min, max = max, format = format)

/** "Turn on God rays to see this move", or null when nothing is missing. */
@Composable
fun VisualLinkTarget.needsText(look: StudioLook): String? = when (unmet(look)) {
    null, VisualLinkTarget.Need.NOTHING -> null
    VisualLinkTarget.Need.GOD_RAYS -> stringResource(R.string.music_link_needs_god_rays)
    VisualLinkTarget.Need.BACKLIGHT -> stringResource(R.string.music_link_needs_backlight)
    VisualLinkTarget.Need.BASS_REACTION -> stringResource(R.string.music_link_needs_bass)
    VisualLinkTarget.Need.LIQUID_GLASS -> stringResource(R.string.music_link_needs_glass)
    VisualLinkTarget.Need.WAVE -> stringResource(R.string.music_link_needs_wave)
    VisualLinkTarget.Need.SHADOW -> stringResource(R.string.music_link_needs_shadow)
    VisualLinkTarget.Need.GLASS_ON -> stringResource(
        if (area == LinkArea.PLAYER) R.string.music_link_needs_player_glass else R.string.music_link_needs_panels_glass,
    )
    VisualLinkTarget.Need.BACKDROP -> stringResource(R.string.music_link_needs_backdrop)
    VisualLinkTarget.Need.GLASS_SHADOW -> stringResource(R.string.music_link_needs_glass_shadow)
    VisualLinkTarget.Need.TV -> stringResource(R.string.music_link_needs_crt)
    VisualLinkTarget.Need.TV_CRT -> stringResource(R.string.music_link_needs_crt_look)
    VisualLinkTarget.Need.TV_ROYALE -> stringResource(R.string.music_link_needs_royale_look)
    VisualLinkTarget.Need.AMBIENT -> stringResource(R.string.music_link_needs_ambient)
    VisualLinkTarget.Need.ART_GLOW -> stringResource(R.string.music_link_needs_art_glow)
}

/**
 * The setting's name with its part of the app in front ("Player · Frost"), as
 * the Player and UI panels tabs share every slider name. Lyrics settings, the
 * first links there were, keep their bare names.
 */
@Composable
fun VisualLinkTarget.fullLabel(): String =
    if (area == LinkArea.LYRICS) stringResource(label)
    else stringResource(area.title) + " · " + stringResource(label)

/** What the Studio's overlay is showing: nothing, the options for a setting, or the editor. */
@Stable
class StudioLinkUi {
    /** The setting whose options sheet is open. */
    var sheetFor by mutableStateOf<String?>(null)

    /** The link just removed, while its undo is on offer. */
    var removed by mutableStateOf<VisualLink?>(null)
}

/**
 * The link host for the Studio's sliders: [live] for the values this frame,
 * [links] for which are linked and their reach.
 */
@Composable
fun rememberVisualLinkHost(
    links: State<List<VisualLink>>,
    live: VisualLinkLive,
    onOpen: (String) -> Unit,
): MusicLinkHost {
    val keys = remember(links) { derivedStateOf { links.value.map { it.target }.toSet() } }
    val ranges = remember(links) {
        derivedStateOf { links.value.associate { it.target to (it.shape.lowest..it.shape.highest) } }
    }
    val open by rememberUpdatedState(onOpen)
    return remember(live, keys, ranges) {
        MusicLinkHost(keys, { live.values }, ranges, { open(it) })
    }
}

/**
 * The Studio's link sheet, editor and undo bar, drawn over whichever tab is up, in
 * the same window, as siblings of [hazeState]'s source, so their glass has the
 * tab behind it to frost.
 */
@Composable
fun BoxScope.StudioLinkOverlay(
    ui: StudioLinkUi,
    editing: VisualLinkEditing,
    look: StudioLook,
    live: VisualLinkLive,
    tap: SpectrumAnalyzerTap,
    hazeState: HazeState,
) {
    val links by editing.links.collectAsStateWithLifecycle()
    val draft by editing.draft.collectAsStateWithLifecycle()

    val sheetKey = ui.sheetFor
    val sheetTarget = sheetKey?.let { VisualLinkTargets.byKey(it) }
    if (sheetKey != null && sheetTarget != null && draft == null) {
        val saved = links.firstOrNull { it.target == sheetKey }
        val need = sheetTarget.needsText(look)
        val follows = saved?.let { followsText(it.shape, it.paused) }
        MusicLinkOptionsSheet(
            title = sheetTarget.fullLabel(),
            valueText = sheetTarget.format(sheetTarget.read(look)),
            linked = saved != null,
            paused = saved?.paused == true,
            followsText = listOfNotNull(follows, need).joinToString("\n").ifEmpty { null },
            cannotReason = null,
            hazeState = hazeState,
            onMove = {
                editing.start(sheetKey, sheetTarget.read(look))
                ui.sheetFor = null
            },
            onChange = {
                editing.start(sheetKey, sheetTarget.read(look))
                ui.sheetFor = null
            },
            onTogglePause = {
                editing.setPaused(sheetKey, !(saved?.paused ?: false))
                ui.sheetFor = null
            },
            onRemove = {
                ui.removed = editing.remove(sheetKey)
                ui.sheetFor = null
            },
            onDismiss = { ui.sheetFor = null },
        )
    }

    val d = draft
    val target = d?.let { VisualLinkTargets.byKey(it.link.target) }
    if (d != null && target != null) {
        val spectrum = tf.monochrome.android.ui.musiclinks.rememberEditorSpectrum(tap)
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
            hazeState = hazeState,
            onDone = editing::commit,
            onCancel = editing::cancel,
            onRemove = if (d.isNew) null else ({ ui.removed = editing.remove(key) }),
            listensTo = { WhatYouHearRow() },
        )
    }

    val removed = ui.removed
    if (removed != null && draft == null && ui.sheetFor == null) {
        LaunchedEffect(removed) {
            delay(UNDO_MS)
            if (ui.removed == removed) ui.removed = null
        }
        val label = VisualLinkTargets.byKey(removed.target)?.fullLabel() ?: removed.target
        UndoBar(
            text = stringResource(R.string.music_link_removed, label),
            hazeState = hazeState,
            onUndo = {
                editing.restore(removed)
                ui.removed = null
            },
        )
    }
}

/** "Removed the link for Exposure. Undo", over the bottom of the screen. */
@Composable
fun BoxScope.UndoBar(text: String, hazeState: HazeState?, onUndo: () -> Unit) {
    GlassPanel(
        hazeState = hazeState,
        glass = LocalMiniPlayerGlass.current,
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomChromeNow.current),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Box {
                GlassPill(onClick = onUndo, hazeState = hazeState, height = 40.dp) {
                    Text(
                        stringResource(R.string.music_link_undo),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 18.dp),
                    )
                }
            }
        }
    }
}

/** How long an undo stays on offer. */
private const val UNDO_MS = 6000L

/**
 * Music links for sliders outside the Studio, such as the CRT effect's in
 * Settings › Appearance: the same ♪, sheet, editor and undo, on the Studio's own
 * view model, so a link made here is the one the Studio and the Music links page
 * show. [content] is the haze source the sheet frosts.
 */
@Composable
fun StudioMusicLinkScope(
    viewModel: LyricsFxStudioViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val linkUi = remember { StudioLinkUi() }
    val linkHaze = dev.chrisbanes.haze.rememberHazeState()
    val savedLinks = viewModel.musicLinks.links.collectAsStateWithLifecycle()
    val previewLinks by viewModel.musicLinks.previewLinks.collectAsStateWithLifecycle()
    val linkLive = tf.monochrome.android.ui.player.rememberVisualLinkDriver(
        tap = viewModel.spectrumAnalyzer,
        links = previewLinks,
        active = true,
        delayMs = tf.monochrome.android.ui.player.LocalOutputSyncMs.current,
    )
    val linkHost = rememberVisualLinkHost(savedLinks, linkLive) { linkUi.sheetFor = it }
    val fx by viewModel.fx.collectAsStateWithLifecycle()
    val playerGlass by viewModel.playerGlass.collectAsStateWithLifecycle()
    val miniPlayerGlass by viewModel.miniPlayerGlass.collectAsStateWithLifecycle()
    val tvTuning by viewModel.appTvTuning.collectAsStateWithLifecycle()
    val tvLook by viewModel.appTvShader.collectAsStateWithLifecycle()
    val ambient by viewModel.ambient.collectAsStateWithLifecycle()
    val look = StudioLook(fx, playerGlass, miniPlayerGlass, tvTuning, tvLook, ambient)
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(linkHaze)) {
            androidx.compose.runtime.CompositionLocalProvider(
                tf.monochrome.android.ui.musiclinks.LocalMusicLinkHost provides linkHost,
                tf.monochrome.android.ui.player.LocalVisualLinks provides linkLive,
            ) { content() }
        }
        StudioLinkOverlay(
            ui = linkUi,
            editing = viewModel.musicLinks,
            look = look,
            live = linkLive,
            tap = viewModel.spectrumAnalyzer,
            hazeState = linkHaze,
        )
    }
}
