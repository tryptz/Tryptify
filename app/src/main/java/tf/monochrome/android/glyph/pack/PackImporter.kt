// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.glyph.chart.SmConverter
import tf.monochrome.android.audio.stepmania.CourseType
import tf.monochrome.android.glyph.chart.SscParser

/**
 * Unpacks a StepMania pack ZIP — StepMania Online's, or one the app made —
 * into [root], one folder per pack.
 *
 * Streams: the ZIP is read once, front to back, straight from the download or
 * the picked file, and never held whole, so a gigabyte pack needs no gigabyte
 * of memory or a second copy on disk.
 *
 * ## A ZIP from the internet is untrusted
 *
 * * No entry is written outside the staging folder: a name with `..`, or one
 *   whose resolved path leaves the folder, is skipped ("zip slip").
 * * Every entry is bounded as it inflates, by what it is ([limitFor]), and the
 *   whole import by [maxTotalBytes] and by the free space left — so a small
 *   file that inflates to fill the phone is stopped part way, not after.
 * * Only what a pack needs is kept: charts, music, images, `Pack.ini`. Videos
 *   and everything else are skipped, and so is macOS's `__MACOSX` folder,
 *   whose `._song.sm` files would otherwise read as charts.
 *
 * ## All or nothing, per pack
 *
 * Everything lands in a hidden staging folder first. Only once the ZIP has
 * been read to the end are the songs checked — a chart that parses, with four
 * panel charts in it, and music it names — and each pack moved into place,
 * replacing an earlier import of the same pack. A failed or cancelled import
 * leaves nothing behind.
 */
