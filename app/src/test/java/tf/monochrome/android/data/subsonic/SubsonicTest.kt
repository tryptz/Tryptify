package tf.monochrome.android.data.subsonic

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tf.monochrome.android.data.subsonic.SubsonicRepository.Companion.codecFor
import tf.monochrome.android.data.subsonic.SubsonicRepository.Companion.toServerPlaylist
import tf.monochrome.android.data.subsonic.SubsonicRepository.Companion.toUnifiedAlbum
import tf.monochrome.android.data.subsonic.SubsonicRepository.Companion.toUnifiedTrack
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * The Subsonic source: signing in, the URLs, what a server's JSON becomes, and
 * the rule that no sign-in ever rides along in a song's artwork.
 */
class SubsonicTest {

    /** The app's own Json (AppModule.provideJson). */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val server = Subsonic.tokenServer("http://192.168.1.20:4533", "joe", "sesame", salt = "c19b2d")

    // ── Signing in ──────────────────────────────────────────────────────

    /** The worked example in the Subsonic API's own documentation. */
    @Test
    fun `the token is md5 of password and salt`() {
        assertEquals("26719a1196d2a940705a59634eb18eab", Subsonic.token("sesame", "c19b2d"))
    }

    @Test
    fun `a token server keeps no password`() {
        assertEquals(SubsonicAuthMode.TOKEN, server.authMode)
        assertEquals("", server.hexPassword)
        assertFalse(json.encodeToString(server).contains("sesame"))
    }

    @Test
    fun `salts are fresh each time`() {
        assertTrue(Subsonic.newSalt() != Subsonic.newSalt())
        assertEquals(16, Subsonic.newSalt().length)
    }

    @Test
    fun `token requests carry the token, the salt and API 1_13_0`() {
        val q = Subsonic.authQuery(server)
        assertTrue(q, "u=joe" in q && "t=26719a1196d2a940705a59634eb18eab" in q && "s=c19b2d" in q)
        assertTrue(q, "v=1.13.0" in q && "c=Tryptify" in q && "f=json" in q)
        assertFalse(q, "p=" in q)
    }

    @Test
    fun `password requests send the hex password at API 1_8_0`() {
        val q = Subsonic.authQuery(Subsonic.passwordServer("http://h", "joe", "sesame"))
        assertTrue(q, "p=enc%3A736573616d65" in q && "v=1.8.0" in q)
        assertFalse(q, "t=" in q)
    }

    @Test
    fun `only a refused token falls back to the password`() {
        for (code in listOf(30, 41, 42)) assertTrue(Subsonic.wantsPassword(SubsonicException(code, null)))
        for (code in listOf(null, 0, 40, 50, 70)) assertFalse(Subsonic.wantsPassword(SubsonicException(code, null)))
    }

    // ── Addresses ───────────────────────────────────────────────────────

    @Test
    fun `an address at home gets http, one on the internet https`() {
        assertEquals("http://192.168.1.20:4533", Subsonic.normalizeUrl("192.168.1.20:4533"))
        assertEquals("http://localhost:4533", Subsonic.normalizeUrl("localhost:4533"))
        assertEquals("http://nas.local", Subsonic.normalizeUrl("nas.local"))
        assertEquals("http://music.example.com:4040", Subsonic.normalizeUrl("music.example.com:4040"))
        assertEquals("https://music.example.com", Subsonic.normalizeUrl("music.example.com"))
    }

    @Test
    fun `a typed scheme is kept`() {
        assertEquals("https://192.168.1.20:4533", Subsonic.normalizeUrl("https://192.168.1.20:4533/"))
        assertEquals("http://music.example.com", Subsonic.normalizeUrl("http://music.example.com"))
    }

