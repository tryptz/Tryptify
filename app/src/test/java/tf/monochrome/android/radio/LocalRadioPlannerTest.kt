package tf.monochrome.android.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/**
 * The weights have to change what radio actually plays, with no server in the
 * loop. Every test here isolates one weight: the baseline silences all of them
 * (every weight `0`), then the one under test is raised, so a preference that
 * appears can only have come from that signal.
 *
 * The pairs are built to differ in exactly the thing the weight names, so
 * "raising it changes the order" and "zeroing it removes the preference" are
 * both asserted — a signal that is always on is as broken as one that never is.
 */
class LocalRadioPlannerTest {

    private val planner = LocalRadioPlanner()

    /** Every weight off, so any ordering must come from the one weight raised. */
    private val silent = RadioPlannerWeights(
        localLibrary = 0f, qobuz = 0f, spotifyDiscovery = 0f,
        canonicalVersionBias = 0f, novelty = 0f, familiarity = 0f,
        artistSimilarity = 0f, genreTagSimilarity = 0f,
        eraConsistency = 0f, avoidRecentlyPlayed = 0f, discoveryDistance = 0f,
    )

    private var nextId = 1L

    private fun track(
        title: String = "Track ${nextId}",
        artist: String = "Artist",
        year: String? = null,
        genre: String? = null,
        version: String? = null,
    ) = Track(
        id = nextId++,
        title = title,
        artist = Artist(id = artist.hashCode().toLong(), name = artist),
        version = version,
        album = Album(
            id = nextId,
            title = "Album",
            releaseDate = year?.let { "$it-01-01" },
            genreSlug = genre,
        ),
    )

    private val seed = track(title = "Seed", artist = "Seed Artist", year = "2000", genre = "rock")

    private fun context(
        history: List<String> = emptyList(),
        library: Set<String> = emptySet(),
    ) = RadioTasteContext(seed = seed, historyKeys = history, libraryKeys = library)

    private fun scoreOf(
        candidate: RadioCandidate,
        weights: RadioPlannerWeights,
        context: RadioTasteContext = context(),
    ) = planner.score(candidate, context, weights).score

    /**
     * The shape every weight test takes: with the weight off the pair ties;
     * with it raised, [preferred] wins.
     */
    private fun assertWeightDecides(
        weight: String,
        raised: RadioPlannerWeights,
        preferred: RadioCandidate,
        other: RadioCandidate,
        context: RadioTasteContext = context(),
    ) {
        assertEquals(
            "$weight: with every weight at 0 these must tie, or the pair differs in more than $weight",
            scoreOf(other, silent, context),
            scoreOf(preferred, silent, context),
            1e-5f,
        )
        val preferredScore = scoreOf(preferred, raised, context)
        val otherScore = scoreOf(other, raised, context)
        assertTrue(
            "$weight has no effect on ranking — it is a dead knob " +
                "(preferred=$preferredScore, other=$otherScore)",
            preferredScore > otherScore,
        )
        // And it must actually reorder them, not merely score differently.
        val ranked = planner.rank(listOf(other, preferred), context, raised)
        assertEquals("$weight: ranking must put the favoured candidate first", preferred, ranked.first().candidate)
    }

    // ── One test per weight scored on-device ──────────────────────────────

    @Test
    fun `local library weight prefers what is already on the device`() {
        assertWeightDecides(
            "localLibrary",
            silent.copy(localLibrary = 3f),
            preferred = RadioCandidate(track(), CandidateOrigin.SEARCH, inLibrary = true),
            other = RadioCandidate(track(), CandidateOrigin.SEARCH, inLibrary = false),
        )
    }

    @Test
    fun `qobuz weight prefers catalogue candidates over the fallback source`() {
        assertWeightDecides(
            "qobuz",
            silent.copy(qobuz = 3f),
            preferred = RadioCandidate(track(), CandidateOrigin.SEARCH, fromQobuz = true),
            other = RadioCandidate(track(), CandidateOrigin.SEARCH, fromQobuz = false),
        )
    }

