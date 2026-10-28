package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import tf.monochrome.android.domain.model.Track

class ListContextTest {

    private fun album(id: Long) = ListRef.Album(id)
    private fun track(id: Long) = Track(id = id, title = "Track $id")

    // --- Rows ---

    @Test
    fun `the next list is the one beside it on the screen`() {
        val context = ListContext.of(album(2), listOf(album(1), album(2), album(3)))
        assertEquals(album(3), context.after())
        assertEquals(album(1), context.before())
    }

    @Test
    fun `nothing after the last list or before the first`() {
        val row = listOf(album(1), album(2))
        assertNull(ListContext.of(album(2), row).after())
        assertNull(ListContext.of(album(1), row).before())
    }

    @Test
    fun `a list opened some other way stands alone`() {
        val context = ListContext.of(album(9), listOf(album(1), album(2)))
        assertTrue(context.row.isEmpty())
        assertNull(context.after())
        assertNull(context.before())
    }

    @Test
    fun `a list shown twice does not send the walk round in circles`() {
        // A B A C: walking from the second A must reach C, not go back to B.
        val context = ListContext.of(album(1), listOf(album(1), album(2), album(1), album(3)))
        assertEquals(listOf(album(1), album(2), album(3)), context.row)
        var at = context
        val seen = mutableListOf<ListRef>(at.tail)
        while (true) {
            val next = at.after() ?: break
            seen += next
            at = at.copy(head = next, tail = next)
        }
        assertEquals(listOf(album(1), album(2), album(3)), seen)
    }

    @Test
    fun `a long row is kept to a window around the list played`() {
        val row = (1L..1_000L).map { album(it) }
        val context = ListContext.of(album(500), row)
        assertEquals(2 * ListContext.MAX_EITHER_SIDE + 1, context.row.size)
        assertEquals(album(501), context.after())
        assertEquals(album(499), context.before())
    }

    @Test
    fun `a folder walks the tree, not a row`() {
        val folder = ListRef.Folder("/Music/A")
        val context = ListContext.of(folder, listOf(folder, ListRef.Folder("/Music/B")))
        assertTrue(context.row.isEmpty())
    }

    /**
     * /M/Artist1 (no songs of its own) → Album1, Album2
     * /M/Artist2 → Album3, and two songs of its own
     * /M/Empty → Nothing (no songs anywhere)
     * /M/Artist3 → Album4
     */
    private val tree = object : FolderView {
        val kids = mapOf(
            "/M/Artist1" to listOf("/M/Artist1/Album1", "/M/Artist1/Album2"),
            "/M/Artist2" to listOf("/M/Artist2/Album3"),
            "/M/Empty" to listOf("/M/Empty/Nothing"),
            "/M/Artist3" to listOf("/M/Artist3/Album4"),
        )
        val own = setOf(
            "/M/Artist1/Album1", "/M/Artist1/Album2", "/M/Artist2",
            "/M/Artist2/Album3", "/M/Artist3/Album4",
        )
        override suspend fun roots() = listOf("/M/Artist1", "/M/Artist2", "/M/Empty", "/M/Artist3")
        override suspend fun children(path: String) = kids[path].orEmpty()
        override suspend fun hasOwnSongs(path: String) = path in own
    }

    private fun walkForward(from: String): List<String> =
        generateSequence(from) { runBlocking { FolderWalk.after(tree, it) } }.toList()

    @Test
    fun `folders walk the whole tree, top to bottom`() {
        assertEquals(
            listOf(
                "/M/Artist1/Album1", "/M/Artist1/Album2",
                // A folder's own songs come before its subfolders.
                "/M/Artist2", "/M/Artist2/Album3",
                // A branch with no songs is passed through.
                "/M/Artist3/Album4",
            ),
            walkForward("/M/Artist1/Album1"),
        )
    }

    @Test
    fun `walking back is the same walk reversed`() {
        val forward = walkForward("/M/Artist1/Album1")
        val backward = generateSequence(forward.last()) { runBlocking { FolderWalk.before(tree, it) } }.toList()
        assertEquals(forward.reversed(), backward)
    }

