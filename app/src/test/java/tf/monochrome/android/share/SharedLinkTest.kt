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
