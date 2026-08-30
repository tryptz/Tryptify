package tf.monochrome.android.ui.player
import tf.monochrome.android.ui.components.rememberGlassPress
import tf.monochrome.android.ui.components.glassSqueeze

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.ui.components.GlassSearchBar
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.graphics.Color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualizerPresetSheet(
    presets: List<VisualizerPreset>,
    selectedPresetId: String?,
    favoritePresetIds: Set<String> = emptySet(),
    onPresetSelected: (VisualizerPreset) -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    onSettingsClick: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }

    val tags = remember(presets) {
        presets.flatMap { preset -> preset.tags.map { tag -> tag.label } }
            .distinct()
            .sorted()
    }
    val filteredPresets = remember(presets, query, selectedTag) {
        presets.filter { preset ->
            val matchesQuery = query.isBlank() || preset.displayName.contains(query, ignoreCase = true)
            val matchesTag = selectedTag == null || preset.tags.any { tag -> tag.label == selectedTag }
            matchesQuery && matchesTag
        }
    }

    val favoritePresets = remember(filteredPresets, favoritePresetIds) {
        filteredPresets.filter { it.id in favoritePresetIds }
    }
    val nonFavoritePresets = remember(filteredPresets, favoritePresetIds) {
        filteredPresets.filter { it.id !in favoritePresetIds }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = MonoDimens.cardAlpha),
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Fixed chrome, and deliberately *above* the bar rather than behind
            // it: the same reason Settings' tab rail sits above its own search,
            // which is that a floating pane must not cover the thing telling you
            // where you are.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Visualizer Presets",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${filteredPresets.size} presets · ${favoritePresetIds.size} favorites",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                }
                IconButton(
                    onClick = {
                        onDismiss()
                        onSettingsClick()
                    }
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings"
                    )
                }
            }

            // The bar floats over the list rather than sitting in the Column
            // above it. Laid out inline it pushes the rows down and the list
            // then clips at its own new top edge, so presets vanish at a hard
            // line short of the glass.
            Box(modifier = Modifier.fillMaxSize()) {
                var searchBarHeight by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // The bar's height reaches the list as contentPadding, not
                    // as padding on the list or a Spacer: rows start below the
                    // glass while staying free to travel up behind it, so
                    // nothing is covered at rest and the list still slides
                    // under the bar as soon as it is scrolled.
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = searchBarHeight + 8.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (favoritePresets.isNotEmpty()) {
                        item {
                            Text(
                                text = "Favorites",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        }
                        items(favoritePresets, key = { "fav_${it.id}" }) { preset ->
                            VisualizerPresetRow(
                                preset = preset,
                                selected = preset.id == selectedPresetId,
                                isFavorite = true,
                                onClick = {
                                    onPresetSelected(preset)
                                    onDismiss()
                                },
                                onToggleFavorite = { onToggleFavorite(preset.id) }
                            )
                        }
                        if (nonFavoritePresets.isNotEmpty()) {
                            item {
                                Text(
                                    text = "All Presets",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                        }
                    }
                    items(nonFavoritePresets, key = { it.id }) { preset ->
                        VisualizerPresetRow(
                            preset = preset,
                            selected = preset.id == selectedPresetId,
                            isFavorite = false,
                            onClick = {
                                onPresetSelected(preset)
                                onDismiss()
                            },
                            onToggleFavorite = { onToggleFavorite(preset.id) }
                        )
                    }
                }

                GlassSearchBar(
                    query = query,
                    onQueryChange = { query = it },
                    placeholder = "Search presets",
                    // No haze source inside a bottom sheet to sample, so the bar
                    // takes the plain translucent glass rather than frosting a
                    // backdrop it cannot see.
                    hazeState = null,
                    // Permanent chrome of this sheet, so the trailing button has
                    // nothing to dismiss once the field is empty.
                    onClose = null,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 12.dp)
                        // Measured on the bar itself. Measuring a wrapper that
                        // animates its height reports a value climbing from zero
                        // and the inset spends the animation chasing it.
                        .onSizeChanged { size ->
                            searchBarHeight = with(density) { size.height.toDp() }
                        }
                ) {
                    // Inside the bar's own pane, so the filter and the field are
                    // one sheet of glass instead of a bar with a second thing
                    // floating under it. It is one scrolling row -- the reason
                    // Discover's rail stayed on the page is that it was four
                    // rows deep and covered the feed it filtered.
                    if (tags.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = selectedTag == null,
                                onClick = { selectedTag = null },
                                label = { Text("All") }
                            )
                            tags.forEach { tag ->
                                FilterChip(
                                    selected = selectedTag == tag,
                                    onClick = {
                                        selectedTag = if (selectedTag == tag) null else tag
                                    },
                                    label = { Text(tag) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualizerPresetRow(
    preset: VisualizerPreset,
    selected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .liquidGlass(shape = RoundedCornerShape(20.dp))
            .glassSqueeze(press = rememberGlassPress(), onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val tagLine = preset.tags.joinToString(" · ") { it.label }
                Text(
                    text = if (tagLine.isBlank()) {
                        "Intensity ${preset.intensity}"
                    } else {
                        "$tagLine · Intensity ${preset.intensity}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
