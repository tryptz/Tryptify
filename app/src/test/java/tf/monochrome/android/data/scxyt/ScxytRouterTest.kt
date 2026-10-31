package tf.monochrome.android.data.scxyt

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.SourceType
import java.io.File

/**
 * Which way each YouTube and SoundCloud call goes: YouTube to the phone,
 * signed in or not; SoundCloud to the server, which without a sign-in sends
 * nothing and leaves the source off.
 */
class ScxytRouterTest {

    /** Records what it was asked; fails every call with [error] when given one. */
    private class FakeBackend(private val name: String, private val error: ScxytError? = null) : ScxytBackend {
        val asked = mutableListOf<String>()

        override suspend fun search(query: String, source: ScxytSource, limit: Int): List<ScxytTrack> {
            asked += "search:${source.wire}:$query"
            error?.let { throw it }
            val id = if (source == ScxytSource.YOUTUBE) "yt:dQw4w9WgXcQ" else "sc:123456"
            return listOf(ScxytTrack(id = id, title = "$name song", artist = "Artist", duration = 200.0))
        }

        override suspend fun prepare(id: String): ScxytStream {
            asked += "prepare:$id"
            error?.let { throw it }
            return ScxytStream(id, "m4a", "audio/mp4", 1, "$name://$id", file = if (name == "phone") File("/cache/youtube/x.m4a") else null)
        }
    }

    @Test
    fun `YouTube is searched on the phone, SoundCloud on the server`() = runBlocking {
        val server = FakeBackend("server")
        val phone = FakeBackend("phone")
        val router = ScxytRouter(server = server, youtube = phone)

        val youtube = router.search("daft punk", ScxytSource.YOUTUBE, 20).getOrThrow()
        val soundcloud = router.search("daft punk", ScxytSource.SOUNDCLOUD, 20).getOrThrow()

        assertEquals(listOf("search:youtube:daft punk"), phone.asked)
        assertEquals(listOf("search:soundcloud:daft punk"), server.asked)
        assertEquals("phone song", youtube.single().title)
        assertEquals(SourceType.YOUTUBE, youtube.single().sourceType)
        assertEquals(SourceType.SOUNDCLOUD, soundcloud.single().sourceType)
    }

    @Test
    fun `signed out, YouTube still answers and only SoundCloud is off`() = runBlocking {
        // The server's client fails before sending anything without a session.
        val server = FakeBackend("server", ScxytError.SignedOut())
        val router = ScxytRouter(server = server, youtube = FakeBackend("phone"))

        assertEquals(1, router.search("x", ScxytSource.YOUTUBE, 20).getOrThrow().size)
        assertTrue(router.search("x", ScxytSource.SOUNDCLOUD, 20).exceptionOrNull() is ScxytError.SignedOut)
        assertTrue(router.prepare("yt:dQw4w9WgXcQ").isSuccess)
        assertTrue(router.prepare("sc:123456").exceptionOrNull() is ScxytError.SignedOut)
    }

    @Test
    fun `a yt song is fetched on the phone, an sc song by the server`() = runBlocking {
        val server = FakeBackend("server")
        val phone = FakeBackend("phone")
        val router = ScxytRouter(server = server, youtube = phone)

        val yt = router.prepare("yt:dQw4w9WgXcQ").getOrThrow()
        val sc = router.prepare("sc:123456").getOrThrow()

        assertEquals(listOf("prepare:yt:dQw4w9WgXcQ"), phone.asked)
        assertEquals(listOf("prepare:sc:123456"), server.asked)
        assertEquals("phone://yt:dQw4w9WgXcQ", yt.link)
        assertEquals("server://sc:123456", sc.link)
        // An id neither service hands out is the server's to refuse, as before.
        router.prepare("nonsense")
        assertEquals("prepare:nonsense", server.asked.last())
    }

    @Test
    fun `a failure on the phone comes back as its error, anything unexpected as the network's`() = runBlocking {
        val blocked = ScxytRouter(server = FakeBackend("server"), youtube = FakeBackend("phone", ScxytError.Blocked("bot check")))
        assertTrue(blocked.prepare("yt:dQw4w9WgXcQ").exceptionOrNull() is ScxytError.Blocked)
        val odd = ScxytRouter(
            server = FakeBackend("server"),
            youtube = object : ScxytBackend {
                override suspend fun search(query: String, source: ScxytSource, limit: Int): List<ScxytTrack> = throw IllegalStateException("odd")
                override suspend fun prepare(id: String): ScxytStream = throw IllegalStateException("odd")
            },
        )
        assertTrue(odd.search("x", ScxytSource.YOUTUBE, 20).exceptionOrNull() is ScxytError.Network)
    }

    @Test
    fun `YouTube turning the phone away says so, SoundCloud turning the server away says that`() {
        assertEquals(R.string.scxyt_error_blocked_network, ScxytMessages.textRes(ScxytError.Blocked(), ScxytSource.YOUTUBE))
        assertEquals(R.string.scxyt_error_blocked, ScxytMessages.textRes(ScxytError.Blocked(), ScxytSource.SOUNDCLOUD))
        assertEquals(R.string.scxyt_error_not_found, ScxytMessages.textRes(ScxytError.NotFound(), ScxytSource.YOUTUBE))
    }
}
