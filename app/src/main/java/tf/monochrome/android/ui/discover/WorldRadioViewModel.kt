package tf.monochrome.android.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.WorldRadioRepository
import tf.monochrome.android.domain.model.GlobeFxSettings
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.RadioCity
import tf.monochrome.android.domain.model.RadioStation
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.model.WorldRadioData
import tf.monochrome.android.ui.player.PlayerViewModel
import javax.inject.Inject

/** What the search bar knows about what is being typed. */
sealed interface StationSearchState {
    data object Idle : StationSearchState
    data object Searching : StationSearchState
    data class Ready(val stations: List<RadioStation>) : StationSearchState

    /** Asked and couldn't reach the directory — not the same as finding nothing. */
    data object Unreachable : StationSearchState
}

/** Shortest query worth a request. */
private const val MIN_QUERY = 2

/** How long typing has to pause before the directory is asked. */
private const val SEARCH_DEBOUNCE_MS = 350L

/** What the panel knows about a city's stations right now. */
sealed interface StationsState {
    data object Idle : StationsState
    data object Loading : StationsState
    data class Ready(val stations: List<RadioStation>) : StationsState

    /**
     * The directory couldn't be reached. Deliberately distinct from an empty
     * [Ready]: "we couldn't ask" and "there is nothing here" are different
     * claims, and a city only reached this globe because it *did* have stations
     * when the asset was built.
     */
    data object Unreachable : StationsState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorldRadioViewModel @Inject constructor(
    private val repository: WorldRadioRepository,
    private val preferences: PreferencesManager,
) : ViewModel() {

    private val _globe = MutableStateFlow(WorldRadioData())

    /** Coastlines and every city that broadcasts. Empty until the asset loads. */
    val globe: StateFlow<WorldRadioData> = _globe.asStateFlow()

    private val _selected = MutableStateFlow<RadioCity?>(null)
    val selected: StateFlow<RadioCity?> = _selected.asStateFlow()

    private val _nearby = MutableStateFlow<List<RadioCity>>(emptyList())
    val nearby: StateFlow<List<RadioCity>> = _nearby.asStateFlow()

    /** Station uuids the listener has kept. */
    val favourites: StateFlow<Set<String>> = preferences.favouriteStations
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val _searchOpen = MutableStateFlow(false)

    /** Whether the search bar is showing. */
    val searchOpen: StateFlow<Boolean> = _searchOpen.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun toggleSearch() {
        val opening = !_searchOpen.value
        _searchOpen.value = opening
        // Closing clears the query. Leaving a stale one behind means re-opening
        // shows results for something typed minutes ago, under a bar that looks
        // like it is waiting for input.
        if (!opening) _query.value = ""
    }

    fun setQuery(value: String) {
        _query.value = value
    }

    /**
     * What the directory has for what is being typed.
     *
     * Debounced, because this is a network round-trip per keystroke otherwise
     * and the directory is a volunteer-run service. Short queries emit nothing
     * at all rather than asking for every station whose name contains "ra".
     */
    val suggestions: StateFlow<StationSearchState> = _query
        .map { it.trim() }
        .distinctUntilChanged()
        .transformLatest { typed ->
            if (typed.length < MIN_QUERY) {
                emit(StationSearchState.Idle)
                return@transformLatest
            }
            emit(StationSearchState.Searching)
            delay(SEARCH_DEBOUNCE_MS)
            val found = repository.searchStations(typed)
            emit(
                when {
                    found == null -> StationSearchState.Unreachable
                    else -> StationSearchState.Ready(found)
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StationSearchState.Idle)

    /**
     * Fly to a searched station and tune in.
     *
     * Selecting the city is what moves the camera — the screen already flies to
     * whatever is selected — so this is one action and not two: you land on the
     * place and hear it at the same time. A station the globe has no city for
     * still plays; it just plays without the journey.
     */
    fun playSearchResult(station: RadioStation, player: PlayerViewModel) {
        viewModelScope.launch {
            val city = repository.locate(station)
            if (city != null) select(city)
            player.playRadioStation(asTrack(station, city))
            runCatching { repository.reportPlay(station) }
        }
    }

    /** Live tuning for how the outlines are lit. */
    val globeFx: StateFlow<GlobeFxSettings> = preferences.globeFx
        .stateIn(viewModelScope, SharingStarted.Eagerly, GlobeFxSettings())

    fun updateGlobeFx(transform: (GlobeFxSettings) -> GlobeFxSettings) {
        viewModelScope.launch { preferences.setGlobeFx(transform(globeFx.value)) }
    }

    fun resetGlobeFx() {
        viewModelScope.launch { preferences.setGlobeFx(GlobeFxSettings()) }
    }

    val stations: StateFlow<StationsState> = _selected
        .map { it?.id }
        .distinctUntilChanged()
        .transformLatest { _ ->
            val city = _selected.value
            if (city == null) {
                emit(StationsState.Idle)
                return@transformLatest
            }
            emit(StationsState.Loading)
            val found = runCatching { repository.stations(city) }.getOrNull()
            emit(
                when {
                    found == null -> StationsState.Unreachable
                    else -> StationsState.Ready(found)
                },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), StationsState.Idle)

    init {
        viewModelScope.launch { _globe.value = repository.data() }
    }

    fun select(city: RadioCity?) {
        _selected.value = city
        _nearby.value = emptyList()
        if (city == null) return
        viewModelScope.launch { _nearby.value = repository.nearby(city) }
    }

    fun selectById(cityId: String) {
        select(_globe.value.cities.firstOrNull { it.id == cityId })
    }

    fun toggleFavourite(station: RadioStation) {
        viewModelScope.launch { preferences.toggleFavouriteStation(station.uuid) }
    }

    /**
     * Tune in.
     *
     * The station is turned into a [UnifiedTrack] here rather than in the
     * player, because the resolver runs on the main thread immediately before
     * the item is handed to ExoPlayer and must not go looking anything up. Every
     * fact it needs is already in hand by this point.
     */
    fun play(station: RadioStation, city: RadioCity, player: PlayerViewModel) {
        player.playRadioStation(asTrack(station, city))
        // The directory builds its popularity ordering — the order this very
        // panel sorts by — out of these. Fire and forget, after playback.
        viewModelScope.launch { runCatching { repository.reportPlay(station) } }
    }

    /**
     * Where a station is from, for the slot an artist would occupy.
     *
     * A station reached from a city knows exactly where it is. One reached from
     * the search bar may not — the globe has no city for every country — so it
     * falls back to the country code the directory gave, and to the station's
     * own name only when even that is blank. Never to an invented place.
     */
    private fun originOf(station: RadioStation, city: RadioCity?): String = when {
        city != null -> "${city.name}, ${city.country}"
        station.countryCode.isNotBlank() -> station.countryCode
        else -> "Live radio"
    }

    private fun asTrack(station: RadioStation, city: RadioCity?): UnifiedTrack = UnifiedTrack(
        id = "radio_${station.uuid}",
        title = station.name,
        // Where it broadcasts from, in the slot an artist would occupy. It is
        // the honest subtitle for a station, and it is what the notification and
        // the lock screen will show underneath the name.
        artistName = originOf(station, city),
        // A null id, so the city never renders as a tappable link to a
        // catalogue artist page that cannot exist.
        artists = listOf(UnifiedArtistRef(id = null, name = originOf(station, city))),
        // Zero rather than a guess: it is what marks this as live, and it is
        // what makes the Discord card omit its progress bar instead of drawing
        // a false one.
        durationSeconds = 0,
        artworkUri = station.favicon,
        bitRate = station.bitrate.takeIf { it > 0 },
        genre = station.topTags(1).firstOrNull(),
        source = PlaybackSource.RadioStream(
            stationUuid = station.uuid,
            url = station.url,
            isHls = station.isHls,
            codecName = station.codec,
            bitrateKbps = station.bitrate.takeIf { it > 0 },
        ),
        sourceType = SourceType.LIVE_RADIO,
    )
}