package tf.monochrome.android.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.data.api.model.ApiMediaMetadata
import tf.monochrome.android.data.api.model.hasDolbyAtmos

class DolbyAtmosFlagTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    @Test
    fun `either field naming DOLBY_ATMOS marks an Atmos mix`() {
        assertEquals(true, hasDolbyAtmos(listOf("STEREO", "DOLBY_ATMOS"), null))
        assertEquals(true, hasDolbyAtmos(null, ApiMediaMetadata(tags = listOf("LOSSLESS", "DOLBY_ATMOS"))))
    }

    @Test
    fun `listed without it is stereo, and saying nothing is unknown`() {
        assertEquals(false, hasDolbyAtmos(listOf("STEREO"), ApiMediaMetadata(tags = listOf("LOSSLESS", "HIRES_LOSSLESS"))))
        // Unknown must stay unknown: getTrackStream only skips the Atmos
        // request for a track TIDAL said has no Atmos mix.
        assertNull(hasDolbyAtmos(null, null))
        assertNull(hasDolbyAtmos(null, ApiMediaMetadata(tags = null)))
    }

    @Test
    fun `TIDAL track JSON keeps its audio modes through search`() {
        val body = """{"version":"2.9","data":{"items":[
            {"id":1,"title":"Atmos one","audioModes":["DOLBY_ATMOS"],"mediaMetadata":{"tags":["DOLBY_ATMOS"]}},
            {"id":2,"title":"Stereo one","audioModes":["STEREO"],"mediaMetadata":{"tags":["LOSSLESS"]}}
        ]}}"""
        val items = HifiPayload.search(json, body, HifiPayload.Kind.TRACKS).items
        assertEquals(listOf(true, false), items.map { hasDolbyAtmos(it.audioModes, it.mediaMetadata) })
    }
}
