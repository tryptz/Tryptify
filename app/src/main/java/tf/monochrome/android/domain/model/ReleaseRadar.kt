package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable
import tf.monochrome.android.data.charts.normalizeForMatch
import java.time.LocalDate

/**
 * One release on the radar, kept small enough to store: the radar is cached
 * on disk between launches, and an album page is reached by [albumId] alone.
 */
@Serializable
data class RadarRelease(
    val albumId: Long,
    val title: String,
    /** The artist the listener plays, as they play it. */
    val artist: String,
    val cover: String? = null,
    /** Release day, as an epoch day. */
    val day: Long,
    /** ALBUM, EP or SINGLE, when the catalogue says. */
    val type: String? = null,
)

/**
 * Release radar: new releases from the artists the listener plays.
 *
 * Its whole value is being right about two things, so those are the rules
 * here. *By the artist*: an artist's discography carries compilations they
 * merely appear on, and "Various Artists — Trance Hits 2025" is not a new
 * release from anyone. *New*: dated within [WINDOW_DAYS] and not in the
 * future — a pre-order is not out yet. Re-issues of one release (deluxe,
 * explicit, remastered) count once, under the newest date.
 */
object ReleaseRadar {

    /** How far back a release still counts as new. */
    const val WINDOW_DAYS = 60

    /** On a first visit, releases this recent wear the NEW badge. */
    const val FIRST_VISIT_NEW_DAYS = 14

    /** At most this many on the row. */
    const val LIMIT = 20

    /**
     * A catalogue release date as a day, or null. Accepts `2025-05-17` and
     * timestamps that start with one. A bare year places nothing within a
     * sixty-day window, so it is null rather than the first of January.
     */
    fun parseDay(raw: String?): LocalDate? {
        val text = raw?.trim()?.take(10) ?: return null
        if (text.length < 10) return null
        return runCatching { LocalDate.parse(text) }.getOrNull()
    }

    /**
     * The releases to show, newest first.
     *
     * [byArtist] is each played artist's name with their discography.
     */
    fun select(
        byArtist: Map<String, List<Album>>,
        today: LocalDate,
        limit: Int = LIMIT,
    ): List<RadarRelease> {
        val earliest = today.minusDays(WINDOW_DAYS.toLong())
        val byEdition = LinkedHashMap<String, RadarRelease>()
        for ((artist, albums) in byArtist) {
            val wanted = normalizeForMatch(artist)
            if (wanted.isEmpty()) continue
            for (album in albums) {
                val day = parseDay(album.releaseDate) ?: continue
                if (day.isBefore(earliest) || day.isAfter(today)) continue
                // Credited to someone else: a compilation they appear on.
                val credited = normalizeForMatch(album.displayArtist)
                if (credited.isNotEmpty() && !creditsArtist(credited, wanted)) continue
                val release = RadarRelease(
                    albumId = album.id,
                    title = album.title,
                    artist = artist,
                    cover = album.coverUrl ?: album.cover,
                    day = day.toEpochDay(),
                    type = album.type,
                )
                val edition = wanted + "|" + editionKey(album.title)
                val held = byEdition[edition]
                if (held == null || release.day > held.day) byEdition[edition] = release
            }
        }
        return byEdition.values
            .sortedWith(compareByDescending<RadarRelease> { it.day }.thenBy { it.title.lowercase() })
            .take(limit)
    }

    /**
     * Whether [release] wears the NEW badge: out since the newest release the
     * listener has already been shown ([seenThrough], an epoch day), or within
     * [FIRST_VISIT_NEW_DAYS] of [today] on a first visit.
     */
    fun isNew(release: RadarRelease, seenThrough: Long?, today: LocalDate): Boolean =
        if (seenThrough == null) release.day >= today.minusDays(FIRST_VISIT_NEW_DAYS.toLong()).toEpochDay()
        else release.day > seenThrough

    /**
     * A credit names the artist when it is them, or a list that has them as a
     * whole name ("Push & Ferry Corsten" credits Push; "Pushkin" does not).
     */
    private fun creditsArtist(credited: String, wanted: String): Boolean {
        if (credited == wanted) return true
        val padded = " $credited "
        return padded.contains(" $wanted ")
    }

    /** A title with its edition labels taken off, so editions of one release match. */
    private fun editionKey(title: String): String = normalizeForMatch(title)
        .replace(EDITION_WORDS, " ")
        .split(' ')
        .filter { it.isNotBlank() }
        .joinToString(" ")

    private val EDITION_WORDS = Regex(
        "\\b(deluxe|expanded|explicit|clean|remastered|remaster|edition|version|anniversary|bonus|tracks?)\\b",
    )
}
