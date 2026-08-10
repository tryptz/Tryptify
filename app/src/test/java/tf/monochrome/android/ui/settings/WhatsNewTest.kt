package tf.monochrome.android.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the update bar appears, and the shape of the notes behind it. The
 * failure that matters here is a notice that either never goes away or never
 * shows up at all, so both directions are pinned down.
 */
class WhatsNewTest {

    private val newest = WhatsNew.releases.first().versionCode

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
        WhatsNew.releases.forEach { release ->
            val runs = release.entries.map { it.section }
                .fold(mutableListOf<String?>()) { acc, s ->
                    if (acc.lastOrNull() != s) acc.add(s)
                    acc
                }
                .filterNotNull()
            assertEquals("a section is split in ${release.versionName}", runs.distinct(), runs)
        }
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
    fun `the notes describe the build being shipped`() {
        // A release entry newer than the app itself would notify forever,
        // because marking seen stores the app's own versionCode.
        assertTrue(
            "notes claim ${WhatsNew.releases.first().versionCode}, app is ${WhatsNew.currentVersionCode}",
            WhatsNew.releases.first().versionCode <= WhatsNew.currentVersionCode,
        )
    }
}
