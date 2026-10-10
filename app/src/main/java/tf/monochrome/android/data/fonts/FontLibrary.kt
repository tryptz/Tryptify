package tf.monochrome.android.data.fonts

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tf.monochrome.android.ui.theme.FontFileInspector
import tf.monochrome.android.ui.theme.FontFormat
import tf.monochrome.android.ui.theme.FontInspection
import tf.monochrome.android.ui.theme.FontRejection
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A font the listener imported, as the library lists it. */
data class ImportedFont(
    val file: File,
    /** The name the font gives itself, or the file's name when it gives none. */
    val name: String,
    /** False for a file that cannot be read as a font — listed so it can be deleted. */
    val usable: Boolean,
) {
    /** The id stored as the active font: imports are addressed by path. */
    val id: String get() = file.absolutePath
}

/** Why a picked file did not join the library. */
enum class FontSkipReason { NOT_A_FONT, WEB_FONT, TYPE1, EMPTY, TOO_LARGE, ALREADY_THERE, UNREADABLE }

data class SkippedFont(
    /** The file as the picker named it. */
    val file: String,
    val reason: FontSkipReason,
    /** For [FontSkipReason.ALREADY_THERE]: the library entry it duplicates. */
    val existing: String? = null,
)

data class AddedFont(val name: String, val id: String)

data class FontImportReport(val added: List<AddedFont>, val skipped: List<SkippedFont>)

/**
 * The imported half of the font library: `filesDir/custom_fonts`.
 *
 * Importing used to be one file at a time, copied as-is under the picker's
 * file name, with a `.ttf` pasted on when it had none. Nothing checked that the
 * file was a font, so a wrong pick became a library entry the app silently
 * could not render; and a second import with the same file name overwrote the
 * first without a word. Now every file is copied to a temporary name while it
 * is hashed, opened by [FontFileInspector], refused if the library already
 * holds the same bytes, named after the family it declares, and finally
 * handed to the platform to load before it is kept.
 */
