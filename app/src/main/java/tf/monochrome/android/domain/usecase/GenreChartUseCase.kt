package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.charts.ChartEntry
import tf.monochrome.android.data.charts.ChartSource
import tf.monochrome.android.data.charts.ChartWindow
import tf.monochrome.android.data.charts.ChartsRepository
import tf.monochrome.android.data.charts.DiscoveryCache
import tf.monochrome.android.data.charts.GenreChart
import tf.monochrome.android.data.charts.GenrePool
import tf.monochrome.android.data.charts.SingleFlight
import tf.monochrome.android.data.charts.mapConcurrent
import tf.monochrome.android.data.charts.normalizeForMatch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.GenreNode
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a genre's Top 100 for a time window.
 *
 * The two sources this composes each hold half of the answer, and the halves do
 * not overlap. ListenBrainz can say what the world played in a bounded window
 * but not what genre any of it was; Last.fm can rank any genre you can name but
 * has no notion of when. So a windowed chart is the global window narrowed by
 * the genre's artists, and when that intersection is too thin to be a chart —
 * which is the normal case for a small scene, where a week of global listening
 * may contain almost none of it — this falls back to the all-time ranking and
 * *says that it did*. A chart labelled "7 days" that is quietly showing all-time
 * data is worse than no chart, because a listener has no way to tell.
 */
