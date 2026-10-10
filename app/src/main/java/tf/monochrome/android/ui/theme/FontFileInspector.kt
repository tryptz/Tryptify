package tf.monochrome.android.ui.theme

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Random access into a font's bytes. A font is read a table at a time —
 * the header, the table directory, the name table — so a 20 MB CJK face is
 * never pulled into memory to find out what it is called.
 */
interface FontSource {
    val size: Long

    /** [length] bytes at [offset], or null when that range is not in the file. */
    fun read(offset: Long, length: Int): ByteArray?
}

class ByteArrayFontSource(private val bytes: ByteArray) : FontSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > bytes.size) return null
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + length)
    }
}

class FileFontSource(file: File) : FontSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long = raf.length()

    override fun read(offset: Long, length: Int): ByteArray? {
        if (offset < 0 || length < 0 || offset + length > size) return null
        val out = ByteArray(length)
        raf.seek(offset)
        raf.readFully(out)
        return out
    }

    override fun close() = raf.close()
}

/** What [FontFileInspector] found in a file. */
sealed interface FontInspection {

    data class Font(
        /** The family the font calls itself — typographic family first, else the legacy one. */
        val family: String?,
        /** Its style within the family: "Regular", "Bold Italic"… */
        val style: String?,
        /** Carries an `fvar` table: one file renders every weight. */
        val variable: Boolean,
        val format: FontFormat,
    ) : FontInspection {
        /**
         * The name to show: the family, with its style when the style says
         * more than "the usual one", so two static cuts of one family stay
         * apart. [fallback] stands in for a font with no readable name.
         */
        fun displayName(fallback: String): String {
            val base = family?.takeIf { it.isNotBlank() } ?: return fallback
            val s = style?.trim().orEmpty()
            return if (s.isEmpty() || s.lowercase() in PLAIN_STYLES || variable) base else "$base $s"
        }
    }

    data class Rejected(val reason: FontRejection) : FontInspection
}

enum class FontFormat { TRUETYPE, OPENTYPE_CFF, COLLECTION }

/** Why a file was turned away — each is said differently to the person importing it. */
enum class FontRejection {
    /** Nothing in it. */
    EMPTY,

    /** Not a font at all: a text file, an image, a font with a broken header. */
    NOT_A_FONT,

    /** WOFF or WOFF2: a font packed for the web, which Android does not load. */
    WEB_FONT,

    /** An old Mac PostScript Type 1 font, which Android does not load either. */
    TYPE1,
}

private val PLAIN_STYLES = setOf("regular", "normal", "book", "roman", "plain", "standard")

/**
 * Says whether a file is a font Android can load, and what it is called.
 *
 * The old importer checked neither: it copied whatever the picker returned,
 * named it after the file, and gave it a `.ttf` suffix if it lacked one. A
 * text file became "notes.ttf", the app tried to render with it, and the
 * failure surfaced as a silent fall back to the default font with nothing in
 * the library to say why. And a font called "font_1728561234567" because the
 * picker reported no display name is a library entry nobody can recognise.
 *
 * This reads the sfnt structure directly (the OpenType spec's table directory
 * and `name` table), so it is pure and runs in a JVM test against the real
 * bundled fonts. It checks only what decides whether the platform can use the
 * file: a known signature, a table directory that fits inside the file, and
 * the tables a renderer cannot do without — `head`, `cmap`, and outlines in
 * either form (`glyf` or `CFF `/`CFF2`).
 */
object FontFileInspector {

    private const val SFNT_TRUETYPE = 0x00010000L
    private const val TAG_OTTO = 0x4F54544FL // 'OTTO' — CFF outlines
    private const val TAG_TRUE = 0x74727565L // 'true' — old Mac TrueType
    private const val TAG_TTCF = 0x74746366L // 'ttcf' — collection
    private const val TAG_WOFF = 0x774F4646L // 'wOFF'
    private const val TAG_WOF2 = 0x774F4632L // 'wOF2'
    private const val TAG_TYP1 = 0x74797031L // 'typ1'

    private const val MAX_TABLES = 512
    private const val MAX_NAME_RECORDS = 4096
    private const val MAX_NAME_LENGTH = 512

    fun inspect(file: File): FontInspection =
        runCatching { FileFontSource(file).use { inspect(it) } }
            .getOrElse { FontInspection.Rejected(FontRejection.NOT_A_FONT) }

    fun inspect(bytes: ByteArray): FontInspection = inspect(ByteArrayFontSource(bytes))

