package tf.monochrome.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.db.entity.UserPlaylistEntity
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.data.import_.CsvPlaylistParser
import tf.monochrome.android.data.import_.ImportProgress
import tf.monochrome.android.data.import_.PlaylistImportService
import android.net.Uri
import javax.inject.Inject


@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val csvPlaylistParser: CsvPlaylistParser,
    private val playlistImportService: PlaylistImportService
) : ViewModel() {

    val importProgress: StateFlow<ImportProgress> = playlistImportService.progress

    fun createPlaylist(name: String, description: String? = null) {
        viewModelScope.launch {
            libraryRepository.createPlaylist(name, description)
        }
    }

    fun resetImportProgress() { playlistImportService.resetProgress() }

    fun importCsvPlaylist(uri: Uri, strictAlbumMatch: Boolean, name: String, description: String?) {
        viewModelScope.launch {
            playlistImportService.reportFetching("CSV")
            // The parser's own message says which column was missing or which
            // export to take instead. Flattening it to "could not parse the CSV
            // file" is why a wrong-format file and a corrupt one were
            // indistinguishable, and neither told anyone what to do next.
            val parsed = csvPlaylistParser.parseFromUri(uri)
            val parsedPlaylist = parsed.getOrNull()
            if (parsedPlaylist == null) {
                // Blank when the parser had nothing specific to say; the screen
                // then shows its own, translated, explanation.
                playlistImportService.reportFailure(parsed.exceptionOrNull()?.message.orEmpty())
                return@launch
            }
            playlistImportService.importTracks(name, description, parsedPlaylist.tracks, strictAlbumMatch)
        }
    }

    /**
     * A playlist, or every playlist, exported with Exportify inside the app
     * (ExportifyScreen): each CSV in it is matched into a playlist of its own,
     * one after another, as a picked CSV file is.
     */
    /** Runs in the import service, so leaving this screen doesn't stop it (see importExportify there). */
    fun importExportify(message: tf.monochrome.android.data.import_.ExportifyFiles.Message, strictAlbumMatch: Boolean) {
        playlistImportService.importExportify(message, strictAlbumMatch)
    }

    val favoriteTracks: StateFlow<List<Track>> = libraryRepository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Bumped by a pull on the page: the history query starts over. */
    private val historyReloads = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentTracks: StateFlow<List<Track>> = historyReloads
        .flatMapLatest { libraryRepository.getHistory() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _historyRefreshing = MutableStateFlow(false)
    val historyRefreshing: StateFlow<Boolean> = _historyRefreshing.asStateFlow()

    /**
     * Pull to refresh on the Library page: reads Recently Played again. It is
     * the phone's own history, and live — a play shows as it is written — so
     * this is the gesture's honest answer, held a moment so the spinner is
     * seen rather than flickered.
     */
    fun refreshHistory() {
        if (_historyRefreshing.value) return
        _historyRefreshing.value = true
        historyReloads.value++
        viewModelScope.launch {
            delay(REFRESH_SPINNER_MS)
            _historyRefreshing.value = false
        }
    }

    private companion object {
        const val REFRESH_SPINNER_MS = 450L
    }

    val favoriteAlbums: StateFlow<List<Album>> = libraryRepository.getFavoriteAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteArtists: StateFlow<List<Artist>> = libraryRepository.getFavoriteArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<UserPlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
