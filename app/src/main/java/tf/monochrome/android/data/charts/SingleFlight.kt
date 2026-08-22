package tf.monochrome.android.data.charts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * One request per question, however many callers ask it at once.
 *
 * Every cache behind the discovery feed is check-then-fetch, which is correct
 * in sequence and wrong under concurrency: the feed builds six shelves at the
 * same moment, and "the answer is already in the memo" is only true for the
 * shelf that got there first. The other five look, find nothing — the first
 * one has not returned yet — and issue the same request. A genre and its
 * neighbours are largely the same people and largely the same records, so this
 * is the normal case rather than a race that occasionally happens.
 *
 * The duplicates cost more than their own latency. Catalogue lookups run behind
 * a fixed number of permits, so a second copy of a question already in flight
 * is not merely wasted, it is *taking the place of* a question nobody has asked
 * yet. Collapsing them is what makes the memos deliver the saving they claim.
 *
 * Cancellation belongs to whoever was cancelled. If the caller that happened to
 * be leading a flight goes away — a shelf whose budget expired, a feed replaced
 * by a chip tap — the callers waiting behind it do not inherit that: they see
 * the flight end without an answer and one of them starts it again. Any other
 * choice makes a shelf's timeout contagious.
 */
class SingleFlight<K : Any, V> {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<K, CompletableDeferred<Result<V>>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            var leading = false
            val slot = mutex.withLock {
                inFlight[key] ?: CompletableDeferred<Result<V>>()
                    .also {
                        inFlight[key] = it
                        leading = true
                    }
            }

            if (leading) {
                val outcome = try {
                    Result.success(block())
                } catch (thrown: Throwable) {
                    Result.failure(thrown)
                }
                // Cleared before the waiters are released, so a flight that
                // ended in a cancellation can be started afresh by one of them
                // rather than every one of them queueing on a dead key. Both
                // steps under the one lock: between them the key is present but
                // dead, and a caller arriving in that gap would join a flight
                // that has already ended.
                //
                // [NonCancellable] because this is the case that matters most.
                // A cancelled leader is already cancelled, so an ordinary
                // `withLock` here would suspend and immediately throw — leaving
                // the key in the map and the slot uncompleted, and every caller
                // behind it waiting on a flight that will never end. The
                // handover has to outlive whatever cancelled the leader.
                withContext(NonCancellable) {
                    mutex.withLock {
                        inFlight.remove(key)
                        slot.complete(outcome)
                    }
                }
                return outcome.getOrThrow()
            }

            val outcome = slot.await()
            if (outcome.exceptionOrNull() is CancellationException) continue
            return outcome.getOrThrow()
        }
    }
}

/**
 * Map every item concurrently, at most [concurrency] of them in flight, in
 * order.
 *
 * The shape this replaces was `chunked(n).flatMap { it.map(::async).awaitAll() }`,
 * which is not a concurrency limit but a lockstep barrier: a batch of eight
 * runs at the pace of its slowest member, and seven idle slots wait out the
 * eighth before the next batch is even started. On a page where four shelves
 * share one pool of permits those idle slots are the page's throughput —
 * they are permits nobody is using while somebody else's shelf is blocked
 * waiting for one.
 *
 * Here a finished lookup releases its permit immediately and the next item
 * takes it, so the pipe stays full for as long as there is work. The result is
 * still in the input's order, which the callers depend on: a chart is a
 * ranking, and resolving it out of order would reorder it.
 */
suspend fun <T, R> List<T>.mapConcurrent(
    concurrency: Int,
    transform: suspend (T) -> R,
): List<R> {
    if (isEmpty()) return emptyList()
    val gate = Semaphore(concurrency.coerceAtLeast(1))
    return coroutineScope {
        map { item -> async { gate.withPermit { transform(item) } } }.awaitAll()
    }
}
