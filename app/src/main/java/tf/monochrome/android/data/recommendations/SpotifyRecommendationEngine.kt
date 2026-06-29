package tf.monochrome.android.data.recommendations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A single dataset recommendation: just enough to build a HiFi search query
 * (`"$title $artist"`). [seedGenre] is carried for grouping/labels.
 */
data class SpotifyRec(
    val title: String,
    val artist: String,
    val genre: String,
)

/**
 * Content-based recommender over the bundled Spotify audio-features dataset
 * ([SpotifyFeatureDb]). It works purely offline on audio-feature similarity;
 * it has no notion of the streaming backends. Turning a [SpotifyRec] into a
 * playable track is the job of RecommendationRepository, which resolves each
 * one through the HiFi API.
 *
 * Similarity = weighted Euclidean distance over the Spotify audio features in
 * a normalized 0..1 space, scored against candidates drawn from the seed's
 * genre (genres are fine-grained — 114 of them — so same-genre neighbours are
 * already musically close, and it keeps each scoring pass to ~1k rows).
 */
@Singleton
class SpotifyRecommendationEngine @Inject constructor(
    private val featureDb: SpotifyFeatureDb,
) {
    /**
     * Tracks similar to a now-playing ([artist], [title]). Matches the seed to
     * its dataset row (exact `artist|title`, else the artist's most-popular
     * row), scores same-genre candidates by feature distance, and diversifies
     * to at most [maxPerArtist] per artist. Empty when the seed artist isn't
     * in the dataset at all (caller then skips / falls back).
     */
    suspend fun similarTo(
        artist: String,
        title: String,
        limit: Int,
        maxPerArtist: Int = 2,
    ): List<SpotifyRec> = withContext(Dispatchers.IO) {
        val artistKey = SpotifyFeatureDb.normalize(artist)
        val seed = featureDb.lookupByKey("$artistKey|${SpotifyFeatureDb.normalize(title)}")
            ?: featureDb.lookupByArtist(artistKey).maxByOrNull { it.popularity }
            ?: return@withContext emptyList()

        val seedVec = seed.toVector()
        featureDb.rowsByGenre(seed.genre)
            .filter { it.normKey != seed.normKey }
            .map { it to distance(seedVec, it.toVector()) }
            .sortedBy { it.second }
            .map { it.first }
            .diversifyByArtist(maxPerArtist)
            .take(limit)
            .map { SpotifyRec(it.title, it.artist, it.genre) }
    }

    /**
     * Popular tracks in [genre], lightly shuffled within the top pool so the
     * discovery row varies between visits. Used for genre-seeded discovery rows.
     */
    suspend fun byGenre(genre: String, limit: Int): List<SpotifyRec> =
        withContext(Dispatchers.IO) {
            featureDb.topByGenre(genre, limit = (limit * 3).coerceAtMost(120))
                .diversifyByArtist(1)
                .shuffled(Random(genre.hashCode()))
                .take(limit)
                .map { SpotifyRec(it.title, it.artist, genre) }
        }

    /** A stable-but-rotating pick of [count] genres for discovery rows. */
    suspend fun sampleGenres(count: Int, salt: Int = 0): List<String> =
        withContext(Dispatchers.IO) {
            featureDb.genres().shuffled(Random(salt)).take(count)
        }

    /** Genres ranked by how many of [seedGenres] fall into each (taste-weighted). */
    suspend fun genresFor(seedGenres: List<String>, count: Int): List<String> =
        withContext(Dispatchers.IO) {
            seedGenres.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }
                .map { it.key }
                .take(count)
        }

    private fun List<FeatureRow>.diversifyByArtist(maxPerArtist: Int): List<FeatureRow> {
        if (maxPerArtist <= 0) return this
        val perArtist = HashMap<String, Int>()
        val out = ArrayList<FeatureRow>(size)
        for (row in this) {
            val key = SpotifyFeatureDb.normalize(row.artist)
            val n = perArtist.getOrDefault(key, 0)
            if (n < maxPerArtist) {
                out.add(row)
                perArtist[key] = n + 1
            }
        }
        return out
    }

    /** Maps a row to the weighted, normalized feature vector used for distance. */
    private fun FeatureRow.toVector(): DoubleArray = doubleArrayOf(
        danceability,
        energy,
        valence,
        acousticness,
        instrumentalness,
        (tempo / 250.0).coerceIn(0.0, 1.0),
        ((loudness + 60.0) / 60.0).coerceIn(0.0, 1.0),
    )

    private fun distance(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val d = a[i] - b[i]
            sum += WEIGHTS[i] * d * d
        }
        return sqrt(sum)
    }

    private companion object {
        // danceability, energy, valence carry the "feel"; acousticness /
        // instrumentalness shape texture; tempo & loudness are coarse, so
        // weighted down (loudness especially is production, not vibe).
        val WEIGHTS = doubleArrayOf(1.3, 1.3, 1.2, 0.9, 0.9, 0.6, 0.4)
    }
}
