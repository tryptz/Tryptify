package tf.monochrome.android.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.R
import tf.monochrome.android.data.local.scanner.MediaScanner
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.components.GlassSearchBar
import tf.monochrome.android.ui.discover.galaxy.StarField
import tf.monochrome.android.ui.mixer.GlassChoiceChip

// The controls both galaxies share: the two layouts as pills at the bottom,
// and the search bar under the title. One copy, so the genre map and the
// library map cannot drift apart.

/**
 * The two layouts as two pills of glass: the disc and the spiral of time. The
 * one showing is lit. They replaced a panel that covered a third of the sky to
 * hold two choices; Surprise lives in the title bar.
 */
@Composable
internal fun GalaxyLayoutPills(timeline: Boolean, onTimeline: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DOCK_MARGIN_DP.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(DOCK_GAP_DP.dp, Alignment.CenterHorizontally),
    ) {
        listOf(false to R.string.galaxy_layout_galaxy, true to R.string.galaxy_layout_timeline).forEach { (option, label) ->
            GlassChoiceChip(
                label = stringResource(label),
                selected = timeline == option,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { onTimeline(option) },
                height = LAYOUT_PILL_HEIGHT,
                modifier = Modifier.weight(1f, fill = false).widthIn(min = LAYOUT_PILL_MIN_WIDTH),
            )
        }
    }
}

/**
 * A galaxy's search bar: the app's own glass bar, over the map so it frosts
 * the sky, with the matching stars as a row of pills under the field — the
 * globe's arrangement, so the sky the search is about stays in view. A pill
 * flies to its star; the keyboard's search key takes the first.
 */
@Composable
internal fun GalaxySearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    /** Said under the field when nothing matches. */
    noMatch: String,
    /** Star id and name, best first. */
    matches: List<Pair<String, String>>,
    hazeState: HazeState,
    glass: PlayerGlassSettings,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
) {
    GlassSearchBar(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = placeholder,
        // The map is the haze source and this bar sits over it, so there is a
        // real sky under the glass to frost.
        hazeState = hazeState,
        glass = glass,
        autoFocus = true,
        onSubmit = { matches.firstOrNull()?.let { onPick(it.first) } },
        onClose = onClose,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        if (query.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            if (matches.isEmpty()) {
                Text(
                    noMatch,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(matches, key = { it.first }) { (id, name) ->
                        GlassChoiceChip(
                            label = name,
                            selected = false,
                            accent = MaterialTheme.colorScheme.primary,
                            onClick = { onPick(id) },
                            height = 36.dp,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Up to [limit] stars whose name — or one of its [aliases], for a genre's
 * other names — contains [query]: those that start with it first, then the
 * biggest stars. Empty for an empty query.
 */
internal fun matchStars(
    field: StarField,
    query: String,
    limit: Int = MAX_SEARCH_MATCHES,
    aliases: (Int) -> List<String> = { emptyList() },
): List<Pair<String, String>> {
    val q = MediaScanner.normalizeText(query)
    if (q.isEmpty()) return emptyList()
    return field.names.indices
        .mapNotNull { i ->
            val at = (listOf(field.names[i]) + aliases(i))
                .map { MediaScanner.normalizeText(it).indexOf(q) }
                .filter { it >= 0 }
                .minOrNull() ?: return@mapNotNull null
            Triple(i, at == 0, field.prominence[i])
        }
        .sortedWith(compareByDescending<Triple<Int, Boolean, Float>> { it.second }.thenByDescending { it.third })
        .take(limit)
        .map { field.ids[it.first] to field.names[it.first] }
}

/** At most this many stars under a search field. */
private const val MAX_SEARCH_MATCHES = 12

/** The layout pills: as tall as the dock's, and never narrower than this. */
private val LAYOUT_PILL_HEIGHT = 48.dp
private val LAYOUT_PILL_MIN_WIDTH = 132.dp
