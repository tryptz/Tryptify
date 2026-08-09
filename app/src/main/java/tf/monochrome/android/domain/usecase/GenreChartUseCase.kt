package tf.monochrome.android.domain.usecase

import kotlinx.coroutines.flow.first
import tf.monochrome.android.data.charts.ChartEntry
import tf.monochrome.android.data.charts.ChartSource
import tf.monochrome.android.data.charts.ChartWindow
import tf.monochrome.android.data.charts.ChartsRepository
import tf.monochrome.android.data.charts.GenreChart
import tf.monochrome.android.data.charts.normalizeForMatch
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.data.repository.GenreGraphRepository
import tf.monochrome.android.data.repository.MusicRepository
import tf.monochrome.android.domain.model.UnifiedTrack
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a genre's Top 100 for a time window.
 *
 * The two sources this composes each hold half of the answer, and the halves do
 * not overlap. ListenBrainz can say what the world played in a bounded window
 * but not what genre any of it was; Last.fm can rank any genre you can name but
 * has no notion of when. So a windowed chart is the global window narrowed by
 * the genre's artists, and when that intersection is too thin to be a chart —
 * which is the normal case for a small scene, where a week of global listening
 * may contain almost none of it — this falls back to the all-time ranking and
 * *says that it did*. A chart labelled "7 days" that is quietly showing all-time
 * data is worse than no chart, because a listener has no way to tell.
 */
