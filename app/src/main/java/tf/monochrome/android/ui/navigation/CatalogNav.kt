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
 * Deliberately NOT `launchSingleTop`, which is a trap here. It does not compare
 * the route: `NavController.launchSingleTopInternal` matches on the destination
 * itself, then removes the top entry and puts a copy back carrying the new
 * arguments. `folder/{folderPath}` is one destination, so walking from a folder
 * into its subfolder *replaced* the folder you came from — Back had nothing
 * left to return to and dropped you out of the browser. The same held for every
 * trail through one screen type: album → album, artist → artist, genre → genre.
 *
 * The duplicate it was meant to stop is the double-tap, and [isSettled] above
 * already stops that: after a navigate the new entry is the current one and is
 * not RESUMED until its transition finishes, so the second tap is dropped.
 * Re-entering a screen from a *different* screen is what [navigateTool] is for.
 */
fun NavController.navigateSafe(route: String) {
    if (!isSettled()) return
    leavePlayerFor(route)
    navigate(route)
}

/**
 * Navigate to a *tool* screen — Settings, the equaliser, the mixer — collapsing
 * any earlier visit to it rather than stacking another copy.
 *
 * Content screens are allowed to chain: album → artist → album is a trail
 * through the catalogue, and Back should walk it in reverse. Tool screens are
 * not. They open from everywhere, including from each other (Settings opens the
 * equaliser, the equaliser is also reachable from the player sheet), so without
 * this a few minutes of fiddling leaves a dozen near-identical entries on the
 * stack and Back becomes a long walk home through screens the listener has
 * already dismissed once.
 *
 * [screen] supplies the route *pattern* to pop back to; [filled] is the actual
 * target, which differs when the route carries arguments (`settings?tab=4`).
 * Popping to a pattern that isn't on the stack is a no-op, so the first visit
 * behaves like an ordinary push.
 */
fun NavController.navigateTool(screen: Screen, filled: String = screen.route) {
    if (!isSettled()) return
    // Closing the player can uncover the very screen being asked for — Settings
    // → player → its output button. That visit is kept as it was left, tab and
    // scroll included, instead of being collapsed and opened fresh.
    if (leavePlayerFor(screen.route) && currentDestination?.route == screen.route) return
    navigate(filled) {
        launchSingleTop = true
        popUpTo(screen.route) { inclusive = true }
    }
}

/**
 * The player is a sheet over the screen it was opened from, not a step in the
 * trail, so going somewhere from it closes it first. Back from the player then
 * always lands where the listener was.
 *
 * It used to stay underneath, and the mini player — which collapses earlier
 * visits like any tool — then took everything above it along: player →
 * Settings → mini player → Back went Home, and the Settings visit, its tab and
 * scroll, was gone. With the player never left under a screen that shows the
 * mini player, there is nothing for it to collapse.
 *
 * The exception is a screen that hides the mini player ([chromeHiddenRoutes]:
 * the mixer, Oxford, car mode). Back is the only way to the player from those,
 * so it stays underneath.
 *
 * Returns whether the player was closed.
 */
private fun NavController.leavePlayerFor(targetRoute: String): Boolean {
    if (currentDestination?.route != Screen.NowPlaying.route) return false
    if (targetRoute in chromeHiddenRoutes || previousBackStackEntry == null) return false
    return popBackStack()
}

/**
 * Back from a screen's own back arrow — [navigateSafe]'s twin.
 *
 * A bare `popBackStack()` from an arrow is two bugs on a fast double tap. The
 * second tap lands while the first pop is still fading out (the screen it
 * leaves is still composed, arrow and all) and pops a second entry; and when
 * the entry under it is `home`, that pop takes the start destination too,
 * leaving an empty back stack — the pager is drawn only while the NavHost is on
 * `home`, so the screen goes blank. The screen popped to is not RESUMED until
 * its transition settles, so [isSettled] drops the second tap; and this never
 * pops the last entry, whatever the lifecycle says.
 */
fun NavController.popBackStackSafe(): Boolean {
    if (!isSettled() || previousBackStackEntry == null) return false
    return popBackStack()
}

private fun NavController.isSettled(): Boolean =
    currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true

