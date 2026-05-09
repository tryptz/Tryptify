package tf.monochrome.android.ui.eq

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.domain.model.MeasurementRig
import tf.monochrome.android.ui.theme.MonoDimens

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
) {
    val availableHeadphones by viewModel.availableHeadphones.collectAsState()
    val headphonesLoading by viewModel.headphonesLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedRig by viewModel.selectedRig.collectAsState()
    val availableRigs by viewModel.availableRigs.collectAsState()

    var localSearchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        viewModel.loadAvailableHeadphones()
    }

    // Denormalize: one row per (headphone, measurement) pair, then apply
    // search + rig filter. A headphone measured by N sources contributes
    // N rows when "All rigs" is selected, so the user can pick the source
    // they trust without first picking the headphone.
    data class Row(val headphone: Headphone, val measurement: AutoEqMeasurement)

    val rows = remember(availableHeadphones, localSearchQuery, selectedRig) {
        availableHeadphones
            .asSequence()
            .filter { hp ->
                localSearchQuery.isEmpty() || hp.name.contains(localSearchQuery, ignoreCase = true)
            }
            .flatMap { hp -> hp.measurements.map { Row(hp, it) } }
            .filter { selectedRig == null || it.measurement.rig == selectedRig }
            .toList()
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
                    "Database",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = selectedRig?.label ?: "All rigs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${rows.size} measurements",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            IconButton(onClick = { viewModel.refreshHeadphones() }) {
                Icon(Icons.Default.Refresh, "Refresh", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Close", modifier = Modifier.size(20.dp))
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
                item(key = "rig_all") {
                    FilterChip(
                        selected = selectedRig == null,
                        onClick = { viewModel.setRigFilter(null) },
                        label = { Text("All rigs") },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
                items(
                    count = availableRigs.size,
                    key = { i -> "rig_${availableRigs[i].name}" },
                ) { i ->
                    val rig = availableRigs[i]
                    FilterChip(
                        selected = selectedRig == rig,
                        onClick = {
                            viewModel.setRigFilter(if (selectedRig == rig) null else rig)
                        },
                        label = { Text(rig.label) },
                        colors = FilterChipDefaults.filterChipColors(),
                    )
                }
            }
        }

        // ─── Search Bar ───
        OutlinedTextField(
            value = localSearchQuery,
            onValueChange = { localSearchQuery = it },
            placeholder = { Text("Search model (e.g. HD 600)...") },
            leadingIcon = {
                Icon(
                    Icons.Default.Search, null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            trailingIcon = {
                if (localSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { localSearchQuery = "" }) {
                        Icon(Icons.Default.Close, "Clear", modifier = Modifier.size(18.dp))
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MonoDimens.glassAlpha),
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MonoDimens.glassAlpha),
            ),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // ─── Error ───
        if (!error.isNullOrEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
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
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Loading measurements from AutoEq + squig.link...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else if (rows.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "No measurements match",
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
                                        )
                                        onHeadphoneSelected(entry.row.headphone)
                                    },
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

@Composable
private fun MeasurementRowItem(
    headphone: Headphone,
    measurement: AutoEqMeasurement,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${measurement.source} • ${measurement.rig.label}",
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
