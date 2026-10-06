package tf.monochrome.android.data.downloads

/**
 * Where a download goes inside the user's download folder, and how to read
 * that back.
 *
 *     <folder>/<Album artist>/<Album>/01. Title.flac
 *     <folder>/<Album artist>/<Album>/cover.jpg
 *     <folder>/<Album artist>/<Album>/Disc 2/01. Title.flac
 *     <folder>/<Album artist>/<Album>/Disc 2/cover.jpg
 *     <folder>/<Artist>/Title.flac                         (no album known)
 *
 * Everything used to land flat in the folder as "Artist - Title.flac". Besides
 * the clutter, the folder had exactly one cover.jpg, claimed by whichever album
 * was downloaded first, and Android's media scanner then gave that cover to
 * every song in the folder.
 *
 * Disc 2 onwards gets a "Disc N" folder inside the album's, with a cover.jpg of
 * its own, because Android reads folder art only from the file's own folder.
 * Disc 1 stays in the album folder: a download does not know how many discs
 * its album has, so it cannot tell a single-disc album from the first disc of
 * several. The disc number in the file name instead ("2-01") was considered
 * and rejected: it sorts before track 20 of disc 1, and a numeric-aware file
 * manager sorts it between "02" and "03".
 *
 * A track with no album goes straight into its artist's folder and never gets
 * a cover.jpg there. A shared folder of singles would bring back the
 * first-cover-wins problem this replaces.
 */
internal object DownloadLayout {

    data class Target(
        val artistFolder: String,
        /** Null when the track has no album: it sits in [artistFolder] itself. */
        val albumFolder: String?,
        /** "Disc 2" and on, inside [albumFolder]; null for disc 1 or no album. */
        val discFolder: String?,
        /** File name without extension, shared by the audio and its .lrc. */
        val stem: String,
    )

    data class Description(val artist: String, val album: String?, val title: String)

    fun target(
        title: String,
        artistName: String,
        albumArtist: String?,
        albumTitle: String?,
        trackNumber: Int?,
        discNumber: Int?,
    ): Target {
        // The album artist keeps an album with guest artists, or a compilation,
        // in one folder rather than one per credited artist.
        val artist = albumArtist?.takeIf { it.isNotBlank() } ?: artistName
        val album = albumTitle?.takeIf { it.isNotBlank() }?.let { sanitize(it, fallback = UNKNOWN_ALBUM) }
        return Target(
            artistFolder = sanitize(artist, fallback = UNKNOWN_ARTIST),
            albumFolder = album,
            discFolder = discNumber?.takeIf { it > 1 && album != null }?.let { "Disc $it" },
            stem = sanitize(trackPrefix(trackNumber) + title, fallback = UNKNOWN_TITLE),
        )
    }

    /** "01. ", or nothing when the number is unknown. */
    fun trackPrefix(trackNumber: Int?): String {
        val track = trackNumber?.takeIf { it > 0 } ?: return ""
        return track.toString().padStart(2, '0') + ". "
    }

    /**
     * A name every Android storage backend accepts: none of the characters FAT
     * and the storage providers refuse, no control characters, no trailing dot
     * or space (FAT drops them, so the name read back would not match), and at
     * most [MAX_NAME_BYTES] of UTF-8. The limit leaves room for an extension
     * and the provider's " (1)" under the 255-byte limit of the filesystems
     * underneath. A name with nothing left becomes [fallback].
     */
    fun sanitize(name: String, fallback: String): String {
        val cleaned = name
            .replace(WHITESPACE, " ")
            .replace(ILLEGAL, "_")
            .trim()
            .trimEnd('.', ' ')
        return truncateUtf8(cleaned, MAX_NAME_BYTES).trimEnd('.', ' ').ifEmpty { fallback }
    }

    /**
     * What a file found in the download folder probably is, from where it sits:
     * [folders] runs from the download folder down to the file's own folder.
     * Used for files that are on disk with no database row, which after a
     * reinstall includes this app's own downloads.
     */
    fun describe(folders: List<String>, fileName: String): Description {
        val stem = fileName.substringBeforeLast('.', fileName)
        if (folders.isEmpty()) {
            // The old flat layout: "Artist - Title".
            val parts = stem.split(" - ", limit = 2)
            return if (parts.size == 2) Description(parts[0], null, parts[1])
            else Description("", null, stem)
        }
        return Description(
            artist = folders[0],
            album = folders.getOrNull(1),
            title = stem.replaceFirst(TRACK_PREFIX, "").ifEmpty { stem },
        )
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < s.length) {
            val cp = s.codePointAt(end)
            val size = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (bytes + size > maxBytes) break
            bytes += size
            end += Character.charCount(cp)
        }
        return s.substring(0, end)
    }

    const val UNKNOWN_ARTIST = "Unknown Artist"
    const val UNKNOWN_ALBUM = "Unknown Album"
    const val UNKNOWN_TITLE = "Untitled"

    /** Leaves room under 255 bytes for ".flac" and the provider's " (1)". */
    private const val MAX_NAME_BYTES = 200

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\x00-\\x1F\\x7F]")
    private val WHITESPACE = Regex("\\s+")
    private val TRACK_PREFIX = Regex("^(\\d+-)?\\d+\\. ")
}
