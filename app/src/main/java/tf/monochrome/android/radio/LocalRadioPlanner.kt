package tf.monochrome.android.radio

import tf.monochrome.android.domain.model.Track
import kotlin.math.abs

/**
 * Where a candidate came from. The origin is itself a ranking signal — a track
 * pulled from the seed artist's own catalogue means something different from
 * one that fell out of a broad search — so it is carried alongside the track
 * rather than being flattened into list order.
 */
enum class CandidateOrigin {
    /** The seed artist's own top tracks. */
    SEED_ARTIST,

    /** Top tracks of an artist the catalogue calls similar to the seed. */
    SIMILAR_ARTIST,

    /** Broad catalogue search — query expansion, artist-name search. */
    SEARCH,
}

/**
 * A candidate track plus the on-device context needed to score it.
 *
 * [artistDistance] is how far the candidate's artist sits from the seed's:
 * `0` is the seed artist, `1..n` its position in the catalogue's similar-artist
 * list, `null` unknown (a search result whose artist was never related back).
 */
data class RadioCandidate(
    val track: Track,
    val origin: CandidateOrigin,
    val fromQobuz: Boolean = true,
    val inLibrary: Boolean = false,
    val artistDistance: Int? = null,
)

/**
 * What the listener's device already knows about them. Keys are the same
 * normalized `artist|title` form [RadioQueueManager] dedupes on, so a remaster
 * counts as the same song as the original.
 */
data class RadioTasteContext(
    val seed: Track,
    /** Recently played, most recent first. */
    val historyKeys: List<String> = emptyList(),
    /** Favourites, downloads and anything else held on-device. */
    val libraryKeys: Set<String> = emptySet(),
)

/** A candidate with its score and the per-signal breakdown that produced it. */
data class ScoredCandidate(
    val candidate: RadioCandidate,
    val score: Float,
    val signals: Map<String, Float>,
)

/**
 * Ranks radio candidates on-device, using the user's weights.
 *
 * This is the whole brain. A remote planner used to sit in front of it as an
 * extra source of candidates, and for a while it was the only thing that read
 * the weights at all — which meant that without a reachable server every knob
 * in Settings › Radio did nothing and radio fell back to "the seed artist's
 * top tracks, in catalogue order". Scoring here fixed that, and the service
 * has since gone; ranking never depended on it.
 *
 * Each signal below is normalized to roughly `0..1` (or `-1..1` where it cuts
 * both ways) and multiplied by its weight, so a weight of `0` silences its
 * signal, `1` is neutral, and `3` lets one signal dominate — which is exactly
 * what the slider's 0–3 range promises.
 *
 * [LOCAL_SIGNALS] names what this class acts on, and the settings screen reads
 * it so the UI cannot quietly drift from the truth. Every weight the model
 * carries is in there: the three that only ever described off-device datasets
 * went out with the service rather than stay as sliders that move nothing.
 */
class LocalRadioPlanner {

    /**
     * Best-first ranking. Deterministic: equal scores keep their input order,
     * so a given seed and library produce a stable station rather than
     * reshuffling under the listener between refills.
     */
    fun rank(
        candidates: List<RadioCandidate>,
        context: RadioTasteContext,
        weights: RadioPlannerWeights,
    ): List<ScoredCandidate> =
        candidates.map { score(it, context, weights) }.sortedByDescending { it.score }

    /** The score for one candidate, with every signal's contribution itemized. */
    fun score(
        candidate: RadioCandidate,
        context: RadioTasteContext,
        weights: RadioPlannerWeights,
    ): ScoredCandidate {
        val w = weights.clamped()
        val key = candidate.track.tasteKey()
        val heard = key in context.historyKeys || key in context.libraryKeys

        val signals = buildMap {
            put(SIGNAL_LOCAL_LIBRARY, w.localLibrary * if (candidate.inLibrary) 1f else 0f)
            put(SIGNAL_QOBUZ, w.qobuz * if (candidate.fromQobuz) 1f else 0f)
            put(SIGNAL_DISCOVERY_EXPANSION, w.spotifyDiscovery * candidate.origin.expansionSignal())
            put(SIGNAL_CANONICAL, w.canonicalVersionBias * candidate.track.canonicalSignal())
            put(SIGNAL_NOVELTY, w.novelty * if (heard) 0f else 1f)
            put(SIGNAL_FAMILIARITY, w.familiarity * if (heard) 1f else 0f)
            put(SIGNAL_ARTIST_SIMILARITY, w.artistSimilarity * closeness(candidate.artistDistance))
            put(SIGNAL_GENRE, w.genreTagSimilarity * genreMatch(context.seed, candidate.track))
            put(SIGNAL_ERA, w.eraConsistency * eraCloseness(context.seed, candidate.track))
            // The only signal that subtracts: everything else argues for a
            // candidate, this one argues against one you just heard.
            put(SIGNAL_AVOID_RECENT, -w.avoidRecentlyPlayed * recency(key, context.historyKeys))
            put(SIGNAL_DISCOVERY_DISTANCE, w.discoveryDistance * drift(candidate.artistDistance))
        }

        return ScoredCandidate(candidate, signals.values.sum(), signals)
    }

    // ── Signals ───────────────────────────────────────────────────────────

