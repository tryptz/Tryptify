package tf.monochrome.android.data.downloads

import java.util.Locale

/**
 * Where a download lands inside the user's folder: `<Artist>/<Album>/` with
 * `NN. <Title>.<ext>` files and one `cover.jpg` per album — the layout Auxio,
 * Symfonium, Poweramp and desktop sync tools expect. Kept apart from the SAF
 * I/O in [TrackDownloader] so the naming can be unit-tested on the JVM.
 */
internal object DownloadPaths {

    /** Characters FAT/exFAT (SD cards) and the SAF providers reject in a name. */
    private val ILLEGAL = Regex("""[\\/:*?"<>|\x00-\x1F]""")

    /**
     * Long enough for any real album title, short enough that
     * `Artist/Album/NN. Title.flac` stays well under the 255-byte name limit.
     */
    private const val MAX_SEGMENT_LENGTH = 120

    /** The `NN. ` / `D-NN. ` prefix [trackFileStem] puts on a file name. */
    private val TRACK_PREFIX = Regex("""^(\d+-)?\d{1,3}\.\s+""")

    /**
     * One path segment made safe for every provider. Trailing dots and spaces
     * go too: FAT drops them silently, so a folder created as "Vol." would come
     * back as "Vol" and never be found again — each download would then make a
     * fresh "Vol (1)", "Vol (2)"… alongside it.
     */
    fun sanitize(raw: String?, fallback: String): String {
        // Whitespace first: tabs and newlines are control characters too, and
        // should become a space, not an underscore.
        val cleaned = raw.orEmpty()
            .replace(Regex("""\s+"""), " ")
            .replace(ILLEGAL, "_")
            .trim()
            .take(MAX_SEGMENT_LENGTH)
            .trimEnd('.', ' ')
        return cleaned.ifEmpty { fallback }
    }

    /**
     * The album artist, so a record with featured guests stays in one folder
     * instead of splitting across every track's credit.
     */
    fun artistFolder(item: DownloadItem): String =
        sanitize(item.albumArtist?.takeIf { it.isNotBlank() } ?: item.artistName, "Unknown Artist")

    /**
     * A track with no album is a single, and gets a folder named after itself
     * — a shared "Unknown Album" would put every single's cover in contention
     * for one `cover.jpg`, the bug this layout exists to fix.
     */
    fun albumFolder(item: DownloadItem): String =
        sanitize(item.albumTitle?.takeIf { it.isNotBlank() } ?: item.title, "Unknown Album")

    /**
     * File name without extension: `01. Title`, or `2-01. Title` from the
     * second disc on, so a multi-disc album sorts in play order in one folder.
     * Disc sub-folders were the alternative, but a download can't know whether
     * disc 1 has siblings, and a `Disc 2/` folder would sit out of reach of the
     * album's `cover.jpg`. Without a track number it is the bare title.
     */
    fun trackFileStem(item: DownloadItem): String {
        val track = item.trackNumber?.takeIf { it > 0 }
        val disc = item.discNumber?.takeIf { it > 1 }
        val prefix = when {
            track == null -> ""
            disc != null -> String.format(Locale.US, "%d-%02d. ", disc, track)
            else -> String.format(Locale.US, "%02d. ", track)
        }
        return sanitize(prefix + item.title, "Unknown Track")
    }

    /** The title back out of a [trackFileStem] name, for files found on disk. */
    fun titleFromStem(stem: String): String =
        stem.replaceFirst(TRACK_PREFIX, "").ifBlank { stem }
}
