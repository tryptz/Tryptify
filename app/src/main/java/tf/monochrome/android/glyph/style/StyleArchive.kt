// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile

/**
 * Unpacks a StepMania download (a noteskin, a theme, an announcer, or a
 * bundle of them) into a folder, keeping only what Trypt Mania can use:
 * images, sounds, fonts, and the small text files that say which image is
 * which (`.ini`, `.redir`, `.sprite`, `.actor`, `.lua`, `.xml`, `.txt`).
 *
 * A ZIP from the internet is untrusted, as for song packs (`PackImporter`):
 * no entry is written outside [into] (no `..`, a resolved path inside it),
 * each kind of file has a size bound checked as it inflates, the whole is
 * bounded by [maxTotalBytes], and `__MACOSX` and `._` files are skipped.
 *
 * ZIP and `.smzip` (StepMania's own, a ZIP) and 7z. RAR is refused with what
 * to do instead: its decoders carry the unRAR licence, which is not GPL's.
 */
class StyleArchive(
    private val maxTotalBytes: Long = 1024L * 1024 * 1024,
    private val maxEntries: Int = 50_000,
) {
    class StyleImportException(message: String) : IOException(message)

    /** Unpacks [archive] into [into]; returns the files kept, as paths under it. */
    fun extract(archive: File, into: File, isActive: () -> Boolean = { true }): List<String> {
        into.mkdirs()
        val head = archive.inputStream().use { stream -> ByteArray(8).also { stream.read(it) } }
        return when {
            head.startsWith(RAR) -> throw StyleImportException(
                "RAR archives can't be opened here. Unpack it on a computer and zip the folder, then import the ZIP.",
            )
            head.startsWith(SEVEN_Z) -> extract7z(archive, into, isActive)
            head.startsWith(ZIP) -> extractZip(archive, into, isActive)
            else -> throw StyleImportException("That file is not a ZIP, .smzip or 7z archive.")
        }
    }

    private fun extractZip(archive: File, into: File, isActive: () -> Boolean): List<String> {
        val writer = Writer(into)
        ZipFile(archive).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                if (!isActive()) throw StyleImportException("The import was cancelled.")
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                writer.write(entry.name) { zip.getInputStream(entry) }
            }
        }
        return writer.kept()
    }

    private fun extract7z(archive: File, into: File, isActive: () -> Boolean): List<String> {
        val writer = Writer(into)
        SevenZFile.builder().setFile(archive).get().use { sevenZ ->
            while (true) {
                if (!isActive()) throw StyleImportException("The import was cancelled.")
                val entry = sevenZ.nextEntry ?: break
                if (entry.isDirectory || !entry.hasStream()) continue
                // The stream is the archive's own: read through it, never closed early.
                writer.write(entry.name) { object : InputStream() {
                    override fun read(): Int = sevenZ.read()
                    override fun read(b: ByteArray, off: Int, len: Int): Int = sevenZ.read(b, off, len)
                    override fun close() {}
                } }
            }
        }
        return writer.kept()
    }

    /** Writes entries into [root], with every bound above. */
    private inner class Writer(private val root: File) {
        private val rootPath = root.canonicalPath + File.separator
        private val kept = ArrayList<String>()
        private var total = 0L
        private var entries = 0
        private val buffer = ByteArray(64 * 1024)

        fun write(name: String, open: () -> InputStream) {
            entries += 1
            if (entries > maxEntries) throw StyleImportException("That archive has too many files to be a theme or noteskin.")
            val segments = name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
            if (segments.isEmpty() || segments.any { it == ".." || it == "__MACOSX" }) return
            val fileName = segments.last()
            if (fileName.startsWith("._")) return
            val limit = limitFor(fileName) ?: return
            val target = File(root, segments.joinToString("/") { it.take(MAX_SEGMENT) })
            if (!target.canonicalPath.startsWith(rootPath)) return
            target.parentFile?.mkdirs()
            var written = 0L
            var tooBig = false
            open().use { input ->
                target.outputStream().buffered().use { out ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        written += read
                        total += read
                        if (written > limit) { tooBig = true; break }
                        if (total > maxTotalBytes) throw StyleImportException("That archive is too large to import.")
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (tooBig) target.delete() else kept += target.relativeTo(root).invariantSeparatorsPath
        }

        fun kept(): List<String> = kept
    }

    companion object {
        private val ZIP = byteArrayOf(0x50, 0x4B)
        private val SEVEN_Z = byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
        private val RAR = byteArrayOf(0x52, 0x61, 0x72, 0x21)
        private const val MAX_SEGMENT = 120
        private const val MB = 1024L * 1024

        /** What is kept, and how big each may be; null for what is skipped. */
        fun limitFor(fileName: String): Long? = when (fileName.substringAfterLast('.', "").lowercase()) {
            "png", "jpg", "jpeg", "gif", "bmp" -> 32 * MB
            "ogg", "mp3", "wav" -> 32 * MB
            "ttf", "otf" -> 16 * MB
            "ini", "redir", "sprite", "actor", "lua", "xml", "txt", "html", "md" -> 1 * MB
            else -> null
        }

        private fun ByteArray.startsWith(prefix: ByteArray) =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
