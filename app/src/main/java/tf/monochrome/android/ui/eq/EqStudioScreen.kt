package tf.monochrome.android.ui.eq

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.EqTarget
import tf.monochrome.android.domain.model.FilterType
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.ui.components.GlassSearchBar
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.popBackStackSafe
import kotlin.math.log2
import kotlin.math.roundToInt

/** Red and green read on the graph in both themes, as on peqdb's. */
private val MeasurementColor = Color(0xFFE5484D)
private val TargetColor = Color(0xFF30A46C)

private enum class StudioTab { FILTERS, HEADPHONE, OUTPUT }

/** Which value the exact-entry dialog is open for. */
private enum class StudioField { FREQ, GAIN, Q, PREAMP }

/**
 * The EQ Studio: the equalizer as one screen built around its graph, after
 * peqdb.com/studio, on the app's own engine — the same [EqViewModel], filters,
 * AutoEQ, headphone database and targets the classic screen uses, so what is
 * tuned here is exactly what plays.
 *
 * It shares the classic screen's view model rather than owning one (see the
 * nav host): two would each hold a copy of the bands, and going back would
 * show the old copy and write it over the new one on the next edit.
 *
 * On a phone the graph stays fixed at the top and only the panel under it
 * scrolls — the whole point is watching the curve move while a filter is
 * tuned, and peqdb's own phone layout lets the panel cover it. Wide screens
 * put the two side by side.
 *
 * Every control is the app's liquid glass ([GlassChoiceChip], [ScrubPill]),
 * frosting this page's own backdrop, of which every pill is a sibling.
 */
