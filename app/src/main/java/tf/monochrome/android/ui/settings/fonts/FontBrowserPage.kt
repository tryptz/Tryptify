package tf.monochrome.android.ui.settings.fonts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.R
import tf.monochrome.android.data.fonts.FontImportReport
import tf.monochrome.android.data.fonts.FontSkipReason
import tf.monochrome.android.data.fonts.ImportedFont
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.settings.LocalSettingsSearchInset
import tf.monochrome.android.ui.theme.BundledFonts
import tf.monochrome.android.ui.theme.InterFontFamily
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.loadAppFontFamily

/** One entry in the browser: the default, a font that ships, or an import. */
private data class FontChoice(
    val key: String,
    val name: String,
    val note: String?,
    val origin: Origin,
    val imported: ImportedFont? = null,
) {
    enum class Origin { DEFAULT, INCLUDED, IMPORTED }

    val usable: Boolean get() = imported?.usable ?: true
}

private enum class FontFilter { ALL, INCLUDED, IMPORTED }

/**
 * Settings › Fonts: a browser for every font the app can wear, each one shown
 * in its own letters. A page of the Settings pager, under its own chip, so it
 * has no bar of its own: Settings' bar is above it, and the import action sits
 * with the filters.
 *
 * Its glass is the app's, not this screen's: the preview and the import pane
 * are [GlassPanel]s cut from the UI panels material that Visual Studio tunes
 * ([LocalMiniPlayerGlass]), the cards are row glass ([liquidGlass], no backdrop
 * of their own), and "Remove liquid glass" flattens all of it like everywhere
 * else. The panels frost a backdrop drawn for them underneath — the screen's
 * own source, as a sibling, never the app-wide one they are drawn inside.
 */
