// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The links TrypT-hifi's share button makes (its lib/share-server.ts,
 * `shareURL`), as this app reads them; anything else is left to the party
 * invites and the rest.
 */
class SharedLinkTest {

    @Test
    fun aTrackLinkNamesItsAlbumAndWhereToStart() {
        // As TrypT-hifi made it, signature and all.
        assertEquals(
            SharedLink(SharedLink.Source.DEEZER, "6575789", 67238728L),
            SharedLink.parse("https", "tryptify.com", "/listen/deezer/album/6575789/track/67238728"),
        )
        assertEquals(
            SharedLink(SharedLink.Source.APPLE, "1440857781", 1440857789L),
            SharedLink.parse("HTTPS", "www.tryptify.com", "/listen/apple/album/1440857781/track/1440857789/"),
        )
    }

    @Test
    fun anAlbumLinkStartsAtItsFirstSong() {
        assertEquals(SharedLink(SharedLink.Source.TIDAL, "1234", null), SharedLink.parse("https", "tryptify.com", "/listen/tidal/album/1234"))
        // A Qobuz album is a slug, not a number.
        assertEquals(SharedLink(SharedLink.Source.QOBUZ, "xw5rk3bkfxjfb", null), SharedLink.parse("https", "tryptify.com", "/listen/qobuz/album/xw5rk3bkfxjfb"))
    }

    @Test
    fun theSongItselfRidesAlongSoAnyServiceCanPlayIt() {
        // As TrypT-hifi made it: ISRC, title, artist and length in the query.
        val query = mapOf("isrc" to "usqx91300101", "title" to " Give Life Back to Music ", "artist" to "Daft Punk", "duration" to "274", "s" to "Q0iqjfn2xWlNUgxv")
        val link = SharedLink.parse("https", "tryptify.com", "/listen/deezer/album/6575789/track/67238728") { query[it] }
        assertEquals(SharedLink(SharedLink.Source.DEEZER, "6575789", 67238728L, "USQX91300101", "Give Life Back to Music", "Daft Punk", 274), link)
        // A bad ISRC or length is dropped; the link still plays from its own service.
        val bad = mapOf("isrc" to "not-an-isrc", "duration" to "-3", "title" to "  ")
        assertEquals(SharedLink(SharedLink.Source.DEEZER, "6575789", null), SharedLink.parse("https", "tryptify.com", "/listen/deezer/album/6575789") { bad[it] })
    }

