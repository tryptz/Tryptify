package tf.monochrome.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tf.monochrome.android.domain.model.Track
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * A song's menu (a long-press or its ⋮): what to do with it, and its Track
 * info.
 *
 * Glass, so not a `ModalBottomSheet`: that opens its own window, and glass
 * cannot blur a page in another window (docs/ui-invariants.md, Glass). It is
 * drawn in the app's [GlassOverlay] slot instead, at the bottom where the
 * sheet was, over the page it blurs.
 *
 * Track info turns the same pane into the song's tags ([TrackInfoPane]); Back
 * returns to the actions. [filePath] is the song's file where the screen knows
 * it (the library's own tracks); otherwise its download or a cached stream is
 * looked for, and without either it shows what the app knows of the song.
 */
@Composable
fun TrackContextMenu(
    track: Track,
    isLiked: Boolean,
    onDismiss: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    // The destructive entry isn't always a playlist removal — on Discover it
    // is "take this off my feed" — so the caller names it.
    removeLabel: String = stringResource(R.string.action_remove_from_playlist),
    onDownloadTrack: (() -> Unit)? = null,
    onShareFile: (() -> Unit)? = null,
    onGoToAlbum: (() -> Unit)? = null,
    onGoToArtist: (() -> Unit)? = null,
    filePath: String? = null,
) {
    // Outside the overlay's content, which is composed in the overlay host:
    // state there would not survive the caller's recompositions.
    var showingInfo by remember(track.id) { mutableStateOf(false) }

    GlassOverlay(onDismiss = onDismiss, alignment = Alignment.BottomCenter) {
        // "Disable animations": the pages swap at once, at their own heights.
        val still = reduceMotion()
        // Back from Track info returns to the actions rather than closing.
        BackHandler(enabled = showingInfo) { showingInfo = false }
        // Track info slides in from the end and the actions back from the
        // start, as a page pushed and popped; the pane's height follows.
        AnimatedContent(
            targetState = showingInfo,
            transitionSpec = {
                if (still) return@AnimatedContent (EnterTransition.None togetherWith ExitTransition.None).using(null)
                val towards = if (targetState) SlideDirection.Start else SlideDirection.End
                (slideIntoContainer(towards, tween(PAGE_MS)) + fadeIn(tween(PAGE_MS)))
                    .togetherWith(slideOutOfContainer(towards, tween(PAGE_MS)) + fadeOut(tween(PAGE_MS / 2)))
                    .using(SizeTransform(clip = true) { _, _ -> tween(PAGE_MS) })
            },
            label = "trackMenuPage",
        ) { info ->
            if (info) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    TrackInfoPane(track = track, filePath = filePath, onBack = { showingInfo = false })
                }
            } else {
                Column(
                    modifier = Modifier
                        // Scrollable so the bottom actions (Go to album/artist) stay
                        // reachable when the sheet is taller than a landscape window.
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    // Track header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CoverImage(
                            url = track.coverUrl,
                            contentDescription = track.title,
                            size = 48.dp,
                            cornerRadius = 6.dp
                        )
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                        ) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.displayArtist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    // Actions
                    ContextMenuItem(
                        icon = Icons.Default.SkipNext,
                        label = stringResource(R.string.action_play_next),
                        onClick = { onPlayNext(); onDismiss() }
                    )
                    ContextMenuItem(
                        icon = Icons.Default.QueueMusic,
                        label = stringResource(R.string.action_add_to_queue),
                        onClick = { onAddToQueue(); onDismiss() }
                    )
                    ContextMenuItem(
                        icon = Icons.Default.Favorite,
                        label = if (isLiked) stringResource(R.string.action_unlike) else stringResource(R.string.action_like),
                        tint = if (isLiked) MaterialTheme.colorScheme.primary else null,
                        onClick = { onToggleLike(); onDismiss() }
                    )
                    ContextMenuItem(
                        icon = Icons.Default.PlaylistAdd,
                        label = stringResource(R.string.action_add_to_playlist),
                        onClick = { onAddToPlaylist(); onDismiss() }
                    )

                    if (onRemoveFromPlaylist != null) {
                        ContextMenuItem(
                            icon = Icons.Default.RemoveCircleOutline,
                            label = removeLabel,
                            tint = MaterialTheme.colorScheme.error,
                            onClick = { onRemoveFromPlaylist(); onDismiss() }
                        )
                    }

                    if (onDownloadTrack != null) {
                        ContextMenuItem(
                            icon = Icons.Default.Download,
                            label = stringResource(R.string.action_download),
                            onClick = { onDownloadTrack(); onDismiss() }
                        )
                    }

                    // Share the actual audio file when the caller provides a handler.
                    // The handler resolves to a downloaded copy or a Qobuz cache hit
                    // and dispatches Intent.ACTION_SEND with the file URI; if neither
                    // exists the action is a no-op (logged in TrackShareHelper).
                    if (onShareFile != null) {
                        ContextMenuItem(
                            icon = Icons.Default.Share,
                            label = stringResource(R.string.action_share_file),
                            onClick = { onShareFile(); onDismiss() }
                        )
                    }

                    if (onGoToAlbum != null && track.album != null) {
                        ContextMenuItem(
                            icon = Icons.Default.Album,
                            label = stringResource(R.string.action_go_to_album),
                            onClick = { onGoToAlbum(); onDismiss() }
                        )
                    }
                    if (onGoToArtist != null && track.artist != null) {
                        ContextMenuItem(
                            icon = Icons.Default.Person,
                            label = stringResource(R.string.action_go_to_artist),
                            onClick = { onGoToArtist(); onDismiss() }
                        )
                    }
                    // Opens in this pane; the menu stays open behind it.
                    ContextMenuItem(
                        icon = Icons.Default.Info,
                        label = stringResource(R.string.action_track_info),
                        onClick = { showingInfo = true }
                    )
                }
            }
        }
    }
}

/** How long the turn between the actions and Track info takes. */
private const val PAGE_MS = 280

@Composable
private fun ContextMenuItem(
    icon: ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 16.dp),
            color = tint ?: MaterialTheme.colorScheme.onSurface
        )
    }
}
