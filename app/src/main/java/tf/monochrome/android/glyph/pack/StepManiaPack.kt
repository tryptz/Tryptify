// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.pack

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.Serializable

/**
 * A StepMania pack, as StepMania lays one out — the one shape both ways in and
 * out of the app use.
 *
 * ```
 * <Pack>/
 *     Pack.ini
 *     <Song>/
 *         <Song>.ssc         (or .sm)
 *         <music>.ogg        (whatever #MUSIC names)
 *         banner.png …       (whatever #BANNER / #BACKGROUND / #JACKET name)
 * ```
 *
 * A pack downloaded from StepMania Online is read in this shape
 * ([PackImporter]), and the charts Tryptify makes are written out in it
 * ([PackWriter]) — so a pack the app made can be zipped and imported again,
 * dropped into StepMania's or ITGmania's `Songs` folder, or shared, exactly
 * like one someone else made.
 */
object StepManiaPack {

    /** Charts, in the order StepMania prefers them when a folder holds both. */
    val CHART_EXTENSIONS = listOf("ssc", "sm")
    val AUDIO_EXTENSIONS = setOf("ogg", "oga", "mp3", "wav", "flac", "opus", "m4a")
    val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif")

    const val PACK_INI = "Pack.ini"

    /** Tryptify's own record of an imported pack, written beside it. */
    const val INDEX_FILE = "tryptify-pack.json"

