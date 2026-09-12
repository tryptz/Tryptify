package tf.monochrome.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The flat page list — the one sequence of pages the app swipes through.
 *
 * All of this is pure, so it is checked here rather than on a device. What is
 * NOT checked here, and has to be checked by hand, is the pager itself: the
 * content lambda's `getOrNull` guard against a stale index, and the back-handler
 * ordering that lets an active track selection win the first back press. Neither
 * is reachable without instrumentation.
 */
class AppPagesTest {

    /** The `library_tab_order` default that shipped before the flat page list. */
    private val legacyDefault = listOf("overview", "local", "playlists", "favorites", "downloads")

    // ── Migration off library_tab_order ──────────────────────────────────

    /**
     * The one that catches getting Local and Overview the wrong way round.
     *
     * The legacy CSV started with `overview`, but `legacyLibrarySections` pinned
     * `local` to the front, so Local is what people saw after Home and Discover.
     * If [DEFAULT_PAGE_ORDER] ever lists Overview first, this fails — and it
     * should, because that silently moves every upgrader's landing page.
     */
    @Test
    fun `the legacy default migrates to Home, Discover, then Local`() {
        assertEquals(
            listOf("home", "discover", "local", "overview", "playlists", "favorites", "downloads"),
            migrateLegacyPageOrder(legacyDefault),
        )
    }

    @Test
    fun `migration puts Home and Discover first, not last`() {
        val migrated = migrateLegacyPageOrder(legacyDefault)
        assertEquals("home", migrated.first())
        assertEquals("discover", migrated[1])
    }

    /** A user who reordered, and whose CSV no longer mentions local at all. */
    @Test
    fun `migration restores the pinned local page a reordered CSV dropped`() {
        assertEquals(
            listOf("home", "discover", "local", "downloads", "overview", "playlists", "favorites"),
            migrateLegacyPageOrder(listOf("downloads", "overview", "playlists", "favorites")),
        )
    }

    /** A fresh install and a never-touched upgrade must land on the same order. */
    @Test
    fun `an unset order resolves to the same list a fresh install gets`() {
        assertEquals(DEFAULT_PAGE_ORDER, resolvePageOrder(stored = null, legacyLibraryOrder = legacyDefault))
    }

    /**
     * Migrate and reconcile agree on a complete legacy order: the migration is
     * already the whole page list, so repairing it is a no-op. (A legacy order
     * that had lost entries is a different case — reconcile fills those back in,
     * which is the next test.)
     */
    @Test
    fun `reconciling a migrated complete order changes nothing`() {
        val migrated = migrateLegacyPageOrder(legacyDefault)
        assertEquals(migrated, reconcilePageOrder(migrated))
    }

    /** A legacy order missing sections still ends up with every page. */
    @Test
    fun `migrating a partial legacy order then reconciling restores every page`() {
        val resolved = resolvePageOrder(stored = null, legacyLibraryOrder = listOf("downloads", "overview"))
        assertEquals(APP_PAGE_IDS.sorted(), resolved.sorted())
        // The pages it did name keep the relative order it had them in.
        assertTrue(resolved.indexOf("downloads") < resolved.indexOf("overview"))
    }

    @Test
    fun `a stored order wins over the legacy one`() {
        val stored = listOf("downloads", "home", "discover", "local", "overview", "playlists", "favorites")
        assertEquals(stored, resolvePageOrder(stored, legacyDefault))
    }

    // ── Forward compatibility ────────────────────────────────────────────

    /**
     * A page this build added must appear for someone whose stored order predates
     * it — and appear where it belongs, not dumped at the end where nobody looks.
     */
    @Test
    fun `a page missing from a stored order is inserted at its canonical position`() {
        assertEquals(
            DEFAULT_PAGE_ORDER,
            reconcilePageOrder(DEFAULT_PAGE_ORDER - "downloads"),
        )
    }

    @Test
    fun `a missing page follows its nearest stored predecessor, wherever that moved`() {
        // favorites is absent; playlists (its canonical predecessor) sits last.
        val stored = listOf("downloads", "home", "discover", "local", "overview", "playlists")
        val out = reconcilePageOrder(stored)
        assertEquals("favorites", out[out.indexOf("playlists") + 1])
        assertEquals("downloads", out.first())
    }

