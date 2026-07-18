package tf.monochrome.android.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.Track
import javax.inject.Inject

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val playlistId: String = checkNotNull(savedStateHandle["playlistId"])

    private val _playlistInfo = MutableStateFlow<UserPlaylistEntity?>(null)
    val playlistInfo: StateFlow<UserPlaylistEntity?> = _playlistInfo.asStateFlow()

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    init {
        viewModelScope.launch {
            libraryRepository.getAllPlaylists().collectLatest { playlists ->
                _playlistInfo.value = playlists.find { it.id == playlistId }
            }
        }
        
        viewModelScope.launch {
            libraryRepository.getPlaylistTracks(playlistId).collectLatest { tracks ->
                _tracks.value = tracks
            }
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
            libraryRepository.deletePlaylist(playlistId)
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
