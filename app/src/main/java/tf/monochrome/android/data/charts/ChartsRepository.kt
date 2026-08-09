package tf.monochrome.android.data.charts

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
 * The cache is in memory only. Charts are ephemeral, worthless once stale, and
 * cheap to refetch, so they do not earn a database table and the schema
 * migration that comes with one; the cost is that a cold start refetches.
 */
@Singleton
class ChartsRepository @Inject constructor(
    private val client: ChartsClient,
) {
    private val mutex = Mutex()
    private val sitewide = mutableMapOf<ChartWindow, Cached<SitewideChart>>()
    private val artistSets = mutableMapOf<String, Cached<Set<String>>>()
    private val tagCharts = mutableMapOf<String, Cached<List<ChartEntry>>>()

    companion object {
        /** The seven-day window genuinely moves day to day; the longer ones do not. */
        private const val TTL_SHORT_WINDOW_MS = 6L * 60 * 60 * 1000
        private const val TTL_LONG_WINDOW_MS = 24L * 60 * 60 * 1000
        private const val TTL_TAG_CHART_MS = 24L * 60 * 60 * 1000
        private const val TTL_ARTIST_SET_MS = 7L * 24 * 60 * 60 * 1000
    }

    private class Cached<T>(val value: T, val at: Long = System.currentTimeMillis()) {
        fun fresh(ttlMs: Long) = System.currentTimeMillis() - at < ttlMs
    }

    /** The global windowed chart, shared by every genre asking for this window. */
    suspend fun sitewide(window: ChartWindow): SitewideChart {
        val ttl = if (window == ChartWindow.SEVEN_DAYS) TTL_SHORT_WINDOW_MS else TTL_LONG_WINDOW_MS
        mutex.withLock { sitewide[window]?.takeIf { it.fresh(ttl) } }?.let { return it.value }

        val fetched = client.sitewideRecordings(window)
        // An empty result is not cached: it usually means the service was
        // briefly unreachable, and pinning that for six hours would turn a blip
        // into an outage for the rest of the session.
        if (fetched.entries.isNotEmpty()) {
            mutex.withLock { sitewide[window] = Cached(fetched) }
        }
        return fetched
    }

    /**
     * The set of artists belonging to a genre, normalised for matching.
     *
     * [names] is the genre's own name followed by its aliases; they are tried in
     * order and the results unioned, because MusicBrainz taggers are inconsistent
     * about which spelling of a genre they reach for and no single one of them
     * covers a scene on its own.
     */
    suspend fun artistsFor(genreId: String, names: List<String>): Set<String> {
        mutex.withLock { artistSets[genreId]?.takeIf { it.fresh(TTL_ARTIST_SET_MS) } }
            ?.let { return it.value }

        val union = mutableSetOf<String>()
        for (name in names.take(3)) {
            union += client.artistsForTag(name).map { normalizeForMatch(it) }
        }
        if (union.isNotEmpty()) {
            mutex.withLock { artistSets[genreId] = Cached(union) }
        }
        return union
    }

    /** A genre's all-time tag chart, if a Last.fm key is configured. */
    suspend fun tagChart(genreId: String, names: List<String>, apiKey: String): List<ChartEntry> {
        if (apiKey.isBlank()) return emptyList()
        mutex.withLock { tagCharts[genreId]?.takeIf { it.fresh(TTL_TAG_CHART_MS) } }
            ?.let { return it.value }

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
            mutex.withLock { tagCharts[genreId] = Cached(best) }
        }
        return best
    }

    /** Drop everything, so a pull-to-refresh actually refetches. */
    suspend fun invalidate() = mutex.withLock {
        sitewide.clear()
        artistSets.clear()
        tagCharts.clear()
    }
}
