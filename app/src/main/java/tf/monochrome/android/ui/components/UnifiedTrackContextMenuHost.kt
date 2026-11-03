package tf.monochrome.android.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.navigation.isNavigableAlbumId
import tf.monochrome.android.ui.navigation.openAlbum
import tf.monochrome.android.ui.navigation.openArtist
import tf.monochrome.android.ui.player.PlayerViewModel
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

/**
 * The ⋮ sheet for a [UnifiedTrack], with the add-to-playlist and
 * create-playlist follow-ups it opens.
 *
 * The local-library screens (folders, local album/artist/genre, downloads)
 * all deal in [UnifiedTrack] and each needed the same thirty lines of wiring
 * to show a context menu, so none of them had one. This is that wiring, once.
 *
 * Two things it gets right that a naive `toLegacyTrack()` + [TrackContextMenu]
 * would not:
 *
 * - **Navigation stays in the right namespace.** `toLegacyTrack()` synthesises
 *   album and artist ids from `hashCode()`, so routing off the legacy track
 *   would open a catalog screen for a garbage id. Album and artist go through
 *   [openAlbum]/[openArtist] on the *unified* track instead, which route local
 *   ids to the local screens.
 * - **Download is hidden for files already on disk**, rather than offered and
 *   silently dropped.
 *
 * Call it unconditionally and drive it with a nullable [track]; it holds the
 * follow-up sheets' state, so it must stay in the composition after [track]
 * goes null:
 *
 * ```
 * var menuTrack by remember { mutableStateOf<UnifiedTrack?>(null) }
 * UnifiedTrackContextMenuHost(menuTrack, { menuTrack = null }, navController, playerViewModel)
 * ```
 *
 * @param onRemove when non-null, adds a destructive "Remove" entry.
 */
@Composable
fun UnifiedTrackContextMenuHost(
    track: UnifiedTrack?,
    onDismissRequest: () -> Unit,
    navController: NavController,
    playerViewModel: PlayerViewModel,
    onRemove: (() -> Unit)? = null,
    removeLabel: String = stringResource(R.string.action_remove_from_playlist),
    /** Starts multi-select on the song, where the list has it. */
    onSelect: (() -> Unit)? = null,
) {
    val favoriteTrackIds by playerViewModel.favoriteTrackIds.collectAsStateWithLifecycle()
    val playlists by playerViewModel.playlists.collectAsStateWithLifecycle()

    // Survives `track` going null: TrackContextMenu dismisses itself as it
    // fires the action, so the sheet these open would never appear if this
    // state lived on the caller's side of the null check.
    var addToPlaylistFor by remember { mutableStateOf<UnifiedTrack?>(null) }
    var createPlaylistFor by remember { mutableStateOf<UnifiedTrack?>(null) }

    if (track != null && addToPlaylistFor == null && createPlaylistFor == null) {
        val legacy = remember(track) { track.toLegacyTrack() }
        val isLocal = playerViewModel.isLocalUnified(track)
        val onOwnServer = track.sourceType == tf.monochrome.android.domain.model.SourceType.SUBSONIC
        TrackContextMenu(
            track = legacy,
            isLiked = favoriteTrackIds.contains(legacy.id),
            onDismiss = onDismissRequest,
            onPlayNext = { playerViewModel.playNextUnified(track) },
            onAddToQueue = { playerViewModel.addUnifiedToQueue(listOf(track)) },
            onToggleLike = { playerViewModel.toggleFavoriteUnified(track) },
            onAddToPlaylist = { addToPlaylistFor = track },
            onRemoveFromPlaylist = onRemove,
            removeLabel = removeLabel,
            // Already on disk — nothing to fetch. A song on the listener's own
            // server has no catalogue download: the downloader would look its
            // hashed id up on TIDAL. A YouTube or SoundCloud song downloads
            // through its own server, which needs it registered with its id.
            onDownloadTrack = if (isLocal || onOwnServer) null else ({ playerViewModel.downloadUnified(track) }),
            // Sharing a file fetches it from Qobuz by id, which for a server
            // song is a hash: it would share somebody else's song.
            onShareFile = if (onOwnServer) null else ({ playerViewModel.shareUnifiedTrack(track) }),
            // A YouTube or SoundCloud song links to itself. Nothing else here
            // gets one: a file or a server song has no catalog, and the legacy
            // track's album id is a hash no catalog loads.
            onShareLink = if (track.source is tf.monochrome.android.domain.model.PlaybackSource.Scxyt) {
                { playerViewModel.shareUnifiedLink(track) }
            } else null,
            onGoToAlbum = if (isNavigableAlbumId(track.albumId)) {
                { navController.openAlbum(track.albumId) }
            } else null,
            onGoToArtist = track.artistId?.let { artistId ->
                { navController.openArtist(track.sourceType, artistId) }
            },
            // A library file's own path: Track info reads its tags from it.
            filePath = (track.source as? tf.monochrome.android.domain.model.PlaybackSource.LocalFile)?.filePath,
            onSelect = onSelect,
        )
    }

    addToPlaylistFor?.let { pending ->
        AddToPlaylistSheet(
            playlists = playlists,
            onDismiss = { addToPlaylistFor = null },
            onPlaylistSelected = { playlist ->
                playerViewModel.registerUnified(listOf(pending))
                playerViewModel.addTrackToPlaylist(playlist.id, pending.toLegacyTrack())
                addToPlaylistFor = null
            },
            onCreateNew = {
                addToPlaylistFor = null
                createPlaylistFor = pending
            },
        )
    }

    createPlaylistFor?.let { pending ->
        CreatePlaylistDialog(
            onDismiss = { createPlaylistFor = null },
            onSubmit = { name, description ->
                playerViewModel.registerUnified(listOf(pending))
                playerViewModel.createPlaylist(name, description, listOf(pending.toLegacyTrack()))
                createPlaylistFor = null
            },
        )
    }
}
