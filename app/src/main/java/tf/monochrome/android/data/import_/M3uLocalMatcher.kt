package tf.monochrome.android.data.import_

import tf.monochrome.android.player.LocalTrackMatching
import java.text.Normalizer
import java.util.Locale

/**
 * Finds each song of an M3U playlist among the listener's own music
 * (issue #155).
 *
 * A playlist names files, and the file it names is rarely where this phone
 * has it: the list was written on a computer (`D:\Music\…`), on another
 * phone, relative to its own folder, or for a copy in another format. So a
 * song is looked for the way a person would, most certain first:
 *
 *  1. **The path itself**, as written, then cleaned up ([cleanLocation]):
 *     under the playlist's own folder when the path is relative and that
 *     folder is known, and under the phone's storage by whichever of its
 *     names the list used (`/sdcard`, `/storage/emulated/0`). Upper and lower
 *     case may differ.
 *  2. **The end of the path.** The file name, and as many of the folders
 *     above it as agree: `Queen/A Night at the Opera/11 Bohemian
 *     Rhapsody.flac` finds that file whatever comes before `Queen`. With no
 *     file of that name, the same without the extension, for a library kept
 *     as MP3 of a playlist written for FLAC.
 *  3. **The `#EXTINF` line**, "Artist - Title", when no file has the name at
 *     all: the title and an artist have to agree, and the length when the
 *     line gives one ([LocalTrackMatching], the rules a streamed song is
 *     matched to a file by).
 *
 * A wrong song in a playlist is worse than a missing one, which the import
 * reports by name. So wherever two files fit equally well and are not plainly
 * copies of one recording, the song is left unmatched rather than guessed;
 * and a file found by its name alone, under none of the folders the list
 * names, is refused when the line's length says it is another recording.
 *
 * Pure, no Android: [M3uLocalMatcherTest].
 */
