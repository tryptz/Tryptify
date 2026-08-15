// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.sampler

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tf.monochrome.android.domain.patterns.SampleCategory
import tf.monochrome.android.domain.patterns.SampleRef
import tf.monochrome.android.ui.patterns.PanelLabel
import tf.monochrome.android.ui.patterns.PatternPill
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The sample library, as a list.
 *
 * Nothing here reads audio. Each row draws the cached peak file if the caller
 * has it and a flat line if not, so scrolling a library of several hundred
 * samples never opens a WAV — which is the difference between a browser that
 * scrolls and one that stutters every few rows.
 */
@Composable
fun SampleList(
    samples: List<SampleRef>,
    selected: SampleRef?,
    accent: Color,
    modifier: Modifier = Modifier,
    peaksFor: (SampleRef) -> FloatArray? = { null },
    onPick: (SampleRef) -> Unit,
    onLongPress: (SampleRef) -> Unit = {},
) {
    if (samples.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth().height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No samples yet.\nCapture one from what you're listening to.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(samples, key = { it.id }) { sample ->
            SampleRow(
                sample = sample,
                selected = sample.id == selected?.id,
                accent = accent,
                peaks = peaksFor(sample),
                onClick = { onPick(sample) },
                onLongClick = { onLongPress(sample) },
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SampleRow(
    sample: SampleRef,
    selected: Boolean,
    accent: Color,
    peaks: FloatArray?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val surface = MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MonoDimens.shapeSm)
            .background(
                if (selected) lerp(surface, accent, 0.18f) else lerp(surface, Color.Black, 0.14f),
            )
            .border(
                width = if (selected) 1.dp else 0.5.dp,
                color = if (selected) accent.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.07f),
                shape = MonoDimens.shapeSm,
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WaveformThumbnail(
            peaks = peaks,
            color = accent,
            modifier = Modifier.width(56.dp).height(30.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = sample.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = buildString {
                    append(sample.category.label)
                    append(" · ")
                    append(formatDuration(sample.durationMs))
                    sample.sourceTrackTitle?.let {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Category filter strip shared by the browser and the picker. */
@Composable
fun CategoryStrip(
    selected: SampleCategory?,
    accent: Color,
    modifier: Modifier = Modifier,
    onSelect: (SampleCategory?) -> Unit,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            PatternPill(
                label = "All",
                active = selected == null,
                accent = accent,
                onClick = { onSelect(null) },
            )
        }
        items(SampleCategory.entries.toList()) { category ->
            PatternPill(
                label = category.label,
                active = selected == category,
                accent = accent,
                onClick = { onSelect(category) },
            )
        }
    }
}

/**
 * The "choose a sample for this channel" sheet.
 *
 * Assigning is the step between capturing a sound and hearing it in a pattern,
 * so it is one tap from the channel strip and one tap inside the sheet. The
 * category strip is a filter rather than a hierarchy — a sample the user
 * filed under FX is still findable when they are looking at everything, which
 * is not true of a folder tree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SamplePickerSheet(
    library: List<SampleRef>,
    selected: SampleRef?,
    accent: Color,
    onDismiss: () -> Unit,
    onPick: (SampleRef?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var category by remember { mutableStateOf<SampleCategory?>(null) }
    val filtered = remember(library, category) {
        if (category == null) library else library.filter { it.category == category }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Assign sample",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(10.dp))
            CategoryStrip(selected = category, accent = accent, onSelect = { category = it })
            Spacer(Modifier.height(10.dp))
            SampleList(
                samples = filtered,
                selected = selected,
                accent = accent,
                modifier = Modifier.heightIn(max = 380.dp),
                onPick = onPick,
            )
            Spacer(Modifier.height(10.dp))
            PatternPill(
                label = "Clear channel sample",
                active = false,
                accent = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
                onClick = { onPick(null) },
            )
        }
    }
}

internal fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000.0
    return if (totalSeconds < 10) {
        "%.2fs".format(totalSeconds)
    } else {
        val minutes = (ms / 60_000).toInt()
        val seconds = ((ms % 60_000) / 1000).toInt()
        "%d:%02d".format(minutes, seconds)
    }
}
