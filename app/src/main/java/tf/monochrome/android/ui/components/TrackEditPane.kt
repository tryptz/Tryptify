// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.components

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.data.local.db.LocalTrackEditEntity
import tf.monochrome.android.data.local.db.LocalTrackEntity
import tf.monochrome.android.data.local.db.TypedTags
import tf.monochrome.android.data.local.db.trackEditFrom
import tf.monochrome.android.data.local.db.withEdit
import tf.monochrome.android.data.local.scanner.TrackEditEntryPoint
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * Edit info: a local song's title, artist, album artist, album, genre, year
 * and numbers, for a file whose tags are wrong or missing. Drawn in the song
 * menu's own glass pane, as Track info is, with a way back to the actions.
 *
 * The edit is kept by the app ([LocalTrackEditEntity]) and laid over the
 * file's tags at every scan; the file is not written. Each field says what
 * the file has where it differs. Saving shows the edit everywhere the
 * library does, and Last.fm gets it from the song's next play.
 */
@Composable
fun TrackEditPane(track: Track, filePath: String, onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scanner = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, TrackEditEntryPoint::class.java).mediaScanner()
    }
    val loaded by produceState<EditLoad>(EditLoad.Reading, filePath) {
        val file = runCatching { scanner.fileTagsOf(filePath) }.getOrNull()
        value = if (file == null) EditLoad.Missing
        else EditLoad.Done(file, runCatching { scanner.trackEditOf(filePath) }.getOrNull())
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
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
                    text = stringResource(R.string.action_edit_info),
                    style = MaterialTheme.typography.titleMedium,
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
            EditLoad.Reading -> Row(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(modifier = Modifier.width(24.dp), strokeWidth = 2.dp)
            }
            EditLoad.Missing -> Text(
                text = stringResource(R.string.edit_info_not_in_library),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
            is EditLoad.Done -> Editor(
                file = state.file,
                edit = state.edit,
                onSave = { typed, reset ->
                    val edit = if (reset) null else trackEditFrom(filePath, state.file, typed)
                    try {
                        scanner.saveTrackEdit(filePath, edit)
                        Toast.makeText(context, R.string.edit_info_saved, Toast.LENGTH_SHORT).show()
                        onSaved()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.edit_info_failed, Toast.LENGTH_LONG).show()
                    }
                },
            )
        }
    }
}

private sealed interface EditLoad {
    data object Reading : EditLoad
    data object Missing : EditLoad
    data class Done(val file: LocalTrackEntity, val edit: LocalTrackEditEntity?) : EditLoad
}

@Composable
private fun Editor(
    file: LocalTrackEntity,
    edit: LocalTrackEditEntity?,
    onSave: suspend (TypedTags, reset: Boolean) -> Unit,
) {
    val fromFile = remember(file) { TypedTags.of(file) }
    var typed by remember(file, edit) { mutableStateOf(TypedTags.of(file.withEdit(edit))) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save(reset: Boolean) {
        if (saving) return
        saving = true
        // Waited for here, in the pane, which closes once it is done.
        scope.launch {
            try {
                onSave(typed, reset)
            } finally {
                saving = false
            }
        }
    }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.edit_info_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Field(stringResource(R.string.track_info_title), typed.title, fromFile.title) { typed = typed.copy(title = it) }
        Field(stringResource(R.string.track_info_artist), typed.artist, fromFile.artist) { typed = typed.copy(artist = it) }
        Field(
            stringResource(R.string.edit_info_album_artist),
            typed.albumArtist,
            fromFile.albumArtist,
            help = stringResource(R.string.edit_info_album_artist_help),
        ) { typed = typed.copy(albumArtist = it) }
        Field(stringResource(R.string.track_info_album), typed.album, fromFile.album) { typed = typed.copy(album = it) }
        Field(stringResource(R.string.edit_info_genre), typed.genre, fromFile.genre) { typed = typed.copy(genre = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field(stringResource(R.string.sort_year), typed.year, fromFile.year, number = true, modifier = Modifier.weight(1.4f)) {
                typed = typed.copy(year = it)
            }
            Field(stringResource(R.string.track_info_track), typed.trackNumber, fromFile.trackNumber, number = true, modifier = Modifier.weight(1f)) {
                typed = typed.copy(trackNumber = it)
            }
            Field(stringResource(R.string.track_info_disc), typed.discNumber, fromFile.discNumber, number = true, modifier = Modifier.weight(1f)) {
                typed = typed.copy(discNumber = it)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only when there is an edit to drop.
            if (edit != null) {
                TextButton(onClick = { save(reset = true) }, enabled = !saving) {
                    Text(stringResource(R.string.edit_info_reset))
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { save(reset = false) }, enabled = !saving, shape = MonoDimens.shapeSm) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text(stringResource(R.string.action_save))
                }
            }
        }
    }
}

/**
 * One tag. Under it, what the file says when the field no longer does, so a
 * change can be seen and undone by hand; [help] otherwise.
 */
@Composable
private fun Field(
    label: String,
    value: String,
    fileValue: String,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    help: String? = null,
    onChange: (String) -> Unit,
) {
    val changed = value.trim() != fileValue.trim()
    val under = when {
        changed && fileValue.isBlank() -> stringResource(R.string.edit_info_not_in_file)
        changed -> stringResource(R.string.edit_info_in_file, fileValue)
        else -> help
    }
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    OutlinedTextField(
        value = value,
        onValueChange = { typed -> onChange(if (number) typed.filter(Char::isDigit).take(4) else typed) },
        label = { Text(label) },
        supportingText = under?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (number) KeyboardType.Number else KeyboardType.Text,
            imeAction = ImeAction.Next,
        ),
        modifier = modifier.fillMaxWidth(),
        shape = MonoDimens.shapeSm,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
