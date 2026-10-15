package tf.monochrome.android.data.preferences

/**
 * Which marks a song row carries beside its artist and album (Settings ›
 * Appearance › Display › Song row badges). Every one costs the line width:
 * with a source pill, a quality label and the duration on it, "Arctic
 * Monkeys • Why'd You Only Call Me When You're High?" came out as "Arctic
 * Monke… • Why'd You On…". Each can be turned off, or all of them.
 */
data class RowBadges(
    /** Where it plays from: Local, Qobuz, TIDAL. */
    val source: Boolean = true,
    /** Its quality: Lossless, FLAC, Hi-Res. */
    val quality: Boolean = true,
    /** Its format: Dolby Atmos, THX, 5.1. */
    val format: Boolean = true,
    /** On this device, or downloading. */
    val download: Boolean = true,
    /** Its length. */
    val duration: Boolean = true,
) {
    /** Whether any of them shows. */
    val any: Boolean get() = source || quality || format || download || duration

    fun with(badge: RowBadge, shown: Boolean): RowBadges = when (badge) {
        RowBadge.SOURCE -> copy(source = shown)
        RowBadge.QUALITY -> copy(quality = shown)
        RowBadge.FORMAT -> copy(format = shown)
        RowBadge.DOWNLOAD -> copy(download = shown)
        RowBadge.DURATION -> copy(duration = shown)
    }

    fun shows(badge: RowBadge): Boolean = when (badge) {
        RowBadge.SOURCE -> source
        RowBadge.QUALITY -> quality
        RowBadge.FORMAT -> format
        RowBadge.DOWNLOAD -> download
        RowBadge.DURATION -> duration
    }

    companion object {
        val NONE = RowBadges(source = false, quality = false, format = false, download = false, duration = false)
    }
}

/** One kind of [RowBadges] mark. */
enum class RowBadge { SOURCE, QUALITY, FORMAT, DOWNLOAD, DURATION }
