// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.stepmania

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Where a chart is separated. [FAST] is a GPU server (Modal), seconds a song
 * and billed by the second; [SLOW] a CPU server (Railway), minutes a song. Fast
 * falls back to a CPU server when no GPU one answers; Slow never goes to a GPU,
 * which would bill a batch its player chose not to pay for.
 */
@Serializable
enum class ChartMode(val label: String, val hardware: String) {
    FAST("Fast", "GPU"),
    SLOW("Slow", "CPU"),
}

/** One song to chart: everything the worker needs, kept on disk with the line. */
@Serializable
data class ChartJob(
    val trackId: String,
    /** The song's audio, as a URI string: a file, or a document a picker granted. */
    val source: String,
    /** Where its simfile is written: an absolute path in app storage. */
    val destination: String,
    val title: String,
    val artist: String,
    val musicFileName: String,
    /** The song's length, for a batch's estimate and the pace it teaches; 0 when unknown. */
    val durationSeconds: Int = 0,
    /** The mode it was queued in: a batch keeps the one it was started with. */
    val mode: ChartMode = ChartMode.FAST,
    /** The buttons the chart is for; a job queued before pop'n is a dance one. */
    val course: CourseType = CourseType.DANCE,
)

/** How one song of a batch went. */
@Serializable
data class ChartOutcome(
    val trackId: String,
    val title: String,
    /** The separator that ran, as the service names it; null when the song failed. */
    val backendName: String? = null,
    /** Why no chart was made; null when one was. */
    val failure: String? = null,
    val savedToPack: Boolean = false,
    /** Why the copy in the player's pack folder could not be written, when it could not. */
    val packFailure: String? = null,
)

/** A song being charted now, and how far it is. */
data class ChartInFlight(
    val job: ChartJob,
    val fraction: Float = 0f,
    val stage: String = "Preparing",
)

/** The line: waiting, in flight, and finished since the panel was last closed. */
data class ChartQueueState(
    val waiting: List<ChartJob> = emptyList(),
    val running: List<ChartInFlight> = emptyList(),
    val finished: List<ChartOutcome> = emptyList(),
) {
    val active: Boolean get() = waiting.isNotEmpty() || running.isNotEmpty()

    /** Every song of the batch, finished ones included. */
    val total: Int get() = waiting.size + running.size + finished.size

    val failed: Int get() = finished.count { it.failure != null }

    /** Whether [trackId] is waiting or being charted. */
    fun holds(trackId: String): Boolean =
        waiting.any { it.trackId == trackId } || running.any { it.job.trackId == trackId }

    /** How far the batch is, 0 to 1: finished songs whole, those in flight by their own progress. */
    val fraction: Float
        get() = if (total == 0) 0f else (finished.size + running.sumOf { it.fraction.toDouble() }.toFloat()) / total
}

/**
 * The songs waiting to be charted, the ones being charted now and how the
 * finished ones went: a batch, worked through by [StepManiaGenerationWorker].
 *
 * Generate used to replace whatever job was running, so charting ten songs
 * meant standing over each one in turn. Now each joins the line, and a folder
 * or the whole library joins it at once. The line is kept on disk: a batch
 * outlives the app being put away, and the worker WorkManager restarts picks
 * it up where it was, the songs that were in flight back at the front.
 *
 * Exactly one worker drains the line. [add] says when none is, so the caller
 * starts one; [next] hands the worker its songs and says when it may stop.
 * Both decide under the same lock, so a song added as the last one finishes is
 * never left with nobody to chart it.
 */
