package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Test
import tf.monochrome.android.data.local.db.LocalFolderEntity
import tf.monochrome.android.domain.model.RepeatMode
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.ui.library.FolderSort
import tf.monochrome.android.ui.library.SubfolderOrder

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
        val context = ListContext(row = kinds, head = kinds[1], tail = kinds[2], headCut = true, tailCut = true)
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
    fun `lists saved before the end of a queue was marked read as not cut`() {
        val raw = """{"version":$PLAYBACK_SNAPSHOT_VERSION,"entries":[{"track":{"id":1,"title":"Track 1"}}],""" +
            """"listContext":{"head":{"type":"album","id":7},"headCut":true}}"""
        assertEquals(ListContext(head = album(7), headCut = true), PlaybackSnapshotCodec.decode(raw)?.listContext)
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

    // --- A saved queue cut short at its end ---

    @Test
    fun `a saved window marks the end it cuts off, as well as the front`() {
        val lists = ListContext(head = album(1))
        // A 1,000-song list from its first song: the front is whole, the end cut.
        assertEquals(ListContext(head = album(1), tailCut = true), QueueWindow.around(1_000, 0).lists(lists, 1_000))
        // Near its end: the front cut, the end whole.
        assertEquals(ListContext(head = album(1), headCut = true), QueueWindow.around(1_000, 900).lists(lists, 1_000))
        // A short queue is saved whole, and a cut already marked stays marked.
        assertEquals(lists, QueueWindow.around(200, 10).lists(lists, 200))
        val restored = lists.copy(tailCut = true)
        assertEquals(restored, QueueWindow.around(300, 10).lists(restored, 300))
    }

    @Test
    fun `a saved queue is cut at its end only inside its last list`() {
        // Lists of every size, each added as the last song of the queue
        // starts, as ListContinuation does: wherever the play head is, what a
        // saved window leaves off at the end belongs to the list named as
        // the tail, so that is the one to load again.
        val sizes = listOf(120, 400, 50, 700, 30, 350)
        val owner = HashMap<Long, Long>()
        var nextId = 0L
        fun list(album: Long) = (0 until sizes[album.toInt()]).map { track(nextId++).also { t -> owner[t.id] = album } }
        val qm = QueueManager()
        qm.setQueue(list(0), 0, context(0))
        var cuts = 0
        fun saved() {
            val queue = qm.currentQueue
            val window = QueueWindow.around(queue.size, qm.currentQueueIndex)
            val tail = (qm.listContext.value!!.tail as ListRef.Album).id
            for (i in window.until until queue.size) assertEquals(tail, owner[queue[i].id])
            if (window.until < queue.size) cuts++
        }
        var added = 0L
        while (true) {
            saved()
            if (qm.currentQueueIndex == qm.currentQueue.lastIndex) {
                if (++added == sizes.size.toLong()) break
                val next = list(added)
                qm.appendList(next, next, qm.listContext.value!!.copy(tail = album(added)))
                saved()
            }
            checkNotNull(qm.next())
        }
        assertTrue(cuts > 0)
    }

    @Test
    fun `a list cut short at its end plays the rest of itself before the next`() = runBlocking {
        val p1 = ListRef.Playlist("p1")
        val p2 = ListRef.Playlist("p2")
        val all = tracks(1, 1_000)
        val search = ListSearch(load = { if (it == p1) all else tracks(5_000, 10) }, folders = { null })
        val context = ListContext(row = listOf(p1, p2), head = p1, tailCut = true)
        // The queue saved from its first song holds songs 1 to 300.
        val rest = search.beside(context, forward = true, queue = all.subList(0, 300)) as ListSearch.Lookup.Found
        assertEquals(p1, rest.list)
        assertEquals((301L..1_000L).toList(), rest.tracks.map { it.id })
        assertFalse(rest.whole)
        // Nothing of it left: on to the next list as usual.
        val next = search.beside(context, forward = true, queue = all) as ListSearch.Lookup.Found
        assertEquals(p2, next.list)
        assertTrue(next.whole)
        // It could not be loaded: not the next list instead, but a try later.
        val offline = ListSearch(load = { null }, folders = { null })
        assertEquals(ListSearch.Lookup.Failed, offline.beside(context, forward = true, queue = all.subList(0, 300)))
    }

    @Test
    fun `reopened after the process died, a long list plays on to its end`() = runBlocking {
        val p1 = ListRef.Playlist("p1")
        val p2 = ListRef.Playlist("p2")
        val all = tracks(1, 1_000)
        // Played from its first song and saved, as PlaybackStateRepository saves it.
        val lists = ListContext(row = listOf(p1, p2), head = p1)
        val window = QueueWindow.around(all.size, 0)
        val restored = QueueManager()
        restored.restore(
            queue = all.subList(window.from, window.until),
            originalQueue = emptyList(),
            currentIndex = 0,
            shuffleEnabled = false,
            repeatMode = RepeatMode.OFF,
            listContext = window.lists(lists, all.size),
        )
        // Song 300 starts, and ListContinuation looks for what comes next.
        restored.playTo(restored.currentQueue.lastIndex)
        val context = restored.listContext.value!!
        val found = ListSearch(load = { if (it == p1) all else tracks(5_000, 10) }, folders = { null })
            .beside(context, forward = true, queue = restored.currentQueue) as ListSearch.Lookup.Found
        restored.appendList(found.tracks, found.tracks, context.copy(tail = found.list, tailCut = false), found.whole)
        assertEquals(all.map { it.id }, restored.currentQueue.map { it.id })
        assertEquals(lists, restored.listContext.value)
        assertEquals(301L, restored.next()?.id)
        // The rest is not a list of its own: the list still starts at song 1,
        // so shuffle keeps nothing in place before the song playing.
        restored.playTo(500)
        restored.toggleShuffle()
        assertEquals(0, restored.currentQueueIndex)
    }

    // --- A list that could not be loaded ---

    private val threeAlbums = ListContext.of(album(1), listOf(album(1), album(2), album(3)))

    @Test
    fun `a list that could not be loaded is not the end, and is tried again`() = runBlocking {
        var online = false
        val search = ListSearch(
            load = { if (online) listOf(track((it as ListRef.Album).id * 100)) else null },
            folders = { null },
        )
        // Offline: neither passed over into album 3 nor the end of the row,
        // which would start song radio and lose albums 2 and 3 for good.
        assertEquals(ListSearch.Lookup.Failed, search.beside(threeAlbums, forward = true))
        // Nor, going back, the start of the row.
        val second = ListContext.of(album(2), threeAlbums.row)
        assertEquals(ListSearch.Lookup.Failed, search.beside(second, forward = false))
        online = true
        assertEquals(album(2), (search.beside(threeAlbums, forward = true) as ListSearch.Lookup.Found).list)
    }

    @Test
    fun `an empty list is still passed over`() = runBlocking {
        val search = ListSearch(load = { if (it == album(2)) emptyList() else listOf(track(300)) }, folders = { null })
        assertEquals(album(3), (search.beside(threeAlbums, forward = true) as ListSearch.Lookup.Found).list)
        // And empty lists to the end of the row are the end.
        val none = ListSearch(load = { emptyList() }, folders = { null })
        assertEquals(ListSearch.Lookup.End, none.beside(threeAlbums, forward = true))
    }

    @Test
    fun `a list that never loads is passed over after a few tries`() = runBlocking {
        // A playlist deleted since must not hold up the ones after it for ever.
        val search = ListSearch(load = { if (it == album(2)) null else listOf(track(300)) }, folders = { null })
        repeat(ListSearch.MAX_TRIES - 1) {
            assertEquals(ListSearch.Lookup.Failed, search.beside(threeAlbums, forward = true))
        }
        assertEquals(album(3), (search.beside(threeAlbums, forward = true) as ListSearch.Lookup.Found).list)
        // A try that works in between starts the count again.
        var up = false
        val flaky = ListSearch(load = { if (it == album(2) && !up) null else listOf(track(200)) }, folders = { null })
        repeat(ListSearch.MAX_TRIES - 1) { flaky.beside(threeAlbums, forward = true) }
        up = true
        flaky.beside(threeAlbums, forward = true)
        up = false
        assertEquals(ListSearch.Lookup.Failed, flaky.beside(threeAlbums, forward = true))
    }

    @Test
    fun `a folder tree that cannot be read is a failure, not the end`() = runBlocking {
        val folder = ListContext(head = ListRef.Folder("/M/Artist1/Album1"))
        val unread = ListSearch(load = { listOf(track(1)) }, folders = { null })
        assertEquals(ListSearch.Lookup.Failed, unread.beside(folder, forward = true))
        val broken = object : FolderView by tree {
            override suspend fun children(path: String): List<String> = throw IllegalStateException("database closed")
        }
        val midWalk = ListSearch(load = { listOf(track(1)) }, folders = { broken })
        assertEquals(ListSearch.Lookup.Failed, midWalk.beside(folder, forward = true))
        // Read, the walk goes on as it did.
        val found = ListSearch(load = { listOf(track(1)) }, folders = { tree }).beside(folder, forward = true)
        assertEquals(ListRef.Folder("/M/Artist1/Album2"), (found as ListSearch.Lookup.Found).list)
    }

    // --- Shuffle among lists ---

    /** Album 1 played to its end, album 2 added behind it as it does, and played [into] it. */
    private fun twoAlbums(into: Int): QueueManager {
        val qm = QueueManager()
        qm.setQueue(tracks(100, 10), 0, context(1))
        qm.playTo(9)
        val second = tracks(200, 10)
        qm.appendList(second, second, qm.listContext.value!!.copy(tail = album(2)))
        qm.playTo(10 + into)
        return qm
    }

    @Test
    fun `shuffle leaves the lists already played where they are`() {
        val qm = twoAlbums(into = 3)
        val playing = qm.currentTrack.value
        qm.toggleShuffle()
        // Album 1 stays behind the play head, in its order.
        assertEquals((100L until 110L).toList(), qm.currentQueue.take(10).map { it.id })
        assertEquals(10, qm.currentQueueIndex)
        assertTrue(playing === qm.currentTrack.value)
        // The rest of album 2, the songs before this one included, comes next.
        assertEquals((200L until 210L).toSet() - 203L, qm.currentQueue.drop(11).map { it.id }.toSet())
        assertEquals(20, qm.currentQueue.size)
    }

    @Test
    fun `with no list played before, shuffle can still play the songs before the one tapped`() {
        for (lists in listOf(null, context(1))) {
            val qm = QueueManager()
            qm.setQueue(tracks(1, 10), 4, lists)
            qm.toggleShuffle()
            assertEquals(0, qm.currentQueueIndex)
            assertEquals(5L, qm.currentTrack.value?.id)
            assertEquals((1L..10L).toSet() - 5L, qm.currentQueue.drop(1).map { it.id }.toSet())
        }
    }

    @Test
    fun `shuffle keeps every copy of a song that is in the queue twice`() {
        val qm = QueueManager()
        qm.setQueue(listOf(track(1), track(2), track(1), track(3)), 0)
        qm.toggleShuffle()
        assertEquals(listOf(1L, 1L, 2L, 3L), qm.currentQueue.map { it.id }.sorted())
    }

    @Test
    fun `shuffle off goes back to the song playing, not an earlier copy of it`() {
        // The same song in both albums: id 2 is the second of each.
        val qm = QueueManager()
        qm.setQueue(listOf(track(1), track(2), track(3)), 0, context(1))
        qm.playTo(2)
        val second = listOf(track(4), track(2), track(5))
        qm.appendList(second, second, qm.listContext.value!!.copy(tail = album(2)))
        qm.playTo(4)
        val playing = qm.currentTrack.value
        qm.toggleShuffle()
        qm.toggleShuffle()
        assertEquals(listOf(1L, 2L, 3L, 4L, 2L, 5L), qm.currentQueue.map { it.id })
        assertEquals(4, qm.currentQueueIndex)
        assertTrue(playing === qm.currentTrack.value)
    }

    @Test
    fun `shuffle turned off and on again still knows which lists have played`() {
        val qm = twoAlbums(into = 3)
        qm.toggleShuffle()
        // Album 3 added under shuffle, and played into.
        qm.playTo(qm.currentQueue.lastIndex)
        val third = tracks(300, 10)
        qm.appendList(third.reversed(), third, qm.listContext.value!!.copy(tail = album(3)))
        qm.playTo(24)
        val playing = qm.currentTrack.value
        qm.toggleShuffle()
        assertEquals((100L until 110L) + (200L until 210L) + (300L until 310L), qm.currentQueue.map { it.id })
        assertTrue(playing === qm.currentTrack.value)
        qm.toggleShuffle()
        // Albums 1 and 2 stay behind the play head, in their order.
        assertEquals((100L until 110L) + (200L until 210L), qm.currentQueue.take(20).map { it.id })
        assertEquals(20, qm.currentQueueIndex)
        assertTrue(playing === qm.currentTrack.value)
    }

    @Test
    fun `turning shuffle on keeps what has played counting towards the cap`() {
        val qm = QueueManager()
        qm.setQueue(tracks(0, 1_500), 0, context(1))
        qm.playTo(1_499)
        val second = tracks(10_000, 1_000)
        qm.appendList(second, second, qm.listContext.value!!.copy(tail = album(2)))
        qm.playTo(1_900)
        qm.toggleShuffle()
        assertEquals(1_500, qm.currentQueueIndex)
        // The 1,500 behind the play head still count: 501 more and the oldest
        // go, down to album 2, which now starts with the song shuffle was
        // turned on at.
        qm.playTo(2_001)
        qm.addToQueue(tracks(50_000, 5))
        assertEquals(501, qm.currentQueueIndex)
        assertEquals(album(2), qm.listContext.value?.head)
        assertFalse(qm.listContext.value!!.headCut)
    }

    // --- Waiting at the end of the queue ---

    @Test
    fun `the wait for the next list gives up after a while`() = runBlocking {
        val wait = QueueEndWait(limitMs = 50)
        val stuck = Job()
        val started = System.nanoTime()
        assertEquals("moved", wait.await(list = { stuck }, radioBusy = MutableStateFlow(false)) { "moved" })
        // And so does the wait for song radio's first pick.
        assertEquals("moved", wait.await(list = { null }, radioBusy = MutableStateFlow(true)) { "moved" })
        assertTrue(System.nanoTime() - started < 5_000_000_000L)
        stuck.cancel()
    }

    @Test
    fun `one wait at a time`() = runBlocking {
        val wait = QueueEndWait(limitMs = 10_000)
        val loading = Job()
        val first = async { wait.await(list = { loading }, radioBusy = MutableStateFlow(false)) { "first" } }
        yield()
        // Next pressed again meanwhile: no second wait, and nothing for it to do.
        assertNull(wait.await(list = { null }, radioBusy = MutableStateFlow(false)) { "second" })
        loading.complete()
        assertEquals("first", first.await())
        // That one done, the next waits again.
        assertEquals("third", wait.await(list = { null }, radioBusy = MutableStateFlow(false)) { "third" })
    }

    @Test
    fun `the wait ends as soon as song radio has its first pick`() = runBlocking {
        val wait = QueueEndWait(limitMs = 10_000)
        val generating = MutableStateFlow(true)
        val moved = async { wait.await(list = { null }, radioBusy = generating) { "moved" } }
        yield()
        assertFalse(moved.isCompleted)
        generating.value = false
        assertEquals("moved", moved.await())
    }

    // --- The folder tree under the date sort ---

    @Test
    fun `under the date sort each folder's newest file is asked for once a walk`() {
        fun row(path: String, songs: Int) = LocalFolderEntity(
            path = path,
            parentPath = path.substringBeforeLast('/'),
            displayName = path.substringAfterLast('/'),
            trackCount = songs,
        )
        val rows = listOf(
            row("/M", 17), row("/M/A", 8), row("/M/A/1", 3), row("/M/A/2", 3),
            row("/M/B", 4), row("/M/B/1", 2), row("/M/B/2", 2), row("/M/C", 5),
        )
        val newest = mapOf(
            "/M/A" to 300L, "/M/B" to 100L, "/M/C" to 200L,
            "/M/A/1" to 10L, "/M/A/2" to 20L, "/M/B/1" to 5L, "/M/B/2" to 50L,
        )
        val asked = HashMap<String, Int>()
        val view = runBlocking {
            folderView(
                rows = rows,
                sort = FolderSort(folders = SubfolderOrder.DATE_MODIFIED, foldersAscending = false),
                userRoots = emptySet(),
                newestUnder = { path -> asked[path] = (asked[path] ?: 0) + 1; newest[path] },
            )
        }
        val walk = generateSequence("/M/A") { runBlocking { FolderWalk.after(view, it) } }.toList()
        // Newest first, each folder's own songs before its subfolders.
        assertEquals(listOf("/M/A", "/M/A/2", "/M/A/1", "/M/C", "/M/B/2", "/M/B/1"), walk)
        assertEquals(newest.keys, asked.keys)
        assertTrue(asked.values.all { it == 1 })
    }

    private companion object {
        const val KEEP_BEHIND = QueueManager.KEEP_BEHIND_AFTER_TRIM
    }
}
