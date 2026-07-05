package tf.monochrome.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.SpotifyApiClient
import tf.monochrome.android.data.api.SpotifySimplePlaylist
import tf.monochrome.android.data.auth.SpotifyAuthManager
import tf.monochrome.android.data.import_.ImportProgress
import tf.monochrome.android.data.import_.PlaylistImportService
import tf.monochrome.android.data.import_.PlaylistImporter
import javax.inject.Inject

/**
 * Drives the "Playlist Import" section in Settings → System: Spotify
 * connect/disconnect, the my-playlists picker, and URL/Liked-Songs imports.
 * Kept separate from the already-large SettingsViewModel.
 */
@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    private val spotifyAuthManager: SpotifyAuthManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val playlistImporter: PlaylistImporter,
    playlistImportService: PlaylistImportService,
) : ViewModel() {

    val isConnected: StateFlow<Boolean> = spotifyAuthManager.isConnected
    val userName: StateFlow<String?> = spotifyAuthManager.connectedUserName
    val isConnecting: StateFlow<Boolean> = spotifyAuthManager.isConnecting
    val authError: StateFlow<String?> = spotifyAuthManager.errorMessage
    val importProgress: StateFlow<ImportProgress> = playlistImportService.progress

    private val _myPlaylists = MutableStateFlow<List<SpotifySimplePlaylist>>(emptyList())
    val myPlaylists: StateFlow<List<SpotifySimplePlaylist>> = _myPlaylists.asStateFlow()

    private val _playlistsLoading = MutableStateFlow(false)
    val playlistsLoading: StateFlow<Boolean> = _playlistsLoading.asStateFlow()

    private val _playlistsError = MutableStateFlow<String?>(null)
    val playlistsError: StateFlow<String?> = _playlistsError.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    init {
        // After the OAuth callback lands we only have tokens — fill in the
        // "Connected as ..." display name (also heals installs that
        // connected before a name was stored).
        viewModelScope.launch {
            spotifyAuthManager.isConnected.collect { connected ->
                if (connected && userName.value.isNullOrBlank()) {
                    spotifyApiClient.getCurrentUser().onSuccess { profile ->
                        spotifyAuthManager.setConnectedUserName(
                            profile.displayName?.takeIf { it.isNotBlank() } ?: profile.id,
                        )
                    }
                }
            }
        }
    }

    fun connect(context: Context) = spotifyAuthManager.connect(context)

    fun disconnect() {
        viewModelScope.launch {
            spotifyAuthManager.disconnect()
            _myPlaylists.value = emptyList()
        }
    }

    fun clearAuthError() = spotifyAuthManager.clearError()

    fun loadMyPlaylists() {
        viewModelScope.launch {
            _playlistsLoading.value = true
            _playlistsError.value = null
            spotifyApiClient.getMyPlaylists()
                .onSuccess { _myPlaylists.value = it }
                .onFailure { _playlistsError.value = it.message }
            _playlistsLoading.value = false
        }
    }

    fun importByUrl(url: String, strictAlbumMatch: Boolean = false, onResult: (Boolean, String) -> Unit) {
        runImport(onResult) { playlistImporter.importFromUrl(url, strictAlbumMatch) }
    }

    fun importPlaylist(playlistId: String, name: String, strictAlbumMatch: Boolean = false, onResult: (Boolean, String) -> Unit) {
        runImport(onResult) { playlistImporter.importSpotifyPlaylist(playlistId, name, strictAlbumMatch) }
    }

    fun importLikedSongs(strictAlbumMatch: Boolean = false, onResult: (Boolean, String) -> Unit) {
        runImport(onResult) { playlistImporter.importSpotifyLikedSongs(strictAlbumMatch) }
    }

    private fun runImport(
        onResult: (Boolean, String) -> Unit,
        import: suspend () -> Result<ImportProgress.Done>,
    ) {
        if (_isImporting.value) return
        viewModelScope.launch {
            _isImporting.value = true
            import()
                .onSuccess { done ->
                    onResult(true, "Imported ${done.matched}/${done.total} tracks into '${done.playlistName}'")
                }
                .onFailure { onResult(false, it.message ?: "Import failed") }
            _isImporting.value = false
        }
    }
}
