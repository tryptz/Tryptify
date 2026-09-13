package tf.monochrome.android.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.data.local.db.LocalFacetTally
import tf.monochrome.android.ui.components.FastScroller
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.detail.LocalFacet
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * One way into the local library.
 *
 * The Local page used to be five swipeable sub-tabs behind a `ScrollableTabRow`,
 * nested inside the section pager, which was itself nested in the page pager.
 * Three pagers deep, Folders was four drags from Albums, and the tab strip was
 * permanently mid-scroll on a phone because five labels do not fit in 360dp.
 * This is that same set as a list you read and tap.
 *
 * [id] is the key the selected category is saved under, so it survives process
 * death. It is spelled out rather than taken from [name] so that renaming a
 * constant cannot silently drop a user back to the index.
 *
 * The three facet-backed categories borrow their icon from [LocalFacet] rather
 * than declaring one, so the row you tap and the screen it opens cannot end up
 * wearing different symbols.
 */
enum class LibraryCategory(
    val id: String,
    val label: String,
    val icon: ImageVector,
) {
    SONGS("songs", "Songs", Icons.Default.MusicNote),
    ALBUMS("albums", "Albums", Icons.Default.Album),
    ARTISTS("artists", "Artists", Icons.Default.Person),
    ALBUM_ARTISTS("album_artists", "Album Artists", LocalFacet.ALBUM_ARTIST.icon),
    COMPOSERS("composers", "Composers", LocalFacet.COMPOSER.icon),
    GENRES("genres", "Genres", LocalFacet.GENRE.icon),
    YEARS("years", "Years", LocalFacet.YEAR.icon),
    FOLDERS("folders", "Folders", Icons.Default.Folder);

    companion object {
        fun fromId(id: String?): LibraryCategory? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The categories, as tiles.
 *
 * Deliberately the same material and metrics as `PageJumpList`: this is the
 * second list-of-places in the app and the two should not look like they came
 * from different products. That means `headlineMedium` bold on a `liquidGlass`
 * pane, spaced 12dp so they read as separate tiles rather than one striped
 * slab — and, as there, **no hazeState**. This is inside the pager, which is
 * inside the app's one hazeSource, and a haze child within its own source is a
 * cycle Haze throws on at draw time.
 *
 * No track counts. Every count would mean keeping that category's flow
 * collected for as long as the index is on screen, which is the opposite of
 * what browsing one category at a time is for.
 */
@Composable
fun LibraryIndexList(
    onSelect: (LibraryCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            top = MonoDimens.spacingSm,
            bottom = MonoDimens.listBottomPadding,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(LibraryCategory.entries, key = { it.id }, contentType = { "category" }) { category ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MonoDimens.listItemPaddingH)
                    .liquidGlass(shape = MonoDimens.shapeMd)
                    .bounceClick { onSelect(category) }
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    category.icon,
                    contentDescription = null,
                    modifier = Modifier.size(MonoDimens.iconMd),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(MonoDimens.spacingLg))
                Text(
                    text = category.label,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A facet's values, with how many tracks each holds.
 *
 * One list for album artists, composers, years and genres. They are the same
 * screen — a name and a tally — and [LocalFacetTally] is shaped like
 * `LocalGenreEntity` for exactly this reason, so the four cannot drift apart in
 * appearance the way four copies would.
 */
@Composable
fun FacetTallyList(
    tallies: List<LocalFacetTally>,
    icon: ImageVector,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    Box(modifier = modifier) {
        LazyColumn(
            state = state,
            contentPadding = PaddingValues(bottom = MonoDimens.listBottomPadding),
        ) {
            items(tallies, key = { it.name }, contentType = { "facet" }) { tally ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(MonoDimens.listRowHeight)
                        .bounceClick { onClick(tally.name) }
                        .padding(horizontal = MonoDimens.listItemPaddingH),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        modifier = Modifier.size(MonoDimens.iconMd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(MonoDimens.spacingLg))
                    Text(
                        tally.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (tally.trackCount == 1) "1 track" else "${tally.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        FastScroller(state = state)
    }
}
