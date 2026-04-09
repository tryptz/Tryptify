package tf.monochrome.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.data.downloads.TrackDownloadState
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.theme.ExplicitBadge
import tf.monochrome.android.ui.theme.MonoDimens

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackItem(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    isLiked: Boolean = false,
    onLikeClick: (() -> Unit)? = null,
    showCover: Boolean = true,
    showDuration: Boolean = true,
    trackNumber: Int? = null,
    onMoreClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    downloadState: TrackDownloadState? = null
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingXs)
            .bounceCombinedClick(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .liquidGlass(shape = MonoDimens.shapeMd),
        shape = MonoDimens.shapeMd,
        color = Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
            verticalAlignment = Alignment.CenterVertically
    ) {
        if (trackNumber != null) {
            Text(
                text = trackNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
        }

        if (showCover) {
            val coverModifier = if (onAlbumClick != null) {
                Modifier.clickable { onAlbumClick() }
            } else {
                Modifier
            }
            androidx.compose.foundation.layout.Box(modifier = coverModifier) {
                CoverImage(
                    url = track.coverUrl,
                    contentDescription = track.title,
                    size = MonoDimens.coverList,
                    cornerRadius = MonoDimens.radiusSm
                )
            }
            Spacer(modifier = Modifier.width(MonoDimens.spacingMd))
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.explicit) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Explicit,
                        contentDescription = "Explicit",
                        tint = ExplicitBadge,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.displayArtist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (track.album != null && onAlbumClick != null) {
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = track.album.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable(onClick = onAlbumClick)
                    )
                } else if (track.album != null) {
                    Text(
                        text = " • ${track.album.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (onLikeClick != null) {
            IconButton(onClick = onLikeClick, modifier = Modifier.padding(start = 4.dp)) {
                Icon(
                    imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (isLiked) "Unlike" else "Like",
                    tint = if (isLiked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (downloadState != null) {
            Spacer(modifier = Modifier.width(4.dp))
            DownloadIndicator(
                state = downloadState,
                size = 18f
            )
        }

        if (showDuration) {
            Spacer(modifier = Modifier.width(if (onLikeClick == null) 8.dp else 4.dp))
            Text(
                text = track.formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (onMoreClick != null) {
            IconButton(onClick = onMoreClick) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = "More options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
}
