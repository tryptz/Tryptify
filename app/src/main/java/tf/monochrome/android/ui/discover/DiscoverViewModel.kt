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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.RecommendationSeed
import tf.monochrome.android.data.repository.RecommendationSeedsRepository
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
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

    private val _shelves = MutableStateFlow<List<DiscoveryShelf>>(emptyList())
    val shelves: StateFlow<List<DiscoveryShelf>> = _shelves.asStateFlow()

    private val _hero = MutableStateFlow<DiscoveryHero?>(null)
    val hero: StateFlow<DiscoveryHero?> = _hero.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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

        return listOfNotNull(favouritesShelf) + discoveryFeed.build(itemsPerShelf = SHELF_SIZE)
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
