package tf.monochrome.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Per-screen multi-select state for track lists. Selection mode is active
 * exactly while at least one row is selected, so deselecting the last row
 * exits the mode with no extra bookkeeping. Generic key type because legacy
 * Track/DownloadedTrackEntity ids are Longs while UnifiedTrack ids are Strings.
 */
@Stable
class TrackSelectionState<K : Any> {
    var selectedIds by mutableStateOf(emptySet<K>())
        private set

    val active: Boolean get() = selectedIds.isNotEmpty()
    val count: Int get() = selectedIds.size

    fun toggle(id: K) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    fun clear() {
        selectedIds = emptySet()
    }

    /** Adds every one of [ids], as the bar's "Select all" does with the list on screen. */
    fun selectAll(ids: Collection<K>) {
        selectedIds = selectedIds + ids
    }

    /**
     * The bar's "Select all" over [items] on screen, or null when there are none
     * or every one is already picked. It stops at the first row not yet picked
     * and builds the id list only when tapped, so a long list costs a glance,
     * not a copy, each time the bar recomposes.
     */
    fun <T> selectAllOf(items: List<T>, key: (T) -> K): (() -> Unit)? {
        val picked = selectedIds
        if (items.isEmpty() || items.all { key(it) in picked }) return null
        return { selectAll(items.map(key)) }
    }
}

@Composable
fun <K : Any> rememberTrackSelectionState(): TrackSelectionState<K> =
    remember { TrackSelectionState() }
