package tf.monochrome.android.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiServerTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `a typed address becomes a base url`() {
        assertEquals("https://hifi.example.com", ApiServers.normalizeUrl("hifi.example.com"))
        assertEquals("https://hifi.example.com", ApiServers.normalizeUrl("  https://hifi.example.com/// "))
        assertEquals("http://192.168.1.4:3000", ApiServers.normalizeUrl("http://192.168.1.4:3000/"))
        assertNull(ApiServers.normalizeUrl(""))
        assertNull(ApiServers.normalizeUrl("not a url"))
        assertNull(ApiServers.normalizeUrl("ftp://hifi.example.com"))
        assertNull(ApiServers.normalizeUrl("https://"))
    }

    @Test
    fun `an upgrade keeps the old per-catalog setup`() {
        val servers = ApiServers.fromLegacy(
            tidalUrl = "https://tidal.example.com",
            qobuzUrl = "https://hifi.example.com/",
            appleUrl = null,
        )
        assertEquals(
            listOf(
                ApiServer("https://tidal.example.com", setOf(ApiService.TIDAL)),
                ApiServer("https://hifi.example.com", setOf(ApiService.QOBUZ, ApiService.APPLE, ApiService.DEEZER)),
            ),
            servers,
        )
        assertTrue(ApiServers.fromLegacy(null, null, null).isEmpty())
    }

    @Test
    fun `a separate Apple address keeps priority over the Qobuz server for Apple`() {
        val servers = ApiServers.fromLegacy(null, "https://hifi.example.com", "https://apple.example.com")
        assertEquals("https://apple.example.com", ApiServers.serverFor(servers, ApiService.APPLE)?.url)
        assertEquals("https://hifi.example.com", ApiServers.serverFor(servers, ApiService.QOBUZ)?.url)
    }

    @Test
    fun `the same address in two old fields becomes one server`() {
        val servers = ApiServers.fromLegacy("https://hifi.example.com", "https://hifi.example.com", null)
        assertEquals(1, servers.size)
        assertEquals(ApiService.entries.toSet(), servers.single().services)
    }

    @Test
    fun `the first server serving a service wins`() {
        val servers = listOf(
            ApiServer("https://a", setOf(ApiService.DEEZER)),
            ApiServer("https://b", setOf(ApiService.QOBUZ, ApiService.DEEZER)),
        )
        assertEquals("https://a", ApiServers.serverFor(servers, ApiService.DEEZER)?.url)
        assertEquals("https://b", ApiServers.serverFor(servers, ApiService.QOBUZ)?.url)
        assertNull(ApiServers.serverFor(servers, ApiService.TIDAL))
    }

    @Test
    fun `probe verdicts`() {
        fun v(service: ApiService, status: Int, body: String) = ApiServerProber.verdict(service, status, body, json)

        // TIDAL hifi-api: any JSON search answer.
        assertNull(v(ApiService.TIDAL, 200, """{"version":"2.4","data":{"items":[]}}"""))
        // A Next.js site answering a route it doesn't have.
        assertNotNull(v(ApiService.TIDAL, 404, "<!DOCTYPE html><html></html>"))
        // TrypT HiFi: only success:true counts.
        assertNull(v(ApiService.DEEZER, 200, """{"success":true,"data":{}}"""))
        // Its errors arrive as 400s, and the message is the useful part.
        assertEquals(
            "reported an error: missing developer token",
            v(ApiService.APPLE, 400, """{"success":false,"error":"missing developer token"}"""),
        )
        assertEquals(
            "reported an error: no token",
            v(ApiService.APPLE, 200, """{"success":false,"error":"no token"}"""),
        )
        // A hifi-api server asked for a TrypT HiFi route.
        assertNotNull(v(ApiService.QOBUZ, 404, """{"detail":"Not Found"}"""))
        assertNotNull(v(ApiService.QOBUZ, 200, """{"detail":"ok"}"""))
    }
}
