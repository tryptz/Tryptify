package tf.monochrome.android.visualizer

import tf.monochrome.android.domain.model.VisualizerPreset

/**
 * The axes a preset library can be browsed along, and the counts behind them.
 *
 * Nine thousand seven hundred and ninety-five presets arrive as one list with a
 * search box over it, which is a filing cabinet with no drawers: everything is
 * reachable and nothing is findable. The material to fix that is already in the
 * data and simply was not being read.
 *
 * **The tree is already two levels deep.** Every preset sits at
 * `Category/Subcategory/name.milk`, and the installer keeps both folder names
 * as tags -- eleven categories over a hundred and eighty-three subcategories.
 * The panel was rendering all hundred and ninety-four as one alphabetical row
 * of chips, so "Aurora" sat beside "Automata" with nothing to say that one is a
 * kind of Reaction and the other a kind of Fractal.
 *
 * **The author is in the file name.** Milkdrop names presets `author - title`,
 * and about six in seven here do. That is the axis a listener actually wants,
 * because preset authors have styles: Geiss looks like Geiss.
 */
class VisualizerPresetIndex private constructor(
    val presets: List<VisualizerPreset>,
    /** Top-level groups, largest first. */
    val categories: List<Facet>,
    /** Second-level groups, keyed by their parent category id. */
    private val subcategoriesByCategory: Map<String, List<Facet>>,
    /** Everyone credited on at least one preset, most prolific first. */
    val authors: List<Facet>,
    private val authorIdsByPreset: Map<String, List<String>>,
    private val titleByPreset: Map<String, String>,
) {

    /** One browsable group: a stable id, what to show, and how many are in it. */
    data class Facet(val id: String, val label: String, val count: Int)

    fun subcategoriesOf(categoryId: String): List<Facet> =
        subcategoriesByCategory[categoryId].orEmpty()

    /** Everyone credited on [preset], as author ids. Empty when unattributed. */
    fun authorsOf(preset: VisualizerPreset): List<String> =
        authorIdsByPreset[preset.id].orEmpty()

    /**
     * What to call an author id, or null if nobody holds it.
     *
     * Exists because the row subtitle needs it, and was reaching it by scanning
     * [authors] -- up to four hundred and seventy-nine string comparisons per
     * row, inside a LazyColumn item body, on the frame thread, while scrolling
     * a category of eighteen hundred. The list is already built; this is the
     * same data keyed the way the caller asks for it.
     */
    fun authorLabel(id: String): String? = authorsById[id]?.label

    private val authorsById: Map<String, Facet> = authors.associateBy { it.id }

    /**
     * The preset's name with the author stripped off. Two thousand presets by
     * one author all reading "suksma - …" waste the width that tells them
     * apart, and the author is already the heading they are filed under.
     */
    fun titleOf(preset: VisualizerPreset): String =
        titleByPreset[preset.id] ?: preset.displayName

    companion object {

        /** Splits `author - title`, on the first separator only. */
        private const val SEPARATOR = " - "

        /**
         * How collaborations are written, in the orders they appear.
         *
         * One in six credited presets names more than one author -- "shifter +
         * Flexi", "Stahlregen & EoS + Geiss + ORB + Phat". Left whole they
         * fragment the list: Flexi's own work lands under "Flexi", "flexi",
         * "shifter + Flexi" and a dozen more, none of which is the body of work
         * anyone is looking for. Split and folded, seven hundred and fifty-seven
         * spellings become four hundred and seventy-seven people.
         */
        private val COLLABORATION = Regex("""\s+(?:\+|&|vs\.?|feat\.?)\s+""", RegexOption.IGNORE_CASE)

        fun build(presets: List<VisualizerPreset>): VisualizerPresetIndex {
            val categoryCounts = LinkedHashMap<String, MutableFacet>()
            val subCounts = LinkedHashMap<String, LinkedHashMap<String, MutableFacet>>()
            val authorCounts = LinkedHashMap<String, MutableFacet>()
            val authorIds = HashMap<String, List<String>>(presets.size)
            val titles = HashMap<String, String>(presets.size)

            presets.forEach { preset ->
                preset.tags.getOrNull(0)?.let { top ->
                    categoryCounts.getOrPut(top.id) { MutableFacet(top.label) }.count++
                    preset.tags.getOrNull(1)?.let { sub ->
                        subCounts.getOrPut(top.id) { LinkedHashMap() }
                            .getOrPut(sub.id) { MutableFacet(sub.label) }.count++
                    }
                }

                val credited = creditsOf(preset.displayName)
                titles[preset.id] = credited.title
                if (credited.authors.isNotEmpty()) {
                    authorIds[preset.id] = credited.authors.map { it.id }
                    credited.authors.forEach { author ->
                        val facet = authorCounts.getOrPut(author.id) { MutableFacet(author.label) }
                        facet.count++
                        // The spelling most people used wins the label, so the
                        // heading reads "Flexi" rather than whichever of
                        // "Flexi"/"flexi" happened to be filed first.
                        facet.vote(author.label)
                    }
                }
            }

            return VisualizerPresetIndex(
                presets = presets,
                categories = categoryCounts.toFacets(),
                subcategoriesByCategory = subCounts.mapValues { it.value.toFacets() },
                authors = authorCounts.toFacets(),
                authorIdsByPreset = authorIds,
                titleByPreset = titles,
            )
        }

        private data class Credit(val id: String, val label: String)
        private data class Credits(val authors: List<Credit>, val title: String)

        /**
         * Reads `author - title`, or gives up and calls the whole thing a title.
         *
         * Roughly one preset in seven carries no separator at all, and those are
         * unattributed rather than badly named -- they keep their full name and
         * appear under no author, instead of inventing one from the first word.
         */
        private fun creditsOf(displayName: String): Credits {
            val at = displayName.indexOf(SEPARATOR)
            if (at <= 0) return Credits(emptyList(), displayName)
            val credit = displayName.substring(0, at).trim()
            val title = displayName.substring(at + SEPARATOR.length).trim()
            if (credit.isEmpty() || title.isEmpty()) return Credits(emptyList(), displayName)

            val authors = credit.split(COLLABORATION)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { Credit(id = it.lowercase(), label = it) }
                .distinctBy { it.id }
            return Credits(authors, title)
        }

        private class MutableFacet(var label: String) {
            var count = 0
            private val spellings = HashMap<String, Int>()

            fun vote(spelling: String) {
                val n = (spellings[spelling] ?: 0) + 1
                spellings[spelling] = n
                if (n > (spellings[label] ?: 0)) label = spelling
            }
        }

        /** Largest first, then alphabetically, so the order never depends on file order. */
        private fun Map<String, MutableFacet>.toFacets(): List<Facet> =
            map { (id, f) -> Facet(id, f.label, f.count) }
                .sortedWith(compareByDescending<Facet> { it.count }.thenBy { it.label.lowercase() })
    }
}
