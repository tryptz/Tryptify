package tf.monochrome.android.data.downloads

/**
 * The pure decisions behind the tags [TrackDownloader] embeds in a download —
 * kept apart from the file I/O so they can be unit-tested on the JVM.
 */
internal object EmbeddedTags {

    /**
     * Largest cover worth embedding. The FLAC picture block's length field is
     * 24 bits (16 MiB hard cap), but every track of an album carries its own
     * copy and players load the block whole, so a huge "max" scan costs far
     * more than it shows.
     */
    const val MAX_EMBEDDED_ART_BYTES = 4 * 1024 * 1024

    private val DATE_PREFIX = Regex("""^\d{4}(-\d{2}(-\d{2})?)?""")

    /**
     * The Vorbis DATE value for a catalogue release date: its leading
     * `yyyy`, `yyyy-MM` or `yyyy-MM-dd`. Anything after that (a time, a zone)
     * is dropped, since players parse DATE strictly. Null when there is no
     * usable year — an absent DATE sorts better than a wrong one.
     */
    fun releaseDate(raw: String?): String? {
        val date = raw?.trim()?.let { DATE_PREFIX.find(it)?.value } ?: return null
        return date.takeUnless { it.startsWith("0000") }
    }

    /**
     * The download's title without the " — <version>" suffix the app adds for
     * display; the version travels in its own VERSION tag instead.
     */
    fun baseTitle(title: String, version: String?): String =
        if (version.isNullOrBlank()) title else title.removeSuffix(" — $version").trim()

    /**
     * MIME type of a cover image from its magic bytes, or null for anything
     * other than JPEG or PNG — the only two picture formats players reliably
     * decode out of a FLAC.
     */
    fun imageMime(bytes: ByteArray): String? = when {
        bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() ->
            "image/jpeg"
        bytes.size >= 8 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() &&
            bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte() ->
            "image/png"
        else -> null
    }
}
