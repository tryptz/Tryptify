package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The 2-channel preset contract: bandsR is nullable, null MEANS mono, and both
 * shapes survive JSON round-trips — including decoding pre-v11 JSON that has
 * no bandsR field at all (every existing saved preset).
 */
class EqPresetStereoTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun band(id: Int, freq: Float, gain: Float) =
        EqBand(id = id, freq = freq, gain = gain, q = 1f)

    @Test
    fun `stereo preset round-trips with distinct per-ear bands`() {
        val preset = EqPreset(
            id = "p1",
            name = "LR",
            bands = listOf(band(0, 100f, 2f), band(1, 1000f, -3f)),
            bandsR = listOf(band(0, 120f, 1.5f)),
            preamp = -3f,
        )
        val decoded = json.decodeFromString<EqPreset>(json.encodeToString(EqPreset.serializer(), preset))
        assertEquals(2, decoded.bands.size)
        assertEquals(1, decoded.bandsR?.size)
        assertEquals(120f, decoded.bandsR!![0].freq, 0f)
    }

    @Test
    fun `mono preset keeps bandsR null through a round-trip`() {
        val preset = EqPreset(id = "p2", name = "mono", bands = listOf(band(0, 100f, 2f)))
        val decoded = json.decodeFromString<EqPreset>(json.encodeToString(EqPreset.serializer(), preset))
        assertNull(decoded.bandsR)
    }

    @Test
    fun `pre-v11 JSON with no bandsR field decodes as a mono preset`() {
        val legacy = """{"id":"p3","name":"old","bands":[{"id":0,"freq":100.0,"gain":2.0}]}"""
        val decoded = json.decodeFromString<EqPreset>(legacy)
        assertNull(decoded.bandsR)
        assertEquals(1, decoded.bands.size)
    }
}
