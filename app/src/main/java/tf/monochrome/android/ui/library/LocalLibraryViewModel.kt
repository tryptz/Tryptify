package tf.monochrome.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import androidx.paging.cachedIn
import androidx.paging.PagingData
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
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

    // localTracks used to live here: the whole library as a StateFlow, rebuilt
    // on every database emission, held for the life of the screen. It is gone
    // rather than left unused, because an unread StateFlow with a live
    // subscriber still does all of that work — leaving it would have made the
    // paging below buy nothing at all. What used it now asks for a page
    // ([pagedTracks]), a count ([trackCount]), or a queue ([songQueue]).

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

    // `flowOn(Default)` on all three: `stateIn(viewModelScope, …)` collects on
    // Dispatchers.Main.immediate, so without it the combine body — an O(n log n)
    // sort of the entire library — ran on the UI thread, on every database
    // emission and every sort toggle. The repository below already does its
    // mapping on Default (LocalMediaRepository), but flowOn only covers what is
    // upstream of it, so everything these view models add landed back on Main.
    /**
     * The songs list, paged, re-pagered whenever the sort changes.
     *
     * `cachedIn` so the pages survive the sub-tab pager swiping this screen
     * away and back, and so a configuration change does not re-query the
     * database from row zero.
     */
    val pagedTracks: Flow<PagingData<UnifiedTrack>> = _songSort
        .flatMapLatest { sort -> localMediaRepository.pagedTracks(sort) }
        .cachedIn(viewModelScope)

    /**
     * How many tracks there are, for the empty state and the shuffle button.
     *
     * A COUNT, not `localTracks.isEmpty()`: asking the list whether it is empty
     * is what forced the whole library into memory to answer a yes/no.
     */
    val trackCount: StateFlow<Int> = localMediaRepository.countTracks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * The play queue for the songs list, in the order it is currently shown.
     *
     * Suspends: the query runs off the main thread when a row is tapped rather
     * than the library being held in memory for the life of the screen against
     * the chance that somebody presses play.
     */
    suspend fun songQueue(): List<UnifiedTrack> = localMediaRepository.tracksForQueue(_songSort.value)

    val sortedAlbums: StateFlow<List<UnifiedAlbum>> = combine(localAlbums, _albumSort) { albums, sort ->
        albums.applySort(sort)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val sortedArtists: StateFlow<List<UnifiedArtist>> = combine(localArtists, _artistSort) { artists, sort ->
        artists.applySort(sort)
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    /**
     * One [StateFlow] per path, for the life of this view model.
     *
     * These are read from a composable body, and `stateIn` builds a NEW
     * StateFlow and launches a NEW sharing coroutine every time it is called.
     * Called straight from composition that is once per recomposition: the
     * coroutines pile up in [viewModelScope] until the screen dies, the Room
     * query is re-issued each time, and because `collectAsStateWithLifecycle`
     * keys on flow identity it restarts collection, emits, and recomposes —
     * which calls the function again.
     *
     * Caching by key makes the call idempotent, so the trap is closed here
     * rather than left for each caller to remember. Main-thread only, which is
     * where composition reads it; bounded by the paths one browser screen
     * visits, and the whole map goes when the back stack entry does.
     */
    private val subfolderFlows = mutableMapOf<String, StateFlow<List<LocalFolderEntity>>>()
    private val folderTrackFlows = mutableMapOf<String, StateFlow<List<UnifiedTrack>>>()

    fun getSubfolders(parentPath: String): StateFlow<List<LocalFolderEntity>> =
        subfolderFlows.getOrPut(parentPath) {
            localMediaRepository.getSubfolders(parentPath)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        }

    fun getTracksInFolder(folderPath: String): StateFlow<List<UnifiedTrack>> =
        folderTrackFlows.getOrPut(folderPath) {
            localMediaRepository.getTracksInFolder(folderPath)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        }

    // getTracksByAlbum / getTracksByArtist / getTracksByGenre used to sit here
    // with the same per-call `stateIn`. Nothing called them: every local detail
    // screen has its own view model that holds the flow as a property
    // (LocalAlbumDetailViewModel.tracks, LocalArtistDetailViewModel,
    // LocalGenreDetailViewModel), which is the shape that does not have the
    // bug. They were three more copies of the trap with no users, so they are
    // gone rather than fixed.
}
