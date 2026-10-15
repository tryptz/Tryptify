package tf.monochrome.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tf.monochrome.android.R
import tf.monochrome.android.data.api.SpotifyEmbed
import tf.monochrome.android.data.api.SpotifyImportEntryPoint
import tf.monochrome.android.data.api.SpotifyPlaylistUnavailableException
import tf.monochrome.android.ui.theme.MonoDimens

/** What the link box has found so far. */
internal sealed interface LinkPreview {
    /** Nothing typed. */
    data object Empty : LinkPreview

    /** Text that is not a Spotify playlist link. */
    data object NotALink : LinkPreview

    data object Loading : LinkPreview

    data class Found(val name: String, val songs: Int, val total: Int?, val coverUrl: String?) : LinkPreview

    /** Spotify has no such public playlist: private, deleted, or mistyped. */
    data object NotFound : LinkPreview

    /** Spotify did not answer; the import itself may still get through. */
    data object Unreachable : LinkPreview
}

/**
 * Looks the pasted playlist up as soon as the link is whole, so the dialog can
 * show its cover, name and length before anything is imported: proof the link
 * works, and the name to give the new playlist. A short wait first, so typing
 * a link out by hand is not a lookup per keystroke.
 */
@Composable
internal fun rememberSpotifyLinkPreview(link: String?): LinkPreview {
    val context = LocalContext.current
    val reader = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, SpotifyImportEntryPoint::class.java)
            .spotifyPublicPlaylistReader()
    }
    val preview by produceState<LinkPreview>(LinkPreview.Empty, link) {
        val text = link?.trim().orEmpty()
        if (text.isEmpty()) {
            value = LinkPreview.Empty
            return@produceState
        }
        if (!SpotifyEmbed.looksImportable(text)) {
            value = LinkPreview.NotALink
            return@produceState
        }
        value = LinkPreview.Loading
        delay(LOOKUP_DELAY_MS)
        value = try {
            val id = SpotifyEmbed.playlistId(text)
                ?: SpotifyEmbed.shortLink(text)?.let { reader.resolveShortLink(it) }
            if (id == null) {
                LinkPreview.NotFound
            } else {
                val playlist = reader.read(id)
                LinkPreview.Found(playlist.name, playlist.tracks.size, playlist.totalTracks, playlist.coverUrl)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpotifyPlaylistUnavailableException) {
            LinkPreview.NotFound
        } catch (e: Exception) {
            LinkPreview.Unreachable
        }
    }
    return preview
}

private const val LOOKUP_DELAY_MS = 350L

/** The link box, with a Paste button: the clipboard is read only when asked. */
@Composable
internal fun SpotifyLinkField(
    link: String,
    onLinkChange: (String) -> Unit,
    field: @Composable (trailing: @Composable () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    field {
        IconButton(onClick = {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as? android.content.ClipboardManager
            val pasted = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(context)?.toString()
            if (!pasted.isNullOrBlank()) onLinkChange(pasted.trim())
        }) {
            Icon(
                imageVector = Icons.Default.ContentPaste,
                contentDescription = stringResource(R.string.action_paste),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Under the link box: what the link is. The playlist's cover, name and length
 * once found, with a note when Spotify shares only part of it; otherwise what
 * to paste, or what went wrong, in a line.
 */
@Composable
internal fun LinkPreviewCard(preview: LinkPreview) {
    when (preview) {
        LinkPreview.Empty -> Note(stringResource(R.string.import_spotify_link_detail))
        LinkPreview.NotALink -> Note(stringResource(R.string.import_unrecognized_url), error = true)
        LinkPreview.NotFound -> Note(stringResource(R.string.import_spotify_not_found), error = true)
        LinkPreview.Unreachable -> Note(stringResource(R.string.import_spotify_unreachable), error = true)
        LinkPreview.Loading -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Note(stringResource(R.string.import_spotify_looking_up))
        }
        is LinkPreview.Found -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.45f), MonoDimens.shapeSm)
                .border(
                    width = MonoDimens.glassBorderWidth,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                    shape = MonoDimens.shapeSm,
                )
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverImage(url = preview.coverUrl, contentDescription = preview.name, size = 56.dp, cornerRadius = 8.dp)
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = preview.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val count = preview.total ?: preview.songs
                Text(
                    text = pluralStringResource(R.plurals.import_spotify_preview_songs, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.total != null && preview.total > preview.songs) {
                    Text(
                        text = stringResource(R.string.import_spotify_preview_partial, preview.songs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun Note(text: String, error: Boolean = false) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