@Singleton
class FontLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val dir: File get() = File(context.filesDir, "custom_fonts")
    private val lock = Mutex()

    private val _imported = MutableStateFlow<List<ImportedFont>>(emptyList())
    val imported: StateFlow<List<ImportedFont>> = _imported.asStateFlow()

    /** Re-reads the folder. Every font is opened, so this runs off the main thread. */
    suspend fun refresh() = withContext(Dispatchers.IO) {
        lock.withLock { _imported.value = scan() }
    }

    suspend fun import(uris: List<Uri>): FontImportReport = withContext(Dispatchers.IO) {
        lock.withLock {
            dir.mkdirs()
            val added = mutableListOf<AddedFont>()
            val skipped = mutableListOf<SkippedFont>()
            // What the library already holds, by content: a font imported
            // again under another file name is still the same font.
            val known = HashMap<String, String>()
            scan().forEach { font -> sha256(font.file)?.let { known[it] = font.name } }
            val takenStems = dir.listFiles().orEmpty().map { it.nameWithoutExtension.lowercase() }.toMutableSet()

            for (uri in uris) {
                val picked = displayNameOf(uri)
                val temp = File(dir, ".import-${System.nanoTime()}.tmp")
                try {
                    val hash = when (val copied = copyHashing(uri, temp)) {
                        CopyResult.TooLarge -> { skipped += SkippedFont(picked, FontSkipReason.TOO_LARGE); continue }
                        CopyResult.Failed -> { skipped += SkippedFont(picked, FontSkipReason.UNREADABLE); continue }
                        is CopyResult.Ok -> copied.sha256
                    }
                    val font = when (val inspection = FontFileInspector.inspect(temp)) {
                        is FontInspection.Rejected -> { skipped += SkippedFont(picked, inspection.reason.toSkip()); continue }
                        is FontInspection.Font -> inspection
                    }
                    known[hash]?.let { existing ->
                        skipped += SkippedFont(picked, FontSkipReason.ALREADY_THERE, existing)
                        continue
                    }
                    val name = font.displayName(fallback = picked.substringBeforeLast('.').ifBlank { "Font" })
                    val stem = uniqueStem(safeFileStem(name), takenStems)
                    val target = File(dir, "$stem.${extensionFor(font.format)}")
                    if (!temp.renameTo(target)) {
                        temp.copyTo(target, overwrite = false)
                    }
                    if (!platformCanLoad(target)) {
                        target.delete()
                        skipped += SkippedFont(picked, FontSkipReason.NOT_A_FONT)
                        continue
                    }
                    takenStems += stem.lowercase()
                    known[hash] = name
                    added += AddedFont(name, target.absolutePath)
                } finally {
                    temp.delete()
                }
            }
            _imported.value = scan()
            FontImportReport(added, skipped)
        }
    }

    suspend fun delete(font: ImportedFont) = withContext(Dispatchers.IO) {
        lock.withLock {
            font.file.delete()
            _imported.value = scan()
        }
    }

    private fun scan(): List<ImportedFont> =
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.extension.lowercase() in FONT_EXTENSIONS }
            .map { file ->
                when (val inspection = FontFileInspector.inspect(file)) {
                    is FontInspection.Font ->
                        ImportedFont(file, inspection.displayName(fallback = file.nameWithoutExtension), usable = true)
                    is FontInspection.Rejected ->
                        ImportedFont(file, file.nameWithoutExtension, usable = false)
                }
            }
            .sortedBy { it.name.lowercase() }

    private sealed interface CopyResult {
        data class Ok(val sha256: String) : CopyResult
        data object TooLarge : CopyResult
        data object Failed : CopyResult
    }

    private fun copyHashing(uri: Uri, target: File): CopyResult = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri) ?: return CopyResult.Failed
        input.use { stream ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) return CopyResult.TooLarge
                    digest.update(buffer, 0, n)
                    out.write(buffer, 0, n)
                }
            }
        }
        CopyResult.Ok(digest.digest().toHex())
    }.getOrDefault(CopyResult.Failed)

    private fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        digest.digest().toHex()
    }.getOrNull()

    private fun displayNameOf(uri: Uri): String {
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "font"
    }

    /**
     * The platform's own verdict, last. The inspector proves the structure is
     * sound; this proves the device's font stack accepts it, which is what a
     * render will need.
     */
    private fun platformCanLoad(file: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.graphics.fonts.Font.Builder(file).build()
            true
        } else {
            Typeface.Builder(file).build() != null
        }
    }.getOrDefault(false)

    companion object {
        /** The largest font accepted. CJK faces run to ~20 MB; nothing real is near this. */
        const val MAX_BYTES = 64L * 1024 * 1024

        private val FONT_EXTENSIONS = setOf("ttf", "otf", "ttc")

        /** A file name made from a font's name: letters, digits, spaces, dashes. */
        internal fun safeFileStem(name: String): String =
            name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-' || it == '_') it else ' ' }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(60)
                .ifBlank { "Font" }

        /** [stem], or "[stem] 2", "[stem] 3"… — never one already taken (compared case-blind). */
        internal fun uniqueStem(stem: String, taken: Set<String>): String {
            if (stem.lowercase() !in taken) return stem
            var n = 2
            while ("$stem $n".lowercase() in taken) n++
            return "$stem $n"
        }

        internal fun extensionFor(format: FontFormat): String = when (format) {
            FontFormat.TRUETYPE -> "ttf"
            FontFormat.OPENTYPE_CFF -> "otf"
            FontFormat.COLLECTION -> "ttc"
        }

        internal fun FontRejection.toSkip(): FontSkipReason = when (this) {
            FontRejection.EMPTY -> FontSkipReason.EMPTY
            FontRejection.NOT_A_FONT -> FontSkipReason.NOT_A_FONT
            FontRejection.WEB_FONT -> FontSkipReason.WEB_FONT
            FontRejection.TYPE1 -> FontSkipReason.TYPE1
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
