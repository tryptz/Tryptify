package tf.monochrome.android.radio.planner

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPlannerWeightsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestDecodesWithoutWeights() {
        val request = json.decodeFromString<RadioPlannerRequest>("{}")

        assertEquals(RadioPlannerWeights(), request.weights)
    }

    @Test
    fun requestDecodesWithWeights() {
        val request = json.decodeFromString<RadioPlannerRequest>(
            """{"weights":{"localLibrary":2.5,"avoidRecentlyPlayed":0.25}}""",
        )

        assertEquals(2.5f, request.weights.localLibrary)
        assertEquals(0.25f, request.weights.avoidRecentlyPlayed)
    }

    @Test
    fun clampedLimitsInvalidPlannerWeights() {
        val clamped = RadioPlannerWeights(
            localLibrary = -1f,
            qobuz = 4f,
            spotifyDiscovery = Float.NaN,
            metabrainzMetadata = Float.POSITIVE_INFINITY,
        ).clamped()

        assertEquals(0f, clamped.localLibrary)
        assertEquals(3f, clamped.qobuz)
        assertEquals(RadioPlannerWeights().spotifyDiscovery, clamped.spotifyDiscovery)
        assertEquals(RadioPlannerWeights().metabrainzMetadata, clamped.metabrainzMetadata)
    }

    @Test
    fun plannerSlidersExposeStableFieldNames() {
        val sliders = RadioPlannerWeights(localLibrary = 1.75f, qobuz = 0.5f).toPlannerSliders()

        assertEquals(1.75f, sliders["localLibrary"])
        assertEquals(0.5f, sliders["qobuz"])
        assertTrue("avoidRecentlyPlayed" in sliders)
    }

    @Test
    fun requestSerializesTypedWeights() {
        val encoded = json.encodeToString(
            RadioPlannerRequest(weights = RadioPlannerWeights(localLibrary = 1.75f)),
        )

        assertTrue(encoded.contains(""""weights""""))
        assertTrue(encoded.contains(""""localLibrary":1.75"""))
    }
}