    @Test
    fun `a whole branch played goes on after the branch`() = runBlocking {
        assertEquals("/M/Artist2", FolderWalk.after(tree, "/M/Artist1", skipBelow = true))
        assertEquals("/M/Artist3/Album4", FolderWalk.after(tree, "/M/Artist2", skipBelow = true))
        assertNull(FolderWalk.after(tree, "/M/Artist3", skipBelow = true))
    }

    @Test
    fun `a folder outside the tree has no neighbours`() = runBlocking {
        assertNull(FolderWalk.after(tree, "/elsewhere/x"))
        assertNull(FolderWalk.before(tree, "/elsewhere/x"))
    }

    // --- The saved queue ---

    @Test
    fun `the lists survive a restart`() {
        val context = ListContext.of(ListRef.Playlist("p2"), listOf(ListRef.Playlist("p1"), ListRef.Playlist("p2")))
        val raw = PlaybackSnapshotCodec.encode(
            PersistedQueue(
                version = PLAYBACK_SNAPSHOT_VERSION,
                entries = listOf(PersistedQueueEntry(track(1))),
                listContext = context,
            )
        )
        assertEquals(context, PlaybackSnapshotCodec.decode(raw)?.listContext)
    }

    @Test
    fun `a queue saved before lists were remembered still reads`() {
        val raw = """{"version":$PLAYBACK_SNAPSHOT_VERSION,"entries":[{"track":{"id":1,"title":"Track 1"}}]}"""
        val decoded = PlaybackSnapshotCodec.decode(raw)
        assertEquals(1, decoded?.entries?.size)
        assertNull(decoded?.listContext)
    }

    @Test
    fun `every kind of list round-trips`() {
        val kinds = listOf(
            ListRef.Playlist("p"),
            ListRef.Album(7),
            ListRef.LocalAlbum(8),
            ListRef.Folder("/Music/A"),
            ListRef.SubsonicPlaylist("s"),
            ListRef.SubsonicAlbum("a"),
        )
        val context = ListContext(row = kinds, head = kinds[1], tail = kinds[2], headCut = true)
        val raw = PlaybackSnapshotCodec.encode(
            PersistedQueue(
                version = PLAYBACK_SNAPSHOT_VERSION,
                entries = listOf(PersistedQueueEntry(track(1))),
                listContext = context,
            )
        )
        assertEquals(context, PlaybackSnapshotCodec.decode(raw)?.listContext)
    }

    // --- The queue ---

    private fun context(id: Long) = ListContext(head = album(id))

    @Test
    fun `a new queue forgets the lists, and can bring its own`() {
        val qm = QueueManager()
        qm.setQueue(listOf(track(1)), 0, context(1))
        assertEquals(context(1), qm.listContext.value)
        qm.setQueue(listOf(track(2)), 0)
        assertNull(qm.listContext.value)
    }

    @Test
    fun `clearing what is up next or the whole queue forgets the lists`() {
        val qm = QueueManager()
        qm.setQueue(listOf(track(1), track(2)), 0, context(1))
        qm.clearUpcoming()
        assertNull(qm.listContext.value)
        qm.setQueue(listOf(track(1)), 0, context(1))
        qm.clearQueue()
        assertNull(qm.listContext.value)
    }

    @Test
    fun `editing the queue keeps the lists`() {
        val qm = QueueManager()
        qm.setQueue(listOf(track(1), track(2), track(3)), 0, context(1))
        qm.addToQueue(listOf(track(4)))
        qm.addNextInQueue(track(5))
        qm.move(1, 2)
        qm.removeFromQueue(3)
        assertEquals(context(1), qm.listContext.value)
    }

    @Test
    fun `the next list goes behind the queue and plays after it`() {
        val qm = QueueManager()
        qm.setQueue(listOf(track(1), track(2)), 1, context(1))
        val next = listOf(track(3), track(4))
        qm.appendList(next, next, ListContext(head = album(1), tail = album(2)))
        assertEquals(listOf(1L, 2L, 3L, 4L), qm.queue.value.map { it.id })
        assertEquals(3L, qm.next()?.id)
        assertEquals(album(2), qm.listContext.value?.tail)
    }

