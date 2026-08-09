package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.data.repository.RecommendationSeed
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.data.repository.RecommendationSeedsRepository
import tf.monochrome.android.domain.model.DiscoveryAdventure
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
import tf.monochrome.android.domain.model.GenreConfidence
import tf.monochrome.android.domain.model.GenreNode
import tf.monochrome.android.domain.model.MoodProfile
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
    ): List<DiscoveryShelf> = coroutineScope {
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
            async { newReleaseShelf(name, itemsPerShelf) }
        }
        // The slice after the familiar band. A short taste profile can't fill
        // both — then the bands do overlap, which is the right trade: a
        // listener with two artists on record should still get a feed.
        val neighbours = seedArtists.drop(mix.familiar)
            .ifEmpty { seedArtists }
            .take(mix.explore)
            .map { name -> async { similarArtistShelf(name, itemsPerShelf) } }
        val curated = seeds.seeds().rotated(rotation + seedArtists.size)
            .take(mix.genre)
            .map { seed -> async { genreShelf(seed, itemsPerShelf) } }

        // Interleaved rather than concatenated: three familiar shelves in a row
        // followed by three genre shelves reads as two separate pages stapled
        // together. Alternating keeps something known next to something new all
        // the way down, which is the whole point of the knob.
        interleave(
            personalized.mapNotNull { it.await() },
            neighbours.mapNotNull { it.await() },
            curated.mapNotNull { it.await() },
        )
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
    ): List<DiscoveryShelf> = coroutineScope {
        val graph = genreGraph.graph
        if (moodIds.size == 1 && excluded.isEmpty()) {
            return@coroutineScope buildForMood(moodIds.first(), adventure, itemsPerShelf, maxShelves, page)
        }
        val moods = moodIds.mapNotNull { graph.mood(it) }
        if (moods.isEmpty()) return@coroutineScope emptyList()

        val skip = page * maxShelves
        val picks = graph.genresForMoods(
            moodIds = moodIds,
            excluded = excluded,
            maxHops = hopsFor(adventure, page),
            limit = skip + maxShelves,
        ).drop(skip)
        if (picks.isEmpty()) return@coroutineScope emptyList()

        picks.map { related ->
            // Attribute each shelf to whichever of the combined moods ranks this
            // genre highest, so the reason on a row names a mood the listener
            // actually picked rather than the combination as a whole.
            val owner = moods.maxByOrNull { mood ->
                mood.genres.firstOrNull { it.getOrNull(0)?.asId() == related.node.id }
                    ?.getOrNull(1)?.asWeight() ?: 0f
            } ?: moods.first()
            async { genreShelfFor(related, owner, itemsPerShelf, variation = page) }
        }.mapNotNull { it.await() }
    }

    suspend fun buildForMood(
        moodId: String,
        adventure: Float = DiscoveryAdventure.DEFAULT,
        itemsPerShelf: Int = 12,
        maxShelves: Int = 6,
        page: Int = 0,
    ): List<DiscoveryShelf> = coroutineScope {
        val graph = genreGraph.graph
        val mood = graph.mood(moodId) ?: return@coroutineScope emptyList()
        val skip = page * maxShelves
        val picks = graph.genresForMood(
            moodId = moodId,
            maxHops = hopsFor(adventure, page),
            limit = skip + maxShelves,
        ).drop(skip)
        if (picks.isEmpty()) return@coroutineScope emptyList()

        picks.map { related ->
            async { genreShelfFor(related, mood, itemsPerShelf, variation = page) }
        }.mapNotNull { it.await() }
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
     */
    private suspend fun genreShelfFor(
        related: RelatedGenre,
        mood: MoodProfile?,
        limit: Int,
        variation: Int = 0,
    ): DiscoveryShelf? = withTimeoutOrNull(QOBUZ_BUDGET_MS) {
        val node = related.node
        // The node's own name first — it is what the catalogue is most likely
        // to have tagged — with an alias as the fallback for genres a store
        // spells differently. [variation] walks the aliases instead, which is
        // what stops a genre asked for twice from returning the same search.
        val queries = node.queries()
        if (queries.isEmpty()) return@withTimeoutOrNull null
        val query = queries[Math.floorMod(variation, queries.size)]
        val result = music.searchQobuz(query).getOrNull() ?: return@withTimeoutOrNull null
        registerArtists(result.tracks.flatMap { it.artists }.map { it.id })

        val reason = buildString {
            append(
                when {
                    mood != null && related.hops == 0 -> "For ${mood.label.lowercase()}"
                    mood != null -> "A step out from ${mood.label.lowercase()}"
                    related.hops == 0 -> "The genre itself"
                    else -> "Next to it on the map"
                }
            )
            if (node.hasTempo) append(" · ${node.bpmLow}–${node.bpmHigh} BPM")
        }

        val tracks = result.tracks.take(limit)
            .map { DiscoveryItem.TrackItem(it.toQobuzUnifiedTrack().taggedWith(node)) }
        val items = tracks.ifEmpty { result.albums.take(limit).map { DiscoveryItem.AlbumItem(it) } }
        val prefix = mood?.let { "mood_" + it.id } ?: "genre"
        items.toShelf(id = prefix + "_" + node.id, title = node.name, reason = reason)
    }

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
    ): List<DiscoveryShelf> = coroutineScope {
        val graph = genreGraph.graph
        val root = graph[genreId] ?: return@coroutineScope emptyList()
        val floor = DiscoveryAdventure.neighbourFloor(adventure)
        val neighbours = graph.neighbours(genreId, maxHops = hopsFor(adventure, page), floor = floor)

        // The genre itself leads its own first page and nothing else's — page
        // two is neighbours, not the same shelf again with a different name.
        val picks = if (page == 0) {
            listOf(RelatedGenre(root, 1f, 0)) + neighbours.take(maxShelves - 1)
        } else {
            neighbours.drop(page * maxShelves - 1).take(maxShelves)
        }
        if (picks.isEmpty()) return@coroutineScope emptyList()

        picks.map { related ->
            async {
                genreShelfFor(
                    related,
                    mood = null,
                    limit = itemsPerShelf,
                    variation = variation + page,
                )
            }
        }.mapNotNull { it.await() }
    }

    // ── Shelf builders ───────────────────────────────────────────────────

    /** "New from <artist>" — tracks off that artist's most recent release. */
    private suspend fun newReleaseShelf(name: String, limit: Int): DiscoveryShelf? =
        withTimeoutOrNull(QOBUZ_BUDGET_MS) {
            // searchQobuz also registers each album's slug into the QobuzIdRegistry
            // as a side effect, so the newest album below is resolvable by id.
            val result = music.searchQobuz(name).getOrNull() ?: return@withTimeoutOrNull null

            // Newest release attributed to this artist, by release date.
            val newest = result.albums
                .filter { it.displayArtist.matchesArtist(name) && !it.releaseDate.isNullOrBlank() }
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
            val seed = search.artists.firstOrNull { it.name.matchesArtist(name) }
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

    /** A curated genre shelf, rendered as albums so it doesn't look like the rest. */
    private suspend fun genreShelf(seed: RecommendationSeed, limit: Int): DiscoveryShelf? =
        withTimeoutOrNull(QOBUZ_BUDGET_MS) {
            val result = music.searchQobuz(seed.query).getOrNull() ?: return@withTimeoutOrNull null
            val albums = result.albums.take(limit)
            if (albums.isNotEmpty()) {
                albums.map { DiscoveryItem.AlbumItem(it) }.toShelf(
                    id = "genre_${seed.query}",
                    title = seed.label,
                    reason = "Popular in ${seed.label.lowercase()}",
                )
            } else {
                registerArtists(result.tracks.flatMap { it.artists }.map { it.id })
                result.tracks.take(limit)
                    .map { DiscoveryItem.TrackItem(it.toQobuzUnifiedTrack()) }
                    .toShelf(
                        id = "genre_${seed.query}",
                        title = seed.label,
                        reason = "Popular in ${seed.label.lowercase()}",
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
    ): DiscoveryShelf? = distinctBy { it.key }
        .takeIf { it.isNotEmpty() }
        ?.let { DiscoveryShelf(id = id, title = title, reason = reason, items = it) }

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
    private fun interleave(vararg bands: List<DiscoveryShelf>): List<DiscoveryShelf> {
        val out = ArrayList<DiscoveryShelf>(bands.sumOf { it.size })
        var i = 0
        while (out.size < bands.sumOf { it.size }) {
            var addedThisRound = false
            for (band in bands) {
                band.getOrNull(i)?.let { out.add(it); addedThisRound = true }
            }
            if (!addedThisRound) break
            i++
        }
        return out
    }

    /**
     * Attach the genre we searched for, without overwriting one the catalogue
     * actually stated.
     *
     * This is the INFERRED rung: the track came back from a search for
     * "Liquid Drum & Bass", so it probably is some. Probably is enough to rank
     * on and to title a shelf with; it is not enough to assert, which is why
     * the confidence travels with it and the UI hedges anything below TAGGED.
     */
    private fun UnifiedTrack.taggedWith(node: GenreNode): UnifiedTrack =
        if (genre != null) copy(genreId = genreId ?: node.id)
        else copy(
            genre = node.name,
            genreId = node.id,
            genreConfidence = GenreConfidence.INFERRED,
        )

    /** Lenient match so search albums credited to the seed artist are kept. */
    private fun String.matchesArtist(name: String): Boolean {
        val a = trim().lowercase()
        val b = name.trim().lowercase()
        return a == b || a.contains(b) || b.contains(a)
    }

    companion object {
        // Per-shelf ceiling, mirroring the 7s budget SearchViewModel uses for
        // Qobuz so one slow lookup can't stall the whole feed.
        private const val QOBUZ_BUDGET_MS = 7_000L

        /** Ceiling on how far paging widens the graph walk. */
        private const val MAX_HOPS = 5
    }
}
