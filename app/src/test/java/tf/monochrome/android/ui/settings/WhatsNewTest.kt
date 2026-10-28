package tf.monochrome.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.R
import java.io.File

/**
 * When the update bar appears, and the shape of the notes behind it. The
 * failure that matters here is a notice that either never goes away or never
 * shows up at all, so both directions are pinned down.
 */
class WhatsNewTest {

    private val newest = WhatsNew.releases.first().versionCode

    /** The first release whose notes are in every language: 1.9.51. */
    private val firstTranslated = 196

    @Test
    fun `notes exist and are newest first`() {
        assertTrue(WhatsNew.releases.isNotEmpty())
        val codes = WhatsNew.releases.map { it.versionCode }
        assertEquals(codes.sortedDescending(), codes)
    }

    @Test
    fun `every entry is short enough to actually be read`() {
        WhatsNew.releases.flatMap { it.entries }.forEach { entry ->
            assertTrue("empty title", entry.title.isNotBlank())
            assertTrue("empty body for '${entry.title}'", entry.body.isNotBlank())
            // Two or three lines' worth. Long enough to say what changed,
            // short enough that a list of them stays skimmable.
            assertTrue(
                "body too long for '${entry.title}': ${entry.body.length}",
                entry.body.length <= 260,
            )
        }
    }

    @Test
    fun `a section's entries sit together so its heading is printed once`() {
        // The panel prints a heading when the section changes, so a section
        // that reappears further down would print its title twice.
        //
        // Scoped per kind, because that is how the panel walks the list: it
        // takes New, then Changed, then Removed, and starts the section over
        // inside each. So the same section MAY appear under two kinds -- a
        // release that both adds and changes something in the player wants
        // exactly that -- but not twice within one of them.
        WhatsNew.releases.forEach { release ->
            val kinds = listOf<WhatsNewKind?>(null) + WhatsNewKind.entries
            kinds.forEach { kind ->
                val runs = release.entries.filter { it.kind == kind }
                    .map { it.section }
                    .fold(mutableListOf<String?>()) { acc, s ->
                        if (acc.lastOrNull() != s) acc.add(s)
                        acc
                    }
                    .filterNotNull()
                assertEquals(
                    "a section is split under ${kind?.heading ?: "no kind"} " +
                        "in ${release.versionName}",
                    runs.distinct(),
                    runs,
                )
            }
        }
    }

    @Test
    fun `the kinds a release uses come out in a fixed order`() {
        // New before Changed before Removed, whatever order the entries were
        // written in -- the panel filters by kind rather than reading the list
        // straight through, so the source order only decides what happens
        // inside a group.
        assertEquals(
            listOf("New", "Changed", "Removed"),
            WhatsNewKind.entries.map { it.heading },
        )
    }

    @Test
    fun `the Discover notes are a section of their own`() {
        val discover = WhatsNew.releases.flatMap { it.entries }
            .filter { it.section == "Discover (Beta)" }
        assertTrue("Discover has no notes", discover.isNotEmpty())
        assertTrue(
            "the map and the charts are the reason to open the page",
            discover.any { it.title.contains("map") } && discover.any { it.title.contains("Top 100") },
        )
    }

    @Test
    fun `someone up to date is not notified`() {
        assertFalse(WhatsNew.shouldNotify(seenVersionCode = newest, neverShow = false))
    }

    @Test
    fun `someone on an older build is notified`() {
        assertTrue(WhatsNew.shouldNotify(seenVersionCode = newest - 1, neverShow = false))
    }

    @Test
    fun `never-show wins over everything`() {
        assertFalse(WhatsNew.shouldNotify(seenVersionCode = 0, neverShow = true))
        assertFalse(WhatsNew.shouldNotify(seenVersionCode = newest - 1, neverShow = true))
    }

    @Test
    fun `a user who has never recorded a version is notified once`() {
        // Upgraders are indistinguishable from fresh installs by stored value,
        // and silently skipping them would defeat the point of the notice.
        assertTrue(WhatsNew.shouldNotify(seenVersionCode = 0, neverShow = false))
    }

    @Test
    fun `marking seen stops the notice`() {
        assertTrue(WhatsNew.shouldNotify(newest - 1, neverShow = false))
        // markWhatsNewSeen stores exactly this.
        assertFalse(WhatsNew.shouldNotify(WhatsNew.currentVersionCode, neverShow = false))
    }

    @Test
    fun `unseen only returns releases newer than what was acknowledged`() {
        assertTrue(WhatsNew.unseen(newest).isEmpty())
        assertEquals(WhatsNew.releases.size, WhatsNew.unseen(0).size)
        assertTrue(WhatsNew.unseen(newest - 1).all { it.versionCode > newest - 1 })
    }

    @Test
    fun `every release from 1951 on is in the listener's language`() {
        // The project rule: new user-facing text goes in all eight languages. The
        // notes did not, until 1.9.51; from there each entry, its section and
        // the kind headings come from strings.tsv, and the English written
        // here must be the table's, or the two drift apart.
        val english = File("l10n/strings.tsv").readLines(Charsets.UTF_8)
            .map { it.split('\t') }
            .filter { it.size > 1 }
            .associate { it[0] to it[1] }
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        fun englishOf(res: Int) = names[res]?.let { english[it] }

        val translated = WhatsNew.releases.filter { it.versionCode >= firstTranslated }
        assertTrue(translated.isNotEmpty())
        translated.flatMap { it.entries }.forEach { entry ->
            assertTrue("no translated title for '${entry.title}'", entry.titleRes != 0)
            assertTrue("no translated body for '${entry.title}'", entry.bodyRes != 0)
            assertEquals(entry.title, englishOf(entry.titleRes))
            assertEquals(entry.body, englishOf(entry.bodyRes))
            entry.section?.let { section ->
                val res = WhatsNew.sectionRes(section)
                assertTrue("section '$section' has no translation", res != 0)
                assertEquals(section, englishOf(res))
            }
        }
        WhatsNewKind.entries.forEach { assertEquals(it.heading, englishOf(it.headingRes)) }
    }

    @Test
    fun `the notes describe the build being shipped`() {
        // A release entry newer than the app itself would notify forever,
        // because marking seen stores the app's own versionCode.
        assertTrue(
            "notes claim ${WhatsNew.releases.first().versionCode}, app is ${WhatsNew.currentVersionCode}",
            WhatsNew.releases.first().versionCode <= WhatsNew.currentVersionCode,
        )
    }
}
