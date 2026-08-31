package tf.monochrome.android.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassSearchBar
import tf.monochrome.android.ui.components.glassSqueeze
import tf.monochrome.android.ui.components.rememberGlassPress
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The preset browser, drawn in the player's own window.
 *
 * It was a ModalBottomSheet, which is a separate window, and that is why it
 * could never be glass: the player it wanted to frost was captured into the
 * window underneath, and haze cannot sample another window's layer. Held at
 * cardAlpha the sheet was simply see-through instead -- the transport, the
 * track title and the visualizer all showing through the list rather than being
 * blurred behind it.
 *
 * Handed to [MainPlayerScreen]'s `overlay` slot it is a sibling of the player's
 * haze source, which is the one place a pane can actually blur this screen. The
 * speed panel goes the same way and for the same reason. What that costs is the
 * scrim, the slide and Back, which the sheet used to get for free.
 */
@Composable
fun BoxScope.VisualizerPresetPanel(
    visible: Boolean,
    presets: List<VisualizerPreset>,
    selectedPresetId: String?,
    favoritePresetIds: Set<String> = emptySet(),
    onPresetSelected: (VisualizerPreset) -> Unit,
    onToggleFavorite: (String) -> Unit = {},
    onSettingsClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Ahead of the player's own Back while the panel is up, and out of the way
    // when it is not.
    BackHandler(enabled = visible) { onDismiss() }

    var query by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    // The panel stays in composition while hidden so it can animate out, so the
    // filter has to be cleared deliberately. On the way IN rather than out:
    // resetting on exit repopulates the list under the slide, which reads as the
    // panel changing its mind on the way down.
    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            selectedTag = null
        }
    }

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

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.matchParentSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Dismiss",
                    onClick = onDismiss,
                ),
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        GlassPanel(
            // The player's background layer, which this pane is a sibling of
            // rather than a descendant -- the whole reason it can be glass.
            hazeState = LocalPlayerHaze.current,
            // The mini player's material, like every other floating pane that
            // is not the transport itself.
            glass = LocalMiniPlayerGlass.current,
            modifier = Modifier.fillMaxHeight(0.88f),
            avoidNavigationBar = false,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Fixed chrome, and deliberately *above* the bar rather than
                // behind it: the same reason Settings' tab rail sits above its
                // own search, which is that a floating pane must not cover the
                // thing telling you where you are.
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

                    // Scoped to this panel rather than the player's source. The
                    // bar is drawn inside that layer, so handing it over would
                    // have it sampling a picture it is part of -- haze has
                    // nothing valid to give and paints its base colour instead,
                    // which is a flat slab exactly where the glass should be.
                    val haze = rememberHazeState()

                    LazyColumn(
                        // The source is the list, because the list is what
                        // passes behind the bar.
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(haze),
                        // The bar's height reaches the list as contentPadding,
                        // not as padding on the list or a Spacer: rows start
                        // below the glass while staying free to travel up behind
                        // it.
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
                        hazeState = haze,
                        // Permanent chrome of this panel, so the trailing button
                        // has nothing to dismiss once the field is empty.
                        onClose = null,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 12.dp)
                            // Measured on the bar itself. Measuring a wrapper
                            // that animates its height reports a value climbing
                            // from zero and the inset spends the animation
                            // chasing it.
                            .onSizeChanged { size ->
                                searchBarHeight = with(density) { size.height.toDp() }
                            }
                    ) {
                        // Inside the bar's own pane, so the filter and the field
                        // are one sheet of glass instead of a bar with a second
                        // thing floating under it.
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
}

@Composable
private fun VisualizerPresetRow(
    preset: VisualizerPreset,
    selected: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    // Solid, with the rim doing the work the blur cannot.
    //
    // This row asked liquidGlass for glass it had no way to make. It passes no
    // haze state, so the modifier drops to its tint-and-rim tier -- and on a
    // device where blur is off it returns the modifier untouched, leaving the
    // row with no background at all. Either way the fill was Color.Transparent,
    // so what sat behind the title was the projectM canvas, moving, at whatever
    // brightness the preset happened to be.
    //
    // Handing it the sheet's haze state would not have helped: these rows are
    // drawn inside the LazyColumn that *is* the haze source, and an effect
    // cannot sample the layer it lives in.
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .glassSqueeze(press = rememberGlassPress(), onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        border = BorderStroke(
            width = MonoDimens.glassBorderWidth,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
            },
        ),
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
                    // Derived from the row's own content colour rather than
                    // pinned to onSurfaceVariant, which is a foreground for the
                    // surface roles and lands on primaryContainer when the row
                    // is selected -- the one row where the subtitle would be
                    // hardest to read.
                    color = LocalContentColor.current.copy(alpha = 0.7f),
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
