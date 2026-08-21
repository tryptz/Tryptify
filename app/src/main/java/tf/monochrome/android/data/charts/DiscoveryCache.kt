package tf.monochrome.android.data.charts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.UnifiedTrack
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the discovery feed learned last time, kept on disk.
 *
 * The caches in front of the chart sources and the catalogue were memory only,
 * with a note saying charts are ephemeral and cheap to refetch and that the
 * cost is a cold start refetching. Both halves of that turned out to be wrong
 * about this feed. The prices are not small — a genre's artist set is a page
 * walk MusicBrainz paces at a request a second, and filling one genre row is a
 * catalogue lookup per charted record plus two per artist — and the TTLs
 * already written down say the answers are not ephemeral either: a day for a
 * tag chart, a week for an artist set, a month for an artist's tag cloud. A
 * cache that expires in a month and dies with the process has a TTL of one
 * session.
 *
 * So the answers outlive the process. The first Discover of the day pays what
 * it always paid; every one after it opens on work already done, which is the
 * difference between a page that assembles itself while you watch and a page
 * that is simply there.
 *
 * This is a cache in the sense the platform means it: it lives in `cacheDir`,
 * the system may delete it whenever it likes, and every read is prepared to
 * find nothing. A corrupt or unreadable file is treated as an absent one — it
 * is thrown away and refetched, never repaired.
 */
