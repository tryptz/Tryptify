package tf.monochrome.android.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.Track

/**
 * The simpler Radio settings are a view of the same weights, so what has to
 * hold is the mapping: untouched stations rank exactly as before, what the
 * dials write reads back as the same dials, settings made with the old sliders
 * carry over by what they did, and each preset really moves a station the way
 * its name says. The last part is the before/after fixture the radio playbook
 * asks for when weights change.
 */
class RadioStyleTest {

    private val eps = 1e-4f

    private fun assertWeights(expected: RadioPlannerWeights, actual: RadioPlannerWeights) {
        assertEquals(expected.localLibrary, actual.localLibrary, eps)
        assertEquals(expected.qobuz, actual.qobuz, eps)
        assertEquals(expected.spotifyDiscovery, actual.spotifyDiscovery, eps)
        assertEquals(expected.canonicalVersionBias, actual.canonicalVersionBias, eps)
        assertEquals(expected.novelty, actual.novelty, eps)
        assertEquals(expected.familiarity, actual.familiarity, eps)
        assertEquals(expected.artistSimilarity, actual.artistSimilarity, eps)
        assertEquals(expected.genreTagSimilarity, actual.genreTagSimilarity, eps)
        assertEquals(expected.eraConsistency, actual.eraConsistency, eps)
        assertEquals(expected.avoidRecentlyPlayed, actual.avoidRecentlyPlayed, eps)
        assertEquals(expected.discoveryDistance, actual.discoveryDistance, eps)
    }

    private fun assertDials(expected: RadioStyle, actual: RadioStyle) {
        assertEquals(expected.newness, actual.newness, eps)
        assertEquals(expected.reach, actual.reach, eps)
        assertEquals(expected.sound, actual.sound, eps)
        assertEquals(expected.preferLibrary, actual.preferLibrary)
        assertEquals(expected.avoidRecent, actual.avoidRecent)
        assertEquals(expected.preferOriginals, actual.preferOriginals)
    }

    @Test
    fun `Balanced is exactly the shipped default weights`() {
        assertWeights(RadioPlannerWeights.DEFAULT, RadioStyle.BALANCED.toWeights())
    }

    @Test
    fun `the shipped defaults read back as Balanced`() {
        val read = RadioStyle.fromWeights(RadioPlannerWeights.DEFAULT)
        assertDials(RadioStyle.BALANCED, read)
        assertEquals(RadioPreset.BALANCED, read.preset())
    }

    @Test
    fun `what the dials write reads back as the same dials`() {
        val r = RadioStyle.NEWNESS_RANGE
        for (newness in listOf(-r, -0.5f, 0f, 0.15f, 0.6f, r)) {
            for (reach in listOf(0f, 0.25f, 0.5f, 1f)) {
                for (sound in listOf(0f, 0.4f, 1f)) {
                    for (switches in listOf(true, false)) {
                        val style = RadioStyle(newness, reach, sound, switches, !switches, switches)
                        assertDials(style, RadioStyle.fromWeights(style.toWeights()))
                    }
                }
            }
        }
    }

    @Test
    fun `every preset reads back as itself`() {
        RadioPreset.entries.forEach { preset ->
            assertEquals(preset, RadioStyle.fromWeights(preset.style.toWeights()).preset())
        }
    }

    @Test
    fun `a preset changes the dials and keeps the switches`() {
        val mine = RadioStyle(preferLibrary = false, avoidRecent = true, preferOriginals = false)
        val adventurous = mine.withPreset(RadioPreset.ADVENTUROUS)
        assertEquals(RadioPreset.ADVENTUROUS, adventurous.preset())
        assertFalse(adventurous.preferLibrary)
        assertTrue(adventurous.avoidRecent)
        assertFalse(adventurous.preferOriginals)
    }

    @Test
    fun `a switch turned off silences its signal`() {
        val off = RadioStyle(preferLibrary = false, avoidRecent = false, preferOriginals = false).toWeights()
        assertEquals(0f, off.localLibrary, 0f)
        assertEquals(0f, off.avoidRecentlyPlayed, 0f)
        assertEquals(0f, off.canonicalVersionBias, 0f)
    }

