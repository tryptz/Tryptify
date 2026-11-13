package tf.monochrome.android.data.import_

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.data.import_.M3uLocalMatcher.LocalFile
import java.text.Normalizer

/**
 * Finding an M3U's songs among the listener's own files (issue #155): what is
 * found, and above all what is refused. A playlist with the wrong song in it
 * is worse than one that says a song is missing.
 */
class M3uLocalMatcherTest {

    private val root = "/storage/emulated/0/Music"

    private val bohemian = LocalFile(1, "$root/Queen/A Night at the Opera/11 Bohemian Rhapsody.flac", "Bohemian Rhapsody", "Queen", 354)
    private val around = LocalFile(2, "$root/Daft Punk/Homework/07 Around the World.flac", "Around the World", "Daft Punk", 429)
    private val september = LocalFile(3, "$root/Earth, Wind & Fire/September.mp3", "September", "Earth, Wind & Fire", 215)
    private val dejaVu = LocalFile(4, "$root/Beyoncé/Déjà Vu.mp3", "Déjà Vu", "Beyoncé", 240)
    private val library = listOf(bohemian, around, september, dejaVu)

    private fun match(location: String, display: String? = null, seconds: Int? = null, files: List<LocalFile> = library, dir: String? = null) =
        M3uLocalMatcher(files, dir).match(M3uEntry(location, display, seconds))

    // ── 1. The path ─────────────────────────────────────────────────────────

    @Test
    fun `a path that is right is found`() {
        assertEquals(bohemian, match(bohemian.path))
    }

    @Test
    fun `the phone's storage is found by any of its names`() {
        val under = "Music/Queen/A Night at the Opera/11 Bohemian Rhapsody.flac"
        for (name in listOf("/sdcard", "/mnt/sdcard", "/storage/sdcard0", "/storage/self/primary", "/storage/emulated/legacy")) {
            assertEquals(name, bohemian, match("$name/$under"))
        }
    }

    @Test
    fun `a file address is a path`() {
        assertEquals(dejaVu, match("file:///storage/emulated/0/Music/Beyonc%C3%A9/D%C3%A9j%C3%A0%20Vu.mp3"))
        assertEquals(september, match("file://localhost/storage/emulated/0/Music/Earth%2C%20Wind%20%26%20Fire/September.mp3"))
        assertEquals(bohemian, match("file:/storage/emulated/0/Music/Queen/A Night at the Opera/11 Bohemian Rhapsody.flac"))
    }

    @Test
    fun `a relative path is under the playlist's own folder`() {
        assertEquals(around, match("Daft Punk/Homework/07 Around the World.flac", dir = root))
        assertEquals(around, match("./Daft Punk/Homework/07 Around the World.flac", dir = root))
        assertEquals(around, match("../Music/Daft Punk/Homework/07 Around the World.flac", dir = "/storage/emulated/0/Playlists"))
        assertEquals(around, match("..\\Daft Punk\\Homework\\07 Around the World.flac", dir = "$root/Lists"))
    }

    @Test
    fun `case may differ`() {
        assertEquals(bohemian, match("/storage/emulated/0/music/queen/a night at the opera/11 bohemian rhapsody.FLAC"))
    }

    @Test
    fun `two files apart only by case are not guessed between`() {
        val upper = LocalFile(10, "$root/A/Song.mp3", "Song", "A", 100)
        val lower = LocalFile(11, "$root/a/song.mp3", "Other", "B", 300)
        val files = listOf(upper, lower)
        assertEquals(upper, match("$root/A/Song.mp3", files = files))
        assertEquals(lower, match("$root/a/song.mp3", files = files))
        // Neither spelling: nothing says which.
        assertNull(match("$root/A/SONG.MP3", files = files))
    }

    @Test
    fun `accents written the Mac's way match Android's`() {
        // "é" as "e" and a combining accent, the way macOS writes file names.
        val decomposed = Normalizer.normalize("$root/Beyoncé/Déjà Vu.mp3", Normalizer.Form.NFD)
        assertEquals(dejaVu, match(decomposed))
    }

