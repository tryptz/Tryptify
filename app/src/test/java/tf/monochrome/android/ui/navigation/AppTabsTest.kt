package tf.monochrome.android.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tab bar's mapping between tabs and the pager's pages. Pure, so checked here. */
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
}