@Composable
fun EqStudioScreen(
    navController: NavController,
    viewModel: EqViewModel,
) {
    val eqEnabled by viewModel.eqEnabled.collectAsStateWithLifecycle()
    val currentBands by viewModel.currentBands.collectAsStateWithLifecycle()
    val currentBandsR by viewModel.currentBandsR.collectAsStateWithLifecycle()
    val stereoMode by viewModel.stereoMode.collectAsStateWithLifecycle()
    val editChannel by viewModel.editChannel.collectAsStateWithLifecycle()
    val originalMeasurement by viewModel.originalMeasurement.collectAsStateWithLifecycle()
    val originalMeasurementR by viewModel.originalMeasurementR.collectAsStateWithLifecycle()
    val measurementLabelL by viewModel.measurementLabelL.collectAsStateWithLifecycle()
    val measurementLabelR by viewModel.measurementLabelR.collectAsStateWithLifecycle()
    val selectedHeadphone by viewModel.selectedHeadphone.collectAsStateWithLifecycle()
    val availableTargets by viewModel.availableTargets.collectAsStateWithLifecycle()
    val selectedTarget by viewModel.selectedTarget.collectAsStateWithLifecycle()
    val isCalculating by viewModel.isCalculating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val error = viewModel.error.collectAsStateWithLifecycle().value?.resolve(context)

    val editRight = stereoMode && editChannel == EqChannel.RIGHT
    val bands = if (editRight) currentBandsR else currentBands
    val measurement =
        if (editRight) originalMeasurementR.ifEmpty { originalMeasurement } else originalMeasurement
    val measurementName = measurementLabelText(if (editRight) measurementLabelR else measurementLabelL)
        ?: selectedHeadphone?.name

    var selectedId by rememberSaveable { mutableIntStateOf(-1) }
    var tab by rememberSaveable { mutableStateOf(StudioTab.FILTERS) }
    var editing by rememberSaveable { mutableStateOf<StudioField?>(null) }
    var namingPreset by rememberSaveable { mutableStateOf(false) }
    var pendingTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = bands.firstOrNull { it.id == selectedId }

    val targetPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.eq_couldnt_read_file), Toast.LENGTH_SHORT).show()
        } else {
            pendingTarget = raw
        }
    }

    val graph: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.padding(horizontal = 12.dp)) {
            EqStudioGraph(
                measurement = measurement,
                target = selectedTarget.data,
                bands = bands,
                selectedId = selectedId,
                onSelect = { id ->
                    selectedId = id
                    tab = StudioTab.FILTERS
                },
                onMove = { id, freq, gain -> viewModel.updateBandByDrag(id, freq, gain) },
                onQ = { id, q -> bands.firstOrNull { it.id == id }?.let { viewModel.updateBand(id, it.copy(q = q)) } },
                measurementColor = MeasurementColor,
                targetColor = TargetColor,
                description = stringResource(R.string.eq_studio_graph_description),
                modifier = Modifier.fillMaxSize(),
            )
            StudioLegend(
                measurementName = measurementName.takeIf { measurement.isNotEmpty() },
                targetName = selectedTarget.label.takeIf { selectedTarget.data.isNotEmpty() },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 38.dp, bottom = 26.dp),
            )
        }
    }
    val targets: @Composable () -> Unit = {
        TargetStrip(
            targets = availableTargets,
            selectedId = selectedTarget.id,
            onSelect = viewModel::selectTarget,
            onImport = { targetPicker.launch("*/*") },
        )
    }
    val panel: @Composable (Modifier) -> Unit = { modifier ->
        Column(modifier) {
            StudioTabs(tab = tab, onTab = { tab = it })
            if (error != null) {
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.clearError() }
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            when (tab) {
                StudioTab.FILTERS -> FiltersPanel(
                    bands = bands,
                    selected = selected,
                    isCalculating = isCalculating,
                    onAutoEq = viewModel::runAutoEq,
                    onAdd = { viewModel.addBand().takeIf { it >= 0 }?.let { selectedId = it } },
                    onSort = viewModel::sortBandsByFrequency,
                    onFlat = viewModel::resetToFlat,
                    onSelect = { selectedId = it },
                    onChange = { viewModel.updateBand(it.id, it) },
                    onDelete = { viewModel.removeBand(it) },
                    onType = { editing = it },
                    modifier = Modifier.weight(1f),
                )
                StudioTab.HEADPHONE -> HeadphonePanel(
                    viewModel = viewModel,
                    selectedHeadphone = selectedHeadphone,
                    channel = if (editRight) EqChannel.RIGHT else EqChannel.LEFT,
                    modifier = Modifier.weight(1f),
                )
                StudioTab.OUTPUT -> OutputPanel(
                    viewModel = viewModel,
                    bands = bands,
                    onType = { editing = it },
                    onSave = { namingPreset = true },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    val haze = rememberHazeState()
    Box(modifier = Modifier.fillMaxSize()) {
        // The page's own backdrop, a sibling of every pill drawn over it.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .hazeSource(haze),
        )
        CompositionLocalProvider(LocalGlassBackdrop provides haze) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                val wide = maxWidth >= 600.dp && maxWidth > maxHeight
                if (wide) {
                    Row(Modifier.fillMaxSize()) {
                        Column(Modifier.weight(1.3f).fillMaxHeight()) {
                            StudioHeader(
                                subtitle = studioSubtitle(measurementName, selectedTarget),
                                eqEnabled = eqEnabled,
                                onBack = { navController.popBackStackSafe() },
                                onToggle = viewModel::toggleEq,
                            )
                            graph(Modifier.weight(1f).fillMaxWidth())
                            targets()
                            Spacer(Modifier.height(8.dp))
                        }
                        panel(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp))
                    }
                } else {
                    val graphHeight = (maxHeight * 0.4f).coerceIn(220.dp, 380.dp)
                    Column(Modifier.fillMaxSize()) {
                        StudioHeader(
                            subtitle = studioSubtitle(measurementName, selectedTarget),
                            eqEnabled = eqEnabled,
                            onBack = { navController.popBackStackSafe() },
                            onToggle = viewModel::toggleEq,
                        )
                        graph(Modifier.height(graphHeight).fillMaxWidth())
                        targets()
                        panel(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    editing?.let { field ->
        val band = selected
        val (title, initial) = when (field) {
            StudioField.FREQ -> stringResource(R.string.eq_studio_freq) to (band?.freq?.roundToInt()?.toString() ?: "")
            StudioField.GAIN -> stringResource(R.string.eq_studio_gain) to (band?.let { String.format(java.util.Locale.US, "%.1f", it.gain) } ?: "")
            StudioField.Q -> stringResource(R.string.eq_studio_q) to (band?.let { formatStudioQ(it.q) } ?: "")
            StudioField.PREAMP -> stringResource(R.string.eq_preamp) to String.format(java.util.Locale.US, "%.1f", viewModel.currentPreamp.value)
        }
        StudioValueDialog(
            title = title,
            initial = initial,
            onDismiss = { editing = null },
            onConfirm = { v ->
                when (field) {
                    StudioField.FREQ -> band?.let { viewModel.updateBand(it.id, it.copy(freq = v.coerceIn(StudioAxis.MIN_FREQ, StudioAxis.MAX_FREQ))) }
                    StudioField.GAIN -> band?.let { viewModel.updateBand(it.id, it.copy(gain = StudioBands.clampGain(v))) }
                    StudioField.Q -> band?.let { viewModel.updateBand(it.id, it.copy(q = v.coerceIn(StudioBands.MIN_Q, StudioBands.MAX_Q))) }
                    StudioField.PREAMP -> viewModel.setPreamp(v)
                }
            },
        )
    }
    if (namingPreset) {
        StudioNameDialog(
            title = stringResource(R.string.eq_save_as_preset),
            label = stringResource(R.string.eq_preset_name),
            onDismiss = { namingPreset = false },
            onConfirm = { viewModel.saveAsPreset(it) },
        )
    }
    pendingTarget?.let { raw ->
        StudioNameDialog(
            title = stringResource(R.string.eq_import_custom_target),
            label = stringResource(R.string.eq_target_name),
            onDismiss = { pendingTarget = null },
            onConfirm = { viewModel.importCustomTarget(raw, it) },
        )
    }
}

@Composable
private fun studioSubtitle(measurementName: String?, target: EqTarget): String =
    listOfNotNull(measurementName, target.label.takeIf { it.isNotBlank() }).joinToString(" → ")
        .ifEmpty { stringResource(R.string.eq_studio_tagline) }

@Composable
private fun StudioHeader(
    subtitle: String,
    eqEnabled: Boolean,
    onBack: () -> Unit,
    onToggle: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.eq_studio_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        GlassChoiceChip(
            label = stringResource(if (eqEnabled) R.string.eq_studio_on else R.string.eq_studio_off),
            selected = eqEnabled,
            accent = MaterialTheme.colorScheme.primary,
            onClick = onToggle,
            description = stringResource(R.string.settings_equalizer),
            modifier = Modifier.height(36.dp),
        )
    }
}

@Composable
private fun StudioLegend(measurementName: String?, targetName: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (measurementName != null) LegendLine(measurementName, MeasurementColor)
        if (targetName != null) LegendLine(targetName, TargetColor)
        if (measurementName != null) LegendLine(stringResource(R.string.eq_studio_with_eq), MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun LegendLine(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(200.dp),
    )
}

/**
 * Every target one tap away, under the graph, as peqdb has them: comparing two
 * targets is the most common thing done here, and the classic screen hid them
 * behind a dropdown.
 */
@Composable
private fun TargetStrip(
    targets: List<EqTarget>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(targets, key = { it.id }) { target ->
            GlassChoiceChip(
                label = target.label,
                selected = target.id == selectedId,
                accent = TargetColor,
                onClick = { onSelect(target.id) },
            )
        }
        item(key = "import") {
            GlassChoiceChip(
                label = stringResource(R.string.eq_studio_import_target),
                selected = false,
                accent = TargetColor,
                leadingIcon = Icons.Default.Add,
                description = stringResource(R.string.eq_import_custom_target),
                onClick = onImport,
            )
        }
    }
}

@Composable
private fun StudioTabs(tab: StudioTab, onTab: (StudioTab) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        for (t in StudioTab.entries) {
            GlassChoiceChip(
                label = stringResource(
                    when (t) {
                        StudioTab.FILTERS -> R.string.eq_studio_tab_filters
                        StudioTab.HEADPHONE -> R.string.eq_studio_tab_headphone
                        StudioTab.OUTPUT -> R.string.eq_studio_tab_output
                    }
                ),
                selected = t == tab,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onTab(t) },
                height = 40.dp,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private val panelPadding: PaddingValues
    @Composable get() =
    PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 32.dp + LocalBottomChromeInset.current)

@Composable
private fun FiltersPanel(
    bands: List<EqBand>,
    selected: EqBand?,
    isCalculating: Boolean,
    onAutoEq: () -> Unit,
    onAdd: () -> Unit,
    onSort: () -> Unit,
    onFlat: () -> Unit,
    onSelect: (Int) -> Unit,
    onChange: (EqBand) -> Unit,
    onDelete: (Int) -> Unit,
    onType: (StudioField) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    val spinner: @Composable () -> Unit = {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(12.dp))
    }
    LazyColumn(modifier = modifier, contentPadding = panelPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "actions") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    GlassChoiceChip(
                        label = stringResource(R.string.eq_studio_autoeq),
                        selected = true,
                        accent = accent,
                        leadingIcon = Icons.Default.AutoFixHigh,
                        enabled = !isCalculating,
                        onClick = onAutoEq,
                        trailingContent = spinner.takeIf { isCalculating },
                    )
                }
                item {
                    GlassChoiceChip(
                        label = stringResource(R.string.eq_studio_add),
                        selected = false,
                        accent = accent,
                        leadingIcon = Icons.Default.Add,
                        enabled = bands.size < StudioBands.MAX_BANDS,
                        onClick = onAdd,
                    )
                }
                item {
                    GlassChoiceChip(
                        label = stringResource(R.string.eq_studio_sort),
                        selected = false,
                        accent = accent,
                        leadingIcon = Icons.AutoMirrored.Filled.Sort,
                        enabled = bands.size > 1,
                        onClick = onSort,
                    )
                }
                item {
                    GlassChoiceChip(
                        label = stringResource(R.string.eq_studio_flat),
                        selected = false,
                        accent = accent,
                        enabled = bands.any { it.gain != 0f },
                        onClick = onFlat,
                    )
                }
            }
        }

        if (bands.isEmpty()) {
            item(key = "empty") { Hint(stringResource(R.string.eq_studio_no_filters)) }
            return@LazyColumn
        }

        item(key = "chips") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(bands, key = { _, b -> b.id }) { index, band ->
                    val color = studioFilterColor(band.id)
                    GlassChoiceChip(
                        label = "${index + 1} · ${band.type.studioCode()} ${formatStudioFreq(band.freq)}",
                        selected = band.id == selected?.id,
                        accent = color,
                        onClick = { onSelect(band.id) },
                        leadingContent = {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (band.enabled) color else color.copy(alpha = 0.3f)),
                            )
                        },
                    )
                }
            }
        }

        item(key = "inspector") {
            if (selected == null) {
                Hint(stringResource(R.string.eq_studio_no_filter_selected))
            } else {
                FilterInspector(
                    band = selected,
                    number = bands.indexOfFirst { it.id == selected.id } + 1,
                    onChange = onChange,
                    onDelete = { onDelete(selected.id) },
                    onType = onType,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

/** The selected filter: its type, its three values as scrub pills, on/off and delete. */
@Composable
private fun FilterInspector(
    band: EqBand,
    number: Int,
    onChange: (EqBand) -> Unit,
    onDelete: () -> Unit,
    onType: (StudioField) -> Unit,
) {
    val color = studioFilterColor(band.id)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(R.string.eq_studio_filter_n, number),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            GlassChoiceChip(
                label = stringResource(if (band.enabled) R.string.eq_studio_on else R.string.eq_studio_bypassed),
                selected = band.enabled,
                accent = color,
                onClick = { onChange(band.copy(enabled = !band.enabled)) },
            )
            Spacer(Modifier.width(8.dp))
            GlassChoiceChip(
                label = stringResource(R.string.eq_studio_delete),
                selected = false,
                accent = MaterialTheme.colorScheme.error,
                leadingIcon = Icons.Default.DeleteOutline,
                onClick = onDelete,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((type, res) in listOf(
                FilterType.LOWSHELF to R.string.eq_studio_low_shelf,
                FilterType.PEAKING to R.string.eq_studio_peak,
                FilterType.HIGHSHELF to R.string.eq_studio_high_shelf,
            )) {
                GlassChoiceChip(
                    label = stringResource(res),
                    selected = band.type == type,
                    accent = color,
                    onClick = { onChange(band.copy(type = type)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        BandScrubPills(band = band, accent = color, onChange = onChange, onType = onType)
    }
}

/**
 * Frequency, gain and Q as scrub pills. The scales are chosen so a thumb-width
 * swipe is a musically sized move: a third of an octave, about a dB, a quarter
 * of an octave of Q. Each pill ticks at its coarse step.
 */
@Composable
private fun BandScrubPills(
    band: EqBand,
    accent: Color,
    onChange: (EqBand) -> Unit,
    onType: (StudioField) -> Unit,
) {
    var startFreq by remember { mutableFloatStateOf(band.freq) }
    var startGain by remember { mutableFloatStateOf(band.gain) }
    var startQ by remember { mutableFloatStateOf(band.q) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ScrubPill(
            label = stringResource(R.string.eq_studio_freq),
            value = formatStudioFreq(band.freq),
            tick = (log2(band.freq) * 6f).roundToInt(),
            accent = accent,
            onDragStart = { startFreq = band.freq },
            onDrag = { dp -> onChange(band.copy(freq = StudioBands.roundFreq(StudioBands.scrubFreq(startFreq, dp / 60f)))) },
            onTap = { onType(StudioField.FREQ) },
            modifier = Modifier.weight(1f),
        )
        ScrubPill(
            label = stringResource(R.string.eq_studio_gain),
            value = formatStudioGain(band.gain),
            tick = band.gain.roundToInt(),
            accent = accent,
            onDragStart = { startGain = band.gain },
            onDrag = { dp -> onChange(band.copy(gain = StudioBands.clampGain(((startGain + dp / 16f) * 10f).roundToInt() / 10f))) },
            onTap = { onType(StudioField.GAIN) },
            modifier = Modifier.weight(1f),
        )
        ScrubPill(
            label = stringResource(R.string.eq_studio_q),
            value = formatStudioQ(band.q),
            tick = (log2(band.q) * 4f).roundToInt(),
            accent = accent,
            onDragStart = { startQ = band.q },
            onDrag = { dp -> onChange(band.copy(q = (StudioBands.scrubQ(startQ, dp / 80f) * 100f).roundToInt() / 100f)) },
            onTap = { onType(StudioField.Q) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Picking a headphone from the app's database: one row per measurement, so
 * the rig and source are chosen together with the model, as on the classic
 * picker. A pick loads that measurement for the ear being edited.
 */
@Composable
private fun HeadphonePanel(
    viewModel: EqViewModel,
    selectedHeadphone: Headphone?,
    channel: EqChannel,
    modifier: Modifier = Modifier,
) {
    val headphones by viewModel.availableHeadphones.collectAsStateWithLifecycle()
    val loading by viewModel.headphonesLoading.collectAsStateWithLifecycle()
    val stereoMode by viewModel.stereoMode.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        if (viewModel.availableHeadphones.value.isEmpty()) viewModel.loadAvailableHeadphones()
    }
    val measurementPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        if (raw.isNullOrBlank()) {
            Toast.makeText(context, context.getString(R.string.eq_couldnt_read_file), Toast.LENGTH_SHORT).show()
        } else {
            viewModel.importMeasurementData(raw, channel)
        }
    }

    val rows = remember(headphones, query) {
        val q = query.trim()
        headphones.asSequence()
            .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) }
            .flatMap { hp -> hp.measurements.asSequence().map { hp to it } }
            .toList()
    }

    LazyColumn(modifier = modifier, contentPadding = panelPadding, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(key = "search") {
            GlassSearchBar(
                query = query,
                onQueryChange = { query = it },
                placeholder = stringResource(R.string.eq_studio_search_headphones),
                hazeState = LocalGlassBackdrop.current,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item(key = "tools") {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 6.dp),
            ) {
                if (stereoMode) {
                    item {
                        GlassChoiceChip(
                            label = stringResource(R.string.eq_left_ear),
                            selected = channel == EqChannel.LEFT,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { viewModel.setEditChannel(EqChannel.LEFT) },
                        )
                    }
                    item {
                        GlassChoiceChip(
                            label = stringResource(R.string.eq_right_ear),
                            selected = channel == EqChannel.RIGHT,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { viewModel.setEditChannel(EqChannel.RIGHT) },
                        )
                    }
                }
                item {
                    GlassChoiceChip(
                        label = stringResource(R.string.eq_studio_import_measurement),
                        selected = false,
                        accent = MaterialTheme.colorScheme.primary,
                        leadingIcon = Icons.Default.UploadFile,
                        description = stringResource(R.string.eq_import_measurement_file),
                        onClick = { measurementPicker.launch("*/*") },
                    )
                }
            }
        }
        when {
            loading && rows.isEmpty() -> item(key = "loading") { Hint(stringResource(R.string.eq_studio_loading_headphones)) }
            rows.isEmpty() -> item(key = "none") { Hint(stringResource(R.string.eq_studio_no_headphones)) }
            else -> items(rows, key = { (hp, m) -> "${hp.id}|${m.source}|${m.path}|${m.fileName}" }) { (hp, m) ->
                HeadphoneRow(
                    headphone = hp,
                    measurement = m,
                    selected = hp.id == selectedHeadphone?.id,
                    onClick = { viewModel.selectMeasurement(hp, m, channel) },
                )
            }
        }
    }
}

@Composable
private fun HeadphoneRow(
    headphone: Headphone,
    measurement: AutoEqMeasurement,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                headphone.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(R.string.eq_source_rig, measurement.source, measurement.rig.label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
    }
}

/** Level and what leaves the Studio: preamp, the two-ear switch, saving and export. */
@Composable
private fun OutputPanel(
    viewModel: EqViewModel,
    bands: List<EqBand>,
    onType: (StudioField) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val preamp by viewModel.currentPreamp.collectAsStateWithLifecycle()
    val autoPreamp by viewModel.autoPreamp.collectAsStateWithLifecycle()
    val stereoMode by viewModel.stereoMode.collectAsStateWithLifecycle()
    val editChannel by viewModel.editChannel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val accent = MaterialTheme.colorScheme.primary
    var startPreamp by remember { mutableFloatStateOf(preamp) }

    LazyColumn(modifier = modifier, contentPadding = panelPadding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "preamp") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ScrubPill(
                    label = stringResource(R.string.eq_preamp),
                    value = formatStudioGain(preamp),
                    tick = preamp.roundToInt(),
                    enabled = !autoPreamp,
                    onDragStart = { startPreamp = preamp },
                    onDrag = { dp -> viewModel.setPreamp(((startPreamp + dp / 16f) * 10f).roundToInt() / 10f) },
                    onTap = { if (!autoPreamp) onType(StudioField.PREAMP) },
                    modifier = Modifier.weight(1f),
                )
                GlassChoiceChip(
                    label = stringResource(R.string.eq_studio_auto),
                    selected = autoPreamp,
                    accent = accent,
                    description = stringResource(R.string.eq_automatic_preamp),
                    onClick = { viewModel.setAutoPreamp(!autoPreamp) },
                    height = 52.dp,
                )
            }
        }
        item(key = "stereo") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    GlassChoiceChip(
                        label = stringResource(R.string.eq_studio_two_channel),
                        selected = stereoMode,
                        accent = accent,
                        onClick = { viewModel.setStereoMode(!stereoMode) },
                    )
                }
                if (stereoMode) {
                    item {
                        GlassChoiceChip(
                            label = stringResource(R.string.eq_left_ear),
                            selected = editChannel == EqChannel.LEFT,
                            accent = accent,
                            onClick = { viewModel.setEditChannel(EqChannel.LEFT) },
                        )
                    }
                    item {
                        GlassChoiceChip(
                            label = stringResource(R.string.eq_right_ear),
                            selected = editChannel == EqChannel.RIGHT,
                            accent = accent,
                            onClick = { viewModel.setEditChannel(EqChannel.RIGHT) },
                        )
                    }
                }
            }
        }
        item(key = "save") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChoiceChip(
                    label = stringResource(R.string.eq_save_as_preset),
                    selected = false,
                    accent = accent,
                    leadingIcon = Icons.Default.Save,
                    enabled = bands.isNotEmpty(),
                    onClick = onSave,
                    height = 44.dp,
                    modifier = Modifier.weight(1f),
                )
                GlassChoiceChip(
                    label = stringResource(R.string.eq_studio_copy_apo),
                    selected = false,
                    accent = accent,
                    leadingIcon = Icons.Default.ContentCopy,
                    enabled = bands.isNotEmpty(),
                    onClick = {
                        val text = buildParametricEqText(bands, preamp)
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("Equalizer APO", text))
                        Toast.makeText(context, context.getString(R.string.eq_studio_copied), Toast.LENGTH_SHORT).show()
                    },
                    height = 44.dp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StudioNameDialog(
    title: String,
    label: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()); onDismiss() }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
