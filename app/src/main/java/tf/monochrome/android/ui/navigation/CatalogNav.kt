package tf.monochrome.android.ui.navigation

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

/** Open the artist page appropriate to [sourceType]. */
fun NavController.openArtist(sourceType: SourceType, artistId: Long) {
    val route = when (sourceType) {
        SourceType.LOCAL -> Screen.LocalArtistDetail.createRoute(artistId)
        else -> Screen.ArtistDetail.createRoute(artistId)
    }
    navigate(route)
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
                ?.let { navigate(Screen.LocalAlbumDetail.createRoute(it)) }
        }
        albumId.startsWith("col_album_") -> Unit // no collection detail screen
        else -> albumId.toLongOrNull()?.let { navigate(Screen.AlbumDetail.createRoute(it)) }
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
    navigate(Screen.ArtistDetail.createRoute(artistId))
}

/** Catalog-only album navigation (for domain `Track` rows). */
fun NavController.openCatalogAlbum(albumId: Long) {
    navigate(Screen.AlbumDetail.createRoute(albumId))
}