@Singleton
class ChartQueue internal constructor(
    /** Where the line is kept: the app's files, or a test's folder. */
    private val file: File,
    private val json: Json,
) {
    @Inject
    constructor(@ApplicationContext context: Context, json: Json) :
        this(File(context.filesDir, "glyph/chart_queue.json"), json)

    private val mutex = Mutex()
    private val _state = MutableStateFlow(ChartQueueState())
    val state: StateFlow<ChartQueueState> = _state.asStateFlow()

    /** Read and written under [mutex]. */
    private var loaded = false
    private var draining = false

    /** The line read off disk, once; every change below waits for it. */
    suspend fun ensureLoaded() = mutex.withLock { loadLocked() }

    /**
     * Adds those of [jobs] not already waiting or in flight, in order. A song
     * that finished earlier in the batch is counted again by how it goes this
     * time.
     */
    suspend fun add(jobs: List<ChartJob>): Added = mutex.withLock {
        loadLocked()
        val now = _state.value
        val fresh = jobs.distinctBy { it.trackId }.filterNot { now.holds(it.trackId) }
        if (fresh.isEmpty()) return@withLock Added(0, startWorker = false)
        val ids = fresh.mapTo(HashSet()) { it.trackId }
        _state.update { it.copy(waiting = it.waiting + fresh, finished = it.finished.filterNot { done -> done.trackId in ids }) }
        saveLocked()
        val start = !draining
        draining = true
        Added(fresh.size, start)
    }

    /** [count] songs joined the line; [startWorker] when nothing is draining it. */
    data class Added(val count: Int, val startWorker: Boolean)

    /**
     * True, once, when songs are in the line and no worker is draining it: a
     * batch left behind by a worker the system stopped. The caller starts one.
     */
    suspend fun claimIfIdle(): Boolean = mutex.withLock {
        loadLocked()
        if (draining || !_state.value.active) return@withLock false
        draining = true
        true
    }

    /** Whether there is anything to chart: a spare worker appended behind one that emptied the line has nothing. */
    suspend fun hasWork(): Boolean = mutex.withLock {
        loadLocked()
        _state.value.active
    }

    /** A worker took the line: songs added from now on need no other. */
    suspend fun startDraining() = mutex.withLock { draining = true }

    /** The worker stopped; the next song added starts another. */
    suspend fun stopDraining() = mutex.withLock { draining = false }

    /**
     * The next song to chart, now in flight. While songs are in flight and none
     * waits, it waits — one may be added yet, and the worker should not stop
     * under a song still being charted — and it is null once nothing is left.
     */
    suspend fun next(): ChartJob? {
        while (true) {
            mutex.withLock {
                loadLocked()
                val now = _state.value
                val job = now.waiting.firstOrNull()
                if (job != null) {
                    _state.update { it.copy(waiting = it.waiting.drop(1), running = it.running + ChartInFlight(job)) }
                    saveLocked()
                    return job
                }
                if (now.running.isEmpty()) {
                    draining = false
                    return null
                }
            }
            _state.first { it.waiting.isNotEmpty() || it.running.isEmpty() }
        }
    }

    /** How far [trackId] is, for the screen. Not saved: a song restarted starts again. */
    fun progress(trackId: String, fraction: Float, stage: String) {
        _state.update { now ->
            now.copy(
                running = now.running.map {
                    if (it.job.trackId == trackId) it.copy(fraction = fraction, stage = stage) else it
                },
            )
        }
    }

    /** [outcome]'s song is done. Nothing, when the batch was cancelled under it. */
    suspend fun finish(outcome: ChartOutcome) = mutex.withLock {
        if (_state.value.running.none { it.job.trackId == outcome.trackId }) return@withLock
        _state.update { now ->
            now.copy(
                running = now.running.filterNot { it.job.trackId == outcome.trackId },
                finished = now.finished + outcome,
            )
        }
        saveLocked()
    }

    /** A song in flight when its worker was stopped: back to the front, to be charted again. */
    suspend fun putBack(job: ChartJob) = mutex.withLock {
        if (_state.value.running.none { it.job.trackId == job.trackId }) return@withLock
        _state.update { now ->
            now.copy(
                waiting = listOf(job) + now.waiting,
                running = now.running.filterNot { it.job.trackId == job.trackId },
            )
        }
        saveLocked()
    }

    /**
     * Drops the whole batch, the songs in flight included; the caller cancels
     * the worker. The next song added starts a new one, rather than waiting on
     * a worker that is on its way out.
     */
    suspend fun cancelAll() = mutex.withLock {
        _state.value = ChartQueueState()
        draining = false
        saveLocked()
    }

    /** Forgets how the finished songs went, when the panel showing them is closed. */
    suspend fun clearFinished() = mutex.withLock {
        if (_state.value.finished.isEmpty()) return@withLock
        _state.update { it.copy(finished = emptyList()) }
        saveLocked()
    }

    private suspend fun loadLocked() {
        if (loaded) return
        loaded = true
        val saved = withContext(Dispatchers.IO) {
            runCatching { file.takeIf { it.isFile }?.readText()?.let { json.decodeFromString(Saved.serializer(), it) } }
                .onFailure { Log.w(TAG, "the chart queue could not be read; starting empty", it) }
                .getOrNull()
        } ?: return
        // Anything added before the file was read stays, after what was saved.
        _state.update { now ->
            now.copy(
                waiting = saved.waiting + now.waiting.filterNot { job -> saved.waiting.any { it.trackId == job.trackId } },
                finished = saved.finished + now.finished,
            )
        }
    }

    private suspend fun saveLocked() {
        val now = _state.value
        // Songs in flight are saved as the front of the line: after a restart
        // nothing is in flight, and they are charted again.
        val saved = Saved(waiting = now.running.map { it.job } + now.waiting, finished = now.finished)
        withContext(Dispatchers.IO) {
            runCatching {
                file.parentFile?.mkdirs()
                if (saved.waiting.isEmpty() && saved.finished.isEmpty()) {
                    file.delete()
                } else {
                    val partial = File(file.path + ".tmp")
                    partial.writeText(json.encodeToString(Saved.serializer(), saved))
                    if (!partial.renameTo(file)) {
                        file.delete()
                        partial.renameTo(file)
                    }
                }
            }.onFailure { Log.w(TAG, "the chart queue could not be saved", it) }
        }
    }

    @Serializable
    private data class Saved(
        val waiting: List<ChartJob> = emptyList(),
        val finished: List<ChartOutcome> = emptyList(),
    )

    private companion object {
        const val TAG = "ChartQueue"
    }
}
