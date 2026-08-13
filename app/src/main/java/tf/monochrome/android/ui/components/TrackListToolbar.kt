package tf.monochrome.android.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.components.GlassSearchBar
import androidx.compose.foundation.background

/**
 * Search box and sort control for a list of tracks.
 *
 * One component for every list rather than a variant per screen: the local
 * library grew its own sort row, and copying that shape into playlists,
 * downloads and the rest would have meant six near-identical implementations
 * drifting apart. Each caller supplies the [orders] its data can actually
 * support — a single album's track list has one album, so offering "Album"
 * there would be a menu entry that reorders nothing.
 *
 * The search field is collapsed behind its icon by default. A list of six
 * tracks does not need a permanently-visible search box eating a row of
 * screen, and the icon is where people look for one anyway.
 */
@Composable
fun TrackListToolbar(
    sort: TrackSort,
    onSortChange: (TrackSort) -> Unit,
    modifier: Modifier = Modifier,
    orders: List<TrackOrder> = DEFAULT_TRACK_ORDERS,
    /** Rendered before the sort button — bulk actions, counts. */
    trailing: @Composable (() -> Unit)? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Opaque, because this is used as a sticky header: it stops at the top of
    // the list and the songs keep going underneath it. Transparent, the rows
    // would slide through the icons.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            trailing?.invoke()
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(
                        imageVector = Icons.Default.SwapVert,
                        contentDescription = "Sort",
                        tint = if (sort.order == TrackOrder.ORIGINAL) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            // Lit while a non-default order is applied, so a list
                            // that isn't in its natural order says so.
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    orders.forEach { order ->
                        val selected = order == sort.order
                        DropdownMenuItem(
                            text = { Text(order.label) },
                            onClick = {
                                // Re-picking the current key flips direction —
                                // the usual behaviour, and it saves a second control.
                                onSortChange(
                                    if (selected) sort.copy(ascending = !sort.ascending)
                                    else TrackSort(order, ascending = true)
                                )
                                menuOpen = false
                            },
                            leadingIcon = {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                            trailingIcon = {
                                if (selected) {
                                    Icon(
                                        imageVector = if (sort.ascending) {
                                            Icons.Default.ArrowUpward
                                        } else {
                                            Icons.Default.ArrowDownward
                                        },
                                        contentDescription = if (sort.ascending) "Ascending" else "Descending",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            },
                        )
                    }
                }
            }
        }

    }
}