class PackImporter(
    private val root: File,
    /** A song's length in seconds, for the song list; 0 when unknown. */
    private val durationOf: (File) -> Int = { 0 },
    /** The same for music read in place, by its document's URI. */
    private val durationOfUri: (String) -> Int = { 0 },
    private val maxTotalBytes: Long = 8L * 1024 * 1024 * 1024,
    /** Free space to leave on the volume; the import stops before going under it. */
    private val reserveBytes: Long = 256L * 1024 * 1024,
) {

    class ImportedPack(val name: String, val folder: File, val songs: Int)

    class Result(val packs: List<ImportedPack>, val skippedSongs: Int) {
        val songs: Int get() = packs.sumOf { it.songs }
    }

    class PackImportException(message: String) : IOException(message)

    /**
     * One file of a folder being imported: its [path] under the picked folder
     * (folder names, then the file's), its size when known, and how to read it.
     */
    class SourceFile(
        val path: List<String>,
        val size: Long,
        /** The file as a document, for a pack read in place; null when it can only be copied. */
        val uri: String? = null,
        val open: () -> InputStream,
    )

    /**
     * Import StepMania songs already unpacked into folders, as StepMania keeps
     * them: `Songs/<pack>/<song>/<chart and music>`. [folderName] is the
     * picked folder's own name, and it can be any level of that tree:
     *
     * * a song (it holds the chart) — a pack of that one song, named after it;
     * * a pack (its folders hold charts) — that pack, named after it;
     * * `Songs`, or StepMania's own folder — every pack in it, each named
     *   after its folder.
     *
     * A song folder is a folder that holds a chart, and its pack is the folder
     * around it. Each pack is copied and installed on its own, through the
     * same checks as a ZIP's: the same files kept, the same bounds, the same
     * all-or-nothing install. So a folder of thirty packs that fills the phone
     * at the eighth keeps the seven before it, and says where it stopped.
     *
     * The files are the player's own, picked from their phone, not a download:
     * there is no cap on the whole beyond the free space left.
     *
     * With [source] (the picked folder's tree URI) the songs are read in place
     * instead ([linkPack]): nothing but the charts is copied, and [onProgress]
     * counts songs rather than bytes.
     */
    fun importFolder(
        files: List<SourceFile>,
        folderName: String,
        onProgress: (copied: Long, total: Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
        source: String? = null,
    ): Result {
        root.mkdirs()
        val wanted = files.filter { file ->
            val name = file.path.lastOrNull() ?: return@filter false
            file.path.none { it.isEmpty() || it == "." || it == ".." || it == "__MACOSX" } &&
                !name.startsWith("._") && limitFor(name) != null
        }
        val byFolder = wanted.groupBy { it.path.dropLast(1) }
        val songFolders = byFolder.keys.filter { folder ->
            byFolder.getValue(folder).any { StepManiaPack.extension(it.path.last()) in StepManiaPack.CHART_EXTENSIONS }
        }
        if (songFolders.isEmpty()) throw PackImportException("That folder has no StepMania charts in it.")

        val fallback = StepManiaPack.safeName(folderName, "Imported pack")
        // The picked folder itself a song: one pack of it. Otherwise each song
        // folder's parent is its pack — the picked folder when it is the pack.
        val byPack = songFolders.groupBy { folder -> if (folder.isEmpty()) null else folder.dropLast(1) }
            .entries.sortedBy { (pack, _) -> pack?.joinToString("/").orEmpty().lowercase() }
        if (source != null) {
            var done = 0L
            var skippedInPlace = 0
            val linked = ArrayList<ImportedPack>()
            for ((packPath, songs) in byPack) {
                if (!isActive()) throw PackImportException("The import was cancelled.")
                val packName = StepManiaPack.safeName(packPath?.lastOrNull() ?: fallback, fallback)
                val (pack, skippedHere) = linkPack(packName, songs, byFolder, source, isActive) {
                    done += 1
                    onProgress(done, songFolders.size.toLong())
                }
                skippedInPlace += skippedHere
                pack?.let { linked += it }
            }
            if (linked.isEmpty()) {
                throw PackImportException("No song in that folder has a dance (dance-single) or pop'n (pnm-nine) chart with its music.")
            }
            return Result(linked, skippedInPlace)
        }

        val total = songFolders.sumOf { folder -> byFolder.getValue(folder).sumOf { it.size.coerceAtLeast(0) } }
        var copied = 0L
        var skipped = 0
        val packs = ArrayList<ImportedPack>()
        val buffer = ByteArray(64 * 1024)

        for ((packPath, songs) in byPack) {
            if (!isActive()) throw PackImportException("The import was cancelled.")
            val packName = StepManiaPack.safeName(packPath?.lastOrNull() ?: fallback, fallback)
            val staging = File(root, ".staging-${System.nanoTime()}")
            try {
                staging.mkdirs()
                for (song in songs) {
                    // A song folder at the top (the pick was a song) lands at
                    // the top of staging, as a bare song in a ZIP does.
                    val into = if (song.isEmpty()) staging else File(staging, StepManiaPack.safeName(song.last()).take(MAX_SEGMENT))
                    into.mkdirs()
                    for (file in byFolder.getValue(song)) {
                        if (!isActive()) throw PackImportException("The import was cancelled.")
                        val name = file.path.last()
                        val limit = limitFor(name) ?: continue
                        ensureSpace()
                        val target = File(into, name.take(MAX_SEGMENT))
                        var written = 0L
                        var tooBig = false
                        file.open().use { input ->
                            target.outputStream().buffered().use { out ->
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    written += read
                                    copied += read
                                    if (written > limit) {
                                        tooBig = true
                                        break
                                    }
                                    if (written % SPACE_CHECK_BYTES < read) ensureSpace()
                                    out.write(buffer, 0, read)
                                    onProgress(copied, total)
                                }
                            }
                        }
                        if (tooBig) target.delete()
                    }
                }
                val installed = try {
                    install(staging, packName)
                } catch (empty: PackImportException) {
                    // Nothing playable in this pack: counted, and on to the next.
                    if (!isActive() || staging.usableSpace < reserveBytes) throw empty
                    skipped += songs.size
                    continue
                }
                packs += installed.packs
                skipped += installed.skippedSongs
            } catch (failure: PackImportException) {
                if (packs.isEmpty()) throw failure
                throw PackImportException(
                    "${failure.message} ${packs.size} pack${if (packs.size == 1) "" else "s"} before it " +
                        "(${packs.joinToString(", ") { it.name }}) were imported.",
                )
            } finally {
                staging.deleteRecursively()
            }
        }
        if (packs.isEmpty()) {
            throw PackImportException("No song in that folder has a dance (dance-single) or pop'n (pnm-nine) chart with its music.")
        }
        return Result(packs, skipped)
    }

    /**
     * Import the ZIP read from [input]. [nameHint] names the pack when the ZIP
     * holds song folders with no pack folder around them (usually the file's
     * name). [onBytes] gets the compressed bytes read so far, for progress.
     */
    fun import(
        input: InputStream,
        nameHint: String,
        onBytes: (Long) -> Unit = {},
        isActive: () -> Boolean = { true },
    ): Result {
        root.mkdirs()
        val staging = File(root, ".staging-${System.nanoTime()}")
        try {
            extract(input, staging, onBytes, isActive)
            return install(staging, StepManiaPack.safeName(nameHint.removeSuffix(".zip"), "Imported pack"))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun extract(input: InputStream, staging: File, onBytes: (Long) -> Unit, isActive: () -> Boolean) {
        staging.mkdirs()
        val stagingPath = staging.canonicalPath + File.separator
        val counted = CountingInputStream(input, onBytes)
        var total = 0L
        var entries = 0
        var kept = 0
        val buffer = ByteArray(64 * 1024)

        ZipInputStream(counted.buffered()).use { zip ->
            while (true) {
                if (!isActive()) throw PackImportException("The import was cancelled.")
                val entry = zip.nextEntry ?: break
                entries += 1
                if (entries > MAX_ENTRIES) throw PackImportException("That archive has too many files to be a pack.")
                if (entry.isDirectory) continue

                val segments = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                if (segments.isEmpty() || segments.any { it == ".." || it == "__MACOSX" }) continue
                val fileName = segments.last()
                if (fileName.startsWith("._")) continue
                val limit = limitFor(fileName) ?: continue

                val target = File(staging, segments.joinToString("/") { it.take(MAX_SEGMENT) })
                // Belt and braces: whatever the name held, the file is inside staging.
                if (!target.canonicalPath.startsWith(stagingPath)) continue
                ensureSpace()

                target.parentFile?.mkdirs()
                var written = 0L
                var tooBig = false
                target.outputStream().buffered().use { out ->
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        written += read
                        total += read
                        if (written > limit) {
                            tooBig = true
                            break
                        }
                        if (total > maxTotalBytes) throw PackImportException("That pack is too large to import.")
                        // A single song can be hundreds of megabytes: the space
                        // is checked as it is written, not only before.
                        if (written % SPACE_CHECK_BYTES < read) ensureSpace()
                        out.write(buffer, 0, read)
                    }
                }
                // An entry past its bound is dropped, not truncated: half a
                // song plays wrong and half a chart reads wrong.
                if (tooBig) target.delete() else kept += 1
            }
        }
        if (kept == 0) throw PackImportException("That file is not a StepMania pack: it has no charts or music in it.")
    }

    /**
     * One pack read in place: each song's chart read from the folder, kept as
     * the `.ssc` the app reads, and its music and art indexed by their
     * documents' URIs. Installed all at once, replacing an earlier import of
     * the same pack. Returns the pack, or null when no song in it plays, and
     * how many songs were left out.
     */
    private fun linkPack(
        packName: String,
        songs: List<List<String>>,
        byFolder: Map<List<String>, List<SourceFile>>,
        source: String,
        isActive: () -> Boolean,
        onSong: () -> Unit,
    ): Pair<ImportedPack?, Int> {
        val staging = File(root, ".staging-${System.nanoTime()}")
        try {
            staging.mkdirs()
            val entries = ArrayList<PackIndex.Song>()
            val usedNames = HashSet<String>()
            var skipped = 0
            for (song in songs) {
                if (!isActive()) throw PackImportException("The import was cancelled.")
                val entry = linkSong(song, byFolder.getValue(song), staging, usedNames)
                if (entry == null) skipped += 1 else entries += entry
                onSong()
            }
            if (entries.isEmpty()) return null to skipped
            File(staging, StepManiaPack.INDEX_FILE).writeText(
                JSON.encodeToString(PackIndex(packName, entries.sortedBy { it.title.lowercase() }, source = source)),
            )
            val destination = File(root, packName)
            if (destination.exists()) destination.deleteRecursively()
            if (!staging.renameTo(destination)) staging.copyRecursively(destination, overwrite = true)
            return ImportedPack(packName, destination, entries.size) to skipped
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun linkSong(
        song: List<String>,
        files: List<SourceFile>,
        staging: File,
        usedNames: MutableSet<String>,
    ): PackIndex.Song? {
        val names = files.map { it.path.last() }
        val chartFile = StepManiaPack.CHART_EXTENSIONS.firstNotNullOfOrNull { ext ->
            files.filter { StepManiaPack.extension(it.path.last()) == ext }.minByOrNull { it.path.last() }
        } ?: return null
        if (chartFile.size > 16L * MB) return null
        val text = runCatching { chartFile.open().use { it.readBytes().toString(Charsets.UTF_8) } }.getOrNull() ?: return null
        val ssc = SmConverter.toSsc(text)
        // A song plays with a chart of either course: the dance panels, or pop'n's buttons.
        val popn = runCatching { SscParser.parse(ssc, CourseType.POPN) }.getOrNull()
        val simfile = runCatching { SscParser.parse(ssc, CourseType.DANCE) }.getOrNull() ?: popn ?: return null
        val musicName = StepManiaPack.musicName(names, ssc) ?: return null
        val music = files.first { it.path.last() == musicName }
        val musicUri = music.uri ?: return null
        val art = StepManiaPack.artName(names, ssc)?.let { name -> files.first { it.path.last() == name } }
        val background = StepManiaPack.backgroundName(names, ssc)?.let { name -> files.first { it.path.last() == name } }

        var folderName = StepManiaPack.safeName(song.lastOrNull() ?: simfile.title)
        while (!usedNames.add(folderName.lowercase())) folderName = "$folderName (2)"
        val chartName = StepManiaPack.safeName(chartFile.path.last().substringBeforeLast('.')) + ".ssc"
        File(staging, folderName).apply { mkdirs() }.resolve(chartName).writeText(ssc)
        return PackIndex.Song(
            folder = folderName,
            chart = chartName,
            music = musicName,
            art = art?.path?.last(),
            title = simfile.title,
            artist = simfile.artist,
            durationSeconds = runCatching { durationOfUri(musicUri) }.getOrDefault(0),
            difficulties = if (simfile.charts.first().course == CourseType.DANCE) simfile.availableDifficulties.map { it.name } else emptyList(),
            popnDifficulties = popn?.availableDifficulties?.map { it.name }.orEmpty(),
            bpm = simfile.listedBpm,
            musicUri = musicUri,
            artUri = art?.uri,
            background = background?.path?.last(),
            backgroundUri = background?.uri,
        )
    }

    private fun ensureSpace() {
        if (root.usableSpace < reserveBytes) throw PackImportException("The phone is out of space for this pack.")
    }

    private fun install(staging: File, nameHint: String): Result {
        val songFolders = staging.walkTopDown()
            .filter { it.isDirectory }
            .filter { folder -> folder.listFiles()?.any { it.isFile && StepManiaPack.extension(it.name) in StepManiaPack.CHART_EXTENSIONS } == true }
            .toList()

        // Group song folders by pack: the folder around each, or the hint when
        // the ZIP is a bare song or a bare set of songs.
        val byPack = LinkedHashMap<String, MutableList<File>>()
        for (folder in songFolders) {
            val depth = folder.relativeTo(staging).invariantSeparatorsPath.split('/').filter { it.isNotEmpty() }.size
            val pack = if (depth >= 2) folder.parentFile!!.name else nameHint
            byPack.getOrPut(StepManiaPack.safeName(pack, nameHint)) { ArrayList() } += folder
        }

        var skipped = 0
        val packs = ArrayList<ImportedPack>()
        for ((packName, folders) in byPack) {
            val packFolder = File(staging, ".ready-${packs.size}-${packName.hashCode()}")
            packFolder.mkdirs()
            val songs = ArrayList<PackIndex.Song>()
            val usedNames = HashSet<String>()
            for (folder in folders) {
                val song = prepareSong(folder)
                if (song == null) {
                    skipped += 1
                    continue
                }
                var name = StepManiaPack.safeName(if (folder == staging) song.title else folder.name)
                while (!usedNames.add(name.lowercase())) name = "$name (2)"
                val destination = File(packFolder, name)
                if (folder == staging) {
                    // A bare song at the top of the ZIP: its files move, since
                    // the folder they are in holds the destination too.
                    destination.mkdirs()
                    folder.listFiles()?.filter { it.isFile }?.forEach { it.renameTo(File(destination, it.name)) }
                } else if (!folder.renameTo(destination)) {
                    folder.copyRecursively(destination, overwrite = true)
                }
                songs += song.copy(folder = name)
            }
            if (songs.isEmpty()) continue

            // The pack's own Pack.ini and banner, when it has them, go with it.
            folders.first().parentFile?.takeIf { it != staging && it.startsWith(staging) }?.listFiles()
                ?.filter { it.isFile && (it.name.equals(StepManiaPack.PACK_INI, true) || StepManiaPack.extension(it.name) in StepManiaPack.IMAGE_EXTENSIONS) }
                ?.forEach { it.copyTo(File(packFolder, it.name), overwrite = true) }

            File(packFolder, StepManiaPack.INDEX_FILE).writeText(JSON.encodeToString(PackIndex(packName, songs.sortedBy { it.title.lowercase() })))

            // Replace an earlier import of the same pack, as a whole.
            val destination = File(root, packName)
            if (destination.exists()) destination.deleteRecursively()
            if (!packFolder.renameTo(destination)) {
                packFolder.copyRecursively(destination, overwrite = true)
            }
            packs += ImportedPack(packName, destination, songs.size)
        }
        if (packs.isEmpty()) {
            throw PackImportException("No song in that pack has a dance (dance-single) or pop'n (pnm-nine) chart with its music.")
        }
        return Result(packs, skipped)
    }

    /**
     * Check one song folder and make its chart an `.ssc`. Null when it cannot
     * be played: no chart that reads, no dance or pop'n chart, or no music.
     */
    private fun prepareSong(folder: File): PackIndex.Song? {
        val chartFile = StepManiaPack.CHART_EXTENSIONS.firstNotNullOfOrNull { ext ->
            folder.listFiles()?.filter { it.isFile && StepManiaPack.extension(it.name) == ext }?.minByOrNull { it.name }
        } ?: return null
        val text = runCatching { chartFile.readText() }.getOrNull() ?: return null
        val ssc = SmConverter.toSsc(text)
        // A song plays with a chart of either course: the dance panels, or pop'n's buttons.
        val popn = runCatching { SscParser.parse(ssc, CourseType.POPN) }.getOrNull()
        val simfile = runCatching { SscParser.parse(ssc, CourseType.DANCE) }.getOrNull() ?: popn ?: return null
        val music = StepManiaPack.music(folder, ssc) ?: return null

        // An .sm is kept as it came, with the .ssc the app reads beside it.
        val chart = if (StepManiaPack.extension(chartFile.name) == "ssc") chartFile else {
            File(folder, chartFile.nameWithoutExtension + ".ssc").also { it.writeText(ssc) }
        }
        return PackIndex.Song(
            folder = folder.name,
            chart = chart.name,
            music = music.name,
            art = StepManiaPack.art(folder, ssc)?.name,
            background = StepManiaPack.background(folder, ssc)?.name,
            title = simfile.title,
            artist = simfile.artist,
            durationSeconds = runCatching { durationOf(music) }.getOrDefault(0),
            difficulties = if (simfile.charts.first().course == CourseType.DANCE) simfile.availableDifficulties.map { it.name } else emptyList(),
            popnDifficulties = popn?.availableDifficulties?.map { it.name }.orEmpty(),
            bpm = simfile.listedBpm,
        )
    }

    /** The most a file of this kind may inflate to; null for a file a pack does not need. */
    private fun limitFor(name: String): Long? = when (StepManiaPack.extension(name)) {
        in StepManiaPack.CHART_EXTENSIONS -> 16L * MB
        in StepManiaPack.AUDIO_EXTENSIONS -> 512L * MB
        in StepManiaPack.IMAGE_EXTENSIONS -> 32L * MB
        "ini" -> if (name.equals(StepManiaPack.PACK_INI, ignoreCase = true)) 64L * 1024 else null
        else -> null
    }

    private class CountingInputStream(input: InputStream, private val onBytes: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) report(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) report(it.toLong()) }

        private fun report(n: Long) {
            count += n
            onBytes(count)
        }
    }

    companion object {
        private const val MB = 1024L * 1024
        private const val MAX_ENTRIES = 100_000
        private const val MAX_SEGMENT = 120
        private const val SPACE_CHECK_BYTES = 16L * MB
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = false }

        /** Read the index [PackImporter] wrote for the pack in [folder], or null. */
        fun readIndex(folder: File): PackIndex? = runCatching {
            JSON.decodeFromString<PackIndex>(File(folder, StepManiaPack.INDEX_FILE).readText())
        }.getOrNull()

        /** Write [index] for the pack in [folder], as the importer writes it. */
        fun writeIndex(folder: File, index: PackIndex) {
            File(folder, StepManiaPack.INDEX_FILE).writeText(JSON.encodeToString(index))
        }
    }
}
