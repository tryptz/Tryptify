package tf.monochrome.android.ui.components

import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import tf.monochrome.android.R
import tf.monochrome.android.ui.theme.reduceMotion
import tf.monochrome.android.data.local.tags.FileTags
import tf.monochrome.android.data.local.tags.TrackInfoEntryPoint
import tf.monochrome.android.domain.model.Track
import java.util.Locale

/**
 * Track info: the song's file and every tag in it, or, with no file on this
 * device, the details the app has for it. Drawn in the song menu's own glass
 * pane, in place of its actions, with a way back to them.
 *
 * The values can be selected and copied: a tag reader is where an ISRC or a
 * MusicBrainz id gets copied from.
 */
@Composable
fun TrackInfoPane(track: Track, filePath: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    // Loading, then the file or null (none on this device).
    val loaded by produceState<Loaded>(Loaded.Reading, track.id, filePath) {
        val source = EntryPointAccessors.fromApplication(context.applicationContext, TrackInfoEntryPoint::class.java)
            .trackInfoSource()
        value = Loaded.Done(runCatching { source.fileTags(track, filePath) }.getOrNull())
    }

    // animateContentSize: the pane grows from the spinner to the tags rather than jumping.
    // Not with "Disable animations": it takes its new height at once.
    val grow = if (reduceMotion()) Modifier else Modifier.animateContentSize()
    Column(modifier = Modifier.fillMaxWidth().then(grow).padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.action_track_info),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        when (val state = loaded) {
            Loaded.Reading -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp), strokeWidth = 2.dp)
            }
            is Loaded.Done -> SelectionContainer {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    val file = state.file
                    if (file != null) {
                        FileSection(file)
                        Section(stringResource(R.string.track_info_tags))
                        if (file.tags.isEmpty()) {
                            Note(stringResource(R.string.track_info_no_tags))
                        } else {
                            file.tags.forEach { (name, value) -> InfoRow(name, value) }
                        }
                    } else {
                        Note(stringResource(R.string.track_info_no_file))
                        DetailsSection(track)
                    }
                }
            }
        }
    }
}

private sealed interface Loaded {
    data object Reading : Loaded
    data class Done(val file: FileTags?) : Loaded
}

@Composable
private fun FileSection(file: FileTags) {
    val context = LocalContext.current
    Section(stringResource(R.string.track_info_file))
    InfoRow(stringResource(R.string.track_info_format), listOfNotNull(file.format, "Lossless".takeIf { file.lossless == true }).joinToString(" · "))
    file.sampleRateHz?.let { InfoRow(stringResource(R.string.track_info_sample_rate), sampleRate(it)) }
    file.bitsPerSample?.let { InfoRow(stringResource(R.string.track_info_bit_depth), "$it-bit") }
    file.bitrateKbps?.let { InfoRow(stringResource(R.string.track_info_bitrate), "$it kbps") }
    file.channels?.let { InfoRow(stringResource(R.string.track_info_channels), it) }
    file.lengthSeconds?.let { InfoRow(stringResource(R.string.track_info_length), length(it)) }
    InfoRow(stringResource(R.string.track_info_size), Formatter.formatFileSize(context, file.sizeBytes))
    if (file.pictures > 0) InfoRow(stringResource(R.string.track_info_pictures), file.pictures.toString())
    InfoRow(stringResource(R.string.track_info_location), file.path)
}

/** What the app knows of a song it has no file of: what it streams it as. */
@Composable
private fun DetailsSection(track: Track) {
    Section(stringResource(R.string.track_info_details))
    InfoRow(stringResource(R.string.track_info_title), track.title)
    track.displayArtist.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.track_info_artist), it) }
    track.album?.title?.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.track_info_album), it) }
    track.version?.takeIf { it.isNotBlank() }?.let { InfoRow(stringResource(R.string.track_info_version), it) }
    track.trackNumber?.let { InfoRow(stringResource(R.string.track_info_track), it.toString()) }
    track.volumeNumber?.let { InfoRow(stringResource(R.string.track_info_disc), it.toString()) }
    if (track.duration > 0) InfoRow(stringResource(R.string.track_info_length), track.formattedDuration)
    track.qualityBadge?.let { InfoRow(stringResource(R.string.track_info_quality), it) }
    track.channelBadge?.let { InfoRow(stringResource(R.string.track_info_channels), it) }
    track.appleId?.let { InfoRow("Apple Music ID", it.toString()) }
    track.deezerId?.let { InfoRow("Deezer ID", it.toString()) }
    if (track.appleId == null && track.deezerId == null && track.id > 0) {
        InfoRow(stringResource(R.string.track_info_catalog_id), track.id.toString())
    }
}

@Composable
private fun Section(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

/** One field: its name over its value, so a long value (a path, lyrics) wraps under it. */
@Composable
private fun InfoRow(name: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            // Lyrics can be a whole song.
            maxLines = 12,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun sampleRate(hz: Int): String =
    if (hz % 1000 == 0) "${hz / 1000} kHz"
    else String.format(Locale.ROOT, "%.1f kHz", hz / 1000.0)

internal fun length(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.ROOT, "%d:%02d", m, s)
}
