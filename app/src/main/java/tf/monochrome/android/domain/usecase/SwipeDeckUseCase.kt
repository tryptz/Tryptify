package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.data.charts.normalizeForMatch
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.data.repository.LibraryRepository
import tf.monochrome.android.domain.model.DeckCard
import tf.monochrome.android.domain.model.DeckReason
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject

/**
 * Gathers what a swipe deck is dealt from: three bands, each a list of cards in
 * the order its source ranks them.
 *
 * 1. **Today's discovery genre**, from its chart — the same pick that heads the
 *    page, so the deck and the page agree on what today is about.
 * 2. **Genres next door** to the listener's own, a few cards from each.
 * 3. **Artists placed next to the ones they play**, two songs from each.
 *
 * Everything goes through the catalogue Discover is on, and every source has a
 * budget: a band that misses it is an empty band, and the deck is dealt from
 * the other two rather than waiting on it. [round] reaches further down each
 * source, which is how "go again" deals new songs instead of the same ones.
 */
class SwipeDeckUseCase @Inject constructor(
    private val genreCharts: GenreChartUseCase,
    private val catalogs: DiscoveryCatalogs,
    private val library: LibraryRepository,
    private val genreGraph: GenreGraphRepository,
) {

    suspend fun gather(todayGenreId: String?, anchors: List<String>, round: Int): List<List<DeckCard>> =
        coroutineScope {
            val today = async { todayBand(todayGenreId, round) }
            val nearby = async { nearbyBand(todayGenreId, anchors, round) }
            val artists = async { artistBand(round) }
            listOf(today.await(), nearby.await(), artists.await())
        }

    /** Every song already hearted, as deck keys, so none is dealt again. */
    suspend fun likedKeys(): Set<String> = runCatching {
        library.getFavoriteTracks().first().mapTo(HashSet()) {
            normalizeForMatch(it.displayArtist) + "|" + normalizeForMatch(it.title)
        }
    }.getOrDefault(emptySet())

    private suspend fun todayBand(genreId: String?, round: Int): List<DeckCard> {
        val node = genreId?.let { genreGraph.graph[it] } ?: return emptyList()
        return pool(node.id, depth = TODAY_DEPTH, skip = round * TODAY_DEPTH)
            .map { DeckCard(it, DeckReason.Today(node.name)) }
    }

    /**
     * A few cards from each of the genres nearest the listener's: the
     * strongest neighbour of each anchor that is not today's genre and not one
     * of their own, interleaved so the band is not one genre and then another.
     */
    private suspend fun nearbyBand(todayId: String?, anchors: List<String>, round: Int): List<DeckCard> =
        coroutineScope {
            val graph = genreGraph.graph
            val own = anchors.toSet()
            val chosen = LinkedHashSet<String>()
            for (anchor in anchors.take(NEARBY_ANCHORS)) {
                graph.neighbours(anchor, maxHops = 1, floor = 0.3f)
                    .firstOrNull { it.node.id != todayId && it.node.id !in own && it.node.id !in chosen }
                    ?.let { chosen += it.node.id }
            }
            val bands = chosen.toList().take(NEARBY_GENRES).map { id ->
                async {
                    val node = graph[id] ?: return@async emptyList()
                    pool(id, depth = NEARBY_DEPTH, skip = round * NEARBY_DEPTH)
                        .map { DeckCard(it, DeckReason.Genre(node.name)) }
                }
            }.awaitAll()
            interleave(bands)
        }

    /**
     * Two songs from each of a few artists placed next to the ones the
     * listener plays. Empty on Deezer, which publishes no similar-artists list.
     */
    private suspend fun artistBand(round: Int): List<DeckCard> = budgeted {
        val catalog = catalogs.current()
        val gate = Semaphore(ARTIST_CONCURRENCY)
        val seeds = library.getSeedArtistNames(SEED_ARTISTS)
        coroutineScope {
            val perSeed = seeds.map { played ->
                async {
                    val similar = gate.withPermit {
                        val found = catalog.search(played).getOrNull()?.artists
                            ?.firstOrNull { matchesArtistName(it.name, played) }
                            ?: return@withPermit emptyList()
                        catalog.artist(found.id).getOrNull()?.similarArtists.orEmpty()
                    }
                    catalog.registerArtists(similar.map { it.id })
                    val tracks = similar.take(SIMILAR_PER_SEED).map { artist ->
                        async {
                            gate.withPermit {
                                catalog.artist(artist.id).getOrNull()?.topTracks.orEmpty()
                                    .filter { matchesArtistName(it.displayArtist, artist.name) }
                                    .drop(round * SONGS_PER_ARTIST)
                                    .take(SONGS_PER_ARTIST)
                                    .map { catalog.unified(it) }
                            }
                        }
                    }.awaitAll()
                    interleave(tracks).map { DeckCard(it, DeckReason.Artist(played)) }
                }
            }.awaitAll()
            interleave(perSeed)
        }
    }

    private suspend fun pool(genreId: String, depth: Int, skip: Int): List<UnifiedTrack> = budgeted {
        genreCharts.playablePool(genreId, depth = depth, skip = skip)
    }

    /** [block] inside the band budget; a miss or a failure is an empty band. */
    private suspend fun <T> budgeted(block: suspend () -> List<T>): List<T> = try {
        withTimeoutOrNull(BAND_BUDGET_MS) { block() } ?: emptyList()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }

    private fun <T> interleave(bands: List<List<T>>): List<T> {
        val out = ArrayList<T>()
        val longest = bands.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (band in bands) band.getOrNull(i)?.let(out::add)
        return out
    }

    private companion object {
        const val TODAY_DEPTH = 8
        const val NEARBY_ANCHORS = 6
        const val NEARBY_GENRES = 3
        const val NEARBY_DEPTH = 4
        const val SEED_ARTISTS = 3
        const val SIMILAR_PER_SEED = 3
        const val SONGS_PER_ARTIST = 2
        const val ARTIST_CONCURRENCY = 3
        const val BAND_BUDGET_MS = 12_000L
    }
}
