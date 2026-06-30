package tf.monochrome.android.radio.planner

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerMetaBrainzContextTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun requestDecodesWithoutMetaBrainzContext() {
        val request = json.decodeFromString<RadioPlannerRequest>("{}")

        assertNull(request.metabrainz)
    }

    @Test
    fun trackIdentitySerializesMusicBrainzIdentifiers() {
        val encoded = json.encodeToString(
            PlannerTrackIdentity(
                title = "Track",
                artist = "Artist",
                album = "Album",
                isrc = "USABC1234567",
                musicBrainzRecordingId = "recording-mbid",
                musicBrainzReleaseId = "release-mbid",
                musicBrainzArtistIds = listOf("artist-mbid"),
            ),
        )

        assertTrue(encoded.contains(""""isrc":"USABC1234567""""))
        assertTrue(encoded.contains(""""musicBrainzRecordingId":"recording-mbid""""))
        assertTrue(encoded.contains(""""musicBrainzReleaseId":"release-mbid""""))
        assertTrue(encoded.contains(""""musicBrainzArtistIds":["artist-mbid"]"""))
    }

    @Test
    fun requestSerializesMetaBrainzContext() {
        val request = RadioPlannerRequest(
            metabrainz = PlannerMetaBrainzContext(
                seedIdentities = listOf(
                    PlannerTrackIdentity(
                        title = "Seed",
                        artist = "Artist",
                        isrc = "USABC1234567",
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(request)
        val decoded = json.decodeFromString<RadioPlannerRequest>(encoded)

        assertTrue(encoded.contains(""""metabrainz""""))
        assertEquals("USABC1234567", decoded.metabrainz?.seedIdentities?.single()?.isrc)
    }
}
