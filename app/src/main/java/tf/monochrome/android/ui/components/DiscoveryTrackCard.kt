package tf.monochrome.android.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.UnifiedTrack
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * A track as a discovery card, sized and surfaced like [AlbumItem] and
 * [ArtistItem].
 *
 * The card it replaces was the odd one out on the page: hard-coded 140/132 dp
 * instead of the `MonoDimens` cover tokens, no glass, no press feedback, and no
 * quality badges — so a shelf of tracks visibly did not belong beside a shelf of
 * albums. This one is built from the same parts as its siblings, and adds the
 * play affordance a track card needs and an album card doesn't.
 */
@Composable
fun DiscoveryTrackCard(
    track: UnifiedTrack,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Hold for the full action sheet — queue, playlist, radio from here, go to
    // the artist. Tapping plays, which is the common case; everything else a
    // listener wants to do with a recommendation lives behind the hold.
    onLongClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .width(MonoDimens.coverCard)
            .bounceCombinedClick(onLongClick = onLongClick, onClick = onClick)
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = Color.Transparent,
    ) {
        Column(modifier = Modifier.padding(MonoDimens.spacingMd)) {
            Box(contentAlignment = Alignment.BottomEnd) {
                CoverImage(
                    url = track.artworkUri,
                    contentDescription = track.title,
                    size = MonoDimens.coverCard - MonoDimens.spacingMd * 2,
                    cornerRadius = MonoDimens.radiusSm,
                )
                // Tapping the card plays; the badge says so, rather than leaving
                // the listener to guess whether a tap opens or plays.
                Surface(
                    modifier = Modifier.padding(MonoDimens.spacingSm).size(28.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(4.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.height(MonoDimens.spacingSm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.isThxSpatialAudio) {
                    ThxBadgePill()
                    Spacer(modifier = Modifier.width(MonoDimens.spacingXs))
                }
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = track.artists.joinToString(", ") { it.name }
                    .ifBlank { track.artistName },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