    /** Broad search is what "widens the pool"; targeted origins are not. */
    private fun CandidateOrigin.expansionSignal(): Float = when (this) {
        CandidateOrigin.SEARCH -> 1f
        CandidateOrigin.SEED_ARTIST, CandidateOrigin.SIMILAR_ARTIST -> 0f
    }

    /** `1` for the seed artist, decaying with similar-artist position. */
    private fun closeness(distance: Int?): Float =
        if (distance == null) 0f else 1f / (1f + distance.coerceAtLeast(0))

    /** The mirror of [closeness]: rewards drifting away from the seed. */
    private fun drift(distance: Int?): Float =
        if (distance == null) UNKNOWN_ARTIST_DRIFT
        else distance.coerceAtLeast(0).let { it / (1f + it) }

    private fun genreMatch(seed: Track, candidate: Track): Float {
        val seedGenre = seed.album?.genreSlug ?: seed.album?.genre ?: return 0f
        val candidateGenre = candidate.album?.genreSlug ?: candidate.album?.genre ?: return 0f
        return if (seedGenre.equals(candidateGenre, ignoreCase = true)) 1f else 0f
    }

    /** Full credit for the same year, fading to nothing [ERA_SPAN_YEARS] apart. */
    private fun eraCloseness(seed: Track, candidate: Track): Float {
        val seedYear = seed.releaseYear() ?: return 0f
        val candidateYear = candidate.releaseYear() ?: return 0f
        val gap = abs(seedYear - candidateYear)
        return (1f - gap / ERA_SPAN_YEARS.toFloat()).coerceAtLeast(0f)
    }

    /** `1` for the very last thing played, fading out across the history window. */
    private fun recency(key: String, historyKeys: List<String>): Float {
        val index = historyKeys.indexOf(key)
        if (index < 0) return 0f
        return 1f - index.toFloat() / historyKeys.size
    }

    companion object {
        /**
         * The weights this planner actually acts on without a server. The
         * settings screen reads this so a weight cannot claim on-device effect
         * it does not have; anything absent is sent to the planner and scored
         * there, or not at all.
         */
        val LOCAL_SIGNALS: Set<String> = setOf(
            SIGNAL_LOCAL_LIBRARY,
            SIGNAL_QOBUZ,
            SIGNAL_DISCOVERY_EXPANSION,
            SIGNAL_CANONICAL,
            SIGNAL_NOVELTY,
            SIGNAL_FAMILIARITY,
            SIGNAL_ARTIST_SIMILARITY,
            SIGNAL_GENRE,
            SIGNAL_ERA,
            SIGNAL_AVOID_RECENT,
            SIGNAL_DISCOVERY_DISTANCE,
        )

        /** Release years further apart than this share no era at all. */
        const val ERA_SPAN_YEARS = 25

        /**
         * Drift credit for a candidate whose artist was never related back to
         * the seed. It came from a search rather than the similarity graph, so
         * it is probably far — but "unknown" is not the same as "distant", so
         * it scores below a confirmed hop out.
         */
        const val UNKNOWN_ARTIST_DRIFT = 0.6f
    }
}

// Signal names double as the map keys in [ScoredCandidate.signals], so a test
// (or a debug readout) can point at exactly which weight moved a track.
const val SIGNAL_LOCAL_LIBRARY = "local_library"
const val SIGNAL_QOBUZ = "qobuz"
const val SIGNAL_DISCOVERY_EXPANSION = "discovery_expansion"
const val SIGNAL_CANONICAL = "canonical_version"
const val SIGNAL_NOVELTY = "novelty"
const val SIGNAL_FAMILIARITY = "familiarity"
const val SIGNAL_ARTIST_SIMILARITY = "artist_similarity"
const val SIGNAL_GENRE = "genre_tag_similarity"
const val SIGNAL_ERA = "era_consistency"
const val SIGNAL_AVOID_RECENT = "avoid_recently_played"
const val SIGNAL_DISCOVERY_DISTANCE = "discovery_distance"

/**
 * Release strings that mark a track as a non-canonical presentation of a song
 * the listener probably already has. Matched on word boundaries so "Alive"
 * isn't a live take and "Editorial" isn't a radio edit.
 */
private val NON_CANONICAL = Regex(
    "\\b(" +
        "remaster(ed)?|remix(es|ed)?|live|acoustic|instrumental|karaoke|demo|" +
        "radio edit|single version|album version|re-?recorded|rerecorded|" +
        "mono|stereo version|edit|version|mix" +
        ")\\b",
    RegexOption.IGNORE_CASE,
)

/** `+1` for a plain studio recording, `-1` for a remaster/live/edit. */
internal fun Track.canonicalSignal(): Float {
    val text = listOfNotNull(title, version, album?.version).joinToString(" ")
    return if (NON_CANONICAL.containsMatchIn(text)) -1f else 1f
}

/** Four-digit release year, or null when the catalogue didn't supply one. */
internal fun Track.releaseYear(): Int? = album?.releaseYear?.toIntOrNull()

/**
 * The identity radio treats as "the same song" — matching [RadioQueueManager]'s
 * dedupe key, so a remaster of something in the listener's history is
 * recognized as already heard.
 */
internal fun Track.tasteKey(): String =
    "$displayArtist|$title".lowercase().filter { it.isLetterOrDigit() || it == '|' }
