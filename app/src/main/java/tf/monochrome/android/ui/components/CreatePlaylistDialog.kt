package tf.monochrome.android.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onSubmit: (name: String, description: String) -> Unit,
    onImportCsv: ((uri: Uri, strictMatch: Boolean, name: String, description: String) -> Unit)? = null,
    initialName: String = "",
    initialDescription: String = "",
    title: String = "Create Playlist",
    confirmLabel: String = "Create",
) {
    // Seed from the initial values so the "Edit playlist" reuse of this dialog
    // shows the existing name/description instead of blanks (submitting blank
    // used to wipe them).
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var isUploadMode by remember { mutableStateOf(true) }
    var selectedFormat by remember { mutableStateOf("CSV") }
    var selectedSource by remember { mutableStateOf("Spotify") }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var strictAlbumMatch by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            selectedUri = uri
        }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .wrapContentHeight()
                .padding(vertical = 24.dp)
                .liquidGlass(shape = RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = androidx.compose.ui.graphics.Color.Transparent
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("Playlist name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                if (onImportCsv != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        TextButton(
                            onClick = { isUploadMode = true },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (isUploadMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("↑ Upload")
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        TextButton(
                            onClick = { isUploadMode = false },
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (!isUploadMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Text("or URL")
                        }
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    placeholder = { Text("Description (optional)") },
                    modifier = Modifier.fillMaxWidth().height(100.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f),
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.3f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent
                    )
                )

                if (onImportCsv != null && isUploadMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("CSV", "JSPF", "XSPF", "XML").forEach { format ->
                            Text(
                                text = format,
                                modifier = Modifier
                                    .clickable { selectedFormat = format }
                                    .background(
                                        if (selectedFormat == format) MaterialTheme.colorScheme.primary.copy(alpha=0.2f) else Color.Transparent,
                                        RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = if (selectedFormat == format) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }

                    if (selectedFormat == "CSV") {
                        Text(
                            text = "Import from CSV",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val sources = listOf("Spotify", "Apple Music", "YouTube Music")
                            sources.forEach { source ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(if (source == "Spotify") CircleShape else RoundedCornerShape(12.dp))
                                        .background(if (selectedSource == source) Color(0xFFFF8A80) else MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { selectedSource = source },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = source.replace(" ", "\n"),
                                        textAlign = TextAlign.Center,
                                        color = if (selectedSource == source) Color.Black else MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                        }

                        if (selectedSource == "Spotify") {
                            Text(
                                text = "Connect Spotify in Settings → System to import playlists directly, or upload an Exportify .csv here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "*/*")) },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(if (selectedUri != null) "File selected" else "Choisir un fichier")
                        }

                        Text(
                            text = "Make sure its headers are in English.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Strict Album Matching", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Album name should strictly match CSV metadata. Disable for better discovery",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = strictAlbumMatch,
                                onCheckedChange = { strictAlbumMatch = it }
                            )
                        }
                    } else {
                        Text("Format $selectedFormat is not supported yet.", color = MaterialTheme.colorScheme.error)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (onImportCsv != null && isUploadMode && selectedUri != null) {
                                onImportCsv(selectedUri!!, strictAlbumMatch, name, description)
                            } else {
                                onSubmit(name, description)
                            }
                            onDismiss()
                        },
                        enabled = name.isNotBlank() && (selectedUri != null || !isUploadMode || onImportCsv == null)
                    ) {
                        Text(if (onImportCsv != null && isUploadMode && selectedUri != null) "Import" else confirmLabel)
                    }
                }
            }
        }
    }
}
