package tf.monochrome.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import tf.monochrome.android.domain.model.UnifiedArtistRef
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.navigation.isNavigableAlbumId
import tf.monochrome.android.ui.theme.MonoDimens

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

    // Cap at one line so many credited artists don't wrap to unbounded rows
    // (uneven list-row heights, stretched cards).
    FlowRow(modifier = modifier, maxLines = 1) {
        artists.forEachIndexed { index, artist ->
            val isLink = artist.id != null && artist.id > 0L
            val separator = if (index < artists.lastIndex) ", " else ""
            Text(
                text = artist.name,
                style = style,
                color = if (isLink) linkColor else color,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Inline link: a hard 48dp min-size would blow up the one-line
                // subtitle row across every track list, so give it a Button role
                // instead — TalkBack then announces it as an activatable link.
                modifier = if (isLink) {
                    Modifier
                        .linkHitBox()
                        .clickable { onArtistClick(artist) }
                        .buttonSemantics(label = artist.name)
                } else Modifier,
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
    // Capped for the same reason [ClickableArtists] caps its own row, which was
    // half the fix: that one stops MANY ARTISTS from wrapping, but the album
    // segment is appended out here, so "artist • album" that did not fit still
    // dropped the album onto a second line and made that one list row taller
    // than its neighbours. One line, always — the row is a fixed height now
    // ([MonoDimens.listRowHeight]) and a second line would be clipped, not shown.
    FlowRow(modifier = modifier, maxLines = 1) {
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
                modifier = if (albumLinkable) {
                    Modifier
                        .linkHitBox()
                        .clickable { onAlbumClick() }
                        .buttonSemantics(label = albumTitle)
                } else Modifier,
            )
        }
    }
}

/**
 * Shrinks an inline link's touch target to slightly *inside* its own glyphs.
 *
 * These links sit in the middle of a song row, right where people tap to play,
 * so a tap that merely grazes the artist or album text used to navigate away
 * instead. Padding applied *before* `clickable` insets the hit box rather than
 * growing it, so a near-miss falls through to the row and plays the track,
 * while a deliberate tap on the text still navigates.
 *
 * This intentionally goes the opposite way to the usual 48dp minimum-target
 * guidance: the row behind it is already a large, correct target, and the
 * failure being fixed is over-triggering, not under-triggering. Both
 * destinations also remain available from the track's long-press menu.
 */
private fun Modifier.linkHitBox(): Modifier =
    padding(horizontal = 3.dp, vertical = MonoDimens.linkHitBoxV)
