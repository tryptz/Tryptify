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
    documentIdToPath(DocumentsContract.getTreeDocumentId(uri))
}.getOrNull()

/**
 * The filesystem path of one document inside a picked tree (a linked
 * StepMania pack's music or picture), on the same terms as [safTreeUriToPath].
 * Null for anything that is not an external-storage document.
 */
fun safDocumentUriToPath(uri: Uri): String? = runCatching {
    // Throws for a URI that names no document; caught below.
    documentIdToPath(DocumentsContract.getDocumentId(uri))
}.getOrNull()

/**
 * An external-storage document id ("primary:Music/a.flac", "1A2B-3C4D:Songs")
 * as a path ("/storage/emulated/0/Music/a.flac", "/storage/1A2B-3C4D/Songs").
 * The Downloads provider's "raw:" ids carry their path; its numbered ids
 * ("msf:12") and the media provider's ("audio:12") name no folder, and have
 * none. Pure, for tests.
 */
internal fun documentIdToPath(docId: String): String? {
    val parts = docId.split(":", limit = 2)
    if (parts.size != 2) return null
    val (type, path) = parts
    if (type.isBlank()) return null
    if (type.equals("raw", ignoreCase = true)) return path.takeIf { it.startsWith("/") }?.trimEnd('/')
    if (type.lowercase() in NO_FOLDER_IDS) return null
    val base = when {
        type.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
        type.equals("home", ignoreCase = true) -> "/storage/emulated/0/Documents"
        else -> "/storage/$type"
    }
    return if (path.isBlank()) base else "$base/$path".trimEnd('/')
}

private val NO_FOLDER_IDS = setOf("msf", "msd", "downloads", "audio", "image", "video", "document")