    /** The pack the charts made in the app go into. */
    const val OWN_PACK = "Tryptify"

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    /**
     * [name] made safe as one folder or file name on any system a pack might
     * be copied to: no path separators, none of Windows' reserved characters,
     * no leading dots, and not too long. Never empty.
     */
    fun safeName(name: String, fallback: String = "Untitled"): String {
        val cleaned = name
            .replace(UNSAFE, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trimStart('.')
            .trimEnd('.', ' ')
            .take(MAX_NAME)
            .trim()
        return cleaned.ifEmpty { fallback }
    }

    /** A song folder's name: its title, as StepMania packs name them. */
    fun songFolder(title: String, artist: String): String =
        safeName(if (artist.isBlank()) title else "$title - $artist")

    /** The value of `#KEY:value;` in a simfile's header, or null. */
    fun tag(text: String, key: String): String? {
        val match = Regex("""#${Regex.escape(key)}\s*:([^;]*);""", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.get(1)?.trim()?.ifEmpty { null }
    }

    /** The name among [names] a tag names, matched without regard to case as Windows-made packs need. */
    fun resolveName(names: List<String>, name: String?): String? {
        if (name.isNullOrBlank()) return null
        val wanted = name.replace('\\', '/').substringAfterLast('/')
        return names.firstOrNull { it.equals(wanted, ignoreCase = true) }
    }

    /** The song's music among [names]: what `#MUSIC` names, or failing that the folder's first audio. */
    fun musicName(names: List<String>, ssc: String): String? =
        resolveName(names, tag(ssc, "MUSIC")) ?: names.filter { extension(it) in AUDIO_EXTENSIONS }.minOrNull()

    /** Art among [names]: a square jacket first, then the banner, then the background. */
    fun artName(names: List<String>, ssc: String): String? =
        listOf("JACKET", "BANNER", "BACKGROUND").firstNotNullOfOrNull { resolveName(names, tag(ssc, it)) }
            ?: names.filter { extension(it) in IMAGE_EXTENSIONS }.minOrNull()

    /**
     * The song's full-screen background among [names]: what `#BACKGROUND`
     * names, and nothing else. A jacket or banner is cut for a list row, not
     * a screen; a song without its own background has none.
     */
    fun backgroundName(names: List<String>, ssc: String): String? = resolveName(names, tag(ssc, "BACKGROUND"))

    private fun fileNames(folder: File): List<String> = folder.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()

    /** The file in [folder] a tag names. */
    fun resolve(folder: File, name: String?): File? = resolveName(fileNames(folder), name)?.let { File(folder, it) }

    /** The song's music in [folder]. */
    fun music(folder: File, ssc: String): File? = musicName(fileNames(folder), ssc)?.let { File(folder, it) }

    /** Art for the song list in [folder]. */
    fun art(folder: File, ssc: String): File? = artName(fileNames(folder), ssc)?.let { File(folder, it) }

    /** The song's own background in [folder]. */
    fun background(folder: File, ssc: String): File? = backgroundName(fileNames(folder), ssc)?.let { File(folder, it) }

    /** A `Pack.ini` in ITGmania's format, which StepMania 5 also reads. */
    fun packIni(title: String, year: Int, banner: String = ""): String = buildString {
        appendLine("[Group]")
        appendLine("Version=2")
        appendLine("DisplayTitle=$title")
        appendLine("TranslitTitle=")
        appendLine("SortTitle=$title")
        appendLine("Series=")
        appendLine("Year=$year")
        appendLine("Banner=$banner")
        appendLine("SeriesBanner=")
        appendLine("SyncOffset=NULL")
    }

    private val UNSAFE = Regex("""[\\/:*?"<>|\u0000-\u001f]""")
    private const val MAX_NAME = 100
}

/** What [PackImporter] wrote beside an imported pack, so listing it needs no re-reading. */
@Serializable
data class PackIndex(
    val name: String,
    val songs: List<Song>,
    /**
     * For a pack read in place: the folder the player picked, as a document
     * tree. Its music and art are read from there, never copied; only the
     * charts the app reads, made `.ssc`, are kept beside this index.
     */
    val source: String? = null,
) {
    /** [folder] is relative to the pack's folder; the other paths to the song's. */
    @Serializable
    data class Song(
        val folder: String,
        val chart: String,
        val music: String,
        val art: String? = null,
        val title: String,
        val artist: String,
        val durationSeconds: Int = 0,
        /** The difficulties the chart has for the dance panels, by name, and its opening tempo: the song list's columns. */
        val difficulties: List<String> = emptyList(),
        /**
         * The same for pop'n's nine buttons. Null for an index written before
         * the app read pop'n charts, which [GlyphPackLibrary] fills in from
         * the chart the first time it sees it.
         */
        val popnDifficulties: List<String>? = null,
        val bpm: Float? = null,
        /** A pack read in place: the music and art as documents in [PackIndex.source]. */
        val musicUri: String? = null,
        val artUri: String? = null,
        /** What the chart's `#BACKGROUND` names; null when it names nothing there. */
        val background: String? = null,
        /** The same, as a document in [PackIndex.source], for a pack read in place. */
        val backgroundUri: String? = null,
    )
}

/**
 * Somewhere a pack can be written: a folder on disk, a folder the player
 * picked through Android's file picker, or a ZIP. [PackWriter] writes the same
 * layout into any of them.
 */
interface PackSink {
    /** Whether [path] (folders, then the file) already exists. */
    fun exists(path: List<String>): Boolean

    /** Write [path], replacing what is there. */
    fun write(path: List<String>, content: (OutputStream) -> Unit)
}

/** A pack written into [root] on disk: a folder is the pack's parent, as StepMania's `Songs` is. */
class FolderPackSink(private val root: File) : PackSink {
    override fun exists(path: List<String>): Boolean = File(root, path.joinToString("/")).exists()

    override fun write(path: List<String>, content: (OutputStream) -> Unit) {
        val target = File(root, path.joinToString("/"))
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        temp.outputStream().buffered().use(content)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }
}

/** A pack written as a ZIP, the way StepMania Online serves them. */
class ZipPackSink(output: OutputStream) : PackSink, AutoCloseable {
    private val zip = ZipOutputStream(output.buffered())
    private val written = HashSet<String>()

    override fun exists(path: List<String>): Boolean = path.joinToString("/") in written

    override fun write(path: List<String>, content: (OutputStream) -> Unit) {
        val name = path.joinToString("/")
        if (!written.add(name)) return
        zip.putNextEntry(ZipEntry(name))
        // The entry is closed here, not by the caller's stream.
        content(object : OutputStream() {
            override fun write(b: Int) = zip.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = zip.write(b, off, len)
        })
        zip.closeEntry()
    }

    override fun close() = zip.close()
}

/**
 * Writes songs into a pack — the charts made in the app, laid out as any
 * StepMania pack is, so they load in StepMania and import back here.
 */
object PackWriter {

    /**
     * One song: `<pack>/<song folder>/<song folder>.ssc` with its music beside
     * it under the name the chart's `#MUSIC` gives. The chart is always
     * rewritten; the music only when it is not there yet, since it is the same
     * file every time and can be large. Returns the song folder's path.
     *
     * [art], the song's cover ([SongArt]), goes beside them as its jacket and
     * the chart names it (`#JACKET`), unless the chart names a jacket of its
     * own: a made chart names no picture, and the pack read back here or in
     * StepMania showed the song with none.
     */
    fun writeSong(
        sink: PackSink,
        pack: String,
        title: String,
        artist: String,
        ssc: String,
        art: ByteArray? = null,
        music: (() -> InputStream)?,
    ): List<String> {
        val packFolder = StepManiaPack.safeName(pack, StepManiaPack.OWN_PACK)
        val songFolder = StepManiaPack.songFolder(title, artist)
        val base = listOf(packFolder, songFolder)
        val musicName = StepManiaPack.tag(ssc, "MUSIC")?.let { StepManiaPack.safeName(it) }

        if (!sink.exists(listOf(packFolder, StepManiaPack.PACK_INI))) {
            sink.write(listOf(packFolder, StepManiaPack.PACK_INI)) {
                it.write(StepManiaPack.packIni(packFolder, java.time.Year.now().value).toByteArray())
            }
        }
        val jacket = art?.takeIf { StepManiaPack.tag(ssc, "JACKET") == null }?.let { SongArt.jacketName(it) }
        val chart = if (jacket != null) SongArt.withJacket(ssc, jacket) else ssc
        sink.write(base + "$songFolder.ssc") { it.write(chart.toByteArray(Charsets.UTF_8)) }
        if (art != null && jacket != null && !sink.exists(base + jacket)) {
            sink.write(base + jacket) { it.write(art) }
        }
        if (music != null && musicName != null && !sink.exists(base + musicName)) {
            sink.write(base + musicName) { out -> music().use { it.copyTo(out) } }
        }
        return base
    }
}
