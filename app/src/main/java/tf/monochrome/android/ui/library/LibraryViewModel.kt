package tf.monochrome.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
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
            val parsedPlaylist = csvPlaylistParser.parseFromUri(uri).getOrNull()
            if (parsedPlaylist == null) {
                playlistImportService.reportFailure("Could not parse the CSV file")
                return@launch
            }
            playlistImportService.importTracks(name, description, parsedPlaylist.tracks, strictAlbumMatch)
        }
    }

    val favoriteTracks: StateFlow<List<Track>> = libraryRepository.getFavoriteTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val recentTracks: StateFlow<List<Track>> = libraryRepository.getHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteAlbums: StateFlow<List<Album>> = libraryRepository.getFavoriteAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val favoriteArtists: StateFlow<List<Artist>> = libraryRepository.getFavoriteArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val playlists: StateFlow<List<UserPlaylistEntity>> = libraryRepository.getAllPlaylists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
