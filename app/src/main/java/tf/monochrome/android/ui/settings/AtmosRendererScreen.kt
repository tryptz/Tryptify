package tf.monochrome.android.ui.settings

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
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
    @ApplicationContext private val context: Context,
) : ViewModel() {

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
                update(_profile.value.copy(hrtfProfileId = dest.absolutePath, hrtfEnabled = true))
            }
        }
    }

    /**
     * Reverts to the baked HRTF (re-enabling it). Stored SOFA presets are
     * kept — they stay selectable in the preset list.
     */
    fun useBuiltInHrtf() {
        update(_profile.value.copy(hrtfProfileId = null, hrtfEnabled = true))
    }

    /** Selects a stored SOFA preset as the active HRTF. */
    fun selectSofa(file: java.io.File) {
        update(_profile.value.copy(hrtfProfileId = file.absolutePath, hrtfEnabled = true))
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
    private val _testTrackStatus = MutableStateFlow<String?>(null)
    val testTrackStatus: StateFlow<String?> = _testTrackStatus.asStateFlow()

    /**
     * Installs the bundled Atmos test clip (a real E-AC-3 JOC channel check) into
     * the Music library via MediaStore, so it plays through the normal, validated
     * path. Idempotent — skips if a copy is already indexed. The user refreshes
     * the Local library to see it (the scanner is MediaStore-backed).
     */
    fun installTestTrack() {
        _testTrackStatus.value = "Adding…"
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
                "Already in your library — refresh the Local tab to find “Tryptify Atmos Test”."
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
                "Added “Tryptify Atmos Test” — refresh the Local library to play it."
            }.getOrElse { "Couldn't add the test track (${it.message})." }
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
    val profile by viewModel.profile.collectAsState()
    val sofaPresets by viewModel.sofaPresets.collectAsState()

    // Re-sync on every (re-)entry: the HRTF database screen writes the profile
    // and adds preset files behind this ViewModel's back, and navigating back
    // here recomposes the screen fresh.
    LaunchedEffect(Unit) { viewModel.refreshFromStorage() }

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

            // ── Built-in test track ────────────────────────────────────────
            SectionHeader("Test Track")
            val testStatus by viewModel.testTrackStatus.collectAsState()
            Text(
                "A bundled E-AC-3 JOC channel check — a voice moving through the " +
                    "Atmos positions. Add it to your library to hear the renderer work.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { viewModel.installTestTrack() }) {
                Text("Add Atmos test track to library")
            }
            testStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
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
            // Multichannel speaker output would need a multichannel DAC and a
            // VBAP output path (vbap.h exists, the render/sink wiring does not).
            // This renderer targets headphones and only outputs binaural stereo,
            // so these are shown disabled for reference rather than pretending to
            // work — feeding 5.1/7.1 to a stereo output just folds back down.
            SectionHeader("Output Layout")
            Text(
                "This renderer outputs binaural stereo for headphones. " +
                    "Multichannel speaker output (5.1 / 7.1 / 7.1.4) needs a " +
                    "multichannel DAC and isn't supported yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            ChoiceChips(
                options = ChannelLayout.entries,
                selected = ChannelLayout.STEREO,
                enabled = false,
                label = { it.label },
                onSelect = { },
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
            val sofaPicker = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? -> if (uri != null) viewModel.importSofa(uri) }
            // .sofa has no registered MIME type, so accept any file and let the
            // native loader reject non-SOFA input.
            val sofaMimes = arrayOf("*/*")
            // Three-way source: Off (dry fold-down, AutoEQ only) / the baked
            // KEMAR set / a user SOFA. Picking either HRTF option re-enables
            // the binauralizer if it was off.
            val hrtfChoice = when {
                !profile.hrtfEnabled -> HrtfChoice.OFF
                profile.hrtfProfileId == null -> HrtfChoice.BUILT_IN
                else -> HrtfChoice.CUSTOM
            }
            ChoiceChips(
                options = HrtfChoice.entries,
                selected = hrtfChoice,
                label = { it.label },
                onSelect = { choice ->
                    when (choice) {
                        HrtfChoice.OFF -> viewModel.update(profile.copy(hrtfEnabled = false))
                        HrtfChoice.BUILT_IN -> viewModel.useBuiltInHrtf()
                        // Prefer what's already on hand: re-enable the stored
                        // selection, else the first saved preset; only open the
                        // file picker when no preset exists yet.
                        HrtfChoice.CUSTOM -> when {
                            profile.hrtfProfileId != null ->
                                viewModel.update(profile.copy(hrtfEnabled = true))
                            sofaPresets.isNotEmpty() -> viewModel.selectSofa(sofaPresets.first())
                            else -> sofaPicker.launch(sofaMimes)
                        }
                    }
                },
            )
            when {
                !profile.hrtfEnabled -> Text(
                    "HRTF off — objects fold down to plain stereo and headphone " +
                        "correction comes from the built-in AutoEQ alone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                profile.hrtfProfileId != null -> Text(
                    "Loaded: ${java.io.File(profile.hrtfProfileId!!).name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Text(
                    "Built-in is MIT KEMAR. Load a SOFA HRTF (your own or a set like " +
                        "SADIE / CIPIC) to change the binaural voicing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Every imported/downloaded .sofa stays on hand as a preset —
            // tap to switch HRTFs without re-downloading or re-picking.
            if (sofaPresets.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "SOFA presets",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
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
                                contentDescription = "Delete preset",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Row {
                TextButton(onClick = {
                    navController.navigate(tf.monochrome.android.ui.navigation.Screen.HrtfDatabase.route)
                }) { Text("Browse HRTF database") }
                TextButton(onClick = { sofaPicker.launch(sofaMimes) }) {
                    Text("Load .sofa file")
                }
            }
            LabeledSlider(
                title = "Binaural strength",
                valueText = "${(profile.binauralStrength * 100).toInt()}%",
                value = profile.binauralStrength,
                range = 0f..1f,
                enabled = profile.hrtfEnabled,
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
            Text(
                "The decoder always applies the stream's Line-mode DRC. " +
                    "Light/Standard add post-render leveling on top; Heavy uses " +
                    "the stream's own RF compression gain (compr) when present.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/**
 * The three binaural back-end sources on the renderer page. OFF bypasses the
 * HRTF entirely (dry stereo fold-down — the built-in AutoEQ becomes the only
 * headphone shaping); the other two pick which impulse-response set drives it.
 */
private enum class HrtfChoice(val label: String) {
    OFF("Off (AutoEQ only)"),
    BUILT_IN("Built-in HRTF"),
    CUSTOM("Custom HRTF (SOFA)"),
}
