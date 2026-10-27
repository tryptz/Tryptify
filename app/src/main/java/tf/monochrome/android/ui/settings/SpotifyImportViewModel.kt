package tf.monochrome.android.ui.settings

import tf.monochrome.android.R
import tf.monochrome.android.ui.components.UiText
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.import_.ImportProgress
import tf.monochrome.android.data.import_.PlaylistImportService
import tf.monochrome.android.data.import_.SpotifyImportForegroundService
import javax.inject.Inject

/**
 * Drives the Playlist Import section in Settings › Library: a pasted Spotify
 * link, read from the public embed with no account. Kept separate from the
 * already-large SettingsViewModel.
 *
 * Imports themselves run in [SpotifyImportForegroundService] (persistent
 * progress notification, survives leaving the screen); this ViewModel only
 * starts the service and mirrors the shared progress flow for in-screen UI.
 */
@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    private val playlistImportService: PlaylistImportService,
) : ViewModel() {

    val importProgress: StateFlow<ImportProgress> = playlistImportService.progress

    // Derived from the shared progress flow (not a local flag) so a
    // recreated ViewModel — the user left Settings and came back mid-import —
    // still shows the running import and keeps the buttons disabled.
    val isImporting: StateFlow<Boolean> = playlistImportService.progress
        .map { it is ImportProgress.Fetching || it is ImportProgress.Matching }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** [name] is the playlist's own, from the link's preview, when it was found. */
    fun importByUrl(context: Context, url: String, strictAlbumMatch: Boolean = false, name: String? = null, onResult: (Boolean, UiText) -> Unit) {
        runImport(onResult) { SpotifyImportForegroundService.importUrl(context, url, strictAlbumMatch, name) }
    }

    /**
     * Kicks off the foreground service and relays its terminal state back to
     * the caller's snackbar while this screen is still around. Progress is
     * reset first so the wait below can't instantly fire on a previous
     * import's Done/Failed. If the user leaves before the import finishes,
     * this collector dies with the ViewModel — the notification still
     * carries the result.
     */
    private fun runImport(onResult: (Boolean, UiText) -> Unit, start: () -> Unit) {
        if (isImporting.value) return
        playlistImportService.resetProgress()
        start()
        viewModelScope.launch {
            val terminal = playlistImportService.progress.first {
                it is ImportProgress.Done || it is ImportProgress.Failed
            }
            when (terminal) {
                is ImportProgress.Done ->
                    onResult(true, UiText.Plural(R.plurals.spotify_import_done, terminal.total, listOf(terminal.matched, terminal.total, terminal.playlistName)))
                is ImportProgress.Failed -> onResult(false, UiText.Raw(terminal.message))
                else -> Unit
            }
        }
    }
}