@Singleton
class GenreChartUseCase @Inject constructor(
    private val charts: ChartsRepository,
    private val genreGraph: GenreGraphRepository,
    private val preferences: PreferencesManager,
    private val music: MusicRepository,
) {
    companion object {
        /**
         * Below this many rows a window isn't a Top 100, it's a rumour. Ten is
         * enough to see a shape and short enough that mid-sized genres still get
         * a real window instead of being bounced to all-time.
         */
        const val MIN_WINDOW_ENTRIES = 10

        const val DEFAULT_LIMIT = 100

        /**
         * How much of a chart to turn into a play queue. Deep enough to be a
         * listening session, shallow enough that building it is a manageable
         * number of catalogue lookups.
         */
        const val POOL_DEPTH = 30

        /**
         * How far down a tag chart to verify artists. Each new artist costs a
         * request; the head is what gets read and what playback opens on.
         */
        const val VERIFY_DEPTH = 40

        /**
         * Most entries one artist may hold. Without this, cross-checking makes
         * clumping worse rather than better: promoting the confirmed artists in
         * hard techno floated four consecutive Sara Landry tracks to the top,
         * which is a truthful chart and a bad one. A Top 100 that is one artist
         * repeated tells you less than a Top 100 that is a scene.
         */
        const val MAX_PER_ARTIST = 3
    }

    suspend fun chart(
        genreId: String,
        window: ChartWindow,
        limit: Int = DEFAULT_LIMIT,
    ): GenreChart {
        val node = genreGraph.graph[genreId]
            ?: return GenreChart(
                genreId = genreId,
                genreName = genreId,
                requested = window,
                shown = window,
                source = ChartSource.WINDOWED_LISTENS,
            )

        val names = node.queries()
        val apiKey = runCatching { preferences.lastFmChartsApiKey.first() }.getOrNull().orEmpty()

        /**
         * The all-time chart, cross-checked against MusicBrainz before it is
         * handed back. Null when there is no tag chart to be had.
         */
        suspend fun tagChart(shownAs: ChartWindow): GenreChart? {
            val tag = charts.tagChart(genreId, names, apiKey)
            if (tag.isEmpty()) return null
            val entries = capPerArtist(tag.take(limit), MAX_PER_ARTIST)
            val confirmed = confirmArtists(node, entries, apiKey)
            return GenreChart(
                genreId = genreId,
                genreName = node.name,
                requested = window,
                shown = shownAs,
                source = ChartSource.TAG_CHART,
                entries = promoteConfirmed(entries, confirmed),
                crossChecked = confirmed.isNotEmpty(),
            )
        }

        // All time is the tag chart's native window, so ask for it directly
        // before paying for a sitewide pull.
        if (window == ChartWindow.ALL_TIME) {
            tagChart(shownAs = window)?.let { return it }
        }

        val sitewide = charts.sitewide(window)
        val artists = charts.artistsFor(genreId, names)
        val filtered = narrowToGenre(sitewide.entries, artists, limit)

        val windowed = GenreChart(
            genreId = genreId,
            genreName = node.name,
            requested = window,
            shown = window,
            source = ChartSource.WINDOWED_LISTENS,
            entries = filtered,
            fromTs = sitewide.fromTs,
            toTs = sitewide.toTs,
        )

        if (filtered.size >= MIN_WINDOW_ENTRIES) return windowed

        // Too thin to stand on its own. Prefer a real all-time ranking over a
        // three-row "chart", but keep the thin window if there is no tag chart
        // to fall back to — a short honest list still beats an empty screen.
        return tagChart(shownAs = ChartWindow.ALL_TIME) ?: windowed
    }

    /** Renumber after filtering so ranks read 1..n rather than the source's gaps. */
    private fun List<ChartEntry>.reranked(): List<ChartEntry> =
        mapIndexed { index, entry -> entry.copy(rank = index + 1) }

    /**
     * Which of a chart's artists genuinely belong to this genre.
     *
     * Two sources of evidence, unioned, because each misses what the other
     * catches. MusicBrainz's artist-tag search is curated and precise but thin:
     * asked for hard techno it names 167 artists and still doesn't include
     * Klangkuenstler, who is about as hard techno as it gets. An artist's own
     * Last.fm tag cloud covers nearly everyone and is dominated by what they
     * actually are — Klangkuenstler comes back `techno`, `hard techno`, while
     * FKA twigs comes back `trip-hop`, `dream pop` and Baby Jane, sitting at
     * number seven in hard techno, comes back `hard rock`, `sleaze rock`.
     *
     * Tags are matched through the genre graph rather than by string, so
     * `techno` confirms a hard techno artist via the parent relation and
     * `dub techno` confirms via the graph's sideways edges. That is the
     * difference between recognising a scene and recognising a spelling.
     *
     * Only the head of the chart is checked. It costs a request per new artist,
     * and the tail is not what anyone reads or what playback opens on.
     */
    private suspend fun confirmArtists(
        node: tf.monochrome.android.domain.model.GenreNode,
        entries: List<ChartEntry>,
        apiKey: String,
    ): Set<String> {
        val relatives = relativesOf(node)
        val fromMusicBrainz = charts.artistsFor(node.id, node.queries())

        val confirmed = mutableSetOf<String>()
        val artists = entries.take(VERIFY_DEPTH)
            .map { it.artistName }
            .distinctBy { normalizeForMatch(it) }

        for (artist in artists) {
            val key = normalizeForMatch(artist)
            if (key in fromMusicBrainz) {
                confirmed += key
                continue
            }
            val belongs = charts.artistTags(artist, apiKey).any { tag ->
                genreGraph.graph.resolve(tag)?.id in relatives
            }
            if (belongs) confirmed += key
        }
        return confirmed
    }

    /**
     * The genre plus everything adjacent to it in the graph.
     *
     * An artist is rarely tagged with the exact leaf you are looking at. Someone
     * who makes hard techno is tagged `techno` far more often than `hard techno`,
     * and a dub techno producer is tagged `dub techno`, `minimal techno` and
     * `ambient techno` in roughly equal measure. Accepting the parent, the
     * children and the sideways edges is what makes the check recognise a scene
     * instead of a single word — while still rejecting `pop` and `reggaeton`,
     * which are nowhere near this part of the map.
     */
    private fun relativesOf(node: tf.monochrome.android.domain.model.GenreNode): Set<String> =
        buildSet {
            add(node.id)
            addAll(node.parents)
            addAll(node.nearEdges().map { it.first })
            genreGraph.graph.allGenres.forEach { candidate ->
                if (node.id in candidate.parents) add(candidate.id)
            }
        }

    /**
     * The genre's charting tracks, in rank order, as things the player can take.
     *
     * This exists so that "play this genre" can mean *the records that define
     * it* rather than whatever the catalogue returns for the genre's name as a
     * search string. Those are very different lists: searching a catalogue for
     * "hard techno" matches titles and album names, which is a magnet for
     * machine-generated filler literally called "Hard Techno", while the chart
     * is ranked by what people actually played.
     *
     * Entries that can't be found in the catalogue are dropped, so the result
     * may be shorter than [depth] — a short list of the real thing beats a full
     * one padded with near-misses.
     */
    suspend fun playablePool(
        genreId: String,
        window: ChartWindow = ChartWindow.ALL_TIME,
        depth: Int = POOL_DEPTH,
    ): List<UnifiedTrack> {
        val entries = chart(genreId, window, limit = depth).entries
        return entries.mapNotNull { resolve(it) }.distinctBy { it.id }
    }

    /**
     * Find the catalogue's copy of a charted track.
     *
     * The search backend ranks by relevance to the whole query, which for
     * "AIROD Acid Storm" usually puts the right record first but will happily
     * return something else when the catalogue simply doesn't have it. So the
     * results are scanned for one that agrees with the chart on *both* artist
     * and title, and if none does this returns null rather than the closest
     * thing to hand. Playing the wrong track is worse than playing nothing:
     * silence is legible, a wrong record looks like the chart is nonsense.
     */
    suspend fun resolve(entry: ChartEntry): UnifiedTrack? {
        val result = music.searchQobuz(entry.matchQuery).getOrNull() ?: return null
        return result.tracks
            .firstOrNull { agrees(entry, it.artists.firstOrNull()?.name ?: "", it.title) }
            ?.toQobuzUnifiedTrack()
    }
}

