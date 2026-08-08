package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.api.QobuzIdRegistry
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.data.repository.RecommendationSeed
import tf.monochrome.android.data.repository.RecommendationSeedsRepository
import tf.monochrome.android.domain.model.DiscoveryAdventure
import tf.monochrome.android.domain.model.DiscoveryItem
import tf.monochrome.android.domain.model.DiscoveryShelf
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
        // Ask for as many seed artists as the widest of the two artist-derived
        // bands needs; one lookup feeds both.
        val seedArtists = library.getSeedArtistNames(maxOf(mix.familiar, mix.explore))

        // Every shelf is fetched concurrently and each carries its own timeout,
        // so one slow artist delays its own shelf and nothing else.
        val personalized = seedArtists.take(mix.familiar).map { name ->
            async { newReleaseShelf(name, itemsPerShelf) }
        }
        // Rotated relative to the familiar band so the same artist isn't
        // usually both "new from X" and "because you play X" in one feed.
        val neighbours = seedArtists.rotated(mix.familiar).take(mix.explore).map { name ->
            async { similarArtistShelf(name, itemsPerShelf) }
        }
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
    }
}
