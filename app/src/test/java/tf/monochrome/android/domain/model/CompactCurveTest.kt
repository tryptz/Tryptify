package tf.monochrome.android.domain.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.eq.EqDataParser

/**
 * A stored curve comes back bit for bit, so a fit from a preset's or the
 * offline store's copy is the fit from the download.
 */
class CompactCurveTest {

    // Values that have no short exact decimal, plus the AutoEq grid's shape.
    private val awkward = listOf(
        FrequencyPoint(20f, -7.88f),
        FrequencyPoint(20.2f, 0.1f),
        FrequencyPoint(123.456789f, 1e-4f),
        FrequencyPoint(1000f, -0.0f),
        FrequencyPoint(19999.99f, 12345.678f),
        FrequencyPoint(20000f, -1e-7f),
    )

    private fun bits(points: List<FrequencyPoint>) =
        points.map { it.freq.toRawBits() to it.gain.toRawBits() }

    @Test
    fun `encode then decode gives the same floats`() {
        assertEquals(bits(awkward), bits(CompactCurve.decode(CompactCurve.encode(awkward))))
    }

    @Test
    fun `the CSV form parses back to the same points`() {
        assertEquals(bits(awkward), bits(EqDataParser.parseRawData(CompactCurve.toCsv(awkward))))
    }

    @Test
    fun `an empty curve is an empty string and back`() {
        assertEquals("", CompactCurve.encode(emptyList()))
        assertTrue(CompactCurve.decode("").isEmpty())
    }

    @Test
    fun `a malformed pair is skipped, not fatal`() {
        val points = CompactCurve.decode("20,1;garbage;30;,4;40,x;50,2")
        assertEquals(listOf(FrequencyPoint(20f, 1f), FrequencyPoint(50f, 2f)), points)
    }

    @Test
    fun `a preset headphone survives JSON with its curves as strings`() {
        val hp = PresetHeadphone(
            id = "a", name = "A",
            left = PresetEar(label = "\u0000imported", curve = awkward),
            right = PresetEar(sample = "R1", curve = awkward.reversed()),
        )
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(PresetHeadphone.serializer(), hp)
        // Not FrequencyPoint objects: one string per ear.
        assertTrue(text, !text.contains("\"freq\""))
        val back = json.decodeFromString(PresetHeadphone.serializer(), text)
        assertEquals(hp, back)
        assertEquals(bits(hp.left.curve), bits(back.left.curve))
    }
}