@Composable
fun FontBrowserPage(
    viewModel: FontBrowserViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activeKey by viewModel.activeKey.collectAsStateWithLifecycle()
    val previewKey by viewModel.previewKey.collectAsStateWithLifecycle()
    val imported by viewModel.imported.collectAsStateWithLifecycle()
    val importing by viewModel.importing.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val pendingDelete by viewModel.pendingDelete.collectAsStateWithLifecycle()

    var filter by rememberSaveable { mutableStateOf(FontFilter.ALL) }
    var sample by rememberSaveable { mutableStateOf("") }
    var weight by rememberSaveable { mutableFloatStateOf(500f) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.import(uris)
    }
    val launchPicker = { picker.launch(FONT_MIME_TYPES) }

    val defaultNote = stringResource(R.string.settings_the_default_neutral_ui_grotesque)
    val choices = remember(imported, defaultNote) {
        buildList {
            add(FontChoice(DEFAULT_FONT_KEY, "Inter", defaultNote, FontChoice.Origin.DEFAULT))
            BundledFonts.ALL.forEach { font ->
                add(FontChoice(BundledFonts.idOf(font), font.displayName, font.note, FontChoice.Origin.INCLUDED))
            }
            imported.forEach { font ->
                add(FontChoice(font.id, font.name, null, FontChoice.Origin.IMPORTED, font))
            }
        }
    }
    val shown = remember(choices, filter) {
        when (filter) {
            FontFilter.ALL -> choices
            FontFilter.INCLUDED -> choices.filter { it.origin != FontChoice.Origin.IMPORTED }
            FontFilter.IMPORTED -> choices.filter { it.origin == FontChoice.Origin.IMPORTED }
        }
    }
    val previewChoice = choices.firstOrNull { it.key == previewKey } ?: choices.first()
    val previewFamily = rememberFontFamily(context, previewChoice)

    pendingDelete?.let { font ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            title = { Text(stringResource(R.string.fonts_delete_title, font.name)) },
            text = { Text(stringResource(R.string.fonts_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val haze = rememberHazeState()
    Box(modifier = Modifier.fillMaxSize()) {
        FontBackdrop(
            family = previewFamily,
            modifier = Modifier.fillMaxSize().hazeSource(haze),
        )
        Column(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
                // The settings search bar floats over the pager; its height
                // comes down as top padding so the preview starts clear of it.
                contentPadding = PaddingValues(
                    top = LocalSettingsSearchInset.current,
                    bottom = 96.dp + LocalBottomChromeInset.current,
                ),
            ) {
                item(key = "preview", span = { GridItemSpan(maxLineSpan) }) {
                    GlassPanel(
                        hazeState = haze,
                        glass = LocalMiniPlayerGlass.current,
                        avoidNavigationBar = false,
                        // In a scrolling grid: a swipe on the pane scrolls it.
                        blockTouchesBelow = false,
                    ) {
                        PreviewPane(
                            choice = previewChoice,
                            family = previewFamily,
                            inUse = previewChoice.key == activeKey,
                            sample = sample,
                            onSampleChange = { sample = it },
                            weight = weight,
                            onWeightChange = { weight = it },
                            onApply = viewModel::apply,
                        )
                    }
                }
                item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                    FilterRow(selected = filter, onSelect = { filter = it }, onImport = launchPicker)
                }
                itemsIndexed(shown, key = { _, c -> c.key }) { index, choice ->
                    FontCard(
                        choice = choice,
                        family = rememberFontFamily(context, choice),
                        selected = choice.key == previewKey,
                        inUse = choice.key == activeKey,
                        onSelect = { viewModel.preview(choice.key) },
                        onDelete = choice.imported?.let { font -> { viewModel.requestDelete(font) } },
                        modifier = Modifier.padding(
                            start = if (index % 2 == 0) 12.dp else 6.dp,
                            end = if (index % 2 == 0) 6.dp else 12.dp,
                            top = 6.dp,
                            bottom = 6.dp,
                        ),
                    )
                }
                item(key = "import", span = { GridItemSpan(maxLineSpan) }) {
                    GlassPanel(
                        hazeState = haze,
                        glass = LocalMiniPlayerGlass.current,
                        avoidNavigationBar = false,
                        // In a scrolling grid: a swipe on the pane scrolls it.
                        blockTouchesBelow = false,
                    ) {
                        ImportPane(
                            importing = importing,
                            report = report,
                            onChoose = launchPicker,
                            onDismissReport = viewModel::dismissReport,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The family a choice is drawn in. An import that cannot be read is drawn in
 * the default rather than handed to the renderer, which would fail on it.
 */
@Composable
private fun rememberFontFamily(context: android.content.Context, choice: FontChoice): FontFamily =
    remember(choice.key, choice.usable) {
        when {
            choice.origin == FontChoice.Origin.DEFAULT || !choice.usable -> InterFontFamily
            else -> loadAppFontFamily(context, choice.key) ?: InterFontFamily
        }
    }

/**
 * What the glass frosts: two soft fields of the theme's own colours and the
 * previewed font's letters, oversized. It changes with the preview, so the
 * panes over it shift as fonts are tried.
 */
@Composable
private fun FontBackdrop(family: FontFamily, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = modifier.background(scheme.background)) {
        Box(
            modifier = Modifier
                .offset(x = (-60).dp, y = 40.dp)
                .size(340.dp)
                .background(Brush.radialGradient(listOf(scheme.primary.copy(alpha = 0.30f), Color.Transparent)), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = 80.dp)
                .size(360.dp)
                .background(Brush.radialGradient(listOf(scheme.tertiary.copy(alpha = 0.26f), Color.Transparent)), CircleShape),
        )
        Text(
            text = "Aa",
            fontFamily = family,
            fontWeight = FontWeight.Bold,
            fontSize = 260.sp,
            color = scheme.onBackground.copy(alpha = 0.07f),
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = 40.dp, y = 60.dp)
                .rotate(-8f),
        )
    }
}

@Composable
private fun PreviewPane(
    choice: FontChoice,
    family: FontFamily,
    inUse: Boolean,
    sample: String,
    onSampleChange: (String) -> Unit,
    weight: Float,
    onWeightChange: (Float) -> Unit,
    onApply: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val previewWeight = FontWeight(weight.toInt())
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.fonts_preview_label, choice.name),
                style = type.labelLarge,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (inUse) InUseBadge()
        }
        Text(
            text = sample.ifBlank { stringResource(R.string.fonts_sample_default) },
            style = type.headlineMedium.copy(fontFamily = family, fontWeight = previewWeight),
            color = scheme.onSurface,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MonoDimens.shapeMd)
                .background(scheme.surfaceContainerHigh.copy(alpha = 0.6f))
                .padding(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(MonoDimens.shapeSm)
                    .background(scheme.primaryContainer),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.fonts_preview_track),
                    style = type.bodyLarge.copy(fontFamily = family, fontWeight = previewWeight),
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.fonts_preview_artist),
                    style = type.bodyMedium.copy(fontFamily = family),
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stringResource(R.string.fonts_preview_heading),
                style = type.titleLarge.copy(fontFamily = family, fontWeight = previewWeight),
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "0123456789 · 140 BPM",
                style = type.bodyMedium.copy(fontFamily = family),
                color = scheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = sample,
            onValueChange = onSampleChange,
            label = { Text(stringResource(R.string.fonts_sample_label)) },
            placeholder = { Text(stringResource(R.string.fonts_sample_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.fonts_weight), style = type.labelLarge, color = scheme.onSurfaceVariant)
            Slider(
                value = weight,
                onValueChange = onWeightChange,
                valueRange = 300f..700f,
                steps = 3,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            Text(
                text = stringResource(weightName(weight)),
                style = type.labelLarge,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.width(72.dp),
            )
        }
        Button(
            onClick = onApply,
            enabled = !inUse,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(
                text = if (inUse) stringResource(R.string.fonts_using, choice.name)
                else stringResource(R.string.fonts_use, choice.name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun weightName(weight: Float): Int = when (weight.toInt()) {
    in 0..349 -> R.string.fonts_weight_light
    in 350..449 -> R.string.fonts_weight_regular
    in 450..549 -> R.string.fonts_weight_medium
    in 550..649 -> R.string.fonts_weight_semibold
    else -> R.string.fonts_weight_bold
}

@Composable
private fun FilterRow(selected: FontFilter, onSelect: (FontFilter) -> Unit, onImport: () -> Unit) {
    val labels = listOf(
        FontFilter.ALL to R.string.fonts_filter_all,
        FontFilter.INCLUDED to R.string.settings_included,
        FontFilter.IMPORTED to R.string.settings_imported,
    )
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels) { (value, label) ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = stringResource(label),
                selected = selected == value,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onSelect(value) },
            )
        }
        item {
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                label = stringResource(R.string.fonts_import),
                selected = false,
                accent = MaterialTheme.colorScheme.primary,
                onClick = onImport,
                leadingIcon = Icons.Default.Add,
            )
        }
    }
}

@Composable
private fun FontCard(
    choice: FontChoice,
    family: FontFamily,
    selected: Boolean,
    inUse: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 136.dp)
            .clip(MonoDimens.shapeLg)
            .liquidGlass(shape = MonoDimens.shapeLg)
            .then(
                if (selected) Modifier.border(2.dp, scheme.primary, MonoDimens.shapeLg) else Modifier,
            )
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                text = "Aa",
                fontFamily = family,
                fontSize = 40.sp,
                lineHeight = 44.sp,
                color = scheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            when {
                inUse -> InUseBadge()
                onDelete != null -> IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(R.string.settings_delete_font, choice.name),
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Text(
            text = choice.name,
            fontFamily = family,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = family),
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val note = when {
            !choice.usable -> stringResource(R.string.fonts_unusable)
            choice.origin == FontChoice.Origin.IMPORTED -> stringResource(R.string.settings_imported)
            else -> choice.note
        }
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (choice.usable) scheme.onSurfaceVariant else scheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InUseBadge() {
    Text(
        text = stringResource(R.string.fonts_in_use),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun ImportPane(
    importing: Boolean,
    report: FontImportReport?,
    onChoose: () -> Unit,
    onDismissReport: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(scheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.FileUpload, contentDescription = null, tint = scheme.onSecondaryContainer)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.fonts_import_title), style = type.titleMedium, color = scheme.onSurface)
                Text(stringResource(R.string.fonts_import_formats), style = type.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.fonts_import_body), style = type.bodyMedium, color = scheme.onSurfaceVariant)
        if (importing) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.fonts_checking), style = type.bodySmall, color = scheme.onSurfaceVariant)
        } else {
            FilledTonalButton(onClick = onChoose, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.fonts_choose_files))
            }
        }
        report?.let { r ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MonoDimens.shapeMd)
                    .background(scheme.surfaceContainerHigh.copy(alpha = 0.7f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (r.added.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.fonts_added, r.added.joinToString(", ") { it.name }),
                        style = type.bodyMedium,
                        color = scheme.onSurface,
                    )
                }
                r.skipped.forEach { skip ->
                    Text(
                        text = when (skip.reason) {
                            FontSkipReason.NOT_A_FONT -> stringResource(R.string.fonts_skip_not_a_font, skip.file)
                            FontSkipReason.WEB_FONT -> stringResource(R.string.fonts_skip_web_font, skip.file)
                            FontSkipReason.TYPE1 -> stringResource(R.string.fonts_skip_type1, skip.file)
                            FontSkipReason.EMPTY -> stringResource(R.string.fonts_skip_empty, skip.file)
                            FontSkipReason.TOO_LARGE -> stringResource(R.string.fonts_skip_too_large, skip.file)
                            FontSkipReason.ALREADY_THERE ->
                                stringResource(R.string.fonts_skip_duplicate, skip.file, skip.existing.orEmpty())
                            FontSkipReason.UNREADABLE -> stringResource(R.string.fonts_skip_unreadable, skip.file)
                        },
                        style = type.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDismissReport, modifier = Modifier.align(Alignment.End)) {
                    Text(stringResource(R.string.action_dismiss))
                }
            }
        }
    }
}

/**
 * What the picker offers. File managers label fonts inconsistently — some as
 * font/ttf, some as an application type, many as plain octet-stream — so the
 * list is wide, and the inspector is what actually decides.
 */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf", "font/otf", "font/sfnt", "font/collection",
    "application/x-font-ttf", "application/x-font-otf", "application/font-sfnt",
    "application/vnd.ms-opentype", "application/octet-stream",
)
