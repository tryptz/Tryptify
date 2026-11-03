package tf.monochrome.android.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The YouTube or SoundCloud song after this one, made ready while this one
 * plays: a YouTube song fetched whole, a SoundCloud one prepared by the
 * server. Without it every change to such a song waited for all of that, in
 * silence, and a blend could not start on time.
 *
 * Only the song next in line is asked for ([StreamResolver.warmUpcoming]):
 * each is a whole file. The gapless job and both preloads ask for the same
 * song, and share the one warm-up here, so it is fetched (or the server is
 * asked) once. Each only waits on it: a caller cancelled, like a gapless job
 * started again, stops its own wait and nothing else.
 *
 * The warm-up itself stops once [queue] moves on without its song, when the
 * song is neither playing nor next. That drops its share of the fetch, which
 * stops the download unless a play of the song is waiting on it as well
 * (YoutubeAudioCache counts who waits). A song that has just become the one
 * playing keeps its warm-up: its play joins that fetch, and may not have
 * reached it yet.
 *
 * Nothing is fetched while the song the listener asked for is still being
 * made ready ([playPreparing], PreparingPlay.waiting): a new list tapped
 * moves the queue before its first song is here, and the gapless job asks
 * for the second one at once. Two whole songs fetched side by side would
 * hold up the one being waited for. The warm-up waits its turn instead, and
 * starts when that song is in the player.
 */
internal class UpcomingPrepare(
    private val queue: QueueManager,
    private val scope: CoroutineScope,
    /** True while a play waits for its own song to be made ready. */
    private val playPreparing: StateFlow<Boolean> = MutableStateFlow(false),
    /** Makes the song ready; true when it is. */
    private val prepare: suspend (mediaId: String) -> Boolean,
) {
    private val running = HashMap<String, Deferred<Boolean>>()

    /** Every change that can change what plays next. */
    private val moves = combine(queue.queue, queue.currentIndex, queue.repeatMode) { _, _, _ -> }

    /**
     * Whether the queue entry [trackId] is still the one playing or the next.
     * Read by index, as the queue moves: currentTrack follows the index a
     * moment later, and in between the song just reached would look dropped.
     */
    private fun wanted(trackId: Long): Boolean =
        queue.currentQueue.getOrNull(queue.currentQueueIndex)?.id == trackId ||
            queue.peekNext()?.id == trackId

    /**
     * Makes [mediaId], the queue entry [trackId], ready ahead of its turn.
     * True once it is; false when it failed or the queue moved on first.
     */
    suspend fun warm(trackId: Long, mediaId: String): Boolean {
        // Read from the queue a moment ago, by a caller that may be late:
        // nothing is started for a song no longer playing or next (under
        // repeat one, nothing is next).
        if (!wanted(trackId)) return false
        val job = synchronized(running) {
            running[mediaId]?.takeUnless { it.isCancelled } ?: start(trackId, mediaId)
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            // Rethrown when it is this wait that was cancelled; otherwise the
            // warm-up was stopped, and the song is not ready.
            currentCoroutineContext().ensureActive()
            false
        }
    }

    /** Under the lock: listed before it can finish, so its finishing always unlists it. */
    private fun start(trackId: Long, mediaId: String): Deferred<Boolean> {
        val job = scope.async {
            val ready = async {
                playPreparing.first { !it }
                prepare(mediaId)
            }
            val stop = launch {
                moves.first { !wanted(trackId) }
                ready.cancel()
            }
            try {
                ready.await()
            } finally {
                stop.cancel()
            }
        }
        running[mediaId] = job
        job.invokeOnCompletion {
            synchronized(running) { if (running[mediaId] === job) running.remove(mediaId) }
        }
        return job
    }
}
