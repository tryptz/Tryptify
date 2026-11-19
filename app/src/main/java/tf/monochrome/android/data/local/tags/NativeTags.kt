package tf.monochrome.android.data.local.tags

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue

/**
 * What the app's own tag reader found in one file (`cpp/tags/tag_reader.cpp`,
 * over TagLib): its stream, every text field by name, and its cover.
 *
 * [fields] are (name, value), names in upper case as TagLib's property map
 * gives them, whatever the format: "TITLE", "ALBUMARTIST", "TRACKNUMBER",
 * "DATE", "BPM", "INITIALKEY", "REPLAYGAIN_TRACK_GAIN". A field with several
 * values is several entries, in the file's order.
 */
internal class NativeTagData(
    val lengthMs: Int,
    val bitrateKbps: Int,
    val sampleRate: Int,
    val channels: Int,
    /** 0 where the format has none: a lossy file has no bit depth. */
    val bitsPerSample: Int,
    /** The file holds a cover, whether or not [picture] was asked for. */
    val hasPicture: Boolean,
    /** "MPEG", "FLAC", "MP4", "OGG_VORBIS", "OGG_OPUS", "WAV"…: see tag_reader.h. */
    val container: String,
    /** Where the container does not settle it: "AAC", "ALAC", "EAC3", "MP3"… */
    val codec: String,
    val fields: List<Pair<String, String>>,
    val picture: ByteArray?,
) {
    fun first(name: String): String? = fields.firstOrNull { it.first == name }?.second?.trim()?.takeIf { it.isNotEmpty() }

    fun first(vararg names: String): String? = names.firstNotNullOfOrNull { first(it) }

    /** Every value of [name], joined as a list reads: two artists are "A, B". */
    fun joined(name: String): String? {
        val values = fields.filter { it.first == name }.map { it.second.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (values.isEmpty()) null else values.joinToString(", ")
    }

    /** Name to first value, as [MusicalTags.from] reads a tag. */
    fun asMap(): Map<String, String> {
        val map = HashMap<String, String>(fields.size * 2)
        for ((name, value) in fields) if (value.isNotBlank()) map.putIfAbsent(name, value)
        return map
    }
}

/** The block of bytes the native reader returns, read back: its layout is in tag_reader.h. */
internal object NativeTagBlob {
    private const val MAGIC = 0x31475454
    private const val FLAG_HAS_PICTURE = 1

    /**
     * Null for a block that is not whole: never a half-read file. Text the
     * file called Latin-1 and that was neither that nor UTF-8 is read as
     * [LegacyText] decides, for [languages] (the phone's).
     */
    fun parse(blob: ByteArray, languages: List<String>, detector: LegacyText.Detector?): NativeTagData? = try {
        val b = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        if (b.int != MAGIC) {
            null
        } else {
            val lengthMs = b.int
            val bitrate = b.int
            val sampleRate = b.int
            val channels = b.int
            val bits = b.int
            val flags = b.int
            val container = b.text()
            val codec = b.text()
            val count = b.int
            require(count >= 0 && count <= b.remaining() / 8)
            val raw = ArrayList<Pair<String, String>>(count)
            repeat(count) { raw += b.text() to b.text() }
            b.text() // the cover's type: the bytes say what they are
            val pictureSize = b.int
            require(pictureSize >= 0 && pictureSize <= b.remaining())
            val picture = if (pictureSize == 0) null else ByteArray(pictureSize).also { b.get(it) }

            val fields = if (LegacyText.hasMarked(raw.map { it.second }) || LegacyText.hasMarked(raw.map { it.first })) {
                val charset = LegacyText.charsetFor(raw.map { it.second }, languages, detector)
                raw.map { (name, value) -> LegacyText.decode(name, charset) to LegacyText.decode(value, charset) }
            } else {
                raw
            }
            NativeTagData(
                lengthMs = lengthMs.coerceAtLeast(0),
                bitrateKbps = bitrate.coerceAtLeast(0),
                sampleRate = sampleRate.coerceAtLeast(0),
                channels = channels.coerceAtLeast(0),
                bitsPerSample = bits.coerceAtLeast(0),
                hasPicture = flags and FLAG_HAS_PICTURE != 0,
                container = container,
                codec = codec,
                fields = fields,
                picture = picture,
            )
        }
    } catch (_: RuntimeException) {
        // Cut short, or a length that runs past the end.
        null
    }

    private fun ByteBuffer.text(): String {
        val size = int
        require(size >= 0 && size <= remaining())
        val text = String(array(), arrayOffset() + position(), size, Charsets.UTF_8)
        position(position() + size)
        return text
    }
}

/**
 * Remembers which files were being read natively when the app died.
 *
 * The native reader runs inside the app's process, so a file that crashes it
 * crashes the app, and the scan at the next launch would read the same file
 * and crash again: an app that cannot be opened. Each read is noted in a
 * small file before it starts and struck out when it ends. A note still there
 * at the next start names a file that was being read when the process ended;
 * it is read by Android's reader from then on, which runs in another process.
 *
 * A note is a write into the app's own storage, a few microseconds, not a
 * sync to disk: the page cache outlives a crashed process, and a phone that
 * loses power was not crashed by a file.
 */
internal class NativeTagGuard(dir: File) {
    private val notes = File(dir, "tag_reads_in_flight")
    private val refusedFile = File(dir, "tag_reads_refused")
    private val refused: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val slots = ArrayBlockingQueue<Int>(SLOTS)
    private val file: RandomAccessFile?

    init {
        runCatching { dir.mkdirs() }
        runCatching { refusedFile.takeIf { it.isFile }?.readLines()?.filterTo(refused) { it.isNotBlank() } }
        val left = runCatching { leftOver() }.getOrDefault(emptyList())
        if (left.isNotEmpty()) {
            refused += left
            Log.w(TAG, "${left.size} file(s) were being read when the app last stopped; Android's reader takes them from now on")
            runCatching { refusedFile.writeText(refused.toList().takeLast(MAX_REFUSED).joinToString("\n", postfix = "\n")) }
        }
        file = runCatching {
            RandomAccessFile(notes, "rw").also { it.setLength(0); it.setLength(SLOTS.toLong() * SLOT_BYTES) }
        }.getOrNull()
        repeat(SLOTS) { slots.offer(it) }
    }

    fun isRefused(path: String): Boolean = path in refused

    /** Runs [read] with [path] noted for as long as it runs. */
    fun <T> noting(path: String, read: () -> T): T {
        val slot = slots.poll()
        val channel = file?.channel
        if (slot == null || channel == null) return read()
        val bytes = path.toByteArray(Charsets.UTF_8)
        // A path too long for a note is read unnoted rather than not at all.
        val noted = bytes.size <= SLOT_BYTES - 2 && runCatching {
            val note = ByteBuffer.allocate(2 + bytes.size).order(ByteOrder.LITTLE_ENDIAN)
            note.putShort(bytes.size.toShort()).put(bytes).flip()
            channel.write(note, slot.toLong() * SLOT_BYTES)
        }.isSuccess
        try {
            return read()
        } finally {
            if (noted) runCatching { channel.write(ByteBuffer.allocate(2), slot.toLong() * SLOT_BYTES) }
            slots.offer(slot)
        }
    }

    private fun leftOver(): List<String> {
        if (!notes.isFile) return emptyList()
        val all = notes.readBytes()
        val paths = ArrayList<String>()
        for (slot in 0 until SLOTS) {
            val at = slot * SLOT_BYTES
            if (at + 2 > all.size) break
            val size = (all[at].toInt() and 0xFF) or ((all[at + 1].toInt() and 0xFF) shl 8)
            if (size <= 0 || at + 2 + size > all.size) continue
            paths += String(all, at + 2, size, Charsets.UTF_8)
        }
        return paths
    }

    private companion object {
        const val TAG = "TagReader"
        const val SLOTS = 16
        const val SLOT_BYTES = 4096
        const val MAX_REFUSED = 500
    }
}

/**
 * The app's own tag reader. One call a file, in this process, with no
 * reflection anywhere on the way: nothing for R8 to rename or strip.
 */
internal object NativeTags {
    private const val TAG = "TagReader"

    @Volatile private var attempted = false
    @Volatile private var available = false

    fun ensureLoaded(): Boolean {
        if (attempted) return available
        synchronized(this) {
            if (attempted) return available
            available = try {
                System.loadLibrary("monochrome_tags")
                true
            } catch (error: Throwable) {
                runCatching { Log.w(TAG, "the app's tag reader is unavailable, Android's is used: ${error.message}") }
                false
            }
            attempted = true
        }
        return available
    }

    /**
     * [path] read, or null when the library is missing, the file was being
     * read at a crash ([guard]), or TagLib does not know the file.
     */
    fun read(
        path: String,
        wantPicture: Boolean,
        guard: NativeTagGuard?,
        languages: List<String>,
        detector: LegacyText.Detector?,
    ): NativeTagData? {
        if (!ensureLoaded()) return null
        if (guard?.isRefused(path) == true) return null
        val encoded = path.toByteArray(Charsets.UTF_8)
        val blob = try {
            if (guard != null) guard.noting(path) { nativeRead(encoded, wantPicture) } else nativeRead(encoded, wantPicture)
        } catch (_: OutOfMemoryError) {
            null
        } ?: return null
        return NativeTagBlob.parse(blob, languages, detector)
    }

    private external fun nativeRead(pathUtf8: ByteArray, wantPicture: Boolean): ByteArray?
}
