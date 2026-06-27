package tf.monochrome.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.navigation.isNavigableAlbumId

/**
 * Renders a track's credited artists as individually tappable segments — a track
 * with multiple (featured) artists wires each name to its own profile. An artist
 * with a non-null catalog id is shown as a colored link and invokes [onArtistClick];
 * an id-less credit (e.g. a Qobuz free-text name) is shown as a plain, non-clickable
 * label. Falls back to [fallbackName] when no structured credits are available.
 *
 * Canonical multi-artist navigation component — reuse this anywhere a track's
 * artist line should route to per-artist pages.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClickableArtists(
    artists: List<UnifiedArtistRef>,
    fallbackName: String,
    onArtistClick: (UnifiedArtistRef) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    if (artists.isEmpty()) {
        Text(
            text = fallbackName,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier,
        )
        return
    }

    FlowRow(modifier = modifier) {
        artists.forEachIndexed { index, artist ->
            val isLink = artist.id != null && artist.id > 0L
            val separator = if (index < artists.lastIndex) ", " else ""
            Text(
                text = artist.name,
                style = style,
                color = if (isLink) linkColor else color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (isLink) Modifier.clickable { onArtistClick(artist) } else Modifier,
            )
            if (separator.isNotEmpty()) {
                Text(text = separator, style = style, color = color, maxLines = 1)
            }
        }
    }
}

/**
 * The standard track subtitle line — credited artists (each linkable via
 * [ClickableArtists]) followed by " • <album>" where the album title links to its
 * page when [UnifiedTrack.albumId] resolves to a real screen (catalog or local).
 * The canonical artist+album line for `UnifiedTrack` rows across the app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrackArtistAlbumLine(
    track: UnifiedTrack,
    onArtistClick: (UnifiedArtistRef) -> Unit,
    onAlbumClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    linkColor: Color = MaterialTheme.colorScheme.primary,
) {
    val albumTitle = track.albumTitle?.takeIf { it.isNotBlank() }
    val albumLinkable = albumTitle != null && isNavigableAlbumId(track.albumId)
    FlowRow(modifier = modifier) {
        ClickableArtists(
            artists = track.artists,
            fallbackName = track.artistName,
            onArtistClick = onArtistClick,
            style = style,
            color = color,
            linkColor = linkColor,
        )
        if (albumTitle != null) {
            Text(text = " • ", style = style, color = color, maxLines = 1)
            Text(
                text = albumTitle,
                style = style,
                color = if (albumLinkable) linkColor else color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (albumLinkable) Modifier.clickable { onAlbumClick() } else Modifier,
            )
        }
    }
}
