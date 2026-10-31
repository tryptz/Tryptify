// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.share

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.scxyt.ScxytTrack
import tf.monochrome.android.data.scxyt.toUnifiedTrack
import tf.monochrome.android.domain.model.Album
import tf.monochrome.android.domain.model.AlbumDetail
import tf.monochrome.android.domain.model.Artist
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * YouTube and SoundCloud songs as links: what Share link makes for one, and
 * what such a link (or a catalog song no service here has) plays, signed in
 * to SoundCloud or not, without a network.
 */
class SharedSongResolverTest {

    // ── Sharing ────────────────────────────────────────────────────────

    @Test
    fun `a YouTube song shares its video id, title, artist and length`() {
        val unified = youtube("FGBhQbmPwH8", "One More Time", "Daft Punk", 320)
        val link = ShareLinks.songLink(unified.toLegacyTrack(), unified.source as PlaybackSource.Scxyt, unified.pageUrl)
        assertEquals(
            SharedLink(SharedLink.Source.YOUTUBE, null, null, title = "One More Time", artist = "Daft Punk", durationSec = 320, song = "FGBhQbmPwH8"),
            link,
        )
        assertEquals(
            "https://tryptify.com/listen/youtube/track/FGBhQbmPwH8?title=One+More+Time&artist=Daft+Punk&duration=320",
            SharedLink.url(link!!),
        )
    }

    @Test
    fun `a SoundCloud song shares the page the server found it at`() {
        // The server's Track.url is the song's soundcloud.com permalink, and
        // it rides on the track from the search to the share button.
        val unified = ScxytTrack(
            id = "sc:199428706", title = "Strobe (Club Edit)", artist = "deadmau5", duration = 637.0,
            url = "https://soundcloud.com/deadmau5/strobe-club-edit",
        ).toUnifiedTrack()!!
        assertEquals("https://soundcloud.com/deadmau5/strobe-club-edit", unified.pageUrl)
        val link = ShareLinks.songLink(unified.toLegacyTrack(), unified.source as PlaybackSource.Scxyt, unified.pageUrl)!!
        assertEquals(SharedLink.Source.SOUNDCLOUD, link.source)
        assertEquals("199428706", link.song)
        assertEquals("https://soundcloud.com/deadmau5/strobe-club-edit", link.page)

        // A page the website would not link to is left out; the song still shares.
        val secret = ShareLinks.songLink(unified.toLegacyTrack(), unified.source as PlaybackSource.Scxyt, "https://soundcloud.com/deadmau5/strobe/s-x1")
        assertNull(secret!!.page)
        // A YouTube song's page is made from its id, so it keeps none.
        assertNull(youtube("FGBhQbmPwH8", "One More Time", "Daft Punk", 320).pageUrl)
    }

    @Test
    fun `an id neither service hands out has no link`() {
        val track = Track(id = 1, title = "x", duration = 10, artist = Artist(1, "y"))
        assertNull(ShareLinks.songLink(track, PlaybackSource.Scxyt("yt:short"), null))
        assertNull(ShareLinks.songLink(track, PlaybackSource.Scxyt("sp:123"), null))
    }

    // ── Opening ────────────────────────────────────────────────────────

    /** Records what it was asked; signed in or not, YouTube answering [results]. */
    private class Fakes(var signedIn: Boolean, val results: List<UnifiedTrack> = emptyList()) {
        val searched = mutableListOf<String>()
        var askedSignIn = 0
        var askedCatalogs = 0
        val songs = SharedSongResolver(
            signedIn = { askedSignIn++; signedIn },
            searchYoutube = { query -> searched += query; results },
        )

        suspend fun route(link: SharedLink, album: SharedLinkResolver.Resolved.Album? = null) =
            SharedLinkResolver.route(link, songs) { askedCatalogs++; album }
    }

    private val ytLink = SharedLink(SharedLink.Source.YOUTUBE, null, null, title = "One More Time", artist = "Daft Punk", durationSec = 320, song = "FGBhQbmPwH8")
    private val scLink = SharedLink(
        SharedLink.Source.SOUNDCLOUD, null, null, title = "Strobe (Club Edit)", artist = "deadmau5", durationSec = 637, song = "199428706",
        page = "https://soundcloud.com/deadmau5/strobe-club-edit",
    )

