package tf.monochrome.android.data.scxyt

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tf.monochrome.android.R
import tf.monochrome.android.data.downloads.DownloadItem
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * The app's half of the YouTube and SoundCloud source's contract with
 * trypt-hifi-scxyt (its README, "The API Tryptify uses"): the requests, the
 * JSON it answers with, its error codes, the stream link, the sign-in, and
 * how its songs live in the queue and the download list. No network: the
 * HTTP is a fake [ScxytClient.Transport].
 */
class ScxytTest {

    /** The app's own Json (AppModule.provideJson). */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val base = "https://scxyt.example"

    /** Answers each GET with the next of [replies], and remembers what was asked with which token. */
    private class FakeTransport(vararg replies: ScxytClient.Reply) : ScxytClient.Transport {
        private val queue = ArrayDeque(replies.toList())
        val asked = mutableListOf<Pair<String, String>>()
        val timeouts = mutableListOf<Long>()
        override suspend fun get(url: String, token: String, timeoutMs: Long): ScxytClient.Reply {
            asked += url to token
            timeouts += timeoutMs
            return queue.removeFirst()
        }
    }

    /** A session whose token is "t1" until a 401 refuses it, then "t2". */
    private class FakeSession {
        val staleAsked = mutableListOf<String?>()
        suspend fun bearer(stale: String?): String {
            staleAsked += stale
            return if (stale == "t1") "t2" else "t1"
        }
    }

    private fun client(transport: ScxytClient.Transport, session: FakeSession = FakeSession()) =
        ScxytClient(base, json, transport, session::bearer)

    private val searchBody = """
        {"success":true,"data":{"query":"one more time","source":"youtube","offset":0,"items":[
          {"id":"yt:A2VpR8HahKc","source":"youtube","title":"Daft Punk - One More Time (Official Audio)",
           "artist":"Daft Punk","artistUrl":"https://www.youtube.com/channel/x","duration":321,
           "cover":"https://i.ytimg.com/vi/A2VpR8HahKc/hqdefault.jpg","url":"https://www.youtube.com/watch?v=A2VpR8HahKc",
           "extra":"ignored"},
          {"id":"sc:199428706","source":"soundcloud","title":"A SoundCloud Upload","artist":"",
           "artistUrl":null,"duration":245,"cover":"http://i1.sndcdn.com/a.jpg","url":"https://soundcloud.com/a/b"},
          {"id":"vimeo:123","source":"vimeo","title":"Not ours","artist":"x","duration":1,"cover":null,"url":"x"}
        ]}}
    """.trimIndent()

    // ── Search ─────────────────────────────────────────────────────────

    @Test
    fun `a search asks for one source's first page, with the query encoded`() {
        assertEquals(
            "$base/api/v1/search?q=daft+punk+%26+co&source=soundcloud&limit=20&offset=0",
            ScxytClient.searchUrl("$base/", "daft punk & co", ScxytSource.SOUNDCLOUD, 20, 0),
        )
        assertEquals("$base/api/v1/prepare?id=yt%3AdQw4w9WgXcQ", ScxytClient.prepareUrl(base, "yt:dQw4w9WgXcQ"))
    }

