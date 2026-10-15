package tf.monochrome.android.ui.components

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tf.monochrome.android.ui.theme.MonoDimens
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

/**
 * The exports this dialog can read, and how to get one out of each service.
 *
 * These used to be three decorative squares: picking one changed nothing, while
 * the parser accepted Spotify's export and nothing else. They now say what they
 * are — the instructions for getting a file this app can read — and the parser
 * behind them reads all three.
 */
private enum class ImportSource(val label: String, @androidx.annotation.StringRes val instructions: Int) {
    SPOTIFY(
        "Spotify",
        R.string.import_spotify_instructions,
    ),
    APPLE_MUSIC(
        "Apple Music",
        R.string.import_apple_music_instructions,
    ),
    YOUTUBE_MUSIC(
        "YouTube Music",
        R.string.import_youtube_music_instructions,
    ),
}

private val IMPORT_FORMATS = listOf("CSV", "JSPF", "XSPF", "XML")

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onSubmit: (name: String, description: String) -> Unit,
    onImportCsv: ((uri: Uri, strictMatch: Boolean, name: String, description: String) -> Unit)? = null,
    /**
     * Import from a pasted Spotify playlist link. When given, Spotify's tab is
     * a link box rather than instructions for a third-party export: the app
     * reads the playlist itself (SpotifyPublicPlaylistReader). A blank name
     * takes the playlist's own.
     */
    onImportLink: ((url: String, strictMatch: Boolean, name: String, description: String) -> Unit)? = null,
    /**
     * Open Exportify inside the app, for what a link cannot bring: private
     * playlists, Liked Songs, and playlists past 100 songs.
     */
    onOpenExportify: ((strictMatch: Boolean) -> Unit)? = null,
    initialName: String = "",
    initialDescription: String = "",
    title: String = stringResource(R.string.create_playlist_title),
    confirmLabel: String = stringResource(R.string.action_create),
) {
    // Seed from the initial values so the "Edit playlist" reuse of this dialog
    // shows the existing name/description instead of blanks (submitting blank
    // used to wipe them).
    // rememberSaveable so typed input and the picked file survive the process
    // death that the SAF file picker can trigger (the picker launches another
    // activity, and low-memory devices reclaim this one behind it).
    var name by rememberSaveable { mutableStateOf(initialName) }
    var description by rememberSaveable { mutableStateOf(initialDescription) }
    var selectedFormat by rememberSaveable { mutableStateOf("CSV") }
    var selectedSource by rememberSaveable { mutableStateOf(ImportSource.SPOTIFY.name) }
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }
    var strictAlbumMatch by rememberSaveable { mutableStateOf(false) }
    var spotifyLink by rememberSaveable { mutableStateOf("") }
    // Import from a link or from a file; the link first, when one can be imported.
    var importTab by rememberSaveable { mutableStateOf(if (onImportLink != null) TAB_LINK else TAB_FILE) }
    val linkTab = onImportLink != null && importTab == TAB_LINK
    // Outside the overlay's content, which is composed in the overlay host:
    // state there would not survive the caller's recompositions.
    val preview = rememberSpotifyLinkPreview(spotifyLink.takeIf { linkTab })
    // The playlist's own name, written into an empty name box and kept in
    // step while it is still the one written there: typed over, it is left.
    var autoName by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(preview) {
        if (preview is LinkPreview.Found && (name.isBlank() || name == autoName)) {
            name = preview.name
            autoName = preview.name
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            // Cancelling the picker delivers a null uri; keep the previously
            // selected file instead of clearing it.
            if (uri != null) selectedUri = uri
        },
    )

    // Real glass, which means this cannot be a Dialog.
    //
    // A Dialog opens its own window, and a haze effect cannot sample a layer
    // belonging to another one -- the library it wants to blur was captured
    // into the window underneath, so the pane came out as a flat tint with the
    // playlist list legible straight through its text. Drawn instead in the nav
    // host's overlay slot, as a sibling of the app's haze source, it blurs the
    // library for real. The scrim and Back come with that slot rather than with
    // Dialog.
    GlassOverlay(onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            DialogField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.playlist_name_hint),
                singleLine = true,
            )

            DialogField(
                value = description,
                onValueChange = { description = it },
                placeholder = stringResource(R.string.playlist_description_hint),
                singleLine = false,
                modifier = Modifier.heightIn(min = 88.dp),
            )

            if (onImportCsv != null || onImportLink != null) {
                ImportPanel(
                    linkAvailable = onImportLink != null,
                    fileAvailable = onImportCsv != null,
                    importTab = importTab,
                    onImportTabChange = { importTab = it },
                    preview = preview,
                    onOpenExportify = onOpenExportify?.let { open -> { open(strictAlbumMatch); onDismiss() } },
                    selectedFormat = selectedFormat,
                    onFormatChange = { selectedFormat = it },
                    selectedSource = ImportSource.entries
                        .firstOrNull { it.name == selectedSource } ?: ImportSource.SPOTIFY,
                    onSourceChange = { selectedSource = it.name },
                    selectedUri = selectedUri,
                    onPickFile = {
                        // Apple Music writes a .txt and several exporters
                        // send octet-stream, so the picker cannot filter on
                        // the CSV types alone without hiding the files it is
                        // being asked to read.
                        filePickerLauncher.launch(
                            arrayOf(
                                "text/csv",
                                "text/comma-separated-values",
                                "text/tab-separated-values",
                                "text/plain",
                                "application/csv",
                                "application/octet-stream",
                            ),
                        )
                    },
                    strictAlbumMatch = strictAlbumMatch,
                    onStrictChange = { strictAlbumMatch = it },
                    spotifyLink = spotifyLink,
                    onSpotifyLinkChange = { spotifyLink = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                Spacer(Modifier.width(8.dp))
                // A link on the link tab imports from the link; it needs no
                // name, since the playlist has its own. Not one Spotify says
                // does not exist; one it did not answer for may still work.
                val linkReady = linkTab &&
                    tf.monochrome.android.data.api.SpotifyEmbed.looksImportable(spotifyLink) &&
                    preview != LinkPreview.NotFound
                // Only the CSV format actually imports; a file picked while
                // an unsupported format is selected must not silently run
                // the CSV importer (it created garbage playlists).
                val canImport = !linkTab && onImportCsv != null &&
                    selectedUri != null && selectedFormat == "CSV"
                Button(
                    onClick = {
                        when {
                            linkReady -> onImportLink!!(spotifyLink.trim(), strictAlbumMatch, name, description)
                            canImport -> onImportCsv!!(selectedUri!!, strictAlbumMatch, name, description)
                            else -> onSubmit(name, description)
                        }
                        onDismiss()
                    },
                    // A non-blank name is enough: onClick imports when a CSV
                    // file is picked, otherwise creates a plain playlist. The
                    // old extra conditions left the button disabled for a
                    // plainly-named playlist in the default (upload) mode.
                    // A link brings its own name.
                    enabled = name.isNotBlank() || linkReady,
                ) {
                    Text(if (linkReady || canImport) stringResource(R.string.action_import) else confirmLabel)
                }
            }
        }
    }
}

