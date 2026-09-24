package tf.monochrome.android.ui.detail

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.SpotifyApiClient
import tf.monochrome.android.data.spotify.SpotifyNativeSession
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.usecase.toSpotifyTrack
import tf.monochrome.android.domain.usecase.toSpotifyUnifiedTrack
import javax.inject.Inject

/**
 * Any Spotify playlist, public ones included. The tracks come from librespot
 * ([SpotifyNativeSession.getPlaylist]), because the Web API only lists the
 * tracks of playlists the user owns or collaborates on to a Development-mode
 * app. When librespot can't sign in, the Web API is tried anyway — that still
 * works for the user's own playlists — and its error is shown only if both
 * fail. The header (name, owner, cover) comes from the Web API, which does
 * serve it for public playlists, and falls back to what librespot read.
 */
@HiltViewModel
class SpotifyPlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val spotifyApiClient: SpotifyApiClient,
    private val nativeSession: SpotifyNativeSession,
) : ViewModel() {

    private val playlistId: String = savedStateHandle.get<String>("spotifyPlaylistId").orEmpty()

    data class Header(val name: String, val owner: String?, val description: String?, val coverUrl: String?)

    private val _header = MutableStateFlow<Header?>(null)
    val header: StateFlow<Header?> = _header.asStateFlow()

    private val _tracks = MutableStateFlow<List<UnifiedTrack>>(emptyList())
    val tracks: StateFlow<List<UnifiedTrack>> = _tracks.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            val webHeader = async { spotifyApiClient.getPlaylistHeader(playlistId).getOrNull() }
            val native = runCatching { nativeSession.getPlaylist(playlistId) }
                .onFailure { Log.w(TAG, "librespot couldn't read playlist $playlistId", it) }

            val tracks = native.map { playlist -> playlist.tracks.map { it.toSpotifyTrack() } }
                .recoverCatching { nativeError ->
                    spotifyApiClient.getPlaylistTrackObjects(playlistId).getOrElse { webError ->
                        Log.w(TAG, "Web API couldn't read playlist $playlistId either", webError)
                        throw Exception(
                            "Couldn't load this playlist. In-app Spotify: ${nativeError.message}. " +
                                "Check Settings → Connections → Native Spotify playback.",
                        )
                    }
                }

            val web = webHeader.await()
            val fromLibrespot = native.getOrNull()
            _header.value = Header(
                name = web?.name?.takeIf { it.isNotBlank() } ?: fromLibrespot?.name ?: "Playlist",
                owner = web?.owner?.displayName ?: fromLibrespot?.owner,
                description = web?.description?.takeIf { it.isNotBlank() } ?: fromLibrespot?.description,
                coverUrl = web?.images?.firstOrNull()?.url?.takeIf { it.isNotBlank() },
            )
            tracks
                .onSuccess { list -> _tracks.value = list.mapNotNull { it.toSpotifyUnifiedTrack() }.distinctBy { it.id } }
                .onFailure { _error.value = it.message ?: "Failed to load playlist" }
            _isLoading.value = false
        }
    }

    fun retry() = load()

    private companion object {
        const val TAG = "SpotifyPlaylist"
    }
}
