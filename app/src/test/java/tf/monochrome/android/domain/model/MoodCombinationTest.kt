package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Combining moods, and the reach of the mood system as a whole — against the
 * real bundled asset, because what needs guarding is that the 771-node dataset
 * a listener actually hits behaves, not that a fixture does.
 */
class MoodCombinationTest {

    private val graph: GenreGraph by lazy {
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
        val assets = File("src/main/assets")
        val data = json.decodeFromString(
            GenreGraphData.serializer(),
            File(assets, "genre_graph.json").readText(),
        )
        val vocabulary = json.decodeFromString(
            GenreVocabulary.serializer(),
            File(assets, "genre_vocabulary.json").readText(),
        ).names
        GenreGraph(data, vocabulary)
    }

    // ── coverage ────────────────────────────────────────────────────────

    @Test
    fun `every genre is reachable from at least one mood`() {
        // The gap this closes: the genre→mood direction was authored on 766 of
        // 771 nodes and never read, so a mood chip drew on ten hand-picked
        // genres while the rest of the map declared itself relevant to nobody.
        val reachable = graph.moods
            .flatMap { mood -> mood.genres.mapNotNull { it.getOrNull(0)?.asId() } }
            .toSet()
        val orphans = graph.allGenres.map { it.id }.filterNot { it in reachable }
        assertTrue("genres in no mood: ${orphans.take(8)}", orphans.isEmpty())
    }

    @Test
    fun `moods are deep enough to open`() {
        for (mood in graph.moods) {
            assertTrue(
                "${mood.id} has only ${mood.genres.size} genres",
                mood.genres.size >= 20,
            )
        }
    }

    @Test
    fun `curated members outrank derived ones`() {
        // Curation decides what leads a mood; derivation only fills the tail.
        // Every hand-authored weight is 0.6 or above and every derived one below.
        val focus = graph.mood("focus")!!
        val leaders = focus.genres.take(5).mapNotNull { it.getOrNull(1)?.asWeight() }
        assertTrue("curated entries should lead", leaders.all { it >= 0.6f })

        val weights = focus.genres.mapNotNull { it.getOrNull(1)?.asWeight() }
        assertEquals("weights must be ordered", weights.sortedDescending(), weights)
    }

    @Test
    fun `the visceral moods exist and are distinguishable by valence`() {
        // Rage and Euphoric sit at the same end of the arousal scale and are
        // nothing alike; valence is the only field that separates them.
        val rage = graph.mood("rage")!!
        val euphoric = graph.mood("euphoric")!!
        assertTrue(rage.energyLow >= 0.8f && euphoric.energyLow >= 0.8f)
        assertTrue("rage should be dark", rage.valenceHigh <= 0.4f)
        assertTrue("euphoric should be bright", euphoric.valenceLow >= 0.7f)
    }

    // ── combining ───────────────────────────────────────────────────────

    @Test
    fun `combining is a union, not an intersection`() {
        // "Workout + Chill" as an intersection returns nothing, because no
        // genre is authored as both. A listener combining them means "draw on
        // both", so both have to contribute.
        val combined = graph.genresForMoods(listOf("workout", "chill"), limit = 40)
        val workout = graph.genresForMood("workout", limit = 40).map { it.node.id }.toSet()
        val chill = graph.genresForMood("chill", limit = 40).map { it.node.id }.toSet()

        assertTrue(combined.isNotEmpty())
        assertTrue("workout must contribute", combined.any { it.node.id in workout })
        assertTrue("chill must contribute", combined.any { it.node.id in chill })
    }

    @Test
    fun `a genre in both moods outranks one in a single mood`() {
        val moods = listOf("workout", "party")
        val inBoth = graph.moods
            .filter { it.id in moods }
            .map { m -> m.genres.mapNotNull { it.getOrNull(0)?.asId() }.toSet() }
            .reduce { a, b -> a intersect b }

        // Only meaningful if the two moods actually overlap in the dataset.
        if (inBoth.isNotEmpty()) {
            val combined = graph.genresForMoods(moods, limit = 200)
            val firstShared = combined.indexOfFirst { it.node.id in inBoth }
            assertTrue("a shared genre should appear", firstShared >= 0)
            val sharedWeight = combined[firstShared].weight
            val soloWeight = combined.lastOrNull { it.node.id !in inBoth }?.weight ?: 0f
            assertTrue(
                "shared $sharedWeight should beat solo $soloWeight",
                sharedWeight > soloWeight,
            )
        }
    }

    @Test
    fun `subtracting a genre removes it from the combination`() {
        val moods = listOf("workout", "party")
        val before = graph.genresForMoods(moods, limit = 40)
        val victim = before.first().node.id

        val after = graph.genresForMoods(moods, excluded = setOf(victim), limit = 40)
        assertFalse("subtracted genre must not survive", after.any { it.node.id == victim })
        assertTrue("the rest should remain", after.isNotEmpty())
    }

    @Test
    fun `a single mood combines to exactly itself`() {
        // The one-mood case has to stay byte-identical to the old path, so
        // nothing about single selection changes behaviour.
        assertEquals(
            graph.genresForMood("focus", limit = 12).map { it.node.id },
            graph.genresForMoods(listOf("focus"), limit = 12).map { it.node.id },
        )
    }

    @Test
    fun `an unknown or empty mood set yields nothing rather than everything`() {
        assertTrue(graph.genresForMoods(emptyList()).isEmpty())
        assertTrue(graph.genresForMoods(listOf("not-a-mood")).isEmpty())
    }

    @Test
    fun `subtracting everything empties the combination rather than ignoring it`() {
        val moods = listOf("focus", "chill")
        val all = graph.genresForMoods(moods, limit = 500).map { it.node.id }.toSet()
        assertTrue(graph.genresForMoods(moods, excluded = all, limit = 500).isEmpty())
    }

    // ── popularity ──────────────────────────────────────────────────────

    @Test
    fun `genres carry a baked popularity and it spans real orders of magnitude`() {
        val measured = graph.allGenres.mapNotNull { it.reach }
        assertTrue("expected most genres measured", measured.size > graph.size / 2)
        assertTrue("reach cannot be negative", measured.all { it >= 0 })
        // Without a genuine spread the weighting would draw every dot the same
        // size, which is the thing it exists to fix.
        assertTrue("expected a wide spread, got ${measured.max()}", measured.max() > 100_000)
    }
}