    @Test
    fun `an address copied from the browser loses the page but keeps the base path`() {
        assertEquals("http://192.168.1.20:4533", Subsonic.normalizeUrl("http://192.168.1.20:4533/app/#/album/123/show"))
        assertEquals("http://192.168.1.20:4533", Subsonic.normalizeUrl("http://192.168.1.20:4533/rest/"))
        assertEquals("https://example.com/airsonic", Subsonic.normalizeUrl("https://example.com/airsonic/index.view"))
        assertEquals("https://example.com/airsonic", Subsonic.normalizeUrl("https://example.com/airsonic/login"))
        assertEquals("https://example.com/music", Subsonic.normalizeUrl("https://example.com/music?x=1"))
    }

    @Test
    fun `something that is not an address is refused`() {
        for (bad in listOf("", "   ", "ftp://example.com", "http://", "two words", "file:///sdcard/music")) {
            assertNull(bad, Subsonic.normalizeUrl(bad))
        }
    }

    @Test
    fun `requests go to rest, with parameters encoded`() {
        val url = Subsonic.endpoint(server, "search3", listOf("query" to "AC/DC & co"))
        assertTrue(url, url.startsWith("http://192.168.1.20:4533/rest/search3.view?u=joe&"))
        assertTrue(url, url.endsWith("&query=AC%2FDC+%26+co"))
    }

    @Test
    fun `the stream is the original file unless a cap is asked for`() {
        val original = Subsonic.streamUrl(server, "s1")
        assertTrue(original, original.endsWith("&id=s1"))
        assertFalse(original, "maxBitRate" in original || "format" in original)

        val capped = Subsonic.streamUrl(server, "s1", maxBitRateKbps = 192)
        assertTrue(capped, capped.endsWith("&id=s1&maxBitRate=192&format=mp3"))
    }

    @Test
    fun `now playing is a scrobble without submission or time`() {
        assertEquals(
            listOf("id" to "s1", "submission" to "false"),
            Subsonic.scrobbleParams("s1", submission = false, timeMillis = 1_700_000_000_000),
        )
    }

    @Test
    fun `a counted play is stamped with when it started`() {
        assertEquals(
            listOf("id" to "s1", "submission" to "true", "time" to "1700000000000"),
            Subsonic.scrobbleParams("s1", submission = true, timeMillis = 1_700_000_000_000),
        )
        assertEquals(listOf("id" to "s1", "submission" to "true"), Subsonic.scrobbleParams("s1", submission = true))
    }

    // ── Artwork never carries the sign-in ───────────────────────────────

    @Test
    fun `a cover reference holds the id and nothing else`() {
        val ref = Subsonic.coverArtRef("al-12 3")!!
        assertEquals("subsonic-cover://al-12+3", ref)
        assertEquals("al-12 3", Subsonic.coverArtId(ref))
        assertNull(Subsonic.coverArtId("https://example.com/cover.jpg"))
        assertNull(Subsonic.coverArtRef(""))
    }

    @Test
    fun `the real cover URL is made only from the reference and the server`() {
        val url = Subsonic.coverArtUrl(server, Subsonic.coverArtId(Subsonic.coverArtRef("al-1")))!!
        assertTrue(url, url.startsWith("http://192.168.1.20:4533/rest/getCoverArt.view?") && "id=al-1" in url)
        // The URL is the same every time for the same sign-in, so the image cache finds it again.
        assertEquals(url, Subsonic.coverArtUrl(server, "al-1"))
    }

    @Test
    fun `nothing mapped from the server carries the token or the password`() {
        val passwordServer = Subsonic.passwordServer("http://h", "joe", "sesame")
        val track = song.toUnifiedTrack()
        val album = albumWithSongs.toUnifiedAlbum()
        val playlist = SubsonicPlaylist(id = "p1", name = "Mix", coverArt = "pl-p1").toServerPlaylist()
        for (text in listOf(json.encodeToString(track), track.artworkUri.orEmpty(), album.artworkUri.orEmpty(), playlist.toString())) {
            assertFalse(text, server.token in text || passwordServer.hexPassword in text || "sesame" in text)
            assertFalse(text, "getCoverArt" in text)
        }
    }

