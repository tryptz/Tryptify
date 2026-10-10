package tf.monochrome.android.data.charts

import kotlinx.serialization.Serializable
import java.text.Normalizer
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * A window over listening history.
 *
 * The [id]s are stable on purpose — they key the cache and the chart screen's
 * saved state, so renaming one quietly throws away every cached chart.
 *
 * [listenBrainzRange] is the wire value ListenBrainz wants for its sitewide
 * statistics. Every window here maps to a real range on that side; what differs
 * is whether the *genre* half of a chart can be answered for that window, which
 * is [ChartSource]'s job to record.
 */
enum class ChartWindow(
    val id: String,
    val label: String,
    val listenBrainzRange: String,
) {
    SEVEN_DAYS("7d", "7 days", "week"),
    THIRTY_DAYS("30d", "30 days", "month"),
    SIX_MONTHS("6m", "6 months", "half_yearly"),
    ONE_YEAR("1y", "1 year", "year"),
    ALL_TIME("all", "All time", "all_time"),
    ;

    companion object {
        val DEFAULT = SEVEN_DAYS

        fun fromId(id: String?): ChartWindow = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/**
 * Where a chart's numbers actually came from.
 *
 * This is surfaced in the UI rather than kept as an implementation detail. A
 * chart that says "7 days" while showing an all-time ranking is a lie, and the
 * two sources genuinely measure different things — scrobble reach for one,
 * counted listens in a bounded window for the other.
 */
enum class ChartSource {
    /** Last.fm tag chart: global scrobble popularity for a genre, no time bound. */
    TAG_CHART,

    /** ListenBrainz sitewide statistics, filtered to the genre, within a real window. */
    WINDOWED_LISTENS,
}

/**
 * One row of a genre chart.
 *
 * Serializable because a tag chart outlives the process that fetched it — see
 * [tf.monochrome.android.data.charts.DiscoveryCache]. The two computed
 * properties below are derived from the fields and are not written out.
 */
@Serializable
data class ChartEntry(
    val rank: Int,
    val title: String,
    val artistName: String,
    /** Listens in the window, or scrobble listeners for a tag chart. 0 when unknown. */
    val listenCount: Long = 0,
    val recordingMbid: String? = null,
    val artworkUrl: String? = null,
) {
    /** What the catalogue search gets when this row has to become playable. */
    val matchQuery: String get() = "$artistName $title"

    /** Identity for dedup and cross-source joins — not for display. */
    val matchKey: String get() = "${normalizeForMatch(artistName)} ${normalizeForMatch(title)}"
}

/**
 * A genre's chart for one window.
 *
 * [requested] and [shown] differ when the window had too little data to stand up
 * and the chart fell back; the screen reads [fellBack] to say so out loud rather
 * than presenting the fallback as if it were the window asked for.
 */
data class GenreChart(
    val genreId: String,
    val genreName: String,
    val requested: ChartWindow,
    val shown: ChartWindow,
    val source: ChartSource,
    val entries: List<ChartEntry> = emptyList(),
    /** Real window boundaries, in epoch seconds, when the source reports them. */
    val fromTs: Long? = null,
    val toTs: Long? = null,
    /**
     * Whether the ordering was checked against MusicBrainz's genre tags.
     *
     * Only meaningful for [ChartSource.TAG_CHART]: a windowed chart is built
     * *from* that artist set, so it is cross-checked by construction.
     */
    val crossChecked: Boolean = false,
) {
    val fellBack: Boolean get() = requested != shown
    val isEmpty: Boolean get() = entries.isEmpty()
}

/**
 * A genre shelf's raw material, kept split by where it came from.
 *
 * The two halves answer the same question with different evidence, and the
 * caller needs to know which is which, because a row on the discovery feed
 * always says how it knows. [charted] is the tag chart's own rows, matched into
 * the catalogue on both artist and title — the strongest evidence there is, and
 * the half most likely to come up short, because a row the catalogue does not
 * carry resolves to nothing rather than to something near it. [fromArtists] is
 * the catalogue's own most-played tracks for the artists that same chart names:
 * weaker per track, far cheaper per track, and it is what keeps a shelf full
 * instead of falling through to a search for the genre's name.
 */
data class GenrePool(
    val charted: List<UnifiedTrack> = emptyList(),
    val fromArtists: List<UnifiedTrack> = emptyList(),
) {
    val isEmpty: Boolean get() = charted.isEmpty() && fromArtists.isEmpty()
}

/**
 * Whether [url] is Last.fm's stand-in picture — the grey star it serves for
 * any track or artist it has no image of, which is now most of them. Shown, it
 * makes every row of a chart look the same and every planet a grey ball with a
 * star on it; treated as no picture, each gets its own fallback instead.
 */
internal fun isLastFmPlaceholder(url: String?): Boolean =
    url != null && url.contains(LASTFM_PLACEHOLDER_ID)

private const val LASTFM_PLACEHOLDER_ID = "2a96cbd8b46e442fc41c2b86b821562f"

/**
 * Fold a name down to something two different services can agree on.
 *
 * Chart sources and the catalogue disagree constantly about punctuation, case,
 * feature credits and bracketed suffixes — "Sicko Mode (feat. Drake)" against
 * "SICKO MODE" against "Sicko Mode - Remastered". Stripping to bare
 * alphanumerics loses information but is the only thing that makes those three
 * collapse onto one row instead of three.
 *
 * Accents come off for the same reason and not as a nicety. Last.fm scrobbles
 * carry whatever a client sent, so "Olafur Arnalds", "Ólafur Arnalds" and
 * "ÓLAFUR ARNALDS" all appear in one chart — and a fold that keeps the accent
 * reads them as three artists, which shows the same person three times in a row
 * and confirms none of them against a catalogue that spells it the other way.
 */
internal fun normalizeForMatch(raw: String): String {
    val withoutBrackets = raw
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("\\[[^]]*]"), " ")
        .substringBefore(" - ")
    val decomposed = Normalizer.normalize(withoutBrackets, Normalizer.Form.NFD)
    return buildString {
        for (ch in decomposed.lowercase()) {
            // Combining marks are dropped rather than turned into separators:
            // NFD splits "ó" into "o" plus an accent, and treating that accent
            // as punctuation would fold the name to "o lafur".
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            if (ch.isLetterOrDigit()) append(ch) else if (isNotEmpty() && last() != ' ') append(' ')
        }
    }.trim()
}

/**
 * What the genre galaxy shows of an artist beside their chart: the opening of
 * their bio, and how many releases they have — the size of their catalogue,
 * which is the size of their planet. Either may be unknown.
 */
data class ArtistFacts(val bio: String?, val releases: Int?)
