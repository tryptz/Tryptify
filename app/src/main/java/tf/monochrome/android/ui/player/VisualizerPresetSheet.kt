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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import tf.monochrome.android.visualizer.VisualizerPresetIndex

/**
 * Where the browser is looking. A path, not a filter, so Back walks it.
 */
private sealed interface PresetScope {
    /** The list of categories, or of authors, depending on the axis. */
    data object Roots : PresetScope
    data class Category(val facet: VisualizerPresetIndex.Facet) : PresetScope
    data class Sub(
        val category: VisualizerPresetIndex.Facet,
        val facet: VisualizerPresetIndex.Facet,
    ) : PresetScope
    data class Author(val facet: VisualizerPresetIndex.Facet) : PresetScope
    data object Favorites : PresetScope
}

private enum class BrowseAxis(val label: String) { Category("Category"), Author("Author") }

/**
 * The preset browser, drawn in the player's own window.
 *
 * It was a ModalBottomSheet, which is a separate window, and that is why it
 * could never be glass: the player it wanted to frost was captured into the
 * window underneath, and haze cannot sample another window's layer. Handed to
 * [MainPlayerScreen]'s `overlay` slot it is a sibling of the player's haze
 * source, which is the one place a pane can actually blur this screen. The
 * speed panel goes the same way and for the same reason.
 *
 * ## Nine thousand seven hundred and ninety-five
 *
 * That is how many presets ship, and for a long time the answer to finding one
 * was a search box and a single row of chips holding every folder name in the
 * pack -- all one hundred and ninety-four of them, alphabetically, so "Aurora"
 * sat beside "Automata" with nothing to say that one is a kind of Reaction and
 * the other a kind of Fractal. Everything was reachable and nothing was
 * findable, which is a filing cabinet with no drawers.
 *
 * [VisualizerPresetIndex] reads the structure that was already in the data:
 * eleven categories over a hundred and eighty-three subcategories, and four
 * hundred and seventy-nine authors parsed out of the file names. This walks it.
 * Search still cuts across everything, because when you know the name you do
 * not want to navigate to it.
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
    var query by remember { mutableStateOf("") }
    var axis by remember { mutableStateOf(BrowseAxis.Category) }
    var scope by remember { mutableStateOf<PresetScope>(PresetScope.Roots) }

    // Back walks the path before it closes the panel: a listener four hundred
    // authors deep expects it to come up a level, not to throw the whole
    // browser away.
    BackHandler(enabled = visible) {
        when {
            query.isNotBlank() -> query = ""
            scope is PresetScope.Sub -> scope = PresetScope.Category((scope as PresetScope.Sub).category)
            scope != PresetScope.Roots -> scope = PresetScope.Roots
            else -> onDismiss()
        }
    }

    // The panel stays composed while hidden so it can animate out, so the
    // browser has to be put back deliberately. On the way IN rather than out:
    // resetting on exit repopulates the list under the slide, which reads as
    // the panel changing its mind on the way down.
    LaunchedEffect(visible) {
        if (visible) {
            query = ""
            scope = PresetScope.Roots
        }
    }

    // Indexing nine thousand names is not free, so it is keyed on the library
    // rather than redone whenever a favourite is toggled or a chip is tapped.
    //
    // And it waits for the browser to be opened at least once. This panel is
    // composed unconditionally -- it has to be, so it can animate out -- so an
    // eager build ran the moment the library loaded, which is when the player
    // screen opens. Walking nine thousand seven hundred names through indexOf,
    // substring, a Regex split and three map insertions apiece, then sorting
    // three facet lists, on the composition thread, while the now-playing
    // screen animates in, for a listener who may never touch the preset
    // browser. `everShown` only ever goes true, so the index is built once and
    // is not thrown away when the panel closes.
    var everShown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) { if (visible) everShown = true }
    val index = remember(presets, everShown) {
        VisualizerPresetIndex.build(if (everShown) presets else emptyList())
    }

    val searching = query.isNotBlank()
    // Keyed on the favourites only where they are consulted. Otherwise one
    // heart tap produced a new Set, invalidated this, and re-filtered all nine
    // thousand seven hundred while the listener was standing in a Category, an
    // Author or a search result -- none of which read it.
    val favoritesKey = favoritePresetIds.takeIf { scope is PresetScope.Favorites }
    val visiblePresets = remember(index, scope, query, favoritesKey) {
        when {
            // Search ignores where you are standing. Knowing the name is the
            // one case where navigating to it is a waste of time.
            searching -> index.presets.filter { it.displayName.contains(query, ignoreCase = true) }
            scope is PresetScope.Favorites -> index.presets.filter { it.id in favoritePresetIds }
            scope is PresetScope.Sub -> index.presets.filter {
                it.tags.getOrNull(0)?.id == (scope as PresetScope.Sub).category.id &&
                    it.tags.getOrNull(1)?.id == (scope as PresetScope.Sub).facet.id
            }
            scope is PresetScope.Author -> index.presets.filter {
                (scope as PresetScope.Author).facet.id in index.authorsOf(it)
            }
            else -> emptyList()
        }
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
            hazeState = LocalPlayerHaze.current,
            glass = LocalMiniPlayerGlass.current,
            modifier = Modifier.fillMaxHeight(0.88f),
            avoidNavigationBar = false,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                PresetBrowserHeader(
                    index = index,
                    scope = scope,
                    searching = searching,
                    matches = visiblePresets.size,
                    favorites = favoritePresetIds.size,
                    onUp = {
                        scope = when (val s = scope) {
                            is PresetScope.Sub -> PresetScope.Category(s.category)
                            else -> PresetScope.Roots
                        }
                    },
                    onSettingsClick = {
                        onDismiss()
                        onSettingsClick()
                    },
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    var searchBarHeight by remember { mutableStateOf(0.dp) }
                    val density = LocalDensity.current

                    // Scoped to this panel rather than the player's source. The
                    // bar is drawn inside that layer, so handing it over would
                    // have it sampling a picture it is part of -- haze has
                    // nothing valid to give and paints its base colour instead.
                    val haze = rememberHazeState()

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .hazeSource(haze),
                        // The bar's height reaches the list as contentPadding,
                        // not as padding on the list or a Spacer: rows start
                        // below the glass while staying free to travel up
                        // behind it.
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = searchBarHeight + 8.dp,
                            bottom = 24.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (!searching && scope == PresetScope.Roots) {
                            item {
                                FacetRow(
                                    label = "Favourites",
                                    count = favoritePresetIds.size,
                                    onClick = { scope = PresetScope.Favorites },
                                )
                            }
                            val roots = when (axis) {
                                BrowseAxis.Category -> index.categories
                                BrowseAxis.Author -> index.authors
                            }
                            items(roots, key = { "${axis.name}_${it.id}" }) { facet ->
                                FacetRow(
                                    label = facet.label,
                                    count = facet.count,
                                    onClick = {
                                        scope = when (axis) {
                                            BrowseAxis.Category -> PresetScope.Category(facet)
                                            BrowseAxis.Author -> PresetScope.Author(facet)
                                        }
                                    },
                                )
                            }
                        } else if (!searching && scope is PresetScope.Category) {
                            val category = (scope as PresetScope.Category).facet
                            items(
                                index.subcategoriesOf(category.id),
                                key = { "sub_${it.id}" },
                            ) { facet ->
                                FacetRow(
                                    label = facet.label,
                                    count = facet.count,
                                    onClick = { scope = PresetScope.Sub(category, facet) },
                                )
                            }
                        } else {
                            items(visiblePresets, key = { it.id }) { preset ->
                                VisualizerPresetRow(
                                    preset = preset,
                                    title = index.titleOf(preset),
                                    subtitle = subtitleFor(index, preset, scope),
                                    selected = preset.id == selectedPresetId,
                                    isFavorite = preset.id in favoritePresetIds,
                                    onClick = {
                                        onPresetSelected(preset)
                                        onDismiss()
                                    },
                                    onToggleFavorite = { onToggleFavorite(preset.id) },
                                )
                            }
                        }
                    }

                    GlassSearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = "Search ${index.presets.size} presets",
                        hazeState = haze,
                        // Permanent chrome of this panel, so the trailing
                        // button has nothing to dismiss once the field is
                        // empty.
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
                            },
                    ) {
                        // The axis switch lives in the bar's own pane, so the
                        // browser and the field are one sheet of glass. Only at
                        // the roots: once you are inside Reaction, offering to
                        // reinterpret that as an author is meaningless.
                        if (!searching && scope == PresetScope.Roots) {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 10.dp),
                            ) {
                                BrowseAxis.entries.forEachIndexed { i, option ->
                                    SegmentedButton(
                                        selected = axis == option,
                                        onClick = { axis = option },
                                        shape = SegmentedButtonDefaults.itemShape(
                                            index = i,
                                            count = BrowseAxis.entries.size,
                                        ),
                                        label = { Text(option.label, maxLines = 1) },
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

/** Where the preset sits, which is what the row's second line is for. */
private fun subtitleFor(
    index: VisualizerPresetIndex,
    preset: VisualizerPreset,
    scope: PresetScope,
): String {
    // Under an author, saying the author again on every row wastes the line.
    val credits = if (scope is PresetScope.Author) emptyList() else index.authorsOf(preset)
    val place = preset.tags.joinToString(" · ") { it.label }
    val author = credits.firstOrNull()?.let(index::authorLabel)
    return listOfNotNull(author, place.takeIf { it.isNotBlank() }).joinToString(" · ")
        .ifBlank { "Uncategorised" }
}

