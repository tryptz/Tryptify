package tf.monochrome.android.data.charts

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetch-and-cache in front of [ChartsClient].
 *
 * The caching here is what makes per-genre charts affordable at all. A sitewide
 * pull is a thousand rows and is *the same thousand rows for every genre* — the
 * genre filter runs locally afterwards — so one fetch per window serves all 355
 * genres on the map. Without that, walking the map would be a network round trip
 * per node.
 *
 * Genre→artist sets are cached far longer than the charts themselves: which
 * artists count as hard techno moves on the timescale of a scene, not a week.
 *
 * Two things make the cache actually deliver that. Everything but the sitewide
 * pull survives the process in [DiscoveryCache], because a week-long TTL that
 * dies when the app is swapped out is a TTL of one session — and the artist
 * sets in particular are a MusicBrainz page walk paced at a request a second,
 * which is not a price to pay twice. And every fetch is single-flighted, because
 * check-then-fetch is only a cache in sequence: six shelves starting at the same
 * moment all look, all find nothing, and all issue the same request.
 */
@Singleton
class ChartsRepository @Inject constructor(
    private val client: ChartsClient,
    private val disk: DiscoveryCache,
) {
    private val mutex = Mutex()
    private val sitewide = mutableMapOf<ChartWindow, Cached<SitewideChart>>()
    private val artistSets = mutableMapOf<String, Cached<Set<String>>>()
    private val tagCharts = mutableMapOf<String, Cached<List<ChartEntry>>>()
    private val artistTags = mutableMapOf<String, Cached<List<String>>>()

    private val sitewideFlight = SingleFlight<ChartWindow, SitewideChart>()
    private val artistSetFlight = SingleFlight<String, Set<String>>()
    private val tagChartFlight = SingleFlight<String, List<ChartEntry>>()
    private val artistTagFlight = SingleFlight<String, List<String>>()

    /** Whether last session's answers have been folded in yet. */
    private val restoreMutex = Mutex()

    @Volatile
    private var restored = false

    companion object {
        /** The seven-day window genuinely moves day to day; the longer ones do not. */
        private const val TTL_SHORT_WINDOW_MS = 6L * 60 * 60 * 1000
        private const val TTL_LONG_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val TTL_TAG_CHART_MS = 24L * 60 * 60 * 1000
        private const val TTL_ARTIST_SET_MS = 7L * 24 * 60 * 60 * 1000
        private const val TTL_ARTIST_TAGS_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Ceiling on how many of a genre's artists to collect, per spelling.
         * Three pages covers every genre in the graph with room to spare, and
         * bounds a cold fetch to a few paced requests rather than dozens.
         */
        private const val MAX_ARTISTS_PER_NAME = 300
    }

    private class Cached<T>(val value: T, val at: Long = System.currentTimeMillis()) {
        fun fresh(ttlMs: Long) = System.currentTimeMillis() - at < ttlMs
    }

    /**
     * Fold what was on disk into the in-memory maps, once per process.
     *
     * Restored rather than read through: the memory maps stay the authority for
     * the session, so nothing on a request path ever touches the filesystem
     * after this. Stamps are carried across as they were written, so a chart
     * saved twenty-three hours ago expires an hour from now rather than a day
     * from now — a cache that resets its own TTL on load never expires.
     */
    private suspend fun restore() {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return
            val snapshot = disk.restore()
            mutex.withLock {
                snapshot.tagCharts.forEach { (id, held) ->
                    if (id !in tagCharts) tagCharts[id] = Cached(held.value, held.at)
                }
                snapshot.artistSets.forEach { (id, held) ->
                    if (id !in artistSets) artistSets[id] = Cached(held.value, held.at)
                }
                snapshot.artistTags.forEach { (key, held) ->
                    if (key !in artistTags) artistTags[key] = Cached(held.value, held.at)
                }
            }
            restored = true
        }
    }

    /** The global windowed chart, shared by every genre asking for this window. */
    suspend fun sitewide(window: ChartWindow): SitewideChart {
        val ttl = if (window == ChartWindow.SEVEN_DAYS) TTL_SHORT_WINDOW_MS else TTL_LONG_WINDOW_MS
        mutex.withLock { sitewide[window]?.takeIf { it.fresh(ttl) } }?.let { return it.value }

        return sitewideFlight.run(window) {
            // Re-checked inside the flight: a caller that queued behind another
            // one is asking a question that has just been answered.
            mutex.withLock { sitewide[window]?.takeIf { it.fresh(ttl) } }?.let { return@run it.value }

            val fetched = client.sitewideRecordings(window)
            // An empty result is not cached: it usually means the service was
            // briefly unreachable, and pinning that for six hours would turn a blip
            // into an outage for the rest of the session.
            //
            // Not persisted either, and alone in that. It is a thousand rows
            // that no genre row on the feed reads — the feed asks for all-time,
            // which is the tag chart's own window — so it would be most of the
            // file in exchange for one request on a screen nobody opens cold.
            if (fetched.entries.isNotEmpty()) {
                mutex.withLock { sitewide[window] = Cached(fetched) }
            }
            fetched
        }
    }

    /**
     * The set of artists belonging to a genre, normalised for matching.
     *
     * [names] is the genre's own name followed by its aliases. The primary
     * spelling is paged out in full first; an alias is only tried if that found
     * nothing at all, because taggers are inconsistent about which spelling they
     * reach for but rarely split a scene evenly between two of them.
     */
    suspend fun artistsFor(genreId: String, names: List<String>): Set<String> {
        restore()
        cachedArtistSet(genreId)?.let { return it }

        return artistSetFlight.run(genreId) {
            cachedArtistSet(genreId)?.let { return@run it }

            val union = mutableSetOf<String>()
            var requests = 0
            for (name in names.take(3)) {
                // Page through the primary spelling before trying an alias. A genre
                // of any size has more than one page of artists — hard techno has
                // ~167 — and a set that stops at 100 leaves real artists looking
                // unconfirmed, which is the failure this set exists to prevent.
                var offset = 0
                while (offset < MAX_ARTISTS_PER_NAME) {
                    if (requests++ > 0) delay(ChartsClient.MUSICBRAINZ_PACE_MS)
                    val page = client.artistsForTag(name, offset = offset)
                    union += page.map { normalizeForMatch(it) }
                    // A short page is the last page; asking for the next one would
                    // spend a second of someone's time to be told the same thing.
                    if (page.size < ChartsClient.MUSICBRAINZ_PAGE) break
                    offset += ChartsClient.MUSICBRAINZ_PAGE
                }
                // Aliases are only worth the round trip when the primary name found
                // nothing — MusicBrainz taggers are inconsistent about which
                // spelling they reach for, but they rarely split a scene evenly.
                if (union.isNotEmpty()) break
            }
            if (union.isNotEmpty()) {
                val held = Cached(union.toSet())
                mutex.withLock { artistSets[genreId] = held }
                disk.putArtistSet(genreId, held.value, held.at)
            }
            union
        }
    }

    private suspend fun cachedArtistSet(genreId: String): Set<String>? =
        mutex.withLock { artistSets[genreId]?.takeIf { it.fresh(TTL_ARTIST_SET_MS) }?.value }

    /**
     * The genre's artist set if it is already in hand, and null if fetching it
     * would cost a request.
     *
     * A discovery shelf wants this set to push loosely-tagged pop acts down its
     * row, but it wants it for free. Fetching it costs a MusicBrainz page walk
     * paced at a request a second, which is most of a shelf's budget spent on
     * *reordering* — so a shelf asks this instead, gets the set once anything
     * else has paid for it (a genre chart opened, an earlier shelf in the same
     * session, or the same shelf yesterday), and goes without until then. Never
     * a network call, so it is safe to call on any path that is racing a clock.
     */
    suspend fun artistsForIfCached(genreId: String): Set<String>? {
        restore()
        return cachedArtistSet(genreId)
    }

    /**
     * What an artist is generally tagged as. Cached for a month: an artist's
     * tag cloud is a summary of a career, and it does not move in a week.
     */
    suspend fun artistTags(artist: String, apiKey: String): List<String> {
        val key = normalizeForMatch(artist)
        if (key.isEmpty()) return emptyList()
        restore()
        cachedArtistTags(key)?.let { return it }

        return artistTagFlight.run(key) {
            cachedArtistTags(key)?.let { return@run it }

            val tags = client.artistTopTags(artist, apiKey)
            if (tags.isNotEmpty()) {
                val held = Cached(tags)
                mutex.withLock { artistTags[key] = held }
                disk.putArtistTags(key, held.value, held.at)
            }
            tags
        }
    }

    private suspend fun cachedArtistTags(key: String): List<String>? =
        mutex.withLock { artistTags[key]?.takeIf { it.fresh(TTL_ARTIST_TAGS_MS) }?.value }

    /** A genre's all-time tag chart, if a Last.fm key is configured. */
    suspend fun tagChart(genreId: String, names: List<String>, apiKey: String): List<ChartEntry> {
        if (apiKey.isBlank()) return emptyList()
        restore()
        cachedTagChart(genreId)?.let { return it }

        return tagChartFlight.run(genreId) {
            cachedTagChart(genreId)?.let { return@run it }

            // Aliases are a fallback, not a union: a tag chart is already ranked, and
            // concatenating two rankings produces an order that means nothing. The
            // first alias that returns a usable chart wins.
            var best: List<ChartEntry> = emptyList()
            for (name in names.take(3)) {
                val chart = client.tagTopTracks(name, apiKey)
                if (chart.size > best.size) best = chart
                if (best.size >= 50) break
            }
            if (best.isNotEmpty()) {
                val held = Cached(best)
                mutex.withLock { tagCharts[genreId] = held }
                disk.putTagChart(genreId, held.value, held.at)
            }
            best
        }
    }

    private suspend fun cachedTagChart(genreId: String): List<ChartEntry>? =
        mutex.withLock { tagCharts[genreId]?.takeIf { it.fresh(TTL_TAG_CHART_MS) }?.value }

    /** Drop everything, so a pull-to-refresh actually refetches. */
    suspend fun invalidate() {
        mutex.withLock {
            sitewide.clear()
            artistSets.clear()
            tagCharts.clear()
            artistTags.clear()
        }
        // The point of a refresh is to go back to the sources. Leaving the file
        // behind would have the next cold start restore exactly what the user
        // just asked to be rid of.
        disk.clear()
        restoreMutex.withLock { restored = true }
    }
}
