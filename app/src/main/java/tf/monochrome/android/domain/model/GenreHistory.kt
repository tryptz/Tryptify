package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

/**
 * What a genre's history actually says, researched rather than generated.
 *
 * Every field here is text taken from a specific revision of a specific English
 * Wikipedia article that was *verified* to be about this genre — either it
 * carries `{{Infobox music genre}}` or Wikidata records it as an instance of
 * "music genre" — and nothing is written for a genre where that check failed.
 * See `tools/fetch_genre_history.py`, which does the verifying and bakes the
 * asset; the whole point of that file is that a genre with no documented
 * history gets no paragraph rather than a plausible one.
 *
 * [revision] and [url] are not decoration. The text is CC BY-SA 4.0 and has to
 * carry its attribution, and a claim about where a genre came from is worth
 * exactly as much as the source you can go and check it against — so the panel
 * shows the article it came from and links to it.
 */
@Serializable
data class GenreHistory(
    val title: String = "",
    val url: String = "",
    /** The article revision this text was taken from. */
    val revision: Long = 0,
    /** The lead — what this genre *is*, before what happened to it. */
    val summary: String = "",
    /**
     * Set when the genre is documented as a section of a broader article rather
     * than in one of its own, e.g. Xtra Raw inside Rawstyle. Worth surfacing:
     * it tells the reader why the source is named something else.
     */
    val fragment: String? = null,
    /** Where and when it started, as the article's infobox states it. */
    val cultural: String? = null,
    /** The genres it came out of. */
    val stylistic: List<String> = emptyList(),
    /** The genres that came out of it. */
    val derivatives: List<String> = emptyList(),
    /** History, origins, characteristics — in the article's own order. */
    val sections: List<GenreHistorySection> = emptyList(),
) {
    /**
     * True when the article states where this genre came from or led to.
     *
     * Not every verified article carries a genre infobox — the ones reached
     * through Wikidata by definition don't — so the panel has to lay out
     * without these as readily as with them.
     */
    val hasOrigins: Boolean
        get() = cultural != null || stylistic.isNotEmpty() || derivatives.isNotEmpty()
}

@Serializable
data class GenreHistorySection(
    val heading: String = "",
    /** 1 for a top-level section, 2 for a subsection — the panel indents on it. */
    val level: Int = 1,
    val text: String = "",
)

/**
 * The bundled history asset.
 *
 * Keyed by genre id, and deliberately not part of [GenreGraphData]: the graph is
 * ~230 KB that every search and every Discover build reads on startup, while
 * this is the better part of a megabyte of prose that only matters once someone
 * expands the panel. Merging them would put the second on the critical path of
 * the first.
 */
@Serializable
data class GenreHistoryData(
    val version: Int = 1,
    val attribution: String = "",
    val license: String = "",
    val entries: Map<String, GenreHistory> = emptyMap(),
)
