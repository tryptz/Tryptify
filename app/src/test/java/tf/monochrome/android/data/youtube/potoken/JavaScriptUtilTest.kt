package tf.monochrome.android.data.youtube.potoken

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

/**
 * The conversions between BotGuard's web service, the WebView's JavaScript
 * and PO tokens, ported from NewPipe; no WebView or network involved. The
 * challenge's layout is the one NewPipe's parser reads: message id, the
 * interpreter's script and URL (each wrapped in an array), its hash, the
 * program, the VM's global name, and the experiments blob at 7.
 */
class JavaScriptUtilTest {

    private val challenge =
        """["msg-1",[null,"var vm = 1;\n"],[null,"//www.google.com/js/bg/abc.js"],"hash-2","program+3/","vmName",null,"blob-4"]"""

    private val expectedLiteral =
        """{"messageId":"msg-1","interpreterJavascript":{""" +
            """"privateDoNotAccessOrElseSafeScriptWrappedValue":"var vm = 1;\n",""" +
            """"privateDoNotAccessOrElseTrustedResourceUrlWrappedValue":"//www.google.com/js/bg/abc.js"},""" +
            """"interpreterHash":"hash-2","program":"program+3/","globalName":"vmName",""" +
            """"clientExperimentsStateBlob":"blob-4"}"""

    @Test
    fun `a plain challenge becomes the object runBotGuard takes`() {
        assertEquals(expectedLiteral, parseChallengeData("[$challenge]"))
    }

    @Test
    fun `a scrambled challenge reads the same once unscrambled`() {
        // Scrambled as BotGuard does it: 97 taken off every byte, then base64
        // in YouTube's URL-safe alphabet with '.' for padding.
        val scrambled = Base64.getUrlEncoder()
            .encodeToString(challenge.toByteArray().map { (it - 97).toByte() }.toByteArray())
            .replace('=', '.')
        assertEquals(expectedLiteral, parseChallengeData("""[null,"$scrambled"]"""))
    }

    @Test
    fun `missing pieces of a challenge are null, not a crash`() {
        val parsed = Json.parseToJsonElement(parseChallengeData("""[["msg",null,[1,2],"h","p","g"]]""")).jsonObject
        val interpreter = parsed["interpreterJavascript"]!!.jsonObject
        assertSame(JsonNull, interpreter["privateDoNotAccessOrElseSafeScriptWrappedValue"])
        assertSame(JsonNull, interpreter["privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"])
        assertSame(JsonNull, parsed["clientExperimentsStateBlob"])
        assertEquals("p", parsed["program"]!!.jsonPrimitive.content)
    }

    @Test
    fun `line and paragraph separators cannot break the script the challenge is written into`() {
        val literal = parseChallengeData("""[["m",[" x "],[],"h","p","g",null,"b"]]""")
        assertTrue(literal.contains("\\u2028x\\u2029"))
        assertTrue(' ' !in literal && ' ' !in literal)
    }

    @Test
    fun `the integrity token becomes a Uint8Array and its lifetime in seconds`() {
        // "-_8." is YouTube's base64 for the bytes 0xFB 0xFF.
        assertEquals("new Uint8Array([251,255])" to 43200L, parseIntegrityTokenData("""["-_8.",43200,3600,"fallback"]"""))
    }

    @Test(expected = PoTokenException::class)
    fun `an integrity token without a lifetime is refused`() {
        parseIntegrityTokenData("""["YWJj"]""")
    }

    @Test(expected = PoTokenException::class)
    fun `an integrity token that is not base64 is refused`() {
        parseIntegrityTokenData("""["not base64!",43200]""")
    }

    @Test
    fun `an identifier goes in as its UTF-8 bytes`() {
        assertEquals("new Uint8Array([100,81,119,52])", stringToU8("dQw4"))
        assertEquals("new Uint8Array([195,169])", stringToU8("é"))
    }

    @Test
    fun `a token comes back as URL-safe base64`() {
        assertEquals("YWJj", u8ToBase64("97,98,99"))
        assertEquals("-_8=", u8ToBase64("251,255"))
    }

    @Test(expected = NumberFormatException::class)
    fun `a token that is not bytes fails rather than turning into garbage`() {
        u8ToBase64("97,300")
    }

    @Test
    fun `an identifier is quoted so it can never end the script`() {
        val value = "a\"b\\c\nd "
        val literal = jsStringLiteral(value)
        assertEquals("\"a\\\"b\\\\c\\nd\\u2028\"", literal)
        // What the page sees is the identifier exactly.
        assertEquals(value, (Json.parseToJsonElement(literal) as JsonPrimitive).content)
        assertEquals(JsonArray(listOf(JsonPrimitive("k"), JsonPrimitive(value))), Json.parseToJsonElement("[\"k\", $literal]"))
    }

    @Test
    fun `a syntax error marks the WebView as broken, anything else may pass next time`() {
        assertTrue(buildExceptionForJsError("SyntaxError: Unexpected token '?'") is BadWebViewException)
        assertTrue(buildExceptionForJsError("Error: PMD:Undefined") is PoTokenException)
    }

    @Test
    fun `an integrity token counts as expired ten minutes before it runs out`() {
        val now = Instant.parse("2026-10-31T12:00:00Z")
        assertEquals(Instant.parse("2026-10-31T23:50:00Z"), integrityTokenExpiry(now, 43200))
        // One shorter than the margin is expired from the start.
        assertTrue(integrityTokenExpiry(now, 300).isBefore(now))
    }
}
