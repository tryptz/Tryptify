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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Circle
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
import tf.monochrome.android.domain.usecase.uiArtistRefs
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
    onArtistClick: ((Long) -> Unit)? = null,
    downloadState: TrackDownloadState? = null,
    selectionMode: Boolean = false,
    selected: Boolean = false
) {
    // While multi-selecting, per-row affordances (like, 3-dot, inline
    // artist/album links) would steal taps meant for selection — hide them.
    val effectiveOnLikeClick = onLikeClick.takeUnless { selectionMode }
    val effectiveOnMoreClick = onMoreClick.takeUnless { selectionMode }
    val effectiveOnAlbumClick = onAlbumClick.takeUnless { selectionMode }
    val effectiveOnArtistClick = onArtistClick.takeUnless { selectionMode }

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
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
            verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (selected) "Selected" else "Not selected",
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(MonoDimens.spacingMd))
        }

        if (trackNumber != null) {
            Text(
                text = trackNumber.toString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(32.dp)
            )
        }

        if (showCover) {
            val coverModifier = if (effectiveOnAlbumClick != null) {
                Modifier.clickable { effectiveOnAlbumClick() }
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
                track.channelBadge?.let { badge ->
                    Spacer(modifier = Modifier.width(4.dp))
                    ChannelBadgePill(badge)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (effectiveOnArtistClick != null) {
                    ClickableArtists(
                        artists = track.uiArtistRefs(),
                        fallbackName = track.displayArtist,
                        onArtistClick = { ref -> ref.id?.let { effectiveOnArtistClick(it) } },
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    Text(
                        text = track.displayArtist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (track.album != null && effectiveOnAlbumClick != null) {
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
                        modifier = Modifier.clickable(onClick = effectiveOnAlbumClick)
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

        if (effectiveOnLikeClick != null) {
            IconButton(onClick = effectiveOnLikeClick, modifier = Modifier.padding(start = 4.dp)) {
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
            Spacer(modifier = Modifier.width(if (effectiveOnLikeClick == null) 8.dp else 4.dp))
            Text(
                text = track.formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (effectiveOnMoreClick != null) {
            IconButton(onClick = effectiveOnMoreClick) {
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

/**
 * Small rounded pill for multichannel sources ("5.1", "7.1"). Rendered next
 * to the title so surround availability is visible at a glance in any list.
 */
@Composable
fun ChannelBadgePill(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}
