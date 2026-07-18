package tf.monochrome.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.collections.db.CollectionEntity
import tf.monochrome.android.data.collections.repository.CollectionRepository
import tf.monochrome.android.data.local.db.LocalFolderEntity
import tf.monochrome.android.data.local.db.LocalGenreEntity
import tf.monochrome.android.data.local.repository.LocalMediaRepository
import tf.monochrome.android.data.local.scanner.ScanCoordinator
import tf.monochrome.android.data.local.scanner.ScanProgress
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.usecase.ImportCollectionUseCase
import tf.monochrome.android.data.sync.BackupManager
import javax.inject.Inject

@HiltViewModel
class LocalLibraryViewModel @Inject constructor(
    private val localMediaRepository: LocalMediaRepository,
    private val collectionRepository: CollectionRepository,
    private val scanCoordinator: ScanCoordinator,
    private val importCollectionUseCase: ImportCollectionUseCase,
    private val backupManager: BackupManager,
    private val preferencesManager: PreferencesManager
) : ViewModel() {

    // ── Local media ─────────────────────────────────────────────────

    val localTracks: StateFlow<List<UnifiedTrack>> = localMediaRepository.getAllTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localAlbums: StateFlow<List<UnifiedAlbum>> = localMediaRepository.getAllAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localArtists: StateFlow<List<UnifiedArtist>> = localMediaRepository.getAllArtists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Sorting ─────────────────────────────────────────────────────
    // Per-tab sort selection; the displayed lists below re-sort whenever either
    // the data or the chosen order changes. Defaults to A→Z by name.

    private val _songSort = MutableStateFlow(LibrarySort(LibrarySortKey.NAME))
    val songSort: StateFlow<LibrarySort> = _songSort.asStateFlow()

    private val _albumSort = MutableStateFlow(LibrarySort(LibrarySortKey.NAME))
    val albumSort: StateFlow<LibrarySort> = _albumSort.asStateFlow()

    private val _artistSort = MutableStateFlow(LibrarySort(LibrarySortKey.NAME))
    val artistSort: StateFlow<LibrarySort> = _artistSort.asStateFlow()

    init {
        // Restore persisted sort selections so they survive process death and
        // app restarts instead of snapping back to Name / A→Z.
        viewModelScope.launch {
            preferencesManager.songSort.collect { _songSort.value = parseSort(it) }
        }
        viewModelScope.launch {
            preferencesManager.albumSort.collect { _albumSort.value = parseSort(it) }
        }
        viewModelScope.launch {
            preferencesManager.artistSort.collect { _artistSort.value = parseSort(it) }
        }
    }

    fun setSongSort(sort: LibrarySort) {
        _songSort.value = sort
        viewModelScope.launch { preferencesManager.setSongSort(sort.serialize()) }
    }
    fun setAlbumSort(sort: LibrarySort) {
        _albumSort.value = sort
        viewModelScope.launch { preferencesManager.setAlbumSort(sort.serialize()) }
    }
    fun setArtistSort(sort: LibrarySort) {
        _artistSort.value = sort
        viewModelScope.launch { preferencesManager.setArtistSort(sort.serialize()) }
    }

    private fun LibrarySort.serialize(): String = "${key.name}:${if (ascending) "asc" else "desc"}"

    private fun parseSort(raw: String?): LibrarySort {
        val default = LibrarySort(LibrarySortKey.NAME)
        if (raw.isNullOrBlank()) return default
        val parts = raw.split(":")
        val key = LibrarySortKey.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return default
        return LibrarySort(key, ascending = parts.getOrNull(1) != "desc")
    }

    val sortedTracks: StateFlow<List<UnifiedTrack>> = combine(localTracks, _songSort) { tracks, sort ->
        tracks.applySort(sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sortedAlbums: StateFlow<List<UnifiedAlbum>> = combine(localAlbums, _albumSort) { albums, sort ->
        albums.applySort(sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sortedArtists: StateFlow<List<UnifiedArtist>> = combine(localArtists, _artistSort) { artists, sort ->
        artists.applySort(sort)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val localGenres: StateFlow<List<LocalGenreEntity>> = localMediaRepository.getAllGenres()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val rootFolders: StateFlow<List<LocalFolderEntity>> = localMediaRepository.getRootFolders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** User-added folder roots merged with scanner-derived folders. User roots come first. */
    val displayRootFolders: StateFlow<List<Pair<String, String>>> = combine(
        localMediaRepository.getRootFolders(),
        preferencesManager.userFolderRoots
    ) { dbFolders, userPaths ->
        val user = userPaths.map { path ->
            val name = path.substringAfterLast('/').ifBlank { path }
            name to path
        }
        val db = dbFolders
            .filter { it.path !in userPaths }
            .map { it.displayName to it.path }
        user + db
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addUserFolderRoot(path: String) {
        if (path.isBlank()) return
        viewModelScope.launch {
            preferencesManager.addUserFolderRoot(path)
        }
    }

    // ── Search ────────────────────────────────────────────────────────

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<UnifiedTrack>> = _searchQuery
        .debounce(300)
        .flatMapLatest { query ->
            if (query.isBlank()) flowOf(emptyList())
            else localMediaRepository.searchTracks(query)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    // ── Collections ─────────────────────────────────────────────────

    val collections: StateFlow<List<CollectionEntity>> = collectionRepository.getAllCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── Scan state ──────────────────────────────────────────────────

    // Shared across every scan entry point (Library tab, FileObserver,
    // onboarding ScanWorker) so worker-driven scans show progress here too.
    val scanProgress: StateFlow<ScanProgress?> = scanCoordinator.scanProgress
    val isScanning: StateFlow<Boolean> = scanCoordinator.isScanning

    fun startFullScan() {
        viewModelScope.launch { scanCoordinator.runFullScan() }
    }

    fun startIncrementalScan() {
        viewModelScope.launch { scanCoordinator.runIncrementalScan() }
    }

    /** Dismiss the terminal scan-progress bar (Complete/Error). */
    fun clearScanProgress() { scanCoordinator.clearProgress() }

    // ── Collection import ───────────────────────────────────────────

    private val _importResult = MutableStateFlow<Result<String>?>(null)
    val importResult: StateFlow<Result<String>?> = _importResult.asStateFlow()

    fun importCollection(manifestJson: String) {
        viewModelScope.launch {
            if (manifestJson.contains("\"favoriteTracks\"") || manifestJson.contains("\"favorites_tracks\"") || manifestJson.contains("\"playlists\"")) {
                val result = backupManager.importLibrary(manifestJson)
                if (result.isSuccess) {
                    _importResult.value = Result.success("Library Backup imported successfully")
                } else {
                    _importResult.value = Result.failure(result.exceptionOrNull() ?: Exception("Unknown backup import error"))
                }
            } else {
                _importResult.value = importCollectionUseCase.import(manifestJson)
            }
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch {
            importCollectionUseCase.delete(collectionId)
        }
    }

    fun clearImportResult() {
        _importResult.value = null
    }

    // ── Folder browsing ─────────────────────────────────────────────

    fun getSubfolders(parentPath: String): StateFlow<List<LocalFolderEntity>> =
        localMediaRepository.getSubfolders(parentPath)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getTracksInFolder(folderPath: String): StateFlow<List<UnifiedTrack>> =
        localMediaRepository.getTracksInFolder(folderPath)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getTracksByAlbum(albumId: Long): StateFlow<List<UnifiedTrack>> =
        localMediaRepository.getTracksByAlbum(albumId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getTracksByArtist(artistId: Long): StateFlow<List<UnifiedTrack>> =
        localMediaRepository.getTracksByArtist(artistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun getTracksByGenre(genre: String): StateFlow<List<UnifiedTrack>> =
        localMediaRepository.getTracksByGenre(genre)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
