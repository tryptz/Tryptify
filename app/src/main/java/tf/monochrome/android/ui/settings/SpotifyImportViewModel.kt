package tf.monochrome.android.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.SpotifyApiClient
import tf.monochrome.android.data.api.SpotifySimplePlaylist
import tf.monochrome.android.data.auth.SpotifyAuthManager
import tf.monochrome.android.data.import_.ImportProgress
import tf.monochrome.android.data.import_.PlaylistImportService
import tf.monochrome.android.data.import_.SpotifyImportForegroundService
import javax.inject.Inject

/**
 * Drives the "Playlist Import" section in Settings → System: Spotify
 * connect/disconnect, the my-playlists picker, and URL/Liked-Songs imports.
 * Kept separate from the already-large SettingsViewModel.
 *
 * Imports themselves run in [SpotifyImportForegroundService] (persistent
 * progress notification, survives leaving the screen); this ViewModel only
 * starts the service and mirrors the shared progress flow for in-screen UI.
 */
@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    private val spotifyAuthManager: SpotifyAuthManager,
    private val spotifyApiClient: SpotifyApiClient,
    private val playlistImportService: PlaylistImportService,
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

    // Derived from the shared progress flow (not a local flag) so a
    // recreated ViewModel — the user left Settings and came back mid-import —
    // still shows the running import and keeps the buttons disabled.
    val isImporting: StateFlow<Boolean> = playlistImportService.progress
        .map { it is ImportProgress.Fetching || it is ImportProgress.Matching }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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

    fun importByUrl(context: Context, url: String, strictAlbumMatch: Boolean = false, onResult: (Boolean, String) -> Unit) {
        runImport(onResult) { SpotifyImportForegroundService.importUrl(context, url, strictAlbumMatch) }
    }

    fun importPlaylist(context: Context, playlistId: String, name: String, strictAlbumMatch: Boolean = false, onResult: (Boolean, String) -> Unit) {
        runImport(onResult) {
            SpotifyImportForegroundService.importPlaylist(context, playlistId, name, strictAlbumMatch)
        }
    }

    fun importLikedSongs(context: Context, strictAlbumMatch: Boolean = false, onResult: (Boolean, String) -> Unit) {
        runImport(onResult) { SpotifyImportForegroundService.importLikedSongs(context, strictAlbumMatch) }
    }

    /**
     * Kicks off the foreground service and relays its terminal state back to
     * the caller's snackbar while this screen is still around. Progress is
     * reset first so the wait below can't instantly fire on a previous
     * import's Done/Failed. If the user leaves before the import finishes,
     * this collector dies with the ViewModel — the notification still
     * carries the result.
     */
    private fun runImport(onResult: (Boolean, String) -> Unit, start: () -> Unit) {
        if (isImporting.value) return
        playlistImportService.resetProgress()
        start()
        viewModelScope.launch {
            val terminal = playlistImportService.progress.first {
                it is ImportProgress.Done || it is ImportProgress.Failed
            }
            when (terminal) {
                is ImportProgress.Done ->
                    onResult(true, "Imported ${terminal.matched}/${terminal.total} tracks into '${terminal.playlistName}'")
                is ImportProgress.Failed -> onResult(false, terminal.message)
                else -> Unit
            }
        }
    }
}
