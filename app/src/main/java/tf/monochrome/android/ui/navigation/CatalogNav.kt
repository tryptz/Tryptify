package tf.monochrome.android.ui.navigation

import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.SourceType

/**
 * Source-aware navigation helpers shared by every track surface so artist/album
 * taps stay inside the right catalog namespace.
 *
 * A local artist/album id and a catalog (TIDAL/Qobuz) id are both `Long` but route
 * to different screens, so routing must branch on the track's [SourceType]. Targets
 * that don't exist (collection has no detail screens; unknown album-id shapes) are
 * no-ops — we never navigate to a dead route.
 */

/**
 * Navigate only while the current back-stack entry is still RESUMED. A rapid
 * double-tap fires two navigate() calls before the first destination settles;
 * the second used to push a duplicate screen. After the first navigation the
 * current entry leaves RESUMED, so the second tap is ignored here.
 *
 * `launchSingleTop` covers the other half of the same problem: re-entering the
 * screen you are already on — tapping "Settings" from inside Settings, or the
 * mini player while Now Playing is open — replaces the top entry instead of
 * stacking an identical one behind it.
 */
fun NavController.navigateSafe(route: String) {
    if (!isSettled()) return
    navigate(route) { launchSingleTop = true }
}

/**
 * Navigate to a *tool* screen — Settings, the equaliser, the mixer, Now Playing
 * — collapsing any earlier visit to it rather than stacking another copy.
 *
 * Content screens are allowed to chain: album → artist → album is a trail
 * through the catalogue, and Back should walk it in reverse. Tool screens are
 * not. They open from everywhere, including from each other (Now Playing opens
 * Settings, Settings opens the equaliser, the equaliser is also reachable from
 * the player sheet), so without this a few minutes of fiddling leaves a dozen
 * near-identical entries on the stack and Back becomes a long walk home through
 * screens the listener has already dismissed once.
 *
 * [screen] supplies the route *pattern* to pop back to; [filled] is the actual
 * target, which differs when the route carries arguments (`settings?tab=4`).
 * Popping to a pattern that isn't on the stack is a no-op, so the first visit
 * behaves like an ordinary push.
 */
fun NavController.navigateTool(screen: Screen, filled: String = screen.route) {
    if (!isSettled()) return
    navigate(filled) {
        launchSingleTop = true
        popUpTo(screen.route) { inclusive = true }
    }
}

private fun NavController.isSettled(): Boolean =
    currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

/** Open the artist page appropriate to [sourceType]. */
fun NavController.openArtist(sourceType: SourceType, artistId: Long) {
    val route = when (sourceType) {
        SourceType.LOCAL -> Screen.LocalArtistDetail.createRoute(artistId)
        else -> Screen.ArtistDetail.createRoute(artistId)
    }
    navigateSafe(route)
}

/**
 * Open the album page from a [UnifiedTrack.albumId] string. Catalog tracks use a
 * bare numeric id (`"123"`); local tracks use `"local_album_<n>"`. Collection
 * (`"col_album_*"`), blank, or unparseable values are no-ops (no target screen).
 */
fun NavController.openAlbum(albumId: String?) {
    if (albumId.isNullOrBlank()) return
    when {
        albumId.startsWith("local_album_") -> {
            albumId.removePrefix("local_album_").toLongOrNull()
                ?.let { navigateSafe(Screen.LocalAlbumDetail.createRoute(it)) }
        }
        albumId.startsWith("col_album_") -> Unit // no collection detail screen
        else -> albumId.toLongOrNull()?.let { navigateSafe(Screen.AlbumDetail.createRoute(it)) }
    }
}

/**
 * Whether [openAlbum] can route this [UnifiedTrack.albumId] to a real screen — used by
 * UI to decide whether to render the album title as a link or plain text. Catalog
 * (numeric) and local (`local_album_*`) ids are navigable; collection / null / unknown
 * shapes are not.
 */
fun isNavigableAlbumId(albumId: String?): Boolean {
    if (albumId.isNullOrBlank()) return false
    return when {
        albumId.startsWith("local_album_") -> albumId.removePrefix("local_album_").toLongOrNull() != null
        albumId.startsWith("col_album_") -> false
        else -> albumId.toLongOrNull() != null
    }
}

/** Catalog-only artist navigation (for domain `Track` rows, which are TIDAL/Qobuz). */
fun NavController.openCatalogArtist(artistId: Long) {
    // A track can reach the player with an artist *name* and no artist *id* —
    // some catalogue rows carry 0 — and opening the artist screen with that
    // guarantees a failed lookup and a dead-end error page. ("Qobuz artist not
    // available: 0" is what that looked like once the error reporting stopped
    // blaming the empty TIDAL pool for it.)
    //
    // Doing nothing is the better of the two available answers here: the screen
    // has nothing to show and no id to fetch one with. The same artist reached
    // from history works, because those rows carry the real id.
    if (artistId <= 0L) return
    navigateSafe(Screen.ArtistDetail.createRoute(artistId))
}

/** Catalog-only album navigation (for domain `Track` rows). */
fun NavController.openCatalogAlbum(albumId: Long) {
    navigateSafe(Screen.AlbumDetail.createRoute(albumId))
}
