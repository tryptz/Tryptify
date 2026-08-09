package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.hypot

/**
 * The genre graph, exercised against the **real bundled asset** rather than a
 * fixture.
 *
 * A hand-made fixture would only prove the code compiles. What actually needs
 * guarding is that the 355-node dataset a listener will hit still resolves
 * "dnb", still puts liquid DnB next to atmospheric DnB and not next to gabber,
 * and still parses at all after someone edits `tools/genre_source.py`.
 */
class GenreGraphTest {

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

    // ── loading ─────────────────────────────────────────────────────────

    @Test
    fun `the bundled asset parses and is substantial`() {
        assertTrue("graph looks empty: ${graph.size}", graph.size > 300)
        assertTrue("expected at least 9 moods", graph.moods.size >= 9)
        assertTrue("attribution must ship with the data", graph.attribution.isNotBlank())
    }

    @Test
    fun `electronic is covered deeply, which is the point of the exercise`() {
        val electronic = graph.allGenres.count { it.family == "electronic" }
        assertTrue("only $electronic electronic genres", electronic >= 150)
    }

    // ── resolution ──────────────────────────────────────────────────────

    @Test
    fun `the abbreviations people actually type all resolve`() {
        for (text in listOf("dnb", "d&b", "DnB", "drum and bass", "Drum & Bass", "drum n bass")) {
            assertEquals("failed on $text", "drum-and-bass", graph.resolve(text)?.id)
        }
        assertEquals("psytrance", graph.resolve("psy")?.id)
        assertEquals("liquid-dnb", graph.resolve("liquid")?.id)
        assertEquals("hardgroove", graph.resolve("hard groove")?.id)
        assertEquals("amapiano", graph.resolve("Amapiano")?.id)
        assertEquals("drift-phonk", graph.resolve("driftphonk")?.id)
    }

    @Test
    fun `case, spacing and punctuation don't matter`() {
        val expected = graph.resolve("drum and bass")?.id
        assertNotNull(expected)
        for (text in listOf("  DRUM AND BASS  ", "drum-and-bass", "Drum/And/Bass", "drumandbass")) {
            assertEquals("failed on '$text'", expected, graph.resolve(text)?.id)
        }
    }

    @Test
    fun `a modifier in front still finds the head genre`() {
        // "progressive psytrance" is not a curated node; psytrance is.
        assertEquals("psytrance", graph.resolve("progressive psytrance")?.id)
        assertEquals("hiphop", graph.resolve("uk hip hop")?.id)
    }

    @Test
    fun `unknown text resolves to nothing rather than to something wrong`() {
        assertNull(graph.resolve("the beatles"))
        assertNull(graph.resolve("zzzzz not a genre"))
        assertNull(graph.resolve(""))
        assertNull(graph.resolve("   "))
        assertFalse(graph.isGenreTerm("please play something nice"))
    }

    @Test
    fun `the MusicBrainz vocabulary widens recognition beyond the curated set`() {
        // Not a curated node, but MusicBrainz knows the name and it maps home.
        val resolved = graph.resolve("ambient house")
        assertNotNull("expected the vocabulary to catch this", resolved)
    }

    // ── topology ────────────────────────────────────────────────────────

    @Test
    fun `neighbours are relatives, not strangers`() {
        val ids = graph.neighbours("liquid-dnb", maxHops = 1).map { it.node.id }
        assertTrue("expected atmospheric dnb, got $ids", "atmospheric-dnb" in ids)
        assertTrue("expected the parent genre, got $ids", "drum-and-bass" in ids)
        assertFalse("gabber is not one hop from liquid dnb", "gabber" in ids)
    }

