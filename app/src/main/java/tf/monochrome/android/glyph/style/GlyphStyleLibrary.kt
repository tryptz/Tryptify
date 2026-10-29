// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.pack.StepManiaPack

/**
 * The StepMania noteskins, themes, announcers, characters and dances imported on this phone, as
 * Trypt Mania's Themes screen lists them, each baked at import into the
 * game's own layout ([StepManiaStyleBaker]) under
 * `files/glyph/styles/<kind>/<name>/`.
 *
 * Imported noteskins are handed to [GlyphSkin.imported], so every noteskin
 * picker lists them beside the app's; read here, at construction, before the
 * game reads its saved skin.
 */
@Singleton
class GlyphStyleLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class Style(val kind: StepManiaStyleScan.Kind, val id: String, val name: String, val source: String, val dir: File)

    data class Styles(
        val noteskins: List<Style> = emptyList(),
        val themes: List<Style> = emptyList(),
        val announcers: List<Style> = emptyList(),
        val characters: List<Style> = emptyList(),
        /** The dances characters share (the archive's "Animation + Helper Bones"), found by file name. */
        val dances: List<Style> = emptyList(),
    )

    /** What an import brought in, by kind, and what in it could not be used. */
    data class ImportResult(
        val noteskins: Int,
        val themes: Int,
        val announcers: Int,
        val characters: Int,
        val dances: Int,
        val skipped: List<String>,
    ) {
        val total: Int get() = noteskins + themes + announcers + characters + dances
    }

    val root: File = File(context.filesDir, "glyph/styles")
    private val lock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val _styles = MutableStateFlow(read())
    val styles: StateFlow<Styles> = _styles.asStateFlow()

    init {
        publishSkins(_styles.value)
    }

    fun find(kind: StepManiaStyleScan.Kind, id: String?): Style? {
        id ?: return null
        val all = _styles.value
        return when (kind) {
            StepManiaStyleScan.Kind.NOTESKIN -> all.noteskins
            StepManiaStyleScan.Kind.THEME -> all.themes
            StepManiaStyleScan.Kind.ANNOUNCER -> all.announcers
            StepManiaStyleScan.Kind.CHARACTER -> all.characters
            StepManiaStyleScan.Kind.DANCES -> all.dances
        }.firstOrNull { it.id == id }
    }

    /** A shared dance by its file name, from any set of dances imported; null when none has it. */
    fun sharedDance(fileName: String): File? {
        for (set in _styles.value.dances) {
            set.dir.listFiles().orEmpty().firstOrNull { it.name.equals(fileName, ignoreCase = true) }?.let { return it }
        }
        return null
    }

    /**
     * Imports the archive at [uri]: everything in it Trypt Mania can use, each
     * replacing an earlier import of the same name. Throws
     * [StyleArchive.StyleImportException] with a reason a person can act on.
     */
    suspend fun import(uri: Uri): ImportResult = importInto(displayName(uri) ?: "Imported") { archive ->
        context.contentResolver.openInputStream(uri)?.use { input ->
            archive.outputStream().use { input.copyTo(it) }
        } ?: throw StyleArchive.StyleImportException("That file could not be opened.")
    }

    /**
     * Downloads [url] (https only, a few redirects at most, as the StepMania
     * Archive's links go through its file host and GitHub) and imports it, as
     * [import] does a picked file. [nameHint] names it when the link does not.
     */
    suspend fun importUrl(url: String, nameHint: String): ImportResult {
        val name = nameHint.ifBlank { Uri.decode(url.substringAfterLast('/').substringBefore('?')) }.ifBlank { "Imported" }
        return importInto(name) { archive -> download(url, archive) }
    }

    private fun download(url: String, into: File) {
        var address = url
        repeat(MAX_REDIRECTS) {
            val target = java.net.URL(address)
            if (target.protocol != "https") throw StyleArchive.StyleImportException("Only https downloads are imported.")
            val connection = (target.openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 20_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "Tryptify")
            }
            try {
                when (val code = connection.responseCode) {
                    in 300..399 -> {
                        address = java.net.URL(target, connection.getHeaderField("Location") ?: throw StyleArchive.StyleImportException("The download went nowhere.")).toString()
                        return@repeat
                    }
                    in 200..299 -> {
                        var total = 0L
                        connection.inputStream.use { input ->
                            into.outputStream().use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > MAX_DOWNLOAD_BYTES) throw StyleArchive.StyleImportException("That download is too large to import.")
                                    out.write(buffer, 0, read)
                                }
                            }
                        }
                        return
                    }
                    else -> throw StyleArchive.StyleImportException("The download failed (HTTP $code).")
                }
            } finally {
                connection.disconnect()
            }
        }
        throw StyleArchive.StyleImportException("The download redirected too many times.")
    }

    private suspend fun importInto(name: String, fetch: (File) -> Unit): ImportResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val work = File(context.cacheDir, "glyph-style-${System.nanoTime()}")
            try {
                work.mkdirs()
                val archive = File(work, "archive")
                fetch(archive)
                val source = File(work, "src")
                val files = StyleArchive().extract(archive, source)
                archive.delete()
                val found = StepManiaStyleScan.scan(files)
                if (found.isEmpty()) {
                    throw StyleArchive.StyleImportException(
                        "No StepMania noteskin, theme, announcer or character was found in that file.",
                    )
                }
                val baker = StepManiaStyleBaker(source, name)
                val staged = File(root, ".staging-${System.nanoTime()}")
                val counts = IntArray(StepManiaStyleScan.Kind.entries.size)
                val skipped = ArrayList<String>()
                try {
                    for (item in found) {
                        val label = item.name.takeIf { item.dir.isNotEmpty() } ?: name.substringBeforeLast('.')
                        val id = StepManiaPack.safeName(label, "Imported")
                        val out = File(staged, "${folderOf(item.kind)}/$id")
                        val ok = runCatching {
                            when (item.kind) {
                                StepManiaStyleScan.Kind.NOTESKIN -> baker.bakeNoteSkin(files, item.copy(name = label), out)
                                StepManiaStyleScan.Kind.THEME -> baker.bakeTheme(files, item.copy(name = label), out)
                                StepManiaStyleScan.Kind.ANNOUNCER -> baker.bakeAnnouncer(files, item.copy(name = label), out)
                                StepManiaStyleScan.Kind.CHARACTER -> baker.bakeCharacter(files, item.copy(name = label), out)
                                StepManiaStyleScan.Kind.DANCES -> baker.bakeDances(files, item.copy(name = label), out)
                            }
                        }.onFailure { Log.w(TAG, "could not bake $label: ${it.message}") }.getOrDefault(false)
                        if (ok) counts[item.kind.ordinal] += 1 else skipped += label
                    }
                    // Into place, each replacing an earlier import of the same name.
                    for (kind in StepManiaStyleScan.Kind.entries) {
                        val from = File(staged, folderOf(kind))
                        for (dir in from.listFiles().orEmpty()) {
                            val target = File(root, "${folderOf(kind)}/${dir.name}")
                            target.parentFile?.mkdirs()
                            target.deleteRecursively()
                            if (!dir.renameTo(target)) {
                                dir.copyRecursively(target, overwrite = true)
                            }
                        }
                    }
                } finally {
                    staged.deleteRecursively()
                }
                refresh()
                ImportResult(counts[0], counts[1], counts[2], counts[3], counts[4], skipped)
            } finally {
                work.deleteRecursively()
            }
        }
    }

    suspend fun remove(style: Style) = withContext(Dispatchers.IO) {
        lock.withLock {
            val dir = style.dir.canonicalFile
            // Only ever a folder of this library.
            if (dir.path.startsWith(root.canonicalPath + File.separator)) dir.deleteRecursively()
            refresh()
        }
    }

    private fun refresh() {
        val styles = read()
        _styles.value = styles
        publishSkins(styles)
    }

    private fun publishSkins(styles: Styles) {
        GlyphSkin.imported = styles.noteskins.map { style ->
            GlyphSkin(
                id = GlyphSkin.importedId(style.id),
                label = style.name,
                root = style.dir.path,
                isGlass = false,
                isFile = true,
            )
        }
    }

    private fun read(): Styles {
        fun list(kind: StepManiaStyleScan.Kind): List<Style> =
            File(root, folderOf(kind)).listFiles().orEmpty()
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .mapNotNull { dir ->
                    val info = runCatching {
                        json.decodeFromString<StepManiaStyleBaker.StyleInfo>(File(dir, StepManiaStyleBaker.STYLE_FILE).readText())
                    }.getOrNull() ?: return@mapNotNull null
                    Style(kind, dir.name, info.name, info.source, dir)
                }
                .sortedBy { it.name.lowercase() }
        return Styles(
            noteskins = list(StepManiaStyleScan.Kind.NOTESKIN),
            themes = list(StepManiaStyleScan.Kind.THEME),
            announcers = list(StepManiaStyleScan.Kind.ANNOUNCER),
            characters = list(StepManiaStyleScan.Kind.CHARACTER),
            dances = list(StepManiaStyleScan.Kind.DANCES),
        )
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    companion object {
        private const val TAG = "GlyphStyleLibrary"
        private const val MAX_REDIRECTS = 6
        /**
         * The StepMania Archive's "Animation + Helper Bones": the DDR PC moves
         * its characters point at by file name, shared rather than in each.
         */
        const val DANCE_PACK_URL = "https://s3.us-east-005.dream.io/smcharacters/Animation%20%2B%20Helper%20Bones.7z"
        private const val MAX_DOWNLOAD_BYTES = 1024L * 1024 * 1024
        fun folderOf(kind: StepManiaStyleScan.Kind) = when (kind) {
            StepManiaStyleScan.Kind.NOTESKIN -> "noteskins"
            StepManiaStyleScan.Kind.THEME -> "themes"
            StepManiaStyleScan.Kind.ANNOUNCER -> "announcers"
            StepManiaStyleScan.Kind.CHARACTER -> "characters"
            StepManiaStyleScan.Kind.DANCES -> "dances"
        }
    }
}
