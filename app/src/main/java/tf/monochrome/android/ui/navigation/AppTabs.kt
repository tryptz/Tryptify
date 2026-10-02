package tf.monochrome.android.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import tf.monochrome.android.R

/**
 * The tab bar's destinations.
 *
 * Four live in the glass pill and Search is the round button beside it, the way
 * Apple Music lays them out. A tab is not a page: Library is one tab over every
 * Library section, and which section it opens is remembered (see [pageForTab]).
 */
internal enum class AppTab(@StringRes val label: Int, @DrawableRes val glyph: Int) {
    HOME(R.string.tab_home, R.drawable.ic_glass_tab_home),
    DISCOVER(R.string.tab_discover, R.drawable.ic_glass_tab_discover),
    RADIO(R.string.tab_radio, R.drawable.ic_glass_tab_radio),
    LIBRARY(R.string.tab_library, R.drawable.ic_glass_tab_library),
    SEARCH(R.string.tab_search, R.drawable.ic_glass_tab_search),
}

/** The tab a page belongs to, which is the one the bar shows lit. */
internal fun tabFor(pageId: String?): AppTab = when (pageId) {
    Screen.Discover.route -> AppTab.DISCOVER
    RADIO_PAGE_ID -> AppTab.RADIO
    SEARCH_PAGE_ID -> AppTab.SEARCH
    in LIBRARY_PAGE_IDS -> AppTab.LIBRARY
    else -> AppTab.HOME
}

/**
 * The tabs inside the pill, in order. Discover and Radio are there only while
 * their page is: hiding one in Settings removes its tab. Home and Library always
 * are — [visiblePages] guarantees both have something to open.
 */
internal fun pillTabs(pages: List<String>): List<AppTab> = buildList {
    add(AppTab.HOME)
    if (Screen.Discover.route in pages) add(AppTab.DISCOVER)
    if (RADIO_PAGE_ID in pages) add(AppTab.RADIO)
    add(AppTab.LIBRARY)
}

/**
 * The page a tab opens. Library goes back to the section that was open last,
 * the way a tab keeps its place, and to the first visible section otherwise.
 */
internal fun pageForTab(tab: AppTab, pages: List<String>, lastLibrarySection: String?): String =
    when (tab) {
        AppTab.HOME -> Screen.Home.route
        AppTab.DISCOVER -> Screen.Discover.route
        AppTab.RADIO -> RADIO_PAGE_ID
        AppTab.SEARCH -> SEARCH_PAGE_ID
        AppTab.LIBRARY -> lastLibrarySection?.takeIf { it in pages && it in LIBRARY_PAGE_IDS }
            ?: pages.firstOrNull { it in LIBRARY_PAGE_IDS }
            ?: LIBRARY_PAGE_IDS.first()
    }
