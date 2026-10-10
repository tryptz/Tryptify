package tf.monochrome.android.data.downloads

import java.net.URLDecoder

/**
 * Between Android's external-storage documents (the SAF links downloads are
 * saved under) and the file paths the local library knows the same files by.
 *
 * A document of `com.android.externalstorage.documents` is named by a
 * document id of the form `<volume>:<path in volume>`, `primary` being the
 * phone's own storage at /storage/emulated/0 and any other volume (an SD
 * card, `1A2B-3C4D`) at /storage/<volume>. Links from any other provider
 * have no file path and map to null.
 *
 * Kept free of Android types so it can be unit tested.
 */
object SafPaths {
    private const val PREFIX = "content://com.android.externalstorage.documents/"

    /**
     * Where `primary` is for the device owner. A work profile or a second
     * user has its own (`/storage/emulated/10`), so the app passes the real
     * one, Environment.getExternalStorageDirectory(), as `primaryRoot`.
     */
    const val PRIMARY_ROOT = "/storage/emulated/0"

    /**
     * The file path of [uri], a tree (a folder picked in the system picker)
     * or a document under one; null when it is not an external-storage link.
     */
    fun absolutePath(uri: String, primaryRoot: String = PRIMARY_ROOT): String? {
        if (!uri.startsWith(PREFIX)) return null
        // tree/<id>, tree/<id>/document/<id> or document/<id>; ids are
        // percent-encoded, so their own slashes are %2F and never split here.
        val segments = uri.removePrefix(PREFIX).substringBefore('?').split('/')
        val encoded = when {
            segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" -> segments[3]
            segments.size >= 2 && (segments[0] == "tree" || segments[0] == "document") -> segments[1]
            else -> return null
        }
        val documentId = runCatching { URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()
            ?: return null
        return pathOfDocumentId(documentId, primaryRoot)
    }

    /** The file path of external-storage document [documentId] (`primary:Music/a.flac`). */
    fun pathOfDocumentId(documentId: String, primaryRoot: String = PRIMARY_ROOT): String? {
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val inVolume = documentId.substring(colon + 1).trim('/')
        val root = if (volume.equals("primary", ignoreCase = true)) primaryRoot else "/storage/$volume"
        return if (inVolume.isEmpty()) root else "$root/$inVolume"
    }

    /** The external-storage document id of file [path], the reverse of [pathOfDocumentId]. */
    fun documentIdOfPath(path: String, primaryRoot: String = PRIMARY_ROOT): String? {
        val clean = path.trimEnd('/')
        if (clean == primaryRoot) return "primary:"
        if (clean.startsWith("$primaryRoot/")) return "primary:" + clean.removePrefix("$primaryRoot/")
        if (!clean.startsWith("/storage/")) return null
        val rest = clean.removePrefix("/storage/")
        val volume = rest.substringBefore('/')
        if (volume.isEmpty() || volume == "emulated" || volume == "self") return null
        return "$volume:" + rest.substringAfter('/', "")
    }
}
