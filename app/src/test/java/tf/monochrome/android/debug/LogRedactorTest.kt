// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sign-ins never reach the kept log, the shared log or a crash file. */
class LogRedactorTest {

    private fun redact(s: String) = LogRedactor.redact(s, emptyMap())

    @Test
    fun aMusicServersSignInIsHidden() {
        val out = redact("GET http://nas.local/rest/stream?id=7&u=me&t=0123abcd&s=salt99&v=1.16.1")
        assertEquals("GET http://nas.local/rest/stream?id=7&u=<redacted>&t=<redacted>&s=<redacted>&v=1.16.1", out)
    }

    @Test
    fun oauthTokensAndCodesAreHidden() {
        val out = redact("tryptify://auth#access_token=abc.def&refresh_token=r1&expires_in=3600 and ?code=xyz")
        assertFalse(out.contains("abc.def"))
        assertFalse(out.contains("r1&"))
        assertFalse(out.contains("xyz"))
        assertTrue(out.contains("expires_in=3600"))
    }

    @Test
    fun bearerTokensAndJwtsAreHidden() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"
        val out = redact("Authorization: Bearer abcdefgh12345678 then $jwt")
        assertFalse(out.contains("abcdefgh12345678"))
        assertFalse(out.contains(jwt))
    }

    @Test
    fun aSecretAddressKeepsOnlyItsHost() {
        val secret = "https://rooms.example.app/s3cr3tpath"
        val out = LogRedactor.redact("joining $secret/room/1", mapOf(secret to "https://rooms.example.app/<redacted>"))
        assertEquals("joining https://rooms.example.app/<redacted>/room/1", out)
    }

    @Test
    fun ordinaryTextIsLeftAlone() {
        val line = "Playback started: track 42 at 44100 Hz, sessions=3"
        assertEquals(line, redact(line))
    }
}
