package tf.monochrome.android.ui.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * The folder screen's order. A review of Tryptify said files in a folder
 * played "alphabetically rather than in original order": an untagged live
 * recording fell back to its titles. These pin the running order, and the
 * sort keys added alongside it.
 */
class FolderSortTest {

    private fun track(
        path: String,
        title: String = path.substringAfterLast('/').substringBeforeLast('.'),
        album: String? = null,
        disc: Int? = null,
        number: Int? = null,
        year: Int? = null,
        modified: Long? = null,
        bitDepth: Int? = 16,
        sampleRate: Int = 44_100,
        bitRate: Int = 1000,
        codec: AudioCodec = AudioCodec.FLAC,
        size: Long? = null,
    ) = UnifiedTrack(
        id = "local_$path",
        title = title,
        durationSeconds = 200,
        trackNumber = number,
        discNumber = disc,
        artistName = "Swans",
        albumTitle = album,
        releaseYear = year,
        codec = codec,
        sampleRate = sampleRate,
        bitDepth = bitDepth,
        bitRate = bitRate,
        source = PlaybackSource.LocalFile(path, codec, sampleRate, bitDepth),
        sourceType = SourceType.LOCAL,
        dateModified = modified,
        fileSizeBytes = size,
    )

    private fun List<UnifiedTrack>.names() = map { it.title }

    @Test
    fun `numbers in text compare by value`() {
        val sorted = listOf("Track 10", "track 2", "Track 1", "CD10", "CD2").sortedWith(NaturalOrder)
        assertEquals(listOf("CD2", "CD10", "Track 1", "track 2", "Track 10"), sorted)
        // Longer than a Long, still by value.
        assertTrue(NaturalOrder.compare("99999999999999999999", "100000000000000000000") < 0)
        // Equal as read is still a fixed order, never a tie.
        assertNotEquals(0, NaturalOrder.compare("a01", "a1"))
    }

    @Test
    fun `album sort turns the albums round but plays each from its first track`() {
        val folder = listOf(
            track("/d/b2.flac", title = "B2", album = "Beta", number = 2),
            track("/d/a1.flac", title = "A1", album = "Alpha", number = 1),
            track("/d/b1.flac", title = "B1", album = "Beta", number = 1),
            track("/d/a2.flac", title = "A2", album = "Alpha", number = 2),
            track("/d/loose.flac", title = "Loose"),
        )
        assertEquals(
            listOf("A1", "A2", "B1", "B2", "Loose"),
            sortFolderTracks(folder, FolderTrackOrder.ALBUM, ascending = true).names(),
        )
        // Down: Beta before Alpha, each still 1 then 2; no album stays last.
        assertEquals(
            listOf("B1", "B2", "A1", "A2", "Loose"),
            sortFolderTracks(folder, FolderTrackOrder.ALBUM, ascending = false).names(),
        )
        // Which is not what reversing the original order does.
        assertEquals(
            listOf("Loose", "B2", "B1", "A2", "A1"),
            sortFolderTracks(folder, FolderTrackOrder.ORIGINAL, ascending = false).names(),
        )
    }

    @Test
    fun `an untagged live recording plays in file order, not by title`() {
        // No track numbers, and titles whose alphabetical order is not the set's.
        val set = listOf(
            track("/Music/Swans/2024 Live/10 - The Beggar.flac", title = "The Beggar"),
            track("/Music/Swans/2024 Live/02 - Paradise Is Mine.flac", title = "Paradise Is Mine"),
            track("/Music/Swans/2024 Live/01 - Intro.flac", title = "Intro"),
            track("/Music/Swans/2024 Live/03 - A Little God In My Hands.flac", title = "A Little God In My Hands"),
        )
        val played = sortFolderTracks(set, FolderTrackOrder.ORIGINAL, ascending = true)
        assertEquals(listOf("Intro", "Paradise Is Mine", "A Little God In My Hands", "The Beggar"), played.names())
    }

    @Test
    fun `original order is disc then track, with file names behind`() {
        val album = listOf(
            track("/a/d2t1.flac", title = "2-1", album = "X", disc = 2, number = 1),
            track("/a/d1t2.flac", title = "1-2", album = "X", disc = 1, number = 2),
            track("/a/d1t1.flac", title = "1-1", album = "X", disc = 1, number = 1),
            // A file with no number goes after the numbered ones.
            track("/a/bonus.flac", title = "bonus", album = "X", disc = 1),
        )
        assertEquals(listOf("1-1", "1-2", "bonus", "2-1"), sortFolderTracks(album, FolderTrackOrder.ORIGINAL, true).names())
    }

    @Test
    fun `a folder of several albums keeps each album together in its order`() {
        val download = listOf(
            track("/d/b1.flac", title = "B1", album = "Bravo", number = 1),
            track("/d/a2.flac", title = "A2", album = "Alpha", number = 2),
            track("/d/b2.flac", title = "B2", album = "Bravo", number = 2),
            track("/d/a1.flac", title = "A1", album = "Alpha", number = 1),
        )
        assertEquals(listOf("A1", "A2", "B1", "B2"), sortFolderTracks(download, FolderTrackOrder.ORIGINAL, true).names())
    }

