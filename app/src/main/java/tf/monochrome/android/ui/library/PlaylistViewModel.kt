package tf.monochrome.android.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.data.playlistfix.PlaylistFixState
import tf.monochrome.android.data.playlistfix.PlaylistFixer
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.Playlist
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val musicRepository: MusicRepository,
    private val playlistFixer: PlaylistFixer,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    /**
     * This playlist's repair or regeneration, running or just finished; Idle
     * when there is none. One job runs at a time across the app, and it
     * outlives this screen.
     */
    val fixState: StateFlow<PlaylistFixState> = playlistFixer.state
        .map { if (it.playlistId == playlistId) it else PlaylistFixState.Idle }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PlaylistFixState.Idle)

    /** Another playlist's job is running, so this one can't start yet. */
    val fixBusyElsewhere: StateFlow<Boolean> = playlistFixer.state
        .map { it is PlaylistFixState.Running && it.playlistId != playlistId }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Catalogues with a server under Settings › Connections, for the repair and regenerate dialogs. */
    private val _fixServices = MutableStateFlow<List<ApiService>>(emptyList())
    val fixServices: StateFlow<List<ApiService>> = _fixServices.asStateFlow()

    /** Re-reads which catalogues are connected; called as a fix dialog opens. */
    fun refreshFixServices() {
        viewModelScope.launch { _fixServices.value = playlistFixer.availableServices() }
    }

    fun repairPlaylist() {
        playlistFixer.repair(playlistId)
    }

    fun regeneratePlaylist(primary: ApiService, fallback: ApiService?) {
        playlistFixer.regenerate(playlistId, primary, fallback)
    }

    fun stopFix() = playlistFixer.stop()

    fun dismissFixResult() = playlistFixer.dismiss(playlistId)

    private val _playlistInfo = MutableStateFlow<UserPlaylistEntity?>(null)
    val playlistInfo: StateFlow<UserPlaylistEntity?> = _playlistInfo.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    /**
     * A TIDAL playlist opened from search: one of the catalogue's, not the
     * listener's. The route is shared with the listener's own playlists and
     * both have UUID ids, so an id that is not a local playlist is fetched
     * from TIDAL and shown read-only. It used to be looked up in the local
     * database only, where it never is, and the screen stayed empty.
     */
    private val _catalogPlaylist = MutableStateFlow<Playlist?>(null)
    val catalogPlaylist: StateFlow<Playlist?> = _catalogPlaylist.asStateFlow()

    private val _catalogLoading = MutableStateFlow(false)
    val catalogLoading: StateFlow<Boolean> = _catalogLoading.asStateFlow()

    private val _catalogFailed = MutableStateFlow(false)
    val catalogFailed: StateFlow<Boolean> = _catalogFailed.asStateFlow()

    init {
        // Ready before a fix dialog opens, which refreshes it again.
        refreshFixServices()

        viewModelScope.launch {
            libraryRepository.getAllPlaylists().collectLatest { playlists ->
                _playlistInfo.value = playlists.find { it.id == playlistId }
            }
        }

        // The local playlist's tracks while there is one; the catalogue
        // playlist's only when no local playlist has this id, so a playlist
        // just created here can never be read as TIDAL's.
        viewModelScope.launch {
            combine(
                libraryRepository.getPlaylistTracks(playlistId),
                _playlistInfo,
                _catalogPlaylist,
            ) { local, info, catalog ->
                if (info == null && catalog != null) catalog.tracks else local
            }.collectLatest { _tracks.value = it }
        }

        viewModelScope.launch {
            val isLocal = libraryRepository.getAllPlaylists().first().any { it.id == playlistId }
            if (!isLocal) loadCatalogPlaylist()
        }
    }

    /** Fetches the catalogue playlist; also what Retry calls. */
    fun loadCatalogPlaylist() {
        viewModelScope.launch {
            _catalogLoading.value = true
            _catalogFailed.value = false
            musicRepository.getPlaylist(playlistId)
                .onSuccess { _catalogPlaylist.value = it }
                .onFailure { _catalogFailed.value = true }
            _catalogLoading.value = false
        }
    }
    
    fun removeTrack(trackId: Long) {
        viewModelScope.launch {
            libraryRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun removeTracks(trackIds: Collection<Long>) {
        viewModelScope.launch {
            trackIds.forEach { libraryRepository.removeTrackFromPlaylist(playlistId, it) }
        }
    }
    
    fun deletePlaylist() {
        viewModelScope.launch {
            // The screen pops the back stack right after calling this, which
            // clears the ViewModel and cancels viewModelScope. Run the delete in
            // a NonCancellable block so the DB write isn't aborted mid-flight
            // and the playlist can't come back on the next launch.
            withContext(NonCancellable) {
                libraryRepository.deletePlaylist(playlistId)
            }
        }
    }
    
    fun updatePlaylist(name: String, description: String) {
        viewModelScope.launch {
            // Preserve the current visibility — updatePlaylist's isPublic
            // param defaults to false, so omitting it silently made public
            // playlists private on every edit.
            libraryRepository.updatePlaylist(
                playlistId,
                name,
                description,
                isPublic = _playlistInfo.value?.isPublic ?: false
            )
        }
    }

    fun togglePublic() {
        val current = _playlistInfo.value ?: return
        viewModelScope.launch {
            libraryRepository.updatePlaylist(
                playlistId = playlistId,
                name = current.name,
                description = current.description,
                isPublic = !current.isPublic
            )
        }
    }
}
