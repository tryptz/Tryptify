package tf.monochrome.android.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.SpotifyAlbumFull
import tf.monochrome.android.data.api.SpotifyApiClient
import tf.monochrome.android.domain.usecase.toSpotifyAlbumTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Spotify album detail, the twin of the Qobuz branch in
 * [AlbumDetailViewModel]: fetches /v1/albums/{id} and maps its tracks into
 * playable UnifiedTracks (PlaybackSource.SpotifyRemote). The route carries
 * the base62 Spotify album id as a string — it doesn't fit the Long-based
 * TIDAL/Qobuz album routes.
 */
@HiltViewModel
class SpotifyAlbumDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val spotifyApiClient: tf.monochrome.android.data.api.SpotifyApiClient,
) : ViewModel() {

    private val albumId: String = savedStateHandle.get<String>("spotifyAlbumId").orEmpty()

    private val _album = MutableStateFlow<SpotifyAlbumFull?>(null)
    val album: StateFlow<SpotifyAlbumFull?> = _album.asStateFlow()

    private val _tracks = MutableStateFlow<List<tf.monochrome.android.domain.model.UnifiedTrack>>(emptyList())
    val tracks: StateFlow<List<tf.monochrome.android.domain.model.UnifiedTrack>> = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        loadAlbum()
    }

    private fun loadAlbum() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            spotifyApiClient.getAlbum(albumId)
                .onSuccess { full ->
                    _album.value = full
                    _tracks.value = full.tracks?.items.orEmpty()
                        .mapNotNull { it.toSpotifyAlbumTrack(full) }
                }
                .onFailure { _error.value = it.message ?: "Failed to load album" }
            _isLoading.value = false
        }
    }

    fun retry() = loadAlbum()
}
