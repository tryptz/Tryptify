package tf.monochrome.android.ui.search

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * One query's search, shown as it comes in. The catalogues, the library and
 * My server answer as one part, shown the moment they are in ([firstShown]).
 * YouTube and SoundCloud ([ask]) answer on their own, each within its time
 * budget, and are merged in as each answers ([onAnswer]). They used to be
 * awaited with the rest, so every search waited for YouTube's, up to 7 s.
 *
 * An answer that comes in before the first part is held until that part is
 * shown, so it is merged into this query's results and never into the
 * previous query's. [cancel] (a newer query) drops every answer still out.
 * Everything runs on one thread: the main one, in the ViewModel.
 */
internal class SearchRound<K, T>(
    val query: String,
    parent: CoroutineScope,
    private val search: suspend (K) -> T,
    private val onAnswer: SearchRound<K, T>.(K, T) -> Unit,
) {
    enum class State {
        /** Nothing to show yet, and something is still out: the full-page spinner. */
        SEARCHING,
        /** Results are up and a late source is still out: the small spinner under them. */
        MORE_COMING,
        /** Every source asked has answered or run out of time. */
        DONE,
        /** Every source asked has answered, none could be reached, and nothing was found. */
        UNREACHABLE,
    }

    // A child of the ViewModel's scope, so a late source still out when the
    // screen goes is dropped with it, and one failing leaves the other be.
    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext.job))
    private val asked = HashSet<K>()
    private val out = HashSet<K>()
    private val firstIn = CompletableDeferred<Unit>()

    /**
     * Whether nothing in the first part could be reached or gave anything:
     * the test for "couldn't reach search", which waits for the late sources.
     */
    var firstFailed = false
        private set

    // How many songs at the head of the list are the first page, which a late
    // answer is ranked into. The pages loaded on scroll since stay after it.
    private var firstPage = 0

    /** The late sources asked and still out. */
    val pending: Set<K> get() = out

    /** Asks each of [keys] not asked yet for this query; one asked already is not asked again. */
    fun ask(keys: Iterable<K>) {
        for (key in keys) {
            if (!asked.add(key)) continue
            out += key
            scope.launch {
                val answer = search(key)
                firstIn.await()
                out -= key
                onAnswer(key, answer)
            }
        }
    }

    /** The first part is up, with [firstPage] songs; answers held until now are handed over. */
    fun firstShown(failed: Boolean, firstPage: Int) {
        firstFailed = failed
        this.firstPage = firstPage
        firstIn.complete(Unit)
    }

    /**
     * [current] with a late answer's [songs] ranked into its first page by
     * [rank], as if they had come in with it, so the order is the one ranking
     * everything at once gives: a catalogue's copy of a song stays above
     * theirs. The pages loaded on scroll since stay after it, in their order,
     * as appendPage keeps them: ranking the whole list again would reshuffle
     * songs already scrolled past.
     */
    fun <S> mergeIntoFirstPage(current: List<S>, songs: List<S>, rank: (List<S>) -> List<S>): List<S> {
        val first = rank(current.take(firstPage) + songs)
        val merged = first + current.drop(firstPage)
        firstPage = first.size
        return merged
    }

    /** Where the search stands, given whether anything at all was [found] so far. */
    fun state(found: Boolean): State = when {
        !firstIn.isCompleted -> State.SEARCHING
        out.isNotEmpty() -> if (found) State.MORE_COMING else State.SEARCHING
        firstFailed && !found -> State.UNREACHABLE
        else -> State.DONE
    }

    fun cancel() = scope.cancel()
}
