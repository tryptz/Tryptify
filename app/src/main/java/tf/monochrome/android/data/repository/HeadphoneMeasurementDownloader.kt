package tf.monochrome.android.data.repository

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import tf.monochrome.android.audio.eq.EqDataParser
import tf.monochrome.android.data.api.HeadphoneAutoEqApi
import tf.monochrome.android.data.api.MeasurementFetch
import tf.monochrome.android.data.api.MeasurementHttp
import tf.monochrome.android.data.api.SquiglinkApi
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.Headphone
import tf.monochrome.android.domain.model.MeasurementRig
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads every measurement in the headphone database into
 * [HeadphoneOfflineStore]: the AutoEq results and every squig.link site's
 * left and right ears, about 12,000 measurements.
 *
 * Runs in a scope of its own, so it carries on when the headphone list is
 * closed; it stops if the app is killed, and a new run skips everything
 * already saved, so starting again carries on where it stopped. At most
 * [WORKERS] files at once and few per site ([perHostLimit]): squig.link sites
 * are one reviewer's hosting each. A server that asks to slow down (429) is
 * waited for, everyone pausing together; one that keeps asking ends the run
 * as [Outcome.THROTTLED] rather than hammering it.
 *
 * It fetches the same files the EQ screen fetches (`measurementUrls`,
 * `channelSamples`) and keeps them as the screen parses them, so a fit from
 * the stored copy is the fit from the download.
 */