    // ── 2. The end of the path ──────────────────────────────────────────────

    @Test
    fun `a computer's path is found by its end`() {
        assertEquals(bohemian, match("D:\\Music\\Queen\\A Night at the Opera\\11 Bohemian Rhapsody.flac"))
        assertEquals(bohemian, match("/home/me/Music/Queen/A Night at the Opera/11 Bohemian Rhapsody.flac"))
        assertEquals(bohemian, match("file:///C:/Users/me/Music/Queen/A%20Night%20at%20the%20Opera/11%20Bohemian%20Rhapsody.flac"))
    }

    @Test
    fun `a relative path with no folder known is found by its end`() {
        assertEquals(around, match("Daft Punk/Homework/07 Around the World.flac"))
        assertEquals(around, match("../../Daft Punk/Homework/07 Around the World.flac"))
        assertEquals(around, match("07 Around the World.flac"))
    }

    @Test
    fun `the copy in another format is found when the named one is not there`() {
        assertEquals(bohemian, match("D:\\Music\\Queen\\A Night at the Opera\\11 Bohemian Rhapsody.mp3"))
        assertEquals(september, match("Earth, Wind & Fire/September.flac"))
    }

    @Test
    fun `the named format wins over another format's copy`() {
        val flac = LocalFile(20, "$root/A/Album/01 Song.flac", "Song", "A", 200)
        val mp3 = LocalFile(21, "$root/A/Album/01 Song.mp3", "Song", "A", 200)
        assertEquals(mp3, match("A/Album/01 Song.mp3", files = listOf(flac, mp3)))
        assertEquals(flac, match("A/Album/01 Song.flac", files = listOf(flac, mp3)))
    }

    @Test
    fun `of two files with one name, the folders above decide`() {
        val first = LocalFile(30, "$root/Artist/First Album/01 Intro.mp3", "Intro", "Artist", 60)
        val second = LocalFile(31, "$root/Artist/Second Album/01 Intro.mp3", "Intro", "Artist", 95)
        val files = listOf(first, second)
        assertEquals(second, match("E:\\Artist\\Second Album\\01 Intro.mp3", files = files))
        assertEquals(first, match("First Album/01 Intro.mp3", files = files))
    }

    @Test
    fun `two files that fit equally are not guessed between`() {
        val first = LocalFile(30, "$root/Artist/First Album/01 Intro.mp3", "Intro", "Artist", 60)
        val second = LocalFile(31, "$root/Artist/Second Album/01 Intro.mp3", "Intro", "Artist", 95)
        assertNull(match("01 Intro.mp3", files = listOf(first, second)))
        // Two albums of one name under different artists.
        val hitsA = LocalFile(32, "$root/A/Greatest Hits/01.mp3", "One", "A", 200)
        val hitsB = LocalFile(33, "$root/B/Greatest Hits/01.mp3", "Uno", "B", 250)
        assertNull(match("Greatest Hits/01.mp3", files = listOf(hitsA, hitsB)))
    }

    @Test
    fun `the line's text or length decides between files that fit equally`() {
        val first = LocalFile(30, "$root/Artist/First Album/01 Intro.mp3", "Intro", "Artist", 60)
        val second = LocalFile(31, "$root/Artist/Second Album/01 Intro.mp3", "Intro", "Artist", 95)
        val files = listOf(first, second)
        // Same title and artist: only the length tells them apart.
        assertEquals(second, match("01 Intro.mp3", "Artist - Intro", 95, files = files))
        assertEquals(first, match("01 Intro.mp3", "Artist - Intro", 61, files = files))
        // A length that fits neither.
        assertNull(match("01 Intro.mp3", "Artist - Intro", 300, files = files))

        val hitsA = LocalFile(32, "$root/A/Greatest Hits/01.mp3", "One", "A", 200)
        val hitsB = LocalFile(33, "$root/B/Greatest Hits/01.mp3", "Uno", "B", 250)
        assertEquals(hitsB, match("Greatest Hits/01.mp3", "B - Uno", files = listOf(hitsA, hitsB)))
    }