@Singleton
class GenreChartUseCase @Inject constructor(
    private val charts: ChartsRepository,
    private val genreGraph: GenreGraphRepository,
    private val preferences: PreferencesManager,
    private val music: MusicRepository,
    private val disk: DiscoveryCache,
) {
    companion object {
        /**
         * Below this many rows a window isn't a Top 100, it's a rumour. Ten is
         * enough to see a shape and short enough that mid-sized genres still get
         * a real window instead of being bounced to all-time.
         */
        const val MIN_WINDOW_ENTRIES = 10

        const val DEFAULT_LIMIT = 100

        /**
         * How much of a chart to turn into a play queue. Deep enough to be a
         * listening session, and no longer bounded by how many catalogue
         * lookups a caller can afford to wait through — resolution runs
         * concurrently and is cached, so this is a depth rather than a budget.
         */
        const val POOL_DEPTH = 60

        /**
         * How many catalogue lookups to have in flight while turning chart rows
         * into playable tracks. Same reasoning as [VERIFY_CONCURRENCY]: enough
         * to hide the latency, few enough to arrive as a stream and not a burst.
         *
         * This is also the early-stop granularity — a pool that has already
         * filled stops after the batch that filled it rather than resolving the
         * whole chart and discarding the tail.
         */
        const val RESOLVE_CONCURRENCY = 8

        /**
         * How much deeper than the requested pool to read the chart.
         *
         * Not every charted track is in the catalogue, and an entry that isn't
         * resolves to nothing rather than to something near it. Reading twice
         * the depth means an ordinary miss rate still fills the pool without a
         * second chart request.
         */
        const val RESOLVE_OVERREAD = 2

        /**
         * How far down a tag chart to verify artists. Each new artist costs a
         * request; the head is what gets read and what playback opens on.
         */
        const val VERIFY_DEPTH = 40

        /**
         * Most entries one artist may hold. Without this, cross-checking makes
         * clumping worse rather than better: promoting the confirmed artists in
         * hard techno floated four consecutive Sara Landry tracks to the top,
         * which is a truthful chart and a bad one. A Top 100 that is one artist
         * repeated tells you less than a Top 100 that is a scene.
         */
        const val MAX_PER_ARTIST = 3

        /**
         * How many artist lookups to have in flight at once. Enough to hide the
         * latency, few enough not to arrive at Last.fm as a burst.
         */
        const val VERIFY_CONCURRENCY = 8

        /**
         * A ceiling on catalogue lookups in flight for chart resolution across
         * the whole app, however many shelves are building.
         *
         * [RESOLVE_CONCURRENCY] bounds one pool. Six shelves building at once,
         * each bounded to eight, is forty-eight simultaneous searches at one
         * self-hosted instance — which is not concurrency but a queue with
         * extra steps: every shelf gets slower, they all miss their budget
         * together, and they all fall back to searching the genre's name at
         * the same moment. This is what makes a per-shelf timeout mean
         * something.
         */
        const val RESOLVE_GATE = 16

        /**
         * How deep to read a tag chart when it is being read for the *artists*
         * it names rather than the records.
         *
         * [capPerArtist] keeps any one artist to three rows, so a hundred rows
         * is around thirty distinct artists — deep enough that paging a genre
         * walks to new names for several pages instead of running dry. It costs
         * nothing extra: the client always requests a hundred, and the cache is
         * keyed on the genre rather than on the depth asked for.
         */
        const val ARTIST_TIER_CHART_DEPTH = 100

        /**
         * How many of a genre's artists one shelf draws on.
         *
         * Six at three tracks each is eighteen candidates for a twenty-card
         * shelf. Fewer and one artist's catalogue is most of the row; more and
         * the tier costs what the tier it exists to rescue costs.
         */
        const val ARTIST_TIER_ARTISTS = 6

        /** Most tracks to take from one artist. Same reasoning as [MAX_PER_ARTIST]. */
        const val ARTIST_TIER_TRACKS = 3

        /**
         * How far down the artist list to spend confirmation requests.
         *
         * Only the artists this tier can actually reach are worth checking, and
         * a shelf reaches six of them.
         */
        const val ARTIST_TIER_VERIFY_DEPTH = 10

        /**
         * How many confirmations the check needs before its silence counts as
         * evidence. Below this it found nothing rather than found nothing
         * *good*, and dropping names on that would delete a genre whose artists
         * simply are not on Last.fm.
         */
        const val ARTIST_TIER_MIN_CONFIRMED = 3

        /**
         * How many artist lookups to have in flight at once. Two round trips
         * each, so this is deliberately smaller than [RESOLVE_CONCURRENCY].
         */
        const val ARTIST_TIER_CONCURRENCY = 6

        /**
         * How much deeper than the shelf the *feed* reads its chart.
         *
         * [RESOLVE_OVERREAD] exists so an ordinary catalogue miss rate still
         * fills a pool without a second chart request, and it is the right
         * trade for "play this genre", where a short queue is the only failure
         * mode. A shelf has a live top-up tier beside it now, so a short pool
         * is no longer a failure — and at twenty cards a shelf, doubling the
         * read is twenty extra catalogue searches per shelf, six shelves at a
         * time, which is what put the budget out of reach to begin with.
         */
        const val FEED_RESOLVE_OVERREAD = 1

        /**
         * How long cross-checking may take before the chart is shown without it.
         *
         * A chart that never appears is worse than one that appears unverified:
         * the ordering is still the source's own, and the screen simply doesn't
         * claim to be cross-checked. Whatever the lookups return afterwards
         * lands in the cache for the next visit.
         */
        const val CROSS_CHECK_BUDGET_MS = 4_000L

        /**
         * How long a catalogue answer is worth keeping across sessions.
         *
         * Within a session these are held for the life of the process and not
         * expired at all, because a chart row is a fixed pair of strings and
         * the catalogue's answer for it does not change while the app is open.
         * Across sessions it can: a record gets added, a licence lapses. A week
         * is long enough that reopening Discover is free all week and short
         * enough that the catalogue is re-asked about a genre roughly as often
         * as its chart moves.
         */
        const val TTL_CATALOGUE_MS = 7L * 24 * 60 * 60 * 1000
    }

    /**
     * Catalogue answers for chart rows, hits and misses alike. Held for the
     * process rather than expired: a chart row is a fixed pair of strings and
     * the catalogue's answer for it does not change while the app is open.
     *
     * Declared here, above every use, rather than beside [resolve] at the foot
     * of the class. Nothing constructs this use case and immediately resolves,
     * so the late declaration was harmless — but it is the same shape that took
     * DiscoverViewModel down, and the fix costs nothing.
     */
    private val resolved = mutableMapOf<String, Stamped<UnifiedTrack?>>()
    private val resolveMutex = Mutex()

    /**
     * The catalogue's top tracks per artist name, misses included, declared up
     * here for the same reason [resolved] is.
     *
     * A genre and its neighbours are largely the same people — neoclassical,
     * modern classical and film score share most of their names — so on a feed
     * building six shelves at once, most of this is already answered by the
     * time the third shelf asks.
     */
    private val artistTopTracks = mutableMapOf<String, Stamped<List<UnifiedTrack>>>()
    private val artistMutex = Mutex()

    /** See [RESOLVE_GATE]. Shared by every pool and every shelf in the process. */
    private val resolveGate = Semaphore(RESOLVE_GATE)

    /**
     * One lookup per question, however many shelves ask it at once.
     *
     * The memos above are check-then-fetch, which is a cache in sequence and
     * not one under concurrency: six shelves start together, all six look
     * before any of them has answered, and all six spend a permit on the same
     * search. That is the case the memos were written for — the comment on
     * [resolved] says a genre and its neighbours overlap heavily — and it is
     * exactly the case they were missing.
     */
    private val resolveFlight = SingleFlight<String, UnifiedTrack?>()
    private val artistFlight = SingleFlight<String, List<UnifiedTrack>>()

    /** Last session's catalogue answers, folded in once. See [DiscoveryCache]. */
    private val restoreMutex = Mutex()

    @Volatile
    private var restored = false

    /** A remembered answer and when it was learned. */
    private class Stamped<T>(val value: T, val at: Long = System.currentTimeMillis()) {
        fun fresh(ttlMs: Long) = System.currentTimeMillis() - at < ttlMs
    }

    /**
     * Seed the catalogue memos from what the last session learned.
     *
     * These are the expensive half of a genre row — a search per charted record
     * and two per artist, all of them behind [RESOLVE_GATE] — and they were
     * thrown away on every process death, so the second time you opened
     * Discover today it cost exactly what the first time cost. Restored, it
     * costs nothing: the answers are what the row is made of, and the catalogue
     * does not change its mind about which record "AIROD Acid Storm" is.
     */
    private suspend fun restore() {
        if (restored) return
        restoreMutex.withLock {
            if (restored) return
            val snapshot = disk.restore()
            resolveMutex.withLock {
                snapshot.resolved.forEach { (key, held) ->
                    if (key !in resolved) resolved[key] = Stamped(held.value, held.at)
                }
            }
            artistMutex.withLock {
                snapshot.artistTopTracks.forEach { (key, held) ->
                    if (key !in artistTopTracks) artistTopTracks[key] = Stamped(held.value, held.at)
                }
            }
            restored = true
        }
    }

    /**
     * @param crossCheck whether to spend requests confirming the chart's artists
     *   really belong to the genre. The Top 100 screen wants that and can wait
     *   for it; a discovery shelf is racing a budget it shares with the rest of
     *   a page, and the check only ever *reorders* — see the cheap substitute
     *   in [tagChart] below.
     */
    suspend fun chart(
        genreId: String,
        window: ChartWindow,
        limit: Int = DEFAULT_LIMIT,
        crossCheck: Boolean = true,
    ): GenreChart {
        val node = genreGraph.graph[genreId]
            ?: return GenreChart(
                genreId = genreId,
                genreName = genreId,
                requested = window,
                shown = window,
                source = ChartSource.WINDOWED_LISTENS,
            )

        val names = node.queries()
        val apiKey = runCatching { preferences.lastFmChartsApiKey.first() }.getOrNull().orEmpty()

        /**
         * The all-time chart, cross-checked against MusicBrainz before it is
         * handed back. Null when there is no tag chart to be had.
         */
        suspend fun tagChart(shownAs: ChartWindow): GenreChart? {
            val tag = charts.tagChart(genreId, names, apiKey)
            if (tag.isEmpty()) return null
            val entries = capPerArtist(tag.take(limit), MAX_PER_ARTIST)
            // Without [crossCheck], take the MusicBrainz artist set only if
            // something has already paid for it. It is the half of the check
            // that costs a paced page walk, and a shelf that spends its budget
            // reordering a row it then has no time to fetch has made the page
            // worse, not better. Warm — a genre chart opened, an earlier shelf
            // in the same session — and the promotion is free.
            val confirmed = if (crossCheck) {
                withTimeoutOrNull(CROSS_CHECK_BUDGET_MS) {
                    confirmChartArtists(node, entries, apiKey)
                } ?: emptySet()
            } else {
                charts.artistsForIfCached(genreId).orEmpty()
            }
            return GenreChart(
                genreId = genreId,
                genreName = node.name,
                requested = window,
                shown = shownAs,
                source = ChartSource.TAG_CHART,
                entries = promoteConfirmed(entries, confirmed),
                // Only the full check earns the claim. A row promoted off a
                // cached artist set is better ordered for it, but the screen
                // that says "cross-checked" must mean the check it names.
                crossChecked = crossCheck && confirmed.isNotEmpty(),
            )
        }

        // All time is the tag chart's native window, so ask for it directly
        // before paying for a sitewide pull.
        if (window == ChartWindow.ALL_TIME) {
            tagChart(shownAs = window)?.let { return it }
        }

        // Independent of each other: one is a global listening window, the
        // other is which artists a genre owns. Run together rather than in
        // sequence — the MusicBrainz side deliberately paces itself at a
        // request a second, and there is no reason ListenBrainz should wait
        // behind that.
        val (sitewide, artists) = coroutineScope {
            val sitewideJob = async { charts.sitewide(window) }
            val artistsJob = async { charts.artistsFor(genreId, names) }
            sitewideJob.await() to artistsJob.await()
        }
        val filtered = narrowToGenre(sitewide.entries, artists, limit)

        val windowed = GenreChart(
            genreId = genreId,
            genreName = node.name,
            requested = window,
            shown = window,
            source = ChartSource.WINDOWED_LISTENS,
            entries = filtered,
            fromTs = sitewide.fromTs,
            toTs = sitewide.toTs,
        )

        if (filtered.size >= MIN_WINDOW_ENTRIES) return windowed

        // Too thin to stand on its own. Prefer a real all-time ranking over a
        // three-row "chart", but keep the thin window if there is no tag chart
        // to fall back to — a short honest list still beats an empty screen.
        return tagChart(shownAs = ChartWindow.ALL_TIME) ?: windowed
    }

    /** Renumber after filtering so ranks read 1..n rather than the source's gaps. */
    private fun List<ChartEntry>.reranked(): List<ChartEntry> =
        mapIndexed { index, entry -> entry.copy(rank = index + 1) }

    /**
     * Which of these artists genuinely belong to [genreId].
     *
     * Public because the Discover shelves need exactly the same judgement the
     * charts do. They were built from a catalogue search on the genre's *name*,
     * which ranks by how well a title or album matches those words — asking for
     * hardstyle returned "Hardstyle Fish", "I'm so lucky! - Hardstyle" and two
     * compilations whose album art happened to say "Hardstyle". Names are
     * returned normalised, for comparison against [normalizeForMatch].
     */
    suspend fun confirmedArtists(genreId: String, artistNames: List<String>): Set<String> {
        val node = genreGraph.graph[genreId] ?: return emptySet()
        val apiKey = runCatching { preferences.lastFmChartsApiKey.first() }.getOrNull().orEmpty()
        return confirmArtists(node, artistNames, apiKey)
    }

    private suspend fun confirmChartArtists(
        node: GenreNode,
        entries: List<ChartEntry>,
        apiKey: String,
    ): Set<String> = confirmArtists(node, entries.map { it.artistName }, apiKey)

    /**
     * Which of a chart's artists genuinely belong to this genre.
     *
     * Two sources of evidence, unioned, because each misses what the other
     * catches. MusicBrainz's artist-tag search is curated and precise but thin:
     * asked for hard techno it names 167 artists and still doesn't include
     * Klangkuenstler, who is about as hard techno as it gets. An artist's own
     * Last.fm tag cloud covers nearly everyone and is dominated by what they
     * actually are — Klangkuenstler comes back `techno`, `hard techno`, while
     * FKA twigs comes back `trip-hop`, `dream pop` and Baby Jane, sitting at
     * number seven in hard techno, comes back `hard rock`, `sleaze rock`.
     *
     * Tags are matched through the genre graph rather than by string, so
     * `techno` confirms a hard techno artist via the parent relation and
     * `dub techno` confirms via the graph's sideways edges. That is the
     * difference between recognising a scene and recognising a spelling.
     *
     * Only the head of the chart is checked. It costs a request per new artist,
     * and the tail is not what anyone reads or what playback opens on.
     */
    private suspend fun confirmArtists(
        node: GenreNode,
        artistNames: List<String>,
        apiKey: String,
        depth: Int = VERIFY_DEPTH,
        payForArtistSet: Boolean = true,
    ): Set<String> {
        val relatives = relativesOf(node)
        // The MusicBrainz half is three pages paced at a request a second, and
        // it is the half a shelf cannot afford; the Last.fm half is one request
        // per unseen artist, cached for a month. With [payForArtistSet] off the
        // set is taken only if something else has already bought it, and the
        // check runs thinner — still catching the case that matters, which is
        // an artist whose own tag cloud says `hard rock` standing at the top of
        // a hard techno chart.
        val fromMusicBrainz = if (payForArtistSet) {
            charts.artistsFor(node.id, node.queries())
        } else {
            charts.artistsForIfCached(node.id).orEmpty()
        }

        val artists = artistNames.take(depth).distinctBy { normalizeForMatch(it) }

        // Concurrent, and bounded by permits rather than by batches. This loop
        // was sequential, and each unseen artist is a network round trip: forty
        // of them in a row is the ten-to-twenty seconds of spinner the chart
        // screen was showing before it rendered anything. Batching fixed most
        // of that and left the rest — a batch of eight finishes at the pace of
        // its slowest member, so seven lookups' worth of capacity waits out the
        // eighth before the ninth artist is even asked about. Here a finished
        // lookup hands its permit straight to the next name.
        return artists.mapConcurrent(VERIFY_CONCURRENCY) { artist ->
            val key = normalizeForMatch(artist)
            when {
                key in fromMusicBrainz -> key
                charts.artistTags(artist, apiKey).any { tag ->
                    genreGraph.graph.resolve(tag)?.id in relatives
                } -> key
                else -> null
            }
        }.filterNotNull().toSet()
    }

    /**
     * The genre plus everything adjacent to it in the graph.
     *
     * An artist is rarely tagged with the exact leaf you are looking at. Someone
     * who makes hard techno is tagged `techno` far more often than `hard techno`,
     * and a dub techno producer is tagged `dub techno`, `minimal techno` and
     * `ambient techno` in roughly equal measure. Accepting the parent, the
     * children and the sideways edges is what makes the check recognise a scene
     * instead of a single word — while still rejecting `pop` and `reggaeton`,
     * which are nowhere near this part of the map.
     */
    private fun relativesOf(node: GenreNode): Set<String> =
        buildSet {
            add(node.id)
            addAll(node.parents)
            addAll(node.nearEdges().map { it.first })
            genreGraph.graph.allGenres.forEach { candidate ->
                if (node.id in candidate.parents) add(candidate.id)
            }
        }

    /**
     * The genre's charting tracks, in rank order, as things the player can take.
     *
     * This exists so that "play this genre" can mean *the records that define
     * it* rather than whatever the catalogue returns for the genre's name as a
     * search string. Those are very different lists: searching a catalogue for
     * "hard techno" matches titles and album names, which is a magnet for
     * machine-generated filler literally called "Hard Techno", while the chart
     * is ranked by what people actually played.
     *
     * Entries that can't be found in the catalogue are dropped, so the result
     * may be shorter than [depth] — a short list of the real thing beats a full
     * one padded with near-misses.
     *
     * [skip] takes the pool from further down the same chart, which is what
     * makes a genre pageable: page two is the next stretch of the ranking, not
     * the head again in a different order.
     */
    suspend fun playablePool(
        genreId: String,
        window: ChartWindow = ChartWindow.ALL_TIME,
        depth: Int = POOL_DEPTH,
        skip: Int = 0,
        overread: Int = RESOLVE_OVERREAD,
    ): List<UnifiedTrack> {
        if (depth <= 0) return emptyList()
        val wanted = (skip + depth) * overread
        val entries = chart(genreId, window, limit = wanted).entries.drop(skip)
        return resolvePool(entries, depth)
    }

    /**
     * Turn chart rows into catalogue tracks, in rank order, up to [depth].
     *
     * Split out of [playablePool] so a caller that has already fetched the
     * chart — the discovery feed reads the same one for its artists — can
     * resolve from it without asking for it twice.
     *
     * Resolved in bounded parallel batches, stopping as soon as the pool is
     * full. Sequentially this was one network round trip per row — thirty of
     * them back to back, which is why only "play this genre" could afford to
     * call it and the feed could not.
     */
    suspend fun resolvePool(entries: List<ChartEntry>, depth: Int): List<UnifiedTrack> {
        if (depth <= 0 || entries.isEmpty()) return emptyList()
        val pool = LinkedHashMap<String, UnifiedTrack>(depth)
        coroutineScope {
            // A sliding window rather than fixed batches. Batching awaited all
            // eight lookups before starting the ninth, so the whole pool
            // advanced at the pace of the slowest row in each batch — and with
            // [RESOLVE_GATE] shared across a page, the permits those finished
            // lookups were still holding are permits another shelf is blocked
            // waiting for. Here each row that lands starts the next one.
            val inFlight = ArrayDeque<Deferred<UnifiedTrack?>>()
            var next = 0
            fun start() {
                if (next >= entries.size) return
                val entry = entries[next++]
                inFlight.addLast(async { resolve(entry) })
            }
            repeat(RESOLVE_CONCURRENCY) { start() }

            // Awaited in rank order, because a chart is a ranking and the pool
            // is the chart: taking rows as they happen to return would reorder
            // it by whichever search was quickest.
            while (inFlight.isNotEmpty()) {
                val track = inFlight.removeFirst().await()
                if (track != null && !pool.containsKey(track.id)) pool[track.id] = track
                if (pool.size >= depth) {
                    // Full. The rows still in flight are the tail this would
                    // have discarded anyway, and cancelling them hands their
                    // permits back to whichever shelf is waiting on one.
                    inFlight.forEach { it.cancel() }
                    break
                }
                start()
            }
        }
        return pool.values.take(depth)
    }

    /**
     * A genre's popular music, by two roads at once.
     *
     * Both halves start from the same all-time tag chart — fetched here exactly
     * once, so they do not race each other to the same request — and then
     * diverge. One resolves the chart's rows into the catalogue directly, which
     * is the strongest evidence available and the likeliest to come up short: a
     * row the catalogue does not carry resolves to nothing rather than to
     * something near it. The other reads the same chart for the *artists* it
     * names and asks the catalogue for their own most-played tracks, which
     * costs a request per artist instead of one per row and does not come up
     * short for any genre with artists in it.
     *
     * They run concurrently, so a full shelf costs the slower of the two rather
     * than the sum. In sequence this was the bug: resolution spent the whole
     * budget, missed the floor a shelf needs, and the row fell through to a
     * catalogue search for the genre's *name* — which ranks by how well a title
     * matches those words, and is precisely the query production-library filler
     * is written to win. Asking for Neoclassical returned "Neoclassical Cello".
     *
     * Either half may fail on its own without taking the other down; only a
     * cancellation — the feed being replaced out from under us — travels.
     */
    suspend fun popularFor(
        genreId: String,
        depth: Int,
        page: Int = 0,
        variation: Int = 0,
    ): GenrePool {
        if (depth <= 0) return GenrePool()
        val node = genreGraph.graph[genreId] ?: return GenrePool()
        val apiKey = runCatching { preferences.lastFmChartsApiKey.first() }.getOrNull().orEmpty()
        val entries = chart(
            genreId = genreId,
            window = ChartWindow.ALL_TIME,
            limit = ARTIST_TIER_CHART_DEPTH,
            crossCheck = false,
        ).entries
        if (entries.isEmpty()) return GenrePool()

        return coroutineScope {
            val charted = async {
                emptyOnFailure {
                    resolvePool(
                        entries.drop(page * depth).take(depth * FEED_RESOLVE_OVERREAD),
                        depth,
                    )
                }
            }
            // [variation] walks the artist list the same way another page
            // would. It is what keeps "shuffle this genre" from dealing the
            // same hand twice — the chart itself is a fixed ranking, so
            // without it the only thing that differed between two plays of a
            // genre was the order the same records came out in.
            val byArtist = async {
                emptyOnFailure { artistTracks(node, entries, apiKey, depth, page + variation) }
            }
            GenrePool(charted = charted.await(), fromArtists = byArtist.await())
        }
    }

    /**
     * The genre's biggest artists, as the catalogue's own most-played tracks
     * for each of them.
     *
     * The genre's name is never sent to the catalogue here, and that is the
     * whole point: a search for "Neoclassical" ranks records that *say*
     * neoclassical, while a search for "Ólafur Arnalds" ranks records that
     * *are* — and the tag chart has already told us which names to ask for.
     *
     * The membership check matters more here than anywhere else. Last.fm tags
     * are crowd-applied, so the head of a genre's chart collects whoever is on
     * everyone's radar — hard techno comes back led by FKA twigs. A ranked
     * chart carries that error at one row; taking six names off the top and
     * filling a row from their catalogues amplifies it. So the names are ranked
     * by [confirmArtists] first, against evidence that is cheap or already
     * bought.
     *
     * Round-robined, so one prolific artist doesn't take the first eight cards.
     */
    private suspend fun artistTracks(
        node: GenreNode,
        entries: List<ChartEntry>,
        apiKey: String,
        depth: Int,
        page: Int,
    ): List<UnifiedTrack> {
        val named = chartArtists(entries)
        if (named.isEmpty()) return emptyList()
        val confirmed = runCatching {
            confirmArtists(
                node = node,
                artistNames = named,
                apiKey = apiKey,
                depth = ARTIST_TIER_VERIFY_DEPTH,
                payForArtistSet = false,
            )
        }.getOrDefault(emptySet())
        val ranked = rankGenreArtists(
            names = named,
            confirmed = confirmed,
            want = ARTIST_TIER_VERIFY_DEPTH,
            minConfirmed = ARTIST_TIER_MIN_CONFIRMED,
        )
        val window = artistWindowFor(ranked, page, ARTIST_TIER_ARTISTS, ARTIST_TIER_TRACKS)
        if (window.artists.isEmpty()) return emptyList()

        val bands = window.artists.mapConcurrent(ARTIST_TIER_CONCURRENCY) { topTracksFor(it) }
        return roundRobin(
            bands.map { it.drop(window.trackSkip).take(window.perArtist) },
            depth,
        ).distinctBy { it.id }
    }

    /**
     * The catalogue's own most-played tracks for an artist, by name.
     *
     * Two requests rather than one, and worth it. A search's track list is
     * ranked by how well a *title* matches the words of the query, so for an
     * artist's name it hands back whichever of their records reads most like
     * that name, along with every tribute and karaoke cover that mentions it.
     * `ArtistDetail.topTracks` is the catalogue's own popularity ranking for
     * that artist, which is the list this whole tier is trying to reach. The
     * artists are fetched concurrently, so the cost is two round trips of
     * latency rather than two per artist.
     *
     * Falls back to the search's own tracks, credited-artist filtered, when the
     * artist page can't be had — a thinner answer beats an empty row.
     *
     * Memoised, misses included, for the same reason [resolve] is.
     */
    private suspend fun topTracksFor(artistName: String): List<UnifiedTrack> {
        val key = normalizeForMatch(artistName)
        if (key.isEmpty()) return emptyList()
        restore()
        memoisedArtistTracks(key)?.let { return it }

        return artistFlight.run(key) {
            memoisedArtistTracks(key)?.let { return@run it }

            val tracks = resolveGate.withPermit {
                val search = music.searchQobuz(artistName).getOrNull()
                val credited = search?.tracks.orEmpty()
                    .filter { matchesArtistName(it.displayArtist, artistName) }
                val seed = search?.artists?.firstOrNull { matchesArtistName(it.name, artistName) }
                val top = seed?.let { music.getQobuzArtist(it.id).getOrNull()?.topTracks }
                    .orEmpty()
                    .filter { matchesArtistName(it.displayArtist, artistName) }
                (top.ifEmpty { credited }).map { it.toQobuzUnifiedTrack() }
            }

            val held = Stamped(tracks)
            artistMutex.withLock { artistTopTracks[key] = held }
            // An empty answer stays in memory but off the disk. In this session
            // it is a real answer and worth not asking twice; carried into the
            // next one it would pin an artist the instance happened to be
            // unable to serve for a week.
            if (tracks.isNotEmpty()) disk.putArtistTopTracks(key, held.value, held.at)
            tracks
        }
    }

    private suspend fun memoisedArtistTracks(key: String): List<UnifiedTrack>? =
        artistMutex.withLock { artistTopTracks[key]?.takeIf { it.fresh(TTL_CATALOGUE_MS) }?.value }

    /**
     * Run [block], and treat any failure of its own as an empty answer.
     *
     * The two halves of [popularFor] share a scope, so an exception thrown by
     * one would cancel the other and take the shelf with it. A cancellation is
     * different in kind — it is the feed being replaced — and has to keep
     * travelling.
     */
    private suspend fun <T> emptyOnFailure(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    /**
     * Find the catalogue's copy of a charted track.
     *
     * The search backend ranks by relevance to the whole query, which for
     * "AIROD Acid Storm" usually puts the right record first but will happily
     * return something else when the catalogue simply doesn't have it. So the
     * results are scanned for one that agrees with the chart on *both* artist
     * and title, and if none does this returns null rather than the closest
     * thing to hand. Playing the wrong track is worse than playing nothing:
     * silence is legible, a wrong record looks like the chart is nonsense.
     *
     * Memoised on [ChartEntry.matchKey], misses included. A genre and its
     * neighbours overlap heavily — the same record charts under techno, hard
     * techno and industrial techno — so on a feed that builds six shelves at
     * once most rows are already answered. Remembering the misses matters as
     * much as the hits: a track the catalogue simply does not carry would
     * otherwise be re-searched once per shelf, forever.
     */
    suspend fun resolve(entry: ChartEntry): UnifiedTrack? {
        val key = entry.matchKey
        restore()
        memoisedResolution(key)?.let { return it.value }

        return resolveFlight.run(key) {
            memoisedResolution(key)?.let { return@run it.value }

            // Behind the gate, and only past the memo: an answer already in hand
            // must never queue behind six shelves' worth of network.
            val answered = resolveGate.withPermit { music.searchQobuz(entry.matchQuery) }
            val track = answered.getOrNull()?.tracks
                ?.firstOrNull { agrees(entry, it.artists.firstOrNull()?.name ?: "", it.title) }
                ?.toQobuzUnifiedTrack()

            val held = Stamped(track)
            resolveMutex.withLock { resolved[key] = held }
            // A miss travels to the next session only when the catalogue
            // actually answered. In memory the two are worth treating alike —
            // an unreachable instance now is unreachable for the rest of this
            // page — but on disk they are not: "nobody stocks this record" is
            // still true tomorrow, while "the instance was down when you opened
            // Discover on the train" would pin a record as missing for a week.
            if (track != null || answered.isSuccess) {
                disk.putResolved(key, held.value, held.at)
            }
            track
        }
    }

    /**
     * The memo's holder rather than its contents, because the contents are
     * legitimately null: "the catalogue does not carry this row" is an answer,
     * and one worth remembering for as long as a hit is.
     */
    private suspend fun memoisedResolution(key: String): Stamped<UnifiedTrack?>? =
        resolveMutex.withLock { resolved[key]?.takeIf { it.fresh(TTL_CATALOGUE_MS) } }
}

/**
 * The artists a chart names, strongest first, once each.
 *
 * Display spelling is kept. [normalizeForMatch] decides who is a duplicate and
 * nothing else — its output is a comparison key, and handing "olafur arnalds"
 * to a catalogue search is not the same request as handing it "Ólafur Arnalds".
 */
internal fun chartArtists(entries: List<ChartEntry>): List<String> = entries
    .map { it.artistName.trim() }
    .filter { it.isNotEmpty() && normalizeForMatch(it).isNotEmpty() }
    .distinctBy { normalizeForMatch(it) }

/**
 * The artists a genre shelf should draw on, best evidence first.
 *
 * Silence where the check has no opinion, and truncation where it does — the
 * same shape as [promoteConfirmed] and for the same reason. Below
 * [minConfirmed] hits, the check found nothing rather than found nothing
 * *good*, and cutting the list on that would empty out any genre whose artists
 * are simply not well tagged. At or above it the unconfirmed tail is dropped
 * rather than demoted, because a shelf reaches six names and a demoted name at
 * position eleven is a name that never gets used.
 */
internal fun rankGenreArtists(
    names: List<String>,
    confirmed: Set<String>,
    want: Int,
    minConfirmed: Int,
): List<String> {
    val (yes, no) = names.partition { normalizeForMatch(it) in confirmed }
    if (yes.size < minConfirmed) return names.take(want)
    return yes.take(want)
}

/** Which artists a page draws on, and how far into each one's tracks. */
internal data class ArtistWindow(
    val artists: List<String>,
    val trackSkip: Int,
    val perArtist: Int,
)

/**
 * Page a genre through its artists before paging it through their catalogues.
 *
 * Paging has two axes here, and page two must be neither page one nor a
 * shorter page one. This walks the ranked artist list first, a page at a time,
 * and only when that runs out does it lap back to the head and take a deeper
 * slice of each artist's tracks — because somebody paging a genre wants more of
 * the genre, and its seventh-biggest artist is more of the genre than the
 * fourth-best track by its biggest.
 */
internal fun artistWindowFor(
    ranked: List<String>,
    page: Int,
    artistsPerPage: Int,
    perArtist: Int,
): ArtistWindow {
    if (ranked.isEmpty() || artistsPerPage <= 0 || perArtist <= 0) {
        return ArtistWindow(emptyList(), 0, perArtist.coerceAtLeast(0))
    }
    val safePage = page.coerceAtLeast(0)
    val pagesPerLap = ((ranked.size + artistsPerPage - 1) / artistsPerPage).coerceAtLeast(1)
    val lap = safePage / pagesPerLap
    val within = safePage % pagesPerLap
    return ArtistWindow(
        artists = ranked.drop(within * artistsPerPage).take(artistsPerPage),
        trackSkip = lap * perArtist,
        perArtist = perArtist,
    )
}

/**
 * Deal one from each band in turn, so no band owns the head of the result.
 *
 * Ragged bands are fine: a band that runs out drops out of the rounds and the
 * rest carry on, which is what keeps a row full when one artist's catalogue is
 * thinner than the others'.
 */
internal fun <T> roundRobin(bands: List<List<T>>, limit: Int): List<T> {
    if (limit <= 0) return emptyList()
    val out = ArrayList<T>(minOf(limit, bands.sumOf { it.size }))
    var index = 0
    while (out.size < limit) {
        var dealt = false
        for (band in bands) {
            val item = band.getOrNull(index) ?: continue
            out.add(item)
            dealt = true
            if (out.size >= limit) return out
        }
        if (!dealt) break
        index++
    }
    return out
}

/**
 * Whether a credited artist is the artist we asked for.
 *
 * Containment in either direction, because the two sides disagree in
 * predictable, harmless ways: the catalogue credits "Ólafur Arnalds & Nils
 * Frahm" where the chart says "Nils Frahm", and a chart says "Above & Beyond"
 * where the catalogue says "Above and Beyond".
 *
 * The length floor is not decoration. Bare containment makes a two-letter act
 * match half the catalogue, and on the artist tier this test is the only thing
 * standing between a row and whatever else a search returned — so anything
 * shorter than [MIN_ARTIST_MATCH_CHARS] has to match outright.
 */
internal fun matchesArtistName(credited: String, wanted: String): Boolean {
    val a = normalizeForMatch(credited)
    val b = normalizeForMatch(wanted)
    if (a.isEmpty() || b.isEmpty()) return false
    // Spaces removed for the equality test, because the fold turns punctuation
    // into a separator and the two sides punctuate differently: "M.K." folds to
    // "m k" where "MK" folds to "mk", and they are the same act.
    if (a == b || a.replace(" ", "") == b.replace(" ", "")) return true
    val shorter = if (a.length <= b.length) a else b
    if (shorter.length < MIN_ARTIST_MATCH_CHARS) return false
    return a.contains(b) || b.contains(a)
}

/**
 * Below this many characters, a name has to match outright rather than by
 * containment. Four is long enough to clear the initials and stage names that
 * would otherwise match everything ("MK", "AFX") and short enough to keep the
 * real short names that only ever appear whole.
 */
internal const val MIN_ARTIST_MATCH_CHARS = 4

/**
 * Whether a catalogue hit is really the charted track.
 *
 * Containment in either direction rather than equality, because the two sides
 * disagree in predictable, harmless ways once folded: one carries a feature
 * credit the other drops, one says "Artist & Friend" where the other says
 * "Artist". Requiring both fields to agree is what keeps a title-only
 * coincidence — a track called "Hard Techno" by nobody in particular — from
 * passing as the genre's number one.
 */
internal fun agrees(entry: ChartEntry, artistName: String, title: String): Boolean {
    val wantArtist = normalizeForMatch(entry.artistName)
    val gotArtist = normalizeForMatch(artistName)
    val wantTitle = normalizeForMatch(entry.title)
    val gotTitle = normalizeForMatch(title)
    if (wantArtist.isEmpty() || gotArtist.isEmpty()) return false
    if (wantTitle.isEmpty() || gotTitle.isEmpty()) return false
    val artistAgrees = wantArtist.contains(gotArtist) || gotArtist.contains(wantArtist)
    val titleAgrees = wantTitle.contains(gotTitle) || gotTitle.contains(wantTitle)
    return artistAgrees && titleAgrees
}

/**
 * Narrow a global windowed chart down to one genre.
 *
 * Matching is on artist rather than recording because that is the join the two
 * services can actually agree on: MusicBrainz will tell you which artists carry
 * a tag, and ListenBrainz reports an artist name on every row. Recording-level
 * genre tags exist but are far sparser, so joining on them would throw away most
 * of the window.
 *
 * Ranks are rebuilt afterwards. The surviving rows carry their global positions
 * — 4, 51, 900 — and presenting those as a genre chart would suggest 895 hard
 * techno tracks nobody bothered to show.
 */
/**
 * Float the artists MusicBrainz confirms for this genre above the ones it doesn't.
 *
 * Last.fm tags are crowd-applied, and a very popular artist accumulates loose
 * ones: `hard techno` comes back led by FKA twigs and Charli xcx, with the first
 * actual hard techno producer at number three. Nobody sat down and decided that
 * — it is what happens when a million casual taggers meet an artist who is on
 * everyone's radar. MusicBrainz tags are curated by people editing a database,
 * so they disagree in exactly the useful direction.
 *
 * Demotion rather than deletion, because MusicBrainz coverage is partial: a
 * genuinely obscure producer may simply not be in the artist set, and dropping
 * unconfirmed rows would quietly delete the deep cuts a genre chart exists to
 * surface. Sorting them below the confirmed ones fixes the head of the chart —
 * which is what anybody actually reads, and what playback opens on — while
 * costing nothing at the tail.
 *
 * Both guards matter. With no artist set at all (a genre MusicBrainz has never
 * heard of) and with nothing confirmed (a set that missed entirely), the chart
 * is returned untouched rather than shuffled on no evidence.
 */
internal fun promoteConfirmed(
    entries: List<ChartEntry>,
    artistKeys: Set<String>,
): List<ChartEntry> {
    if (artistKeys.isEmpty()) return entries
    val (confirmed, unconfirmed) = entries.partition {
        normalizeForMatch(it.artistName) in artistKeys
    }
    if (confirmed.isEmpty()) return entries
    // partition preserves relative order, so Last.fm's ranking survives inside
    // each group and only the boundary between them is new.
    return (confirmed + unconfirmed).mapIndexed { index, entry -> entry.copy(rank = index + 1) }
}

/**
 * Stop one artist from owning the chart.
 *
 * Keeps each artist's best [max] entries in rank order and drops the rest, then
 * renumbers. Dropping rather than demoting, because an artist's fourth-best
 * track is exactly the entry a reader would skip anyway, and carrying it to
 * position 90 spends a slot that a different artist would fill more usefully.
 */
internal fun capPerArtist(entries: List<ChartEntry>, max: Int): List<ChartEntry> {
    if (max <= 0) return entries
    val seen = mutableMapOf<String, Int>()
    return entries.filter { entry ->
        val key = normalizeForMatch(entry.artistName)
        val count = seen.getOrDefault(key, 0)
        seen[key] = count + 1
        count < max
    }.mapIndexed { index, entry -> entry.copy(rank = index + 1) }
}

internal fun narrowToGenre(
    entries: List<ChartEntry>,
    artistKeys: Set<String>,
    limit: Int,
): List<ChartEntry> = entries
    .filter { normalizeForMatch(it.artistName) in artistKeys }
    .sortedByDescending { it.listenCount }
    .take(limit)
    .mapIndexed { index, entry -> entry.copy(rank = index + 1) }