    @Test
    fun `walking further only ever widens the set`() {
        val one = graph.neighbours("techno", maxHops = 1).map { it.node.id }.toSet()
        val two = graph.neighbours("techno", maxHops = 2).map { it.node.id }.toSet()
        val three = graph.neighbours("techno", maxHops = 3).map { it.node.id }.toSet()
        assertTrue("two hops lost something one hop had", two.containsAll(one))
        assertTrue("three hops lost something two hops had", three.containsAll(two))
        assertTrue("widening the walk found nothing new", three.size > one.size)
    }

    @Test
    fun `results come back strongest first and never include the start`() {
        val related = graph.neighbours("house", maxHops = 2)
        assertTrue(related.isNotEmpty())
        assertFalse(related.any { it.node.id == "house" })
        val weights = related.map { it.weight }
        assertEquals("not sorted by weight", weights.sortedDescending(), weights)
    }

    @Test
    fun `an unknown id yields nothing instead of throwing`() {
        assertTrue(graph.neighbours("not-a-genre").isEmpty())
        assertTrue(graph.ancestors("not-a-genre").isEmpty())
        assertTrue(graph.genresForMood("not-a-mood").isEmpty())
    }

    @Test
    fun `every genre reaches a family root by walking parents`() {
        for (node in graph.allGenres) {
            if (node.parents.isEmpty()) continue
            assertTrue(
                "${node.id} has parents but no ancestors",
                graph.ancestors(node.id).isNotEmpty(),
            )
        }
    }

    // ── moods ───────────────────────────────────────────────────────────

    @Test
    fun `every mood resolves to real genres`() {
        for (mood in graph.moods) {
            val genres = graph.genresForMood(mood.id)
            assertTrue(
                "mood ${mood.id} resolved to ${genres.size} genres",
                genres.size >= 4,
            )
            assertTrue("mood ${mood.id} has no label", mood.label.isNotBlank())
        }
    }

    @Test
    fun `widening a mood adds to the tail without reshuffling the head`() {
        // Turning the knob up should show you more, not something else.
        val tight = graph.genresForMood("late-night", maxHops = 0, limit = 20)
        val wide = graph.genresForMood("late-night", maxHops = 2, limit = 40)
        assertTrue("widening lost genres", wide.size >= tight.size)
        assertEquals(
            "the head of the list moved",
            tight.take(3).map { it.node.id },
            wide.take(3).map { it.node.id },
        )
    }

    @Test
    fun `mood windows and genre attributes stay inside their ranges`() {
        for (mood in graph.moods) {
            assertTrue(mood.id, mood.energyLow in 0f..1f)
            assertTrue(mood.id, mood.energyHigh in 0f..1f)
            assertTrue(mood.id, mood.energyLow <= mood.energyHigh)
        }
        for (node in graph.allGenres) {
            assertTrue("${node.id} energy", node.energy in 0f..1f)
            assertTrue("${node.id} valence", node.valence in 0f..1f)
            if (node.hasTempo) {
                assertTrue("${node.id} bpm", node.bpmLow in 40..400 && node.bpmHigh in 40..400)
                assertTrue("${node.id} bpm reversed", node.bpmLow <= node.bpmHigh)
            }
        }
    }

    // ── map layout ──────────────────────────────────────────────────────

    @Test
    fun `every genre has a baked map position`() {
        for (node in graph.allGenres) {
            assertTrue("${node.id} x ${node.x}", node.x.isFinite())
            assertTrue("${node.id} y ${node.y}", node.y.isFinite())
            assertTrue("${node.id} ring ${node.ring}", node.ring in 0..12)
        }
        // Not every coordinate collapsed to the origin, which is what a broken
        // build script produces and what a defaulted field would look like.
        assertTrue(
            "the whole map is sitting on one point",
            graph.allGenres.count { it.x != 0f || it.y != 0f } > 340,
        )
    }