    // ── What the server's JSON becomes ──────────────────────────────────

    private val song = SubsonicSong(
        id = "tr-1", title = "Song", album = "Album", albumId = "al-1", artist = "Artist",
        track = 3, discNumber = 1, year = 2020, coverArt = "al-1", duration = 241,
        bitRate = 1000, suffix = "flac", samplingRate = 96_000, bitDepth = 24,
    )

    private val albumWithSongs = SubsonicAlbum(id = "al-1", name = "Album", artist = "Artist", songCount = 1, song = listOf(song))

    @Test
    fun `a song becomes a playable unified track`() {
        val t = song.toUnifiedTrack()
        assertEquals("subsonic_tr-1", t.id)
        assertEquals(SourceType.SUBSONIC, t.sourceType)
        assertEquals(PlaybackSource.Subsonic(songId = "tr-1", suffix = "flac", bitRateKbps = 1000), t.source)
        assertEquals("subsonic_album_al-1", t.albumId)
        assertEquals(AudioCodec.FLAC, t.codec)
        assertEquals("FLAC 24/96", t.qualityBadge)
        assertEquals("subsonic-cover://al-1", t.artworkUri)
        // String ids: never mistaken for a catalogue artist or album.
        assertNull(t.artistId)
        assertTrue(t.artists.all { it.id == null })
    }

    @Test
    fun `a track id is the same every time, so the queue and likes keep it`() {
        assertEquals(song.toUnifiedTrack().legacyId, song.copy(title = "Renamed").toUnifiedTrack().legacyId)
    }

    @Test
    fun `a saved queue entry reads back as the same Subsonic song`() {
        val t = song.toUnifiedTrack()
        assertEquals(t, json.decodeFromString<UnifiedTrack>(json.encodeToString(t)))
        assertTrue(json.encodeToString(t.source).contains("Subsonic"))
    }

    @Test
    fun `a missing artist reads as unknown, and an album fills what the song leaves out`() {
        val bare = SubsonicSong(id = "x")
        assertEquals("Unknown Artist", bare.toUnifiedTrack().artistName)
        assertEquals("x", bare.toUnifiedTrack().title)
        val filled = bare.toUnifiedTrack(albumWithSongs)
        assertEquals("Artist", filled.artistName)
        assertEquals("Album", filled.albumTitle)
        assertEquals("subsonic-cover://al-1", filled.artworkUri)
    }

    @Test
    fun `codecs come from the suffix, and an m4a with a bit depth is ALAC`() {
        assertEquals(AudioCodec.MP3, codecFor("MP3", null))
        assertEquals(AudioCodec.AAC, codecFor("m4a", null))
        assertEquals(AudioCodec.ALAC, codecFor("m4a", 16))
        assertEquals(AudioCodec.OPUS, codecFor("opus", null))
        assertNull(codecFor("xyz", null))
        assertNull(codecFor(null, null))
    }

    // ── Playlists, likes and lyrics ─────────────────────────────────────

    @Test
    fun `playlists, a playlist's songs and starred songs parse`() {
        val lists = SubsonicClient.parse(
            json,
            """{"subsonic-response":{"status":"ok","playlists":{"playlist":[
                {"id":"p1","name":"Road","songCount":2,"duration":400,"coverArt":"pl-p1","public":true}]}}}""",
        )
        assertEquals("Road", lists.playlists!!.playlist.single().name)
        assertEquals("subsonic-cover://pl-p1", lists.playlists!!.playlist.single().toServerPlaylist().artworkUri)

        val one = SubsonicClient.parse(
            json,
            """{"subsonic-response":{"status":"ok","playlist":{"id":"p1","name":"Road",
                "entry":[{"id":"s1","title":"A"},{"id":"s2","title":"B"}]}}}""",
        )
        assertEquals(listOf("s1", "s2"), one.playlist!!.entry.map { it.id })

        val starred = SubsonicClient.parse(
            json,
            """{"subsonic-response":{"status":"ok","starred2":{"song":[{"id":"s9","title":"Fav","starred":"2026-01-01T00:00:00Z"}]}}}""",
        )
        assertEquals("subsonic_s9", starred.starred2!!.song.single().toUnifiedTrack().id)
    }

