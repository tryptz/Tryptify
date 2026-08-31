package tf.monochrome.android.visualizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.VisualizerPreset
import tf.monochrome.android.domain.model.VisualizerTag

/**
 * Built from the shapes the real pack actually contains.
 *
 * The corpus is projectM's cream-of-the-crop: 9,795 presets, filed
 * `Category/Subcategory/name.milk` across 11 categories and 183 subcategories,
 * with 8,443 of the names following Milkdrop's `author - title`. Every case
 * below is a spelling taken from it rather than invented, because the awkward
 * ones -- the collaborations, the case splits, the names with no author at all
 * -- are the entire reason this class exists.
 */
class VisualizerPresetIndexTest {

    private fun preset(
        name: String,
        category: String? = null,
        sub: String? = null,
        id: String = name,
    ) = VisualizerPreset(
        id = id,
        displayName = name,
        filePath = "presets/$name.milk",
        tags = listOfNotNull(
            category?.let { VisualizerTag(it.lowercase().replace(" ", "_"), it) },
            sub?.let { VisualizerTag(it.lowercase().replace(" ", "_"), it) },
        ),
    )

    // ── The two-level tree the pack already has ─────────────────────────

    @Test
    fun `categories and their subcategories come out separately`() {
        val index = VisualizerPresetIndex.build(
            listOf(
                preset("a - one", "Dancer", "Blobby Mirror"),
                preset("b - two", "Dancer", "Blobby Mirror"),
                preset("c - three", "Dancer", "Comet"),
                preset("d - four", "Fractal", "Nested Spiral"),
            )
        )
        assertEquals(listOf("Dancer" to 3, "Fractal" to 1), index.categories.map { it.label to it.count })
        // The panel used to render both levels as one flat row of chips, so a
        // subcategory sat beside a category with nothing to say which was which.
        assertEquals(
            listOf("Blobby Mirror" to 2, "Comet" to 1),
            index.subcategoriesOf("dancer").map { it.label to it.count },
        )
        assertEquals(
            listOf("Nested Spiral" to 1),
            index.subcategoriesOf("fractal").map { it.label to it.count },
        )
        assertTrue(index.subcategoriesOf("nonexistent").isEmpty())
    }

    // ── Authors, which are only in the file name ────────────────────────

    @Test
    fun `the author is read off the name`() {
        val index = VisualizerPresetIndex.build(listOf(preset("amandio c - living boxes 2")))
        val p = index.presets.single()
        assertEquals(listOf("amandio c"), index.authorsOf(p))
        assertEquals("living boxes 2", index.titleOf(p))
    }

    @Test
    fun `only the first separator splits`() {
        // Real: "beta106i - Trickshot (No Man's Land)". A title may contain a
        // dash of its own, and splitting on the last one would credit the wrong
        // person.
        val index = VisualizerPresetIndex.build(
            listOf(preset("shifter - tumbling cubes - ripples edit"))
        )
        val p = index.presets.single()
        assertEquals(listOf("shifter"), index.authorsOf(p))
        assertEquals("tumbling cubes - ripples edit", index.titleOf(p))
    }

    @Test
    fun `collaborations credit everyone`() {
        // Real: "Stahlregen & EoS + Geiss + ORB + Phat - ...". Left whole, that
        // is a one-off author nobody will ever browse to, and it hides five
        // people's work.
        val index = VisualizerPresetIndex.build(
            listOf(preset("Stahlregen & EoS + Geiss + ORB + Phat - glowsticks"))
        )
        assertEquals(
            listOf("stahlregen", "eos", "geiss", "orb", "phat"),
            index.authorsOf(index.presets.single()),
        )
        assertEquals(5, index.authors.size)
    }