/**
 * The title bar, which doubles as the way back up.
 *
 * It reads as a breadcrumb rather than a static heading because the browser is
 * now several levels deep, and a listener inside `Reaction / Aurora` needs to
 * see where they are without leaving to find out.
 */
@Composable
private fun PresetBrowserHeader(
    index: VisualizerPresetIndex,
    scope: PresetScope,
    searching: Boolean,
    matches: Int,
    favorites: Int,
    onUp: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val atRoot = scope == PresetScope.Roots && !searching
    val title = when {
        searching -> "Search"
        scope is PresetScope.Favorites -> "Favourites"
        scope is PresetScope.Author -> scope.facet.label
        scope is PresetScope.Sub -> scope.facet.label
        scope is PresetScope.Category -> scope.facet.label
        else -> "Visualizer Presets"
    }
    val detail = when {
        searching -> "$matches of ${index.presets.size}"
        scope is PresetScope.Roots ->
            "${index.presets.size} presets · ${index.categories.size} categories · " +
                "${index.authors.size} authors · $favorites favourites"
        scope is PresetScope.Category -> "${scope.facet.count} presets in " +
            "${index.subcategoriesOf(scope.facet.id).size} groups"
        scope is PresetScope.Sub -> "${scope.category.label} · $matches presets"
        else -> "$matches presets"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!atRoot) {
            IconButton(onClick = onUp, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
        IconButton(onClick = onSettingsClick) {
            Icon(Icons.Default.Settings, contentDescription = "Settings")
        }
    }
}

/**
 * One drawer of the cabinet: a name, how much is behind it, and an arrow.
 *
 * The count is the point. "Reaction" alone says nothing about whether it is
 * worth opening; "Reaction 1,791" says it is most of the pack, and "Supernova
 * 380" says it is a corner of it.
 */
@Composable
private fun FacetRow(label: String, count: Int, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .glassSqueeze(press = rememberGlassPress(), onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = MonoDimens.glassBorderWidth,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = LocalContentColor.current.copy(alpha = 0.7f),
            )
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = LocalContentColor.current.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun VisualizerPresetRow(
    preset: VisualizerPreset,
    title: String,
    subtitle: String,
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
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