    @Test
    fun `the Qobuz weight that moved nothing stays neutral whatever the dials say`() {
        RadioPreset.entries.forEach {
            assertEquals(RadioPlannerWeights.DEFAULT.qobuz, it.style.toWeights().qobuz, 0f)
        }
    }

    @Test
    fun `old slider settings carry over by what they did`() {
        // The values this app's owner had set: familiarity a little above
        // novelty, discovery expansion at 1.50, the rest shipped.
        val old = RadioPlannerWeights.DEFAULT.copy(novelty = 0.75f, familiarity = 0.80f, spotifyDiscovery = 1.5f)
        val read = RadioStyle.fromWeights(old)
        assertEquals(-0.025f, read.newness, eps)
        assertEquals(0.5f, read.reach, eps)
        assertNull("a hand-set mix is custom, not a preset", read.preset())
        assertTrue(read.preferLibrary && read.avoidRecent && read.preferOriginals)

        val switchedOff = RadioStyle.fromWeights(old.copy(localLibrary = 0.1f, avoidRecentlyPlayed = 0f))
        assertFalse(switchedOff.preferLibrary)
        assertFalse(switchedOff.avoidRecent)
    }

    @Test
    fun `broken stored values cannot wedge the dials`() {
        val broken = RadioStyle(newness = Float.NaN, reach = Float.POSITIVE_INFINITY, sound = -4f).clamped()
        assertEquals(RadioStyle.BALANCED_NEWNESS, broken.newness, 0f)
        assertEquals(0.5f, broken.reach, 0f)
        assertEquals(0f, broken.sound, 0f)
        val weights = broken.toWeights()
        listOf(weights.novelty, weights.familiarity, weights.artistSimilarity, weights.discoveryDistance)
            .forEach { assertTrue(it.isFinite() && it in PLANNER_WEIGHT_MIN..PLANNER_WEIGHT_MAX) }
    }

    // ── Before and after, on a fixed station ─────────────────────────────

    private val planner = LocalRadioPlanner()
    private var nextId = 1L

    private fun track(title: String, artist: String, year: String = "2000", genre: String = "rock") = Track(
        id = nextId++,
        title = title,
        artist = Artist(id = artist.hashCode().toLong(), name = artist),
        album = Album(id = nextId, title = "Album", releaseDate = "$year-01-01", genreSlug = genre),
    )

    private val seed = track("Seed", "Seed Artist")

    /** A song already heard, by the seed's own artist, in its genre and era. */
    private val known = RadioCandidate(track("Known", "Seed Artist"), CandidateOrigin.SEED_ARTIST, artistDistance = 0)

    /** A song not heard, four artists out, another genre, another decade. */
    private val far = RadioCandidate(
        track("Far", "Distant Artist", year = "2018", genre = "ambient"),
        CandidateOrigin.SIMILAR_ARTIST,
        artistDistance = 4,
    )

    private val context = RadioTasteContext(seed = seed, historyKeys = listOf(known.track.tasteKey()).plus(List(29) { "x$it" }))

    private fun firstUnder(style: RadioStyle): String =
        planner.rank(listOf(known, far), context, style.toWeights()).first().candidate.track.title

    @Test
    fun `Familiar plays the song you know first, Adventurous the one you don't`() {
        // History is placed so the recent-play penalty doesn't decide it:
        // the known song is the oldest of thirty plays.
        val olderContext = context.copy(historyKeys = List(29) { "x$it" } + known.track.tasteKey())
        fun first(style: RadioStyle) =
            planner.rank(listOf(known, far), olderContext, style.toWeights()).first().candidate.track.title
        assertEquals("Known", first(RadioPreset.FAMILIAR.style))
        assertEquals("Far", first(RadioPreset.ADVENTUROUS.style))
    }

    @Test
    fun `an untouched station ranks exactly as it did before`() {
        val before = planner.rank(listOf(known, far), context, RadioPlannerWeights.DEFAULT)
        val after = planner.rank(listOf(known, far), context, RadioStyle.BALANCED.toWeights())
        assertEquals(before.map { it.candidate.track.id }, after.map { it.candidate.track.id })
        before.zip(after).forEach { (b, a) -> assertEquals(b.score, a.score, eps) }
        // And the fixture is not a tie the defaults happen to break.
        assertEquals("Far", firstUnder(RadioStyle.BALANCED))
    }
}
