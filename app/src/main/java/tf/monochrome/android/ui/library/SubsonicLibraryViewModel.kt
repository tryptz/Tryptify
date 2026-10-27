package tf.monochrome.android.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.subsonic.SubsonicAlbumSort
import tf.monochrome.android.data.subsonic.SubsonicRepository
import tf.monochrome.android.data.subsonic.ServerPlaylist
import tf.monochrome.android.data.subsonic.SubsonicServer
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject

/**
 * The "My server" Library page: the listener's Subsonic server, read live.
 *
 * The page keeps its own small stack (index → artist → album) rather than
 * pushing nav routes. It is one page of the pager, and Back pops this stack
 * first (see SubsonicLibraryTab's BackHandler), the way the Local page's
 * categories close before the page does.
 */
@HiltViewModel
class SubsonicLibraryViewModel @Inject constructor(
    private val repository: SubsonicRepository,
) : ViewModel() {

    enum class Section { ALBUMS, ARTISTS, SONGS, PLAYLISTS, LIKED }

    sealed interface View {
        data object Index : View
        data class Artist(val artist: UnifiedArtist) : View
        data class Album(val album: UnifiedAlbum) : View
        data class Playlist(val playlist: ServerPlaylist) : View
    }

    /** Something loading, loaded, or failed; [Failed] keeps the reason for the log. */
    sealed interface Load<out T> {
        data object Loading : Load<Nothing>
        data class Loaded<T>(val value: T) : Load<T>
        data class Failed(val reason: String?) : Load<Nothing>
    }

    /** Loading until the saved server has been read, so the page never flashes "not connected". */
    val server: StateFlow<Load<SubsonicServer?>> = repository.server
        .distinctUntilChanged()
        .map<SubsonicServer?, Load<SubsonicServer?>> { Load.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Loading)

    private val _section = MutableStateFlow(Section.ALBUMS)
    val section: StateFlow<Section> = _section.asStateFlow()

    private val _albumSort = MutableStateFlow(SubsonicAlbumSort.NAME)
    val albumSort: StateFlow<SubsonicAlbumSort> = _albumSort.asStateFlow()

    private val _albums = MutableStateFlow<Load<List<UnifiedAlbum>>>(Load.Loading)
    val albums: StateFlow<Load<List<UnifiedAlbum>>> = _albums.asStateFlow()

    private val _artists = MutableStateFlow<Load<List<UnifiedArtist>>>(Load.Loading)
    val artists: StateFlow<Load<List<UnifiedArtist>>> = _artists.asStateFlow()

    private val _songs = MutableStateFlow<Load<List<UnifiedTrack>>>(Load.Loading)
    val songs: StateFlow<Load<List<UnifiedTrack>>> = _songs.asStateFlow()

    private val _query = MutableStateFlow("")

    /** Bumped by [retrySearch], to ask again for the same words. */
    private val searchTries = MutableStateFlow(0)
    val query: StateFlow<String> = _query.asStateFlow()

    /**
     * The server's own search over its songs, asked again once typing pauses.
     * Songs only, like Search's server results: an album or artist row would
     * open this page's stack from a place that has none.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<Load<List<UnifiedTrack>>> = _query
        .map { it.trim() }
        .distinctUntilChanged()
        .debounce(SEARCH_DEBOUNCE_MS)
        .combine(searchTries) { q, _ -> q }
        .flatMapLatest { q ->
            flow {
                if (q.isEmpty()) {
                    emit(Load.Loaded(emptyList()))
                } else {
                    emit(Load.Loading)
                    emit(runLoad { repository.songs(q, offset = 0, size = SEARCH_RESULTS) })
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Load.Loaded(emptyList()))

    private val _stack = MutableStateFlow<List<View>>(listOf(View.Index))
    val view: StateFlow<List<View>> = _stack.asStateFlow()

    private val _detailAlbums = MutableStateFlow<Load<List<UnifiedAlbum>>>(Load.Loading)
    val artistAlbums: StateFlow<Load<List<UnifiedAlbum>>> = _detailAlbums.asStateFlow()

    // The open album's songs, or the open playlist's: one is open at a time.
    private val _detailTracks = MutableStateFlow<Load<List<UnifiedTrack>>>(Load.Loading)
    val albumTracks: StateFlow<Load<List<UnifiedTrack>>> = _detailTracks.asStateFlow()

    private val _playlists = MutableStateFlow<Load<List<ServerPlaylist>>>(Load.Loading)
    val playlists: StateFlow<Load<List<ServerPlaylist>>> = _playlists.asStateFlow()

    /** The songs starred on the server: liking a server song here stars it. */
    private val _liked = MutableStateFlow<Load<List<UnifiedTrack>>>(Load.Loading)
    val liked: StateFlow<Load<List<UnifiedTrack>>> = _liked.asStateFlow()

    /**
     * The sections this server has said it does not keep: a Subsonic server
     * need not have playlists or stars (Tryptify Cloud has neither, and
     * answers "Not supported"). Their chips are not shown; an error there
     * would only say the library failed to load, which it did not.
     */
    private val _unsupported = MutableStateFlow<Set<Section>>(emptySet())
    val unsupported: StateFlow<Set<Section>> = _unsupported.asStateFlow()

    private var albumsJob: Job? = null
    private var songsJob: Job? = null
    private var loadedFor: SubsonicServer? = null

    /** Called when the page shows a connected server: loads once per server. */
    fun onServer(server: SubsonicServer) {
        if (server == loadedFor) return
        loadedFor = server
        _stack.value = listOf(View.Index)
        reload()
    }

    fun reload() {
        loadAlbums()
        loadArtists()
        // Every song can be tens of thousands of rows: fetched only once the
        // Songs chip has been opened, not on every visit to the page.
        if (_section.value == Section.SONGS) loadSongs() else {
            songsJob?.cancel()
            _songs.value = Load.Loading
        }
        loadPlaylists()
        loadLiked()
    }

    fun selectSection(section: Section) {
        _section.value = section
        if (section == Section.SONGS && _songs.value !is Load.Loaded && songsJob?.isActive != true) loadSongs()
        // Likes change while the page is open (a like stars on the server).
        if (section == Section.LIKED) loadLiked()
    }

    private fun loadPlaylists() {
        _playlists.value = Load.Loading
        viewModelScope.launch {
            _playlists.value = runLoad(Section.PLAYLISTS) { repository.playlists().sortedBy { it.name.lowercase() } }
        }
    }

    private fun loadLiked() {
        // Kept on screen while it refreshes, rather than a spinner each visit.
        if (_liked.value !is Load.Loaded) _liked.value = Load.Loading
        viewModelScope.launch { _liked.value = runLoad(Section.LIKED) { repository.starredSongs() } }
    }

    /**
     * [runLoad] for a section the server may not keep: a `failed` answer (the
     * server replied, and said no) marks it unsupported and leaves it, so the
     * page shows albums rather than an error. Anything else (the server away)
     * fails as usual, to be retried.
     */
    private suspend fun <T> runLoad(section: Section, block: suspend () -> T): Load<T> = try {
        Load.Loaded(block()).also { _unsupported.value = _unsupported.value - section }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: tf.monochrome.android.data.subsonic.SubsonicException) {
        _unsupported.value = _unsupported.value + section
        if (_section.value == section) _section.value = Section.ALBUMS
        Load.Failed(e.message)
    } catch (e: Exception) {
        Load.Failed(e.message)
    }

    fun openPlaylist(playlist: ServerPlaylist) {
        _stack.value = _stack.value + View.Playlist(playlist)
        _detailTracks.value = Load.Loading
        viewModelScope.launch { _detailTracks.value = runLoad { repository.playlistTracks(playlist.id) } }
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun retrySearch() {
        searchTries.value++
    }

    fun setAlbumSort(sort: SubsonicAlbumSort) {
        if (_albumSort.value == sort) return
        _albumSort.value = sort
        loadAlbums()
    }

    /**
     * Every album, a page at a time, showing each page as it lands so a big
     * library fills in rather than sitting on a spinner. getAlbumList2 caps a
     * page at 500.
     */
    private fun loadAlbums() {
        albumsJob?.cancel()
        _albums.value = Load.Loading
        albumsJob = viewModelScope.launch {
            val sort = _albumSort.value
            val all = mutableListOf<UnifiedAlbum>()
            try {
                while (all.size < MAX_ALBUMS) {
                    val page = repository.albums(sort, offset = all.size, size = ALBUM_PAGE)
                    all += page
                    _albums.value = Load.Loaded(all.distinctBy { it.id })
                    // A random list never ends; one page of it is the answer.
                    if (page.size < ALBUM_PAGE || sort == SubsonicAlbumSort.RANDOM) break
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (all.isEmpty()) _albums.value = Load.Failed(e.message)
            }
        }
    }

    /**
     * Every song, a page at a time, the way [loadAlbums] does albums. The end
     * is an empty page, not a short one: videos are dropped from each page, so
     * a short page can still have more after it. A page that adds nothing new
     * also ends it, in case a server ignores songOffset and repeats page one.
     */
    private fun loadSongs() {
        songsJob?.cancel()
        _songs.value = Load.Loading
        songsJob = viewModelScope.launch {
            val all = LinkedHashMap<String, UnifiedTrack>()
            var offset = 0
            try {
                while (all.size < MAX_SONGS) {
                    val page = repository.songs("", offset = offset, size = SONG_PAGE)
                    val before = all.size
                    page.forEach { all.putIfAbsent(it.id, it) }
                    _songs.value = Load.Loaded(all.values.toList())
                    if (page.isEmpty() || all.size == before) break
                    offset += SONG_PAGE
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (all.isEmpty()) _songs.value = Load.Failed(e.message)
            }
        }
    }

    private fun loadArtists() {
        _artists.value = Load.Loading
        viewModelScope.launch {
            _artists.value = runLoad { repository.artists().sortedBy { it.name.lowercase() } }
        }
    }

    fun openArtist(artist: UnifiedArtist) {
        _stack.value = _stack.value + View.Artist(artist)
        _detailAlbums.value = Load.Loading
        viewModelScope.launch { _detailAlbums.value = runLoad { repository.artistAlbums(artist.id) } }
    }

    fun openAlbum(album: UnifiedAlbum) {
        _stack.value = _stack.value + View.Album(album)
        _detailTracks.value = Load.Loading
        viewModelScope.launch { _detailTracks.value = runLoad { repository.albumTracks(album.id) } }
    }

    /** Back out of an album or artist. False when already at the index. */
    fun back(): Boolean {
        val stack = _stack.value
        if (stack.size <= 1) return false
        val next = stack.dropLast(1)
        _stack.value = next
        // Going back to an artist from one of its albums: its albums are still
        // what _detailAlbums holds, since an album page loads only tracks.
        return true
    }

    /** A random selection from the whole server, for "Shuffle all". */
    fun shuffleAll(play: (List<UnifiedTrack>) -> Unit) {
        viewModelScope.launch {
            runCatching { repository.randomSongs() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let(play)
        }
    }

    private suspend fun <T> runLoad(block: suspend () -> T): Load<T> = try {
        Load.Loaded(block())
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Load.Failed(e.message)
    }

    private companion object {
        const val ALBUM_PAGE = 500
        const val MAX_ALBUMS = 20_000
        const val SONG_PAGE = 500
        const val MAX_SONGS = 100_000
        const val SEARCH_RESULTS = 200
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}