@Singleton
class HeadphoneMeasurementDownloader @Inject constructor(
    private val store: HeadphoneOfflineStore,
    private val autoEqApi: HeadphoneAutoEqApi,
    private val squiglinkApi: SquiglinkApi,
) {
    sealed interface State {
        /** Nothing running; [last] is how the last run ended, null before any. */
        data class Idle(val last: Outcome? = null, val failed: Int = 0) : State

        /**
         * [saved] of [total] measurements are on the phone (from earlier runs
         * too), [failed] could not be fetched this run, and [waiting] while a
         * server has asked to slow down. [total] is 0 while the list loads.
         */
        data class Running(
            val saved: Int,
            val total: Int,
            val failed: Int,
            val waiting: Boolean,
        ) : State
    }

    enum class Outcome {
        /** Everything is saved. */
        COMPLETE,

        /** Done, but some files could not be fetched; another run tries them again. */
        PARTIAL,

        /** Stopped by the listener. */
        STOPPED,

        /** Neither database could be reached. */
        NO_NETWORK,

        /** A server kept asking to slow down. */
        THROTTLED,

        /** A file could not be written: most likely the phone is full. */
        NO_SPACE,
    }

    /** What a download would cost, from the list's measurement counts. */
    data class Estimate(val measurements: Int, val downloadBytes: Long, val storedBytes: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _state = MutableStateFlow<State>(State.Idle())
    val state: StateFlow<State> = _state.asStateFlow()

    private val _summary = MutableStateFlow<HeadphoneOfflineStore.Summary?>(null)

    /** What is on the phone now; null until first read. */
    val summary: StateFlow<HeadphoneOfflineStore.Summary?> = _summary.asStateFlow()

    fun refreshSummary() {
        scope.launch { _summary.value = store.summary() }
    }

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = State.Running(saved = 0, total = 0, failed = 0, waiting = false)
            val failed = AtomicInteger()
            val outcome = try {
                download(failed)
            } catch (e: CancellationException) {
                _state.value = State.Idle(Outcome.STOPPED, failed.get())
                _summary.value = store.summary()
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Offline download failed: ${e.javaClass.simpleName}")
                Outcome.NO_NETWORK
            }
            _summary.value = store.summary()
            _state.value = State.Idle(outcome, failed.get())
        }
    }

    fun stop() {
        job?.cancel()
    }

    /** Stops a run and removes everything saved. */
    fun delete() {
        scope.launch {
            job?.cancelAndJoin()
            store.clear()
            _summary.value = store.summary()
            _state.value = State.Idle()
        }
    }

    private class Throttled : Exception()
    private class OutOfSpace : Exception()

    @Volatile private var pausedUntil = 0L

    private suspend fun download(failed: AtomicInteger): Outcome {
        val auto = autoEqApi.fetchHeadphones().getOrDefault(emptyList())
        val squig = squiglinkApi.fetchHeadphones().getOrDefault(emptyList())
        if (auto.isEmpty() && squig.isEmpty()) return Outcome.NO_NETWORK
        // A source that cannot be reached keeps its part of the saved list,
        // rather than vanishing from it until the next full run.
        val headphones = mergeHeadphonesByName(
            auto.ifEmpty { store.indexPart { !isSquiglink(it) } } +
                squig.ifEmpty { store.indexPart { isSquiglink(it) } },
        )
        try {
            store.writeIndex(headphones)
        } catch (_: IOException) {
            return Outcome.NO_SPACE
        }

        val all = measurementsOf(headphones)
        val todo = interleaveByHost(all.filterNot { store.has(it) })
        val saved = AtomicInteger(all.size - todo.size)
        pausedUntil = 0L

        val queue = Channel<AutoEqMeasurement>(Channel.UNLIMITED)
        todo.forEach { queue.trySend(it) }
        queue.close()

        val hosts = HashMap<String, Semaphore>()
        fun limiter(m: AutoEqMeasurement) = synchronized(hosts) {
            hosts.getOrPut(hostOf(m)) { Semaphore(perHostLimit(m)) }
        }

        fun publish() {
            _state.value = State.Running(
                saved = saved.get(),
                total = all.size,
                failed = failed.get(),
                waiting = System.currentTimeMillis() < pausedUntil,
            )
        }

        try {
            coroutineScope {
                val ticker = launch {
                    while (isActive) {
                        publish()
                        delay(PROGRESS_INTERVAL_MS)
                    }
                }
                List(WORKERS) {
                    launch {
                        for (m in queue) {
                            val ok = limiter(m).withPermit { fetchOne(m) }
                            if (ok) saved.incrementAndGet() else failed.incrementAndGet()
                        }
                    }
                }.joinAll()
                ticker.cancel()
            }
        } catch (_: Throttled) {
            return Outcome.THROTTLED
        } catch (_: OutOfSpace) {
            return Outcome.NO_SPACE
        }
        publish()
        return if (failed.get() == 0) Outcome.COMPLETE else Outcome.PARTIAL
    }

    /** Saves [m]; false when it could not be fetched and a later run should try again. */
    private suspend fun fetchOne(m: AutoEqMeasurement): Boolean {
        val stored = if (isSquiglink(m)) {
            val left = fetchChannel(m, "L") ?: return false
            val right = fetchChannel(m, "R") ?: return false
            HeadphoneOfflineStore.StoredMeasurement(left = left.value, right = right.value)
        } else {
            fetchAutoEq(m) ?: return false
        }
        try {
            store.write(m, stored)
        } catch (_: IOException) {
            throw OutOfSpace()
        }
        return true
    }

    /** AutoEq's one file; an empty measurement when none is published, null on failure. */
    private suspend fun fetchAutoEq(m: AutoEqMeasurement): HeadphoneOfflineStore.StoredMeasurement? {
        for (url in autoEqApi.measurementUrls(m.path, m.fileName)) {
            when (val r = get(url, accept = null)) {
                is MeasurementFetch.Found ->
                    return HeadphoneOfflineStore.StoredMeasurement(curve = EqDataParser.parseRawData(r.text))
                MeasurementFetch.Missing -> continue
                else -> return null
            }
        }
        return HeadphoneOfflineStore.StoredMeasurement()
    }

    /** A boxed channel, so "not published" (value null) differs from "failed" (null). */
    private class ChannelResult(val value: HeadphoneOfflineStore.StoredChannel?)

    private suspend fun fetchChannel(m: AutoEqMeasurement, channel: String): ChannelResult? {
        for (sample in squiglinkApi.channelSamples(channel)) {
            when (val r = get(squiglinkApi.sampleUrl(m.host, m.fileName, sample), SQUIG_ACCEPT)) {
                is MeasurementFetch.Found -> {
                    val curve = EqDataParser.parseRawData(r.text)
                    return ChannelResult(
                        if (curve.isEmpty()) null else HeadphoneOfflineStore.StoredChannel(sample, curve),
                    )
                }
                MeasurementFetch.Missing -> continue
                else -> return null
            }
        }
        return ChannelResult(null)
    }

    /**
     * One request, waiting out a server that asks to slow down: every worker
     * holds off until the pause is over, and a file throttled
     * [MAX_THROTTLES_PER_FILE] times ends the run.
     */
    private suspend fun get(url: String, accept: String?): MeasurementFetch {
        var throttles = 0
        while (true) {
            val wait = pausedUntil - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val r = MeasurementHttp.get(url, accept)
            if (r !is MeasurementFetch.Throttled) return r
            if (++throttles >= MAX_THROTTLES_PER_FILE) throw Throttled()
            pausedUntil = maxOf(pausedUntil, System.currentTimeMillis() + r.retryAfterMs)
        }
    }

    companion object {
        private const val TAG = "OfflineMeasurements"
        private const val WORKERS = 8
        private const val PROGRESS_INTERVAL_MS = 250L
        private const val MAX_THROTTLES_PER_FILE = 4
        private const val SQUIG_ACCEPT = "application/json,text/plain;q=0.9,*/*;q=0.5"
        private const val GITHUB_HOST = "raw.githubusercontent.com"

        // Measured on a sample of 40 of each (2026-11-18): an AutoEq result
        // is ~42 KB to fetch and one 4 KB block kept; a squig.link pair is
        // two ~14 KB files and ~6 KB kept, two blocks.
        private const val AUTOEQ_DOWNLOAD_BYTES = 43_000L
        private const val AUTOEQ_STORED_BYTES = 4_096L
        private const val SQUIG_DOWNLOAD_BYTES = 28_000L
        private const val SQUIG_STORED_BYTES = 8_192L

        fun isSquiglink(m: AutoEqMeasurement) = m.target == "squiglink"

        /** Every remote measurement of [headphones] once; uploads are the listener's own. */
        fun measurementsOf(headphones: List<Headphone>): List<AutoEqMeasurement> =
            headphones.asSequence()
                .flatMap { it.measurements }
                .filter { it.rig != MeasurementRig.UPLOADED }
                .distinctBy { listOf(it.target, it.host, it.path, it.fileName) }
                .toList()

        fun hostOf(m: AutoEqMeasurement): String = if (isSquiglink(m)) m.host else GITHUB_HOST

        fun perHostLimit(m: AutoEqMeasurement): Int = if (isSquiglink(m)) 2 else 4

        /** One from each site in turn, so the workers are never all queued on one site. */
        fun interleaveByHost(ms: List<AutoEqMeasurement>): List<AutoEqMeasurement> {
            val lanes = ms.groupBy(::hostOf).values.map { it.iterator() }
            return buildList(ms.size) {
                while (size < ms.size) {
                    for (lane in lanes) if (lane.hasNext()) add(lane.next())
                }
            }
        }

        fun estimate(headphones: List<Headphone>): Estimate {
            val ms = measurementsOf(headphones)
            val squig = ms.count(::isSquiglink).toLong()
            val auto = ms.size - squig
            return Estimate(
                measurements = ms.size,
                downloadBytes = auto * AUTOEQ_DOWNLOAD_BYTES + squig * SQUIG_DOWNLOAD_BYTES,
                storedBytes = auto * AUTOEQ_STORED_BYTES + squig * SQUIG_STORED_BYTES,
            )
        }
    }
}
