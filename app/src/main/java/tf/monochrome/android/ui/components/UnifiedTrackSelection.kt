package tf.monochrome.android.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.R
import tf.monochrome.android.data.local.art.ArtRequest
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.party.LocalPartyAdder
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Multi-select over a [UnifiedTrack] list, begun from "Select" in a song's ⋮
 * menu (holding a row plays its preview, see docs/ui-invariants.md, List rows).
 *
 * It keeps the picked songs themselves, not only their ids, so a paged list
 * (the Local page's Songs) can act on a song whose row has scrolled away and
 * been dropped from the page.
 */
@Stable
class UnifiedTrackSelection {
    private val ids = TrackSelectionState<String>()
    private val picked = HashMap<String, UnifiedTrack>()

    val active: Boolean get() = ids.active
    val count: Int get() = ids.count

    fun isSelected(track: UnifiedTrack): Boolean = track.id in ids.selectedIds

    fun toggle(track: UnifiedTrack) {
        if (isSelected(track)) picked.remove(track.id) else picked[track.id] = track
        ids.toggle(track.id)
    }

    fun clear() {
        picked.clear()
        ids.clear()
    }

    /** The selected songs in [order]'s order when given, else in the order they were picked. */
    fun tracks(order: List<UnifiedTrack>? = null): List<UnifiedTrack> {
        val selected = ids.selectedIds
        return order?.filter { it.id in selected } ?: selected.mapNotNull { picked[it] }
    }
}

@Composable
fun rememberUnifiedTrackSelection(): UnifiedTrackSelection = remember { UnifiedTrackSelection() }

/**
 * The bar over a [UnifiedTrackSelection] while it is active, with the
 * add-to-playlist and create-playlist follow-ups it opens, and Back to leave
 * the selection. Find album art and Upload to Tryptify Cloud show when a picked
 * song is a file on the phone, and act on those files.
 *
 * @param order the list on screen, so queue and playlist get its order.
 */
@Composable
fun UnifiedTrackSelectionBar(
    selection: UnifiedTrackSelection,
    playerViewModel: PlayerViewModel,
    modifier: Modifier = Modifier,
    order: List<UnifiedTrack>? = null,
) {
    BackHandler(enabled = selection.active) { selection.clear() }
    var addToPlaylist by remember { mutableStateOf(false) }
    var createPlaylist by remember { mutableStateOf(false) }
    val selected = { selection.tracks(order) }

    if (addToPlaylist) {
        val playlists by playerViewModel.playlists.collectAsStateWithLifecycle()
        AddToPlaylistSheet(
            title = pluralStringResource(R.plurals.add_n_tracks_to_playlist, selection.count, selection.count),
            playlists = playlists,
            onDismiss = { addToPlaylist = false },
            onPlaylistSelected = { playlist ->
                playerViewModel.addTracksToPlaylist(playlist.id, selected().map { it.toLegacyTrack() })
                addToPlaylist = false
                selection.clear()
            },
            onCreateNew = {
                addToPlaylist = false
                createPlaylist = true
            },
        )
    }
    if (createPlaylist) {
        CreatePlaylistDialog(
            onDismiss = { createPlaylist = false },
            onSubmit = { name, description ->
                playerViewModel.createPlaylist(name, description, selected().map { it.toLegacyTrack() })
                createPlaylist = false
                selection.clear()
            },
        )
    }

    val findAlbumArt = LocalFindAlbumArt.current
    val uploadToCloud = LocalUploadToCloud.current
    val party = LocalPartyAdder.current
    AnimatedVisibility(visible = selection.active, modifier = modifier) {
        val files = selected().mapNotNull { t ->
            val path = (t.source as? PlaybackSource.LocalFile)?.filePath ?: return@mapNotNull null
            ArtRequest(t.title, t.artistName, t.albumTitle, t.durationSeconds, path)
        }
        TrackSelectionBar(
            selectedCount = selection.count,
            onClose = { selection.clear() },
            onAddToQueue = {
                playerViewModel.addUnifiedToQueue(selected())
                selection.clear()
            },
            onAddToParty = party?.let { p ->
                {
                    p.unified(selected())
                    selection.clear()
                }
            },
            onAddToPlaylist = { addToPlaylist = true },
            onFindAlbumArt = findAlbumArt?.takeIf { files.isNotEmpty() }?.let { find ->
                {
                    find(files)
                    selection.clear()
                }
            },
            // Only the files on the phone go up; a server or catalogue song has none here.
            onUploadToCloud = uploadToCloud?.takeIf { files.isNotEmpty() }?.let { upload ->
                {
                    upload(files.map { it.filePath })
                    selection.clear()
                }
            },
        )
    }
}

/** The leading circle of a row in selection mode, ticked when [selected], as in [TrackItem]. */
@Composable
fun SelectionCheck(selected: Boolean) {
    Icon(
        imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
        contentDescription = stringResource(if (selected) R.string.state_selected else R.string.state_not_selected),
        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.width(MonoDimens.spacingMd))
}
