package tf.monochrome.android.radio

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioPlannerModelsTest {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `response decodes with all fields present`() {
        val body = """
            {
              "queries": ["artist track", "genre similar artist"],
              "source_boosts": {"local": 1.2, "qobuz": 1.0},
              "candidate_hints": [
                {"title": "Song", "artist": "Artist", "isrc": "USABC1234567",
                 "recording_mbid": "mbid-1", "reason": "shared canonical cluster"}
              ],
              "fallback_reason": null
            }
        """.trimIndent()
        val response = json.decodeFromString<RadioPlanResponse>(body)
        assertEquals(2, response.queries.size)
        assertEquals(1.2f, response.sourceBoosts["local"]!!, 0f)
        assertEquals("Song", response.candidateHints.single().title)
        assertEquals("mbid-1", response.candidateHints.single().recordingMbid)
        assertNull(response.fallbackReason)
    }

    @Test
    fun `response decodes an empty object`() {
        val response = json.decodeFromString<RadioPlanResponse>("{}")
        assertTrue(response.queries.isEmpty())
        assertTrue(response.candidateHints.isEmpty())
        assertNull(response.fallbackReason)
    }

    @Test
    fun `response ignores unknown server fields`() {
        val body = """{"queries": ["a"], "brand_new_field": {"x": 1}, "another": [1,2,3]}"""
        val response = json.decodeFromString<RadioPlanResponse>(body)
        assertEquals(listOf("a"), response.queries)
    }

    @Test
    fun `request encodes weights in snake_case with defaults included`() {
        val encoded = json.encodeToString(
            RadioPlanRequest.serializer(),
            RadioPlanRequest(seed = "chill jazz")
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        assertEquals("chill jazz", obj["seed"]!!.jsonPrimitive.content)
        val weights = obj["weights"]!!.jsonObject
        assertEquals(1.3f, weights["avoid_recently_played"]!!.jsonPrimitive.float, 1e-4f)
        assertEquals(1.2f, weights["local_library"]!!.jsonPrimitive.float, 1e-4f)
        // Null metabrainz context stays off the wire entirely.
        assertTrue("metabrainz" !in obj)
    }

    @Test
    fun `request includes history and identities when provided`() {
        val encoded = json.encodeToString(
            RadioPlanRequest.serializer(),
            RadioPlanRequest(
                seed = "Song by Artist",
                history = listOf(PlannerHistoryItem(title = "Prev", artist = "Someone")),
                metabrainz = PlannerMetaBrainzContext(
                    seedIdentities = listOf(
                        PlannerTrackIdentity(
                            title = "Song",
                            artist = "Artist",
                            isrc = "USABC1234567",
                            musicBrainzRecordingId = "mbid-1",
                        )
                    )
                ),
            )
        )
        val obj = json.parseToJsonElement(encoded).jsonObject
        val history = obj["history"]!!.jsonArray.single().jsonObject
        assertEquals("Prev", history["title"]!!.jsonPrimitive.content)
        assertEquals("Someone", history["artist"]!!.jsonPrimitive.content)
        val identity = obj["metabrainz"]!!.jsonObject["seed_identities"]!!
            .jsonArray.single().jsonObject
        assertEquals("USABC1234567", identity["isrc"]!!.jsonPrimitive.content)
        assertEquals("mbid-1", identity["musicbrainz_recording_id"]!!.jsonPrimitive.content)
    }
}
