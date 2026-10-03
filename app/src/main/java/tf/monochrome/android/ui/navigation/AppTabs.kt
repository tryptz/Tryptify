package tf.monochrome.android.ui.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import tf.monochrome.android.R

/**
 * The nav bar's destinations.
 *
 * Four live in the glass pill and Search is the round button beside it, the way
 * Apple Music lays them out. Home and Library are fixed at either end; the two
 * between them are the listener's choice (see [NAV_BAR_CHOICES]). A tab is not
 * a page: Library is one tab over every Library section not pinned beside it,
 * and which section it opens is remembered (see [pageForTab]).
 */
internal enum class AppTab(@StringRes val label: Int, @DrawableRes val glyph: Int, val pageId: String?) {
    HOME(R.string.tab_home, R.drawable.ic_glass_tab_home, null),
    DISCOVER(R.string.tab_discover, R.drawable.ic_glass_tab_discover, Screen.Discover.route),
    RADIO(R.string.tab_radio, R.drawable.ic_glass_tab_radio, RADIO_PAGE_ID),
    PLAYLISTS(R.string.page_playlists, R.drawable.ic_glass_tab_playlists, "playlists"),
    LOCAL(R.string.page_local, R.drawable.ic_glass_tab_local, "local"),
    FAVORITES(R.string.page_favorites, R.drawable.ic_glass_tab_favorites, "favorites"),
    DOWNLOADS(R.string.page_downloads, R.drawable.ic_glass_tab_downloads, "downloads"),
    LIBRARY(R.string.tab_library, R.drawable.ic_glass_tab_library, null),
    SEARCH(R.string.tab_search, R.drawable.ic_glass_tab_search, null),
}

/** The pages that can take one of the nav bar's two middle buttons. */
internal val NAV_BAR_CHOICES: List<String> =
    listOf(Screen.Discover.route, RADIO_PAGE_ID) + LIBRARY_PAGE_IDS

/** What the bar held before it could be changed, and holds until it is. */
internal val DEFAULT_NAV_BAR_SLOTS: List<String> = listOf(Screen.Discover.route, RADIO_PAGE_ID)

/**
 * The stored choice, made safe to draw: two different pages the bar can hold.
 *
 * Whatever comes back from storage or sync — an id this build has no page for,
 * the same page twice, one entry or five — is reduced to that, falling back to
 * the defaults slot by slot, so a bad value can cost a slot its choice but never
 * leave the bar with a duplicate or a hole.
 */
internal fun sanitizeNavBarSlots(stored: List<String>?): List<String> {
    val picked = mutableListOf<String>()
    for (id in stored.orEmpty()) {
        if (id in NAV_BAR_CHOICES && id !in picked) picked += id
        if (picked.size == 2) break
    }
    for (id in DEFAULT_NAV_BAR_SLOTS + NAV_BAR_CHOICES) {
        if (picked.size == 2) break
        if (id !in picked) picked += id
    }
    return picked
}

/**
 * [slots] with [pageId] put in slot [index]. Choosing what the other slot
 * already holds swaps the two, so the bar never shows one page twice.
 */
internal fun withNavBarSlot(slots: List<String>, index: Int, pageId: String): List<String> {
    val next = sanitizeNavBarSlots(slots).toMutableList()
    val other = 1 - index
    if (next[other] == pageId) next[other] = next[index]
    next[index] = pageId
    return next
}

internal fun tabForPage(pageId: String): AppTab? = AppTab.entries.firstOrNull { it.pageId == pageId }

/**
 * The tab a page belongs to, which is the one the bar shows lit. A pinned page
 * lights its own button; any other Library section lights Library.
 */
internal fun tabFor(pageId: String?, slots: List<String> = DEFAULT_NAV_BAR_SLOTS): AppTab = when {
    pageId == null -> AppTab.HOME
    pageId == SEARCH_PAGE_ID -> AppTab.SEARCH
    pageId in slots -> tabForPage(pageId) ?: AppTab.HOME
    pageId in LIBRARY_PAGE_IDS -> AppTab.LIBRARY
    else -> tabForPage(pageId) ?: AppTab.HOME
}

/**
 * The tabs inside the pill, in order: Home, the two chosen pages, Library. A
 * chosen page that is hidden in Settings has no button — hiding it is the
 * stronger statement — and Home and Library always do, [visiblePages]
 * guaranteeing both have something to open.
 */
internal fun pillTabs(pages: List<String>, slots: List<String> = DEFAULT_NAV_BAR_SLOTS): List<AppTab> = buildList {
    add(AppTab.HOME)
    slots.filter { it in pages }.mapNotNullTo(this) { tabForPage(it) }
    add(AppTab.LIBRARY)
}

/**
 * The page a tab opens. Library goes back to the section that was open last,
 * the way a tab keeps its place, and to the first visible section otherwise —
 * skipping any section pinned to its own button, which already opens it.
 */
internal fun pageForTab(
    tab: AppTab,
    pages: List<String>,
    lastLibrarySection: String?,
    slots: List<String> = DEFAULT_NAV_BAR_SLOTS,
): String = when (tab) {
    AppTab.HOME -> Screen.Home.route
    AppTab.SEARCH -> SEARCH_PAGE_ID
    AppTab.LIBRARY -> {
        val open = pages.filter { it in LIBRARY_PAGE_IDS }
        val unpinned = open.filter { it !in slots }
        lastLibrarySection?.takeIf { it in unpinned }
            ?: unpinned.firstOrNull()
            ?: open.firstOrNull()
            ?: LIBRARY_PAGE_IDS.first()
    }
    else -> tab.pageId ?: Screen.Home.route
}