    @Test
    fun `a YouTube link plays that song, signed in or not`() = runBlocking {
        val fakes = Fakes(signedIn = false)
        val found = fakes.route(ytLink) as SharedLinkResolver.Resolved.Song
        assertFalse(found.substitute)
        assertEquals("yt:FGBhQbmPwH8", found.track.id)
        assertEquals(PlaybackSource.Scxyt("yt:FGBhQbmPwH8"), found.track.source)
        assertEquals(SourceType.YOUTUBE, found.track.sourceType)
        // Named as the link names it, with the picture the website shows.
        assertEquals("One More Time", found.track.title)
        assertEquals("Daft Punk", found.track.artistName)
        assertEquals(320, found.track.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/FGBhQbmPwH8/hqdefault.jpg", found.track.artworkUri)
        // YouTube needs no sign-in, no catalog and no search.
        assertEquals(0, fakes.askedSignIn)
        assertEquals(0, fakes.askedCatalogs)
        assertTrue(fakes.searched.isEmpty())
    }

    @Test
    fun `signed in, a SoundCloud link plays from SoundCloud`() = runBlocking {
        val fakes = Fakes(signedIn = true)
        val found = fakes.route(scLink) as SharedLinkResolver.Resolved.Song
        assertFalse(found.substitute)
        assertEquals(PlaybackSource.Scxyt("sc:199428706"), found.track.source)
        assertEquals(SourceType.SOUNDCLOUD, found.track.sourceType)
        assertEquals("Strobe (Club Edit)", found.track.title)
        // Kept, so the song shared on from here keeps its page.
        assertEquals("https://soundcloud.com/deadmau5/strobe-club-edit", found.track.pageUrl)
        assertNull(found.track.artworkUri)
        assertTrue(fakes.searched.isEmpty())
        assertEquals(0, fakes.askedCatalogs)
    }

    @Test
    fun `signed out, a SoundCloud link plays YouTube's match instead`() = runBlocking {
        val match = youtube("tKi9Z-f6qX4", "Strobe (Club Edit)", "deadmau5", 637)
        val fakes = Fakes(signedIn = false, results = listOf(youtube("aaaaaaaaaaa", "Ghosts 'n' Stuff", "deadmau5", 380), match))
        val found = fakes.route(scLink) as SharedLinkResolver.Resolved.Song
        assertTrue(found.substitute)
        assertEquals(match, found.track)
        assertEquals(listOf("deadmau5 Strobe (Club Edit)"), fakes.searched)
    }

    @Test
    fun `signed out, with no match on YouTube, a SoundCloud link plays nothing`() = runBlocking {
        val fakes = Fakes(signedIn = false, results = listOf(youtube("aaaaaaaaaaa", "Ghosts 'n' Stuff", "deadmau5", 380)))
        assertNull(fakes.route(scLink))
        assertEquals(1, fakes.searched.size)
        // Nothing to search by: no title.
        val untitled = Fakes(signedIn = false)
        assertNull(untitled.route(scLink.copy(title = null)))
        assertTrue(untitled.searched.isEmpty())
    }

    @Test
    fun `a catalog link a service here has plays its album, and YouTube is not asked`() = runBlocking {
        val track = Track(id = 67238728, title = "Give Life Back to Music", duration = 274)
        val album = SharedLinkResolver.Resolved.Album(AlbumDetail(Album(6575789, "Random Access Memories"), listOf(track)), track)
        val fakes = Fakes(signedIn = false, results = listOf(youtube("bbbbbbbbbbb", "Give Life Back to Music", "Daft Punk", 274)))
        assertEquals(album, fakes.route(catalogSong, album))
        assertTrue(fakes.searched.isEmpty())
    }

    @Test
    fun `a catalog song no service here has plays from YouTube as a last resort`() = runBlocking {
        val match = youtube("bbbbbbbbbbb", "Give Life Back to Music", "Daft Punk", 275)
        val fakes = Fakes(signedIn = false, results = listOf(match))
        val found = fakes.route(catalogSong) as SharedLinkResolver.Resolved.Song
        assertTrue(found.substitute)
        assertEquals(match, found.track)
        assertEquals(1, fakes.askedCatalogs)
        assertEquals(listOf("Daft Punk Give Life Back to Music"), fakes.searched)
    }

    @Test
    fun `an album no service here has is not looked for on YouTube`() = runBlocking {
        val fakes = Fakes(signedIn = true, results = listOf(youtube("bbbbbbbbbbb", "Random Access Memories", "Daft Punk", 0)))
        assertNull(fakes.route(SharedLink(SharedLink.Source.TIDAL, "1234", null, title = "Random Access Memories", artist = "Daft Punk")))
        assertTrue(fakes.searched.isEmpty())
    }

    private val catalogSong = SharedLink(SharedLink.Source.DEEZER, "6575789", 67238728L, "USQX91300101", "Give Life Back to Music", "Daft Punk", 274)

    // ── YouTube's match ────────────────────────────────────────────────

    @Test
    fun `the match is the same song by the same artist, the nearest in length`() {
        val video = youtube("ccccccccccc", "Daft Punk - One More Time (Official Video)", "Daft Punk", 327)
        val song = youtube("ddddddddddd", "One More Time", "Daft Punk", 320)
        val cover = youtube("eeeeeeeeeee", "One More Time", "Some Tribute Band", 320)
        val other = youtube("fffffffffff", "Aerodynamic", "Daft Punk", 320)
        assertEquals(song, SharedSongResolver.bestMatch("One More Time", "Daft Punk", 320, listOf(cover, other, video, song)))
        // An "Artist - Title" video name is its title; within ten seconds is the same song.
        assertEquals(video, SharedSongResolver.bestMatch("One More Time", "Daft Punk", 320, listOf(cover, video)))
        // Further off is another song (a live take, an extended mix).
        assertNull(SharedSongResolver.bestMatch("One More Time", "Daft Punk", 340, listOf(video)))
        // Without a length, YouTube's first answer that fits.
        assertEquals(video, SharedSongResolver.bestMatch("One More Time", "Daft Punk", null, listOf(cover, video, song)))
    }

    @Test
    fun `an upload named Artist - Title by whoever posted it still finds the song`() {
        // A SoundCloud repost: the artist is in the title, the uploader is a channel.
        val song = youtube("ddddddddddd", "One More Time", "Daft Punk", 320)
        assertEquals(song, SharedSongResolver.bestMatch("Daft Punk - One More Time", "Majestic Casual", 321, listOf(song)))
        // And the artist's own upload, its name in front of the title.
        assertEquals(song, SharedSongResolver.bestMatch("Daft Punk - One More Time", "Daft Punk", 320, listOf(song)))
        // Another artist's song of that name is not it.
        assertNull(SharedSongResolver.bestMatch("One More Time", "Britney Spears", 320, listOf(song)))
    }

    private fun youtube(id: String, title: String, artist: String, seconds: Int): UnifiedTrack =
        ScxytTrack(id = "yt:$id", title = title, artist = artist, duration = seconds.toDouble()).toUnifiedTrack()!!
}
