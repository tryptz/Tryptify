package tf.monochrome.android.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.GenreGraph
import tf.monochrome.android.domain.model.GenreGraphData
import tf.monochrome.android.domain.model.GenreVocabulary
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled genre knowledge graph.
 *
 * Follows [RecommendationSeedsRepository]'s shape deliberately — asset JSON,
 * `ignoreUnknownKeys`, `runCatching`, fail soft — because the failure mode has
 * to be "discovery gets no smarter" and never "the app won't start". Every
 * caller treats an empty graph as "I don't recognise this", which is the same
 * behaviour the app had before the graph existed.
 *
 * Parsed once, lazily, on first use: ~130 KB across the two assets, immutable
 * afterwards, so there is nothing to invalidate and no reason to reload.
 */
@Singleton
class GenreGraphRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val graph: GenreGraph by lazy { load() }

    private fun load(): GenreGraph {
        val data = runCatching {
            val text = context.assets.open(GRAPH_ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString(GenreGraphData.serializer(), text)
        }.getOrNull()?.takeIf { it.genres.isNotEmpty() } ?: return GenreGraph.EMPTY

        // The vocabulary is optional: without it the graph still resolves every
        // curated name and alias, it just stops recognising the long tail of
        // MusicBrainz names that map onto them.
        val vocabulary = runCatching {
            val text = context.assets.open(VOCAB_ASSET).bufferedReader().use { it.readText() }
            json.decodeFromString(GenreVocabulary.serializer(), text).names
        }.getOrDefault(emptyMap())

        return GenreGraph(data, vocabulary)
    }

    private companion object {
        const val GRAPH_ASSET = "genre_graph.json"
        const val VOCAB_ASSET = "genre_vocabulary.json"
    }
}
