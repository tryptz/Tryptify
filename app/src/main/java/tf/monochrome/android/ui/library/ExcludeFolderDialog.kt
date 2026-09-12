package tf.monochrome.android.ui.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * A folder about to be dropped from the library — its name, and where it is.
 *
 * [trackCount] is null where the caller has no count to hand (the root folder
 * list stores names and paths only).
 */
internal data class FolderToExclude(
    val path: String,
    val displayName: String,
    val trackCount: Int? = null,
)

/**
 * Confirms removing a folder from the library.
 *
 * A long press is easy to do by accident, and the action behind this one takes
 * away every track under the folder — so it asks, and it says how many.
 *
 * It is also worth saying plainly that the files survive: "remove" next to a
 * folder full of music reads as a delete, and someone who thinks they might
 * have just erased an album will not find out otherwise from the result.
 */
@Composable
internal fun ExcludeFolderDialog(
    folder: FolderToExclude,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${folder.displayName}?") },
        text = {
            Text(
                buildString {
                    append(
                        when (folder.trackCount) {
                            null -> "This folder and its music leave your library"
                            1 -> "1 track leaves your library"
                            else -> "${folder.trackCount} tracks leave your library"
                        }
                    )
                    append(", and scans will skip it from now on.\n\n")
                    append("Nothing is deleted from your phone — the files stay in ")
                    append(folder.path)
                    append(".")
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Remove") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
