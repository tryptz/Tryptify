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
    private val artistTags = mutableMapOf<String, Cached<List<String>>>()

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
     * [names] is the genre's own name followed by its aliases. The primary
     * spelling is paged out in full first; an alias is only tried if that found
     * nothing at all, because taggers are inconsistent about which spelling they
     * reach for but rarely split a scene evenly between two of them.
     */
    suspend fun artistsFor(genreId: String, names: List<String>): Set<String> {
        mutex.withLock { artistSets[genreId]?.takeIf { it.fresh(TTL_ARTIST_SET_MS) } }
            ?.let { return it.value }

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
            mutex.withLock { artistSets[genreId] = Cached(union) }
        }
        return union
    }

    /**
     * What an artist is generally tagged as. Cached for a month: an artist's
     * tag cloud is a summary of a career, and it does not move in a week.
     */
    suspend fun artistTags(artist: String, apiKey: String): List<String> {
        val key = normalizeForMatch(artist)
        if (key.isEmpty()) return emptyList()
        mutex.withLock { artistTags[key]?.takeIf { it.fresh(TTL_ARTIST_TAGS_MS) } }
            ?.let { return it.value }

        val tags = client.artistTopTags(artist, apiKey)
        if (tags.isNotEmpty()) {
            mutex.withLock { artistTags[key] = Cached(tags) }
        }
        return tags
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
        artistTags.clear()
    }
}
