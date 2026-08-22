package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.data.repository.RecommendationSeed
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.data.repository.RecommendationSeedsRepository
import tf.monochrome.android.domain.model.DiscoveryAdventure
import tf.monochrome.android.data.charts.GenrePool
import tf.monochrome.android.data.charts.normalizeForMatch
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
import tf.monochrome.android.domain.model.GenreConfidence
import tf.monochrome.android.domain.model.GenreNode
import tf.monochrome.android.domain.model.MoodProfile
import tf.monochrome.android.domain.model.RankedShelf
import tf.monochrome.android.domain.model.RelatedGenre
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject

/**
 * Builds the discovery feed: a list of titled, *explained* shelves.
 *
 * Two things changed from the row builder this replaces. Shelves now carry a
 * reason ("Because you play X"), because an unexplained recommendation is one
 * the listener has no way to trust or correct. And personalized and curated
 * shelves are no longer mutually exclusive — the old feed showed genre rows
 * *only* to users with no taste data at all, so an established listener never
 * saw anything outside their own orbit and a new one never saw anything else.
 *
 * Everything runs through the Qobuz instance so ids stay in one namespace.
 * Returns whatever it managed to build; an unconfigured or unreachable
 * instance yields an empty list rather than an error.
 */
class DiscoveryFeedUseCase @Inject constructor(
    private val genreCharts: GenreChartUseCase,
    private val library: LibraryRepository,
    private val music: MusicRepository,
    private val registry: QobuzIdRegistry,
    private val seeds: RecommendationSeedsRepository,
    private val genreGraph: GenreGraphRepository,
) {

    /**
     * The personalized feed: new releases and neighbouring artists derived from
     * what the listener actually plays, followed by curated genre shelves.
     *
     * [adventure] decides the mix rather than just relabelling it. At the
     * familiar end most of the budget goes to new releases from artists already
     * in the library; at the adventurous end it shifts to neighbouring artists
     * and whole genres. See [DiscoveryAdventure.shelfMix].
     *
     * @param adventure 0..1, the Discover knob
     * @param itemsPerShelf cap on each shelf's card count
     * @param rotation offset into the curated seeds, so "show me something
     *   else" deals a different hand without reshuffling on every visit
     */
    suspend fun build(
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        rotation: Int = 0,
    ): List<DiscoveryShelf> = buildFlow(adventure, itemsPerShelf, rotation).ordered()

    /** [build], emitted shelf by shelf. See [shelvesFrom]. */
    fun buildFlow(
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        rotation: Int = 0,
    ): Flow<RankedShelf> = shelvesFrom { forYouPlans(adventure, itemsPerShelf, rotation) }

    private suspend fun forYouPlans(
        adventure: Float,
        itemsPerShelf: Int,
        rotation: Int,
    ): List<ShelfPlan> {
        val mix = DiscoveryAdventure.shelfMix(adventure)
        // Ask for enough seed artists to give the two artist-derived bands
        // *disjoint* slices. Sizing this to the wider band and rotating instead
        // doesn't work: with as many artists as the band is wide, a rotation by
        // that width is a full cycle — the identity — so both bands started
        // from the same artist and the feed carried "New from X" and "Because
        // you play X" for the same X.
        val seedArtists = library.getSeedArtistNames(mix.familiar + mix.explore)

        // Every shelf is fetched concurrently and each carries its own timeout,
        // so one slow artist delays its own shelf and nothing else.
        val personalized = seedArtists.take(mix.familiar).map { name ->
            ShelfPlan { newReleaseShelf(name, itemsPerShelf) }
        }
        // The slice after the familiar band. A short taste profile can't fill
        // both — then the bands do overlap, which is the right trade: a
        // listener with two artists on record should still get a feed.
        val neighbours = seedArtists.drop(mix.familiar)
            .ifEmpty { seedArtists }
            .take(mix.explore)
            .map { name -> ShelfPlan { similarArtistShelf(name, itemsPerShelf) } }
        val curated = seeds.seeds().rotated(rotation + seedArtists.size)
            .take(mix.genre)
            .map { seed -> ShelfPlan { genreShelf(seed, itemsPerShelf) } }

        // Interleaved rather than concatenated: three familiar shelves in a row
        // followed by three genre shelves reads as two separate pages stapled
        // together. Alternating keeps something known next to something new all
        // the way down, which is the whole point of the knob.
        //
        // Interleaved as *plans*, before any of them is fetched, so the order a
        // shelf lands in is decided by the feed rather than by which request
        // came back first.
        return interleave(personalized, neighbours, curated)
    }

    /**
     * The feed for one mood or genre chip: several shelves seeded off a single
     * query, so picking "Late night" gives a page rather than a single row.
     */
    suspend fun buildForQuery(
        label: String,
        query: String,
        itemsPerShelf: Int = 12,
    ): List<DiscoveryShelf> = withTimeoutOrNull(QOBUZ_BUDGET_MS) {
        val result = music.searchQobuz(query).getOrNull() ?: return@withTimeoutOrNull emptyList()
        registerArtists(result.tracks.flatMap { it.artists }.map { it.id })

        listOfNotNull(
            result.tracks.take(itemsPerShelf)
                .map { DiscoveryItem.TrackItem(it.toQobuzUnifiedTrack()) }
                .toShelf("mood_tracks_$query", label, "Tracks for $label"),
            result.albums.take(itemsPerShelf)
                .map { DiscoveryItem.AlbumItem(it) }
                .toShelf("mood_albums_$query", "$label albums", "Releases that fit $label"),
            result.artists.take(itemsPerShelf)
                .map { DiscoveryItem.ArtistItem(it) }
                .toShelf("mood_artists_$query", "$label artists", "Artists to start from"),
        )
    } ?: emptyList()

    /**
     * A mood, expanded through the genre graph into one shelf per genre.
     *
     * This is what the graph was built for. "Late night" used to be a single
     * opaque search — `searchQobuz("late night ambient downtempo")` — sliced
     * three ways, so the page could only ever be as good as whatever that
     * string happened to match, and it could not explain itself. Now the mood
     * names actual genres, each with a tempo and energy profile, each fetched
     * and titled separately: *Dub Techno · Slow and deep, for Late night*.
     *
     * [adventure] decides how far past the mood's own genres to walk, so the
     * knob widens the *kind* of music offered and not merely the shelf count.
     */
    /**
     * A page built from several moods at once, minus anything subtracted.
     *
     * The single-mood path stays [buildForMood] and is untouched; this is the
     * combining case, and it titles its shelves from the mood that contributed
     * most to each genre so a combined page still says why each row is there.
     */
    suspend fun buildForMoods(
        moodIds: List<String>,
        excluded: Set<String> = emptySet(),
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        maxShelves: Int = 6,
        page: Int = 0,
    ): List<DiscoveryShelf> =
        buildForMoodsFlow(moodIds, excluded, adventure, itemsPerShelf, maxShelves, page).ordered()

    /** [buildForMoods], emitted shelf by shelf. See [shelvesFrom]. */
    fun buildForMoodsFlow(
        moodIds: List<String>,
        excluded: Set<String> = emptySet(),
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        maxShelves: Int = 6,
        page: Int = 0,
    ): Flow<RankedShelf> = shelvesFrom {
        moodPlans(moodIds, excluded, adventure, itemsPerShelf, maxShelves, page)
    }

    private fun moodPlans(
        moodIds: List<String>,
        excluded: Set<String>,
        adventure: Float,
        itemsPerShelf: Int,
        maxShelves: Int,
        page: Int,
    ): List<ShelfPlan> {
        val graph = genreGraph.graph
        if (moodIds.size == 1 && excluded.isEmpty()) {
            return singleMoodPlans(moodIds.first(), adventure, itemsPerShelf, maxShelves, page)
        }
        val moods = moodIds.mapNotNull { graph.mood(it) }
        if (moods.isEmpty()) return emptyList()

        val skip = page * maxShelves
        val picks = graph.genresForMoods(
            moodIds = moodIds,
            excluded = excluded,
            maxHops = hopsFor(adventure, page),
            limit = skip + maxShelves,
        ).drop(skip)

        return picks.map { related ->
            // Attribute each shelf to whichever of the combined moods ranks this
            // genre highest, so the reason on a row names a mood the listener
            // actually picked rather than the combination as a whole.
            val owner = moods.maxByOrNull { mood ->
                mood.genres.firstOrNull { it.getOrNull(0)?.asId() == related.node.id }
                    ?.getOrNull(1)?.asWeight() ?: 0f
            } ?: moods.first()
            ShelfPlan { genreShelfFor(related, owner, itemsPerShelf, variation = page) }
        }
    }

    suspend fun buildForMood(
        moodId: String,
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        maxShelves: Int = 6,
        page: Int = 0,
    ): List<DiscoveryShelf> =
        buildForMoodFlow(moodId, adventure, itemsPerShelf, maxShelves, page).ordered()

    /** [buildForMood], emitted shelf by shelf. See [shelvesFrom]. */
    fun buildForMoodFlow(
        moodId: String,
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        maxShelves: Int = 6,
        page: Int = 0,
    ): Flow<RankedShelf> = shelvesFrom {
        singleMoodPlans(moodId, adventure, itemsPerShelf, maxShelves, page)
    }

    private fun singleMoodPlans(
        moodId: String,
        adventure: Float,
        itemsPerShelf: Int,
        maxShelves: Int,
        page: Int,
    ): List<ShelfPlan> {
        val graph = genreGraph.graph
        val mood = graph.mood(moodId) ?: return emptyList()
        val skip = page * maxShelves
        val picks = graph.genresForMood(
            moodId = moodId,
            maxHops = hopsFor(adventure, page),
            limit = skip + maxShelves,
        ).drop(skip)

        return picks.map { related ->
            ShelfPlan { genreShelfFor(related, mood, itemsPerShelf, variation = page) }
        }
    }

    /**
     * How far out to walk for page [page] of a category.
     *
     * The knob sets the starting reach; scrolling widens it. Without that, a
     * mood's genre list is however long the graph says it is and the feed hits
     * a hard floor two screens down — with it, page five is drawing on genres
     * three hops out and there is always more below.
     */
    private fun hopsFor(adventure: Float, page: Int): Int =
        (DiscoveryAdventure.maxHops(adventure) + page / 2).coerceAtMost(MAX_HOPS)

    /**
     * One genre's shelf. [mood] is null when the genre is the subject rather
     * than a means to a mood — the reason line changes, nothing else does.
     *
     * [page] is depth *within this genre*, not a different genre: page one is
     * the next stretch of the same ranking. Paging used to mean only "walk
     * further across the graph", so the genre you actually asked for was one
     * shelf deep and there was no way to reach its thirteenth track.
     */
    private suspend fun genreShelfFor(
        related: RelatedGenre,
        mood: MoodProfile?,
        limit: Int,
        variation: Int = 0,
        page: Int = 0,
        idOverride: String? = null,
        titleOverride: String? = null,
        reasonBase: String? = null,
        borrow: Boolean = true,
    ): DiscoveryShelf? {
        val node = related.node
        val prefix = mood?.let { "mood_" + it.id } ?: "genre"
        val id = idOverride ?: shelfId(prefix, node.id, page)
        val reason = buildString {
            append(
                reasonBase ?: when {
                    mood != null && related.hops == 0 -> "For ${mood.label.lowercase()}"
                    mood != null -> "A step out from ${mood.label.lowercase()}"
                    related.hops == 0 -> "The genre itself"
                    else -> "Next to it on the map"
                }
            )
            if (node.hasTempo) append(" · ${node.bpmLow}–${node.bpmHigh} BPM")
        }

        // What the genre actually is, before what merely says so. Both halves
        // of the pool come from the genre's chart — one matched into the
        // catalogue row by row, the other reached through the artists that
        // chart names — and neither one ever hands the genre's name to the
        // catalogue as a query.
        val pool = pooledFor(node.id, limit, page, variation = variation)
        val merged = mergeByKey(pool.charted, pool.fromArtists, limit) { it.id }

        if (merged.size >= MIN_CHART_TRACKS) {
            val chartedIds = pool.charted.mapTo(HashSet()) { it.id }
            val fromChart = merged.count { it.id in chartedIds }
            return merged.map { DiscoveryItem.TrackItem(it.chartedAs(node)) }.toShelf(
                id = id,
                title = titleOverride ?: node.name,
                reason = genreShelfReason(reason, fromChart, merged.size - fromChart),
                genreId = node.id,
                depth = page,
            )
        }

        // Deep pages don't borrow. [moreForGenre] appends straight into a row
        // that is already on screen under its own reason line, so a page five
        // that quietly returned a neighbour's music would put gabber in a
        // terrorcore row with nothing on screen saying so. Running out is the
        // honest answer there, and the grid stops asking.
        if (!borrow) return null

        return borrowedShelf(
            node = node,
            own = merged,
            id = id,
            title = titleOverride ?: node.name,
            reason = reason,
            limit = limit,
            page = page,
        )
    }

    /**
     * A genre's pool, inside the shelf's budget.
     *
     * The timeout is the budget; a cancellation is the feed being replaced out
     * from under us and has to keep travelling. runCatching around the whole
     * thing would swallow the second as if it were the first and go on building
     * a shelf nobody is waiting for.
     */
    private suspend fun pooledFor(
        genreId: String,
        limit: Int,
        page: Int,
        budgetMs: Long = CHART_BUDGET_MS,
        variation: Int = 0,
    ): GenrePool = try {
        withTimeoutOrNull(budgetMs) {
            genreCharts.popularFor(genreId, depth = limit, page = page, variation = variation)
        } ?: GenrePool()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        GenrePool()
    }

    /**
     * The row for a genre no chart source has enough to say about, filled from
     * its nearest neighbours on the map.
     *
     * The alternative — the one this replaces — was to search the catalogue for
     * the genre's *name*, and a name search is not a weaker answer to the same
     * question but a confident answer to a different one: it ranks records that
     * say "terrorcore" above records that are terrorcore, and the difference is
     * the whole complaint. Gabber is genuinely the nearest thing to terrorcore
     * that the world has scrobbled enough of to rank, so playing gabber and
     * saying so is both more useful and more honest than playing whatever is
     * called terrorcore.
     *
     * Whatever the genre itself yielded stays at the head — those tracks are
     * real, there were just too few of them to be a row — and the neighbour
     * fills the rest. The row keeps the title of the genre asked for; only the
     * reason line changes, because the listener asked for terrorcore and a row
     * that renames itself Gabber has lost them their place.
     *
     * One hop only, and no recursion: a neighbour with nothing of its own is a
     * neighbour we move past, not a new place to start borrowing from.
     */
    private suspend fun borrowedShelf(
        node: GenreNode,
        own: List<UnifiedTrack>,
        id: String,
        title: String,
        reason: String,
        limit: Int,
        page: Int,
    ): DiscoveryShelf? = coroutineScope {
        val neighbours = genreGraph.graph.neighbours(node.id, maxHops = 1)
            .take(BORROW_CANDIDATES)
        if (neighbours.isEmpty()) return@coroutineScope null

        // Tried together rather than one after another: they are independent
        // requests, and a shelf that asks three genres in sequence has spent
        // three budgets to fill one row.
        val lent = neighbours
            .map { related ->
                async { related.node to pooledFor(related.node.id, limit, page, BORROW_BUDGET_MS) }
            }
            .awaitAll()

        for ((neighbour, pool) in lent) {
            val tracks = mergeByKey(
                own,
                mergeByKey(pool.charted, pool.fromArtists, limit) { it.id },
                limit,
            ) { it.id }
            if (tracks.size < MIN_CHART_TRACKS) continue
            val ownIds = own.mapTo(HashSet()) { it.id }
            return@coroutineScope tracks
                .map { track ->
                    // Tagged with whichever genre the track actually came from.
                    // A gabber record borrowed into a terrorcore row is a gabber
                    // record, and saying otherwise would put a genre on a track
                    // on no evidence at all.
                    DiscoveryItem.TrackItem(
                        track.chartedAs(if (track.id in ownIds) node else neighbour)
                    )
                }
                .toShelf(
                    id = id,
                    title = title,
                    reason = genreShelfReason(reason, 0, 0, borrowedFrom = neighbour.name),
                    genreId = node.id,
                    depth = page,
                )
        }
        null
    }

    /**
     * The last-resort shelf: a catalogue search on a name, cleaned up.
     *
     * No longer reachable from a genre row. A genre the map knows and the
     * charts don't now borrows from its neighbours instead — see
     * [borrowedShelf] — because a search for a genre's name is not a weaker
     * answer to "what is this genre" but a confident answer to "what is called
     * this". What is left here is the curated seed that names nothing on the
     * map at all ("Pop hits"), where there is no genre to be right about and a
     * rough answer beats an empty row.
     *
     * The search
     * ranks by how well a *title or album* matches the words, which is the
     * query machine-generated filler is built to win — asking for hardstyle
     * returns "Hardstyle Fish", "I'm so lucky! - Hardstyle" and compilations
     * whose artwork happens to say Hardstyle.
     *
     * Two defences, because either alone lets the filler through. Artists the
     * genre demonstrably owns are kept whatever their tracks are called, and
     * floated to the front. Everyone else has to not be echoing the genre's own
     * name back at us. What survives is presented as a search result and says so
     * — the previous code fell back to the raw, unfiltered hits and titled them
     * as though they were the genre, which is the part that read as broken.
     */
    private suspend fun searchShelf(
        title: String,
        queries: List<String>,
        genreId: String?,
        id: String,
        reason: String,
        limit: Int,
        variation: Int,
        page: Int,
    ): DiscoveryShelf? {
        // The name asked for first, with an alias as the fallback for anything
        // a store spells differently. [variation] walks the aliases instead,
        // which is what stops the same seed asked for twice from returning the
        // same search.
        if (queries.isEmpty()) return null
        val query = queries[Math.floorMod(variation, queries.size)]
        // Deeper pages ask the catalogue for a later slice rather than
        // re-reading the first one. The parameter has been plumbed through
        // MusicRepository and HiFiApiClient all along and was never passed.
        val offset = page * limit * SHELF_OVERFETCH
        val result = music.searchQobuz(query, offset).getOrNull() ?: return null
        registerArtists(result.tracks.flatMap { it.artists }.map { it.id })

        // Verification is by artist rather than per track because it is cached
        // per artist for a month and shared across every shelf and chart — a
        // warm cache makes this free, and a cold one costs at most one request
        // per new name.
        val candidates = result.tracks.take(limit * SHELF_OVERFETCH)
        val confirmed = genreId?.let {
            runCatching {
                genreCharts.confirmedArtists(
                    genreId = it,
                    artistNames = candidates.mapNotNull { track -> track.artists.firstOrNull()?.name },
                )
            }.getOrDefault(emptySet())
        }.orEmpty()

        val (owned, rest) = candidates
            .filter { track ->
                val artist = normalizeForMatch(track.artists.firstOrNull()?.name.orEmpty())
                artist in confirmed || !echoesGenreName(track.title, track.album?.title, queries)
            }
            .partition { normalizeForMatch(it.artists.firstOrNull()?.name.orEmpty()) in confirmed }

        val tracks = (owned + rest).take(limit)
            .map { DiscoveryItem.TrackItem(it.toQobuzUnifiedTrack().taggedWith(title, genreId)) }
        // Albums only when no track survived, and held to the same standard:
        // the shelf that has just dropped every track called "Techno" would
        // otherwise fill itself back up with compilations called "Techno 2024".
        val items = tracks.ifEmpty {
            result.albums
                .filterNot { echoesGenreName(it.title, null, queries) }
                .take(limit)
                .map { DiscoveryItem.AlbumItem(it) }
        }
        val honest = if (confirmed.isEmpty()) "$reason · matched by name" else reason
        return items.toShelf(
            id = id,
            title = title,
            reason = honest,
            genreId = genreId,
            depth = page,
        )
    }

    /**
     * The next page of one genre, as items to append to a shelf already on
     * screen.
     *
     * This is what "See All" scrolls into. It deliberately returns items rather
     * than a shelf: the grid is already showing this genre under its own title
     * and reason, and swapping the shelf underneath it would reset the scroll
     * position and any running selection.
     */
    suspend fun moreForGenre(
        genreId: String,
        page: Int,
        limit: Int = 20,
    ): List<DiscoveryItem> {
        val node = genreGraph.graph[genreId] ?: return emptyList()
        return genreShelfFor(
            related = RelatedGenre(node, 1f, 0),
            mood = null,
            limit = limit,
            variation = page,
            page = page,
            borrow = false,
        )?.items.orEmpty()
    }

    /** Shelf ids carry their depth, or a genre's second page collides with its first. */
    private fun shelfId(prefix: String, nodeId: String, page: Int): String =
        if (page == 0) prefix + "_" + nodeId else prefix + "_" + nodeId + "_p" + page

    /**
     * A feed built around one genre — what the map hands back when you tap a node.
     *
     * The genre itself leads, then its relatives in graph order, so the page
     * reads as "this, and what sits next to it". [adventure] decides how far
     * the neighbours reach, exactly as it does for a mood, which keeps the one
     * knob meaningful everywhere instead of meaning something different per
     * surface.
     */
    suspend fun buildForGenre(
        genreId: String,
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        maxShelves: Int = 6,
        variation: Int = 0,
        page: Int = 0,
    ): List<DiscoveryShelf> =
        buildForGenreFlow(genreId, adventure, itemsPerShelf, maxShelves, variation, page).ordered()

    /** [buildForGenre], emitted shelf by shelf. See [shelvesFrom]. */
    fun buildForGenreFlow(
        genreId: String,
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        maxShelves: Int = 6,
        variation: Int = 0,
        page: Int = 0,
    ): Flow<RankedShelf> = shelvesFrom {
        genrePlans(genreId, adventure, itemsPerShelf, maxShelves, variation, page)
    }

    private fun genrePlans(
        genreId: String,
        adventure: Float,
        itemsPerShelf: Int,
        maxShelves: Int,
        variation: Int,
        page: Int,
    ): List<ShelfPlan> {
        val graph = genreGraph.graph
        val root = graph[genreId] ?: return emptyList()
        val floor = DiscoveryAdventure.neighbourFloor(adventure)
        val neighbours = graph.neighbours(genreId, maxHops = hopsFor(adventure, page), floor = floor)

        // The genre itself leads every page, one stretch deeper each time,
        // followed by neighbours we haven't shown yet. It used to lead page one
        // and then vanish, so scrolling a genre walked away from it — twelve
        // tracks of what you asked for and then an hour of things adjacent to
        // it. Each pick carries its own depth: the root goes deeper, while a
        // neighbour appearing for the first time starts at its own beginning.
        val picks = buildList {
            add(RelatedGenre(root, 1f, 0) to page)
            neighbours.drop(page * (maxShelves - 1))
                .take(maxShelves - 1)
                .forEach { add(it to 0) }
        }

        return picks.map { (related, depth) ->
            ShelfPlan {
                genreShelfFor(
                    related,
                    mood = null,
                    limit = itemsPerShelf,
                    variation = variation + page,
                    page = depth,
                )
            }
        }
    }

    // ── Emitting a feed as it arrives ────────────────────────────────────

    /**
     * One shelf, decided but not yet fetched.
     *
     * Separating the decision from the fetch is what lets a feed be emitted in
     * pieces without shuffling itself: which genres a page is made of, and in
     * what order, is worked out before a single request goes out, so a shelf
     * that comes back first still lands in the position the feed chose for it
     * rather than at the top.
     */
    private class ShelfPlan(val build: suspend () -> DiscoveryShelf?)

    /**
     * Run every plan at once and emit each shelf the moment it is ready.
     *
     * The page used to appear all at once, when the slowest of six concurrent
     * builds finished — and each of those builds can spend nine seconds on a
     * chart before it settles, so the whole feed waited on its worst row. Now
     * each row draws itself as it arrives.
     *
     * [RankedShelf.index] is the position the feed decided on, not arrival
     * order: a collector holds the shelves in a sparse list and renders them in
     * index order, so a slow first row leaves a gap that fills in rather than
     * pushing everything below it down when it lands.
     *
     * A plan that comes back with nothing emits nothing. Its slot simply stays
     * empty, which is what a shelf returning null has always meant.
     */
    private fun shelvesFrom(plans: suspend () -> List<ShelfPlan>): Flow<RankedShelf> =
        channelFlow {
            plans().forEachIndexed { index, plan ->
                launch { plan.build()?.let { send(RankedShelf(index, it)) } }
            }
        }

    /** The whole feed at once, in the order it was planned. */
    private suspend fun Flow<RankedShelf>.ordered(): List<DiscoveryShelf> =
        toList().sortedBy { it.index }.map { it.shelf }

    // ── Shelf builders ───────────────────────────────────────────────────

    /** "New from <artist>" — tracks off that artist's most recent release. */
    private suspend fun newReleaseShelf(name: String, limit: Int): DiscoveryShelf? =
        withTimeoutOrNull(QOBUZ_BUDGET_MS) {
            // searchQobuz also registers each album's slug into the QobuzIdRegistry
            // as a side effect, so the newest album below is resolvable by id.
            val result = music.searchQobuz(name).getOrNull() ?: return@withTimeoutOrNull null

            // Newest release attributed to this artist, by release date.
            val newest = result.albums
                .filter { matchesArtistName(it.displayArtist, name) && !it.releaseDate.isNullOrBlank() }
                .maxByOrNull { it.releaseDate!! }

            val albumTracks = newest
                ?.let { registry.albumSlugFor(it.id) }
                ?.let { slug -> music.getQobuzAlbum(slug).getOrNull()?.tracks }
                ?.take(limit)

            // Fallback: if no resolvable newest album, surface the search's top
            // Qobuz tracks for this artist so the shelf still populates.
            val sourceTracks = albumTracks?.takeIf { it.isNotEmpty() }
                ?: result.tracks.take(limit)

            registerArtists(sourceTracks.flatMap { it.artists }.map { it.id })

            sourceTracks.map { DiscoveryItem.TrackItem(it.toQobuzUnifiedTrack()) }.toShelf(
                id = "new_from_$name",
                title = "New from $name",
                reason = newest?.releaseDate?.take(4)
                    ?.let { year -> "Their latest release ($year) — you play $name" }
                    ?: "Because you play $name",
            )
        }

    /**
     * "Because you play <artist>" — the artists Qobuz places next to them.
     *
     * This graph is already fetched on every radio station (RadioQueueManager
     * expands through it) but has never been shown to the listener. Rendering
     * it is the cheapest real recommendation on the page: no new backend, and
     * it explains itself.
     */
    private suspend fun similarArtistShelf(name: String, limit: Int): DiscoveryShelf? =
        withTimeoutOrNull(QOBUZ_BUDGET_MS) {
            val search = music.searchQobuz(name).getOrNull() ?: return@withTimeoutOrNull null
            val seed = search.artists.firstOrNull { matchesArtistName(it.name, name) }
                ?: search.artists.firstOrNull()
                ?: return@withTimeoutOrNull null

            val similar = music.getQobuzArtist(seed.id).getOrNull()?.similarArtists.orEmpty()
            registerArtists(similar.map { it.id } + seed.id)

            similar.take(limit).map { DiscoveryItem.ArtistItem(it) }.toShelf(
                id = "similar_to_${seed.id}",
                title = "Because you play $name",
                reason = "Artists Qobuz places next to $name",
            )
        }

    /**
     * A curated genre shelf.
     *
     * A seed that names a genre the map knows is a genre, not a search string,
     * and gets the same evidence every other genre row gets: "Popular in jazz"
     * was a catalogue search for the word "jazz", which is the identical
     * failure the mood rows had on an identical query. Only a seed the map has
     * never heard of ("Pop hits") keeps the search — and the albums it renders,
     * which are there so the curated band doesn't look like the rest of the
     * feed.
     */
    private suspend fun genreShelf(seed: RecommendationSeed, limit: Int): DiscoveryShelf? {
        genreGraph.graph.resolve(seed.query)?.let { node ->
            genreShelfFor(
                related = RelatedGenre(node, 1f, 0),
                mood = null,
                limit = limit,
                // The id is byte-identical to the search shelf's, so the feed's
                // id-based dedup and the Flow feed's exhaustion counting carry
                // on meaning what they meant.
                idOverride = "genre_${seed.query}",
                titleOverride = seed.label,
                reasonBase = "Popular in ${seed.label.lowercase()}",
            )?.let { return it }
        }
        // A seed that names nothing on the map has no genre to be right about,
        // so the name is all there is to go on — and the row says so.
        return withTimeoutOrNull(QOBUZ_BUDGET_MS) {
            searchShelf(
                title = seed.label,
                queries = listOf(seed.query, seed.label).distinct(),
                genreId = null,
                id = "genre_${seed.query}",
                reason = "Popular in ${seed.label.lowercase()}",
                limit = limit,
                variation = 0,
                page = 0,
            )
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Wrap items in a shelf, or return null if there are none.
     *
     * De-duplicates on the card key. Nothing guarantees a catalogue search
     * returns each record once — a re-release, a mirrored entry, the same artist
     * credited twice in a similar-artists list — and the feed keys its lazy rows
     * on that value, so a repeat is not a cosmetic double but a duplicate key
     * that takes the screen down.
     */
    private fun List<DiscoveryItem>.toShelf(
        id: String,
        title: String,
        reason: String?,
        genreId: String? = null,
        depth: Int = 0,
    ): DiscoveryShelf? = distinctBy { it.key }
        .takeIf { it.isNotEmpty() }
        ?.let {
            DiscoveryShelf(
                id = id,
                title = title,
                reason = reason,
                items = it,
                genreId = genreId,
                depth = depth,
            )
        }

    /**
     * Tag every credited artist id as Qobuz, so ArtistDetailViewModel routes it
     * to getQobuzArtist. getQobuzAlbum registers track ids and album slugs but
     * not artist ids, so without this a dual-source setup could mis-route a
     * tapped featured artist to the TIDAL pool.
     */
    private fun registerArtists(ids: List<Long>) {
        ids.filter { it > 0L }.distinct().forEach { registry.registerArtist(it) }
    }

    /**
     * Rotates a list by a caller-supplied offset instead of shuffling.
     *
     * The feed is rebuilt whenever the knob moves, and a real shuffle would
     * deal a different genre every time — motion where the listener expects a
     * place. Rotating gives variety between listeners, and between explicit
     * "show me something else" taps, while staying put otherwise.
     */
    private fun <T> List<T>.rotated(offset: Int): List<T> =
        if (isEmpty()) this
        else List(size) { this[((it + offset) % size + size) % size] }

    /**
     * Round-robins the bands together, longest-first within each round, so the
     * feed alternates known and unknown instead of serving each band as a block.
     */
    private fun <T> interleave(vararg bands: List<T>): List<T> =
        roundRobin(bands.toList(), bands.sumOf { it.size })

    /**
     * Attach the genre we searched for, without overwriting one the catalogue
     * actually stated.
     *
     * This is the INFERRED rung: the track came back from a search for
     * "Liquid Drum & Bass", so it probably is some. Probably is enough to rank
     * on and to title a shelf with; it is not enough to assert, which is why
     * the confidence travels with it and the UI hedges anything below TAGGED.
     */
    private fun UnifiedTrack.taggedWith(name: String, id: String?): UnifiedTrack = when {
        // "Pop hits" is a seed, not a genre. Writing it onto a track as one
        // would be inventing a fact about the music to fill a field.
        id == null -> this
        genre != null -> copy(genreId = genreId ?: id)
        else -> copy(
            genre = name,
            genreId = id,
            genreConfidence = GenreConfidence.INFERRED,
        )
    }

    /**
     * Attach a genre the track earned by charting in it.
     *
     * A rung above [taggedWith]: this track is not here because a search for
     * the genre's name returned it, but because the genre's chart ranked it and
     * the catalogue agreed on both artist and title. That is inherited from the
     * scene rather than stated by the file, which is what DERIVED means.
     */
    private fun UnifiedTrack.chartedAs(node: GenreNode): UnifiedTrack =
        if (genre != null) copy(genreId = genreId ?: node.id)
        else copy(
            genre = node.name,
            genreId = node.id,
            genreConfidence = GenreConfidence.DERIVED,
        )

    companion object {
        // Per-shelf ceiling, mirroring the 7s budget SearchViewModel uses for
        // Qobuz so one slow lookup can't stall the whole feed.
        private const val QOBUZ_BUDGET_MS = 7_000L

        /**
         * How many extra search hits to pull before filtering by artist.
         * Roughly half a genre-name search is usually filler, so fetching only
         * [limit] leaves a half-empty shelf once the filler is dropped.
         */
        private const val SHELF_OVERFETCH = 3

        /** Ceiling on how far paging widens the graph walk. */
        private const val MAX_HOPS = 5

        /**
         * How long a shelf may spend on the chart before it settles for the
         * search. Longer than the search budget because it buys something
         * better, and because a cold resolve cache is the expensive case — once
         * warm, neighbouring shelves answer from memory well inside this.
         */
        private const val CHART_BUDGET_MS = 9_000L

        /**
         * How many neighbours to ask when a genre's own sources come up short.
         *
         * Three, because the nearest neighbour is not always the one with a
         * chart — asking only the first would leave a row empty whenever the
         * closest relative is as obscure as the genre itself — and because
         * they are asked concurrently, so three costs one budget, not three.
         */
        private const val BORROW_CANDIDATES = 3

        /**
         * How long borrowing may take, on top of the budget the genre itself
         * already spent. Shorter than [CHART_BUDGET_MS] because it is the
         * second attempt at one row and the caches it needs are the ones a
         * neighbouring shelf has usually just filled.
         */
        private const val BORROW_BUDGET_MS = 6_000L

        /**
         * Below this many tracks a genre is not carrying its own row. A row of
         * three is not a shelf, so at that depth the honest trade is to fill it
         * from the nearest genre that does have the music, and say so.
         */
        private const val MIN_CHART_TRACKS = 6
    }
}

/**
 * Concatenate two ranked lists without repeating a record, keeping the first
 * list's order intact at the head.
 *
 * Merging rather than choosing is what stops a shelf with five charted tracks
 * from throwing them away — five real records and fifteen from the genre's own
 * artists is a better row than either half could be alone, and than the name
 * search that used to replace both.
 *
 * Generic on the key so the ordering rule can be tested without building a
 * track: this is a list operation, and the only thing that can go wrong with it
 * is a list mistake.
 */
internal fun <T> mergeByKey(
    first: List<T>,
    second: List<T>,
    limit: Int,
    key: (T) -> String,
): List<T> {
    if (limit <= 0) return emptyList()
    val out = ArrayList<T>(minOf(limit, first.size + second.size))
    val seen = HashSet<String>()
    for (item in first.asSequence() + second.asSequence()) {
        if (out.size >= limit) break
        if (seen.add(key(item))) out.add(item)
    }
    return out
}

/**
 * How a genre shelf says it knows what it knows.
 *
 * A row on this feed always states its evidence — that is most of the
 * difference between a recommendation and a dump — and a row built from two
 * sources has to name both rather than claim the stronger one for all of it.
 * The wording is in listeners' terms rather than the pipeline's: "ranked by
 * plays" is the tag chart, "its most-played artists" is the catalogue's own
 * ranking for the artists that chart names, and "by way of" is a genre the
 * world has scrobbled too little of to rank at all.
 *
 * Nothing here claims a genre *check*. The artists are confirmed where that
 * evidence was cheap or already bought and left in the chart's order
 * otherwise — but either way they are the genre's most-played artists, so the
 * line stays true without asserting a verification that may not have run.
 */
internal fun genreShelfReason(
    base: String,
    charted: Int,
    fromArtists: Int,
    borrowedFrom: String? = null,
): String = when {
    borrowedFrom != null -> "$base · by way of $borrowedFrom"
    charted > 0 && fromArtists > 0 -> "$base · ranked by plays and its most-played artists"
    charted > 0 -> "$base · ranked by plays"
    fromArtists > 0 -> "$base · its most-played artists"
    else -> base
}

/**
 * Whether this record is only in the results because it says the genre's name.
 *
 * The catalogue search is a title/album match, so asking it for a genre returns
 * two kinds of thing: records that *are* the genre, and records that merely
 * *mention* it — "Hardstyle Fish", "I'm so lucky! - Hardstyle", and the endless
 * compilations called "Techno 2024". The second kind wins the ranking, because
 * putting the genre in the title is exactly how you get found by someone
 * searching for the genre.
 *
 * The test is deliberately one-directional: the genre's name appearing in the
 * title or the album title is suspicious, the track's title appearing in the
 * genre's name is not (a track really called "Techno" by a techno artist is a
 * normal record). Callers pair this with artist evidence and let confirmed
 * artists through regardless, so a genre's own defining track is never dropped
 * for being named after it.
 *
 * Matching is on whole words, so "Trance" does not fire on "Entrance" and
 * punctuation differences don't matter.
 */
internal fun echoesGenreName(
    title: String,
    albumTitle: String?,
    genreNames: List<String>,
): Boolean {
    val haystacks = listOfNotNull(
        foldWhole(title).takeIf { it.isNotEmpty() },
        albumTitle?.let { foldWhole(it) }?.takeIf { it.isNotEmpty() },
    )
    if (haystacks.isEmpty()) return false

    return genreNames.any { raw ->
        val needle = foldWhole(raw)
        needle.isNotEmpty() && haystacks.any { it.containsWords(needle) }
    }
}

/**
 * Lowercase alphanumerics and single spaces, keeping the *whole* string.
 *
 * Deliberately not [normalizeForMatch], which drops bracketed suffixes and
 * everything after " - " so that two services can agree a record is the same
 * record. Here that folding would erase the evidence: "I'm so lucky! -
 * Hardstyle" folds to "im so lucky" under it, and the genre echo — the entire
 * thing being tested for — disappears along with the dash. Bracketed text is
 * kept for the same reason, so "(Hardstyle Remix)" still counts.
 */
private fun foldWhole(raw: String): String = buildString {
    for (ch in raw.lowercase()) {
        if (ch.isLetterOrDigit()) append(ch)
        else if (isNotEmpty() && last() != ' ') append(' ')
    }
}.trim()

/**
 * Whether [words] appears in this string as a run of whole words.
 *
 * Both sides are already folded to lowercase alphanumerics separated by single
 * spaces, so padding with spaces turns a substring test into a word-boundary
 * one without a regex.
 */
private fun String.containsWords(words: String): Boolean =
    " $this ".contains(" $words ")