    @Test
    fun `the previous list goes in front and plays from its last song`() {
        val qm = QueueManager()
        qm.setQueue(listOf(track(3), track(4)), 0, context(2))
        val before = listOf(track(1), track(2))
        qm.prependList(before, before, ListContext(head = album(1), tail = album(2)))
        assertEquals(listOf(1L, 2L, 3L, 4L), qm.queue.value.map { it.id })
        assertEquals(1, qm.currentQueueIndex)
        assertEquals(2L, qm.currentTrack.value?.id)
        // And Next goes back into the list it came from.
        assertEquals(3L, qm.next()?.id)
    }

    @Test
    fun `a finished list stays in the queue, and the count runs from its first song`() {
        val qm = QueueManager()
        qm.setQueue(listOf(track(1), track(2)), 1, context(1))
        val next = listOf(track(3), track(4))
        qm.appendList(next, next, ListContext(head = album(1), tail = album(2)))
        assertEquals(3L, qm.next()?.id)
        // The queue sheet still holds the list before, and its "3 / 4".
        assertEquals(listOf(1L, 2L, 3L, 4L), qm.queue.value.map { it.id })
        assertEquals(2, qm.currentQueueIndex)
        // Previous on the next list's first song is the last song of the one before.
        assertEquals(2L, qm.previous()?.id)
        assertEquals(album(1), qm.listContext.value?.head)
    }

