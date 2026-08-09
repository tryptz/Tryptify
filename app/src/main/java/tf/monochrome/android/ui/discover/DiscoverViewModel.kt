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
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.RecommendationSeed
import tf.monochrome.android.data.repository.RecommendationSeedsRepository
import tf.monochrome.android.domain.model.DiscoveryAdventure
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
import tf.monochrome.android.domain.model.DiscoverySort
import tf.monochrome.android.domain.model.GenreGraph
import tf.monochrome.android.domain.model.GenreNode
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

/** One genre on Discover's genre rail, and why it's there. */
data class GenreRailItem(val node: GenreNode, val hearted: Boolean)

/**
 * Index in the chip rail from which chips start combining.
 *
 * The first three are the listener's own entry points — a single choice about
 * whose taste the page follows, which does not compose. Moods begin after them
 * and are the only chips that combine.
 */
const val COMBINABLE_FROM = 3

/** How many contributing genres the subtract control offers. */
const val COMBINED_GENRE_LIMIT = 14

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val discoveryFeed: DiscoveryFeedUseCase,
    private val seedsRepository: RecommendationSeedsRepository,
    private val qobuzIdRegistry: QobuzIdRegistry,
    private val preferences: PreferencesManager,
    private val genreGraphRepo: GenreGraphRepository,
    private val genreSearch: tf.monochrome.android.domain.usecase.GenreSearchUseCase,
    private val genreCharts: tf.monochrome.android.domain.usecase.GenreChartUseCase,
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
    val chips: List<RecommendationSeed> = buildList {
        // The graph's moods lead, because those are the ones that expand into
        // real genre shelves rather than a single opaque search. The asset
        // seeds follow as a tail — and as the whole list if the graph failed to
        // load, which is the pre-graph behaviour, unchanged.
        for (mood in genreGraphRepo.graph.moods) add(RecommendationSeed(mood.label, mood.id))
        addAll(seedsRepository.moods())
        addAll(seedsRepository.seeds())
    }.distinctBy { it.label }

    /** Chip labels, by graph mood id — the reverse of [moodIdByLabel]. */
    private val labelByMoodId: Map<String, String> =
        genreGraphRepo.graph.moods.associate { it.id to it.label }

    /** Graph mood ids, by the chip label that selects them. */
    private val moodIdByLabel: Map<String, String> =
        genreGraphRepo.graph.moods.associate { it.label to it.id }

    // null = "For you", the personalized feed. Any other value is a chip label.
    private val _selectedChip = MutableStateFlow<String?>(null)
    val selectedChip: StateFlow<String?> = _selectedChip.asStateFlow()

    /**
     * Mood ids currently combined, in the order they were picked.
     *
     * Empty means no combination is active and [selectedChip] decides the page.
     * Only chips from [COMBINABLE_FROM] onward can enter this set: the rail
     * opens with the listener's own entry points, which are a single choice
     * about whose taste the page follows and don't compose with each other.
     */
    private val _selectedMoods = MutableStateFlow<List<String>>(emptyList())
    val selectedMoods: StateFlow<List<String>> = _selectedMoods.asStateFlow()

    /**
     * Genres subtracted from the current combination.
     *
     * Cleared whenever the combination changes, because a subtraction only
     * means anything against the pool it was made from — carrying "no gabber"
     * from Workout across into Wind down would silently narrow a page the
     * listener never applied it to. Session-scoped and unsaved, matching how
     * the existing "Not interested" dismissals already behave.
     */
    private val _excludedGenres = MutableStateFlow<Set<String>>(emptySet())
    val excludedGenres: StateFlow<Set<String>> = _excludedGenres.asStateFlow()

    /** The genres feeding the current combination, so the UI can offer them for removal. */
    val combinedGenres: StateFlow<List<tf.monochrome.android.domain.model.GenreNode>> =
        combine(_selectedMoods, _excludedGenres) { moods, excluded ->
            if (moods.size < 2) emptyList()
            else genreGraphRepo.graph
                .genresForMoods(moods, excluded, maxHops = 0, limit = COMBINED_GENRE_LIMIT)
                .map { it.node }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Deliberately private: everything on screen reads `visibleShelves`, which
    // is this minus what the listener has waved away. A new surface wired to
    // the raw list would silently ignore dismissals.
    private val _shelves = MutableStateFlow<List<DiscoveryShelf>>(emptyList())

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    /**
     * Set only by [refresh]. The pull-to-refresh indicator reads this rather
     * than [loading], which is also raised by the first build, by every chip
     * tap and by releasing the adventure slider — a spinner that appears when
     * you tapped something says the wrong thing about the gesture you made.
     */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

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

    // ── Sorting ─────────────────────────────────────────────────────────

    val sort: StateFlow<DiscoverySort> = preferences.discoverySort
        .map { DiscoverySort.fromId(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, DiscoverySort.DEFAULT)

    fun setSort(value: DiscoverySort) {
        viewModelScope.launch { preferences.setDiscoverySort(value.id) }
    }

    /**
     * Lower-cased names of the artists the listener actually plays, for the
     * "For you" ordering.
     *
     * Compared by name rather than by id because a shelf mixes sources and only
     * some of them carry a catalogue artist id — an id match would silently
     * order half the feed as though nothing were familiar.
     */
    private val _familiarArtists = MutableStateFlow<Set<String>>(emptySet())
    private val familiarArtists: StateFlow<Set<String>> = _familiarArtists.asStateFlow()

    init {
        viewModelScope.launch {
            _familiarArtists.value = runCatching {
                libraryRepository.getSeedArtistNames(FAMILIAR_ARTISTS)
                    .map { it.lowercase() }
                    .toSet()
            }.getOrDefault(emptySet())
        }
    }

    // ── The genre rail ──────────────────────────────────────────────────

    /**
     * Genres hearted on the map, and genres recently played from it.
     *
     * Hearted first and marked as such, then recents that aren't already
     * hearted — a genre you both hearted and played last night should appear
     * once, in the row that means something. Resolved through the graph so a
     * stale id from an older dataset simply disappears instead of rendering as
     * a blank chip.
     */

    /** What the listener has typed into the genre search. */
    private val _genreQuery = MutableStateFlow("")
    val genreQuery: StateFlow<String> = _genreQuery.asStateFlow()

    /**
     * The genres offered right now.
     *
     * With nothing typed this is the listener's own — hearted on the map, then
     * recently played — because that is the row they had before and the one
     * most likely to be what they want. The moment anything is typed it becomes
     * the search, so the row is a way into all 771 genres rather than a fixed
     * shortlist of the handful already visited.
     */
    val genreRail: StateFlow<List<GenreRailItem>> =
        combine(
            preferences.discoveryHeartedGenres,
            preferences.discoveryRecentGenres,
            _genreQuery,
        ) { hearted, recent, query ->
            val graph = genreGraphRepo.graph
            if (query.isBlank()) {
                val heartedNodes = hearted.mapNotNull { graph[it] }.sortedBy { it.name }
                    .map { GenreRailItem(it, hearted = true) }
                val recentNodes = recent.filterNot { it in hearted }.mapNotNull { graph[it] }
                    .map { GenreRailItem(it, hearted = false) }
                (heartedNodes + recentNodes).distinctBy { it.node.id }
            } else {
                genreSearch.search(query).map { GenreRailItem(it, hearted = it.id in hearted) }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setGenreQuery(query: String) {
        _genreQuery.value = query
    }

    val heartedGenres: StateFlow<Set<String>> = preferences.discoveryHeartedGenres
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    fun toggleHeartGenre(genreId: String) {
        viewModelScope.launch { preferences.toggleHeartedGenre(genreId) }
    }

    private fun noteGenreVisited(genreId: String) {
        viewModelScope.launch { preferences.noteGenreVisited(genreId) }
    }

    // ── Paging ──────────────────────────────────────────────────────────

    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore.asStateFlow()

    /**
     * True once a page has come back with nothing new.
     *
     * The feed is meant to feel bottomless, but a catalogue is not, and a list
     * that keeps firing a request every time you reach the end of the same
     * shelves is worse than one that admits it has run out.
     */
    private val _exhausted = MutableStateFlow(false)
    val exhausted: StateFlow<Boolean> = _exhausted.asStateFlow()

    private var page = 0
    private var moreJob: Job? = null

    /**
     * The feed with dismissals applied. Everything on screen reads this rather
     * than [_shelves], so waving a card away takes effect everywhere at once —
     * the shelf, its See All grid, and the Flow feed.
     */
    val visibleShelves: StateFlow<List<DiscoveryShelf>> =
        combine(_shelves, _dismissedItems, _dismissedShelves, sort, familiarArtists) {
            shelves, items, hiddenShelves, order, familiar ->
            val kept = shelves
                .filterNot { it.id in hiddenShelves }
                .map { shelf -> shelf.copy(items = shelf.items.filterNot { it.key in items }) }
                .filter { it.items.isNotEmpty() }
            // Sorting last, and here rather than in the builder: it reorders
            // what has already been fetched, so changing it is instant and
            // costs no round trip. Qobuz ranks a genre search one way and one
            // way only — "the newest drum & bass" is not a question it answers.
            DiscoverySort.apply(kept, order, familiar)
            // Eagerly for the same reason as flowTracks below: a See All grid
            // reached on a restored back stack would otherwise read the initial
            // empty list and report the shelf missing.
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            // Eagerly, not WhileSubscribed: nothing subscribes until the Flow
            // screen composes, so a lazy share would hand it the initial empty
            // list on its first frame and flash "nothing to flow through" over
            // a feed that was already built. The data is in memory either way.
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
        if (extendJob?.isActive == true || chips.isEmpty() || flowExhausted) return
        extendJob = viewModelScope.launch {
            val seed = chips[(extendRotation++ % chips.size + chips.size) % chips.size]
            val built = try {
                discoveryFeed.buildForQuery(seed.label, seed.query, SHELF_SIZE)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            val known = (_shelves.value + _flowExtra.value).map { it.id }.toSet()
            val fresh = built.filterNot { it.id in known }
            _flowExtra.value = _flowExtra.value + fresh
            // Every chip's shelves carry a fixed id, so once a full rotation
            // adds nothing the well is dry. Without this the listener sitting
            // near the end fires a 7-second Qobuz search on every settle, for
            // ever, and never gets a card out of it.
            if (fresh.isEmpty()) {
                dryRounds++
                if (dryRounds >= chips.size) flowExhausted = true
            } else {
                dryRounds = 0
            }
        }
    }
    private var dryRounds = 0
    private var flowExhausted = false

    private var extendRotation = 0

    // Switching chips fast would otherwise leave two builds racing to write the
    // same list, and the slower one wins whichever chip the user is looking at.
    private var feedJob: Job? = null

    init {
        selectChip(null)
    }

    fun selectChip(label: String?) {
        // Picking a single chip ends any combination in progress.
        _selectedMoods.value = emptyList()
        _excludedGenres.value = emptySet()
        rebuild(label = label)
    }

    /**
     * Add or remove a mood from the combination.
     *
     * Toggling the last one off returns to "For you" rather than leaving the
     * page showing whatever the combination happened to produce last.
     */
    fun toggleMood(label: String) {
        val moodId = moodIdByLabel[label] ?: return
        val next = _selectedMoods.value.toMutableList()
        if (!next.remove(moodId)) next.add(moodId)
        _selectedMoods.value = next
        // Any change to the combination invalidates the subtractions made
        // against it — see the note on [_excludedGenres].
        _excludedGenres.value = emptySet()
        rebuild(label = if (next.isEmpty()) null else _selectedChip.value)
    }

    /** Chip labels for a set of mood ids, so the rail can light them up. */
    fun labelsForMoods(moodIds: List<String>): Set<String> =
        moodIds.mapNotNullTo(mutableSetOf()) { id -> labelByMoodId[id] }

    /** Drop a genre from the combined page. */
    fun subtractGenre(genreId: String) {
        if (_selectedMoods.value.size < 2) return
        _excludedGenres.value = _excludedGenres.value + genreId
        rebuild()
    }

    /** Put every subtracted genre back. */
    fun clearSubtractions() {
        if (_excludedGenres.value.isEmpty()) return
        _excludedGenres.value = emptySet()
        rebuild()
    }

    private fun rebuild(
        label: String? = _selectedChip.value,
        // Named to avoid shadowing the `adventure` StateFlow above — the
        // override is the value a just-released slider committed, which the
        // StateFlow mirror may not have caught up to yet.
        adventureOverride: Float? = null,
    ) {
        _selectedChip.value = label
        feedJob?.cancel()
        moreJob?.cancel()
        page = 0
        _exhausted.value = false
        _loadingMore.value = false
        feedJob = viewModelScope.launch {
            _loading.value = true
            _shelves.value = emptyList()
            // The tail was fetched to continue a feed that no longer exists.
            _flowExtra.value = emptyList()
            try {
                val moods = _selectedMoods.value
                val knobForMoods = adventureOverride ?: _pendingAdventure.value ?: adventure.value
                val built = if (moods.isNotEmpty()) {
                    discoveryFeed.buildForMoods(
                        moodIds = moods,
                        excluded = _excludedGenres.value,
                        adventure = knobForMoods,
                        itemsPerShelf = SHELF_SIZE,
                    )
                } else if (label == null) {
                    buildForYou(adventureOverride)
                } else {
                    // A genre picked on the map. Matched by name so that
                    // switching to a chip clears it, rather than the map's
                    // choice silently outliving the label on screen.
                    val genreId = _selectedGenreId.value
                        ?.takeIf { genreGraphRepo.graph[it]?.name == label }
                    val moodId = moodIdByLabel[label]
                    val seed = chips.firstOrNull { it.label == label }
                    val knob = adventureOverride ?: _pendingAdventure.value ?: adventure.value
                    when {
                        genreId != null -> discoveryFeed.buildForGenre(
                            genreId = genreId,
                            adventure = knob,
                            itemsPerShelf = SHELF_SIZE,
                        )
                        // A graph mood: expands into one shelf per genre, each
                        // with its own tempo and a reason in the mood's terms.
                        moodId != null -> discoveryFeed.buildForMood(
                            moodId = moodId,
                            adventure = knob,
                            itemsPerShelf = SHELF_SIZE,
                        ).ifEmpty {
                            // The graph knew the mood but the catalogue had
                            // nothing for it — fall back to the flat search
                            // rather than showing an empty page.
                            seed?.let { discoveryFeed.buildForQuery(it.label, it.query) }.orEmpty()
                        }
                        seed != null -> discoveryFeed.buildForQuery(seed.label, seed.query)
                        else -> emptyList()
                    }
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
            _refreshing.value = false
        }
    }

    /**
     * Fetch the next page of the current category and append it.
     *
     * Called when the list is nearly scrolled out rather than on a timer or a
     * button: the feed should feel like it has no end, and the only honest
     * moment to spend a network round trip on more of it is when the listener
     * has actually reached the bottom of what's already there.
     *
     * A page that comes back with nothing new sets [exhausted] and stops the
     * loop — genuinely infinite would mean re-requesting the same shelves for
     * as long as the screen is open.
     */
    fun loadMore() {
        if (_loading.value || _loadingMore.value || _exhausted.value) return
        if (moreJob?.isActive == true) return
        val label = _selectedChip.value
        moreJob = viewModelScope.launch {
            _loadingMore.value = true
            val next = page + 1
            val built = try {
                buildPage(label, next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            val known = _shelves.value.map { it.id }.toSet()
            val fresh = built.distinctBy { it.id }.filterNot { it.id in known }
            if (fresh.isEmpty()) {
                _exhausted.value = true
            } else {
                page = next
                _shelves.value = _shelves.value + fresh
            }
            _loadingMore.value = false
        }
    }

    /**
     * One page of whichever category is showing.
     *
     * Page 0 is the initial build; every page after it walks further out — into
     * the genre graph for a mood or a genre, and into the seed-artist list for
     * the personalized feed.
     */
    private suspend fun buildPage(label: String?, page: Int): List<DiscoveryShelf> {
        val knob = _pendingAdventure.value ?: adventure.value
        if (label == null) {
            // "For you" has no graph to walk, so it rotates the curated seeds
            // and the artist window instead — the same mechanism "show me
            // something else" uses, driven by depth rather than by a tap.
            return discoveryFeed.build(
                adventure = knob,
                itemsPerShelf = SHELF_SIZE,
                rotation = rotation + page * PAGE_ROTATION,
            )
        }
        val genreId = _selectedGenreId.value
            ?.takeIf { genreGraphRepo.graph[it]?.name == label }
        val moodId = moodIdByLabel[label]
        return when {
            genreId != null -> discoveryFeed.buildForGenre(
                genreId = genreId,
                adventure = knob,
                itemsPerShelf = SHELF_SIZE,
                page = page,
            )
            moodId != null -> discoveryFeed.buildForMood(
                moodId = moodId,
                adventure = knob,
                itemsPerShelf = SHELF_SIZE,
                page = page,
            )
            // A plain seed chip is one search with no graph behind it, so there
            // is no second page to fetch — saying so beats firing the same
            // query again and quietly discarding the answer.
            else -> emptyList()
        }
    }

    /** The map reads the graph straight off the repository. */
    val genreGraph: GenreGraph get() = genreGraphRepo.graph

    private val _mapSelection = MutableStateFlow<GenreNode?>(null)
    val mapSelection: StateFlow<GenreNode?> = _mapSelection.asStateFlow()

    fun selectOnMap(genreId: String?) {
        _mapSelection.value = genreId?.let { genreGraphRepo.graph[it] }
    }

    /**
     * How many times a genre has been played from the map this session.
     *
     * Two taps on the same genre used to be two identical queues, in the same
     * order, starting on the same song — which reads as the map being broken
     * rather than as the catalogue being stable. The counter walks the genre's
     * aliases so the underlying search differs too, not just the shuffle.
     */
    private val genrePlays = HashMap<String, Int>()

    /**
     * What "play this genre" should actually queue.
     *
     * The genre's chart comes first, in rank order, so tapping hard techno
     * starts on the record hard techno listeners actually play. The old path —
     * straight to a catalogue search for the genre's name — ranked by how well
     * a *title or album* matched the words "hard techno", which is exactly the
     * query machine-generated filler is built to win. A genre is a property of
     * the music, not a substring of its title.
     *
     * The search pool stays as the fallback for genres no chart source has
     * heard of, where a rough answer still beats silence.
     */
    private suspend fun genreQueue(genreId: String): List<UnifiedTrack> {
        val charted = runCatching { genreCharts.playablePool(genreId) }
            .getOrDefault(emptyList())
        return charted.ifEmpty { genrePool(genreId) }
    }

    /** Every track the catalogue will give us for a genre, in a fresh order each time. */
    private suspend fun genrePool(genreId: String): List<UnifiedTrack> {
        val variation = genrePlays.merge(genreId, 1, Int::plus)!! - 1
        val shelves = runCatching {
            discoveryFeed.buildForGenre(
                genreId = genreId,
                adventure = _pendingAdventure.value ?: adventure.value,
                // A deep single shelf, not several shallow ones: "play this
                // genre" should stay inside the genre, and a wide pool is what
                // makes shuffling it worth anything.
                itemsPerShelf = 40,
                maxShelves = 1,
                variation = variation,
            )
        }.getOrDefault(emptyList())
        return shelves
            .flatMap { it.items }
            .filterIsInstance<DiscoveryItem.TrackItem>()
            .map { it.track }
            .distinctBy { it.id }
            .shuffled()
    }

    /**
     * Play a genre straight from the map.
     *
     * Searches the catalogue for the genre and plays what comes back in a
     * shuffled order, queueing the rest — the same path Flow uses, so leaving
     * the map keeps the music going. This is the "quickly listen to" half of
     * the map; [selectGenre] is the "look into it properly" half.
     */
    fun playGenre(genreId: String, player: tf.monochrome.android.ui.player.PlayerViewModel) {
        val node = genreGraphRepo.graph[genreId] ?: return
        noteGenreVisited(genreId)
        viewModelScope.launch {
            val tracks = genreQueue(genreId)
            // Silence rather than a wrong track: if the catalogue has nothing
            // for this genre there is nothing honest to play.
            tracks.firstOrNull()?.let { player.playUnifiedTrack(it, tracks) }
            // The map stays open, so the panel should reflect what's playing.
            _mapSelection.value = node
        }
    }

    /**
     * Start a radio station from a genre.
     *
     * Seeds the existing station machinery from a random track of that genre
     * and hands over: the station then expands through neighbouring artists
     * and keeps refilling, which is the difference between this and [playGenre]
     * — that plays a finite page of search results, this doesn't end.
     */
    fun radioGenre(genreId: String, player: tf.monochrome.android.ui.player.PlayerViewModel) {
        val node = genreGraphRepo.graph[genreId] ?: return
        noteGenreVisited(genreId)
        viewModelScope.launch {
            val tracks = genreQueue(genreId).take(RADIO_OPENING)
            val seed = tracks.firstOrNull() ?: return@launch
            // A short opening run rather than the whole pool. The station is
            // seeded from one track and then follows *that* track's neighbours,
            // so a handful of genre tracks in front keeps the first stretch
            // sounding like the genre you asked for instead of like whichever
            // artist happened to come up first. The planner treats what's
            // already queued as seen, so it appends rather than repeating them.
            player.playUnifiedTrack(seed, tracks)
            player.startRadioFrom(seed.toLegacyTrack())
            _mapSelection.value = node
        }
    }

    /**
     * Hand a genre to the Discover feed — the map's other exit.
     *
     * Rebuilds the page around that genre and its graph neighbours, and lights
     * the chip rail on the genre's own name so the page says what it is
     * showing rather than silently changing under the listener.
     */
    fun selectGenre(genreId: String) {
        val node = genreGraphRepo.graph[genreId] ?: return
        noteGenreVisited(genreId)
        _selectedGenreId.value = genreId
        rebuild(label = node.name)
    }

    private val _selectedGenreId = MutableStateFlow<String?>(null)

    fun refresh() {
        _refreshing.value = true
        selectChip(_selectedChip.value)
    }

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
        adventureCommit++
        val token = adventureCommit
        viewModelScope.launch {
            // A DataStore write can fail (disk full, corrupt file). Left
            // uncaught it escapes viewModelScope and takes the app down, and
            // the knob would stay stuck at a value that was never stored.
            runCatching { preferences.setDiscoveryAdventure(target) }
            // Only the newest commit may clear the pending value. Without the
            // token, a slow write finishing after the user has grabbed the
            // slider again would snap the thumb and caption back mid-drag, and
            // the next release would commit that restored value instead of
            // where the finger actually is.
            if (token == adventureCommit) _pendingAdventure.value = null
            // Passed explicitly rather than re-read: `adventure` mirrors the
            // DataStore flow through a StateFlow, and nothing orders that
            // emission ahead of the rebuild reaching the value.
            rebuild(adventureOverride = target)
        }
    }

    private var adventureCommit = 0

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
    private suspend fun buildForYou(adventureOverride: Float? = null): List<DiscoveryShelf> {
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
            adventure = adventureOverride ?: _pendingAdventure.value ?: adventure.value,
            itemsPerShelf = SHELF_SIZE,
            rotation = rotation,
        )
    }

    private companion object {
        const val SHELF_SIZE = 12

        /** Genre tracks played ahead of a station, to anchor it in the genre. */
        const val RADIO_OPENING = 6

        /** How many library artists count as "familiar" for the For you sort. */
        const val FAMILIAR_ARTISTS = 60

        /** Seed rotation per page of the personalized feed. */
        const val PAGE_ROTATION = 3
    }
}
