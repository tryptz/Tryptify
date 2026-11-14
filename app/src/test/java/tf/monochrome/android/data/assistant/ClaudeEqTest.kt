package tf.monochrome.android.data.assistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.debug.LogRedactor
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType

class ClaudeEqTest {

    @Test
    fun `an answer becomes filters, numbered in order`() {
        val proposal = ClaudeEq.parse(
            """{"summary":"A bass hump and a 3 kHz peak.","preamp":-2.5,"bands":[
               {"type":"LOWSHELF","freq":105,"gain":2.5,"q":0.7},
               {"type":"PEAKING","freq":3100,"gain":-4,"q":2.2}]}"""
        )!!
        assertEquals("A bass hump and a 3 kHz peak.", proposal.summary)
        assertEquals(-2.5f, proposal.profile.preamp, 0f)
        assertEquals(listOf(0, 1), proposal.profile.bands.map { it.id })
        assertEquals(FilterType.LOWSHELF, proposal.profile.bands[0].type)
        assertEquals(3100f, proposal.profile.bands[1].freq, 0f)
        assertEquals(-4f, proposal.profile.bands[1].gain, 0f)
    }

    @Test
    fun `whatever is sent is held to the limits before it is shown`() {
        val many = (1..25).joinToString(",") { """{"type":"PEAKING","freq":${it * 100},"gain":1,"q":1}""" }
        val proposal = ClaudeEq.parse(
            """{"summary":"","preamp":9,"bands":[
               {"type":"PEAKING","freq":5,"gain":40,"q":900},
               {"type":"NOTCH","freq":90000,"gain":-40,"q":0.001},
               {"type":"PEAKING","freq":1000,"gain":0.01,"q":1},
               $many]}"""
        )!!
        val bands = proposal.profile.bands
        assertEquals(ClaudeEq.MAX_BANDS, bands.size)
        assertEquals(20f, bands[0].freq, 0f)
        assertEquals(ClaudeEq.MAX_GAIN_DB, bands[0].gain, 0f)
        assertEquals(ClaudeEq.MAX_Q, bands[0].q, 0f)
        // A type the equaliser does not have is a peaking filter, not a crash.
        assertEquals(FilterType.PEAKING, bands[1].type)
        assertEquals(20_000f, bands[1].freq, 0f)
        assertEquals(-ClaudeEq.MAX_GAIN_DB, bands[1].gain, 0f)
        assertEquals(ClaudeEq.MIN_Q, bands[1].q, 0f)
        // The filter that does nothing is left out, and a preamp is never a boost.
        assertTrue(bands.none { it.freq == 1000f && it.gain < 0.05f })
        assertEquals(0f, proposal.profile.preamp, 0f)
    }

    @Test
    fun `what is not the answer asked for is no proposal`() {
        assertNull(ClaudeEq.parse("Sure! Here are your filters."))
        assertNull(ClaudeEq.parse(""))
        // A picture that is no graph: a summary and no filters is an answer.
        val none = ClaudeEq.parse("""{"summary":"This is a photo of a cat.","preamp":0,"bands":[]}""")
        assertNotNull(none)
        assertTrue(none!!.profile.bands.isEmpty())
    }

    @Test
    fun `the request carries the filters in use and the wish`() {
        val current = listOf(
            EqBand(id = 0, type = FilterType.LOWSHELF, freq = 105f, gain = 4.5f, q = 0.7f),
            EqBand(id = 1, freq = 3000f, gain = -3f, q = 2f, enabled = false),
        )
        val text = ClaudeEq.request("  less bass ", current, -4.5f)
        assertTrue("LOWSHELF at 105 Hz, 4.50 dB, Q 0.70" in text)
        assertTrue("preamp -4.50 dB" in text)
        assertFalse("3000" in text)                       // a filter that is switched off is not in use
        assertTrue(text.endsWith("less bass"))
        // With nothing said and nothing to start from, the picture is the request.
        assertEquals("Lay out the filters for this.", ClaudeEq.request("", null, 0f))
    }

    @Test
    fun `the schema is JSON, and names what the answer is read by`() {
        val schema = Json.parseToJsonElement(ClaudeEq.SCHEMA).jsonObject
        assertEquals(setOf("summary", "preamp", "bands"), schema["properties"]!!.jsonObject.keys)
        // The limits Claude is told are the limits the answer is held to.
        assertTrue("at most ${ClaudeEq.MAX_BANDS}" in ClaudeEq.SYSTEM)
        assertTrue("+${ClaudeEq.MAX_GAIN_DB} dB" in ClaudeEq.SYSTEM)
    }

    @Test
    fun `a Claude API key never stays in a log line`() {
        val line = LogRedactor.redact("request failed for key sk-ant-api03-AbCdEf123456_-xyz and more")
        assertFalse("AbCdEf123456" in line)
        assertTrue("and more" in line)
    }
}
