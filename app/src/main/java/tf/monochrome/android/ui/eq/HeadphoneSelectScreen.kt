package tf.monochrome.android.ui.eq

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import tf.monochrome.android.data.repository.HeadphoneMeasurementDownloader
import tf.monochrome.android.data.repository.HeadphoneOfflineStore
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.domain.model.MeasurementRig
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.components.SearchOverlay
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R
import androidx.compose.ui.res.pluralStringResource

/**
 * Full-screen headphone browser.
 *
 * Layout: a horizontally-scrolling rig filter chip row pinned at the top
 * ("All rigs" + every rig present in the loaded data, ordered B&K → GRAS →
 * 711 clone → MiniDSP → Unknown), then a search bar, then a denormalized
 * one-row-per-measurement list with the source label visible on each row
 * and an A–Z sidebar for jumping by headphone name.
 *
 * Each row binds to a single (headphone, measurement) pair so picking
 * commits the user to that specific measurement on that specific rig —
 * the EQ engine then runs against that measurement's FR data.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeadphoneSelectScreen(
    viewModel: EqViewModel,
    onHeadphoneSelected: (Headphone) -> Unit,
    onDismiss: () -> Unit,
    channel: EqChannel = EqChannel.LEFT,
) {
    val availableHeadphones by viewModel.availableHeadphones.collectAsStateWithLifecycle()
    val uploadedHeadphones by viewModel.uploadedHeadphones.collectAsStateWithLifecycle()
    val headphonesLoading by viewModel.headphonesLoading.collectAsStateWithLifecycle()
    val error = viewModel.error.collectAsStateWithLifecycle().value?.resolve(androidx.compose.ui.platform.LocalContext.current)
    val selectedRig by viewModel.selectedRig.collectAsStateWithLifecycle()
    val availableRigs by viewModel.availableRigs.collectAsStateWithLifecycle()

    val offlineState by viewModel.offlineState.collectAsStateWithLifecycle()
    val offlineSummary by viewModel.offlineSummary.collectAsStateWithLifecycle()
    val offlineEstimate by viewModel.offlineEstimate.collectAsStateWithLifecycle()

    var localSearchQuery by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<Headphone?>(null) }
    var showOffline by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadAvailableHeadphones()
        viewModel.refreshOfflineSummary()
    }

    // Denormalize: one row per (headphone, measurement) pair, then apply
    // search + rig filter. A headphone measured by N sources contributes
    // N rows when "All rigs" is selected, so the user can pick the source
    // they trust without first picking the headphone.
    data class Row(val headphone: Headphone, val measurement: AutoEqMeasurement)

    // Each row's name and source prepared for HeadphoneSearch once per list,
    // not per keystroke: there are some 12,000 of them.
    data class Searchable(val row: Row, val name: HeadphoneSearch.Entry, val source: HeadphoneSearch.Entry)

    val searchable = remember(availableHeadphones) {
        val sources = HashMap<String, HeadphoneSearch.Entry>()
        availableHeadphones.flatMap { hp ->
            val name = HeadphoneSearch.entry(hp.name)
            hp.measurements.map { m ->
                val source = m.source + " " + m.rig.label
                Searchable(Row(hp, m), name, sources.getOrPut(source) { HeadphoneSearch.entry(source) })
            }
        }
    }

    val rows = remember(searchable, localSearchQuery, selectedRig) {
        HeadphoneSearch.filter(
            items = searchable.filter { selectedRig == null || it.row.measurement.rig == selectedRig },
            text = localSearchQuery,
            name = { it.name },
            extra = { it.source },
        ).map { it.row }
    }

    val groupedByLetter = remember(rows) {
        rows.groupBy {
            val first = it.headphone.name.firstOrNull()?.uppercaseChar() ?: '#'
            if (first.isLetter()) first else '#'
        }.toSortedMap()
    }

    val availableLetters = remember(groupedByLetter) { groupedByLetter.keys }

    data class ListEntry(
        val isHeader: Boolean,
        val letter: Char? = null,
        val row: Row? = null,
    )

    val flatList = remember(groupedByLetter) {
        buildList {
            groupedByLetter.forEach { (letter, group) ->
                add(ListEntry(isHeader = true, letter = letter))
                group.forEach { add(ListEntry(isHeader = false, row = it)) }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = MonoDimens.cardAlpha))
    ) {
        // ─── Header ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.eq_database),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = selectedRig?.label ?: "All rigs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                pluralStringResource(R.plurals.eq_measurement_count, rows.size, rows.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            OfflineDownloadButton(
                state = offlineState,
                anythingSaved = offlineSummary?.isEmpty == false,
                onClick = {
                    viewModel.refreshOfflineSummary()
                    showOffline = true
                },
            )
            IconButton(onClick = { viewModel.refreshHeadphones() }) {
                Icon(Icons.Default.Refresh, stringResource(R.string.eq_refresh), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, stringResource(R.string.action_close), modifier = Modifier.size(20.dp))
            }
        }

        // ─── Rig filter (horizontally scrollable chips) ───
        if (availableRigs.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Pin the Uploaded chip leftmost the moment the user has any
                // saved uploads — gated on uploadedHeadphones (restored from
                // prefs at init) rather than availableRigs (which is empty
                // until the network round-trip in loadAvailableHeadphones
                // returns), so the chip appears instantly on screen open.
                val uploadedRig = MeasurementRig.UPLOADED
                if (uploadedHeadphones.isNotEmpty()) {
                    item(key = "rig_uploaded") {
                        tf.monochrome.android.ui.mixer.GlassChoiceChip(
                            label = uploadedRig.label,
                            selected = selectedRig == uploadedRig,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = {
                                viewModel.setRigFilter(if (selectedRig == uploadedRig) null else uploadedRig)
                            },
                        )
                    }
                }
                item(key = "rig_all") {
                    tf.monochrome.android.ui.mixer.GlassChoiceChip(
                        label = stringResource(R.string.eq_all_rigs),
                        selected = selectedRig == null,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = { viewModel.setRigFilter(null) },
                    )
                }
                val remoteRigs = availableRigs.filter { it != uploadedRig }
                items(
                    count = remoteRigs.size,
                    key = { i -> "rig_${remoteRigs[i].name}" },
                ) { i ->
                    val rig = remoteRigs[i]
                    tf.monochrome.android.ui.mixer.GlassChoiceChip(
                        label = rig.label,
                        selected = selectedRig == rig,
                        accent = MaterialTheme.colorScheme.primary,
                        onClick = {
                            viewModel.setRigFilter(if (selectedRig == rig) null else rig)
                        },
                    )
                }
            }
        }

        // ─── Search bar, floating over the measurements ───
        //
        // A permanent part of this screen rather than something toggled, so the
        // list is given the bar's height as padding: it starts below the glass
        // instead of with its first row parked under it forever, and still
        // scrolls underneath, which is what the glass is for.
        SearchOverlay(
            open = true,
            query = localSearchQuery,
            onQueryChange = { localSearchQuery = it },
            placeholder = stringResource(R.string.eq_search_model_e_g_hd_600),
            onClose = null,
            modifier = Modifier.weight(1f),
            // Arriving on this screen is not a request to type — the rig chips
            // and the A–Z list are how most people find their headphones.
            autoFocus = false,
        ) { searchTopInset ->
        Column(modifier = Modifier.fillMaxSize()) {
        // The room goes to whichever of these is actually first, and to the list
        // as *content* padding rather than a Spacer above it — a Spacer would
        // put the rows below the glass and clip them at the list's own top edge,
        // leaving the bar frosting an empty background.
        val listTopInset = if (error.isNullOrEmpty()) searchTopInset else 0.dp

        // ─── Error ───
        if (!error.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = searchTopInset)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(8.dp),
                    )
                    .padding(12.dp),
            ) {
                Text(
                    error!!,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontSize = 12.sp,
                )
            }
        }

        // ─── Content: list + A-Z sidebar ───
        if (headphonesLoading && availableHeadphones.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = listTopInset),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.eq_loading_measurements_from_autoeq_squig_link),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(top = listTopInset),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.eq_no_measurements_match),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(top = listTopInset),
                ) {
                    flatList.forEachIndexed { index, entry ->
                        if (entry.isHeader && entry.letter != null) {
                            stickyHeader(key = "header_${entry.letter}") {
                                Text(
                                    entry.letter.toString(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surface
                                                .copy(alpha = MonoDimens.cardAlpha)
                                        )
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                            }
                        } else if (entry.row != null) {
                            item(
                                key = "m_${entry.row.headphone.id}_${entry.row.measurement.source}_$index"
                            ) {
                                MeasurementRowItem(
                                    headphone = entry.row.headphone,
                                    measurement = entry.row.measurement,
                                    onClick = {
                                        viewModel.selectMeasurement(
                                            entry.row.headphone,
                                            entry.row.measurement,
                                            channel,
                                        )
                                        onHeadphoneSelected(entry.row.headphone)
                                    },
                                    onLongClick = if (entry.row.measurement.rig == MeasurementRig.UPLOADED) {
                                        { pendingDelete = entry.row.headphone }
                                    } else null,
                                )
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                                )
                            }
                        }
                    }
                }

                AlphabeticalIndexSidebar(
                    availableLetters = availableLetters,
                    onLetterClicked = { letter ->
                        scope.launch {
                            val index = flatList.indexOfFirst {
                                it.isHeader && it.letter == letter
                            }
                            if (index >= 0) {
                                listState.animateScrollToItem(index)
                            }
                        }
                    },
                )
            }
        }
        }
        }
    }

    if (showOffline) {
        OfflineMeasurementsDialog(
            state = offlineState,
            summary = offlineSummary,
            estimate = offlineEstimate,
            onDownload = { viewModel.startOfflineDownload() },
            onStop = { viewModel.stopOfflineDownload() },
            onDelete = { viewModel.deleteOfflineMeasurements() },
            onDismiss = { showOffline = false },
        )
    }

    // Long-press confirmation for uploaded measurements. Tap-and-hold the
    // row → this dialog → Delete wipes the upload from prefs.
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.eq_delete_uploaded_measurement)) },
            text = { Text(toDelete.name) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeUploadedMeasurement(toDelete.id)
                    pendingDelete = null
                }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MeasurementRowItem(
    headphone: Headphone,
    measurement: AutoEqMeasurement,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Headphones,
            contentDescription = null,
            modifier = Modifier.size(28.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                headphone.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.eq_source_rig, measurement.source, measurement.rig.label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            ">",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The header's button for measurements saved for offline use: a ring of the
 * download's progress while it runs, a pin once anything is saved.
 */