    @Test
    fun aLinkThisPhoneMakesReadsBackAsTheSameLink() {
        // What the app shares when no TrypT-hifi signs the link: no signature,
        // and it must still open here, song and all.
        fun roundTrip(link: SharedLink): SharedLink? {
            val uri = java.net.URI(SharedLink.url(link))
            val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val (k, v) = it.split('=', limit = 2)
                k to java.net.URLDecoder.decode(v, "UTF-8")
            }
            return SharedLink.parse(uri.scheme, uri.host, uri.path) { query[it] }
        }
        val song = SharedLink(SharedLink.Source.QOBUZ, "xw5rk3bkfxjfb", 12345L, null, "Ça & Ça (Remix) / 1", "Daft Punk", 274)
        assertEquals(song, roundTrip(song))
        val album = SharedLink(SharedLink.Source.TIDAL, "1234", null, title = "Random Access Memories", artist = "Daft Punk")
        assertEquals(album, roundTrip(album))
        assertEquals("https://tryptify.com/listen/tidal/album/1234?title=Random+Access+Memories&artist=Daft+Punk", SharedLink.url(album))
    }

    @Test
    fun catalogLinksAreWrittenAsTheyAlwaysWere() {
        // Byte for byte what TrypT-hifi makes and older builds shared.
        assertEquals(
            "https://tryptify.com/listen/deezer/album/6575789/track/67238728?isrc=USQX91300101&title=Give+Life+Back+to+Music&artist=Daft+Punk&duration=274",
            SharedLink.url(SharedLink(SharedLink.Source.DEEZER, "6575789", 67238728L, "USQX91300101", "Give Life Back to Music", "Daft Punk", 274)),
        )
        assertEquals(
            "https://tryptify.com/listen/qobuz/album/xw5rk3bkfxjfb/track/12345?title=%C3%87a+%26+%C3%87a+%28Remix%29+%2F+1&artist=Daft+Punk&duration=274",
            SharedLink.url(SharedLink(SharedLink.Source.QOBUZ, "xw5rk3bkfxjfb", 12345L, null, "Ça & Ça (Remix) / 1", "Daft Punk", 274)),
        )
        // An album link carries no length, and no link at all leaves no "?".
        assertEquals(
            "https://tryptify.com/listen/apple/album/1440857781?artist=Daft+Punk",
            SharedLink.url(SharedLink(SharedLink.Source.APPLE, "1440857781", null, artist = "Daft Punk", durationSec = 274)),
        )
        assertEquals("https://tryptify.com/listen/tidal/album/1234", SharedLink.url(SharedLink(SharedLink.Source.TIDAL, "1234", null)))
    }

    @Test
    fun aYoutubeSongIsSharedAsTheSongAlone() {
        val song = SharedLink(
            SharedLink.Source.YOUTUBE, album = null, track = null,
            title = "One More Time", artist = "Daft Punk", durationSec = 320, song = "FGBhQbmPwH8",
        )
        // The website's shape: /listen/youtube/track/<videoId>, no ISRC, no page.
        assertEquals(
            "https://tryptify.com/listen/youtube/track/FGBhQbmPwH8?title=One+More+Time&artist=Daft+Punk&duration=320",
            SharedLink.url(song),
        )
        assertEquals(song, roundTrip(song))
        assertEquals("yt:FGBhQbmPwH8", song.mediaId)
        // Ids with - and _ are video ids too, and a trailing slash is the same link.
        assertEquals(
            SharedLink(SharedLink.Source.YOUTUBE, null, null, song = "a-B_c1D2e3F"),
            SharedLink.parse("https", "www.tryptify.com", "/listen/youtube/track/a-B_c1D2e3F/"),
        )
        // A page is SoundCloud's alone: a YouTube song's comes from its id.
        val withPage = mapOf("page" to "https://soundcloud.com/daftpunk/one-more-time")
        assertNull(SharedLink.parse("https", "tryptify.com", "/listen/youtube/track/FGBhQbmPwH8") { withPage[it] }?.page)
        assertEquals(
            "https://tryptify.com/listen/youtube/track/FGBhQbmPwH8",
            SharedLink.url(SharedLink(SharedLink.Source.YOUTUBE, null, null, song = "FGBhQbmPwH8", page = "https://soundcloud.com/a/b")),
        )
    }

    @Test
    fun aSoundcloudSongCarriesItsPageWhenItIsOne() {
        val song = SharedLink(
            SharedLink.Source.SOUNDCLOUD, album = null, track = null,
            title = "Strobe (Club Edit)", artist = "deadmau5", durationSec = 637, song = "199428706",
            page = "https://soundcloud.com/deadmau5/strobe-club-edit",
        )
        assertEquals(
            "https://tryptify.com/listen/soundcloud/track/199428706?title=Strobe+%28Club+Edit%29&artist=deadmau5&duration=637" +
                "&page=https%3A%2F%2Fsoundcloud.com%2Fdeadmau5%2Fstrobe-club-edit",
            SharedLink.url(song),
        )
        assertEquals(song, roundTrip(song))
        assertEquals("sc:199428706", song.mediaId)
        // Twenty digits is still a track number, beyond what a Long holds.
        assertEquals("98765432109876543210", SharedLink.parse("https", "tryptify.com", "/listen/soundcloud/track/98765432109876543210")?.song)
        // Any other page is dropped, not the link: another host, plain http,
        // a private song's secret path, or more than an artist and a song.
        for (page in listOf(
            "https://evil.example/deadmau5/strobe", "http://soundcloud.com/deadmau5/strobe",
            "https://soundcloud.com/deadmau5/strobe/s-AbCdEf", "https://soundcloud.com/deadmau5",
            "https://soundcloud.com/deadmau5/strobe?in=x", "https://m.soundcloud.com/deadmau5/strobe",
        )) {
            val link = SharedLink.parse("https", "tryptify.com", "/listen/soundcloud/track/199428706") { if (it == "page") page else null }
            assertEquals(page, SharedLink(SharedLink.Source.SOUNDCLOUD, null, null, song = "199428706"), link)
            assertEquals(page, "https://tryptify.com/listen/soundcloud/track/199428706", SharedLink.url(song.copy(title = null, artist = null, durationSec = null, page = page)))
        }
    }

    @Test
    fun aSongLinkWithABadIdIsNoLink() {
        for (path in listOf(
            "/listen/youtube/track/dQw4w9WgXc", // 10 characters
            "/listen/youtube/track/dQw4w9WgXcQQ", // 12
            "/listen/youtube/track/dQw4w9WgX.Q",
            "/listen/youtube/track/dQw4w9WgXcQ/more",
            "/listen/soundcloud/track/12a",
            "/listen/soundcloud/track/123456789012345678901", // 21 digits
            "/listen/soundcloud/track/",
            // A song service in a catalog's shape, and a catalog in a song's.
            "/listen/youtube/album/123/track/456",
            "/listen/soundcloud/album/123",
            "/listen/tidal/track/123",
            "/listen/spotify/track/123",
        )) {
            assertNull(path, SharedLink.parse("https", "tryptify.com", path))
        }
        assertNull(SharedLink.parse("https", "youtube.com", "/listen/youtube/track/dQw4w9WgXcQ"))
        assertNull(SharedLink.parse("http", "tryptify.com", "/listen/youtube/track/dQw4w9WgXcQ"))
        assertNull(SharedLink.parse("https", "tryptify.com.evil.example", "/listen/soundcloud/track/1"))
    }

    // What the app shares, read back as Android reads an opened link.
    private fun roundTrip(link: SharedLink): SharedLink? {
        val uri = java.net.URI(SharedLink.url(link))
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val (k, v) = it.split('=', limit = 2)
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }
        return SharedLink.parse(uri.scheme, uri.host, uri.path) { query[it] }
    }

    @Test
    fun anAlbumFoundByNameIsTheSameAlbumByTheSameArtist() {
        assertTrue(SharedLinkResolver.sameAlbum("Random Access Memories (10th Anniversary Edition)", "Daft Punk", "Random Access Memories", "Daft Punk"))
        assertTrue(SharedLinkResolver.sameAlbum("Random Access Memories", null, "Random Access Memories", "Daft Punk"))
        assertFalse(SharedLinkResolver.sameAlbum("Random Access Memories", "Some Tribute Band", "Random Access Memories", "Daft Punk"))
        assertFalse(SharedLinkResolver.sameAlbum("Discovery", "Daft Punk", "Random Access Memories", "Daft Punk"))
    }

    @Test
    fun anythingElseIsNotASharedLink() {
        // Another site, plain http, or no path.
        assertNull(SharedLink.parse("https", "example.com", "/listen/deezer/album/1"))
        assertNull(SharedLink.parse("http", "tryptify.com", "/listen/deezer/album/1"))
        assertNull(SharedLink.parse("https", "tryptify.com", null))
        // A catalog TrypT-hifi doesn't share from, or ids it doesn't make.
        assertNull(SharedLink.parse("https", "tryptify.com", "/listen/spotify/album/1"))
        assertNull(SharedLink.parse("https", "tryptify.com", "/listen/deezer/album/abc"))
        assertNull(SharedLink.parse("https", "tryptify.com", "/listen/deezer/album/1/track/abc"))
        assertNull(SharedLink.parse("https", "tryptify.com", "/listen/deezer/track/1"))
        // The party's own links stay the party's.
        assertNull(SharedLink.parse("https", "tryptify.com", "/party/K7QD"))
    }
}
