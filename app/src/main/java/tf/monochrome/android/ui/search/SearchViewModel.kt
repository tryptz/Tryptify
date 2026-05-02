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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.Playlist
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.usecase.SearchUnifiedLibraryUseCase

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: MusicRepository,
    private val unifiedLibrarySearch: SearchUnifiedLibraryUseCase,
    private val preferences: PreferencesManager
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
        private const val DEFAULT_ARTIST_NAME = "Unknown Artist"
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
        var tidalEnd: Boolean = false
        var qobuzEnd: Boolean = false
        @Volatile var inFlight: Boolean = false
        fun reset() { nextOffset = 0; tidalEnd = false; qobuzEnd = false; inFlight = false }
        fun done(): Boolean = tidalEnd && qobuzEnd
    }

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

    private fun resetPaging() {
        tracksPage.reset(); albumsPage.reset(); artistsPage.reset(); playlistsPage.reset()
        _isLoadingMore.value = false
        _endReached.value = false
        loadMoreJob?.cancel()
    }

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _allTracks = MutableStateFlow<List<UnifiedTrack>>(emptyList())
    private val _allAlbums = MutableStateFlow<List<Album>>(emptyList())
    private val _allArtists = MutableStateFlow<List<Artist>>(emptyList())
    private val _allPlaylists = MutableStateFlow<List<Playlist>>(emptyList())

    private val _selectedType = MutableStateFlow(SearchTypeFilter.ALL)
    val selectedType: StateFlow<SearchTypeFilter> = _selectedType.asStateFlow()

    private val _selectedSource = MutableStateFlow(SearchSourceFilter.ALL)
    val selectedSource: StateFlow<SearchSourceFilter> = _selectedSource.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    val searchHistory: StateFlow<List<String>> = preferences.searchHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tracks: StateFlow<List<UnifiedTrack>> = combine(
        _allTracks,
        _selectedType,
        _selectedSource
    ) { trackResults, type, source ->
        if (type != SearchTypeFilter.ALL && type != SearchTypeFilter.TRACKS) {
            emptyList()
        } else if (source == SearchSourceFilter.ALL) {
            trackResults
        } else {
            trackResults.filter { it.sourceType == source.sourceType }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<Album>> = combine(_allAlbums, _selectedType) { albumResults, type ->
        if (type == SearchTypeFilter.ALL || type == SearchTypeFilter.ALBUMS) albumResults else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val artists: StateFlow<List<Artist>> = combine(_allArtists, _selectedType) { artistResults, type ->
        if (type == SearchTypeFilter.ALL || type == SearchTypeFilter.ARTISTS) artistResults else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlists: StateFlow<List<Playlist>> = combine(_allPlaylists, _selectedType) { playlistResults, type ->
        if (type == SearchTypeFilter.ALL || type == SearchTypeFilter.PLAYLISTS) playlistResults else emptyList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val showSourceFilter: StateFlow<Boolean> = selectedType
        .combine(query) { type, currentQuery ->
            currentQuery.isNotBlank() && (type == SearchTypeFilter.ALL || type == SearchTypeFilter.TRACKS)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null

    fun onQueryChange(newQuery: String) {
        _query.value = newQuery
        searchJob?.cancel()
        resetPaging()
        if (newQuery.isBlank()) {
            clearResults()
            return
        }
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

    private suspend fun performSearch(query: String) {
        _isSearching.value = true
        val trimmedQuery = query.trim()
        // TIDAL, Qobuz, and the local/collection library all run in parallel.
        // Qobuz failures (instance unset, network error, schema mismatch) are
        // swallowed so the existing TIDAL flow keeps working unchanged.
        val (searchResult, qobuzResult, unifiedResultsResult) = coroutineScope {
            // Source mode (Settings → Instances → Source) gates which
            // catalogs we fan out to. TIDAL_ONLY / QOBUZ_ONLY skip the
            // disabled side; BOTH (default) keeps the existing behavior.
            val sourceMode = preferences.sourceMode.first()
            val apiDeferred = async {
                if (sourceMode == tf.monochrome.android.data.preferences.SourceMode.QOBUZ_ONLY) {
                    runCatching {
                        Result.failure<tf.monochrome.android.domain.model.SearchResult>(
                            IllegalStateException("TIDAL disabled by source mode")
                        )
                    }
                } else {
                    runCatching { repository.search(trimmedQuery) }
                }
            }
            val qobuzDeferred = async {
                if (sourceMode == tf.monochrome.android.data.preferences.SourceMode.TIDAL_ONLY) {
                    null
                } else {
                    withTimeoutOrNull(QOBUZ_BUDGET_MS) {
                        runCatching { repository.searchQobuz(trimmedQuery) }
                    }
                }
            }
            val libraryDeferred = async { runCatching { unifiedLibrarySearch.search(trimmedQuery).first() } }
            Triple(apiDeferred.await(), qobuzDeferred.await(), libraryDeferred.await())
        }
        val unifiedResults = unifiedResultsResult.getOrNull()

        val qobuzAvailable = qobuzResult?.isSuccess == true
        if (searchResult.isFailure && unifiedResults == null && !qobuzAvailable) {
            clearResults()
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

        if (searchResult.getOrNull()?.isSuccess == true) {
            val result = searchResult.getOrThrow().getOrThrow()
            _allTracks.value = scoreTracks(
                query = trimmedQuery,
                tracks = localAndCollectionTracks +
                    result.tracks.map { it.toUnifiedTrack() } +
                    qobuzTracks
            )
            _allAlbums.value = scoreItems(
                trimmedQuery,
                (result.albums + qobuzAlbums).distinctBy { it.id },
            ) { listOf(it.title, it.displayArtist) }
            _allArtists.value = scoreItems(
                trimmedQuery,
                (result.artists + qobuzArtists).distinctBy { it.id },
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
                tracks = localAndCollectionTracks + qobuzTracks
            )
            _allAlbums.value = scoreItems(trimmedQuery, qobuzAlbums) { listOf(it.title, it.displayArtist) }
            _allArtists.value = scoreItems(trimmedQuery, qobuzArtists) { listOf(it.name) }
            _allPlaylists.value = emptyList()
            // TIDAL failed → mark its end on every type so loadMore won't retry.
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

    // Qobuz returns one combined envelope per call; we only paginate the
    // TIDAL side from loadMore, so always mark Qobuz "end" after the initial
    // seed. The Qobuz arg is accepted for symmetry with the call sites and
    // future per-type Qobuz paging.
    private fun seedPageEnd(state: PageState, tidalCount: Int, @Suppress("unused") qobuzCount: Int, @Suppress("unused") qobuzAvailable: Boolean) {
        if (tidalCount < PAGE_SIZE) state.tidalEnd = true
        state.qobuzEnd = true
        state.nextOffset = PAGE_SIZE
    }

    /**
     * Append the next page of results for [type] from TIDAL. Called by the
     * Compose UI when the user scrolls near the end of a list. No-op if a
     * page is already in-flight, the query is blank, or the type has
     * exhausted both backends. Only TIDAL is paginated incrementally —
     * Qobuz returns a single combined envelope at initial-search time.
     */
    fun loadMore(type: SearchPageType) {
        val q = _query.value.trim()
        if (q.isBlank()) return
        val state = pageFor(type)
        if (state.inFlight || state.done()) return
        if (state.tidalEnd) return // nothing left to fetch (Qobuz isn't paged here)
        state.inFlight = true
        _isLoadingMore.value = true
        loadMoreJob = viewModelScope.launch {
            try {
                val offset = state.nextOffset
                val newItems: List<Any> = when (type) {
                    SearchPageType.TRACKS ->
                        repository.searchTracks(q, offset, PAGE_SIZE).getOrDefault(emptyList())
                    SearchPageType.ALBUMS ->
                        repository.searchAlbums(q, offset, PAGE_SIZE).getOrDefault(emptyList())
                    SearchPageType.ARTISTS ->
                        repository.searchArtists(q, offset, PAGE_SIZE).getOrDefault(emptyList())
                    SearchPageType.PLAYLISTS ->
                        repository.searchPlaylists(q, offset, PAGE_SIZE).getOrDefault(emptyList())
                }
                if (newItems.size < PAGE_SIZE) state.tidalEnd = true
                state.nextOffset = offset + PAGE_SIZE

                if (newItems.isNotEmpty()) {
                    when (type) {
                        SearchPageType.TRACKS -> {
                            @Suppress("UNCHECKED_CAST")
                            val tracks = (newItems as List<Track>).map { it.toUnifiedTrack() }
                            _allTracks.value = scoreTracks(q, _allTracks.value + tracks)
                        }
                        SearchPageType.ALBUMS -> {
                            @Suppress("UNCHECKED_CAST")
                            _allAlbums.value = scoreItems(q, (_allAlbums.value + (newItems as List<Album>)).distinctBy { it.id }) {
                                listOf(it.title, it.displayArtist)
                            }
                        }
                        SearchPageType.ARTISTS -> {
                            @Suppress("UNCHECKED_CAST")
                            _allArtists.value = scoreItems(q, (_allArtists.value + (newItems as List<Artist>)).distinctBy { it.id }) {
                                listOf(it.name)
                            }
                        }
                        SearchPageType.PLAYLISTS -> {
                            @Suppress("UNCHECKED_CAST")
                            _allPlaylists.value = scoreItems(q, (_allPlaylists.value + (newItems as List<Playlist>)).distinctBy { it.uuid }) {
                                listOfNotNull(it.title, it.creator?.name, it.description)
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Swallow — a failed page just stops paging for this type.
                state.tidalEnd = true
            } finally {
                state.inFlight = false
                _endReached.value = tracksPage.done() && albumsPage.done() &&
                    artistsPage.done() && playlistsPage.done()
                _isLoadingMore.value = tracksPage.inFlight || albumsPage.inFlight ||
                    artistsPage.inFlight || playlistsPage.inFlight
            }
        }
    }

    private fun clearResults() {
        _allTracks.value = emptyList()
        _allAlbums.value = emptyList()
        _allArtists.value = emptyList()
        _allPlaylists.value = emptyList()
        _isSearching.value = false
    }

    private fun Track.toUnifiedTrack(): UnifiedTrack = UnifiedTrack(
        id = "api_$id",
        title = title,
        durationSeconds = duration,
        trackNumber = trackNumber,
        discNumber = volumeNumber,
        explicit = explicit,
        artistName = displayArtist.ifBlank { DEFAULT_ARTIST_NAME },
        artistNames = artists.map { it.name }.ifEmpty { listOfNotNull(artist?.name) },
        albumArtistName = artist?.name,
        albumTitle = album?.title,
        albumId = album?.id?.toString(),
        artworkUri = coverUrl,
        source = PlaybackSource.HiFiApi(tidalId = id),
        sourceType = SourceType.API
    )

    // Qobuz tracks share the Track shape with TIDAL but are tagged so the UI
    // can label them and so the existing dedup (distinctBy id) doesn't collapse
    // a Qobuz hit onto the same numeric ID from TIDAL. Source is QobuzCached
    // — playback fetches the file via /api/download-music into the app cache
    // and ExoPlayer plays from the local file.
    private fun Track.toQobuzUnifiedTrack(): UnifiedTrack = UnifiedTrack(
        id = "qobuz_$id",
        title = title,
        durationSeconds = duration,
        trackNumber = trackNumber,
        discNumber = volumeNumber,
        explicit = explicit,
        artistName = displayArtist.ifBlank { DEFAULT_ARTIST_NAME },
        artistNames = artists.map { it.name }.ifEmpty { listOfNotNull(artist?.name) },
        albumArtistName = artist?.name,
        albumTitle = album?.title,
        albumId = album?.id?.toString(),
        artworkUri = coverUrl,
        source = PlaybackSource.QobuzCached(qobuzId = id),
        sourceType = SourceType.QOBUZ,
    )

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