    fun inspect(source: FontSource): FontInspection {
        if (source.size == 0L) return FontInspection.Rejected(FontRejection.EMPTY)
        val signature = source.u32(0) ?: return FontInspection.Rejected(FontRejection.NOT_A_FONT)
        return when (signature) {
            TAG_WOFF, TAG_WOF2 -> FontInspection.Rejected(FontRejection.WEB_FONT)
            TAG_TYP1 -> FontInspection.Rejected(FontRejection.TYPE1)
            TAG_TTCF -> {
                // A collection: the platform opens its first face, so that is
                // the one that has to be sound and the one whose name counts.
                val count = source.u32(8) ?: return reject()
                if (count < 1 || count > 1024) return reject()
                val first = source.u32(12) ?: return reject()
                when (val face = inspectFace(source, first)) {
                    is FontInspection.Font -> face.copy(format = FontFormat.COLLECTION)
                    else -> face
                }
            }
            SFNT_TRUETYPE, TAG_OTTO, TAG_TRUE -> inspectFace(source, 0)
            else -> reject()
        }
    }

    private fun inspectFace(source: FontSource, start: Long): FontInspection {
        val version = source.u32(start) ?: return reject()
        if (version != SFNT_TRUETYPE && version != TAG_OTTO && version != TAG_TRUE) return reject()
        val numTables = source.u16(start + 4) ?: return reject()
        if (numTables < 1 || numTables > MAX_TABLES) return reject()

        val tables = HashMap<String, Pair<Long, Long>>(numTables * 2)
        for (i in 0 until numTables) {
            val rec = start + 12 + i * 16L
            val tagBytes = source.read(rec, 4) ?: return reject()
            val offset = source.u32(rec + 8) ?: return reject()
            val length = source.u32(rec + 12) ?: return reject()
            // A table that runs off the end of the file is a truncated or
            // damaged font, and loading it is how a render crashes later.
            if (offset + length > source.size) return reject()
            tables[String(tagBytes, Charsets.ISO_8859_1)] = offset to length
        }

        val hasOutlines = "glyf" in tables || "CFF " in tables || "CFF2" in tables
        if ("head" !in tables || "cmap" !in tables || !hasOutlines) return reject()

        val names = tables["name"]?.let { (offset, length) -> readNames(source, offset, length) }.orEmpty()
        return FontInspection.Font(
            family = names[16] ?: names[1],
            style = names[17] ?: names[2],
            variable = "fvar" in tables,
            format = if (version == TAG_OTTO) FontFormat.OPENTYPE_CFF else FontFormat.TRUETYPE,
        )
    }

    /**
     * The best string for each name id in the `name` table: Windows Unicode in
     * US English first (what nearly every font fills in), then Windows Unicode
     * in any language, then the Unicode platform, then Mac Roman.
     */
    private fun readNames(source: FontSource, tableOffset: Long, tableLength: Long): Map<Int, String> {
        val count = source.u16(tableOffset + 2) ?: return emptyMap()
        val storage = source.u16(tableOffset + 4) ?: return emptyMap()
        if (count > MAX_NAME_RECORDS) return emptyMap()
        val best = HashMap<Int, Pair<Int, String>>() // nameId -> (rank, text)
        for (i in 0 until count) {
            val rec = tableOffset + 6 + i * 12L
            val platform = source.u16(rec) ?: break
            val encoding = source.u16(rec + 2) ?: break
            val language = source.u16(rec + 4) ?: break
            val nameId = source.u16(rec + 6) ?: break
            if (nameId !in WANTED_IDS) continue
            val length = source.u16(rec + 8) ?: break
            val offset = source.u16(rec + 10) ?: break
            if (length == 0 || length > MAX_NAME_LENGTH) continue
            val at = tableOffset + storage + offset
            if (at + length > tableOffset + tableLength) continue
            val rank = when {
                platform == 3 && (encoding == 1 || encoding == 10) && language == 0x0409 -> 0
                platform == 3 && (encoding == 1 || encoding == 10) -> 1
                platform == 0 -> 2
                platform == 1 && encoding == 0 -> 3
                else -> continue
            }
            val current = best[nameId]
            if (current != null && current.first <= rank) continue
            val raw = source.read(at, length) ?: continue
            val text = if (rank == 3) String(raw, Charsets.ISO_8859_1) else String(raw, Charsets.UTF_16BE)
            val clean = text.filter { !it.isISOControl() }.trim()
            if (clean.isNotEmpty()) best[nameId] = rank to clean
        }
        return best.mapValues { it.value.second }
    }

    private val WANTED_IDS = setOf(1, 2, 16, 17)

    private fun reject() = FontInspection.Rejected(FontRejection.NOT_A_FONT)

    private fun FontSource.u16(offset: Long): Int? =
        read(offset, 2)?.let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }

    private fun FontSource.u32(offset: Long): Long? =
        read(offset, 4)?.let {
            ((it[0].toLong() and 0xFF) shl 24) or ((it[1].toLong() and 0xFF) shl 16) or
                ((it[2].toLong() and 0xFF) shl 8) or (it[3].toLong() and 0xFF)
        }
}
