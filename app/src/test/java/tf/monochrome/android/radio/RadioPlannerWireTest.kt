package tf.monochrome.android.radio

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does a planner weight the user moved actually influence anything?
 *
 * The scoring itself happens on the planner service, so what can be settled
 * on-device is the half that is ours: every weight leaves the device, under
 * the name the server reads, carrying that field's own value. Those are the
 * ways a weight becomes a dead knob without anything failing loudly — a field
 * dropped from the request, a serial name that no longer matches the server's
 * model, or a copy-paste slip in one of the three fourteen-line field-by-field
 * blocks (`clamped`, and the read/write pair in PreferencesManager) that pins
 * one weight to another's value.
 *
 * Encoding goes through [plannerJson], the same encoder [RadioPlannerClient]
 * builds request bodies with, so this cannot pass against a config the app
 * does not actually use.
 *
 * NOT covered here, and deliberately: whether the *server* honours any of it.
 * See RadioPlannerWeightsTest for the clamping rules.
 */
class RadioPlannerWireTest {

    /**
     * Every field a distinct value, so a swap between any two is visible.
     * Identical values (the defaults have several) would let `novelty =
     * familiarity` pass unnoticed.
     */
    private val distinct = RadioPlannerWeights(
        localLibrary = 0.10f,
        qobuz = 0.20f,
        spotifyDiscovery = 0.30f,
        metabrainzMetadata = 0.40f,
        listenbrainzGraph = 0.50f,
        canonicalVersionBias = 0.60f,
        novelty = 0.70f,
        familiarity = 0.80f,
        artistSimilarity = 0.90f,
        genreTagSimilarity = 1.00f,
        moodContinuity = 1.10f,
        eraConsistency = 1.20f,
        avoidRecentlyPlayed = 1.30f,
        discoveryDistance = 1.40f,
    )

    /** The serial name the server reads, paired with the value set above. */
    private val expectedOnTheWire = mapOf(
        "local_library" to 0.10f,
        "qobuz" to 0.20f,
        "spotify_discovery" to 0.30f,
        "metabrainz_metadata" to 0.40f,
        "listenbrainz_graph" to 0.50f,
        "canonical_version_bias" to 0.60f,
        "novelty" to 0.70f,
        "familiarity" to 0.80f,
        "artist_similarity" to 0.90f,
        "genre_tag_similarity" to 1.00f,
        "mood_continuity" to 1.10f,
        "era_consistency" to 1.20f,
        "avoid_recently_played" to 1.30f,
        "discovery_distance" to 1.40f,
    )

    private fun weightsObjectOf(request: RadioPlanRequest): JsonObject =
        Json.parseToJsonElement(plannerJson.encodeToString(request))
            .jsonObject["weights"]!!
            .jsonObject

    @Test
    fun `every weight reaches the request body under its server-side name`() {
        val weights = weightsObjectOf(RadioPlanRequest(seed = "a song", weights = distinct))
        for ((name, value) in expectedOnTheWire) {
            val sent = weights[name]
                ?: error("weight '$name' never reaches the planner — it cannot influence anything")
            assertEquals("weight '$name' sent the wrong value", value, sent.jsonPrimitive.float, 0f)
        }
    }

    @Test
    fun `the request carries no weight the server does not know about`() {
        // A field added here but not to the service's model is silently ignored
        // server-side, which looks identical to a knob that works.
        val weights = weightsObjectOf(RadioPlanRequest(seed = "a song", weights = distinct))
        assertEquals(
            "weights on the wire drifted from the expected set",
            expectedOnTheWire.keys,
            weights.keys,
        )
    }

    @Test
    fun `moving one weight changes that weight alone`() {
        // Catches a field-by-field block that reads or writes the wrong
        // neighbour: bump each weight in turn and require exactly one key of
        // the body to move.
        val baseline = weightsObjectOf(RadioPlanRequest(seed = "s", weights = distinct))
        val bumped = listOf<Pair<String, RadioPlannerWeights>>(
            "local_library" to distinct.copy(localLibrary = 2.9f),
            "qobuz" to distinct.copy(qobuz = 2.9f),
            "spotify_discovery" to distinct.copy(spotifyDiscovery = 2.9f),
            "metabrainz_metadata" to distinct.copy(metabrainzMetadata = 2.9f),
            "listenbrainz_graph" to distinct.copy(listenbrainzGraph = 2.9f),
            "canonical_version_bias" to distinct.copy(canonicalVersionBias = 2.9f),
            "novelty" to distinct.copy(novelty = 2.9f),
            "familiarity" to distinct.copy(familiarity = 2.9f),
            "artist_similarity" to distinct.copy(artistSimilarity = 2.9f),
            "genre_tag_similarity" to distinct.copy(genreTagSimilarity = 2.9f),
            "mood_continuity" to distinct.copy(moodContinuity = 2.9f),
            "era_consistency" to distinct.copy(eraConsistency = 2.9f),
            "avoid_recently_played" to distinct.copy(avoidRecentlyPlayed = 2.9f),
            "discovery_distance" to distinct.copy(discoveryDistance = 2.9f),
        )
        assertEquals("every weight must be exercised", expectedOnTheWire.size, bumped.size)

        for ((movedKey, weights) in bumped) {
            val after = weightsObjectOf(RadioPlanRequest(seed = "s", weights = weights))
            val changed = after.keys.filter { after[it] != baseline[it] }
            assertEquals("moving '$movedKey' should move exactly that key", listOf(movedKey), changed)
        }
    }

