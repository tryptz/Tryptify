package tf.monochrome.android.util

import android.net.Uri
import android.provider.DocumentsContract

/**
 * Resolve a SAF tree URI (from `OpenDocumentTree`) to a best-guess filesystem path.
 * Handles "primary" (emulated) storage plus SD-card volume IDs. Returns null if the
 * URI isn't a recognized tree document (e.g. a cloud DocumentsProvider) — callers
 * should fall back gracefully.
 */
fun safTreeUriToPath(uri: Uri): String? = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val parts = docId.split(":", limit = 2)
    if (parts.size != 2) return@runCatching null
    val (type, path) = parts
    val base = if (type.equals("primary", ignoreCase = true)) {
        "/storage/emulated/0"
    } else {
        "/storage/$type"
    }
    if (path.isBlank()) base else "$base/$path".trimEnd('/')
}.getOrNull()
