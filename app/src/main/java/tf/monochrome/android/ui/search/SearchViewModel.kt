package tf.monochrome.android.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.GenreNode
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Playlist
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.usecase.SearchUnifiedLibraryUseCase
import tf.monochrome.android.domain.usecase.toDeezerUnifiedTrack
import tf.monochrome.android.domain.usecase.toQobuzUnifiedTrack
import tf.monochrome.android.domain.usecase.toUnifiedTrack

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val unifiedLibrarySearch: SearchUnifiedLibraryUseCase,
    private val preferences: PreferencesManager,
    private val genreGraph: GenreGraphRepository,
    libraryRepository: LibraryRepository,
) : ViewModel() {

    /**
     * Search relevance favors title matches first, then artist and album context,
     * and finally applies a small source boost so locally playable results edge out
     * remote ones when textual relevance is otherwise identical.
     */
    companion object {
        private const val SEARCH_DEBOUNCE_MS = 250L
        // Page size used by the initial query and every loadMore. End-of-results
        // is detected when fewer than PAGE_SIZE items come back (matching the
        // existing /album/ paging convention at HiFiApiClient.kt). 50 keeps the
        // round-trip count low while staying small enough to render smoothly.
        private const val PAGE_SIZE = 50
        // Hard ceiling on the Qobuz fan-out so the TIDAL flow can never be
        // blocked behind a slow or hung Qobuz instance (coroutineScope waits
        // for all children, so an unbounded child would freeze the whole
        // search). HiFiApiClient.searchQobuz already times out per
        // sub-request — this is a belt-and-suspenders ceiling.
        private const val QOBUZ_BUDGET_MS = 7_000L
        private const val SCORE_WEIGHT_PRIMARY = 4_000
        private const val SCORE_WEIGHT_SECONDARY = 1_800
        private const val SCORE_WEIGHT_TERTIARY = 1_200
        private const val SCORE_PENALTY_PREFIX_MATCH = 500
        private const val SCORE_PENALTY_TOKEN_PREFIX_MATCH = 1_000
        private const val SCORE_PENALTY_SUBSTRING_MATCH = 1_500
        private const val SOURCE_BOOST_LOCAL = 90
        private const val SOURCE_BOOST_COLLECTION = 70
        private const val SOURCE_BOOST_API = 50
    }

    enum class SearchTypeFilter(val label: String) {
        ALL("All"),
        TRACKS("Tracks"),
        ALBUMS("Albums"),
        ARTISTS("Artists"),
        PLAYLISTS("Playlists")
    }

    enum class SearchSourceFilter(val label: String, val sourceType: SourceType?) {
        ALL("All", null),
        TIDAL("TIDAL", SourceType.API),
        QOBUZ("Qobuz", SourceType.QOBUZ),
        DEEZER("Deezer", SourceType.DEEZER),
        LOCAL("Local", SourceType.LOCAL),
        COLLECTION("Collection", SourceType.COLLECTION)
    }

    /** What the UI prefetch trigger is asking for more of. */
    enum class SearchPageType { TRACKS, ALBUMS, ARTISTS, PLAYLISTS }

    // Per-type, per-source paging state. nextOffset advances by PAGE_SIZE on
    // every successful fetch; the per-source endReached flags stop the
    // ViewModel from issuing further requests once a backend has run out of
    // results. inFlight gates against the prefetch trigger firing twice while
    // a page is in-flight.
    private class PageState {
        var nextOffset: Int = 0
        var qobuzOffset: Int = 0
        var tidalEnd: Boolean = false
        var qobuzEnd: Boolean = false
        @Volatile var inFlight: Boolean = false
        fun reset() {
            nextOffset = 0; qobuzOffset = 0
            tidalEnd = false; qobuzEnd = false
            inFlight = false
        }
        fun done(): Boolean = tidalEnd && qobuzEnd
    }

    /** Which catalogue a fetched page came from, so tracks map to the right id space. */
    private enum class PageSource(val sourceType: SourceType) { TIDAL(SourceType.API), QOBUZ(SourceType.QOBUZ) }

    private val tracksPage = PageState()
    private val albumsPage = PageState()
    private val artistsPage = PageState()
    private val playlistsPage = PageState()

    private fun pageFor(type: SearchPageType): PageState = when (type) {
        SearchPageType.TRACKS -> tracksPage
        SearchPageType.ALBUMS -> albumsPage
        SearchPageType.ARTISTS -> artistsPage
        SearchPageType.PLAYLISTS -> playlistsPage
    }

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    private val _endReached = MutableStateFlow(false)
    val endReached: StateFlow<Boolean> = _endReached.asStateFlow()

    // True when every backend for the current query failed — the UI shows an
    // error state instead of the misleading "No results found".
    private val _searchError = MutableStateFlow(false)
    val searchError: StateFlow<Boolean> = _searchError.asStateFlow()

    // Bumped on every new/reset query. A loadMore job captures the generation it
    // was launched under and refuses to touch paging state or results once the
    // generation has moved on — so a page in flight from the previous query
    // can't append its results into (or falsely end) the new query's paging.
    private var searchGeneration = 0

    // One loadMore job per page type, so cancelling stale paging doesn't leave
    // the other types' jobs running (the old single-job var only tracked the
    // last one, letting survivors bleed results across queries).
    private val loadMoreJobs = mutableMapOf<SearchPageType, Job>()

    private fun resetPaging() {
        searchGeneration++
        tracksPage.reset(); albumsPage.reset(); artistsPage.reset(); playlistsPage.reset()
        _isLoadingMore.value = false
        _endReached.value = false
        loadMoreJobs.values.forEach { it.cancel() }
        loadMoreJobs.clear()
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _allTracks = MutableStateFlow<List<UnifiedTrack>>(emptyList())
    private val _allAlbums = MutableStateFlow<List<Album>>(emptyList())
    private val _allArtists = MutableStateFlow<List<Artist>>(emptyList())
    private val _allPlaylists = MutableStateFlow<List<Playlist>>(emptyList())

    // Which catalog each album / artist result came from. Tracks carry their
    // own sourceType; Album and Artist don't, and adding one to those models
    // would reach every screen and every persisted copy for the sake of this
    // one. Written before the matching list, so a filter never sees a result
    // it can't place. First writer wins, matching the distinctBy that keeps
    // the first of two results sharing an id.
    private val _albumSources = MutableStateFlow<Map<Long, SourceType>>(emptyMap())
    val albumSources: StateFlow<Map<Long, SourceType>> = _albumSources.asStateFlow()
    private val _artistSources = MutableStateFlow<Map<Long, SourceType>>(emptyMap())
    val artistSources: StateFlow<Map<Long, SourceType>> = _artistSources.asStateFlow()

    private fun tagAlbums(items: List<Album>, source: SourceType) {
        if (items.isEmpty()) return
        _albumSources.value = _albumSources.value + items
            .filterNot { it.id in _albumSources.value }
            .associate { it.id to source }
    }

    private fun tagArtists(items: List<Artist>, source: SourceType) {
        if (items.isEmpty()) return
        _artistSources.value = _artistSources.value + items
            .filterNot { it.id in _artistSources.value }
            .associate { it.id to source }
    }

    private val _selectedType = MutableStateFlow(SearchTypeFilter.ALL)
    val selectedType: StateFlow<SearchTypeFilter> = _selectedType.asStateFlow()

    private val _selectedSource = MutableStateFlow(SearchSourceFilter.ALL)
    val selectedSource: StateFlow<SearchSourceFilter> = _selectedSource.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val searchHistory: StateFlow<List<String>> = preferences.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The curated genre rows that used to fill the search empty state moved to
    // the Discover tab (DiscoveryFeedUseCase). They are not loaded here any
    // more: this ViewModel is created by HomeScreen for its search bar, so
    // keeping them would fire one Qobuz search per genre seed on every visit to
    // Home for rows nothing renders.

    // Ids with a downloaded copy on disk. A downloaded track is a local track:
    // StreamResolver already plays it from the file whichever catalog it came
    // from, so the Local filter includes it and its pill says Local.
    private val downloadedIds: StateFlow<Set<Long>> = libraryRepository.getDownloadedTracks()
        .map { downloads -> downloads.mapTo(HashSet()) { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val tracks: StateFlow<List<UnifiedTrack>> = combine(
        _allTracks,
        _selectedType,
        _selectedSource,
        downloadedIds,
    ) { trackResults, type, source, downloaded ->
        if (type != SearchTypeFilter.ALL && type != SearchTypeFilter.TRACKS) {
            emptyList()
        } else if (source == SearchSourceFilter.ALL) {
            trackResults
        } else {
            trackResults.filter { track ->
                val effective = if (track.legacyId in downloaded) SourceType.LOCAL else track.sourceType
                effective == source.sourceType
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<Album>> = combine(
        _allAlbums, _selectedType, _selectedSource, _albumSources,
    ) { albumResults, type, source, sources ->
        when {
            type != SearchTypeFilter.ALL && type != SearchTypeFilter.ALBUMS -> emptyList()
            source == SearchSourceFilter.ALL -> albumResults
            else -> albumResults.filter { sources[it.id] == source.sourceType }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<Artist>> = combine(
        _allArtists, _selectedType, _selectedSource, _artistSources,
    ) { artistResults, type, source, sources ->
        when {
            type != SearchTypeFilter.ALL && type != SearchTypeFilter.ARTISTS -> emptyList()
            source == SearchSourceFilter.ALL -> artistResults
            else -> artistResults.filter { sources[it.id] == source.sourceType }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Playlists only ever come from TIDAL.
    val playlists: StateFlow<List<Playlist>> = combine(
        _allPlaylists, _selectedType, _selectedSource,
    ) { playlistResults, type, source ->
        when {
            type != SearchTypeFilter.ALL && type != SearchTypeFilter.PLAYLISTS -> emptyList()
            source == SearchSourceFilter.ALL || source == SearchSourceFilter.TIDAL -> playlistResults
            else -> emptyList()
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // The source row sits under the type row for every type, now that albums
    // and artists filter by source too. It used to vanish on Albums, Artists
    // and Playlists, because only tracks knew where they came from.
    val showSourceFilter: StateFlow<Boolean> = query
        .map { it.isNotBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var searchJob: Job? = null

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        resetPaging()
        if (newQuery.isBlank()) {
            clearResults()
            return
        }
        // If there's nothing on screen yet, enter the searching state during the
        // debounce window so it shows a spinner instead of flashing "No results
        // found" before the request even starts. When results from the previous
        // query are still showing, leave them up while the user refines — they
        // aren't empty, so no flash — until performSearch swaps them.
        val hasResults = _allTracks.value.isNotEmpty() || _allAlbums.value.isNotEmpty() ||
            _allArtists.value.isNotEmpty() || _allPlaylists.value.isNotEmpty()
        if (!hasResults) _isSearching.value = true
        _searchError.value = false
        searchJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SEARCH_DEBOUNCE_MS)
            performSearch(newQuery.trim())
        }
    }

    fun submitSearch() {
        val currentQuery = _query.value.trim()
        if (currentQuery.isBlank()) {
            clearResults()
            return
        }
        searchJob?.cancel()
        resetPaging()
        searchJob = viewModelScope.launch {
            performSearch(currentQuery)
        }
    }

    fun selectHistoryQuery(historyQuery: String) {
        _query.value = historyQuery
        searchJob?.cancel()
        resetPaging()
        searchJob = viewModelScope.launch {
            performSearch(historyQuery)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            preferences.clearSearchHistory()
        }
    }

    fun setSelectedType(type: SearchTypeFilter) {
        _selectedType.value = type
    }

    fun setSelectedSource(source: SearchSourceFilter) {
        _selectedSource.value = source
    }

    /**
     * The genre the current query names, if it names one.
     *
     * Exposed so the results screen can say what it understood the query to be
     * — "dnb" silently becoming a drum & bass search is helpful; silently
     * becoming one with no explanation is confusing.
     */
    private val _resolvedGenre = MutableStateFlow<GenreNode?>(null)
    val resolvedGenre: StateFlow<GenreNode?> = _resolvedGenre.asStateFlow()

    private suspend fun performSearch(query: String) {
        _isSearching.value = true
        _searchError.value = false
        // "dnb" is not a string any catalogue has tagged anything with, but it
        // is unambiguously drum & bass. Resolve first and search the genre's
        // real name, keeping the raw text as a fallback for everything the
        // graph doesn't recognise — which is most queries, since most queries
        // are artists and titles.
        val genre = genreGraph.graph.resolve(query.trim())
        _resolvedGenre.value = genre
        val trimmedQuery = genre?.queries()?.firstOrNull() ?: query.trim()
        // TIDAL, Qobuz, and the local/collection library all run in parallel.
        // Qobuz failures (instance unset, network error, schema mismatch) are
        // swallowed so the existing TIDAL flow keeps working unchanged.
        var deezerSearch: tf.monochrome.android.domain.model.SearchResult? = null
        val (searchResult, qobuzResult, unifiedResultsResult) = coroutineScope {
            // Every catalog is asked; which ones answer is decided by the APIs
            // under Settings › Connections. One no API serves comes back empty
            // (or, for TIDAL, as a failure the branches below already treat as
            // "TIDAL is down"), so there is nothing to choose between here.
            val apiDeferred = async { runCatching { repository.search(trimmedQuery) } }
            val qobuzDeferred = async {
                withTimeoutOrNull(QOBUZ_BUDGET_MS) {
                    runCatching { repository.searchQobuz(trimmedQuery) }
                }
            }
            // Deezer runs on the same time budget as Qobuz, and fails soft.
            val deezerDeferred = async {
                withTimeoutOrNull(QOBUZ_BUDGET_MS) {
                    repository.searchDeezer(trimmedQuery).getOrNull()
                }
            }
            val libraryDeferred = async { runCatching { unifiedLibrarySearch.search(trimmedQuery).first() } }
            deezerSearch = deezerDeferred.await()
            Triple(apiDeferred.await(), qobuzDeferred.await(), libraryDeferred.await())
        }
        val unifiedResults = unifiedResultsResult.getOrNull()

        val qobuzAvailable = qobuzResult?.isSuccess == true
        val deezerTracks = deezerSearch?.tracks?.map { it.toDeezerUnifiedTrack() } ?: emptyList()
        val deezerAlbums = deezerSearch?.albums ?: emptyList()
        val deezerArtists = deezerSearch?.artists ?: emptyList()
        val deezerHasResults = deezerTracks.isNotEmpty() || deezerAlbums.isNotEmpty() || deezerArtists.isNotEmpty()
        if (searchResult.isFailure && unifiedResults == null && !qobuzAvailable && !deezerHasResults) {
            // Every backend failed (offline / all instances down). Distinguish
            // this from a successful-but-empty search so the UI can offer a
            // retry instead of a flat "No results found".
            clearResults()
            _searchError.value = true
            _isSearching.value = false
            return
        }

        val localAndCollectionTracks = listOfNotNull(
            unifiedResults?.localTracks,
            unifiedResults?.collectionTracks
        ).flatten()

        val qobuzSearch = qobuzResult?.getOrNull()?.getOrNull()
        val qobuzTracks = qobuzSearch?.tracks?.map { it.toQobuzUnifiedTrack() } ?: emptyList()
        val qobuzAlbums = qobuzSearch?.albums ?: emptyList()
        val qobuzArtists = qobuzSearch?.artists ?: emptyList()
        // Qobuz album clicks are now wired through QobuzIdRegistry +
        // AlbumDetailViewModel's Qobuz-first lookup. Artist clicks still fall
        // through to the TIDAL artist endpoint until /api/get-artist's
        // contract is verified — when that endpoint surfaces an unknown id
        // it returns a Result.failure and the screen renders an error
        // (handled, not a crash).

        // A new query's results are tagged afresh; the previous query's tags
        // would otherwise win first-writer for an id that changed catalog.
        _albumSources.value = emptyMap()
        _artistSources.value = emptyMap()
        if (searchResult.getOrNull()?.isSuccess == true) {
            val result = searchResult.getOrThrow().getOrThrow()
            _allTracks.value = scoreTracks(
                query = trimmedQuery,
                tracks = localAndCollectionTracks +
                    result.tracks.map { it.toUnifiedTrack() } +
                    qobuzTracks +
                    deezerTracks
            )
            tagAlbums(result.albums, SourceType.API)
            tagAlbums(qobuzAlbums, SourceType.QOBUZ)
            tagAlbums(deezerAlbums, SourceType.DEEZER)
            tagArtists(result.artists, SourceType.API)
            tagArtists(qobuzArtists, SourceType.QOBUZ)
            tagArtists(deezerArtists, SourceType.DEEZER)
            _allAlbums.value = scoreItems(
                trimmedQuery,
                (result.albums + qobuzAlbums + deezerAlbums).distinctBy { it.id },
            ) { listOf(it.title, it.displayArtist) }
            _allArtists.value = scoreItems(
                trimmedQuery,
                (result.artists + qobuzArtists + deezerArtists).distinctBy { it.id },
            ) { listOf(it.name) }
            _allPlaylists.value = scoreItems(trimmedQuery, result.playlists) {
                listOfNotNull(it.title, it.creator?.name, it.description)
            }
            preferences.addSearchHistoryQuery(trimmedQuery)
            // Seed paging state from the initial page sizes. Backends that
            // ignore &offset=&limit= will return < PAGE_SIZE here and the
            // ViewModel will refuse further fetches for that type/source.
            seedPageEnd(tracksPage,    result.tracks.size,    qobuzTracks.size,  qobuzAvailable)
            seedPageEnd(albumsPage,    result.albums.size,    qobuzAlbums.size,  qobuzAvailable)
            seedPageEnd(artistsPage,   result.artists.size,   qobuzArtists.size, qobuzAvailable)
            seedPageEnd(playlistsPage, result.playlists.size, /*qobuz=*/0,       qobuzAvailable)
        } else {
            // TIDAL is down — still surface Qobuz + local results so search
            // doesn't feel broken when the public TIDAL pool is unreachable.
            _allTracks.value = scoreTracks(
                query = trimmedQuery,
                tracks = localAndCollectionTracks + qobuzTracks + deezerTracks
            )
            tagAlbums(qobuzAlbums, SourceType.QOBUZ)
            tagAlbums(deezerAlbums, SourceType.DEEZER)
            tagArtists(qobuzArtists, SourceType.QOBUZ)
            tagArtists(deezerArtists, SourceType.DEEZER)
            _allAlbums.value = scoreItems(trimmedQuery, (qobuzAlbums + deezerAlbums).distinctBy { it.id }) { listOf(it.title, it.displayArtist) }
            _allArtists.value = scoreItems(trimmedQuery, (qobuzArtists + deezerArtists).distinctBy { it.id }) { listOf(it.name) }
            _allPlaylists.value = emptyList()
            // TIDAL failed → mark its end on every type so loadMore won't retry.
            // This is also the path a setup with no TIDAL API takes: the TIDAL
            // search fails rather than being skipped, so tidalEnd lands here and
            // paging never asks TIDAL for a page 2.
            tracksPage.tidalEnd = true; albumsPage.tidalEnd = true
            artistsPage.tidalEnd = true; playlistsPage.tidalEnd = true
            seedPageEnd(tracksPage,    /*tidal=*/0, qobuzTracks.size,  qobuzAvailable)
            seedPageEnd(albumsPage,    /*tidal=*/0, qobuzAlbums.size,  qobuzAvailable)
            seedPageEnd(artistsPage,   /*tidal=*/0, qobuzArtists.size, qobuzAvailable)
            seedPageEnd(playlistsPage, /*tidal=*/0, /*qobuz=*/0,       qobuzAvailable)
        }
        _endReached.value = tracksPage.done() && albumsPage.done() && artistsPage.done() && playlistsPage.done()
        _isSearching.value = false
    }

    // Seed paging state from the initial page. Both catalogues paginate from
    // loadMore: TIDAL by a PAGE_SIZE offset, Qobuz by the number of items
    // already shown for this type (one combined envelope per call).
    private fun seedPageEnd(state: PageState, tidalCount: Int, qobuzCount: Int, qobuzAvailable: Boolean) {
        if (tidalCount < PAGE_SIZE) state.tidalEnd = true
        state.qobuzEnd = !qobuzAvailable || qobuzCount == 0
        state.qobuzOffset = qobuzCount
        state.nextOffset = PAGE_SIZE
    }

    /**
     * Append the next page of results for [type]. Called by the Compose UI when
     * the user scrolls near the end of a list. No-op if a page is already
     * in-flight, the query is blank, or the type has exhausted BOTH backends.
     * Now pages TIDAL (PAGE_SIZE offset) and Qobuz (offset = items shown so far)
     * so Qobuz results keep loading on scroll instead of stopping after page 1.
     */
    fun loadMore(type: SearchPageType) {
        val q = _query.value.trim()
        if (q.isBlank()) return
        val state = pageFor(type)
        if (state.inFlight || state.done()) return
        // Snapshot the query generation: if it changes while this page is in
        // flight (the user edited the query), every write below is skipped so
        // the stale page can neither append into nor prematurely end the new
        // query's paging.
        val gen = searchGeneration
        state.inFlight = true
        _isLoadingMore.value = true
        val job = viewModelScope.launch {
            try {
                // --- TIDAL page (incremental offset paging) ---
                if (!state.tidalEnd) {
                    val offset = state.nextOffset
                    val tidalItems: List<Any> = when (type) {
                        SearchPageType.TRACKS ->
                            repository.searchTracks(q, offset, PAGE_SIZE).getOrDefault(emptyList())
                        SearchPageType.ALBUMS ->
                            repository.searchAlbums(q, offset, PAGE_SIZE).getOrDefault(emptyList())
                        SearchPageType.ARTISTS ->
                            repository.searchArtists(q, offset, PAGE_SIZE).getOrDefault(emptyList())
                        SearchPageType.PLAYLISTS ->
                            repository.searchPlaylists(q, offset, PAGE_SIZE).getOrDefault(emptyList())
                    }
                    if (gen != searchGeneration) return@launch
                    if (tidalItems.size < PAGE_SIZE) state.tidalEnd = true
                    state.nextOffset = offset + PAGE_SIZE
                    appendPage(type, tidalItems, PageSource.TIDAL, q = q)
                }

                // --- Qobuz page (one combined envelope; offset = items shown). ---
                // Qobuz has no playlists. Dedup makes a backend that ignores
                // &offset= harmless: a repeated page adds nothing → we mark end.
                if (!state.qobuzEnd && type != SearchPageType.PLAYLISTS) {
                    val before = currentCountFor(type)
                    val qobuz = withTimeoutOrNull(QOBUZ_BUDGET_MS) {
                        runCatching { repository.searchQobuz(q, state.qobuzOffset) }.getOrNull()?.getOrNull()
                    }
                    if (gen != searchGeneration) return@launch
                    val qItems: List<Any> = when (type) {
                        SearchPageType.TRACKS -> qobuz?.tracks ?: emptyList()
                        SearchPageType.ALBUMS -> qobuz?.albums ?: emptyList()
                        SearchPageType.ARTISTS -> qobuz?.artists ?: emptyList()
                        SearchPageType.PLAYLISTS -> emptyList()
                    }
                    state.qobuzOffset += qItems.size
                    appendPage(type, qItems, PageSource.QOBUZ, q = q)
                    if (qItems.isEmpty() || currentCountFor(type) == before) state.qobuzEnd = true
                } else if (type == SearchPageType.PLAYLISTS) {
                    state.qobuzEnd = true
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // A cancelled page (query changed) must NOT mark paging ended —
                // that used to kill the new query's infinite scroll after page 1.
                throw e
            } catch (_: Exception) {
                // A genuinely failed page just stops paging for this type.
                if (gen == searchGeneration) {
                    state.tidalEnd = true
                    state.qobuzEnd = true
                }
            } finally {
                // Only touch shared paging flags / the job map if we're still the
                // current query — otherwise resetPaging already owns this state.
                if (gen == searchGeneration) {
                    state.inFlight = false
                    _endReached.value = tracksPage.done() && albumsPage.done() &&
                        artistsPage.done() && playlistsPage.done()
                    _isLoadingMore.value = tracksPage.inFlight || albumsPage.inFlight ||
                        artistsPage.inFlight || playlistsPage.inFlight
                    loadMoreJobs.remove(type)
                }
            }
        }
        loadMoreJobs[type] = job
    }

    private fun currentCountFor(type: SearchPageType): Int = when (type) {
        SearchPageType.TRACKS -> _allTracks.value.size
        SearchPageType.ALBUMS -> _allAlbums.value.size
        SearchPageType.ARTISTS -> _allArtists.value.size
        SearchPageType.PLAYLISTS -> _allPlaylists.value.size
    }

    // Appends a fetched page, scoring only the NEW items and dropping ones
    // already shown. The whole list used to be re-scored on every page, which
    // reshuffled items the user had already scrolled past; keeping existing
    // order and only ranking the fresh tail fixes that.
    private fun appendPage(type: SearchPageType, items: List<Any>, source: PageSource, q: String) {
        if (items.isEmpty()) return
        when (type) {
            SearchPageType.TRACKS -> {
                @Suppress("UNCHECKED_CAST")
                val mapped = (items as List<Track>).map {
                    // The id spaces don't overlap — a Qobuz id read as TIDAL's
                    // resolves to a different recording — so the catalogue a
                    // page came from decides the conversion.
                    when (source) {
                        PageSource.QOBUZ -> it.toQobuzUnifiedTrack()
                        PageSource.TIDAL -> it.toUnifiedTrack()
                    }
                }
                val existing = _allTracks.value
                val seen = existing.mapTo(HashSet()) { it.id }
                val fresh = scoreTracks(q, mapped).filterNot { it.id in seen }
                _allTracks.value = existing + fresh
            }
            SearchPageType.ALBUMS -> {
                @Suppress("UNCHECKED_CAST")
                tagAlbums(items as List<Album>, source.sourceType)
                val existing = _allAlbums.value
                val seen = existing.mapTo(HashSet()) { it.id }
                val fresh = scoreItems(q, (items as List<Album>).distinctBy { it.id }) {
                    listOf(it.title, it.displayArtist)
                }.filterNot { it.id in seen }
                _allAlbums.value = existing + fresh
            }
            SearchPageType.ARTISTS -> {
                @Suppress("UNCHECKED_CAST")
                tagArtists(items as List<Artist>, source.sourceType)
                val existing = _allArtists.value
                val seen = existing.mapTo(HashSet()) { it.id }
                val fresh = scoreItems(q, (items as List<Artist>).distinctBy { it.id }) {
                    listOf(it.name)
                }.filterNot { it.id in seen }
                _allArtists.value = existing + fresh
            }
            SearchPageType.PLAYLISTS -> {
                @Suppress("UNCHECKED_CAST")
                val existing = _allPlaylists.value
                val seen = existing.mapTo(HashSet()) { it.uuid }
                val fresh = scoreItems(q, (items as List<Playlist>).distinctBy { it.uuid }) {
                    listOfNotNull(it.title, it.creator?.name, it.description)
                }.filterNot { it.uuid in seen }
                _allPlaylists.value = existing + fresh
            }
        }
    }

    private fun clearResults() {
        _albumSources.value = emptyMap()
        _artistSources.value = emptyMap()
        _allTracks.value = emptyList()
        _allAlbums.value = emptyList()
        _allArtists.value = emptyList()
        _allPlaylists.value = emptyList()
        _isSearching.value = false
        _searchError.value = false
    }

    private fun scoreTracks(
        query: String,
        tracks: List<UnifiedTrack>
    ): List<UnifiedTrack> = tracks
        .distinctBy { it.id }
        .sortedByDescending { track ->
            scoreField(query, track.title, SCORE_WEIGHT_PRIMARY) +
                scoreField(query, track.artistName, SCORE_WEIGHT_SECONDARY) +
                scoreField(query, track.albumTitle, SCORE_WEIGHT_TERTIARY) +
                sourceBoost(track.sourceType)
        }

    private fun <T> scoreItems(
        query: String,
        items: List<T>,
        fields: (T) -> List<String?>
    ): List<T> = items.sortedByDescending { item ->
        fields(item).mapIndexed { index, value ->
            scoreField(query, value, when (index) {
                0 -> SCORE_WEIGHT_PRIMARY
                1 -> SCORE_WEIGHT_SECONDARY
                else -> SCORE_WEIGHT_TERTIARY
            })
        }.sum()
    }

    private fun sourceBoost(sourceType: SourceType): Int = when (sourceType) {
        SourceType.LOCAL -> SOURCE_BOOST_LOCAL
        SourceType.COLLECTION -> SOURCE_BOOST_COLLECTION
        SourceType.API -> SOURCE_BOOST_API
        // Qobuz is download-only — keep below TIDAL streaming so taps default
        // to a playable result when both sources surface the same query.
        SourceType.QOBUZ -> SOURCE_BOOST_API - 5
        // Apple Music (via the instance); rank just below Qobuz.
        SourceType.APPLE -> SOURCE_BOOST_API - 6
        // Deezer is a preview catalog until a pick is matched to Qobuz, so it
        // ranks under both streaming catalogs for the same query.
        SourceType.DEEZER -> SOURCE_BOOST_API - 7
        // Live radio never reaches search — stations are found on the globe, by
        // place rather than by name — so it has no ranking to earn.
        SourceType.LIVE_RADIO -> 0
    }

    private fun scoreField(query: String, rawValue: String?, baseScore: Int): Int {
        val normalizedQuery = query.normalized()
        val normalizedValue = rawValue?.normalized().orEmpty()
        if (normalizedQuery.isBlank() || normalizedValue.isBlank()) return 0
        return when {
            normalizedValue == normalizedQuery -> baseScore
            normalizedValue.startsWith(normalizedQuery) -> baseScore - SCORE_PENALTY_PREFIX_MATCH
            normalizedValue.tokenStartsWith(normalizedQuery) -> baseScore - SCORE_PENALTY_TOKEN_PREFIX_MATCH
            normalizedValue.contains(normalizedQuery) -> baseScore - SCORE_PENALTY_SUBSTRING_MATCH
            else -> 0
        }
    }

    private fun String.normalized(): String = lowercase().trim()

    private fun String.tokenStartsWith(query: String): Boolean =
        splitToSequence(' ', '-', '_', '/', '.', '(', ')').any { token -> token.startsWith(query) }
}