    @Test
    fun `timed server lyrics keep their times, less the offset`() {
        val r = SubsonicClient.parse(
            json,
            """{"subsonic-response":{"status":"ok","lyricsList":{"structuredLyrics":[
                {"lang":"eng","synced":false,"line":[{"value":"plain one"}]},
                {"lang":"eng","synced":true,"offset":100,"line":[
                  {"start":2000,"value":"second"},{"start":1000,"value":"first"},{"start":1500,"value":" "}]}]}}}""",
        )
        val lyrics = SubsonicRepository.pickLyrics(r.lyricsList!!.structuredLyrics)!!
        assertTrue(lyrics.isSynced)
        assertEquals(listOf("first", "second"), lyrics.lines.map { it.text })
        assertEquals(listOf(900L, 1_900L), lyrics.lines.map { it.timeMs })
    }

    @Test
    fun `untimed server lyrics keep their verses, and all-zero times are untimed`() {
        val plain = SubsonicStructuredLyrics(synced = false, line = listOf("", "verse one", "", "verse two", "").map { SubsonicLyricLine(value = it) })
        with(SubsonicRepository) {
            val lyrics = plain.toLyrics()!!
            assertFalse(lyrics.isSynced)
            assertEquals(listOf("verse one", "", "verse two"), lyrics.lines.map { it.text })
            val zero = SubsonicStructuredLyrics(synced = true, line = listOf(SubsonicLyricLine(0, "a"), SubsonicLyricLine(0, "b")))
            assertFalse(zero.toLyrics()!!.isSynced)
            assertNull(SubsonicStructuredLyrics(synced = true).toLyrics())
        }
    }

    // ── Parsing ─────────────────────────────────────────────────────────

    @Test
    fun `an ok answer parses, extras and all`() {
        val body = """
            {"subsonic-response":{"status":"ok","version":"1.16.1","type":"navidrome",
             "serverVersion":"0.53.3","openSubsonic":true,
             "albumList2":{"album":[{"id":"a1","name":"One","artist":"X","songCount":"9",
               "coverArt":"al-a1","genres":[{"name":"Rock"}],"isrc":["US1"]}]}}}
        """.trimIndent()
        val r = SubsonicClient.parse(json, body)
        assertEquals("navidrome", r.type)
        assertEquals("One", r.albumList2!!.album.single().displayName)
        assertEquals(9, r.albumList2!!.album.single().songCount)
    }

    @Test
    fun `an album named by title rather than name still has a name`() {
        val body = """{"subsonic-response":{"status":"ok","album":{"id":"a","title":"Old style"}}}"""
        assertEquals("Old style", SubsonicClient.parse(json, body).album!!.displayName)
    }

    @Test
    fun `a failed answer throws with its code`() {
        val body = """{"subsonic-response":{"status":"failed","version":"1.16.1",
            "error":{"code":40,"message":"Wrong username or password"}}}"""
        try {
            SubsonicClient.parse(json, body)
            fail("expected a SubsonicException")
        } catch (e: SubsonicException) {
            assertEquals(40, e.code)
        }
    }

    @Test
    fun `a page that is not a Subsonic server does not parse`() {
        for (body in listOf("<html><body>Router login</body></html>", """{"hello":"world"}""")) {
            try {
                SubsonicClient.parse(json, body)
                fail("expected a parse failure for $body")
            } catch (e: SerializationException) {
                // the repository reports this as NOT_SUBSONIC
            } catch (e: IllegalArgumentException) {
                // likewise
            }
        }
    }
}