    @Test
    fun `search results become tracks named by the server's own ids`() = runBlocking {
        val transport = FakeTransport(ScxytClient.Reply(200, searchBody))
        val items = client(transport).search("one more time", ScxytSource.YOUTUBE, 20)
        assertEquals(3, items.size)
        assertEquals("Bearer is the session's token", "t1", transport.asked.single().second)
        assertEquals(ScxytClient.SEARCH_TIMEOUT_MS, transport.timeouts.single())

        val tracks = items.mapNotNull { it.toUnifiedTrack() }
        // An id the server never hands out is not played or stored.
        assertEquals(listOf("yt:A2VpR8HahKc", "sc:199428706"), tracks.map { it.id })

        val yt = tracks[0]
        assertEquals("Daft Punk - One More Time (Official Audio)", yt.title)
        assertEquals("Daft Punk", yt.artistName)
        assertEquals(321, yt.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/A2VpR8HahKc/hqdefault.jpg", yt.artworkUri)
        assertEquals(SourceType.YOUTUBE, yt.sourceType)
        assertEquals(PlaybackSource.Scxyt("yt:A2VpR8HahKc"), yt.source)
        // Nothing that would pass for a catalogue album or artist.
        assertNull(yt.albumId)
        assertNull(yt.artistId)
        assertTrue(yt.artists.all { it.id == null })

        val sc = tracks[1]
        assertEquals(SourceType.SOUNDCLOUD, sc.sourceType)
        assertEquals("Unknown Artist", sc.artistName)
        // Only a plain https picture leaves the app with the song.
        assertNull(sc.artworkUri)
    }

    @Test
    fun `ids are told apart by their shape`() {
        assertEquals(ScxytSource.YOUTUBE, ScxytSource.ofId("yt:dQw4w9WgXcQ"))
        assertEquals(ScxytSource.SOUNDCLOUD, ScxytSource.ofId("sc:199428706"))
        assertNull(ScxytSource.ofId("yt:short"))
        assertNull(ScxytSource.ofId("sc:12a"))
        assertNull(ScxytSource.ofId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(ScxytSource.SOUNDCLOUD, ScxytSource.of(SourceType.SOUNDCLOUD))
        assertNull(ScxytSource.of(SourceType.API))
    }

    // ── Prepare and the stream link ────────────────────────────────────

    @Test
    fun `prepare hands back an absolute link the player can open as it is`() = runBlocking {
        val transport = FakeTransport(
            ScxytClient.Reply(
                200,
                """{"success":true,"data":{"id":"yt:dQw4w9WgXcQ","format":"m4a","mime":"audio/mp4","size":3456789,
                   "stream":"/api/v1/stream?id=yt%3AdQw4w9WgXcQ",
                   "link":"/api/v1/stream?id=yt%3AdQw4w9WgXcQ&exp=1761900000&sig=abc"}}""",
            ),
        )
        val stream = client(transport).prepare("yt:dQw4w9WgXcQ")
        assertEquals("$base/api/v1/stream?id=yt%3AdQw4w9WgXcQ&exp=1761900000&sig=abc", stream.link)
        assertEquals("audio/mp4", stream.mime)
        assertEquals("m4a", stream.format)
        assertEquals(3456789L, stream.size)
        // A first fetch can take a minute: far longer than a search may.
        assertEquals(ScxytClient.PREPARE_TIMEOUT_MS, transport.timeouts.single())
        assertTrue(ScxytClient.PREPARE_TIMEOUT_MS >= 120_000L)
    }

    @Test
    fun `a link is joined to the base, whatever its slashes`() {
        assertEquals("$base/api/v1/stream?id=x", ScxytClient.absoluteLink(base, "/api/v1/stream?id=x"))
        assertEquals("$base/api/v1/stream?id=x", ScxytClient.absoluteLink("$base/", "/api/v1/stream?id=x"))
        assertEquals("$base/api/v1/stream?id=x", ScxytClient.absoluteLink(base, "api/v1/stream?id=x"))
        // A server behind a path keeps it, as every route here does.
        assertEquals("$base/scxyt/api/v1/stream?id=x", ScxytClient.absoluteLink("$base/scxyt/", "/api/v1/stream?id=x"))
        assertEquals("https://cdn.example/s?id=x", ScxytClient.absoluteLink(base, "https://cdn.example/s?id=x"))
        assertNull(ScxytClient.absoluteLink(base, " "))
    }

    @Test
    fun `a prepare answer without a link is a failure, not an empty stream`() = runBlocking {
        val transport = FakeTransport(ScxytClient.Reply(200, """{"success":true,"data":{"id":"sc:1","format":"mp3"}}"""))
        try {
            client(transport).prepare("sc:1")
            fail("no link should not be playable")
        } catch (e: ScxytError.Failed) {
            assertEquals("no_link", e.code)
        }
    }

    // ── Sign-in ────────────────────────────────────────────────────────

    @Test
    fun `a refused token is refreshed once and the call sent again`() = runBlocking {
        val session = FakeSession()
        val transport = FakeTransport(
            ScxytClient.Reply(401, """{"success":false,"error":"signed_out","message":"Sign in"}"""),
            ScxytClient.Reply(200, searchBody),
        )
        val items = client(transport, session).search("x", ScxytSource.YOUTUBE, 20)
        assertEquals(3, items.size)
        assertEquals(listOf("t1", "t2"), transport.asked.map { it.second })
        // The refresh is asked with the token that was refused.
        assertEquals(listOf(null, "t1"), session.staleAsked)
    }

    @Test
    fun `a second refusal is signed out, without a third try`() = runBlocking {
        val transport = FakeTransport(
            ScxytClient.Reply(401, """{"success":false,"error":"signed_out"}"""),
            ScxytClient.Reply(401, """{"success":false,"error":"signed_out"}"""),
        )
        try {
            client(transport).prepare("yt:dQw4w9WgXcQ")
            fail("should be signed out")
        } catch (e: ScxytError.SignedOut) {
            assertEquals(2, transport.asked.size)
        }
    }

    @Test
    fun `signed out, nothing is sent at all`() = runBlocking {
        val transport = FakeTransport()
        val signedOut = ScxytClient(base, json, transport) { throw ScxytError.SignedOut() }
        try {
            signedOut.search("x", ScxytSource.SOUNDCLOUD, 20)
            fail("should be signed out")
        } catch (e: ScxytError.SignedOut) {
            assertTrue(transport.asked.isEmpty())
        }
    }

    // ── Errors ─────────────────────────────────────────────────────────

    private fun body(code: String) = """{"success":false,"error":"$code","message":"for people"}"""

    @Test
    fun `every error code becomes the error it means`() {
        assertTrue(ScxytError.of(401, body("signed_out")) is ScxytError.SignedOut)
        assertTrue(ScxytError.of(503, body("blocked")) is ScxytError.Blocked)
        assertTrue(ScxytError.of(422, body("unavailable")) is ScxytError.Unavailable)
        assertTrue(ScxytError.of(422, body("too_long")) is ScxytError.TooLong)
        assertTrue(ScxytError.of(404, body("not_found")) is ScxytError.NotFound)
        assertTrue(ScxytError.of(503, body("busy")) is ScxytError.Busy)
        assertTrue(ScxytError.of(502, body("auth_unreachable")) is ScxytError.Busy)
        assertTrue(ScxytError.of(504, body("timeout")) is ScxytError.Timeout)
        val failed = ScxytError.of(502, body("failed"))
        assertTrue(failed is ScxytError.Failed && failed.code == "failed")
        val bad = ScxytError.of(400, body("bad_request"))
        assertTrue(bad is ScxytError.Failed && bad.code == "bad_request")
        // The server's message is kept for the log.
        assertEquals("for people", ScxytError.of(503, body("blocked")).message)
    }

    @Test
    fun `an answer that is not the server's JSON still means something`() {
        assertTrue(ScxytError.of(401, "") is ScxytError.SignedOut)
        assertTrue(ScxytError.of(404, "<html>") is ScxytError.NotFound)
        val gateway = ScxytError.of(502, "<html>Bad gateway</html>")
        assertTrue(gateway is ScxytError.Failed && gateway.code == "http_502")
    }

    @Test
    fun `only what may pass is retried`() {
        assertTrue(ScxytError.Busy().retryable)
        assertTrue(ScxytError.Timeout().retryable)
        assertTrue(ScxytError.Network(java.io.IOException("reset")).retryable)
        assertFalse(ScxytError.Blocked().retryable)
        assertFalse(ScxytError.NotFound().retryable)
        assertFalse(ScxytError.Unavailable().retryable)
        assertFalse(ScxytError.SignedOut().retryable)
    }

    @Test
    fun `a failed answer is thrown as its error, even with a 200`() = runBlocking {
        val transport = FakeTransport(ScxytClient.Reply(503, body("blocked")), ScxytClient.Reply(200, body("busy")))
        try {
            client(transport).search("x", ScxytSource.YOUTUBE, 20)
            fail("blocked should throw")
        } catch (e: ScxytError.Blocked) {
        }
        try {
            client(transport).prepare("yt:dQw4w9WgXcQ")
            fail("busy should throw")
        } catch (e: ScxytError.Busy) {
        }
    }

    @Test
    fun `a page that is not the server's is a failure, not a crash`() = runBlocking {
        val transport = FakeTransport(ScxytClient.Reply(200, "<html>captive portal</html>"))
        try {
            client(transport).search("x", ScxytSource.YOUTUBE, 20)
            fail("should not parse")
        } catch (e: ScxytError.Failed) {
            assertEquals("bad_response", e.code)
        }
    }

    @Test
    fun `each error has its own words, and blocked says so plainly`() {
        assertEquals(R.string.scxyt_error_blocked, ScxytMessages.textRes(ScxytError.Blocked()))
        assertEquals(R.string.scxyt_error_signed_out, ScxytMessages.textRes(ScxytError.SignedOut()))
        assertEquals(R.string.scxyt_error_unavailable, ScxytMessages.textRes(ScxytError.Unavailable()))
        assertEquals(R.string.scxyt_error_too_long, ScxytMessages.textRes(ScxytError.TooLong()))
        assertEquals(R.string.scxyt_error_not_found, ScxytMessages.textRes(ScxytError.NotFound()))
        assertEquals(R.string.scxyt_error_busy, ScxytMessages.textRes(ScxytError.Busy()))
        assertEquals(R.string.scxyt_error_timeout, ScxytMessages.textRes(ScxytError.Timeout()))
        assertEquals(R.string.scxyt_error_failed, ScxytMessages.textRes(ScxytError.Failed("failed")))
        assertEquals(R.string.scxyt_error_failed, ScxytMessages.textRes(ScxytError.Network(java.io.IOException())))
    }

    // ── The queue, history and downloads ───────────────────────────────

    private val song = ScxytTrack(id = "yt:dQw4w9WgXcQ", title = "Song", artist = "Artist", duration = 213.0, cover = "https://i.ytimg.com/x.jpg")
        .toUnifiedTrack()!!

    @Test
    fun `a saved queue or history entry reads back as the same song`() {
        val encoded = json.encodeToString(song)
        assertTrue(encoded.contains("Scxyt"))
        assertEquals(song, json.decodeFromString<UnifiedTrack>(encoded))
    }

    @Test
    fun `a song keeps one queue id, so likes, history and downloads find it`() {
        assertEquals("yt:dQw4w9WgXcQ".hashCode().toLong(), song.legacyId)
        assertEquals(song.legacyId, song.copy(title = "Renamed").legacyId)
        assertEquals(song.legacyId, song.toLegacyTrack().id)
    }

    @Test
    fun `a download carries the server id, and an old queue still reads`() {
        val track: Track = song.toLegacyTrack()
        val item = DownloadItem.from(track, scxytId = "yt:dQw4w9WgXcQ")
        val plain = Json { ignoreUnknownKeys = true }
        val back = plain.decodeFromString<DownloadItem>(plain.encodeToString(item))
        assertEquals("yt:dQw4w9WgXcQ", back.scxytId)
        assertEquals(track.id, back.trackId)
        // Saved before the field existed.
        val old = plain.decodeFromString<DownloadItem>("""{"trackId":5,"title":"T","artistName":"A"}""")
        assertNull(old.scxytId)
    }
}