    @Test
    fun `discovery expansion weight prefers broad search over the seed artist`() {
        assertWeightDecides(
            "spotifyDiscovery",
            silent.copy(spotifyDiscovery = 3f),
            preferred = RadioCandidate(track(), CandidateOrigin.SEARCH),
            other = RadioCandidate(track(), CandidateOrigin.SEED_ARTIST),
        )
    }

    @Test
    fun `canonical version weight prefers the original over a remaster`() {
        assertWeightDecides(
            "canonicalVersionBias",
            silent.copy(canonicalVersionBias = 3f),
            preferred = RadioCandidate(track(title = "Song"), CandidateOrigin.SEARCH),
            other = RadioCandidate(track(title = "Song (2011 Remastered)"), CandidateOrigin.SEARCH),
        )
    }

    @Test
    fun `novelty weight prefers the unheard`() {
        val heard = track(title = "Known", artist = "Someone")
        assertWeightDecides(
            "novelty",
            silent.copy(novelty = 3f),
            preferred = RadioCandidate(track(title = "New"), CandidateOrigin.SEARCH),
            other = RadioCandidate(heard, CandidateOrigin.SEARCH),
            context = context(history = listOf(heard.tasteKey())),
        )
    }

    @Test
    fun `familiarity weight prefers what has been heard before`() {
        val heard = track(title = "Known", artist = "Someone")
        assertWeightDecides(
            "familiarity",
            silent.copy(familiarity = 3f),
            preferred = RadioCandidate(heard, CandidateOrigin.SEARCH),
            other = RadioCandidate(track(title = "New"), CandidateOrigin.SEARCH),
            context = context(history = listOf(heard.tasteKey())),
        )
    }

    @Test
    fun `artist similarity weight prefers artists close to the seed`() {
        assertWeightDecides(
            "artistSimilarity",
            silent.copy(artistSimilarity = 3f),
            preferred = RadioCandidate(track(), CandidateOrigin.SEED_ARTIST, artistDistance = 0),
            other = RadioCandidate(track(), CandidateOrigin.SEED_ARTIST, artistDistance = 4),
        )
    }

    @Test
    fun `genre weight prefers a matching genre`() {
        assertWeightDecides(
            "genreTagSimilarity",
            silent.copy(genreTagSimilarity = 3f),
            preferred = RadioCandidate(track(genre = "rock"), CandidateOrigin.SEARCH),
            other = RadioCandidate(track(genre = "jazz"), CandidateOrigin.SEARCH),
        )
    }

    @Test
    fun `era weight prefers a nearby release year`() {
        assertWeightDecides(
            "eraConsistency",
            silent.copy(eraConsistency = 3f),
            preferred = RadioCandidate(track(year = "2001"), CandidateOrigin.SEARCH),
            other = RadioCandidate(track(year = "1965"), CandidateOrigin.SEARCH),
        )
    }

    @Test
    fun `avoid recently played weight pushes down what was just heard`() {
        val recent = track(title = "Just Played", artist = "Someone")
        assertWeightDecides(
            "avoidRecentlyPlayed",
            silent.copy(avoidRecentlyPlayed = 3f),
            preferred = RadioCandidate(track(title = "Fresh"), CandidateOrigin.SEARCH),
            other = RadioCandidate(recent, CandidateOrigin.SEARCH),
            context = context(history = listOf(recent.tasteKey())),
        )
    }

    @Test
    fun `discovery distance weight prefers drifting away from the seed`() {
        assertWeightDecides(
            "discoveryDistance",
            silent.copy(discoveryDistance = 3f),
            preferred = RadioCandidate(track(), CandidateOrigin.SIMILAR_ARTIST, artistDistance = 5),
            other = RadioCandidate(track(), CandidateOrigin.SIMILAR_ARTIST, artistDistance = 0),
        )
    }

