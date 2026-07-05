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
}

@Composable
fun <K : Any> rememberTrackSelectionState(): TrackSelectionState<K> =
    remember { TrackSelectionState() }
