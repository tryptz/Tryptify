package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The researched genre histories, checked against the **real bundled asset**.
 *
 * What needs guarding here is not that the model compiles — it is that the
 * thing shipped in the APK is what it claims to be. Every entry is prose about
 * a genre, taken from a named revision of a named article, filed under a genre
 * the map actually has. Those are the properties that make the panel a citation
 * rather than an assertion, and they are exactly the ones that a re-run of
 * `tools/fetch_genre_history.py` against a changed Wikipedia could quietly
 * break.
 *
 * The naming check is the load-bearing one. An entry keyed to `samba-de-roda`
 * whose text is the Samba article is *true prose about the wrong genre* — the
 * single failure this dataset is most likely to develop, and the one that no
 * amount of eyeballing a 700-entry JSON file would catch.
 */
class GenreHistoryTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val assets = File("src/main/assets")

    private val history: GenreHistoryData by lazy {
        json.decodeFromString(
            GenreHistoryData.serializer(),
            File(assets, "genre_history.json").readText(),
        )
    }

    private val graph: GenreGraph by lazy {
        val data = json.decodeFromString(
            GenreGraphData.serializer(),
            File(assets, "genre_graph.json").readText(),
        )
        GenreGraph(data, emptyMap())
    }

    @Test
    fun `the bundled asset parses and covers most of the map`() {
        assertTrue("history looks empty: ${history.entries.size}", history.entries.size > 500)
        assertTrue(
            "the licence has to ship with the text",
            history.attribution.isNotBlank() && history.license.isNotBlank(),
        )
    }

    @Test
    fun `every entry is filed under a genre the map has`() {
        val unknown = history.entries.keys.filter { graph[it] == null }
        assertTrue("history for genres that don't exist: $unknown", unknown.isEmpty())
    }

    @Test
    fun `every entry cites the article and revision it came from`() {
        // Not paperwork: without these the text can't be attributed as CC BY-SA
        // requires, and a reader has no way to check a claim about the music.
        val uncited = history.entries.filter { (_, entry) ->
            entry.revision <= 0 ||
                !entry.url.startsWith("https://en.wikipedia.org/wiki/") ||
                entry.title.isBlank()
        }.keys
        assertTrue("entries with no checkable source: $uncited", uncited.isEmpty())
    }

    @Test
    fun `every entry is prose, not a stub`() {
        // Sixty characters, not more: "Landó is an Afro-Peruvian form of music in
        // the musica criolla genre." is the entire lead of that article, and a
        // true one-sentence answer is not a broken entry. What this catches is
        // an extract that came back as a fragment of one.
        val thin = history.entries.filter { it.value.summary.length < 60 }.keys
        assertTrue("summaries too short to say anything: $thin", thin.isEmpty())
        val emptySections = history.entries.filter { (_, entry) ->
            entry.sections.any { it.heading.isBlank() || it.text.isBlank() }
        }.keys
        assertTrue("sections with no heading or no body: $emptySections", emptySections.isEmpty())
    }

    @Test
    fun `entries name the genre they claim to describe`() {
        // The one check that catches a redirect landing on a neighbour. Not
        // every entry can pass it — an article titled "New wave of British
        // heavy metal" need not spell out "NWOBHM" in its first lines, and a
        // genre documented as a section of its parent's article may open by
        // describing the parent — so this is a proportion, set well below what
        // the dataset actually manages, and a floor rather than a target.
        val named = history.entries.count { (id, entry) ->
            val node = graph[id] ?: return@count false
            val text = GenreGraph.fold(entry.summary.take(700))
            (listOf(node.name) + node.aka + listOf(entry.title)).any {
                val alias = GenreGraph.fold(it)
                alias.isNotEmpty() && alias in text
            }
        }
        val ratio = named.toFloat() / history.entries.size
        assertTrue(
            "only $named of ${history.entries.size} entries name their own genre",
            ratio >= 0.9f,
        )
    }

    @Test
    fun `no entry carries an article's apparatus`() {
        // "References", "See also" and discographies are lists and citations,
        // not history — and a section of them in the panel is a wall of names.
        val banned = Regex(
            "^(see also|references|notes|further reading|external links|" +
                "bibliography|sources|discography|list of|notable)",
            RegexOption.IGNORE_CASE,
        )
        val offenders = history.entries.filter { (_, entry) ->
            entry.sections.any { banned.containsMatchIn(it.heading.trim()) }
        }.keys
        assertTrue("apparatus shipped as history: $offenders", offenders.isEmpty())
    }

    @Test
    fun `the asset stays inside its size budget`() {
        // It ships in the APK and is parsed whole on the first expand. At the
        // time of writing it is 1.9 MB of JSON — about 700 KB in the APK, which
        // compresses it — and the ceiling here is set by the parse, not the
        // download: one decode on the IO dispatcher that a listener waiting for
        // a panel doesn't notice. Past this it wants an index and a partial
        // read instead, which is a different design and should be a decision
        // rather than a drift.
        val kilobytes = File(assets, "genre_history.json").length() / 1024
        assertTrue("genre_history.json is $kilobytes KB", kilobytes < 2560)
    }

    @Test
    fun `the genres a listener is most likely to open all have a history`() {
        // A dataset that covered 600 obscure genres and missed techno would
        // pass every check above and be useless in practice.
        val staples = listOf(
            "techno", "house", "drum-and-bass", "dubstep", "black-metal",
            "hiphop", "jazz", "ambient", "disco", "punk",
        ).filter { graph[it] != null }
        val missing = staples.filterNot { history.entries.containsKey(it) }
        assertTrue("no history for $missing", missing.isEmpty())
    }
}