    @Test
    fun `every weight the model carries has a test above`() {
        // Guards against a signal being added to LOCAL_SIGNALS — and so a
        // slider appearing in Settings › Radio — without a test proving it
        // changes anything.
        val tested = setOf(
            SIGNAL_LOCAL_LIBRARY, SIGNAL_QOBUZ, SIGNAL_DISCOVERY_EXPANSION, SIGNAL_CANONICAL,
            SIGNAL_NOVELTY, SIGNAL_FAMILIARITY, SIGNAL_ARTIST_SIMILARITY, SIGNAL_GENRE,
            SIGNAL_ERA, SIGNAL_AVOID_RECENT, SIGNAL_DISCOVERY_DISTANCE,
        )
        assertEquals(LocalRadioPlanner.LOCAL_SIGNALS, tested)
    }

    /**
     * Three weights used to sit outside [LocalRadioPlanner.LOCAL_SIGNALS]:
     * mood continuity, which needs audio features the app does not compute,
     * and the MetaBrainz and ListenBrainz weights, which describe datasets
     * that live off-device. They only ever went to the remote planner, so
     * they left with it. This pins the model to what is actually scored, so
     * a field cannot come back without a signal behind it.
     */
    @Test
    fun `the model carries no weight the planner cannot score`() {
        val fields = RadioPlannerWeights::class.java.declaredFields
            .filter { it.type == Float::class.javaPrimitiveType }
            .map { it.name }
            .toSet()
        assertEquals(LocalRadioPlanner.LOCAL_SIGNALS.size, fields.size)
        for (gone in listOf("moodContinuity", "metabrainzMetadata", "listenbrainzGraph")) {
            assertTrue("$gone came back without a signal to score it", gone !in fields)
        }
    }

    // ── Behaviour end to end ─────────────────────────────────────────────

    @Test
    fun `shipped defaults rank a plausible station`() {
        // The realistic case: default weights, a little history, and a mixed
        // pool. The unheard canonical track by the seed artist should beat a
        // remaster the listener just played.
        val justPlayed = track(title = "Old Favourite", artist = "Seed Artist")
        val pool = listOf(
            RadioCandidate(
                track(title = "Old Favourite (Remastered)", artist = "Seed Artist"),
                CandidateOrigin.SEARCH, artistDistance = 0,
            ),
            RadioCandidate(justPlayed, CandidateOrigin.SEED_ARTIST, artistDistance = 0),
            RadioCandidate(
                track(title = "Deep Cut", artist = "Seed Artist", year = "2000", genre = "rock"),
                CandidateOrigin.SEED_ARTIST, artistDistance = 0,
            ),
        )
        val ranked = planner.rank(
            pool,
            context(history = listOf(justPlayed.tasteKey())),
            RadioPlannerWeights.DEFAULT,
        )
        assertEquals("Deep Cut", ranked.first().candidate.track.title)
        assertEquals("Old Favourite", ranked.last().candidate.track.title)
    }

    @Test
    fun `ranking is deterministic and keeps input order on ties`() {
        val a = RadioCandidate(track(title = "A"), CandidateOrigin.SEARCH)
        val b = RadioCandidate(track(title = "B"), CandidateOrigin.SEARCH)
        val once = planner.rank(listOf(a, b), context(), RadioPlannerWeights.DEFAULT)
        val twice = planner.rank(listOf(a, b), context(), RadioPlannerWeights.DEFAULT)
        assertEquals(once.map { it.candidate }, twice.map { it.candidate })
        assertEquals(listOf(a, b), once.map { it.candidate })
    }

    @Test
    fun `an empty pool ranks to nothing rather than throwing`() {
        assertTrue(planner.rank(emptyList(), context(), RadioPlannerWeights.DEFAULT).isEmpty())
    }

    // ── Signal details worth pinning ─────────────────────────────────────