/**
 * Whether a catalogue hit is really the charted track.
 *
 * Containment in either direction rather than equality, because the two sides
 * disagree in predictable, harmless ways once folded: one carries a feature
 * credit the other drops, one says "Artist & Friend" where the other says
 * "Artist". Requiring both fields to agree is what keeps a title-only
 * coincidence — a track called "Hard Techno" by nobody in particular — from
 * passing as the genre's number one.
 */
internal fun agrees(entry: ChartEntry, artistName: String, title: String): Boolean {
    val wantArtist = normalizeForMatch(entry.artistName)
    val gotArtist = normalizeForMatch(artistName)
    val wantTitle = normalizeForMatch(entry.title)
    val gotTitle = normalizeForMatch(title)
    if (wantArtist.isEmpty() || gotArtist.isEmpty()) return false
    if (wantTitle.isEmpty() || gotTitle.isEmpty()) return false
    val artistAgrees = wantArtist.contains(gotArtist) || gotArtist.contains(wantArtist)
    val titleAgrees = wantTitle.contains(gotTitle) || gotTitle.contains(wantTitle)
    return artistAgrees && titleAgrees
}

/**
 * Narrow a global windowed chart down to one genre.
 *
 * Matching is on artist rather than recording because that is the join the two
 * services can actually agree on: MusicBrainz will tell you which artists carry
 * a tag, and ListenBrainz reports an artist name on every row. Recording-level
 * genre tags exist but are far sparser, so joining on them would throw away most
 * of the window.
 *
 * Ranks are rebuilt afterwards. The surviving rows carry their global positions
 * — 4, 51, 900 — and presenting those as a genre chart would suggest 895 hard
 * techno tracks nobody bothered to show.
 */
/**
 * Float the artists MusicBrainz confirms for this genre above the ones it doesn't.
 *
 * Last.fm tags are crowd-applied, and a very popular artist accumulates loose
 * ones: `hard techno` comes back led by FKA twigs and Charli xcx, with the first
 * actual hard techno producer at number three. Nobody sat down and decided that
 * — it is what happens when a million casual taggers meet an artist who is on
 * everyone's radar. MusicBrainz tags are curated by people editing a database,
 * so they disagree in exactly the useful direction.
 *
 * Demotion rather than deletion, because MusicBrainz coverage is partial: a
 * genuinely obscure producer may simply not be in the artist set, and dropping
 * unconfirmed rows would quietly delete the deep cuts a genre chart exists to
 * surface. Sorting them below the confirmed ones fixes the head of the chart —
 * which is what anybody actually reads, and what playback opens on — while
 * costing nothing at the tail.
 *
 * Both guards matter. With no artist set at all (a genre MusicBrainz has never
 * heard of) and with nothing confirmed (a set that missed entirely), the chart
 * is returned untouched rather than shuffled on no evidence.
 */
internal fun promoteConfirmed(
    entries: List<ChartEntry>,
    artistKeys: Set<String>,
): List<ChartEntry> {
    if (artistKeys.isEmpty()) return entries
    val (confirmed, unconfirmed) = entries.partition {
        normalizeForMatch(it.artistName) in artistKeys
    }
    if (confirmed.isEmpty()) return entries
    // partition preserves relative order, so Last.fm's ranking survives inside
    // each group and only the boundary between them is new.
    return (confirmed + unconfirmed).mapIndexed { index, entry -> entry.copy(rank = index + 1) }
}

/**
 * Stop one artist from owning the chart.
 *
 * Keeps each artist's best [max] entries in rank order and drops the rest, then
 * renumbers. Dropping rather than demoting, because an artist's fourth-best
 * track is exactly the entry a reader would skip anyway, and carrying it to
 * position 90 spends a slot that a different artist would fill more usefully.
 */
internal fun capPerArtist(entries: List<ChartEntry>, max: Int): List<ChartEntry> {
    if (max <= 0) return entries
    val seen = mutableMapOf<String, Int>()
    return entries.filter { entry ->
        val key = normalizeForMatch(entry.artistName)
        val count = seen.getOrDefault(key, 0)
        seen[key] = count + 1
        count < max
    }.mapIndexed { index, entry -> entry.copy(rank = index + 1) }
}

internal fun narrowToGenre(
    entries: List<ChartEntry>,
    artistKeys: Set<String>,
    limit: Int,
): List<ChartEntry> = entries
    .filter { normalizeForMatch(it.artistName) in artistKeys }
    .sortedByDescending { it.listenCount }
    .take(limit)
    .mapIndexed { index, entry -> entry.copy(rank = index + 1) }