    @Test
    fun `under shuffle the next list is added in order underneath`() {
        val qm = QueueManager()
        qm.setQueue(listOf(track(1), track(2)), 0, context(1))
        qm.toggleShuffle()
        val inOrder = listOf(track(3), track(4), track(5))
        qm.appendList(inOrder.reversed(), inOrder, context(1))
        // Turning shuffle off plays every list in its own order.
        qm.toggleShuffle()
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), qm.queue.value.map { it.id })
    }

    // --- The oldest dropped from a long queue ---

    private fun tracks(from: Long, count: Int) = (from until from + count).map { track(it) }

    /** Plays on, song by song, to [index]: the cap counts songs played, not jumped to. */
    private fun QueueManager.playTo(index: Int) {
        while (currentQueueIndex < index) checkNotNull(next())
    }

    @Test
    fun `a queue that runs on past the cap drops its oldest lists whole`() {
        val qm = QueueManager()
        qm.setQueue(tracks(0, 100), 0, context(0))
        // Album after album of 100 songs, each added as the last song of the
        // queue starts: 2,099 songs behind the one playing after the 21st.
        for (n in 1L..21L) {
            qm.playTo(qm.currentQueue.lastIndex)
            val next = tracks(n * 100, 100)
            qm.appendList(next, next, qm.listContext.value!!.copy(tail = album(n)))
        }
        // Down to between 1,500 and 2,000 behind, at the start of a list.
        assertEquals(1_599, qm.currentQueueIndex)
        assertEquals(500L, qm.currentQueue.first().id)
        assertEquals(1_700, qm.currentQueue.size)
        assertEquals(2_099L, qm.currentTrack.value?.id)
        // The queue starts where its first list does, so Previous on its first
        // song can still go to the list before.
        assertEquals(album(5), qm.listContext.value?.head)
        assertFalse(qm.listContext.value!!.headCut)
        assertEquals(album(21), qm.listContext.value?.tail)
    }

    @Test
    fun `cut inside a list, the queue no longer starts where that list does`() {
        val qm = QueueManager()
        qm.setQueue(tracks(0, 2_100), 0, context(1))
        qm.playTo(2_099)
        // Song radio's picks behind the last song.
        qm.addToQueue(tracks(10_000, 10))
        assertEquals(KEEP_BEHIND, qm.currentQueueIndex)
        assertEquals(599L, qm.currentQueue.first().id)
        assertEquals(2_099L, qm.currentTrack.value?.id)
        assertEquals(album(1), qm.listContext.value?.head)
        assertTrue(qm.listContext.value!!.headCut)
    }

    @Test
    fun `nothing is dropped at the cap or under Repeat All`() {
        val atCap = QueueManager()
        atCap.setQueue(tracks(0, 2_001), 0)
        atCap.playTo(QueueManager.MAX_BEHIND)
        atCap.addToQueue(tracks(10_000, 10))
        assertEquals(2_011, atCap.currentQueue.size)

        // Repeat All plays the whole queue round again.
        val looping = QueueManager()
        looping.setQueue(tracks(0, 2_100), 0)
        looping.playTo(2_099)
        looping.setRepeatMode(tf.monochrome.android.domain.model.RepeatMode.ALL)
        looping.addToQueue(tracks(10_000, 10))
        assertEquals(2_110, looping.currentQueue.size)
        assertEquals(2_099, looping.currentQueueIndex)
    }

    @Test
    fun `a song tapped deep in a long list, or jumped to, has nothing played behind it`() {
        // The 5,000th song of a library, then Add to queue: none of the 4,999
        // before it has played, so none is dropped.
        val tapped = QueueManager()
        tapped.setQueue(tracks(0, 20_000), 4_999)
        tapped.addToQueue(tracks(30_000, 10))
        assertEquals(20_010, tapped.currentQueue.size)
        assertEquals(4_999, tapped.currentQueueIndex)

        // A jump in the queue sheet skips songs without playing them.
        val jumped = QueueManager()
        jumped.setQueue(tracks(0, 5_000), 0)
        jumped.playTo(10)
        jumped.skipToIndex(4_000)
        jumped.addToQueue(tracks(30_000, 10))
        assertEquals(5_010, jumped.currentQueue.size)

        // Played on from the tap, the cap counts from there.
        tapped.playTo(4_999 + QueueManager.MAX_BEHIND + 1)
        tapped.addToQueue(tracks(40_000, 10))
        assertEquals(KEEP_BEHIND, tapped.currentQueueIndex)
        assertEquals(7_000L - KEEP_BEHIND, tapped.currentQueue.first().id)
    }

    @Test
    fun `under shuffle the pre-shuffle order drops the same songs`() {
        val qm = QueueManager()
        val all = tracks(0, 2_200)
        qm.setQueue(all, 0)
        qm.toggleShuffle()
        qm.playTo(2_100)
        val before = qm.currentQueue
        val playing = qm.currentTrack.value
        qm.addToQueue(tracks(10_000, 5))

        val dropped = before.subList(0, 600)
        assertEquals(KEEP_BEHIND, qm.currentQueueIndex)
        assertTrue(playing === qm.currentTrack.value)
        assertEquals(2_205 - 600, qm.originalQueueSnapshot.size)
        assertTrue(dropped.none { d -> qm.originalQueueSnapshot.any { it === d } })
        // Shuffle off: what is left, in its own order, the current song kept.
        qm.toggleShuffle()
        val droppedIds = dropped.map { it.id }.toSet()
        assertEquals(
            all.map { it.id }.filterNot { it in droppedIds } + (10_000L until 10_005L),
            qm.currentQueue.map { it.id },
        )
        assertEquals(playing?.id, qm.currentTrack.value?.id)
    }

    @Test
    fun `whoever keeps tracks for the queue hears of what it dropped`() {
        val qm = QueueManager()
        var heard: List<Track>? = null
        qm.onTracksReleased = { queue, _ -> heard = queue }
        qm.setQueue(tracks(0, 2_100), 0)
        qm.playTo(2_099)
        heard = null
        qm.addToQueue(tracks(10_000, 1))
        assertEquals(qm.currentQueue, heard)
        assertEquals(1_502, heard?.size)
    }

    private companion object {
        const val KEEP_BEHIND = QueueManager.KEEP_BEHIND_AFTER_TRIM
    }
}