    @Test
    fun `one recording kept twice is the first copy`() {
        val phone = LocalFile(40, "/storage/emulated/0/Music/A/Album/01 Song.flac", "Song", "A", 200)
        val card = LocalFile(41, "/storage/1A2B-3C4D/Music/A/Album/01 Song.flac", "Song", "A", 200)
        assertEquals(card, match("Music/A/Album/01 Song.flac", files = listOf(phone, card)))
        assertEquals(card, match("Music/A/Album/01 Song.flac", files = listOf(card, phone)))
        // Named outright, each is itself.
        assertEquals(phone, match(phone.path, files = listOf(phone, card)))
    }

    @Test
    fun `a file found by its name alone is refused when the length says otherwise`() {
        val intro = LocalFile(50, "$root/Jazz/Album/01.mp3", "Prelude", "Someone", 400)
        // Under none of the folders the list names, and three minutes shorter.
        assertNull(match("Rock/Other/01.mp3", "Band - Opener", 215, files = listOf(intro)))
        // The same file with nothing to contradict it, or a length that fits.
        assertEquals(intro, match("Rock/Other/01.mp3", files = listOf(intro)))
        assertEquals(intro, match("Rock/Other/01.mp3", "Someone - Prelude", 398, files = listOf(intro)))
        // With a folder that agrees, the path is trusted over the length.
        assertEquals(intro, match("Album/01.mp3", "x", 215, files = listOf(intro)))
    }

    // ── 3. The EXTINF text ──────────────────────────────────────────────────

    @Test
    fun `a renamed file is found by its artist and title`() {
        assertEquals(bohemian, match("D:\\Rips\\track11.wav", "Queen - Bohemian Rhapsody", 354))
        assertEquals(bohemian, match("D:\\Rips\\track11.wav", "Queen - Bohemian Rhapsody"))
        assertEquals(september, match("x/y.ogg", "Earth, Wind & Fire - September", 215))
        assertEquals(dejaVu, match("x/y.ogg", "BEYONCE - Deja Vu (Radio Edit)"))
    }

    @Test
    fun `the title alone is enough when one file has it`() {
        assertEquals(around, match("x/y.ogg", "Around the World", 429))
        assertEquals(around, match("x/y.ogg", "Around the World"))
    }

    @Test
    fun `another artist's song of the same title is refused`() {
        assertNull(match("x/y.ogg", "ATC - Around the World", 215))
    }

    @Test
    fun `the same song at another length is refused`() {
        // A live take, twice as long.
        assertNull(match("x/y.ogg", "Queen - Bohemian Rhapsody", 720))
    }

    @Test
    fun `two songs the text fits are not guessed between`() {
        val studio = LocalFile(60, "$root/Q/Studio/a.flac", "Bohemian Rhapsody", "Queen", 354)
        val live = LocalFile(61, "$root/Q/Live/b.flac", "Bohemian Rhapsody", "Queen", 395)
        val files = listOf(studio, live)
        assertNull(match("x/y.ogg", "Queen - Bohemian Rhapsody", files = files))
        assertEquals(live, match("x/y.ogg", "Queen - Bohemian Rhapsody", 396, files = files))
    }

    @Test
    fun `an untagged file named artist - title is found by the whole text`() {
        // No tags: the title shown is the file's name.
        val untagged = LocalFile(70, "$root/Loose/whatever.mp3", "Some Band - Some Song", "", 180)
        assertEquals(untagged, match("x/y.ogg", "Some Band - Some Song", 180, files = listOf(untagged)))
    }

    // ── Not files, and not there ────────────────────────────────────────────