    @Test
    fun `each family lands in its own part of the map`() {
        // The point of the clustered layout: a family is a place you can point
        // at, not a set of dots scattered through everyone else's. Measured as
        // the spread of a family's members about their own centroid versus the
        // spread of the whole map — if a cluster isn't several times tighter
        // than the map, it isn't a cluster.
        val all = graph.allGenres
        val mapSpread = spreadAbout(all, all.map { it.x }.average(), all.map { it.y }.average())

        for ((family, members) in all.groupBy { it.family }) {
            if (members.size < 5) continue
            val spread = spreadAbout(members, members.map { it.x }.average(), members.map { it.y }.average())
            assertTrue(
                "family $family is as spread out as the whole map ($spread vs $mapSpread)",
                spread < mapSpread * 0.65,
            )
        }
    }

    @Test
    fun `the clusters are laid out apart from each other, not stacked`() {
        // Twelve families whose centroids all sat near the middle would satisfy
        // the tightness test above and still be an unreadable pile.
        val centroids = graph.allGenres.groupBy { it.family }
            .map { (_, members) -> members.map { it.x }.average() to members.map { it.y }.average() }
        assertTrue("expected twelve families", centroids.size >= 12)

        val gaps = centroids.flatMapIndexed { i, a ->
            centroids.drop(i + 1).map { b -> hypot(a.first - b.first, a.second - b.second) }
        }
        assertTrue("two families share a centre", gaps.min() > 50.0)
    }

    @Test
    fun `no two genres occupy the same point on the map`() {
        // A node under another node is invisible and untappable — the layout's
        // single most important guarantee, and the reason the build script runs
        // a separation pass after nudging the fusion genres around.
        val nodes = graph.allGenres
        for (i in nodes.indices) {
            for (j in i + 1 until nodes.size) {
                val d = hypot(nodes[i].x - nodes[j].x, nodes[i].y - nodes[j].y)
                assertTrue("${nodes[i].id} sits on top of ${nodes[j].id}", d > 4f)
            }
        }
    }

    @Test
    fun `children sit further out than their parents`() {
        // `ring` is depth in the parent forest and drives dot size and label
        // density, so a child on the same ring as its parent would render as
        // its equal.
        for (node in graph.allGenres) {
            val parent = node.parents.firstOrNull()?.let { graph[it] } ?: continue
            assertTrue(
                "${node.id} (ring ${node.ring}) is not below ${parent.id} (ring ${parent.ring})",
                node.ring > parent.ring,
            )
        }
    }

    @Test
    fun `fusion genres sit between the families they join`() {
        // Jazz house is filed under house, but it is also jazz, and the layout
        // is supposed to say so by placing it nearer jazz than a pure house
        // genre is. This is the whole argument for clusters over one big tree.
        val jazzCentre = centroidOf("jazz")
        val fusion = graph["jazz-house"] ?: graph["acid-jazz"]
        assertNotNull("expected a jazz/electronic fusion genre in the dataset", fusion)
        val tech = graph["techno"]
        assertNotNull(tech)

        val fusionGap = hypot(fusion!!.x - jazzCentre.first, fusion.y - jazzCentre.second)
        val technoGap = hypot(tech!!.x - jazzCentre.first, tech.y - jazzCentre.second)
        assertTrue(
            "${fusion.id} ($fusionGap from jazz) is no closer to jazz than techno ($technoGap)",
            fusionGap < technoGap,
        )
    }

    private fun centroidOf(family: String): Pair<Double, Double> {
        val members = graph.allGenres.filter { it.family == family }
        return members.map { it.x }.average() to members.map { it.y }.average()
    }

    private fun spreadAbout(nodes: List<GenreNode>, cx: Double, cy: Double): Double =
        nodes.map { hypot(it.x - cx, it.y - cy) }.average()

    @Test
    fun `an empty graph degrades quietly instead of throwing`() {
        val empty = GenreGraph.EMPTY
        assertEquals(0, empty.size)
        assertNull(empty.resolve("techno"))
        assertTrue(empty.neighbours("techno").isEmpty())
        assertTrue(empty.moods.isEmpty())
    }
}
