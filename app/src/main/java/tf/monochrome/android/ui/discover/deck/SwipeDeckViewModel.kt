package tf.monochrome.android.ui.discover.deck

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.data.discover.SwipeDeckStore
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.DeckCard
import tf.monochrome.android.domain.model.SwipeDeck
import tf.monochrome.android.domain.usecase.DiscoveryCatalogs
import tf.monochrome.android.domain.usecase.SwipeDeckUseCase
import java.time.LocalDate
import javax.inject.Inject

/** What the deck screen shows. */
sealed interface DeckUiState {
    data object Dealing : DeckUiState

    /** Nothing could be dealt: no service reachable, or nothing new left. */
    data object Empty : DeckUiState

    data class Swiping(val cards: List<DeckCard>, val position: Int, val kept: Int) : DeckUiState {
        val current: DeckCard? get() = cards.getOrNull(position)
        val next: DeckCard? get() = cards.getOrNull(position + 1)
    }

    data class Done(val dealt: Int, val kept: Int, val keptCards: List<DeckCard>) : DeckUiState
}

/** One swipe, as undo needs to know it. [hearted] says whether this swipe added the heart. */
private class Swipe(val card: DeckCard, val kept: Boolean, val hearted: Deferred<Boolean>?)

/**
 * Swipe to discover: today's stack, one card at a time.
 *
 * A right swipe hearts the song — it lands in Liked songs, and liked songs are
 * what the feed and tomorrow's stack are seeded from, so keeping is also
 * teaching. A left swipe keeps it out of the deck for thirty days.
 *
 * The deck lives here in memory and the screen follows that copy, so a swipe
 * moves the card the instant it is made; the file behind it is written after.
 * Leaving halfway and coming back picks up on the same card.
 */
@HiltViewModel
class SwipeDeckViewModel @Inject constructor(
    private val store: SwipeDeckStore,
    private val deck: SwipeDeckUseCase,
    private val catalogs: DiscoveryCatalogs,
    private val preferences: PreferencesManager,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<DeckUiState>(DeckUiState.Dealing)
    val state: StateFlow<DeckUiState> = _state.asStateFlow()

    /** The deck as this screen holds it; the store is written from it. */
    private var local = SwipeDeckStore.State()

    /** Swipes this visit, newest last, for undo. */
    private val history = ArrayList<Swipe>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    private val _savedPlaylist = MutableStateFlow(false)
    val savedPlaylist: StateFlow<Boolean> = _savedPlaylist.asStateFlow()

    /** The service the stack is dealt from, for the empty message. */
    val serviceLabel: StateFlow<String> = catalogs.selected
        .map { it.label }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    init {
        viewModelScope.launch { open() }
    }

    private fun today(): Long = LocalDate.now().toEpochDay()

    /** Today's stack as stored, or a new one when there is none for today on this service. */
    private suspend fun open() {
        val service = catalogs.currentService().name
        val stored = store.load()
        if (stored.day == today() && stored.service == service && stored.cards.isNotEmpty()) {
            local = stored
            publish()
        } else {
            deal(round = 0, sameDay = false)
        }
    }

    /** Deals a stack: today's first, or another round of today's when [sameDay]. */
    private suspend fun deal(round: Int, sameDay: Boolean) {
        _state.value = DeckUiState.Dealing
        val day = today()
        val service = catalogs.currentService()
        val before = store.load()
        val anchors = (preferences.discoveryHeartedGenres.first().sorted() +
            preferences.discoveryRecentGenres.first()).distinct()
        val todayGenre = preferences.discoveryTodayPick.first()?.takeIf { it.first == day }?.second

        val skips = SwipeDeck.liveSkips(before.skipped, day)
        val exclude = deck.likedKeys() + skips.keys + (if (sameDay) before.dealtToday else emptyList())
        val cards = SwipeDeck.deal(deck.gather(todayGenre, anchors, round), exclude)

        history.clear()
        _canUndo.value = false
        commit(
            before.copy(
                day = day,
                service = service.name,
                cards = cards,
                position = 0,
                kept = if (sameDay) before.kept else emptyList(),
                dealtToday = (if (sameDay) before.dealtToday else emptyList()) + cards.map { it.key },
                round = round,
                skipped = skips,
            ),
        )
    }

    /** Shows [next] now, and writes it behind. */
    private fun commit(next: SwipeDeckStore.State) {
        local = next
        publish()
        viewModelScope.launch { store.update { next } }
    }

    private fun publish() {
        val state = local
        val keptIds = state.kept.toSet()
        _state.value = when {
            state.cards.isEmpty() -> DeckUiState.Empty
            state.position >= state.cards.size -> {
                val keptCards = state.cards.filter { it.track.id in keptIds }
                DeckUiState.Done(state.cards.size, keptCards.size, keptCards)
            }
            else -> DeckUiState.Swiping(
                cards = state.cards,
                position = state.position,
                kept = state.cards.take(state.position).count { it.track.id in keptIds },
            )
        }
    }

    /** Right: heart it, and move on. */
    fun keep() = swipe(kept = true)

    /** Left: out of the deck for thirty days, and move on. */
    fun skip() = swipe(kept = false)

    private fun swipe(kept: Boolean) {
        val card = (_state.value as? DeckUiState.Swiping)?.current ?: return
        val hearting = if (kept) viewModelScope.async { heart(card) } else null
        history += Swipe(card, kept, hearting)
        _canUndo.value = true
        commit(
            local.copy(
                position = (local.position + 1).coerceAtMost(local.cards.size),
                kept = if (kept) local.kept + card.track.id else local.kept,
                skipped = if (kept) local.skipped else local.skipped + (card.key to today()),
            ),
        )
    }

    /** Hearts [card]'s song unless it already is; true when this added the heart. */
    private suspend fun heart(card: DeckCard): Boolean {
        val legacy = card.track.toLegacyTrack()
        val already = runCatching { library.getFavoriteTracks().first().any { it.id == legacy.id } }
            .getOrDefault(false)
        if (already) return false
        return runCatching { library.toggleFavoriteTrack(legacy) }.isSuccess
    }

    /** Puts the last card back, and takes back what its swipe did. */
    fun undo() {
        val last = history.removeLastOrNull() ?: return
        _canUndo.value = history.isNotEmpty()
        commit(
            local.copy(
                position = (local.position - 1).coerceAtLeast(0),
                kept = local.kept - last.card.track.id,
                skipped = local.skipped - last.card.key,
            ),
        )
        viewModelScope.launch {
            // Only a heart this deck added is taken back; one the listener
            // already had stays. Waits for the heart if it is still landing.
            if (last.hearted?.await() == true) {
                runCatching { library.toggleFavoriteTrack(last.card.track.toLegacyTrack()) }
            }
        }
    }

    /** Another stack today, from further down the same sources. */
    fun goAgain() {
        viewModelScope.launch { deal(round = local.round + 1, sameDay = true) }
    }

    /** Saves the songs kept today as a playlist. */
    fun saveAsPlaylist(name: String) {
        val done = _state.value as? DeckUiState.Done ?: return
        if (done.keptCards.isEmpty() || _savedPlaylist.value) return
        _savedPlaylist.value = true
        viewModelScope.launch {
            runCatching {
                val id = library.createPlaylist(name)
                done.keptCards.forEach { library.addTrackToPlaylist(id, it.track.toLegacyTrack()) }
            }.onFailure { _savedPlaylist.value = false }
        }
    }
}
