package tf.monochrome.android.ui.settings

import tf.monochrome.android.ui.components.UiText
import tf.monochrome.android.ui.navigation.popBackStackSafe
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import tf.monochrome.android.domain.model.RoomKind
import tf.monochrome.android.domain.model.RoomShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
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
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.ChannelLayout
import tf.monochrome.android.domain.model.RendererMode
import tf.monochrome.android.domain.model.RendererProfile
import tf.monochrome.android.domain.model.SpeakerChannel
import tf.monochrome.android.domain.model.speakers
import javax.inject.Inject
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

@HiltViewModel
class AtmosRendererViewModel @Inject constructor(
    private val preferences: PreferencesManager,
    private val channelDetector: tf.monochrome.android.audio.dsp.ChannelDetectorProcessor,
    private val atmosProcessor: tf.monochrome.android.audio.atmos.AtmosAudioProcessor,
    private val roomStore: tf.monochrome.android.audio.atmos.AtmosRoomStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    /** The measured or custom room in use, once it is ready; null for the built-in one or while it is made. */
    val room: StateFlow<tf.monochrome.android.domain.model.AtmosRoom?> = roomStore.current

    private val _measuredRooms = kotlinx.coroutines.flow.MutableStateFlow<List<java.io.File>>(emptyList())

    /** The measured rooms that have been added (the room database, or a file). */
    val measuredRooms: StateFlow<List<java.io.File>> = _measuredRooms

    /** Deletes a measured room; the built-in one takes its place if it was the one in use. */
    fun deleteRoom(file: java.io.File) {
        roomStore.delete(file)
        _measuredRooms.value = roomStore.measured()
        val current = profile.value
        if (current.roomFile == file.absolutePath) {
            update(current.copy(roomKind = tf.monochrome.android.domain.model.RoomKind.BUILT_IN, roomFile = null))
        }
    }

    /**
     * Live SOFA apply outcome from the render pipeline (path → accepted).
     * Lets the screen say "this .sofa was REJECTED — running built-in KEMAR"
     * instead of silently claiming the custom HRTF is active.
     */
    val sofaStatus: StateFlow<Pair<String, Boolean>?> = atmosProcessor.sofaStatus

    /** Live detected input format + per-channel peaks, drives the channel map. */
    val channelState:
        StateFlow<tf.monochrome.android.audio.dsp.ChannelDetectorProcessor.ChannelState?> =
        channelDetector.state

    /** Reference-counted metering stake — held only while this screen shows. */
    fun acquireDetector() {
        channelDetector.acquire()
        atmosProcessor.acquireObjectMeter()
    }
    fun releaseDetector() {
        channelDetector.release()
        atmosProcessor.releaseObjectMeter()
    }

    /** The Atmos objects being rendered, with their room positions and levels; null when none. */
    val objects: StateFlow<tf.monochrome.android.audio.atmos.AtmosAudioProcessor.ObjectMeter?> =
        atmosProcessor.objects

    /** How hard each speaker of the 15.1.12 room is driven; null when nothing plays through it. */
    val speakers: StateFlow<FloatArray?> = atmosProcessor.speakers

    /** The 15.1.12 room's speakers, as [tf.monochrome.android.audio.atmos.AtmosNative.nativeRoomLayout] gives them; empty without the native library. */
    val roomLayout: FloatArray by lazy {
        val native = tf.monochrome.android.audio.atmos.AtmosNative
        if (!native.isAvailable) return@lazy FloatArray(0)
        val buf = FloatArray(native.ROOM_MAX_SPEAKERS * native.ROOM_FIELDS)
        val count = runCatching { native.nativeRoomLayout(buf) }.getOrDefault(0)
        buf.copyOf(count.coerceIn(0, native.ROOM_MAX_SPEAKERS) * native.ROOM_FIELDS)
    }

    /** What the Atmos stage did with the stream playing; null when it is out of the chain. */
    val outcome: StateFlow<tf.monochrome.android.audio.atmos.AtmosAudioProcessor.Outcome?> =
        atmosProcessor.outcome

    /** Multichannel → stereo fold toggle (same preference as Settings). */
    val multichannelDownmixEnabled: StateFlow<Boolean> =
        preferences.multichannelDownmixEnabled.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), true,
        )

    fun setMultichannelDownmix(enabled: Boolean) {
        viewModelScope.launch { preferences.setMultichannelDownmixEnabled(enabled) }
    }

    val tidalAtmosPreferred: StateFlow<Boolean> =
        preferences.tidalAtmosPreferred.stateIn(
            viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), false,
        )

    fun setTidalAtmosPreferred(enabled: Boolean) {
        viewModelScope.launch { preferences.setTidalAtmosPreferred(enabled) }
    }

    // In-memory working copy so slider drags update the UI instantly; the
    // DataStore write is debounced to the drag's tail (same approach the Player
    // Visuals Studio uses for its glass sliders).
    private val _profile = MutableStateFlow(RendererProfile.DEFAULT)
    val profile: StateFlow<RendererProfile> = _profile.asStateFlow()
    private var persistJob: Job? = null

    // Every .sofa kept in app storage is a selectable preset — imports and
    // database downloads accumulate here instead of replacing each other.
    private val _sofaPresets = MutableStateFlow<List<java.io.File>>(emptyList())
    val sofaPresets: StateFlow<List<java.io.File>> = _sofaPresets.asStateFlow()

    init {
        refreshFromStorage()
    }

    /**
     * Re-syncs the working profile and the preset list from disk. Also called
     * when the screen re-enters composition: the HRTF database screen writes
     * the profile and adds preset files behind this ViewModel's back.
     */
    fun refreshFromStorage() {
        _measuredRooms.value = roomStore.measured()
        viewModelScope.launch { _profile.value = preferences.rendererProfile.first() }
        viewModelScope.launch(Dispatchers.IO) { rescanSofaDir() }
    }

    private fun rescanSofaDir() {
        _sofaPresets.value = java.io.File(context.filesDir, "hrtf")
            .listFiles { f -> f.isFile && f.name.endsWith(".sofa", ignoreCase = true) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
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

    /**
     * Copies a user-picked .sofa (a SAF content URI) into app storage and points
     * the profile's hrtfProfileId at it, so [AtmosAudioProcessor] can load it as
     * the binaural HRTF. The copy is kept because the content URI grant is not
     * durable across restarts; the file's own name is preserved for the UI.
     */
    fun importSofa(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = java.io.File(context.filesDir, "hrtf").apply { mkdirs() }
            val name = sofaDisplayName(uri)
            val dest = java.io.File(dir, name)
            val ok = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } ?: error("cannot open $uri")
            }.isSuccess
            if (ok) {
                // The import joins the preset list (same-name files overwrite)
                // and becomes the active HRTF. Importing is an explicit request
                // to use one, so it also re-enables the binauralizer.
                rescanSofaDir()
                update(
                    _profile.value.copy(
                        hrtfProfileId = dest.absolutePath, hrtfEnabled = true,
                        mode = RendererMode.OBJECT_RENDER,
                    )
                )
            }
        }
    }

    /**
     * The single spatial switch: on = SOFA/HRTF binaural render
     * (OBJECT_RENDER), off = the coefficient downmix renderer handles every
     * Atmos track (PASSTHROUGH — the Atmos processor drops out and the
     * DownmixProcessor folds the decoded bed). Mode and hrtfEnabled always
     * move together so no half-spatial combination can exist.
     */
    fun setSpatial(enabled: Boolean) {
        update(
            _profile.value.copy(
                hrtfEnabled = enabled,
                // The listener has chosen: no longer by what is plugged in.
                hrtfAuto = false,
                mode = if (enabled) RendererMode.OBJECT_RENDER else RendererMode.PASSTHROUGH,
            )
        )
    }

    /**
     * Reverts to the baked HRTF (keeping spatial on). Stored SOFA presets
     * are kept — they stay selectable in the preset list.
     */
    fun useBuiltInHrtf() {
        update(
            _profile.value.copy(
                hrtfProfileId = null, hrtfEnabled = true, mode = RendererMode.OBJECT_RENDER,
            )
        )
    }

    /** Selects a stored SOFA preset as the active HRTF (turns spatial on). */
    fun selectSofa(file: java.io.File) {
        update(
            _profile.value.copy(
                hrtfProfileId = file.absolutePath, hrtfEnabled = true,
                mode = RendererMode.OBJECT_RENDER,
            )
        )
    }

    /** Deletes a stored SOFA preset; falls back to built-in if it was active. */
    fun deleteSofa(file: java.io.File) {
        if (_profile.value.hrtfProfileId == file.absolutePath) {
            update(_profile.value.copy(hrtfProfileId = null))
        }
        viewModelScope.launch(Dispatchers.IO) {
            file.delete()
            rescanSofaDir()
        }
    }

    // Status of the "add built-in Atmos test track" action, shown in the UI.
    private val _testTrackStatus = MutableStateFlow<UiText?>(null)
    val testTrackStatus: StateFlow<UiText?> = _testTrackStatus.asStateFlow()

    /**
     * Installs the bundled Atmos test clip (a real E-AC-3 JOC channel check) into
     * the Music library via MediaStore, so it plays through the normal, validated
     * path. Idempotent — skips if a copy is already indexed. The user refreshes
     * the Local library to see it (the scanner is MediaStore-backed).
     */
    fun installTestTrack() {
        _testTrackStatus.value = UiText.Res(R.string.atmos_adding)
        viewModelScope.launch(Dispatchers.IO) {
            // MediaStore canonicalizes the extension for audio/mp4 to ".m4a" —
            // inserting "….mp4" came back as "….mp4.m4a", so an equality check
            // on the pre-rename name never matched and every tap added another
            // copy. Use the canonical name and match existing copies by prefix.
            val name = "Tryptify Atmos Test.m4a"
            val resolver = context.contentResolver
            val audio = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val existing = runCatching {
                resolver.query(audio, arrayOf(MediaStore.Audio.Media._ID),
                    "${MediaStore.Audio.Media.DISPLAY_NAME} LIKE ?",
                    arrayOf("Tryptify Atmos Test%"), null)
                    ?.use { c ->
                        buildList { while (c.moveToNext()) add(c.getLong(0)) }
                    } ?: emptyList()
            }.getOrDefault(emptyList())

            _testTrackStatus.value = if (existing.isNotEmpty()) {
                // Heal earlier duplicate inserts: keep one copy, drop the rest
                // (they are app-owned rows, deletable without user consent).
                existing.drop(1).forEach { id ->
                    runCatching {
                        resolver.delete(ContentUris.withAppendedId(audio, id), null, null)
                    }
                }
                UiText.Res(R.string.atmos_test_already)
            } else runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/")
                    }
                }
                val uri = resolver.insert(audio, values) ?: error("insert rejected")
                resolver.openOutputStream(uri)?.use { out ->
                    context.assets.open("atmos_test.mp4").use { it.copyTo(out) }
                } ?: error("cannot open output")
                UiText.Res(R.string.atmos_test_added)
            }.getOrElse { UiText.Res(R.string.atmos_test_failed, listOf(it.message.orEmpty())) }
        }
    }

    private fun sofaDisplayName(uri: Uri): String {
        val raw = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        } ?: uri.lastPathSegment ?: "custom.sofa"
        // Sanitize to a safe filename, and guarantee a .sofa suffix.
        val safe = raw.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (safe.endsWith(".sofa", ignoreCase = true)) safe else "$safe.sofa"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtmosRendererScreen(
    navController: NavController,
    viewModel: AtmosRendererViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val sofaPresets by viewModel.sofaPresets.collectAsStateWithLifecycle()
    val detected by viewModel.channelState.collectAsStateWithLifecycle()
    val objects by viewModel.objects.collectAsStateWithLifecycle()
    val roomSpeakers by viewModel.speakers.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val measuredRooms by viewModel.measuredRooms.collectAsStateWithLifecycle()
    val outcome by viewModel.outcome.collectAsStateWithLifecycle()

    // Re-sync on every (re-)entry: the HRTF database screen writes the profile
    // and adds preset files behind this ViewModel's back, and navigating back
    // here recomposes the screen fresh.
    LaunchedEffect(Unit) { viewModel.refreshFromStorage() }

    // Per-channel metering for the live channel map runs only while this
    // screen is visible (same acquire/release contract as the spectrum tap):
    // started, so not in the background and not with the phone locked.
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) {
        viewModel.acquireDetector()
        onStopOrDispose { viewModel.releaseDetector() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_atmos_renderer_configuration)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        // The 3D room, for the page and for the small picture of it that
        // stays in the corner once the page has scrolled past it. One of the
        // two is drawn at a time, both turned the same way.
        val roomLayout = viewModel.roomLayout
        val showsRoom = !profile.speakerRender && roomLayout.isNotEmpty()
        val roomYaw = remember { mutableFloatStateOf(RoomCamera.START_YAW) }
        var roomInView by remember { mutableStateOf(true) }
        val roomView: @Composable (Modifier, Boolean) -> Unit = { modifier, compact ->
            val d = detected
            // With no object through or towards it, the stream's own channels
            // on the speakers they would play from.
            val fromChannels = remember(d, roomLayout) {
                d?.let { roomLevelsFromChannels(roomLayout, it.channelNames, it.peaksDb) }
            }
            // The room that is heard: the listener's own at once, a
            // measured one when its file gives a size, else the built-in.
            val shape = when (profile.roomKind) {
                RoomKind.CUSTOM -> RoomShape.of(profile.customRoom)
                RoomKind.MEASURED -> room?.let { RoomShape.of(it) }
                else -> null
            } ?: RoomShape.BUILT_IN
            AtmosRoom3d(
                layout = roomLayout,
                speakers = roomSpeakers ?: fromChannels,
                objects = objects,
                // Its walls lean as the panning envelope does, when that is on:
                // the view shows the space objects may use.
                shape = shape.within(profile.panEnvelope.clamped()),
                roomAmount = profile.roomAmount,
                decaySeconds = room?.decaySeconds?.takeIf { it > 0f } ?: BUILT_IN_ROOM_DECAY,
                modifier = modifier,
                sound = profile.roomSound,
                compact = compact,
                yaw = roomYaw,
            )
        }
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()

        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(bottom = LocalBottomChromeInset.current)
                    .padding(horizontal = 20.dp),
            ) {
                // ── Channel map ────────────────────────────────────────────────
                SectionHeader(stringResource(R.string.atmos_channel_map))
                val d = detected
                Text(
                    if (d != null) {
                        val rate = if (d.sampleRate % 1000 == 0) "${d.sampleRate / 1000} kHz"
                        else "${d.sampleRate} Hz"
                        // An Atmos stream's decoder puts out its 5.1 core alone: "5.1 · 6 ch"
                        // read as if that were all of it. While objects render, say so.
                        val o = objects
                        when {
                            // Watched and not rendered: the dots are where the
                            // mix puts its objects, and the line says they are
                            // not what is being heard.
                            o != null && outcome == null ->
                                stringResource(R.string.atmos_live_watched, o.count, d.layoutName, rate)
                            o != null -> stringResource(R.string.atmos_live_objects, o.count, d.layoutName, rate)
                            // More than stereo and no objects on the map: say which
                            // of the two reasons it is, instead of "6 ch" and nothing.
                            d.channelCount > 2 && outcome == null ->
                                stringResource(R.string.atmos_live_not_rendering, d.layoutName, rate)
                            d.channelCount > 2 ->
                                stringResource(R.string.atmos_live_no_objects, d.layoutName, rate)
                            else -> stringResource(R.string.atmos_live_levels, d.layoutName, d.channelCount, rate)
                        }
                    } else {
                        stringResource(R.string.atmos_speakers_idle)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                if (!showsRoom) {
                    // Real speakers: the layout that is wired up, as it is driven.
                    SpeakerLayoutMap(
                        layout = if (profile.speakerRender) profile.layout else ChannelLayout.STEREO,
                        detected = d,
                        objects = objects,
                        accent = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.15f),
                    )
                } else {
                    // Headphones: the 15.1.12 room the render plays in. Scrolled
                    // out of sight its place is kept and it is not drawn here.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.15f)
                            // Clipped by the scroll viewport, so this is the part on screen.
                            .onGloballyPositioned { roomInView = it.boundsInWindow().height > 0f },
                    ) {
                        if (roomInView) roomView(Modifier.fillMaxSize(), false)
                    }
                    SettingSwitchItem(
                        title = stringResource(R.string.atmos_room_sound),
                        subtitle = stringResource(R.string.atmos_room_sound_summary),
                        checked = profile.roomSound,
                        onCheckedChange = { viewModel.update(profile.copy(roomSound = it)) },
                    )
                }
                Spacer(Modifier.height(20.dp))

                // ── Built-in test track ────────────────────────────────────────
                SectionHeader(stringResource(R.string.atmos_test_track))
                val testStatus by viewModel.testTrackStatus.collectAsStateWithLifecycle()
                Text(
                    stringResource(R.string.atmos_a_bundled_e_ac_3_joc_channel_check_a_voice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { viewModel.installTestTrack() }) {
                    Text(stringResource(R.string.atmos_add_atmos_test_track_to_library))
                }
                testStatus?.let {
                    Text(
                        it.resolve(androidx.compose.ui.platform.LocalContext.current),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(20.dp))

                // ── Multichannel downmix ───────────────────────────────────────
                // Replaces the old read-only "Output Layout" (binaural pipeline)
                // blurb: the setting that actually governs multichannel output —
                // the fixed-matrix fold to stereo — lives here now. Same
                // preference as the toggle in Settings › Audio › Output, which now
                // sits directly under the row that opens this screen.
                SectionHeader(stringResource(R.string.atmos_downmix))
                val downmixEnabled by viewModel.multichannelDownmixEnabled.collectAsStateWithLifecycle()
                SettingSwitchItem(
                    title = stringResource(R.string.settings_downmix_multichannel_to_stereo),
                    subtitle = if (downmixEnabled) {
                        stringResource(R.string.settings_downmix_on)
                    } else {
                        stringResource(R.string.settings_downmix_off)
                    },
                    checked = downmixEnabled,
                    onCheckedChange = { viewModel.setMultichannelDownmix(it) },
                )
                // Master trim inside the fold (the peqdb Downmix Renderer's
                // --master-gain-db): the verbatim matrix runs hot, this pulls it
                // below clipping. Applies to both matrices and the Atmos fallback.
                LabeledSlider(
                    title = stringResource(R.string.atmos_downmix_preamp),
                    valueText = "%+.1f dB".format(profile.downmixPreampDb),
                    value = profile.downmixPreampDb,
                    range = -24f..6f,
                    enabled = downmixEnabled,
                    onValueChange = { viewModel.update(profile.copy(downmixPreampDb = it)) },
                )
                SettingSwitchItem(
                    title = stringResource(R.string.atmos_lfe_low_pass_125_hz),
                    subtitle = stringResource(R.string.atmos_butterworth_4th_order_on_the_lfe_feed_the_dry),
                    checked = profile.lfeLowpass,
                    onCheckedChange = { viewModel.update(profile.copy(lfeLowpass = it)) },
                )
                Spacer(Modifier.height(20.dp))

                // ── Sources ────────────────────────────────────────────────────
                SectionHeader(stringResource(R.string.atmos_sources))
                val tidalAtmos by viewModel.tidalAtmosPreferred.collectAsStateWithLifecycle()
                SettingSwitchItem(
                    title = stringResource(R.string.atmos_tidal_dolby_atmos),
                    subtitle = if (tidalAtmos) {
                        stringResource(R.string.atmos_tidal_on)
                    } else {
                        stringResource(R.string.atmos_tidal_off)
                    },
                    checked = tidalAtmos,
                    onCheckedChange = { viewModel.setTidalAtmosPreferred(it) },
                )
                Spacer(Modifier.height(20.dp))

                // ── Speakers — render objects to a physical layout ───────────
                // Off (default) leaves every stereo path above and below exactly as
                // it was. On: Atmos objects render to the layout (auto-detected
                // from the connected HDMI/USB output, or picked here), the fold is
                // bypassed, and the track carries the layout's real channel mask.
                SectionHeader(stringResource(R.string.atmos_speakers))
                SettingSwitchItem(
                    title = stringResource(R.string.atmos_render_atmos_to_speakers),
                    subtitle = if (profile.speakerRender) {
                        stringResource(R.string.atmos_speakers_on)
                    } else {
                        stringResource(R.string.atmos_speakers_off)
                    },
                    checked = profile.speakerRender,
                    onCheckedChange = { viewModel.update(profile.copy(speakerRender = it)) },
                )
                if (profile.speakerRender) {
                    SettingSwitchItem(
                        title = stringResource(R.string.atmos_detect_layout_from_the_output),
                        subtitle = stringResource(R.string.atmos_uses_the_channel_count_the_hdmi_usb_device),
                        checked = profile.autoDetectLayout,
                        onCheckedChange = { viewModel.update(profile.copy(autoDetectLayout = it)) },
                    )
                    if (!profile.autoDetectLayout) {
                        ChannelLayout.entries.filter { it.isMultichannel }.forEach { layout ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.update(profile.copy(layout = layout)) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = profile.layout == layout,
                                    onClick = { viewModel.update(profile.copy(layout = layout)) },
                                )
                                Text(
                                    stringResource(R.string.atmos_layout_option, layout.label, layout.channelCount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))

                // ── Spatial render — the ONE option beyond the fold ───────────
                // Off (default): every Atmos track goes through the coefficient
                // downmix renderer above. On: objects are binauralized via the
                // built-in KEMAR or a SOFA HRTF. Renderer mode follows the switch
                // (PASSTHROUGH ↔ OBJECT_RENDER) — no separate mode setting.
                SectionHeader(stringResource(R.string.atmos_spatial_render))
                val sofaPicker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri: Uri? -> if (uri != null) viewModel.importSofa(uri) }
                // .sofa has no registered MIME type, so accept any file and let the
                // native loader reject non-SOFA input.
                val sofaMimes = arrayOf("*/*")
                val spatial = profile.hrtfEnabled || profile.hrtfAuto
                SettingSwitchItem(
                    title = stringResource(R.string.atmos_binaural_render_sofa_hrtf),
                    // Until the switch is used it follows the output: on with
                    // headphones, the fold on a speaker. Shown on, and said so.
                    subtitle = when {
                        profile.hrtfAuto && !profile.hrtfEnabled -> stringResource(R.string.atmos_binaural_auto)
                        profile.hrtfEnabled -> stringResource(R.string.atmos_binaural_on)
                        else -> stringResource(R.string.atmos_binaural_off)
                    },
                    checked = spatial,
                    onCheckedChange = { viewModel.setSpatial(it) },
                )
                if (spatial) {
                    // The headphone render is the 15.1.12 virtual room; Room at 0
                    // is the room's speakers with no reflections or tail.
                    LabeledSlider(
                        title = stringResource(R.string.atmos_room_amount),
                        valueText = "${(profile.roomAmount * 100).toInt()}%",
                        value = profile.roomAmount,
                        range = 0f..1f,
                        onValueChange = { viewModel.update(profile.copy(roomAmount = it)) },
                    )
                    AtmosRoomSection(
                        profile = profile,
                        room = room,
                        measured = measuredRooms,
                        onProfile = viewModel::update,
                        onDelete = viewModel::deleteRoom,
                        onBrowse = { navController.navigateTool(Screen.RoomDatabase) },
                    )
                    if (profile.hrtfProfileId == null) Text(
                        stringResource(R.string.atmos_using_the_built_in_mit_kemar_set_pick_a_sofa),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ) else {
                        val sofaStatus by viewModel.sofaStatus.collectAsStateWithLifecycle()
                        val selectedName = java.io.File(profile.hrtfProfileId!!).name
                        val status = sofaStatus?.takeIf {
                            java.io.File(it.first).name == selectedName
                        }
                        when {
                            status?.second == false -> Text(
                                stringResource(R.string.atmos_sofa_rejected, selectedName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            status?.second == true -> Text(
                                stringResource(R.string.atmos_sofa_loaded, selectedName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> Text(
                                stringResource(R.string.atmos_sofa_selected, selectedName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // Every imported/downloaded .sofa stays on hand as a preset —
                    // tap to switch HRTFs without re-downloading or re-picking.
                    if (sofaPresets.isNotEmpty()) FoldingList(
                        title = stringResource(R.string.atmos_sofa_presets),
                        chosen = sofaPresets
                            .firstOrNull { profile.hrtfEnabled && profile.hrtfProfileId == it.absolutePath }
                            ?.name?.removeSuffix(".sofa"),
                    ) {
                        sofaPresets.forEach { file ->
                            val isSelected =
                                profile.hrtfEnabled && profile.hrtfProfileId == file.absolutePath
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.selectSofa(file) },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.selectSofa(file) },
                                )
                                Text(
                                    file.name.removeSuffix(".sofa"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { viewModel.deleteSofa(file) }) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.settings_delete_preset),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Row {
                        TextButton(onClick = {
                            navController.navigateTool(Screen.HrtfDatabase)
                        }) { Text(stringResource(R.string.atmos_browse_hrtf_database)) }
                        TextButton(onClick = { sofaPicker.launch(sofaMimes) }) {
                            Text(stringResource(R.string.atmos_load_sofa_file))
                        }
                    }
                }
                // The space objects may use, for headphones and loudspeakers
                // alike; the room above takes its shape.
                val envelope = profile.panEnvelope.clamped()
                SettingSwitchItem(
                    title = stringResource(R.string.atmos_envelope),
                    subtitle = stringResource(R.string.atmos_envelope_summary),
                    checked = envelope.enabled,
                    onCheckedChange = { viewModel.update(profile.copy(panEnvelope = envelope.copy(enabled = it))) },
                )
                if (envelope.enabled) {
                    val min = tf.monochrome.android.domain.model.PanEnvelope.MIN_CEILING
                    LabeledSlider(
                        title = stringResource(R.string.atmos_envelope_width),
                        valueText = "${(envelope.ceilingWidth * 100).toInt()}%",
                        value = envelope.ceilingWidth,
                        range = min..1f,
                        onValueChange = { viewModel.update(profile.copy(panEnvelope = envelope.copy(ceilingWidth = it))) },
                    )
                    LabeledSlider(
                        title = stringResource(R.string.atmos_envelope_depth),
                        valueText = "${(envelope.ceilingDepth * 100).toInt()}%",
                        value = envelope.ceilingDepth,
                        range = min..1f,
                        onValueChange = { viewModel.update(profile.copy(panEnvelope = envelope.copy(ceilingDepth = it))) },
                    )
                    LabeledSlider(
                        title = stringResource(R.string.atmos_envelope_curve),
                        valueText = String.format(java.util.Locale.getDefault(), "%.2f", envelope.curve),
                        value = envelope.curve,
                        range = tf.monochrome.android.domain.model.PanEnvelope.MIN_CURVE..tf.monochrome.android.domain.model.PanEnvelope.MAX_CURVE,
                        onValueChange = { viewModel.update(profile.copy(panEnvelope = envelope.copy(curve = it))) },
                    )
                }
                Spacer(Modifier.height(16.dp))

                TextButton(onClick = { viewModel.reset() }) { Text(stringResource(R.string.fx_reset_to_defaults)) }
                Spacer(Modifier.height(32.dp))
            }
            RoomThumbnail(
                visible = showsRoom && !roomInView,
                onClick = { scope.launch { scroll.animateScrollTo(0) } },
                modifier = Modifier.align(Alignment.TopEnd),
            ) { roomView(Modifier.fillMaxSize(), true) }
        }
    }
}

/**
 * The room as a small picture in the page's top corner, for when the page
 * has scrolled past the room itself: what the settings further down do can
 * still be watched. A tap goes back up to the room. It is a picture, not a
 * pane of glass: plain surface colour behind it, so it reads over the rows
 * it covers.
 */
@Composable
private fun RoomThumbnail(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    room: @Composable () -> Unit,
) {
    val millis = tf.monochrome.android.ui.theme.motionMillis(180)
    val corner = TransformOrigin(1f, 0f)
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(millis)) + scaleIn(tween(millis), initialScale = 0.7f, transformOrigin = corner),
        exit = fadeOut(tween(millis)) + scaleOut(tween(millis), targetScale = 0.7f, transformOrigin = corner),
    ) {
        val shape = RoundedCornerShape(14.dp)
        Box(
            modifier = Modifier
                .padding(top = 8.dp, end = 12.dp)
                .width(ROOM_THUMBNAIL_WIDTH)
                .aspectRatio(1.15f)
                .shadow(8.dp, shape)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)
                .clickable(onClickLabel = stringResource(R.string.atmos_room_thumbnail), onClick = onClick),
        ) { room() }
    }
}

// ── Building blocks ─────────────────────────────────────────────────────────

/** The built-in room's tail, in seconds (VirtualRoom::kTailSeconds). */
private const val BUILT_IN_ROOM_DECAY = 0.32f

/** The corner picture of the room: about the size of a video that plays on over another app. */
private val ROOM_THUMBNAIL_WIDTH = 156.dp

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

@Composable
internal fun LabeledSlider(
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
 * Top-down SQUARE room plan: the listener at centre, each speaker projected
 * from its azimuth onto the room's walls (corners for the diagonals, like a
 * real rectangular room). While a track plays, [detected] drives the map:
 * speakers come from the channel detector's assumed layout and each one glows
 * with its channel's live level (volume-rendered light — brighter and wider
 * the louder the channel), beaming toward the listener. With nothing detected
 * it falls back to [layout]'s speakers with the idle pulse. Height speakers
 * sit on an inner square in a warmer tint; the LFE is a small dot below the
 * listener.
 */
@Composable
private fun SpeakerLayoutMap(
    layout: ChannelLayout,
    detected: tf.monochrome.android.audio.dsp.ChannelDetectorProcessor.ChannelState?,
    objects: tf.monochrome.android.audio.atmos.AtmosAudioProcessor.ObjectMeter?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val liveObjects by rememberUpdatedState(objects)
    // The speakers change with the stream or the setting; the levels arrive
    // ten times a second, and only those are read per frame below.
    val names = detected?.channelNames
    val speakers = remember(names, layout) { names?.let { detectedSpeakers(it) } ?: layout.speakers() }
    // 0..1 level per speaker from the −60..0 dBFS meter range; null = idle map.
    val levels = detected?.let { d ->
        FloatArray(speakers.size) { i ->
            ((d.peaksDb.getOrElse(i) { -120f } + 60f) / 60f).coerceIn(0f, 1f)
        }
    }
    val liveLevels by rememberUpdatedState(levels)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    val heightTint = MaterialTheme.colorScheme.tertiary

    // Measured once per layout, not per speaker per frame: a map of twelve to
    // sixteen labels is more than the measurer's own cache holds, so every one
    // of them used to be laid out again on every frame.
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember(onSurface) {
        TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium, color = onSurface.copy(alpha = 0.85f))
    }
    val labels = remember(speakers, labelStyle, textMeasurer) {
        speakers.map { textMeasurer.measure(it.label, style = labelStyle) }
    }

    // The idle pulse runs only while it is what the map shows: nothing is
    // playing, and the map is in view — it sits in a scrolling column, and
    // scrolled away it used to keep redrawing at the display's rate.
    var inView by remember { mutableStateOf(true) }
    val pulse = if ((detected == null && inView) || tf.monochrome.android.ui.theme.reduceMotion()) {
        tf.monochrome.android.ui.theme.rememberMotionFloat(
            initialValue = 0.55f,
            targetValue = 1f,
            durationMillis = 1600,
            label = "map-pulse",
            still = 1f,
        )
    } else {
        remember { mutableFloatStateOf(0.55f) }
    }

    Box(modifier = modifier) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                // Clipped by the scroll viewport, so this is the part on screen.
                .onGloballyPositioned { inView = it.boundsInWindow().height > 0f }
                .drawWithCache {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val half = min(cx, cy) * 0.82f
                    val listener = Offset(cx, cy)
                    // Each speaker's place and its light at rest, for this size.
                    // The idle pulse only changes how bright that light is, which
                    // the draw passes as alpha, so its paths and gradients are
                    // made here once instead of every frame.
                    val art = speakers.map { s ->
                        val pos = if (s.isLfe) Offset(cx, cy + half * 0.28f)
                        else speakerPosition(s, cx, cy, half)
                        val tint = if (s.isHeight) heightTint else accent
                        val dotScale = when {
                            s.isLfe -> 0.85f
                            s.isHeight -> 0.9f
                            else -> 1f
                        }
                        SpeakerArt(pos, tint, dotScale, speakerGlow(pos, tint, dotScale, 1f, listener))
                    }
                    onDrawBehind {
                        // Square room boundary (slightly rounded corners) + listener.
                        drawRoundRect(
                            color = outline.copy(alpha = 0.25f),
                            topLeft = Offset(cx - half, cy - half),
                            size = androidx.compose.ui.geometry.Size(2f * half, 2f * half),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(half * 0.10f, half * 0.10f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                        )
                        drawCircle(color = onSurface.copy(alpha = 0.5f), radius = half * 0.05f, center = listener)

                        val live = liveLevels
                        art.forEachIndexed { i, a ->
                            val level = live?.getOrNull(i)
                            // Live: glow brightness + reach + dot opacity all render the
                            // channel's volume; a silent channel stays a dim marker.
                            // Idle: the gentle shared pulse.
                            val glowAlpha = if (level != null) 0.10f + 0.70f * level else 0.45f * pulse.value
                            val dotAlpha = if (level != null) 0.35f + 0.65f * level else 0.9f
                            val glow = if (level != null) {
                                speakerGlow(a.pos, a.tint, a.dotScale, 0.8f + 1.5f * level, listener)
                            } else {
                                a.rest
                            }
                            drawSpeaker(a, glow, glowAlpha, dotAlpha, labels[i])
                        }
                        // The Atmos objects, where the mix puts them: left to
                        // right and front to back across the room, a height
                        // object in the warmer tint and larger the higher it
                        // is, each as bright as it is loud. The LFE has no place.
                        liveObjects?.let { o ->
                            for (i in 0 until o.count) {
                                if (o.isLfe(i)) continue
                                // Every object is drawn, a silent one dim: the
                                // parked ones are the mix's bed, and where it
                                // stands is the point. One that is watched and
                                // not rendered has no level: drawn half bright.
                                val level = atmosObjectLevel(o.level(i))
                                val up = o.z(i).coerceIn(0f, 1f)
                                val pos = Offset(
                                    cx + (o.x(i).coerceIn(0f, 1f) * 2f - 1f) * half * 0.92f,
                                    cy + (o.y(i).coerceIn(0f, 1f) * 2f - 1f) * half * 0.92f,
                                )
                                val tint = if (up > 0.25f) heightTint else onSurface
                                val r = half * (0.035f + 0.03f * up)
                                drawCircle(tint.copy(alpha = 0.18f * level), r * 2.4f, pos)
                                drawCircle(tint.copy(alpha = 0.35f + 0.65f * level), r, pos)
                            }
                        }
                    }
                },
        )
    }
}

/**
 * Room positions for the channel detector's assumed layout names — the map
 * counterpart of [tf.monochrome.android.audio.dsp.ChannelDetectorProcessor]'s
 * channel order tables (FLAC/FFmpeg order; 16 ch = 9.1.6). Unknown names
 * (generic "Ch n" layouts) are spread evenly around the ring.
 */
private fun detectedSpeakers(names: List<String>): List<SpeakerChannel> =
    names.mapIndexed { i, n ->
        when (n) {
            "M" -> SpeakerChannel("M", 0f)
            "FL" -> SpeakerChannel("FL", -30f)
            "FR" -> SpeakerChannel("FR", 30f)
            "FC" -> SpeakerChannel("FC", 0f)
            "LFE" -> SpeakerChannel("LFE", 0f, isLfe = true)
            "SL" -> SpeakerChannel("SL", -90f)
            "SR" -> SpeakerChannel("SR", 90f)
            "BL" -> SpeakerChannel("BL", -150f)
            "BR" -> SpeakerChannel("BR", 150f)
            "BLC" -> SpeakerChannel("BLC", -165f)
            "BRC" -> SpeakerChannel("BRC", 165f)
            "BC" -> SpeakerChannel("BC", 180f)
            "TFL" -> SpeakerChannel("TFL", -45f, 45f)
            "TFR" -> SpeakerChannel("TFR", 45f, 45f)
            "TSL" -> SpeakerChannel("TSL", -90f, 45f)
            "TSR" -> SpeakerChannel("TSR", 90f, 45f)
            "TBL" -> SpeakerChannel("TBL", -135f, 45f)
            "TBR" -> SpeakerChannel("TBR", 135f, 45f)
            else -> SpeakerChannel(n, -180f + 360f * (i + 0.5f) / names.size)
        }
    }

/**
 * Screen position for a speaker in the SQUARE room: azimuth 0 = front (up),
 * growing clockwise. The direction vector is projected onto the square's
 * boundary (divide by the larger axis component), so walls hold the cardinal
 * speakers and corners hold the diagonals — like speakers along real walls.
 */
private fun speakerPosition(s: SpeakerChannel, cx: Float, cy: Float, half: Float): Offset {
    // Height speakers pulled onto an inner square so they read as "above".
    val r = half * if (s.isHeight) 0.55f else 1f
    val az = Math.toRadians(s.azimuthDeg.toDouble())
    var dx = sin(az).toFloat()
    var dy = -cos(az).toFloat()
    val m = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
    if (m > 1e-6f) {
        dx /= m
        dy /= m
    }
    return Offset(cx + dx * r, cy + dy * r)
}

/** Where a speaker sits on the map, its colour and size, and its light at rest. */
private class SpeakerArt(val pos: Offset, val tint: Color, val dotScale: Float, val rest: SpeakerGlow)

/**
 * A speaker's light at one level: the beam toward the listener and the bloom
 * at the source, in full-strength colour. How bright it is drawn is the alpha
 * it is drawn with, so the same light serves every frame of the idle pulse.
 */
private class SpeakerGlow(val beam: androidx.compose.ui.graphics.Path?, val beamBrush: Brush?, val bloom: Brush, val radius: Float)

private fun speakerGlow(pos: Offset, tint: Color, dotScale: Float, glowScale: Float, listener: Offset): SpeakerGlow {
    val glowR = 40f * dotScale * glowScale
    // Directional light: a tapered beam from the speaker aimed at the
    // listener — narrow at the source, widening as it travels, fading to
    // nothing. Reach and width grow with the channel's level, so loud
    // channels visibly "shine" into the room centre.
    val toListener = listener - pos
    val dist = toListener.getDistance()
    var beam: androidx.compose.ui.graphics.Path? = null
    var beamBrush: Brush? = null
    if (dist > 1f) {
        val dir = toListener / dist
        val perp = Offset(-dir.y, dir.x)
        val reach = dist * (0.35f + 0.30f * glowScale).coerceAtMost(0.92f)
        val end = pos + dir * reach
        val srcW = glowR * 0.28f
        val endW = glowR * 0.95f
        beam = androidx.compose.ui.graphics.Path().apply {
            moveTo(pos.x + perp.x * srcW, pos.y + perp.y * srcW)
            lineTo(end.x + perp.x * endW, end.y + perp.y * endW)
            lineTo(end.x - perp.x * endW, end.y - perp.y * endW)
            lineTo(pos.x - perp.x * srcW, pos.y - perp.y * srcW)
            close()
        }
        beamBrush = Brush.linearGradient(
            colors = listOf(tint.copy(alpha = 0.85f), tint.copy(alpha = 0f)),
            start = pos,
            end = end,
        )
    }
    // Soft bloom at the source, rendered from the channel's live level.
    val bloom = Brush.radialGradient(
        colors = listOf(tint.copy(alpha = 1f), Color.Transparent),
        center = pos,
        radius = glowR,
    )
    return SpeakerGlow(beam, beamBrush, bloom, glowR)
}

private fun DrawScope.drawSpeaker(
    art: SpeakerArt,
    glow: SpeakerGlow,
    glowAlpha: Float,
    dotAlpha: Float,
    label: androidx.compose.ui.text.TextLayoutResult,
) {
    val pos = art.pos
    if (glowAlpha > 0.01f) {
        if (glow.beam != null && glow.beamBrush != null) {
            drawPath(path = glow.beam, brush = glow.beamBrush, alpha = glowAlpha)
        }
        drawCircle(brush = glow.bloom, radius = glow.radius, center = pos, alpha = glowAlpha)
    }
    // Solid speaker dot.
    drawCircle(color = art.tint.copy(alpha = dotAlpha), radius = 7f * art.dotScale, center = pos)

    // Label just below the dot.
    drawText(
        label,
        topLeft = Offset(pos.x - label.size.width / 2f, pos.y + 9f * art.dotScale),
    )
}
