package tf.monochrome.android.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Explicit
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.data.downloads.DownloadStatus
import tf.monochrome.android.data.downloads.TrackDownloadState
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.usecase.uiArtistRefs
import tf.monochrome.android.ui.theme.ExplicitBadge
import tf.monochrome.android.ui.navigation.LocalNowPlayingTrackId
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackItem(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    showCover: Boolean = true,
    showDuration: Boolean = true,
    trackNumber: Int? = null,
    onMoreClick: (() -> Unit)? = null,
    onAlbumClick: (() -> Unit)? = null,
    onArtistClick: ((Long) -> Unit)? = null,
    downloadState: TrackDownloadState? = null,
    isDownloaded: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false
) {
    // While multi-selecting, per-row affordances (3-dot, inline artist/album
    // links) would steal taps meant for selection — hide them. Liking is in
    // the 3-dot menu, as in the Library's Local list.
    val effectiveOnMoreClick = onMoreClick.takeUnless { selectionMode }
    val effectiveOnAlbumClick = onAlbumClick.takeUnless { selectionMode }
    val effectiveOnArtistClick = onArtistClick.takeUnless { selectionMode }

    // The row for whatever is playing right now. Read here, not passed in: see
    // LocalNowPlayingTrackId. A track change recomposes the visible rows and
    // nothing else.
    val nowPlaying = track.id == LocalNowPlayingTrackId.current

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
        // Selection wins over now-playing: while multi-selecting, whether a row
        // is ticked is the only thing the colour is being asked to say.
        color = when {
            selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
            nowPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
            else -> Color.Transparent
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MonoDimens.listRowHeight)
                .padding(horizontal = MonoDimens.listItemPaddingH),
            verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode) {
            Icon(
                imageVector = if (selected) Icons.Default.CheckCircle else Icons.Outlined.Circle,
                contentDescription = if (selected) stringResource(R.string.state_selected) else stringResource(R.string.state_not_selected),
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
            // The artwork plays the track: it's the largest target in the row
            // and the one people aim at to start a song. It used to open the
            // album instead, which made a big, obvious-looking play target do
            // something else entirely. Album and artist are still one long-press
            // away, in the track's context menu.
            CoverImage(
                url = track.coverUrl,
                contentDescription = track.title,
                size = MonoDimens.coverList,
                cornerRadius = MonoDimens.radiusSm
            )
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
                    // The tint alone is easy to miss against a dark row; the
                    // title carrying the accent too is what makes the playing
                    // track findable at a glance in a long list.
                    color = if (nowPlaying) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (track.explicit && tf.monochrome.android.ui.theme.showExplicitBadges()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.Explicit,
                        contentDescription = stringResource(R.string.badge_explicit),
                        tint = ExplicitBadge,
                        modifier = Modifier.padding(top = 1.dp)
                    )
                }
            }
            // Artist and album, then where it plays from and its badges, on
            // one line: the Library's Local list row, which every track list
            // now shares. The title keeps the whole first line.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
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
                            // weight(fill=false) so a long album title ellipsizes and
                            // shares the row instead of squeezing the artist to zero.
                            // Inset before clickable so the hit box lands inside
                            // the glyphs: a near-miss plays the track instead of
                            // navigating. See ClickableArtists.linkHitBox.
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = 3.dp, vertical = 4.dp)
                                .clickable(onClick = effectiveOnAlbumClick)
                        )
                    } else if (track.album != null) {
                        Text(
                            text = " • ${track.album.title}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }
                }
                // Downloaded means the device, as in search.
                val source = if (isDownloaded) tf.monochrome.android.domain.model.SourceType.LOCAL
                    else LocalTrackSource.current(track)
                if (source != null) {
                    Spacer(modifier = Modifier.width(MonoDimens.spacingSm))
                    SourcePill(source)
                }
                if (track.isThxSpatialAudio) {
                    Spacer(modifier = Modifier.width(4.dp))
                    ThxBadgePill()
                }
                if (track.isDolbyAtmos) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DolbyAtmosBadgePill()
                }
                track.channelBadge?.let { badge ->
                    Spacer(modifier = Modifier.width(4.dp))
                    ChannelBadgePill(badge)
                }
                track.qualityBadge?.let { badge ->
                    Spacer(modifier = Modifier.width(MonoDimens.spacingSm))
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }
        }

        // In-flight state wins while a download is running; once it settles the
        // row falls back to the persistent "on this device" badge, so the mark
        // doesn't vanish the moment the transfer finishes.
        val liveDownload = downloadState?.takeIf { it.status != DownloadStatus.IDLE }
        if (liveDownload != null) {
            Spacer(modifier = Modifier.width(4.dp))
            DownloadIndicator(
                state = liveDownload,
                size = 18f
            )
        } else if (isDownloaded) {
            Spacer(modifier = Modifier.width(4.dp))
            DownloadedBadge(size = 18f)
        }

        if (showDuration) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = track.formattedDuration,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (effectiveOnMoreClick != null) {
            // Compact, as in the Library's Local list.
            IconButton(onClick = effectiveOnMoreClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.action_more_options),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
}

/**
 * Small rounded pill for multichannel sources ("5.1", "7.1"). Rendered on the
 * row's badge line so surround availability is visible at a glance in any list.
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

/**
 * Highlighted "THX" pill for Qobuz THX Spatial Audio releases. Deliberately
 * stronger than the translucent quality/channel pills — solid primary fill —
 * so the spatial designation stands out wherever a track is listed.
 */
@Composable
fun ThxBadgePill(modifier: Modifier = Modifier) {
    val thxLabel = stringResource(R.string.badge_thx)
    Surface(
        modifier = modifier.semantics { contentDescription = thxLabel },
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = "THX",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
        )
    }
}

/**
 * The Dolby Atmos mark on a TIDAL track or release that has an Atmos mix,
 * styled like [ThxBadgePill]: the double-D symbol and "ATMOS". With TIDAL
 * Dolby Atmos on (Settings > Audio), these are the tracks that play the Atmos
 * mix instead of stereo.
 */
@Composable
fun DolbyAtmosBadgePill(modifier: Modifier = Modifier) {
    Surface(
        // A trademark, not translated.
        modifier = modifier.semantics { contentDescription = "Dolby Atmos" },
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_dolby_mark),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(width = 12.dp, height = 8.4.dp),
            )
            Text(
                text = "ATMOS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
