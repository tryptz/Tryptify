package tf.monochrome.android.data.api.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The instance's /api/deezer routes (trypt-hifi lib/deezer.tsx) reshapes Deezer
 * into Qobuz envelopes, but not byte for byte: album ids are strings beside a
 * numeric qobuz_id, artist images can be null, and the artist payload has an
 * object-shaped `releases` and a string `id`. One mismatched field fails the
 * whole decode, which the client turns into an empty result — silently. These
 * payloads are what that layer actually emits.
 */
class DeezerModelsTest {

    // Same configuration as AppModule.provideJson.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val album = """
        {"maximum_bit_depth":16,
         "image":{"small":"s.jpg","thumbnail":"t.jpg","large":"l.jpg","back":null},
         "artist":{"image":{"small":"a-s.jpg","medium":"a-m.jpg","large":"a-l.jpg","extralarge":"a-xl.jpg","mega":"a-xl.jpg"},
                   "name":"Daft Punk","id":27,"albums_count":36,"deezer":{"id":27,"link":null}},
         "artists":[{"id":27,"name":"Daft Punk","roles":["main-artist"]}],
         "released_at":1044144000,"label":{"name":"Parlophone","id":0,"albums_count":0},
         "title":"Discovery","qobuz_id":302127,"version":null,"duration":3660,
         "parental_warning":false,"tracks_count":14,
         "genre":{"path":[],"color":"#000000","name":"Electro","id":106},
         "id":"302127","maximum_sampling_rate":44.1,"release_date_original":"2001-03-07",
         "hires":false,"upc":"724384960650","streamable":true,
         "deezer":{"id":302127,"link":"https://www.deezer.com/album/302127"}}
    """.trimIndent()

    private val track = """
        {"isrc":null,"copyright":"","maximum_bit_depth":16,"maximum_sampling_rate":44.1,
         "performer":{"name":"Daft Punk","id":27},
         "album":$album,
         "track_number":1,"released_at":0,"title":"One More Time","version":null,
         "duration":320,"parental_warning":false,"id":3135553,"hires":false,
         "streamable":true,"media_number":1,
         "deezer":{"id":3135553,"link":null,"hasPreview":true}}
    """.trimIndent()

    @Test
    fun `search envelope decodes Deezer albums, tracks and artists`() {
        val body = """
            {"success":true,"data":{"query":"daft punk","switchTo":null,
             "albums":{"limit":10,"offset":0,"total":1,"items":[$album]},
             "tracks":{"limit":10,"offset":0,"total":1,"items":[$track]},
             "artists":{"limit":10,"offset":0,"total":1,"items":[
               {"image":null,"name":"Daft Punk","id":27,"albums_count":36,"deezer":{"id":27,"link":null}}]}}}
        """.trimIndent()

        val env = json.decodeFromString<QobuzSearchEnvelope>(body)

        assertTrue(env.success)
        val a = env.data!!.albums!!.items.single()
        assertEquals(302127L, a.qobuzId)
        assertEquals("302127", a.id)
        assertEquals(27L, a.artist?.id)
        assertEquals("a-l.jpg", a.artist?.image?.large)
        val t = env.data!!.tracks!!.items.single()
        assertEquals(3135553L, t.id)
        assertNull(t.isrc)
        assertEquals(27L, t.performer?.id)
        val ar = env.data!!.artists!!.items.single()
        assertEquals(27L, ar.id)
        assertNull(ar.image)
    }

    @Test
    fun `artist envelope decodes object-shaped releases and a string id`() {
        val body = """
            {"success":true,"data":{"artist":{
              "id":"27","name":{"display":"Daft Punk"},"artist_category":"",
              "biography":{"content":"","source":null,"language":"en"},
              "images":{"portrait":{"hash":"","format":"jpg"}},
              "top_tracks":[$track],
              "releases":{
                "album":{"has_more":false,"items":[$album]},
                "live":{"has_more":false,"items":[]},
                "compilation":{"has_more":false,"items":[]},
                "epSingle":{"has_more":false,"items":[]}}}}}
        """.trimIndent()

        val artist = json.decodeFromString<DeezerArtistEnvelope>(body).data!!.artist!!

        assertEquals("27", artist.id)
        assertEquals("Daft Punk", artist.name?.display)
        assertEquals(3135553L, artist.topTracks.single().id)
        assertEquals(setOf("album", "live", "compilation", "epSingle"), artist.releases.keys)
        assertEquals(302127L, artist.releases["album"]!!.items.single().qobuzId)
    }

    @Test
    fun `preview envelope yields the signed url`() {
        val env = json.decodeFromString<DeezerPreviewEnvelope>(
            """{"success":true,"data":{"url":"https://hifi.example/api/file?u=abc&sig=def"}}"""
        )
        assertEquals("https://hifi.example/api/file?u=abc&sig=def", env.data?.url)
    }
}
