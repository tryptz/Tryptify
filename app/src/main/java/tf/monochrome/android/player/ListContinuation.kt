package tf.monochrome.android.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.radio.RadioQueueManager
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Continue into the next list" (Settings › Audio › Playback): a queue played
 * from a playlist, album or folder carries on into the list beside it on the
 * screen it came from, and after the last one into song radio. Previous on its
 * first song goes to the last song of the list before.
 *
 * Works like song radio ([RadioQueueManager]): it watches the queue and adds
 * to it through [QueueManager], never touching the player. The next list is
 * added as soon as the last song of the queue starts, so gapless, the blend
 * and Next all see a following track like any other; a song added with Play
 * next or Add to queue still plays first.
 *
 * Repeat steps it aside: Repeat All loops the list playing, Repeat One the
 * song. So does song radio, which keeps the queue from running out.
 *
 * A list that could not be loaded (offline, a server down) is neither passed
 * over nor the end: the lists are kept, and the last song's end, or Next on
 * it, looks again ([next]).
 *
 * Finished lists stay in the queue, so the queue sheet scrolls back through
 * them and its count runs from the first song played. The queue grows only by
 * what has played, plus the one list added ahead, as it does under song radio,
 * and past [QueueManager.MAX_BEHIND] songs behind the one playing its oldest
 * lists are dropped.
 */