@Singleton
class DiscoveryCache @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        private const val FILE_NAME = "discovery-cache.json"

        /**
         * How long a debounced write waits for the next change before going to
         * disk.
         *
         * A feed fills in a burst — six shelves, dozens of answers, all inside
         * a few seconds — and writing on each one would be the same file
         * written a hundred times. Long enough to collect a whole page, short
         * enough that leaving the app straight after one is still caught.
         */
        private const val WRITE_DELAY_MS = 2_000L

        /**
         * Ceiling on remembered catalogue answers, oldest dropped first.
         *
         * Chart rows and artist names accumulate for as long as somebody keeps
         * opening genres, and nothing else here would ever bound them: the
         * other buckets are keyed by genre or by artist and the map bounds
         * them, but a chart row is a pair of strings and there is no end to
         * those. Four thousand is a few megabytes and many sessions of feed.
         */
        private const val MAX_TRACK_ENTRIES = 4_000
    }

    /** A remembered answer and when it was learned. */
    @Serializable
    data class Stamped<T>(val value: T, val at: Long = System.currentTimeMillis())

    @Serializable
    data class Snapshot(
        val tagCharts: Map<String, Stamped<List<ChartEntry>>> = emptyMap(),
        val artistSets: Map<String, Stamped<Set<String>>> = emptyMap(),
        val artistTags: Map<String, Stamped<List<String>>> = emptyMap(),
        /**
         * The catalogue's answer for a chart row, misses included — a null
         * value is the answer "the catalogue does not carry this", which is
         * worth remembering for exactly as long as a hit is. Forgetting it is
         * how a record nobody stocks gets searched for again on every visit.
         */
        val resolved: Map<String, Stamped<UnifiedTrack?>> = emptyMap(),
        val artistTopTracks: Map<String, Stamped<List<UnifiedTrack>>> = emptyMap(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val file: File by lazy { File(context.cacheDir, FILE_NAME) }

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Held mutable and copied only when the file is written. Every answer the
    // feed learns passes through here, so an API that rebuilt an immutable map
    // per answer would copy the whole cache a hundred times a page.
    private val tagCharts = LinkedHashMap<String, Stamped<List<ChartEntry>>>()
    private val artistSets = LinkedHashMap<String, Stamped<Set<String>>>()
    private val artistTags = LinkedHashMap<String, Stamped<List<String>>>()
    private val resolved = LinkedHashMap<String, Stamped<UnifiedTrack?>>()
    private val artistTopTracks = LinkedHashMap<String, Stamped<List<UnifiedTrack>>>()

    private var loaded = false

    @Volatile
    private var writeJob: Job? = null

    /**
     * What was on disk when this session started.
     *
     * Read once, then served from memory. Callers seed their own maps from it
     * and go on using those, so this is a starting point rather than a live
     * store: nothing on a request path reads back through it.
     */
    suspend fun restore(): Snapshot = mutex.withLock {
        if (!loaded) {
            // On the IO dispatcher explicitly. The feed's shelf builders are
            // launched from the ViewModel scope, which is the main thread, and
            // they reach this on the first row they try to resolve — reading a
            // few megabytes of JSON there is a dropped frame at exactly the
            // moment the page is trying to draw.
            val fromDisk = withContext(Dispatchers.IO) { readFile() }
            tagCharts.putAll(fromDisk.tagCharts)
            artistSets.putAll(fromDisk.artistSets)
            artistTags.putAll(fromDisk.artistTags)
            resolved.putAll(fromDisk.resolved)
            artistTopTracks.putAll(fromDisk.artistTopTracks)
            loaded = true
        }
        snapshotLocked()
    }

    suspend fun putTagChart(genreId: String, entries: List<ChartEntry>, at: Long) =
        put { tagCharts[genreId] = Stamped(entries, at) }

    suspend fun putArtistSet(genreId: String, artists: Set<String>, at: Long) =
        put { artistSets[genreId] = Stamped(artists, at) }

    suspend fun putArtistTags(artistKey: String, tags: List<String>, at: Long) =
        put { artistTags[artistKey] = Stamped(tags, at) }

    suspend fun putResolved(matchKey: String, track: UnifiedTrack?, at: Long) =
        put { resolved.trimTo(MAX_TRACK_ENTRIES)[matchKey] = Stamped(track, at) }

    suspend fun putArtistTopTracks(artistKey: String, tracks: List<UnifiedTrack>, at: Long) =
        put { artistTopTracks.trimTo(MAX_TRACK_ENTRIES)[artistKey] = Stamped(tracks, at) }

    /** Forget everything, on disk and in memory, so a refresh really refetches. */
    suspend fun clear() {
        writeJob?.cancel()
        mutex.withLock {
            tagCharts.clear()
            artistSets.clear()
            artistTags.clear()
            resolved.clear()
            artistTopTracks.clear()
            // Loaded, and empty: a later read must not go back to the file for
            // the very answers this just discarded.
            loaded = true
        }
        runCatching { file.delete() }
    }

    private suspend fun put(mutate: () -> Unit) {
        mutex.withLock { mutate() }
        scheduleWrite()
    }

    private fun snapshotLocked() = Snapshot(
        tagCharts = LinkedHashMap(tagCharts),
        artistSets = LinkedHashMap(artistSets),
        artistTags = LinkedHashMap(artistTags),
        resolved = LinkedHashMap(resolved),
        artistTopTracks = LinkedHashMap(artistTopTracks),
    )

    private fun scheduleWrite() {
        writeJob?.cancel()
        // [scope] is on the IO dispatcher, so the encode and the write are off
        // whatever thread learned the answer.
        writeJob = scope.launch {
            delay(WRITE_DELAY_MS)
            writeFile(mutex.withLock { snapshotLocked() })
        }
    }

    private fun writeFile(snapshot: Snapshot) {
        // Through a temporary and then renamed. A half-written cache file is
        // indistinguishable from a corrupt one on the next read, and the next
        // read is a cold start — which is the moment this exists to help.
        runCatching {
            val temp = File(file.parentFile, "$FILE_NAME.tmp")
            temp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }
    }

    private fun readFile(): Snapshot = runCatching {
        if (!file.exists()) return@runCatching Snapshot()
        json.decodeFromString(Snapshot.serializer(), file.readText())
    }.getOrElse {
        // Unreadable is the same as absent. A cache that tries to repair itself
        // is a cache that can hand back something that was never true.
        runCatching { file.delete() }
        Snapshot()
    }

    /**
     * Drop the oldest entries until there is room for one more.
     *
     * Oldest by when the answer was learned rather than by when it was last
     * read, because that is what the file records and a read-stamp would have
     * to be written back on every cache hit.
     */
    private fun <T> LinkedHashMap<String, Stamped<T>>.trimTo(
        max: Int,
    ): LinkedHashMap<String, Stamped<T>> {
        if (size < max) return this
        entries.sortedBy { it.value.at }
            .take(size - max + 1)
            .forEach { remove(it.key) }
        return this
    }
}