    @Test
    fun `newest first leaves undated files last, and ties in file order`() {
        val files = listOf(
            track("/f/b.flac", modified = 100),
            track("/f/undated.flac"),
            track("/f/c.flac", modified = 300),
            track("/f/a.flac", modified = 100),
        )
        assertEquals(false, FolderTrackOrder.DATE_MODIFIED.firstAscending)
        assertEquals(listOf("c", "a", "b", "undated"), sortFolderTracks(files, FolderTrackOrder.DATE_MODIFIED, false).names())
        assertEquals(listOf("a", "b", "c", "undated"), sortFolderTracks(files, FolderTrackOrder.DATE_MODIFIED, true).names())
    }

    @Test
    fun `quality puts hi-res above CD above lossy`() {
        val files = listOf(
            track("/q/mp3.mp3", bitDepth = null, codec = AudioCodec.MP3, bitRate = 320),
            track("/q/hires.flac", bitDepth = 24, sampleRate = 96_000),
            track("/q/cd.flac"),
        )
        assertEquals(listOf("hires", "cd", "mp3"), sortFolderTracks(files, FolderTrackOrder.QUALITY, false).names())
    }

    @Test
    fun `a year sorted descending keeps each album in its running order`() {
        val files = listOf(
            track("/y/old2.flac", album = "Old", year = 1990, number = 2),
            track("/y/new1.flac", album = "New", year = 2020, number = 1),
            track("/y/old1.flac", album = "Old", year = 1990, number = 1),
            track("/y/new2.flac", album = "New", year = 2020, number = 2),
        )
        assertEquals(listOf("new1", "new2", "old1", "old2"), sortFolderTracks(files, FolderTrackOrder.YEAR, false).names())
    }

    private data class Folder(val name: String, val songs: Int, val newest: Long?)

    private fun List<Folder>.sorted(order: SubfolderOrder, ascending: Boolean) =
        sortFolders(this, order, ascending, { it.name }, { it.songs }, { it.newest }).map { it.name }

    @Test
    fun `folders sort by name as read, by size, and by newest file`() {
        val folders = listOf(
            Folder("CD10", 5, 300),
            Folder("CD2", 12, null),
            Folder("Bootlegs", 12, 100),
        )
        assertEquals(listOf("Bootlegs", "CD2", "CD10"), folders.sorted(SubfolderOrder.NAME, true))
        // Most songs first; a tie in name order.
        assertEquals(listOf("Bootlegs", "CD2", "CD10"), folders.sorted(SubfolderOrder.SONG_COUNT, false))
        // Newest first; a folder with no dated file last.
        assertEquals(listOf("CD10", "Bootlegs", "CD2"), folders.sorted(SubfolderOrder.DATE_MODIFIED, false))
    }

    @Test
    fun `playing a folder takes its own songs, then each folder inside, in the sort`() {
        val tracks = listOf(
            track("/M/Swans/2024 Live/02 - Two.flac", title = "Live 2"),
            track("/M/Swans/1987 Children of God/01 - New Mind.flac", title = "CoG 1", number = 1, album = "Children of God"),
            track("/M/Swans/loose.flac", title = "Loose"),
            track("/M/Swans/2024 Live/01 - One.flac", title = "Live 1"),
            // A folder holding only a folder is walked through, not skipped.
            track("/M/Swans/Box/Disc 1/01.flac", title = "Box 1"),
        )
        val byName = folderPlayOrder("/M/Swans", tracks, FolderSort())
        assertEquals(listOf("Loose", "CoG 1", "Live 1", "Live 2", "Box 1"), byName.names())

        val newestFolderFirst = folderPlayOrder(
            "/M/Swans",
            tracks.map {
                if ("2024 Live" in it.filePath.orEmpty()) it.copy(dateModified = 500) else it.copy(dateModified = 1)
            },
            FolderSort(folders = SubfolderOrder.DATE_MODIFIED, foldersAscending = false),
        )
        assertEquals(listOf("Loose", "Live 1", "Live 2"), newestFolderFirst.names().take(3))
    }

    @Test
    fun `the saved sort reads back, and anything unreadable is the default`() {
        val sort = FolderSort(FolderTrackOrder.QUALITY, false, SubfolderOrder.DATE_MODIFIED, false)
        assertEquals(sort, FolderSort.decode(sort.encode()))
        assertEquals(FolderSort(), FolderSort.decode(null))
        assertEquals(FolderSort(), FolderSort.decode("garbage"))
        assertEquals(FolderTrackOrder.ORIGINAL, FolderSort.decode("RENAMED:1:NAME:1").tracks)
    }

    @Test
    fun `crumbs start at the deepest root the folder is in`() {
        val roots = listOf(
            FolderCrumb("Music", "/storage/emulated/0/Music"),
            FolderCrumb("Swans", "/storage/emulated/0/Music/Swans"),
        )
        val crumbs = folderCrumbs("/storage/emulated/0/Music/Swans/2024 Live", roots, "/storage/emulated/0")
        assertEquals(listOf("Swans", "2024 Live"), crumbs.map { it.name })
        assertEquals("/storage/emulated/0/Music/Swans", crumbs.first().path)
        assertEquals("/storage/emulated/0/Music/Swans/2024 Live", crumbs.last().path)
    }

    @Test
    fun `outside every root, crumbs start below the phone's storage`() {
        val crumbs = folderCrumbs("/storage/emulated/0/Download/Live", emptyList(), "/storage/emulated/0")
        assertEquals(listOf("Download", "Live"), crumbs.map { it.name })
        assertEquals("/storage/emulated/0/Download", crumbs.first().path)
    }
}
