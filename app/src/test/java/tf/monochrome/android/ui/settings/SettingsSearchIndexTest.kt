package tf.monochrome.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import tf.monochrome.android.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

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

    private val settingsSources by lazy {
        File("src/main/java/tf/monochrome/android/ui/settings")
            .walkTopDown()
            .filter { it.extension == "kt" && it.name != "SettingsSearchIndex.kt" }
            .joinToString("\n") { it.readText() }
    }

    /** Resource id → name, from the generated R class. */
    private val stringNames: Map<Int, String> by lazy {
        R.string::class.java.fields.associate { it.getInt(null) to it.name }
    }

    /** One language's shipped strings, by resource name, unescaped as a device shows them. */
    private fun shipped(folder: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$folder/strings.xml"))
        val out = mutableMapOf<String, String>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            if (e.tagName == "string") {
                out[e.getAttribute("name")] = e.textContent.removeSurrounding("\"").replace("\\'", "'").replace("\\\"", "\"")
            }
        }
        return out
    }

    private fun resolverFor(folder: String): (Int) -> String {
        val strings = shipped(folder)
        return { id -> strings.getValue(stringNames.getValue(id)) }
    }

    /**
     * A tab result scrolls to its row by matching the title as the screen
     * shows it, so a title no row uses anchors nothing — it lands on the tab
     * and stops, silently, with no way to tell from the outside that it was
     * supposed to do more. Renaming a row is exactly how that happens.
     *
     * Rows are drawn from string resources, so a translated entry has to name
     * a resource the settings screens actually use; a name that is never
     * translated has to appear in them as it is.
     */
    @Test
    fun `every tab entry names something the settings screens actually say`() {
        val unanchored = SettingsSearchIndex
            .filter { it.destination is SettingsDestination.Tab }
            .filterNot { entry ->
                when (val res = entry.titleRes) {
                    null -> settingsSources.contains("\"${entry.title}\"")
                    else -> settingsSources.contains("R.string.${stringNames.getValue(res)}")
                }
            }
            .map { it.title }

        assertTrue("index titles that appear nowhere in Settings: $unanchored", unanchored.isEmpty())
    }

    /**
     * The on-screen title of an entry is its English name, or a resource that
     * says the same thing — not some other row's string that happens to sit
     * nearby. Checked loosely (one shares a word with the other) because some
     * entries deliberately point at the row that *is* the feature under a
     * different name: "Font scale" lands on "Font Size".
     */
    @Test
    fun `each entry's resource is about the same thing as its title`() {
        val english = resolverFor("values")
        val words = Regex("[a-z]{3,}")
        val stray = SettingsSearchIndex.filter { entry ->
            val res = entry.titleRes ?: return@filter false
            val a = words.findAll(entry.title.lowercase()).map { it.value.removeSuffix("s") }.toSet()
            val b = words.findAll(english(res).lowercase()).map { it.value.removeSuffix("s") }.toSet()
            a.isNotEmpty() && b.isNotEmpty() && (a intersect b).isEmpty()
        }.map { it.title to english(it.titleRes!!) }

        assertTrue("entries showing an unrelated title: $stray", stray.isEmpty())
    }

    /**
     * Someone reading the app in German types the German word. Without the
     * resolver the index only knew English titles, so every search in another
     * language came back empty except the ones that happen to be loanwords.
     */
    @Test
    fun `search finds settings by their translated names`() {
        val german = resolverFor("values-de")
        assertEquals("Theme", searchSettings("farbschema", resolve = german).first().title)
        assertTrue(searchSettings("überblendung", resolve = german).any { it.title == "Crossfade" })

        val japanese = resolverFor("values-ja")
        assertTrue(searchSettings("ギャップレス", resolve = japanese).any { it.title == "Gapless playback" })

        // English still works in every language: it is what guides and the
        // changelog name things by.
        assertEquals("Theme", searchSettings("theme", resolve = german).first().title)
    }

    /** Tab names in results come from resources too, and every tab has one. */
    @Test
    fun `every tab label has a translation`() {
        val german = resolverFor("values-de")
        val labels = SettingsSearchIndex.map { it.tabLabel }.distinct()
        for (label in labels) {
            assertTrue("no chip text for tab $label", german(settingsTabLabelRes(label)).isNotBlank())
        }
    }

    /** Sub-screens have to be reachable, or the index is only a tab switcher. */
    @Test
    fun `results can leave the settings screen`() {
        val routed = SettingsSearchIndex.count { it.destination is SettingsDestination.Route }
        assertTrue("nothing in the index opens a screen of its own", routed >= 5)
    }
}