    @Test
    fun `a stream's address is not a file, whatever its text says`() {
        assertNull(match("http://radio.example/stream.mp3", "Queen - Bohemian Rhapsody", 354))
        assertNull(match("https://example.com/Music/Queen/A Night at the Opera/11 Bohemian Rhapsody.flac"))
        assertNull(match("content://media/external/audio/media/42", "Queen - Bohemian Rhapsody"))
        assertNull(match("rtsp://example.com/a", "Around the World"))
    }

    @Test
    fun `a song that is not on the device is not found`() {
        assertNull(match("/storage/emulated/0/Music/Nobody/Nothing.flac"))
        assertNull(match("Nobody/Nothing.flac", "Nobody - Nothing", 100))
        assertNull(match("Nobody/Nothing.flac"))
    }

    @Test
    fun `an empty library finds nothing`() {
        assertNull(match(bohemian.path, "Queen - Bohemian Rhapsody", 354, files = emptyList()))
    }

    // ── The pieces ──────────────────────────────────────────────────────────

    @Test
    fun `locations are cleaned to forward-slash paths`() {
        assertEquals("/Music/a.mp3", M3uLocalMatcher.cleanLocation("D:\\Music\\a.mp3"))
        assertEquals("/Music/a.mp3", M3uLocalMatcher.cleanLocation("d:/Music/a.mp3"))
        assertEquals("../a b.mp3", M3uLocalMatcher.cleanLocation("  ..\\a b.mp3  "))
        assertEquals("/a b.mp3", M3uLocalMatcher.cleanLocation("\"/a b.mp3\""))
        assertEquals("/a b.mp3", M3uLocalMatcher.cleanLocation("file:///a%20b.mp3"))
        assertEquals("/Music/a.mp3", M3uLocalMatcher.cleanLocation("file:///C:/Music/a.mp3"))
        assertEquals("/share/a.mp3", M3uLocalMatcher.cleanLocation("file://server/share/a.mp3"))
        // A plain path's percent sign is a percent sign.
        assertEquals("/100%20Hits/a.mp3", M3uLocalMatcher.cleanLocation("/100%20Hits/a.mp3"))
        assertNull(M3uLocalMatcher.cleanLocation("https://example.com/a.mp3"))
        assertNull(M3uLocalMatcher.cleanLocation("   "))
    }

    @Test
    fun `percent signs that encode nothing stay`() {
        assertEquals("100% a", M3uLocalMatcher.percentDecoded("100%25%20a"))
        assertEquals("100% a", M3uLocalMatcher.percentDecoded("100% a"))
        assertEquals("50%", M3uLocalMatcher.percentDecoded("50%"))
        assertEquals("%zz", M3uLocalMatcher.percentDecoded("%zz"))
        assertEquals("é", M3uLocalMatcher.percentDecoded("%C3%A9"))
        assertEquals("a+b", M3uLocalMatcher.percentDecoded("a+b"))
    }

    @Test
    fun `dot-dot takes back the folder before it`() {
        assertEquals(listOf("a", "c"), M3uLocalMatcher.resolve(listOf("a", "b", "..", "c")))
        assertEquals(listOf("..", "..", "a"), M3uLocalMatcher.resolve(listOf("..", "..", "a")))
        assertEquals(listOf("a", "b", "c"), M3uLocalMatcher.segmentsOf("/a//b/./c/"))
    }

    @Test
    fun `the shared end counts from the file name back`() {
        val have = listOf("storage", "music", "queen", "opera", "11.flac")
        assertEquals(3, M3uLocalMatcher.sharedEnd(listOf("d", "queen", "opera", "11.flac"), have))
        assertEquals(1, M3uLocalMatcher.sharedEnd(listOf("11.flac"), have))
        assertEquals(0, M3uLocalMatcher.sharedEnd(listOf("queen", "opera", "12.flac"), have))
        assertEquals(0, M3uLocalMatcher.sharedEnd(listOf("opera", "11.mp3"), have))
        assertEquals(2, M3uLocalMatcher.sharedEnd(listOf("opera", "11.mp3"), have, byStem = true))
        assertEquals(5, M3uLocalMatcher.sharedEnd(have, have))
    }
}
