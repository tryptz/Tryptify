package tf.monochrome.android.ui.navigation

/**
 * One swipeable top-level page: the id it is stored and keyed under, and what it
 * calls itself.
 */
internal data class AppPage(val id: String, val title: String)

/**
 * Every page the app can swipe between, in the order a fresh install gets them.
 *
 * There used to be two nested pagers: an outer one hardcoded to Home / Discover /
 * Library, and an inner one over the Library's own sections driven by the
 * `library_tab_order` preference. Only the inner one was reorderable, which is
 * why Discover could not be moved and why the settings list called itself
 * "Library Tab Order". The dot indicator already drew both as a single strip of
 * seven, so the app has been presenting one flat sequence for a while; this is
 * that sequence, made real.
 *
 * **Local sits above Overview on purpose.** The old `library_tab_order` default
 * started with `overview`, but the old `legacyLibrarySections` pinned `local` to
 * the front of whatever it read, so the page everyone actually landed on after
 * Home and Discover was Local. This list reproduces what was on screen, not what
 * the CSV said. Swapping these two would silently move every existing user's
 * landing page.
 */
internal val APP_PAGES: List<AppPage> = listOf(
    AppPage(Screen.Home.route, "Home"),
    AppPage(Screen.Discover.route, "Discover"),
    AppPage("local", "Local"),
    AppPage("overview", "Overview"),
    AppPage("playlists", "Playlists"),
    AppPage("favorites", "Favorites"),
    AppPage("downloads", "Downloads"),
)

internal val APP_PAGE_IDS: List<String> = APP_PAGES.map { it.id }

/** Display name for every page id. The one source for what a page is called. */
internal val APP_PAGE_TITLES: Map<String, String> = APP_PAGES.associate { it.id to it.title }

/** The order a fresh install gets, and the order missing pages are folded back into. */
internal val DEFAULT_PAGE_ORDER: List<String> = APP_PAGE_IDS

/**
 * The pages `LibraryScreen` renders — everything that is not Home or Discover.
 *
 * Every id here needs a branch in `LibraryScreen`'s `when (sectionId)`, or it
 * draws a blank page. `AppPagesTest` reads that file and checks.
 */
internal val LIBRARY_PAGE_IDS: List<String> =
    APP_PAGE_IDS - setOf(Screen.Home.route, Screen.Discover.route)

/**
 * What an install that predates the flat page list was actually looking at.
 *
 * Home was pager page 0 and Discover page 1, both hardcoded; everything after
 * them came out of the Library pager, whose `legacyLibrarySections` pinned Local
 * to the front of the stored order. Reproducing that exactly is the whole point:
 * appending "home" and "discover" to the end of the stored order instead would
 * fling a user's first two pages to the far end of a seven-page swipe on upgrade.
 */
internal fun migrateLegacyPageOrder(legacy: List<String>): List<String> =
    listOf(Screen.Home.route, Screen.Discover.route) +
        tf.monochrome.android.ui.library.legacyLibrarySections(legacy)

/**
 * A stored order brought up to date with the pages this build knows about.
 *
 * Unknown ids are dropped (a page a later build removed, or a hand-edited blob)
 * and duplicates collapse. Pages the stored order has never heard of are
 * *inserted* where the canonical order puts them — right after the nearest
 * earlier page that is stored — rather than appended.
 *
 * The old `library_tab_order` did neither, which is why any install that had ever
 * touched that setting could never see a section added in a later version: it
 * kept its five-entry CSV forever. Appending would have fixed the disappearance
 * but dropped every new page at the far end of the swipe, which is where nobody
 * finds one.
 */
internal fun reconcilePageOrder(stored: List<String>): List<String> {
    val out = stored.filter { it in APP_PAGE_TITLES }.distinct().toMutableList()
    APP_PAGE_IDS.forEachIndexed { canonical, id ->
        if (id in out) return@forEachIndexed
        val insertAt = APP_PAGE_IDS.take(canonical).lastOrNull { it in out }
            ?.let { out.indexOf(it) + 1 } ?: 0
        out.add(insertAt, id)
    }
    return out
}

/**
 * The one entry point: the stored order when there is one, the migrated legacy
 * order when there is not, always reconciled against this build's page list.
 *
 * [stored] is null only when `page_order` has never been written — which is the
 * signal that [legacyLibraryOrder] is still the truth. That is why
 * `PreferencesManager` deliberately publishes a nullable flow with no default of
 * its own: a default baked in down there would erase the distinction.
 */
internal fun resolvePageOrder(stored: List<String>?, legacyLibraryOrder: List<String>): List<String> =
    reconcilePageOrder(stored ?: migrateLegacyPageOrder(legacyLibraryOrder))

/**
 * The pages actually drawn, in order.
 *
 * Never empty. Hiding every page would leave a pager with nothing in it: a blank
 * screen with no top bar, and every route into Settings is a top bar. Settings
 * refuses to hide the last visible page and the view model refuses it again;
 * this is the third net, for a hidden set that arrived from another device's
 * settings sync and that no UI on this device ever saw.
 */
internal fun visiblePages(order: List<String>, hidden: Set<String>): List<String> =
    order.filter { it !in hidden }
        .ifEmpty { listOf(order.firstOrNull() ?: APP_PAGE_IDS.first()) }

/** Whether this page's visibility can be flipped — the last visible one cannot be hidden. */
internal fun canTogglePageVisibility(
    order: List<String>,
    hidden: Set<String>,
    id: String,
): Boolean = id in hidden || order.count { it !in hidden } > 1

/**
 * Where a route handed over by onboarding lands in the page list, or null when it
 * is an ordinary navigation target the caller should navigate to instead.
 *
 * "library" is not a page any more. It used to mean "the Library pager tab",
 * which landed on that pager's page 0 — Local, because the old pin put it there.
 * Onboarding still says "library", so it still means Local, falling back to
 * whichever library page is visible and then to the first page. It never
 * un-hides a page to satisfy a landing request.
 */
internal fun landingPageIndex(pages: List<String>, route: String): Int? = when (route) {
    Screen.Library.route ->
        pages.indexOf("local").takeIf { it >= 0 }
            ?: pages.indexOfFirst { it in LIBRARY_PAGE_IDS }.coerceAtLeast(0)
    in APP_PAGE_IDS -> pages.indexOf(route).coerceAtLeast(0)
    else -> null
}

/**
 * Where a remembered page id sits in a new page list — the first page when it is
 * gone.
 *
 * Keeps the user on the same *page* rather than the same index when the list
 * changes under them. Landing on page 0 when their page was hidden is
 * predictable; clamping to the last index would drop them somewhere arbitrary.
 */
internal fun restoredPageIndex(pages: List<String>, lastId: String?): Int =
    pages.indexOf(lastId).takeIf { it >= 0 } ?: 0

/**
 * Where Home sits in [pages] — where Back goes, and the only page Back goes to.
 *
 * Back used to retrace the route the user swiped, which made sense while the
 * swipe existed. It does not: pages are chosen from the list on Home, so the
 * only movement to undo is "I opened this page", and its undo is Home.
 *
 * Settings can hide Home, so this falls back to the first visible page rather
 * than returning the -1 that `indexOf` would. Back landing nowhere is worse
 * than Back landing somewhere unexpected.
 */
internal fun homePageIndex(pages: List<String>): Int =
    pages.indexOf(Screen.Home.route).takeIf { it >= 0 } ?: 0
