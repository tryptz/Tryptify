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

    @Test
    fun planDecodesCandidateHints() {
        val plan = json.decodeFromString<RadioPlan>(
            """
            {
              "candidateHints": [
                {
                  "title": "Roads",
                  "artist": "Portishead",
                  "album": "Dummy",
                  "release_year": 1994,
                  "tags": ["trip hop"]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, plan.candidateHints.size)
        assertEquals("Roads", plan.candidateHints.first().title)
        assertEquals(1994, plan.candidateHints.first().releaseYear)
        assertTrue(plan.isUseful)
    }

    @Test
    fun songListRequestSerializesRadioContext() {
        val encoded = json.encodeToString(
            RadioSongListRequest(
                query = "dark trip hop",
                spotifyContext = PlannerSpotifyContext(
                    searchTracks = listOf(
                        PlannerTrackMetadata(
                            title = "Roads",
                            artistName = "Portishead",
                            spotifyId = "spotify-track-id",
                            source = "spotify_search",
                        ),
                    ),
                ),
                weights = RadioPlannerWeights(localLibrary = 1.5f),
                sliders = RadioPlannerWeights(localLibrary = 1.5f).toPlannerSliders(),
            ),
        )

        assertTrue(encoded.contains(""""query":"dark trip hop""""))
        assertTrue(encoded.contains(""""spotifyContext""""))
        assertTrue(encoded.contains(""""spotifyId":"spotify-track-id""""))
        assertTrue(encoded.contains(""""localLibrary":1.5"""))
    }

    @Test
    fun songListResponseDecodesDirectSongs() {
        val response = json.decodeFromString<RadioSongListResponse>(
            """
            {
              "query": "dark trip hop",
              "message": "Late-night bass pressure",
              "songs": [
                {
                  "artist": "Portishead",
                  "title": "Roads",
                  "album": "Dummy",
                  "reason": "smoky trip hop",
                  "confidence": 0.91
                }
              ],
              "safety": {
                "modelBacked": true,
                "confidence": 0.84
              }
            }
            """.trimIndent(),
        )

        assertEquals("Late-night bass pressure", response.message)
        assertEquals("Portishead - Roads", response.songs.first().displayTitle)
        assertTrue(response.safety.modelBacked)
    }
}
