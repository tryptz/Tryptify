package tf.monochrome.android.data.local.tags

import java.io.File
import java.util.Locale

/**
 * What an audio file says about itself, for the song menu's Track info: its
 * stream (format, rate, depth, bitrate, channels, length) and every tag in it,
 * by name. Read on demand for one file, never during a scan.
 */
data class FileTags(
    val path: String,
    val sizeBytes: Long,
    val format: String?,
    val sampleRateHz: Int?,
    val bitsPerSample: Int?,
    val bitrateKbps: Long?,
    val channels: String?,
    val lengthSeconds: Double?,
    val lossless: Boolean?,
    /** Embedded pictures (covers), counted rather than shown. */
    val pictures: Int,
    /** Each tag as (name, value), the well-known ones first and in a fixed order. */
    val tags: List<Pair<String, String>>,
)

object FileTagsReader {

    /** jaudiotagger's formats, WAV included: it reads WAV's INFO and ID3 chunks too. */
    private val READABLE = TagReader.JAUDIOTAGGER_EXTENSIONS + "wav"

    /**
     * [path]'s stream and tags, or null when there is no such file. A file
     * jaudiotagger cannot open still gives its size and what its extension
     * says. [path] without a known extension (the play cache's ".bin") is
     * recognised by its first bytes ([sniff]).
     */
    fun read(path: String): FileTags? {
        val file = File(path)
        if (!file.isFile) return null
        val named = file.extension.lowercase(Locale.ROOT)
        val ext = if (named in READABLE || named in TagReader.OGG_EXTENSIONS) named else sniff(file) ?: named
        if (ext in TagReader.OGG_EXTENSIONS) return readOgg(file, ext)
        val audio = runCatching {
            if (ext == named) org.jaudiotagger.audio.AudioFileIO.read(file)
            else org.jaudiotagger.audio.AudioFileIO.readAs(file, ext)
        }.getOrNull() ?: return FileTags(
            path = path, sizeBytes = file.length(), format = ext.uppercase(Locale.ROOT).ifEmpty { null },
            sampleRateHz = null, bitsPerSample = null, bitrateKbps = null, channels = null,
            lengthSeconds = null, lossless = null, pictures = 0, tags = emptyList(),
        )
        val header = runCatching { audio.audioHeader }.getOrNull()
        val tag = runCatching { audio.tag }.getOrNull()
        val fromTags = FileTags(
            path = path,
            sizeBytes = file.length(),
            format = header?.let { runCatching { it.format }.getOrNull() }?.takeIf { it.isNotBlank() }
                ?: ext.uppercase(Locale.ROOT).ifEmpty { null },
            sampleRateHz = header?.let { runCatching { it.sampleRateAsNumber }.getOrNull() }?.takeIf { it > 0 },
            bitsPerSample = header?.let { runCatching { it.bitsPerSample }.getOrNull() }?.takeIf { it > 0 },
            bitrateKbps = header?.let { runCatching { it.bitRateAsNumber }.getOrNull() }?.takeIf { it > 0 },
            channels = header?.let { runCatching { it.channels }.getOrNull() }?.takeIf { it.isNotBlank() },
            lengthSeconds = header?.let { runCatching { it.preciseTrackLength }.getOrNull() }?.takeIf { it > 0 },
            lossless = header?.let { runCatching { it.isLossless }.getOrNull() },
            pictures = tag?.let { runCatching { it.artworkList.size }.getOrNull() } ?: 0,
            tags = tag?.let(::tagsOf).orEmpty(),
        )
        // MP4's stream facts come from its own sample entry: jaudiotagger read a
        // 5.1 AAC file as "-14" channels and 16-bit, and ALAC as AAC.
        return if (ext in MP4_EXTENSIONS) Mp4AudioInfo.read(file)?.let { withStream(fromTags, it) } ?: fromTags
        else fromTags
    }

    private val MP4_EXTENSIONS = setOf("m4a", "m4b", "mp4", "m4p", "alac")