/** Open the artist page appropriate to [sourceType]. */
fun NavController.openArtist(
    sourceType: SourceType,
    artistId: Long,
    artistName: String? = null,
) {
    // Local artists are always identified by a real row id, so the name is only
    // ever the catalogue path's fallback — see [openCatalogArtist].
    if (sourceType == SourceType.LOCAL) {
        if (artistId > 0L) navigateSafe(Screen.LocalArtistDetail.createRoute(artistId))
        return
    }
    openCatalogArtist(artistId, artistName)
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
fun NavController.openCatalogArtist(artistId: Long, artistName: String? = null) {
    // A track can reach the player with an artist *name* and no artist *id* —
    // some catalogue rows carry 0 — and opening the artist screen with that
    // guarantees a failed lookup and a dead-end error page. ("Qobuz artist not
    // available: 0" is what that looked like once the error reporting stopped
    // blaming the empty TIDAL pool for it.)
    //
    // The name is the way out: the screen can search the catalogue for it and
    // recover the id. The same artist reached from history works without any of
    // this, because those rows carry the real id already.
    // Without an id AND without a name there is nothing to look up, so the only
    // honest answer is to stay put rather than push a page that must fail.
    if (artistId <= 0L && artistName.isNullOrBlank()) return
    navigateSafe(Screen.ArtistDetail.createRoute(artistId, artistName))
}

/** Catalog-only album navigation (for domain `Track` rows). */
fun NavController.openCatalogAlbum(albumId: Long) {
    navigateSafe(Screen.AlbumDetail.createRoute(albumId))
}

/**
 * "Go to artist" for a song row or menu, or null when there is nowhere to go —
 * a null action hides the menu entry instead of offering a dead end.
 *
 * Routes by where the song really comes from, which [unified] knows and the
 * legacy [track] does not. A local or collection song turned into a `Track`
 * carries *made-up* ids — the artist's is a hash of its name, the album's a hash
 * of its id string — and handing those to the catalogue artist screen opened an
 * error page, or worse an unrelated artist whose real id the hash happened to
 * hit. A local song goes to its local artist; a collection or radio song has no
 * artist screen; everything else goes through [openCatalogArtist], name and
 * all, so an id of 0 is recovered from the name rather than opening
 * "artist not available: 0".
 */
fun NavController.trackArtistAction(
    track: tf.monochrome.android.domain.model.Track?,
    unified: tf.monochrome.android.domain.model.UnifiedTrack?,
): (() -> Unit)? {
    if (track == null) return null
    return when (unified?.sourceType) {
        SourceType.LOCAL -> unified.artistId?.takeIf { it > 0L }
            ?.let { id -> { navigateSafe(Screen.LocalArtistDetail.createRoute(id)) } }
        SourceType.COLLECTION, SourceType.LIVE_RADIO -> null
        else -> {
            val id = track.artist?.id ?: 0L
            val name = track.artist?.name?.takeIf { it.isNotBlank() } ?: track.displayArtist
            if (id <= 0L && name.isBlank()) null else ({ openCatalogArtist(id, name) })
        }
    }
}

/** "Go to album" — the album twin of [trackArtistAction], by the same rules. */
fun NavController.trackAlbumAction(
    track: tf.monochrome.android.domain.model.Track?,
    unified: tf.monochrome.android.domain.model.UnifiedTrack?,
): (() -> Unit)? {
    if (track == null) return null
    return when (unified?.sourceType) {
        SourceType.LOCAL, SourceType.COLLECTION ->
            unified.albumId?.takeIf { isNavigableAlbumId(it) }?.let { id -> { openAlbum(id) } }
        SourceType.LIVE_RADIO -> null
        else -> track.album?.id?.takeIf { it > 0L }?.let { id -> { openCatalogAlbum(id) } }
    }
}

/**
 * A tap on one artist name in a song row. The row hands over the id of the
 * name tapped, since a song can credit several; that id is trusted only for a
 * catalogue song. A local or collection song's ids are made up, so it goes
 * where [trackArtistAction] says instead.
 */
fun NavController.openTrackArtist(
    track: tf.monochrome.android.domain.model.Track,
    unified: tf.monochrome.android.domain.model.UnifiedTrack?,
    tappedArtistId: Long,
) {
    when (unified?.sourceType) {
        SourceType.LOCAL, SourceType.COLLECTION, SourceType.LIVE_RADIO ->
            trackArtistAction(track, unified)?.invoke()
        else -> {
            val name = track.artists.firstOrNull { it.id == tappedArtistId }?.name
                ?: track.artist?.takeIf { it.id == tappedArtistId }?.name
            openCatalogArtist(tappedArtistId, name)
        }
    }
}
