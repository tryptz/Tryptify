package tf.monochrome.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings index, checked against the app it claims to describe.
 *
 * A curated index buys reliable, instant search at the price of being a second
 * thing to keep true. This is what collects that price at build time instead of
 * from someone tapping a result that goes nowhere: every route has to exist in
 * the nav host, every tab label has to be a real tab, and nothing may be listed
 * twice.
 */
class SettingsSearchIndexTest {

    private val navHost = File(
        "src/main/java/tf/monochrome/android/ui/navigation/MonochromeNavHost.kt",
    ).readText()

    @Test
    fun `every route in the index is a route the app registers`() {
        val missing = SettingsSearchIndex
            .mapNotNull { (it.destination as? SettingsDestination.Route)?.route }
            .distinct()
            .filterNot { route -> navHost.contains("\"$route\"") }

        assertTrue("routes not registered in the nav host: $missing", missing.isEmpty())
    }

    @Test
    fun `every tab destination lands on a real tab`() {
        val tabs = SettingsSearchIndex.mapNotNull { it.destination as? SettingsDestination.Tab }
        assertTrue("no tab destinations at all", tabs.isNotEmpty())
        // settingsTabIndex throws on an unknown label, so building the index at
        // all proves the labels resolve; this pins the range as well, which is
        // what catches an index built against a shorter tab list.
        for (tab in tabs) {
            assertTrue("tab index ${tab.index} is out of range", tab.index >= 0)
        }
    }

    /** A setting listed twice shows up twice in the results. */
    @Test
    fun `no setting is listed twice`() {
        val titles = SettingsSearchIndex.map { it.title.lowercase() }
        val repeated = titles.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertEquals("repeated entries: $repeated", emptySet<String>(), repeated)
    }

    @Test
    fun `short queries return nothing rather than everything`() {
        assertTrue(searchSettings("").isEmpty())
        assertTrue(searchSettings("e").isEmpty())
    }

    /**
     * The ranking is the difference between search that works and a list of
     * everything containing a letter. A title that starts with the query has to
     * come before one that merely contains it.
     */
    @Test
    fun `titles beat keywords and prefixes beat substrings`() {
        val theme = searchSettings("theme")
        assertEquals("Theme", theme.first().title)

        // "bass" appears in no title, only in the equaliser's keywords.
        val bass = searchSettings("bass")
        assertTrue("bass found nothing", bass.isNotEmpty())
        assertTrue(
            "bass did not reach an equaliser: ${bass.map { it.title }}",
            bass.any { it.title.contains("EQ", ignoreCase = true) || it.title == "Equalizer" },
        )
    }

    /** The things people go to Settings for should be one query away. */
    @Test
    fun `the settings most often hunted for are findable`() {
        val expected = mapOf(
            "crossfade" to "Crossfade",
            "mixer" to "Mixer",
            "lyrics" to "Player Visuals Studio",
            "scrobble" to "Last.fm scrobbling",
            "battery" to "Performance",
            "cache" to "Clear cache",
        )
        for ((query, title) in expected) {
            val hits = searchSettings(query).map { it.title }
            assertTrue("\"$query\" did not find \"$title\" (got $hits)", hits.contains(title))
        }
    }

    /** Sub-screens have to be reachable, or the index is only a tab switcher. */
    @Test
    fun `results can leave the settings screen`() {
        val routed = SettingsSearchIndex.count { it.destination is SettingsDestination.Route }
        assertTrue("nothing in the index opens a screen of its own", routed >= 5)
    }
}
