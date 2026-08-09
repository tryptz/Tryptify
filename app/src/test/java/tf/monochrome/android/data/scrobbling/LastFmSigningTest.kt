package tf.monochrome.android.data.scrobbling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The signed half of the Last.fm API.
 *
 * Everything here is guarding the same failure mode: a signature Last.fm does
 * not accept is not an error anyone sees. The server answers politely, the
 * scrobble is dropped, and the app looks connected. That is exactly how this
 * codebase shipped a client signing with the literal string `"dummy_secret"`
 * for its entire history — so the signature is pinned against vectors computed
 * independently, not against the implementation's own output.
 */
class LastFmSigningTest {

    @Test
    fun `the session signature matches an independently computed one`() {
        // md5("api_keyabc123methodauth.getSessiontokentok789" + "s3cr3t"),
        // computed outside this codebase.
        val params = LastFmSigning.sessionParams(apiKey = "abc123", token = "tok789")
        assertEquals(
            "b1b74bbc044734f109c9bf896124a545",
            LastFmSigning.sign(params, "s3cr3t"),
        )
    }

    @Test
    fun `a scrobble signature sorts the indexed parameters correctly`() {
        // The bracketed names are what makes this worth pinning: "artist[0]"
        // sorts before "method" and after "api_key", and getting that order
        // wrong is invisible at runtime.
        val params = mapOf(
            "method" to "track.scrobble",
            "api_key" to "abc123",
            "sk" to "sess",
            "timestamp[0]" to "1700000000",
            "track[0]" to "Bassline Junkie",
            "artist[0]" to "Dizzee Rascal",
        )
        assertEquals(
            "6083c6d43ea95f68c43497ca72bf9486",
            LastFmSigning.sign(params, "s3cr3t"),
        )
    }

    @Test
    fun `format and api_sig are never part of the signature`() {
        val bare = mapOf("method" to "auth.getSession", "api_key" to "k", "token" to "t")
        val polluted = bare + mapOf("format" to "json", "api_sig" to "whatever")
        assertEquals(
            "adding format or a previous signature must not change the signature",
            LastFmSigning.sign(bare, "sec"),
            LastFmSigning.sign(polluted, "sec"),
        )
    }

    @Test
    fun `the authorize url carries the key and the callback`() {
        val url = LastFmSigning.authorizeUrl("abc123")
        assertTrue(url, url.startsWith("https://www.last.fm/api/auth/"))
        assertTrue(url, url.contains("api_key=abc123"))
        // Without cb, Last.fm never redirects and the connection silently
        // never completes — this is the whole difference from the old flow.
        assertTrue(url, url.contains("cb=${LastFmSigning.CALLBACK_URL}"))
    }

    @Test
    fun `the callback url is one the manifest actually receives`() {
        // A scheme or host that drifts from the intent filter produces a
        // browser that redirects into nothing, which looks identical to the
        // user declining. Read the manifest rather than trusting a constant.
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val uri = LastFmSigning.CALLBACK_URL
        val scheme = uri.substringBefore("://")
        val host = uri.removePrefix("$scheme://").substringBefore("/")
        assertTrue("manifest declares no scheme $scheme", manifest.contains("android:scheme=\"$scheme\""))
        assertTrue("manifest declares no host $host", manifest.contains("android:host=\"$host\""))
    }

    @Test
    fun `a granted session yields its key and name`() {
        val body = """{"session":{"name":"tryptz","key":"s3ss10nk3y","subscriber":0}}"""
        val result = LastFmSigning.parseSession(body)
        assertTrue("$result", result is LastFmSigning.SessionResult.Granted)
        result as LastFmSigning.SessionResult.Granted
        assertEquals("s3ss10nk3y", result.sessionKey)
        assertEquals("tryptz", result.username)
    }

    @Test
    fun `a refused session keeps the numbered code`() {
        // The number is the only thing separating "try again" from "this will
        // never work", so it has to survive parsing.
        val body = """{"error":4,"message":"Unauthorized Token - This token has not been issued"}"""
        val result = LastFmSigning.parseSession(body)
        assertTrue("$result", result is LastFmSigning.SessionResult.Refused)
        result as LastFmSigning.SessionResult.Refused
        assertEquals(4, result.code)
    }

    @Test
    fun `a reply that is not json is refused rather than crashing`() {
        // Captive portals and error pages answer with HTML, and a parse
        // exception here would escape into the callback handler.
        val result = LastFmSigning.parseSession("<html>502 Bad Gateway</html>")
        assertTrue("$result", result is LastFmSigning.SessionResult.Refused)
    }

    @Test
    fun `a session with no key is refused rather than stored blank`() {
        // Storing an empty key would flip the UI to "Connected" and then drop
        // every scrobble — worse than not connecting at all.
        val result = LastFmSigning.parseSession("""{"session":{"name":"tryptz"}}""")
        assertTrue("$result", result is LastFmSigning.SessionResult.Refused)
    }

    @Test
    fun `known error codes say what to do about them`() {
        // 13 is a bad signature and 10 is a bad key: both read as "it didn't
        // work" without this, and both are fixed in the same dialog.
        assertTrue(LastFmSigning.explain(13, "Invalid method signature").contains("secret"))
        assertTrue(LastFmSigning.explain(10, "Invalid API key").contains("key"))
        // Anything unrecognised falls through to Last.fm's own words rather
        // than being replaced by a vaguer sentence of ours.
        assertEquals("Some new failure", LastFmSigning.explain(99, "Some new failure"))
        assertEquals("Some new failure", LastFmSigning.explain(null, "Some new failure"))
    }
}