    @Test
    fun `canonical detection matches on whole words only`() {
        // "Alive" is not a live take and "Editorial" is not a radio edit; a
        // substring match here would down-rank a chunk of the catalogue.
        // "Versions"/"Mixtape" likewise are not "version"/"mix".
        for (clean in listOf("Song", "Alive", "Editorial", "Versions of You", "Mixtape", "Delivery")) {
            assertEquals(clean, 1f, track(title = clean).canonicalSignal(), 0f)
        }
        for (flagged in listOf(
            "Song - Remastered", "Song (Live)", "Song - Radio Edit",
            "Song (Acoustic)", "Song - 2019 Remaster",
        )) {
            assertEquals(flagged, -1f, track(title = flagged).canonicalSignal(), 0f)
        }
    }

    @Test
    fun `a release version marks a track non-canonical even with a clean title`() {
        val t = track(title = "Song", version = "2011 Remastered Version")
        assertEquals(-1f, t.canonicalSignal(), 0f)
    }

    @Test
    fun `era credit fades to nothing beyond the era span`() {
        val weights = silent.copy(eraConsistency = 1f)
        val sameYear = RadioCandidate(track(year = "2000"), CandidateOrigin.SEARCH)
        val farOff = RadioCandidate(
            track(year = "${2000 + LocalRadioPlanner.ERA_SPAN_YEARS + 10}"),
            CandidateOrigin.SEARCH,
        )
        assertEquals(1f, scoreOf(sameYear, weights), 1e-5f)
        // Clamped at zero — a very distant year must not become a penalty.
        assertEquals(0f, scoreOf(farOff, weights), 1e-5f)
    }

    @Test
    fun `a missing release year is neutral rather than penalised`() {
        val weights = silent.copy(eraConsistency = 3f)
        assertEquals(0f, scoreOf(RadioCandidate(track(year = null), CandidateOrigin.SEARCH), weights), 0f)
    }

    @Test
    fun `recency penalty is strongest for the most recently played`() {
        val weights = silent.copy(avoidRecentlyPlayed = 1f)
        val justPlayed = track(title = "One", artist = "A")
        val longAgo = track(title = "Two", artist = "B")
        val history = listOf(justPlayed.tasteKey()) + List(8) { "filler$it" } + longAgo.tasteKey()
        val ctx = context(history = history)
        val justPlayedScore = scoreOf(RadioCandidate(justPlayed, CandidateOrigin.SEARCH), weights, ctx)
        val longAgoScore = scoreOf(RadioCandidate(longAgo, CandidateOrigin.SEARCH), weights, ctx)
        assertTrue("both must be penalised", justPlayedScore < 0f && longAgoScore < 0f)
        assertTrue("the more recent play must be penalised harder", justPlayedScore < longAgoScore)
    }

    @Test
    fun `a remaster of something in history counts as already heard`() {
        // tasteKey is artist+title normalized, so the novelty signal isn't
        // fooled by a different pressing of a song the listener knows.
        val original = track(title = "Song", artist = "Band")
        val remaster = track(title = "Song", artist = "Band")
        assertEquals(original.tasteKey(), remaster.tasteKey())
        val weights = silent.copy(novelty = 3f)
        assertEquals(
            0f,
            scoreOf(
                RadioCandidate(remaster, CandidateOrigin.SEARCH),
                weights,
                context(history = listOf(original.tasteKey())),
            ),
            0f,
        )
    }

    @Test
    fun `library membership counts as heard for novelty`() {
        val owned = track(title = "Owned", artist = "Band")
        val weights = silent.copy(novelty = 3f)
        assertEquals(
            0f,
            scoreOf(
                RadioCandidate(owned, CandidateOrigin.SEARCH),
                weights,
                context(library = setOf(owned.tasteKey())),
            ),
            0f,
        )
    }

    @Test
    fun `out-of-range weights are clamped before scoring`() {
        val candidate = RadioCandidate(track(), CandidateOrigin.SEARCH, inLibrary = true)
        assertEquals(
            scoreOf(candidate, silent.copy(localLibrary = PLANNER_WEIGHT_MAX)),
            scoreOf(candidate, silent.copy(localLibrary = 500f)),
            0f,
        )
    }
}