    @Test
    fun `a missing page with no stored predecessor goes to the front`() {
        assertEquals(DEFAULT_PAGE_ORDER, reconcilePageOrder(listOf("downloads")))
    }

    @Test
    fun `an empty stored order becomes the default`() {
        assertEquals(DEFAULT_PAGE_ORDER, reconcilePageOrder(emptyList()))
    }

    @Test
    fun `an id this build does not know is dropped`() {
        assertFalse("podcasts" in reconcilePageOrder(listOf("home", "podcasts", "discover")))
    }

    @Test
    fun `duplicates collapse`() {
        assertEquals(1, reconcilePageOrder(listOf("home", "home", "discover")).count { it == "home" })
    }

    @Test
    fun `reconciling is idempotent`() {
        val cases = listOf(
            emptyList(),
            listOf("downloads"),
            DEFAULT_PAGE_ORDER - "downloads",
            listOf("home", "podcasts", "home", "discover"),
        )
        for (case in cases) {
            val once = reconcilePageOrder(case)
            assertEquals("reconciling $case twice differed", once, reconcilePageOrder(once))
        }
    }

    // ── Visibility ───────────────────────────────────────────────────────

    @Test
    fun `hiding a page removes it and leaves the rest in order`() {
        assertEquals(
            DEFAULT_PAGE_ORDER - "discover",
            visiblePages(DEFAULT_PAGE_ORDER, setOf("discover")),
        )
    }

    @Test
    fun `a hidden id this build does not know is inert`() {
        assertEquals(DEFAULT_PAGE_ORDER, visiblePages(DEFAULT_PAGE_ORDER, setOf("podcasts")))
    }

    /**
     * The brick guard. A pager with no pages is a blank screen with no top bar,
     * and every route into Settings is a top bar — so there would be no way back.
     */
    @Test
    fun `hiding every page still leaves one to draw`() {
        assertEquals(
            listOf(DEFAULT_PAGE_ORDER.first()),
            visiblePages(DEFAULT_PAGE_ORDER, DEFAULT_PAGE_ORDER.toSet()),
        )
    }

    @Test
    fun `the last visible page cannot be hidden`() {
        val allButHome = DEFAULT_PAGE_ORDER.toSet() - "home"
        assertFalse(canTogglePageVisibility(DEFAULT_PAGE_ORDER, allButHome, "home"))
    }

    @Test
    fun `an already-hidden page can always be shown again`() {
        val allButHome = DEFAULT_PAGE_ORDER.toSet() - "home"
        assertTrue(canTogglePageVisibility(DEFAULT_PAGE_ORDER, allButHome, "downloads"))
    }

    @Test
    fun `any page can be hidden while two are visible`() {
        val hidden = DEFAULT_PAGE_ORDER.toSet() - "home" - "local"
        assertTrue(canTogglePageVisibility(DEFAULT_PAGE_ORDER, hidden, "home"))
        assertTrue(canTogglePageVisibility(DEFAULT_PAGE_ORDER, hidden, "local"))
    }

    // ── Landing and restore ──────────────────────────────────────────────

    @Test
    fun `onboarding's library route lands on Local`() {
        assertEquals(
            DEFAULT_PAGE_ORDER.indexOf("local"),
            landingPageIndex(DEFAULT_PAGE_ORDER, Screen.Library.route),
        )
    }

    @Test
    fun `with Local hidden the library route lands on the next library page`() {
        val pages = visiblePages(DEFAULT_PAGE_ORDER, setOf("local"))
        assertEquals(pages.indexOf("overview"), landingPageIndex(pages, Screen.Library.route))
    }

    @Test
    fun `with every library page hidden the library route lands on the first page`() {
        val pages = visiblePages(DEFAULT_PAGE_ORDER, LIBRARY_PAGE_IDS.toSet())
        assertEquals(0, landingPageIndex(pages, Screen.Library.route))
    }

    @Test
    fun `a hidden page's own route lands on the first page rather than un-hiding it`() {
        val pages = visiblePages(DEFAULT_PAGE_ORDER, setOf("discover"))
        assertEquals(0, landingPageIndex(pages, Screen.Discover.route))
    }

