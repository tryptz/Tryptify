package tf.monochrome.android.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.radio.RadioQueueManager
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
 * Finished lists stay in the queue, so the queue sheet scrolls back through
 * them and its count runs from the first song played. The queue grows only by
 * what has played, plus the one list added ahead, as it does under song radio.
 */
@Singleton
class ListContinuation @Inject constructor(
    private val queueManager: QueueManager,
    private val radio: RadioQueueManager,
    private val loader: ListTrackLoader,
    private val preferences: PreferencesManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

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

        /** The queue changed while it waited: leave the player to whoever changed it. */
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
            val found = findBeside(context, forward = true)
            // Something else set the queue while this one was loading.
            if (queueManager.listContext.value != context) return@launch
            if (found == null) {
                // The last list: songs like the one playing, then.
                queueManager.forgetListContext()
                radio.startRadio()
                radioStartedHere = true
                return@launch
            }
            queueManager.appendList(playOrder(found.tracks), found.tracks, context.copy(tail = found.list))
        }
    }

    /**
     * The queue has run out: the last song ended, or Next was pressed on it.
     * Waits for the next list if it is still being fetched, and for song
     * radio's first pick if that is what comes next, then moves on.
     */
    suspend fun next(): Outcome {
        val at = queueManager.currentTrack.value ?: return Outcome.NOTHING
        extending?.join()
        if (radio.isGenerating.value) {
            withTimeoutOrNull(RADIO_WAIT_MS) { radio.isGenerating.first { !it } }
        }
        if (queueManager.currentTrack.value !== at) return Outcome.OVERTAKEN
        return if (queueManager.next() != null) Outcome.MOVED else Outcome.NOTHING
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
        val found = findBeside(context, forward = false)
        if (queueManager.listContext.value != context ||
            queueManager.currentQueue.firstOrNull() !== head ||
            queueManager.currentQueueIndex != 0
        ) {
            return Outcome.OVERTAKEN
        }
        if (found == null) return Outcome.NOTHING
        queueManager.prependList(playOrder(found.tracks), found.tracks, context.copy(head = found.list))
        return Outcome.MOVED
    }

    private class Found(val list: ListRef, val tracks: List<Track>)

    /**
     * The nearest list after the queue's last (or before its first) that has
     * songs. Lists that turn out empty, or cannot be reached, are passed
     * over, a few at most, so a row of dead links cannot keep it fetching.
     */
    private suspend fun findBeside(context: ListContext, forward: Boolean): Found? {
        var from = if (forward) context.tail else context.head
        var folders: FolderView? = null
        repeat(MAX_SKIPPED_LISTS) {
            val next = when (from) {
                is ListRef.Folder -> {
                    val folder = from as ListRef.Folder
                    val view = folders ?: loader.folders().also { folders = it }
                    val path = if (forward) {
                        FolderWalk.after(view, folder.path, skipBelow = folder.withSubfolders)
                    } else {
                        FolderWalk.before(view, folder.path)
                    }
                    path?.let { ListRef.Folder(it) }
                }
                else -> if (forward) context.after(from) else context.before(from)
            } ?: return null
            val tracks = loader.load(next)
            if (tracks.isNotEmpty()) return Found(next, tracks)
            from = next
        }
        return null
    }

    /** Under shuffle the next list plays shuffled too. */
    private fun playOrder(tracks: List<Track>): List<Track> =
        if (queueManager.shuffleEnabled.value) tracks.shuffled() else tracks

    private companion object {
        const val MAX_SKIPPED_LISTS = 10

        /** How long Next on the last song waits for song radio's first pick. */
        const val RADIO_WAIT_MS = 15_000L
    }
}
