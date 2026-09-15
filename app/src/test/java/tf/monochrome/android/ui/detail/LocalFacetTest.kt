package tf.monochrome.android.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The facet that travels in `local_facet/{facet}/{value}`.
 *
 * `createRoute` itself is not tested here: it calls `android.net.Uri.encode`,
 * which is an unmocked android.jar stub in a JVM unit test and there is no
 * Robolectric on this source set. What is tested is the part that decides
 * whether the route can be parsed at all.
 */
class LocalFacetTest {

    @Test
    fun `fromKey round-trips every facet`() {
        LocalFacet.entries.forEach { facet ->
            assertEquals(facet, LocalFacet.fromKey(facet.key))
        }
    }

    @Test
    fun `an unknown or missing facet opens a genre rather than crashing`() {
        // A stale deep link, or a restored back stack from a build that had a
        // facet this one does not. Landing on genres is wrong but harmless;
        // throwing on the way into a nav destination is not.
        assertEquals(LocalFacet.GENRE, LocalFacet.fromKey(null))
        assertEquals(LocalFacet.GENRE, LocalFacet.fromKey(""))
        assertEquals(LocalFacet.GENRE, LocalFacet.fromKey("mood"))
    }

    @Test
    fun `keys are unique`() {
        val keys = LocalFacet.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `no key contains a path separator`() {
        // The route has exactly three segments. A key with a slash in it would
        // add a fourth and match nothing, which reads as a dead row rather
        // than as a bad constant.
        LocalFacet.entries.forEach { facet ->
            assert(!facet.key.contains('/')) { "${facet.name} key '${facet.key}' would split the route" }
            assert(facet.key.isNotBlank()) { "${facet.name} has a blank key" }
        }
    }

    @Test
    fun `every facet has a noun the hero can print`() {
        // Used as "Search this $noun" and "Unknown $noun", so it has to read as
        // a singular thing, lower case.
        LocalFacet.entries.forEach { facet ->
            assert(facet.noun.isNotBlank()) { "${facet.name} has no noun" }
            assertEquals(facet.noun.lowercase(), facet.noun)
        }
    }
}