    @Test
    fun `one author spelled two ways is one author`() {
        // "Flexi" and "flexi" are both in the pack, 217 and 145 of them. Kept
        // apart, neither list is that person's work.
        val index = VisualizerPresetIndex.build(
            listOf(
                preset("Flexi - alien", id = "1"),
                preset("Flexi - bee", id = "2"),
                preset("flexi - cell", id = "3"),
            )
        )
        assertEquals(1, index.authors.size)
        assertEquals(3, index.authors.single().count)
        // The majority spelling wins the heading.
        assertEquals("Flexi", index.authors.single().label)
    }

    @Test
    fun `an unattributed name keeps its whole name and credits nobody`() {
        // About one in seven has no separator. Guessing an author from the
        // first word would invent hundreds of people who do not exist.
        val index = VisualizerPresetIndex.build(listOf(preset("cope_beattrix")))
        val p = index.presets.single()
        assertTrue(index.authorsOf(p).isEmpty())
        assertEquals("cope_beattrix", index.titleOf(p))
        assertTrue(index.authors.isEmpty())
    }

    @Test
    fun `a name that is only a separator is not an author`() {
        listOf(" - lonely", "orphan - ", " - ").forEach { name ->
            val index = VisualizerPresetIndex.build(listOf(preset(name)))
            assertTrue("<$name> must credit nobody", index.authors.isEmpty())
            assertEquals(name, index.titleOf(index.presets.single()))
        }
    }

    @Test
    fun `the same author twice on one preset counts once`() {
        val index = VisualizerPresetIndex.build(listOf(preset("Geiss + geiss - double")))
        assertEquals(listOf("geiss"), index.authorsOf(index.presets.single()))
        assertEquals(1, index.authors.single().count)
    }

    // ── Ordering is a property, not an accident ─────────────────────────

    @Test
    fun `facets are ordered by size, then by name`() {
        // So the order does not depend on the order the files were walked in.
        val index = VisualizerPresetIndex.build(
            listOf(
                preset("zed - a", "Sparkle"),
                preset("adam - b", "Reaction"),
                preset("eve - c", "Reaction"),
                preset("cain - d", "Fractal"),
            )
        )
        assertEquals(listOf("Reaction", "Fractal", "Sparkle"), index.categories.map { it.label })
    }

    @Test
    fun `an empty library indexes to nothing rather than failing`() {
        val index = VisualizerPresetIndex.build(emptyList())
        assertTrue(index.categories.isEmpty())
        assertTrue(index.authors.isEmpty())
        assertTrue(index.presets.isEmpty())
    }

    @Test
    fun `a preset with no folders at all still indexes`() {
        // Four presets in the pack sit at the top level with no category.
        val index = VisualizerPresetIndex.build(listOf(preset("suksma - loose")))
        assertTrue(index.categories.isEmpty())
        assertEquals(listOf("suksma"), index.authorsOf(index.presets.single()))
    }

    @Test
    fun `the whole corpus shape survives indexing`() {
        // A miniature of the real distribution: one dominant author, a
        // collaboration, a case split and an unattributed name, all at once.
        val index = VisualizerPresetIndex.build(
            listOf(
                preset("suksma - one", "Reaction", "Aurora", id = "1"),
                preset("suksma - two", "Reaction", "Aurora", id = "2"),
                preset("suksma - three", "Reaction", "Contagion", id = "3"),
                preset("EoS + Phat - chasers", "Dancer", "Comet Mirror", id = "4"),
                preset("Flexi - alien", "Dancer", "Comet Mirror", id = "5"),
                preset("flexi - bee", "Fractal", "Nested Spiral", id = "6"),
                preset("cope_beattrix", "Fractal", "Nested Spiral", id = "7"),
            )
        )
        assertEquals(3, index.categories.size)
        assertEquals(4, index.authors.size) // suksma, flexi, eos, phat
        assertEquals("suksma", index.authors.first().label)
        assertEquals(3, index.authors.first().count)
        assertEquals(
            listOf("Aurora" to 2, "Contagion" to 1),
            index.subcategoriesOf("reaction").map { it.label to it.count },
        )
    }
}