@Composable
private fun OfflineDownloadButton(
    state: HeadphoneMeasurementDownloader.State,
    anythingSaved: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick) {
        if (state is HeadphoneMeasurementDownloader.State.Running) {
            if (state.total > 0) {
                CircularProgressIndicator(
                    progress = { state.saved.toFloat() / state.total },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            Icon(
                if (anythingSaved) Icons.Default.OfflinePin else Icons.Default.DownloadForOffline,
                contentDescription = stringResource(R.string.eq_offline_download),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Saving the whole measurement database for offline use: what it costs before
 * it starts, its progress while it runs (it carries on with this closed), and
 * what is on the phone after, with Update and Delete.
 */
@Composable
private fun OfflineMeasurementsDialog(
    state: HeadphoneMeasurementDownloader.State,
    summary: HeadphoneOfflineStore.Summary?,
    estimate: HeadphoneMeasurementDownloader.Estimate,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val numbers = remember { java.text.NumberFormat.getIntegerInstance() }
    val saved = summary?.takeIf { !it.isEmpty }
    val running = state as? HeadphoneMeasurementDownloader.State.Running
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.eq_offline_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running != null) {
                    if (running.total == 0) {
                        Text(stringResource(R.string.eq_offline_loading_list))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Text(
                            stringResource(
                                R.string.eq_offline_progress,
                                numbers.format(running.saved),
                                numbers.format(running.total),
                            ),
                        )
                        LinearProgressIndicator(
                            progress = { running.saved.toFloat() / running.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (running.waiting) {
                        Text(stringResource(R.string.eq_offline_waiting), color = muted)
                    }
                    if (running.failed > 0) {
                        Text(
                            stringResource(R.string.eq_offline_not_fetched, numbers.format(running.failed)),
                            color = muted,
                        )
                    }
                    Text(
                        stringResource(R.string.eq_offline_runs_on),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                } else {
                    val idle = state as? HeadphoneMeasurementDownloader.State.Idle
                    outcomeText(idle)?.let { (text, isError) ->
                        Text(text, color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    if (saved != null) {
                        Text(
                            stringResource(
                                R.string.eq_offline_saved,
                                numbers.format(saved.measurements),
                                android.text.format.Formatter.formatShortFileSize(context, saved.bytes),
                                saved.savedAt?.let {
                                    java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                                        .format(java.util.Date(it))
                                }.orEmpty(),
                            ),
                        )
                    } else if (estimate.measurements > 0) {
                        Text(
                            stringResource(
                                R.string.eq_offline_intro,
                                numbers.format(estimate.measurements),
                                android.text.format.Formatter.formatShortFileSize(context, estimate.downloadBytes),
                                android.text.format.Formatter.formatShortFileSize(context, estimate.storedBytes),
                            ),
                        )
                    } else {
                        Text(stringResource(R.string.eq_offline_intro_unknown))
                    }
                }
            }
        },
        confirmButton = {
            when {
                running != null -> TextButton(onClick = onStop) {
                    Text(stringResource(R.string.eq_offline_stop))
                }
                saved != null -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.eq_offline_update))
                }
                else -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.eq_offline_download_action))
                }
            }
        },
        dismissButton = {
            Row {
                if (running == null && saved != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                }
                TextButton(onClick = onDismiss) {
                    Text(
                        stringResource(
                            if (running == null && saved == null) R.string.action_cancel else R.string.action_close,
                        ),
                    )
                }
            }
        },
    )
}

/** How the last run ended, and whether that is an error; null before any run. */
@Composable
private fun outcomeText(idle: HeadphoneMeasurementDownloader.State.Idle?): Pair<String, Boolean>? {
    val outcome = idle?.last ?: return null
    return when (outcome) {
        HeadphoneMeasurementDownloader.Outcome.COMPLETE ->
            stringResource(R.string.eq_offline_complete) to false
        HeadphoneMeasurementDownloader.Outcome.PARTIAL ->
            stringResource(R.string.eq_offline_partial, java.text.NumberFormat.getIntegerInstance().format(idle.failed)) to false
        HeadphoneMeasurementDownloader.Outcome.STOPPED ->
            stringResource(R.string.eq_offline_stopped) to false
        HeadphoneMeasurementDownloader.Outcome.NO_NETWORK ->
            stringResource(R.string.eq_offline_err_network) to true
        HeadphoneMeasurementDownloader.Outcome.THROTTLED ->
            stringResource(R.string.eq_offline_err_throttled) to true
        HeadphoneMeasurementDownloader.Outcome.NO_SPACE ->
            stringResource(R.string.eq_offline_err_space) to true
    }
}