class M3uLocalMatcher(
    files: List<LocalFile>,
    /** The folder the playlist file is in, when the picker says ("/storage/emulated/0/Playlists"). */
    playlistDir: String? = null,
) {
    /** One file of the library: what the matcher needs of a `local_tracks` row. */
    data class LocalFile(
        val id: Long,
        val path: String,
        val title: String,
        val artist: String,
        val durationSeconds: Int,
    )

    private class Indexed(val file: LocalFile) {
        /** The path's parts, folded: ["storage", "emulated", "0", "music", "a.flac"]. */
        val segments: List<String> = segmentsOf(file.path).map(::fold)
        val title: String = LocalTrackMatching.normalize(LocalTrackMatching.baseTitle(file.title))
        val artists: Set<String> = LocalTrackMatching.artistParts(file.artist)
    }

    // By path order, so that "the first of several copies" is the same one every time.
    private val all: List<Indexed> = files.sortedBy { it.path }.map(::Indexed)
    private val byPath: Map<String, LocalFile> =
        all.associateByFirst { nfc(it.file.path) }.mapValues { it.value.file }
    private val byFoldedPath: Map<String, List<Indexed>> = all.groupBy { "/" + it.segments.joinToString("/") }
    private val byName: Map<String, List<Indexed>> =
        all.filter { it.segments.isNotEmpty() }.groupBy { it.segments.last() }
    private val byStem: Map<String, List<Indexed>> =
        all.filter { it.segments.isNotEmpty() }.groupBy { stemOf(it.segments.last()) }
    private val byTitle: Map<String, List<Indexed>> = all.filter { it.title.isNotEmpty() }.groupBy { it.title }

    private val playlistDirSegments: List<String>? =
        playlistDir?.takeIf { it.startsWith("/") }?.let { resolve(segmentsOf(it)) }

    /** The library file [entry] is, or null: not there, not a file, or not certain enough. */
    fun match(entry: M3uEntry): LocalFile? {
        // Exactly as written, before anything is cleaned: a path that is right is right.
        byPath[nfc(entry.location.trim())]?.let { return it }

        val location = cleanLocation(entry.location) ?: return null
        val absolute = location.startsWith("/")
        val written = segmentsOf(location)
        if (written.isEmpty()) return null

        // 1. The path, where the list says it is.
        val places = buildList {
            if (absolute) {
                val path = resolve(written)
                add(path)
                internalStorageAlias(path)?.let(::add)
            } else if (playlistDirSegments != null) {
                val path = resolve(playlistDirSegments + written)
                add(path)
                internalStorageAlias(path)?.let(::add)
            }
        }
        for (place in places) {
            byPath["/" + place.joinToString("/")]?.let { return it }
            byFoldedPath["/" + place.joinToString("/") { fold(it) }]?.let { same ->
                // Two files whose paths differ only by case: the list does not say which.
                if (same.size == 1) return same[0].file
            }
        }

        // 2. The end of the path. Leading ".." say nothing without the folder they start from.
        val tail = resolve(written.dropWhile { it == ".." }).map(::fold)
        if (tail.isNotEmpty()) {
            val named = byName[tail.last()]
            val candidates = named ?: byStem[stemOf(tail.last())]
            if (candidates != null) {
                bestByPathEnd(tail, candidates, byStemOnly = named == null, entry)?.let { return it }
            }
        }

        // 3. No file of that name, or none that is certainly it: what the
        // line says the song is, anywhere in the library.
        return byDescription(entry, all = null)
    }

    private fun bestByPathEnd(
        tail: List<String>,
        candidates: List<Indexed>,
        byStemOnly: Boolean,
        entry: M3uEntry,
    ): LocalFile? {
        val scored = candidates.map { it to sharedEnd(tail, it.segments, byStemOnly) }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }.map { it.first }
        if (top.size == 1) {
            val only = top[0]
            // Found by its file name and nothing above it: "01.mp3" is in many
            // albums. The line's length, when it has one, has to allow it.
            if (best == 1 && !lengthAllows(entry, only)) return null
            return only.file
        }
        // Several files end the same way. The line may say which; failing
        // that, they are one recording kept twice, or the song is not certain.
        return byDescription(entry, top) ?: top.takeIf(::areCopies)?.first()?.file
    }

    /**
     * The file the `#EXTINF` text describes, among [all] (the whole library
     * when null): by artist and title, or by the title alone when the text
     * does not split into the two. One file, or copies of one recording;
     * otherwise null.
     */
    private fun byDescription(entry: M3uEntry, all: List<Indexed>?): LocalFile? {
        val display = entry.display?.takeIf { it.isNotBlank() } ?: return null
        fun titled(title: String): List<Indexed> {
            val key = LocalTrackMatching.normalize(LocalTrackMatching.baseTitle(title))
            if (key.isEmpty()) return emptyList()
            val pool = all?.filter { it.title == key } ?: byTitle[key].orEmpty()
            return pool.filter { lengthAllows(entry, it) }
        }
        // "Artist - Title", the convention; the title may itself hold " - ".
        val dash = display.indexOf(" - ")
        if (dash > 0) {
            val artists = LocalTrackMatching.artistParts(display.substring(0, dash))
            val hits = titled(display.substring(dash + 3)).filter { file -> file.artists.any { it in artists } }
            pick(hits)?.let { return it }
            if (hits.isNotEmpty()) return null
        }
        // The whole text as a title: a line with no artist, or a title with a dash in it.
        return pick(titled(display))
    }

    private fun pick(hits: List<Indexed>): LocalFile? = when {
        hits.isEmpty() -> null
        hits.size == 1 || areCopies(hits) -> hits[0].file
        else -> null
    }

    /** The line's length does not rule [candidate] out; a line or a file without a length rules out nothing. */
    private fun lengthAllows(entry: M3uEntry, candidate: Indexed): Boolean =
        LocalTrackMatching.durationsAgree(entry.durationSeconds ?: 0, candidate.file.durationSeconds)

    /** One recording kept more than once (on the phone and the card, as FLAC and MP3): same title, artist and length. */
    private fun areCopies(files: List<Indexed>): Boolean {
        val first = files.first()
        return files.all {
            it.title == first.title && it.artists == first.artists &&
                first.file.durationSeconds > 0 && it.file.durationSeconds > 0 &&
                LocalTrackMatching.durationsAgree(it.file.durationSeconds, first.file.durationSeconds)
        }
    }

    companion object {
        /**
         * The path an entry names, with forward slashes, or null for an
         * address that is not a file on this device (a radio stream's
         * `http://…`, a `content://…`).
         *
         * A `file:` address loses its scheme and its percent-encoding. A
         * Windows path loses its backslashes and its drive letter, which no
         * phone has: `D:\Music\a.mp3` is `/Music/a.mp3`, an absolute path that
         * will be found by its end.
         */
        internal fun cleanLocation(raw: String): String? {
            var path = raw.trim()
            if (path.length >= 2 && path.first() == '"' && path.last() == '"') path = path.substring(1, path.length - 1).trim()
            if (path.isEmpty()) return null
            val fileUrl = FILE_URL.find(path)
            if (fileUrl != null) {
                path = percentDecoded(path.substring(fileUrl.value.length))
                // After "//" comes a host ("file://localhost/…"), which says
                // nothing about where the file is here: dropped.
                if (fileUrl.groupValues[1].isNotEmpty() && !path.startsWith("/")) {
                    val slash = path.indexOf('/')
                    if (slash < 0) return null
                    path = path.substring(slash)
                }
                if (!path.startsWith("/")) path = "/$path"
            } else if (ADDRESS.containsMatchIn(path)) {
                return null
            }
            path = path.replace('\\', '/')
            // "C:/Music" and, from a file: address, "/C:/Music".
            DRIVE.find(path)?.let { path = "/" + path.substring(it.value.length) }
            return nfc(path).takeIf { it.isNotBlank() }
        }

        /** "file:", then "//" and a host or nothing: "file:///a", "file://localhost/a", "file:/a". */
        private val FILE_URL = Regex("""^file:(//)?""", RegexOption.IGNORE_CASE)

        /** Any other "scheme://". Two letters at least, so "C://x" is a drive. */
        private val ADDRESS = Regex("""^[A-Za-z][A-Za-z0-9+.\-]+://""")
        private val DRIVE = Regex("""^/?[A-Za-z]:(/|$)""")

        /** "%20" and the like, as UTF-8; a "%" that is not followed by two hex digits is itself. */
        internal fun percentDecoded(text: String): String {
            if ('%' !in text) return text
            val out = java.io.ByteArrayOutputStream(text.length)
            val bytes = text.toByteArray(Charsets.UTF_8)
            var i = 0
            while (i < bytes.size) {
                val b = bytes[i]
                if (b == '%'.code.toByte() && i + 2 < bytes.size) {
                    val hi = Character.digit(bytes[i + 1].toInt().toChar(), 16)
                    val lo = Character.digit(bytes[i + 2].toInt().toChar(), 16)
                    if (hi >= 0 && lo >= 0) {
                        out.write(hi * 16 + lo)
                        i += 3
                        continue
                    }
                }
                out.write(b.toInt())
                i++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }

        /** A path's parts, without the empty ones and the "." ones: "/a//b/./c" is [a, b, c]. */
        internal fun segmentsOf(path: String): List<String> =
            path.split('/').filter { it.isNotEmpty() && it != "." }

        /** [segments] with each ".." taking back the part before it; one with nothing before it stays. */
        internal fun resolve(segments: List<String>): List<String> {
            val out = ArrayList<String>(segments.size)
            for (segment in segments) {
                if (segment == ".." && out.isNotEmpty() && out.last() != "..") out.removeAt(out.size - 1)
                else out.add(segment)
            }
            return out
        }

        /**
         * The phone's own storage has several names, and a list may use any:
         * [path] under one of the others as the same path under
         * `/storage/emulated/0`, which is the one the library is scanned by.
         * Null when it is under none of them.
         */
        internal fun internalStorageAlias(path: List<String>): List<String>? {
            for (alias in INTERNAL_STORAGE_ALIASES) {
                if (path.size > alias.size && path.subList(0, alias.size) == alias) {
                    return INTERNAL_STORAGE + path.subList(alias.size, path.size)
                }
            }
            return null
        }

        private val INTERNAL_STORAGE = listOf("storage", "emulated", "0")
        private val INTERNAL_STORAGE_ALIASES = listOf(
            listOf("sdcard"),
            listOf("mnt", "sdcard"),
            listOf("storage", "sdcard0"),
            listOf("storage", "self", "primary"),
            listOf("storage", "emulated", "legacy"),
            listOf("data", "media", "0"),
        )

        /**
         * How many parts [wanted] and [have] share, counted from the file
         * name back; 0 when the file names differ. With [byStem], the file
         * names are compared without their extensions.
         */
        internal fun sharedEnd(wanted: List<String>, have: List<String>, byStem: Boolean = false): Int {
            if (wanted.isEmpty() || have.isEmpty()) return 0
            val sameName = if (byStem) stemOf(wanted.last()) == stemOf(have.last()) else wanted.last() == have.last()
            if (!sameName) return 0
            var shared = 1
            while (shared < wanted.size && shared < have.size &&
                wanted[wanted.size - 1 - shared] == have[have.size - 1 - shared]
            ) shared++
            return shared
        }

        /** A file name without its extension; a name that is all extension (".nomedia") is itself. */
        internal fun stemOf(name: String): String {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) else name
        }

        /**
         * One spelling for comparing: composed accents (a list written on a
         * Mac spells "é" as two characters, Android's file names as one) and
         * lower case.
         */
        internal fun fold(text: String): String = nfc(text).lowercase(Locale.ROOT)

        private fun nfc(text: String): String =
            if (Normalizer.isNormalized(text, Normalizer.Form.NFC)) text else Normalizer.normalize(text, Normalizer.Form.NFC)

        /** [associateBy], but the first value of a key stays. */
        private inline fun <T, K> Iterable<T>.associateByFirst(key: (T) -> K): Map<K, T> {
            val out = LinkedHashMap<K, T>()
            for (item in this) out.getOrPut(key(item)) { item }
            return out
        }
    }
}
