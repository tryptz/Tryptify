package tf.monochrome.android.ui.discover

import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.RecommendationSeed
import tf.monochrome.android.data.repository.RecommendationSeedsRepository
import tf.monochrome.android.domain.model.DiscoveryAdventure
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.domain.usecase.DiscoveryFeedUseCase
import tf.monochrome.android.domain.usecase.toUnifiedTrackAuto
import javax.inject.Inject

/**
 * The one [DiscoverViewModel] for the whole Discover feature, scoped to the
 * Activity.
 *
 * Discover lives in the tab pager while its "See All" screens are NavHost
 * destinations, so the default `hiltViewModel()` owner differs between them —
 * the grid would get its own ViewModel, rebuild the feed from scratch, and
 * could easily show a different set of records than the row the user tapped.
 * Both call this instead so they read the same feed.
 */
@Composable
fun rememberDiscoverViewModel(): DiscoverViewModel {
    val context = LocalContext.current
    val owner = remember(context) {
        generateSequence(context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<ViewModelStoreOwner>()
            .firstOrNull()
    }
    // firstOrNull, not first: a preview or test can be composed under a bare
    // context with no Activity in the chain, and falling back to the ambient
    // owner is a worse feed, not a crash.
    return if (owner != null) hiltViewModel(owner) else hiltViewModel()
}

/** What the hero card at the top of Discover is offering. */
data class DiscoveryHero(
    val title: String,
    val subtitle: String,
    val artworkUrl: String?,
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val discoveryFeed: DiscoveryFeedUseCase,
    private val seedsRepository: RecommendationSeedsRepository,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val preferences: PreferencesManager,
) : ViewModel() {

    /**
     * Mood/activity chips, plus the curated genres, in one rail.
     *
     * De-duplicated by label because both lists come from editable assets and
     * the rail keys its lazy row on the label: one "Jazz" in each file would be
     * two chips claiming one key, which Compose treats as a duplicate and
     * crashes on. It is also what the selection is looked up by, so a repeat
     * would make one of the two chips unselectable anyway.
     */
    val chips: List<RecommendationSeed> =
        (seedsRepository.moods() + seedsRepository.seeds()).distinctBy { it.label }

    // null = "For you", the personalized feed. Any other value is a chip label.
    private val _selectedChip = MutableStateFlow<String?>(null)
    val selectedChip: StateFlow<String?> = _selectedChip.asStateFlow()

    // Deliberately private: everything on screen reads `visibleShelves`, which
    // is this minus what the listener has waved away. A new surface wired to
    // the raw list would silently ignore dismissals.
    private val _shelves = MutableStateFlow<List<DiscoveryShelf>>(emptyList())

    private val _hero = MutableStateFlow<DiscoveryHero?>(null)
    val hero: StateFlow<DiscoveryHero?> = _hero.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * The familiar ↔ adventurous knob. Read live so the caption tracks the
     * finger, but the feed is only rebuilt when the drag ends — see
     * [setAdventure] / [commitAdventure].
     */
    val adventure: StateFlow<Float> = preferences.discoveryAdventure
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryAdventure.DEFAULT)

    private val _pendingAdventure = MutableStateFlow<Float?>(null)

    /** What the control should draw: the finger's position if dragging, else the stored value. */
    val adventureDisplay: StateFlow<Float> =
        combine(adventure, _pendingAdventure) { stored, pending -> pending ?: stored }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DiscoveryAdventure.DEFAULT)

    /**
     * Cards and shelves the listener has waved away this session.
     *
     * Deliberately in memory rather than in the database: this is a "not right
     * now", and the app has no negative-signal store to put a durable "never"
     * in. Held on the Activity-scoped ViewModel, so it survives moving between
     * Discover, a See All grid and the Flow feed, and is forgotten on restart.
     */
    private val _dismissedItems = MutableStateFlow<Set<String>>(emptySet())
    val dismissedItems: StateFlow<Set<String>> = _dismissedItems.asStateFlow()
    private val _dismissedShelves = MutableStateFlow<Set<String>>(emptySet())

    /** How many times "show me something else" has been tapped, rotating the seeds. */
    private var rotation = 0

    /**
     * The feed with dismissals applied. Everything on screen reads this rather
     * than [_shelves], so waving a card away takes effect everywhere at once —
     * the shelf, its See All grid, and the Flow feed.
     */
    val visibleShelves: StateFlow<List<DiscoveryShelf>> =
        combine(_shelves, _dismissedItems, _dismissedShelves) { shelves, items, hiddenShelves ->
            shelves
                .filterNot { it.id in hiddenShelves }
                .map { shelf -> shelf.copy(items = shelf.items.filterNot { it.key in items }) }
                .filter { it.items.isNotEmpty() }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Shelves fetched to keep the Flow feed going past the end of the page.
     *
     * Kept apart from [_shelves] so swiping deep into Flow doesn't silently
     * grow the Discover page behind it — the two surfaces share a feed, not a
     * scroll position.
     */
    private val _flowExtra = MutableStateFlow<List<DiscoveryShelf>>(emptyList())
    private var extendJob: Job? = null

    /**
     * Every track in the feed, in feed order, de-duplicated — the supply the
     * Flow feed swipes through and the queue it plays into.
     */
    val flowTracks: StateFlow<List<UnifiedTrack>> =
        combine(visibleShelves, _flowExtra, _dismissedItems) { shelves, extra, dismissed ->
            (shelves + extra)
                .flatMap { it.items }
                .filterIsInstance<DiscoveryItem.TrackItem>()
                .filterNot { it.key in dismissed }
                .map { it.track }
                .distinctBy { it.id }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The reason line for a track, so the Flow feed can say why it's showing it. */
    fun reasonFor(track: UnifiedTrack): String? =
        (_shelves.value + _flowExtra.value).firstOrNull { shelf ->
            shelf.items.any { it is DiscoveryItem.TrackItem && it.track.id == track.id }
        }?.reason

    /**
     * Fetch more for Flow, called as the listener approaches the end.
     *
     * Each call takes the next chip in rotation, so the tail of a long session
     * drifts through moods rather than looping the same page — the format only
     * works if there is always a next one, but "always" shouldn't mean "the
     * same twelve records again".
     */
    fun extendFlow() {
        if (extendJob?.isActive == true || chips.isEmpty()) return
        extendJob = viewModelScope.launch {
            val seed = chips[(extendRotation++ % chips.size + chips.size) % chips.size]
            runCatching { discoveryFeed.buildForQuery(seed.label, seed.query, SHELF_SIZE) }
                .getOrDefault(emptyList())
                .let { built ->
                    val known = (_shelves.value + _flowExtra.value).map { it.id }.toSet()
                    _flowExtra.value = _flowExtra.value + built.filterNot { it.id in known }
                }
        }
    }

    private var extendRotation = 0

    // Switching chips fast would otherwise leave two builds racing to write the
    // same list, and the slower one wins whichever chip the user is looking at.
    private var feedJob: Job? = null

    init {
        loadHero()
        selectChip(null)
    }

    fun selectChip(label: String?) {
        _selectedChip.value = label
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            _loading.value = true
            _shelves.value = emptyList()
            // The tail was fetched to continue a feed that no longer exists.
            _flowExtra.value = emptyList()
            try {
                val built = if (label == null) {
                    buildForYou()
                } else {
                    val seed = chips.firstOrNull { it.label == label }
                    if (seed == null) emptyList()
                    else discoveryFeed.buildForQuery(seed.label, seed.query)
                }
                // The feed keys its lazy list on the shelf id, and ids are
                // derived from what came back rather than from a counter: two
                // seed artist names ("AFX", "Aphex Twin") can resolve to the
                // same Qobuz artist and produce two `similar_to_<id>` shelves.
                // Duplicate keys crash Compose, so the last line of defence is
                // here, where the whole feed is in one place.
                _shelves.value = built.distinctBy { it.id }
            } catch (cancelled: CancellationException) {
                // A newer chip took over. Rethrow rather than swallow, and in
                // particular do NOT clear the loading flag on the way out —
                // that belongs to the build that replaced this one.
                throw cancelled
            } catch (_: Exception) {
                // An unreachable instance shows the empty state, not a crash.
            }
            // Deliberately not a `finally`: cancellation runs that too, so a
            // fast second tap had the outgoing build clear the flag out from
            // under the incoming one, and the feed showed "nothing came back"
            // while it was still fetching.
            _loading.value = false
        }
    }

    fun refresh() = selectChip(_selectedChip.value)

    /**
     * Deal a different hand. Rotates the seed offset and rebuilds, so the same
     * setting of the knob doesn't keep producing the same page.
     */
    fun showSomethingElse() {
        rotation++
        // Dismissals are a judgement on what was on screen, not on the feed as
        // a whole — clearing them here is what makes this "something else"
        // rather than "the same thing minus what I rejected".
        _dismissedShelves.value = emptySet()
        _dismissedItems.value = emptySet()
        _flowExtra.value = emptyList()
        selectChip(_selectedChip.value)
    }

    /** Live drag: move the caption without touching the feed or the store. */
    fun setAdventure(value: Float) {
        _pendingAdventure.value = DiscoveryAdventure.clamp(value)
    }

    /**
     * Drag released: persist, fold the value into the planner weights, rebuild.
     *
     * Split from [setAdventure] because writing on every frame of a drag would
     * be a DataStore write and a fan-out of Qobuz searches per pixel. The
     * Studio's sliders debounce to the drag tail the same way.
     */
    fun commitAdventure(value: Float) {
        val target = DiscoveryAdventure.clamp(value)
        _pendingAdventure.value = target
        viewModelScope.launch {
            preferences.setDiscoveryAdventure(target)
            _pendingAdventure.value = null
            selectChip(_selectedChip.value)
        }
    }

    /** Wave one card away. */
    fun dismissItem(key: String) {
        _dismissedItems.value = _dismissedItems.value + key
    }

    fun undismissItem(key: String) {
        _dismissedItems.value = _dismissedItems.value - key
    }

    /** Wave a whole shelf away. */
    fun dismissShelf(id: String) {
        _dismissedShelves.value = _dismissedShelves.value + id
    }

    /**
     * The personalized feed, with the listener's own favourites pinned to the
     * front. Favourites come from the local database rather than the network,
     * so the page has something real on it before any Qobuz call returns.
     */
    private suspend fun buildForYou(): List<DiscoveryShelf> {
        val favourites = libraryRepository.getFavoriteTracks().first()
            .take(SHELF_SIZE)
            .map { DiscoveryItem.TrackItem(it.toUnifiedTrackAuto(qobuzIdRegistry)) }
            // Same reason as DiscoveryFeedUseCase.toShelf: the row is keyed on
            // the card key, and a duplicate key crashes the list.
            .distinctBy { it.key }

        val favouritesShelf = favourites.takeIf { it.isNotEmpty() }?.let {
            DiscoveryShelf(
                id = "favorites",
                title = "From your favorites",
                reason = "Tracks you hearted",
                items = it,
            )
        }

        return listOfNotNull(favouritesShelf) + discoveryFeed.build(
            adventure = _pendingAdventure.value ?: adventure.value,
            itemsPerShelf = SHELF_SIZE,
            rotation = rotation,
        )
    }

    private fun loadHero() {
        viewModelScope.launch {
            runCatching {
                // The most-played artist is the safest thing to build a mix
                // around: it is the one recommendation that needs no
                // explanation beyond the listener's own history.
                val topArtist = libraryRepository.getSeedArtistNames(1).firstOrNull()
                val recent = libraryRepository.getHistory().first().firstOrNull()
                if (topArtist != null) {
                    _hero.value = DiscoveryHero(
                        title = "$topArtist mix",
                        subtitle = "Built from an artist you keep coming back to",
                        artworkUrl = recent?.coverUrl,
                    )
                } else if (recent != null) {
                    _hero.value = DiscoveryHero(
                        title = "Pick up where you left off",
                        subtitle = recent.title,
                        artworkUrl = recent.coverUrl,
                    )
                }
            }
        }
    }

    private companion object {
        const val SHELF_SIZE = 12
    }
}