    @Test
    fun `an ordinary route is not a page and is left to the navigator`() {
        assertNull(landingPageIndex(DEFAULT_PAGE_ORDER, "settings"))
    }

    @Test
    fun `a remembered page keeps its place when the list changes`() {
        val pages = visiblePages(DEFAULT_PAGE_ORDER, setOf("discover"))
        assertEquals(pages.indexOf("favorites"), restoredPageIndex(pages, "favorites"))
    }

    @Test
    fun `a remembered page that is gone falls to the first page`() {
        assertEquals(0, restoredPageIndex(DEFAULT_PAGE_ORDER, "podcasts"))
        assertEquals(0, restoredPageIndex(DEFAULT_PAGE_ORDER, null))
    }

    // ── Registry integrity ───────────────────────────────────────────────

    @Test
    fun `every page has a unique id and a name`() {
        assertEquals(APP_PAGE_IDS.size, APP_PAGE_IDS.distinct().size)
        for (page in APP_PAGES) {
            assertTrue("${page.id} has no title", page.title.isNotBlank())
        }
        assertEquals(APP_PAGE_IDS.toSet(), APP_PAGE_TITLES.keys)
    }

    @Test
    fun `the default order is every page exactly once`() {
        assertEquals(APP_PAGE_IDS.sorted(), DEFAULT_PAGE_ORDER.sorted())
        assertEquals(DEFAULT_PAGE_ORDER.size, DEFAULT_PAGE_ORDER.distinct().size)
    }

    @Test
    fun `the library pages are everything but Home and Discover`() {
        assertEquals(listOf("local", "overview", "playlists", "favorites", "downloads"), LIBRARY_PAGE_IDS)
    }

    // ── Cross-file guards ────────────────────────────────────────────────

    /**
     * Adding a page to the registry with no branch to render it produces a blank
     * page and no other symptom, so the dispatch is checked against the registry
     * rather than left to be noticed. Reading source in a test is how
     * `SettingsSearchIndexTest` holds its own cross-file rule.
     */
    @Test
    fun `every library page has a branch that renders it`() {
        val source = File("src/main/java/tf/monochrome/android/ui/library/LibraryScreen.kt").readText()
        val missing = LIBRARY_PAGE_IDS.filterNot { source.contains("\"$it\" ->") }
        assertTrue("library pages with no render branch: $missing", missing.isEmpty())
    }

    /**
     * An order or hidden set that does not sync is invisible until someone uses a
     * second device, which is the worst time to find out. Both keys are named
     * here so adding one and forgetting the other fails.
     */
    @Test
    fun `the page order and hidden pages both sync across devices`() {
        val source = File("src/main/java/tf/monochrome/android/data/preferences/PreferencesManager.kt").readText()
        // Anchor on the declaration, not the name: the name also appears in four
        // comments above it, and the first of those is where a plain
        // substringAfter lands.
        val syncBlock = source
            .substringAfter("val SETTINGS_SYNC_KEYS")
            .substringBefore("SETTINGS_SYNC_KEY_NAMES")
        assertTrue("page_order is not in SETTINGS_SYNC_KEYS", syncBlock.contains("PAGE_ORDER"))
        assertTrue("hidden_pages is not in SETTINGS_SYNC_KEYS", syncBlock.contains("HIDDEN_PAGES"))
    }

    // ── Back goes to Home, and only to Home ────────────────────────────
    //
    // Back used to retrace the route the user swiped. There is no swipe now —
    // pages are chosen from the list on Home — so the only movement to undo is
    // "I opened this page", and its undo is Home.

    @Test
    fun `back goes to Home wherever Home sits in the order`() {
        assertEquals(0, homePageIndex(listOf("home", "discover", "playlists")))
        // The user can drag Home anywhere in Settings, so this must not assume 0.
        assertEquals(2, homePageIndex(listOf("discover", "playlists", "home")))
    }

    @Test
    fun `a hidden Home falls back to the first page rather than nowhere`() {
        // Settings can hide Home. indexOf would answer -1 and Back would land
        // on no page at all, which is worse than landing on an unexpected one.
        assertEquals(0, homePageIndex(listOf("discover", "playlists")))
    }
}
