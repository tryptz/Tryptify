package tf.monochrome.android.data.api

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.api.HifiPayload.Kind
import tf.monochrome.android.data.api.model.ArtistResponse
import tf.monochrome.android.data.api.model.LyricsResponse
import tf.monochrome.android.data.api.model.PlaylistResponse

class HifiPayloadTest {

    // The app's own Json settings (AppModule.provideJson).
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    // What hifi-api / TrypT HiFi send for /search/?al= and ?a=: TIDAL's top-hits answer.
    private val topHits = """
        {"version":"2.9","data":{
          "artists":{"limit":2,"offset":0,"totalNumberOfItems":1,"items":[
            {"id":3346,"name":"The Beatles","picture":"a1b2-c3d4","artistTypes":["ARTIST"],"artistRoles":[{"category":"Artist"}],"popularity":90}
          ]},
          "albums":{"limit":2,"offset":0,"totalNumberOfItems":2,"items":[
            {"id":55163243,"title":"Help!","cover":"e5f6-a7b8","releaseDate":"1965-08-06","numberOfTracks":14,"type":"ALBUM","artists":[{"id":3346,"name":"The Beatles"}],"mediaMetadata":{"tags":["LOSSLESS"]}},
            {"id":55163244,"title":"Rubber Soul","cover":"c9d0-e1f2","numberOfTracks":14,"type":"ALBUM"}
          ]},
          "playlists":{"items":[{"uuid":"p-1","title":"Beatles Essentials","creator":{"id":0},"type":"EDITORIAL"}]},
          "tracks":{"items":[{"id":1,"title":"Yesterday"}]},
          "topHit":{"type":"ARTISTS","value":{"id":3346}}
        }}
    """.trimIndent()

    @Test
    fun `album search reads the albums page of a top-hits answer`() {
        val albums = HifiPayload.search(json, topHits, Kind.ALBUMS).items
        assertEquals(listOf("Help!", "Rubber Soul"), albums.map { it.title })
        assertEquals(55163243L, albums.first().id)
        assertEquals("e5f6-a7b8", albums.first().cover)
    }

    @Test
    fun `artist search reads the artists page, not the tracks beside it`() {
        val artists = HifiPayload.search(json, topHits, Kind.ARTISTS).items
        assertEquals(1, artists.size)
        assertEquals("The Beatles", artists.single().name)
        assertEquals("a1b2-c3d4", artists.single().picture)
    }

    @Test
    fun `playlist search reads the playlists page`() {
        val playlists = HifiPayload.search(json, topHits, Kind.PLAYLISTS).items
        assertEquals("p-1", playlists.single().uuid)
    }

    @Test
    fun `track search still reads one flat page`() {
        val body = """{"version":"2.9","data":{"limit":50,"offset":0,"items":[{"id":7,"title":"Yesterday","duration":125}]}}"""
        assertEquals(listOf("Yesterday"), HifiPayload.search(json, body, Kind.TRACKS).items.map { it.title })
    }

    @Test
    fun `similar artists read the bare list under artists`() {
        val body = """{"version":"2.9","artists":[{"id":1,"name":"The Rolling Stones","picture":"x"},{"id":2,"name":"The Kinks"}]}"""
        assertEquals(
            listOf("The Rolling Stones", "The Kinks"),
            HifiPayload.search(json, body, Kind.ARTISTS).items.map { it.name },
        )
    }

    @Test
    fun `one malformed item drops only itself`() {
        val body = """{"data":{"albums":{"items":[{"id":"not-a-number","title":"Bad"},{"id":9,"title":"Good"}]}}}"""
        assertEquals(listOf("Good"), HifiPayload.search(json, body, Kind.ALBUMS).items.map { it.title })
    }

    @Test
    fun `an answer with nothing to read is an empty page`() {
        assertTrue(HifiPayload.search(json, "<html></html>", Kind.ALBUMS).items.isEmpty())
        assertTrue(HifiPayload.search(json, """{"detail":"Not Found"}""", Kind.ALBUMS).items.isEmpty())
    }

    @Test
    fun `the artist route's artist object is lifted out`() {
        val body = """{"version":"2.9","artist":{"id":3346,"name":"The Beatles","picture":"a1b2"},"cover":{"id":3346,"750":"https://x/750x750.jpg"}}"""
        val artist = json.decodeFromString<ArtistResponse>(HifiPayload.entity(json, body, "artist"))
        assertEquals(3346L, artist.id)
        assertEquals("The Beatles", artist.name)
        assertEquals("a1b2", artist.picture)
    }

    @Test
    fun `a playlist keeps its items beside its own fields`() {
        val body = """{"version":"2.9","playlist":{"uuid":"p-1","title":"Essentials","numberOfTracks":2},"items":[{"item":{"id":1,"title":"A"},"type":"track"},{"item":{"id":2,"title":"B"},"type":"track"}]}"""
        val playlist = json.decodeFromString<PlaylistResponse>(HifiPayload.entity(json, body, "playlist"))
        assertEquals("Essentials", playlist.title)
        assertEquals(2, playlist.numberOfTracks)
        assertEquals(listOf("A", "B"), playlist.items?.map { it.item?.title })
    }

    @Test
    fun `lyrics are read from inside the lyrics object, and the old flat answer still reads`() {
        val nested = """{"version":"2.9","lyrics":{"trackId":1,"lyrics":"Yesterday","subtitles":"[00:01.00]Yesterday","isRightToLeft":false}}"""
        val lyrics = json.decodeFromString<LyricsResponse>(HifiPayload.entity(json, nested, "lyrics"))
        assertEquals("[00:01.00]Yesterday", lyrics.subtitles)
        val flat = """{"lyrics":"Yesterday","subtitles":"[00:01.00]Yesterday"}"""
        assertEquals("Yesterday", json.decodeFromString<LyricsResponse>(HifiPayload.entity(json, flat, "lyrics")).lyrics)
    }

    @Test
    fun `a data-wrapped answer is unwrapped as before`() {
        val body = """{"version":"2.9","data":{"id":5,"title":"Help!","items":[]}}"""
        assertEquals("""{"id":5,"title":"Help!","items":[]}""", HifiPayload.entity(json, body, "album"))
    }

    @Test
    fun `recommendations unwrap each track entry`() {
        val body = """{"version":"2.9","data":{"limit":20,"items":[{"track":{"id":11,"title":"Michelle"},"sources":["SUGGESTED_TRACKS"]},{"id":12,"title":"Girl"}]}}"""
        assertEquals(listOf(11L to "Michelle", 12L to "Girl"), HifiPayload.tracks(json, body).map { it.id to it.title })
    }
}