    /** [tags] with its stream facts from [mp4], where the sample entry has them. */
    internal fun withStream(tags: FileTags, mp4: Mp4Audio): FileTags = tags.copy(
        format = if (mp4.atmos) "${mp4.codec} · Dolby Atmos" else mp4.codec,
        sampleRateHz = mp4.sampleRateHz ?: tags.sampleRateHz,
        // Lossy audio has no bit depth: whatever jaudiotagger reported is not one.
        bitsPerSample = if (mp4.lossless) mp4.bitsPerSample ?: tags.bitsPerSample else null,
        bitrateKbps = mp4.bitrateBps?.let { (it + 500) / 1000 }?.takeIf { it > 0 } ?: tags.bitrateKbps,
        channels = mp4.channels?.let(::channelLabel) ?: tags.channels?.takeIf { it.toIntOrNull()?.let { n -> n > 0 } ?: true },
        lengthSeconds = mp4.durationSeconds?.takeIf { it > 0 } ?: tags.lengthSeconds,
        lossless = mp4.lossless,
    )

    /** "2", "6 (5.1)", "8 (7.1)": the count, and the layout where it names one. */
    internal fun channelLabel(count: Int): String = when (count) {
        3 -> "3 (2.1)"
        6 -> "6 (5.1)"
        7 -> "7 (6.1)"
        8 -> "8 (7.1)"
        12 -> "12 (7.1.4)"
        else -> count.toString()
    }

    /**
     * The format of a file without a telling extension, from its first bytes:
     * the play cache saves every stream as ".bin". jaudiotagger's own
     * readMagic does not know WAV, so it is not asked.
     */
    internal fun sniff(file: File): String? {
        val head = ByteArray(12)
        val n = runCatching { file.inputStream().use { it.read(head) } }.getOrDefault(-1)
        if (n < 4) return null
        fun at(i: Int, s: String) = n >= i + s.length && s.indices.all { head[i + it] == s[it].code.toByte() }
        return when {
            at(0, "fLaC") -> "flac"
            at(0, "ID3") -> "mp3"
            (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> "mp3"
            at(4, "ftyp") -> "m4a"
            at(0, "RIFF") && at(8, "WAVE") -> "wav"
            at(0, "FORM") && (at(8, "AIFF") || at(8, "AIFC")) -> "aiff"
            at(0, "OggS") -> "ogg"
            else -> null
        }
    }

    /** Opus and Vorbis: their comments by name, which is all they carry for this. */
    private fun readOgg(file: File, ext: String): FileTags = FileTags(
        path = file.path,
        sizeBytes = file.length(),
        format = if (ext == "opus") "Opus" else "Ogg Vorbis",
        sampleRateHz = null, bitsPerSample = null, bitrateKbps = null, channels = null,
        lengthSeconds = null, lossless = false, pictures = 0,
        tags = OggComments.read(file).orEmpty().entries
            .sortedBy { it.key }
            .map { (name, value) -> readableName(name) to value },
    )

    /**
     * Every tag in [tag]: the fields jaudiotagger knows by name (title, album
     * artist, MusicBrainz ids…), then any other text field it holds — an ID3
     * TXXX by its description, an MP4 "----" atom by its name, a Vorbis
     * comment by its own — unless its value is already listed.
     */
    internal fun tagsOf(tag: org.jaudiotagger.tag.Tag): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val shown = HashSet<String>()
        for (key in org.jaudiotagger.tag.FieldKey.entries) {
            if (key == org.jaudiotagger.tag.FieldKey.COVER_ART) continue
            val values = runCatching { tag.getAll(key) }.getOrNull().orEmpty()
                .filter { it.isNotBlank() }
            if (values.isEmpty()) continue
            val value = values.joinToString(" / ")
            out += readableName(key.name) to value
            shown += value
            values.forEach { shown += it }
        }
        for ((name, value) in tagTextFields(tag)) {
            if (value in shown) continue
            out += readableName(name) to value
            shown += value
        }
        return out
    }

    /** "ALBUM_ARTIST" or "REPLAYGAIN_TRACK_GAIN" as "Album artist", "Replaygain track gain". */
    internal fun readableName(name: String): String =
        name.trim().replace('_', ' ').lowercase(Locale.ROOT)
            .replaceFirstChar { it.titlecase(Locale.ROOT) }
}