/**
 * A grouping panel *inside* the glass pane.
 *
 * Deliberately not a second sheet of glass. The pane it sits on is already
 * frosting the library, and stacking a blur on a blur reads as neither -- so
 * this is a thin tint and a rim, enough to group the import controls while
 * leaving the pane's own material visible through it.
 */
@Composable
private fun DialogPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.30f),
                MonoDimens.shapeMd,
            )
            .border(
                width = MonoDimens.glassBorderWidth,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                shape = MonoDimens.shapeMd,
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

@Composable
private fun ImportPanel(
    linkAvailable: Boolean,
    fileAvailable: Boolean,
    importTab: String,
    onImportTabChange: (String) -> Unit,
    preview: LinkPreview,
    onOpenExportify: (() -> Unit)?,
    selectedFormat: String,
    onFormatChange: (String) -> Unit,
    selectedSource: ImportSource,
    onSourceChange: (ImportSource) -> Unit,
    selectedUri: Uri?,
    onPickFile: () -> Unit,
    strictAlbumMatch: Boolean,
    onStrictChange: (Boolean) -> Unit,
    spotifyLink: String,
    onSpotifyLinkChange: (String) -> Unit,
) {
    DialogPanel {
        Text(
            text = stringResource(R.string.import_tracks_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        // From a Spotify link, or from an exported file. The link is the way
        // in: it needs nothing but the link. The file is for what a link
        // cannot do: a long playlist past the 100 songs Spotify shares
        // without signing in, or Apple Music and YouTube Music.
        if (linkAvailable && fileAvailable) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ChoiceChip(
                    label = stringResource(R.string.import_tab_link),
                    selected = importTab == TAB_LINK,
                    onClick = { onImportTabChange(TAB_LINK) },
                    modifier = Modifier.weight(1f),
                )
                ChoiceChip(
                    label = stringResource(R.string.import_tab_file),
                    selected = importTab == TAB_FILE,
                    onClick = { onImportTabChange(TAB_FILE) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (linkAvailable && (importTab == TAB_LINK || !fileAvailable)) {
            SpotifyLinkField(link = spotifyLink, onLinkChange = onSpotifyLinkChange) { trailing ->
                DialogField(
                    value = spotifyLink,
                    onValueChange = onSpotifyLinkChange,
                    placeholder = stringResource(R.string.import_spotify_link_hint),
                    singleLine = true,
                    trailingIcon = trailing,
                )
            }
            LinkPreviewCard(preview)
            if (onOpenExportify != null) {
                // What a link cannot bring, Exportify can: it signs in to
                // Spotify for any account, inside the app.
                TextButton(onClick = onOpenExportify) {
                    Text(stringResource(R.string.import_open_exportify))
                }
            }
        } else {
            // Where the export comes from. Equal shapes: one of these was a
            // circle and the other two were rounded rectangles, which read as
            // a rendering fault rather than a selection, and the selected fill
            // was a hardcoded salmon that ignored the theme entirely.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ImportSource.entries.forEach { source ->
                    ChoiceChip(
                        label = source.label,
                        selected = selectedSource == source,
                        onClick = { onSourceChange(source) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            FileImport(selectedFormat, onFormatChange, selectedSource, selectedUri, onPickFile)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.strict_album_matching),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.strict_album_matching_detail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = strictAlbumMatch, onCheckedChange = onStrictChange)
        }
    }
}

private const val TAB_LINK = "link"
private const val TAB_FILE = "file"

/** An exported file: its format, where to get one, and the picker. */
@Composable
private fun FileImport(
    selectedFormat: String,
    onFormatChange: (String) -> Unit,
    selectedSource: ImportSource,
    selectedUri: Uri?,
    onPickFile: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IMPORT_FORMATS.forEach { format ->
                ChoiceChip(
                    label = format,
                    selected = selectedFormat == format,
                    onClick = { onFormatChange(format) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (selectedFormat != "CSV") {
            Text(
                text = stringResource(R.string.import_format_unsupported, selectedFormat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        Text(
            text = stringResource(selectedSource.instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val pickedName = rememberFileName(selectedUri)
        Button(
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            shape = MonoDimens.shapeSm,
        ) {
            Text(
                text = pickedName ?: stringResource(R.string.action_choose_file),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (pickedName != null) {
            Text(
                text = stringResource(R.string.import_any_columns),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One selectable chip. Same shape for every option, coloured from the theme. */
@Composable
private fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .background(container, MonoDimens.shapeSm)
            .border(
                width = MonoDimens.glassBorderWidth,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                } else {
                    Color.Transparent
                },
                shape = MonoDimens.shapeSm,
            )
            .bounceClick(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Text field with a filled, opaque container — the dialog has no blur behind it. */
@Composable
private fun DialogField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean,
    modifier: Modifier = Modifier,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val container = MaterialTheme.colorScheme.surfaceContainerHighest
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = {
            Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = trailingIcon,
        singleLine = singleLine,
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

/**
 * The picked file's display name.
 *
 * "File selected" told you a file was selected and not which one, which is the
 * thing worth knowing when the import then fails. Queried off the main thread —
 * a `ContentResolver` query is disk work, and this one runs inside a dialog
 * that is already animating in.
 */
@Composable
private fun rememberFileName(uri: Uri?): String? {
    val context = LocalContext.current
    val resolver = remember(context) { context.contentResolver }
    val name by produceState<String?>(initialValue = null, uri, resolver) {
        val target = uri
        value = if (target == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (target.scheme == "content") {
                        resolver.query(target, null, null, null, null)?.use { cursor ->
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && index != -1) cursor.getString(index) else null
                        }
                    } else {
                        null
                    }
                }.getOrNull() ?: target.path?.substringAfterLast('/')
            }
        }
    }
    return name
}
