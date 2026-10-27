package tf.monochrome.android.data.repository

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.ThemePackCodec
import tf.monochrome.android.domain.model.ThemePart
import tf.monochrome.android.ui.themestore.SnapshotCodec
import java.io.File

/**
 * The app's half of the Theme Store's contract with its server (`theme-store/`
 * in tryptz/tryptify-servers): what its answers decode to, which errors they
 * stand for, and the limits both sides have to agree on or packs are refused
 * for reasons nobody can see. The server's parts and limits come from
 * `server-contract/theme-store.json`, which that repository's
 * `tools/app-contract.ts` writes.
 */
class ThemeStoreWireTest {
    // As AppModule provides it.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val server = Json.parseToJsonElement(File("src/test/resources/server-contract/theme-store.json").readText()).jsonObject
    private fun bytes(key: String) = server.getValue(key).jsonPrimitive.int

    @Test
    fun `a listing row decodes as the server sends it`() {
        val row = """
            {"id":"6f1c2b9e-3a1d-4c55-9e8f-0a1b2c3d4e5f","user_id":"11111111-1111-4111-8111-111111111111",
             "author_name":"tryptz","name":"Night","description":"dark and quiet",
             "parts":["COLOURS","LYRICS","SOMETHING_NEWER"],"accent":-16776961,"background":-16777216,
             "listed":false,"share_code":"K7QX2MPA","likes":3,"downloads":12,
             "created_at":"2026-10-18T01:40:12.345Z","updated_at":"2026-10-18T01:40:12.345Z"}
        """.trimIndent()
        val pack = json.decodeFromString(WirePack.serializer(), row).toStore()
        assertEquals("Night", pack.name)
        assertEquals("11111111-1111-4111-8111-111111111111", pack.authorId)
        // A part this build does not know is left out, not a failure.
        assertEquals(setOf(ThemePart.COLOURS, ThemePart.LYRICS), pack.parts)
        assertEquals(-16776961, pack.accent)
        assertEquals(false, pack.listed)
        assertEquals("K7QX2MPA", pack.shareCode)
        assertEquals(12, pack.downloads)
    }

    @Test
    fun `the server's own errors read as what they mean`() {
        assertTrue(StoreWire.errorOf(401, """{"error":"signed_out"}""") is StoreError.SignedOut)
        assertTrue(StoreWire.errorOf(409, """{"error":"limit_reached"}""") is StoreError.LimitReached)
        assertTrue(StoreWire.errorOf(400, """{"error":"bad_pack"}""") is StoreError.BadPack)
        assertTrue(StoreWire.errorOf(413, """{"error":"too_big"}""") is StoreError.BadPack)
        assertTrue(StoreWire.errorOf(404, """{"error":"not_found"}""") is StoreError.Gone)
        assertTrue(StoreWire.errorOf(503, """{"error":"not_set_up"}""") is StoreError.NotSetUp)
    }

    @Test
    fun `anything else is the network's, whatever its status`() {
        // The host's own pages: a 404 from the edge is not "this pack was deleted".
        assertTrue(StoreWire.errorOf(404, "<html>Application not found</html>") is StoreError.Network)
        assertTrue(StoreWire.errorOf(502, "Bad Gateway") is StoreError.Network)
        assertTrue(StoreWire.errorOf(500, """{"error":"server_error"}""") is StoreError.Network)
        // Supabase Auth did not answer the server: the session may be fine, so keep it.
        assertTrue(StoreWire.errorOf(502, """{"error":"auth_unreachable"}""") is StoreError.Network)
        assertTrue(StoreWire.errorOf(503, "") is StoreError.Network)
        // But a 401 is a 401 even from something in between.
        assertTrue(StoreWire.errorOf(401, "") is StoreError.SignedOut)
    }

    @Test
    fun `codes are typed loosely and sent clean`() {
        assertEquals("K7QX2MPA", StoreWire.cleanCode(" k7qx-2mpa "))
        assertEquals("", StoreWire.cleanCode("--"))
        assertEquals(16, StoreWire.cleanCode("A".repeat(40)).length)
        assertEquals(listOf("popular", "new", "mine"), StoreSort.entries.map(StoreWire::sortName))
    }

    @Test
    fun `the server accepts exactly the parts this build can put in a pack`() {
        val named = server.getValue("parts").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertEquals(ThemePart.entries.map { it.name }.toSet(), named)
    }

    @Test
    fun `the server takes a store picture as big as the app will send`() {
        val max = bytes("maxSnapshotBytes")
        assertTrue("the app's picture cap must fit under the server's", SnapshotCodec.MAX_BYTES <= max)
        // And a whole request carrying one gets through the body limit.
        val body = bytes("maxBodyBytes")
        assertTrue(body >= max)
        val row = """{"id":"a","snapshot":"0123456789abcdef"}"""
        assertEquals("0123456789abcdef", json.decodeFromString(WirePack.serializer(), row).toStore().snapshot)
        assertEquals(null, json.decodeFromString(WirePack.serializer(), """{"id":"a"}""").toStore().snapshot)
    }

    @Test
    fun `the server takes a pack as big as the app will make`() {
        val max = bytes("maxPackBytes")
        assertEquals(ThemePackCodec.MAX_BYTES, max)
        // A request carries the pack as an escaped JSON string, up to twice its
        // size, with the listing fields beside it.
        val body = bytes("maxBodyBytes")
        assertTrue(body >= 2 * ThemePackCodec.MAX_BYTES + 4096)
    }
}