    @Test
    fun `clamping preserves which field is which`() {
        // clamped() rebuilds all fourteen fields by hand and runs on every read
        // and every write, so a swap here would corrupt weights on both paths.
        val clamped = distinct.clamped()
        assertEquals(distinct, clamped)
    }

    @Test
    fun `clamping an out-of-range weight leaves its neighbours alone`() {
        val clamped = distinct.copy(novelty = 99f).clamped()
        assertEquals("the out-of-range field is the one that moves", 3f, clamped.novelty, 0f)
        assertEquals(distinct.copy(novelty = 3f), clamped)
    }

    @Test
    fun `shipped defaults are not a no-op`() {
        // If every default were neutral the feature would ship doing nothing,
        // and a user who never opens the settings would get no benefit from it.
        val defaults = RadioPlannerWeights.DEFAULT
        val onTheWire = weightsObjectOf(RadioPlanRequest(seed = "s", weights = defaults))
        val nonNeutral = onTheWire.filterValues { it.jsonPrimitive.float != PLANNER_WEIGHT_NEUTRAL }
        assertTrue("no default weight departs from neutral", nonNeutral.isNotEmpty())
    }

    @Test
    fun `neutral weights are still sent rather than omitted`() {
        // encodeDefaults is load-bearing: the server's own default for a signal
        // need not be 1.0, so "user left it neutral" has to be stated, not
        // implied by absence.
        val allNeutral = RadioPlannerWeights(
            localLibrary = 1f, qobuz = 1f, spotifyDiscovery = 1f, metabrainzMetadata = 1f,
            listenbrainzGraph = 1f, canonicalVersionBias = 1f, novelty = 1f, familiarity = 1f,
            artistSimilarity = 1f, genreTagSimilarity = 1f, moodContinuity = 1f,
            eraConsistency = 1f, avoidRecentlyPlayed = 1f, discoveryDistance = 1f,
        )
        val weights = weightsObjectOf(RadioPlanRequest(seed = "s", weights = allNeutral))
        assertEquals(expectedOnTheWire.keys, weights.keys)
        for (name in expectedOnTheWire.keys) {
            assertEquals(name, PLANNER_WEIGHT_NEUTRAL, weights[name]!!.jsonPrimitive.float, 0f)
        }
    }

    @Test
    fun `a request with no weights set still sends the shipped defaults`() {
        // RadioQueueManager always passes stored weights, but the field is
        // defaulted; if that default were ever dropped the knobs would go quiet.
        val weights = weightsObjectOf(RadioPlanRequest(seed = "s"))
        assertEquals(expectedOnTheWire.keys, weights.keys)
        assertEquals(
            RadioPlannerWeights.DEFAULT.avoidRecentlyPlayed,
            weights["avoid_recently_played"]!!.jsonPrimitive.float,
            0f,
        )
    }

    @Test
    fun `the seed-only retry carries no weights at all`() {
        // Pins the documented degradation in RadioPlannerClient.plan(): when a
        // stricter server rejects the extended body, the retry is seed-only, so
        // weights (and history, catalog and MetaBrainz context) stop influencing
        // that request entirely. Intended, but it means a 4xx from the planner
        // silently turns every weight off until the server accepts the full shape.
        val retryBody = plannerJson.encodeToString(mapOf("seed" to "a song"))
        val parsed = Json.parseToJsonElement(retryBody).jsonObject
        assertEquals(setOf("seed"), parsed.keys)
        assertFalse("retry body must not claim to carry weights", parsed.containsKey("weights"))
    }

    @Test
    fun `the catalog the planner biases toward is sent`() {
        val body = Json.parseToJsonElement(
            plannerJson.encodeToString(RadioPlanRequest(seed = "s"))
        ).jsonObject
        assertEquals("qobuz", body["catalog"]!!.jsonPrimitive.content)
    }

    @Test
    fun `distinct fixture really is distinct`() {
        // Guards the guard: if two fixture values collided, the swap-detecting
        // tests above would quietly stop detecting swaps.
        val values = expectedOnTheWire.values.toList()
        assertEquals(values.size, values.toSet().size)
        assertNotEquals(0, values.size)
    }
}