@Singleton
class ListContinuation @Inject constructor(
    private val queueManager: QueueManager,
    private val radio: RadioQueueManager,
    private val loader: ListTrackLoader,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val search = ListSearch(load = loader::load, folders = loader::folders)
    private val endWait = QueueEndWait(WAIT_MS)

    private var extending: Job? = null
    private var extendingFor: ListContext? = null

    /** Song radio was started here, after the last list; a list played next takes over from it. */
    private var radioStartedHere = false

    /** How [next] and [stepBack] came out. */
    enum class Outcome {
        /** Moved; play the queue's current track. */
        MOVED,

        /** Nothing to move to. */
        NOTHING,

        /**
         * Someone else moves the player: the queue changed while it waited,
         * or an earlier call is still waiting and will.
         */
        OVERTAKEN,
    }

    init {
        scope.launch {
            combine(
                queueManager.queue,
                queueManager.currentIndex,
                queueManager.repeatMode,
                queueManager.listContext,
            ) { queue, index, _, context -> Triple(queue, index, context) }
                .collect { (queue, index, context) -> onMoved(queue, index, context) }
        }
    }

    private fun onMoved(queue: List<Track>, index: Int, context: ListContext?) {
        if (context == null) return
        if (radioStartedHere) {
            // A list played after the radio this started: the list's turn.
            radioStartedHere = false
            if (radio.isActive.value) radio.stopRadio()
        }
        if (index >= 0 && index == queue.lastIndex) extend(context)
    }

    /** The last song of the queue started: add the next list behind it, or start song radio. */
    private fun extend(context: ListContext) {
        if (queueManager.repeatMode.value != RepeatMode.OFF) return
        if (radio.isActive.value) return
        if (extending?.isActive == true) {
            if (extendingFor == context) return
            extending?.cancel()
        }
        extendingFor = context
        extending = scope.launch {
            if (!preferences.continueIntoNextList.first()) return@launch
            val found = search.beside(context, forward = true, queue = queueManager.currentQueue)
            // Something else set the queue while this one was loading.
            if (queueManager.listContext.value != context) return@launch
            when (found) {
                // Not the end, only not now: the lists stay, and the song's end tries again.
                ListSearch.Lookup.Failed -> Unit
                ListSearch.Lookup.End -> {
                    // The last list: songs like the one playing, then.
                    queueManager.forgetListContext()
                    radio.startRadio()
                    radioStartedHere = true
                }
                is ListSearch.Lookup.Found -> queueManager.appendList(
                    tracks = playOrder(found.tracks),
                    inOrder = found.tracks,
                    context = context.copy(tail = found.list, tailCut = false),
                    startsList = found.whole,
                )
            }
        }
    }

    /**
     * The queue has run out: the last song ended, or Next was pressed on it.
     * Waits for the next list if it is still being fetched, and for song
     * radio's first pick if that is what comes next, each for a while at most
     * ([QueueEndWait]), then moves on.
     */
    suspend fun next(): Outcome {
        val at = queueManager.currentTrack.value ?: return Outcome.NOTHING
        return endWait.await(
            list = {
                // Nothing on its way: the next list could not be loaded when
                // this song started. Its end is when to look again, once.
                if (extending?.isActive != true) retry()
                extending
            },
            radioBusy = radio.isGenerating,
        ) {
            when {
                queueManager.currentTrack.value !== at -> Outcome.OVERTAKEN
                queueManager.next() != null -> Outcome.MOVED
                else -> Outcome.NOTHING
            }
        } ?: Outcome.OVERTAKEN
    }

    /** Look for the next list again, if the queue still has lists and is on its last song. */
    private fun retry() {
        val context = queueManager.listContext.value ?: return
        if (queueManager.currentQueueIndex == queueManager.currentQueue.lastIndex) extend(context)
    }

    /**
     * Previous on the first song of the queue: put the list before in front
     * and move to its last song. [Outcome.NOTHING] when there is none, and
     * Previous restarts the song as it always has.
     */
    suspend fun stepBack(): Outcome {
        if (queueManager.currentQueueIndex != 0) return Outcome.NOTHING
        if (queueManager.repeatMode.value != RepeatMode.OFF) return Outcome.NOTHING
        val context = queueManager.listContext.value ?: return Outcome.NOTHING
        if (context.headCut) return Outcome.NOTHING
        if (!preferences.continueIntoNextList.first()) return Outcome.NOTHING
        val head = queueManager.currentQueue.firstOrNull() ?: return Outcome.NOTHING
        val found = search.beside(context, forward = false)
        if (queueManager.listContext.value != context ||
            queueManager.currentQueue.firstOrNull() !== head ||
            queueManager.currentQueueIndex != 0
        ) {
            return Outcome.OVERTAKEN
        }
        // None, or none that would load: the song restarts, as it always has.
        if (found !is ListSearch.Lookup.Found) return Outcome.NOTHING
        queueManager.prependList(playOrder(found.tracks), found.tracks, context.copy(head = found.list))
        return Outcome.MOVED
    }

    /** Under shuffle the next list plays shuffled too. */
    private fun playOrder(tracks: List<Track>): List<Track> =
        if (queueManager.shuffleEnabled.value) tracks.shuffled() else tracks

    private companion object {
        /** How long Next on the last song waits for the next list, and then for song radio's first pick. */
        const val WAIT_MS = 15_000L
    }
}

/**
 * The wait in [ListContinuation.next] for what follows the queue's last song:
 * one at a time, and each part of it [limitMs] at most. Without the limit a
 * slow walk through a row of lists kept the player silent after the last
 * song for as long as the walk took; without the one at a time, every press
 * of Next meanwhile started another wait, each to move the player when it
 * was done.
 */
class QueueEndWait(private val limitMs: Long) {
    private val waiting = AtomicBoolean(false)

    /**
     * Waits for [list] (the next list on its way, if one is) and then, while
     * [radioBusy], for song radio's first pick, then answers [then]. Null at
     * once, without waiting, while an earlier call is still waiting: that
     * one moves the player.
     */
    suspend fun <T> await(list: () -> Job?, radioBusy: StateFlow<Boolean>, then: () -> T): T? {
        if (!waiting.compareAndSet(false, true)) return null
        try {
            list()?.let { job -> withTimeoutOrNull(limitMs) { job.join() } }
            if (radioBusy.value) withTimeoutOrNull(limitMs) { radioBusy.first { !it } }
            return then()
        } finally {
            waiting.set(false)
        }
    }
}
