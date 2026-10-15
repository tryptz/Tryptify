package tf.monochrome.android.ui.library.galaxy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.local.galaxy.ArtistMusic
import tf.monochrome.android.data.local.galaxy.LibraryArtists
import tf.monochrome.android.data.local.galaxy.LocalGalaxyRepository
import tf.monochrome.android.data.local.repository.LocalMediaRepository.Companion.toUnifiedTrack
import tf.monochrome.android.data.local.scanner.MediaScanner
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.domain.model.GalaxyVisualSettings
import tf.monochrome.android.ui.discover.galaxy.GalaxyScene
import tf.monochrome.android.ui.discover.galaxy.LibraryStarField
import tf.monochrome.android.ui.discover.galaxy.LocalSystems
import tf.monochrome.android.ui.discover.galaxy.MoonRef
import tf.monochrome.android.ui.discover.galaxy.PlanetSystem
import tf.monochrome.android.ui.discover.galaxy.StarField
import tf.monochrome.android.ui.player.PlayerViewModel
import javax.inject.Inject
import kotlin.random.Random

/**
 * The library galaxy: the device's own music as a sky, artists as stars,
 * albums as planets, tracks as moons. See `docs/local-galaxy-architecture.md`.
 *
 * The stars are laid out off the main thread whenever the library changes;
 * a star's system is read once the selection has settled on it, so flying
 * past fifty artists does not read fifty artists' albums.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryGalaxyViewModel @Inject constructor(
    private val repository: LocalGalaxyRepository,
    private val genreGraph: GenreGraphRepository,
    private val preferences: PreferencesManager,
) : ViewModel() {

    private val library: StateFlow<LibraryArtists?> =
        repository.artists().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The stars, or null while they are still being read. Empty for an empty library. */
    val stars: StateFlow<Stars?> = library
        .map { lib -> lib?.let { Stars(LibraryStarField.build(it.artists, it.collabs, genreGraph.graph).takeIf { f -> f.size > 0 }, it) } }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _selection = MutableStateFlow<String?>(null)
    /** The selected star's id. */
    val selection: StateFlow<String?> = _selection.asStateFlow()

    /** The selected star's system, once its albums are read; null while they are, or with no star selected. */
    val system: StateFlow<LibrarySystemView?> = combine(_selection, stars) { id, s -> id to s }
        .flatMapLatest { (id, s) ->
            val field = s?.field
            val artistId = id?.let { LibraryStarField.keyOf(it) }?.let { s?.library?.ids?.get(it) }
            if (id == null || field == null || artistId == null) {
                flowOf<LibrarySystemView?>(null)
            } else {
                flow<LibrarySystemView?> {
                    emit(null)
                    // Settle first: a selection passing through is not worth a read.
                    delay(SYSTEM_SETTLE_MS)
                    val music = repository.music(artistId)
                    val star = field.ids.indexOf(id)
                    val radius = if (star >= 0) GalaxyScene.starRadius(field.prominence[star]) else PlanetSystem.DEFAULT_STAR_RADIUS
                    val sys = music?.let { LocalSystems.build(id, it.albums, radius) }
                    emit(if (sys != null && music != null) LibrarySystemView(sys, music) else null)
                }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val visualsEdit = MutableStateFlow<GalaxyVisualSettings?>(null)
    private var visualsSave: Job? = null

    /** How the galaxy looks: the genre map's own settings, so both maps look alike. */
    val visuals: StateFlow<GalaxyVisualSettings> =
        combine(preferences.galaxyVisuals, visualsEdit) { saved, edit -> edit ?: saved }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GalaxyVisualSettings.DEFAULT)

    fun setVisuals(settings: GalaxyVisualSettings) {
        val clamped = settings.clamped()
        visualsEdit.value = clamped
        visualsSave?.cancel()
        visualsSave = viewModelScope.launch {
            delay(VISUALS_SAVE_MS)
            preferences.setGalaxyVisuals(clamped)
        }
    }

    fun select(id: String?) {
        _selection.value = id
    }

    /** Somewhere else in the library: any artist but the one you are at. */
    fun surprise() {
        val ids = stars.value?.field?.ids ?: return
        val pool = ids.filter { it != _selection.value }
        if (pool.isNotEmpty()) select(pool[Random.nextInt(pool.size)])
    }

    /** The star of the artist [name] (as the player shows them), if they are in the library. */
    fun starOf(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val ids = stars.value?.library?.ids ?: return null
        val whole = MediaScanner.normalizeText(name)
        if (whole in ids) return LibraryStarField.starId(whole)
        // A track by several shows them joined; the first one with a star.
        return name.split(',', '&').map { MediaScanner.normalizeText(it) }.firstOrNull { it in ids }?.let(LibraryStarField::starId)
    }

    /** The `local_artists` row behind star [id], for the artist's own page. */
    fun artistIdOf(id: String): Long? = LibraryStarField.keyOf(id)?.let { stars.value?.library?.ids?.get(it) }

    /** Up to [count] artists star [id] has worked with, by star id: the dock's places to go next. */
    fun collaborators(id: String, count: Int): List<Pair<String, String>> {
        val field = stars.value?.field ?: return emptyList()
        val i = field.ids.indexOf(id)
        if (i < 0) return emptyList()
        val out = LinkedHashSet<Int>()
        for (k in 0 until field.links.size / 2) {
            val a = field.links[k * 2]
            val b = field.links[k * 2 + 1]
            if (a == i) out += b else if (b == i) out += a
        }
        return out.sortedByDescending { field.prominence[it] }.take(count).map { field.ids[it] to field.names[it] }
    }

    /** Every track by star [id]'s artist, shuffled: the dock's Play. */
    fun playArtist(id: String, player: PlayerViewModel) {
        val artistId = artistIdOf(id) ?: return
        viewModelScope.launch {
            val tracks = repository.tracksOf(artistId).map { it.toUnifiedTrack() }
            if (tracks.isNotEmpty()) player.shufflePlayUnified(tracks)
        }
    }

    /** Album [albumKey] of the open system, from its first track. */
    fun playAlbum(albumKey: String, player: PlayerViewModel) {
        val tracks = system.value?.music?.albumTracks?.get(albumKey) ?: return
        val queue = runningOrder(tracks).map { it.toUnifiedTrack() }
        if (queue.isNotEmpty()) player.playAllUnified(queue)
    }

    /** A moon: its track, with the rest of its album after it. */
    fun playMoon(ref: MoonRef, player: PlayerViewModel) {
        val local = ref as? MoonRef.Local ?: return
        val music = system.value?.music ?: return
        val album = music.albumTracks.values.firstOrNull { tracks -> tracks.any { it.id == local.trackId } } ?: return
        val queue = runningOrder(album).map { it.toUnifiedTrack() }
        val start = queue.firstOrNull { it.id == "local_${local.trackId}" } ?: return
        player.playUnifiedTrack(start, queue)
    }

    private fun runningOrder(tracks: List<tf.monochrome.android.data.local.db.LocalTrackEntity>) =
        tracks.sortedWith(compareBy({ it.discNumber ?: 1 }, { it.trackNumber ?: Int.MAX_VALUE }, { it.displayTitle.lowercase() }))

    /** The library's stars, and the artists they were laid out from. */
    class Stars(
        /** Null for an empty library. */
        val field: StarField?,
        val library: LibraryArtists,
    )

    private companion object {
        /** How long the selection rests on a star before its albums are read. */
        const val SYSTEM_SETTLE_MS = 150L

        /** The save waits for the hand to stop, as the genre map's does. */
        const val VISUALS_SAVE_MS = 350L
    }
}

/** An artist's system, and the files behind it. */
class LibrarySystemView(val system: PlanetSystem, val music: ArtistMusic)
