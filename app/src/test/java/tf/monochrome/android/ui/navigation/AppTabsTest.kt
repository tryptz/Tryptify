package tf.monochrome.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nav bar's mapping between tabs and the pager's pages. Pure, so checked here. */
class AppTabsTest {

    private val allPages = visiblePages(DEFAULT_PAGE_ORDER, emptySet()) + SEARCH_PAGE_ID

    @Test
    fun `every page lights exactly one tab`() {
        assertEquals(AppTab.HOME, tabFor("home"))
        assertEquals(AppTab.DISCOVER, tabFor("discover"))
        assertEquals(AppTab.RADIO, tabFor(RADIO_PAGE_ID))
        assertEquals(AppTab.SEARCH, tabFor(SEARCH_PAGE_ID))
        for (section in LIBRARY_PAGE_IDS) assertEquals(section, AppTab.LIBRARY, tabFor(section))
    }

    /** Before the pager has a page (the very first frame) the bar shows Home, not nothing. */
    @Test
    fun `no page yet means Home`() {
        assertEquals(AppTab.HOME, tabFor(null))
    }

    @Test
    fun `every tab opens a page the pager has`() {
        for (tab in AppTab.entries) {
            assertTrue(tab.name, pageForTab(tab, allPages, lastLibrarySection = null) in allPages)
        }
    }

    @Test
    fun `Library returns to the section open last`() {
        assertEquals("downloads", pageForTab(AppTab.LIBRARY, allPages, lastLibrarySection = "downloads"))
    }

    @Test
    fun `Library falls back to the first visible section when the last one was hidden`() {
        val pages = visiblePages(DEFAULT_PAGE_ORDER, setOf("downloads"))
        assertEquals("local", pageForTab(AppTab.LIBRARY, pages, lastLibrarySection = "downloads"))
    }

    @Test
    fun `the pill holds Home, Discover, Radio and Library, with Search outside it`() {
        assertEquals(
            listOf(AppTab.HOME, AppTab.DISCOVER, AppTab.RADIO, AppTab.LIBRARY),
            pillTabs(allPages),
        )
        assertFalse(AppTab.SEARCH in pillTabs(allPages))
    }

    @Test
    fun `hiding Discover or Radio removes its tab and nothing else`() {
        val pages = visiblePages(DEFAULT_PAGE_ORDER, setOf("discover", RADIO_PAGE_ID))
        assertEquals(listOf(AppTab.HOME, AppTab.LIBRARY), pillTabs(pages))
    }

    // ── The two middle buttons are the listener's choice ──────────────────

    @Test
    fun `out of the box the bar is what it always was`() {
        assertEquals(listOf("discover", RADIO_PAGE_ID), sanitizeNavBarSlots(null))
    }

    @Test
    fun `any two choices become the middle buttons, in slot order`() {
        val slots = listOf("favorites", "discover")
        assertEquals(
            listOf(AppTab.HOME, AppTab.FAVORITES, AppTab.DISCOVER, AppTab.LIBRARY),
            pillTabs(allPages, slots),
        )
    }

    @Test
    fun `every choice has a button of its own`() {
        for (id in NAV_BAR_CHOICES) {
            val tab = tabForPage(id)
            assertTrue(id, tab != null && tab.pageId == id)
        }
    }

    @Test
    fun `a pinned section lights its own button, the rest still light Library`() {
        val slots = listOf("favorites", "local")
        assertEquals(AppTab.FAVORITES, tabFor("favorites", slots))
        assertEquals(AppTab.LOCAL, tabFor("local", slots))
        assertEquals(AppTab.LIBRARY, tabFor("playlists", slots))
        assertEquals(AppTab.LIBRARY, tabFor("downloads", slots))
    }

    @Test
    fun `Library skips a section pinned to its own button`() {
        val slots = listOf("playlists", RADIO_PAGE_ID)
        // Even when it was the last one open: its button already opens it.
        val opened = pageForTab(AppTab.LIBRARY, allPages, lastLibrarySection = "playlists", slots = slots)
        assertTrue(opened in LIBRARY_PAGE_IDS && opened != "playlists")
    }

    @Test
    fun `Library still opens something with every section it has pinned`() {
        val pages = visiblePages(DEFAULT_PAGE_ORDER, setOf("local", "downloads"))
        val slots = listOf("playlists", "favorites")
        assertTrue(pageForTab(AppTab.LIBRARY, pages, lastLibrarySection = null, slots = slots) in pages)
    }

    @Test
    fun `a hidden page loses its button but keeps its slot`() {
        val pages = visiblePages(DEFAULT_PAGE_ORDER, setOf("favorites"))
        assertEquals(listOf(AppTab.HOME, AppTab.DISCOVER, AppTab.LIBRARY), pillTabs(pages, listOf("favorites", "discover")))
    }

    @Test
    fun `a bad stored value can cost a slot its choice but never break the bar`() {
        assertEquals(listOf("favorites", "discover"), sanitizeNavBarSlots(listOf("favorites", "favorites")))
        assertEquals(listOf("local", "discover"), sanitizeNavBarSlots(listOf("no_such_page", "local")))
        assertEquals(listOf("local", "playlists"), sanitizeNavBarSlots(listOf("local", "playlists", "favorites")))
        assertEquals(listOf("discover", RADIO_PAGE_ID), sanitizeNavBarSlots(listOf("", "home", SEARCH_PAGE_ID)))
    }

    @Test
    fun `picking the page the other slot holds swaps the two`() {
        val slots = listOf("discover", "favorites")
        assertEquals(listOf("favorites", "discover"), withNavBarSlot(slots, 0, "favorites"))
        assertEquals(listOf("discover", "local"), withNavBarSlot(slots, 1, "local"))
    }
}
