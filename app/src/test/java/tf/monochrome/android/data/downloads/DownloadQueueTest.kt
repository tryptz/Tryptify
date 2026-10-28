package tf.monochrome.android.data.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The queue is the part that must not lose a track. Fifty downloads is fifty
 * rows that each have to end up finished, failed-and-visible, or deliberately
 * removed — never silently gone, and never running more than [DownloadQueue.CONCURRENCY]
 * at a time, which is what the crash this replaced came from.
 */
class DownloadQueueTest {

    private fun queue() = DownloadQueue()

    private fun item(id: Long) = DownloadItem(
        trackId = id,
        title = "Track $id",
        artistName = "Artist",
    )

    private fun items(count: Int) = (1L..count).map { item(it) }

    @Test
    fun `fifty enqueued tracks are all queued`() {
        val q = queue()
        q.enqueue(items(50))
        assertEquals(50, q.entries.value.size)
        assertTrue(q.entries.value.all { it.status == DownloadStatus.QUEUED })
        assertTrue(q.hasWork())
    }

    @Test
    fun `takeNext never hands out more than asked for`() {
        val q = queue()
        q.enqueue(items(50))
        val batch = q.takeNext(DownloadQueue.CONCURRENCY)
        assertEquals(DownloadQueue.CONCURRENCY, batch.size)
        assertEquals(DownloadQueue.CONCURRENCY, q.runningCount())
        // The rest stay waiting rather than all starting at once — the whole
        // point of the queue.
        assertEquals(50 - DownloadQueue.CONCURRENCY, q.entries.value.count { it.status == DownloadStatus.QUEUED })
    }

    @Test
    fun `takeNext preserves queue order`() {
        val q = queue()
        q.enqueue(items(10))
        assertEquals(listOf(1L, 2L, 3L), q.takeNext(3).map { it.trackId })
    }

    @Test
    fun `a running entry is not handed out twice`() {
        val q = queue()
        q.enqueue(items(4))
        val first = q.takeNext(2).map { it.trackId }
        val second = q.takeNext(2).map { it.trackId }
        assertTrue("$first and $second overlap", first.intersect(second.toSet()).isEmpty())
    }

    @Test
    fun `takeNext returns nothing when everything is running`() {
        val q = queue()
        q.enqueue(items(2))
        q.takeNext(2)
        assertTrue(q.takeNext(2).isEmpty())
    }

    @Test
    fun `draining the whole queue completes every track exactly once`() {
        val q = queue()
        q.enqueue(items(50))
        val done = mutableListOf<Long>()
        while (q.hasWork()) {
            val batch = q.takeNext(DownloadQueue.CONCURRENCY - q.runningCount())
            assertTrue("stalled with work outstanding", batch.isNotEmpty())
            batch.forEach { done += it.trackId; q.complete(it.trackId) }
        }
        assertEquals((1L..50L).toList(), done.sorted())
        assertEquals(50, done.size)
        assertTrue(q.entries.value.isEmpty())
    }

    @Test
    fun `a retryable failure goes back in line until the attempt limit`() {
        val q = queue()
        q.enqueue(listOf(item(1)))
        repeat(DownloadQueue.MAX_ATTEMPTS - 1) {
            q.takeNext(1)
            q.fail(1, retryable = true)
            assertEquals(DownloadStatus.QUEUED, q.entries.value.single().status)
        }
        q.takeNext(1)
        q.fail(1, retryable = true)
        assertEquals(DownloadStatus.FAILED, q.entries.value.single().status)
    }

    @Test
    fun `a permanent failure is not retried`() {
        val q = queue()
        q.enqueue(listOf(item(1)))
        q.takeNext(1)
        q.fail(1, retryable = false)
        assertEquals(DownloadStatus.FAILED, q.entries.value.single().status)
    }

    @Test
    fun `a failed entry stays visible and stops the queue asking for more work`() {
        val q = queue()
        q.enqueue(listOf(item(1)))
        q.takeNext(1)
        q.fail(1, retryable = false)
        // Still listed, so it can be retried or dismissed — but not work.
        assertEquals(1, q.entries.value.size)
        assertFalse(q.hasWork())
        assertTrue(q.takeNext(3).isEmpty())
    }

    @Test
    fun `retry puts a failed entry back to the front of the work`() {
        val q = queue()
        q.enqueue(listOf(item(1)))
        q.takeNext(1)
        q.fail(1, retryable = false)
        q.retry(1)
        assertTrue(q.hasWork())
        assertEquals(0, q.entries.value.single().attempts)
        assertEquals(listOf(1L), q.takeNext(1).map { it.trackId })
    }

    @Test
    fun `a permanent failure keeps its reason until it is retried`() {
        val q = queue()
        q.enqueue(listOf(item(1)))
        q.takeNext(1)
        q.fail(1, retryable = false, reason = "Dolby Atmos unavailable: no TIDAL server is set")
        assertEquals("Dolby Atmos unavailable: no TIDAL server is set", q.entries.value.single().error)
        q.retry(1)
        assertEquals(null, q.entries.value.single().error)
    }

    @Test
    fun `re-requesting a track already queued does not duplicate it`() {
        val q = queue()
        q.enqueue(listOf(item(1), item(2)))
        q.enqueue(listOf(item(1)))
        assertEquals(2, q.entries.value.size)
    }

    @Test
    fun `re-requesting a failed track restarts it instead of stacking a second row`() {
        val q = queue()
        q.enqueue(listOf(item(1)))
        q.takeNext(1)
        q.fail(1, retryable = false)
        q.enqueue(listOf(item(1)))
        assertEquals(1, q.entries.value.size)
        assertEquals(DownloadStatus.QUEUED, q.entries.value.single().status)
    }

    @Test
    fun `removing a running entry signals the transfer to stop`() {
        val q = queue()
        val cancelled = mutableListOf<Long>()
        q.onCancel = { cancelled += it }
        q.enqueue(items(2))
        q.takeNext(2)
        q.remove(1)
        // Dropping the row is not enough — the bytes are still arriving.
        assertEquals(listOf(1L), cancelled)
        assertEquals(listOf(2L), q.entries.value.map { it.item.trackId })
    }

    @Test
    fun `removing a queued entry does not signal a cancel`() {
        val q = queue()
        val cancelled = mutableListOf<Long>()
        q.onCancel = { cancelled += it }
        q.enqueue(items(2))
        q.remove(2)
        assertTrue(cancelled.isEmpty())
    }

    @Test
    fun `clear cancels everything in flight`() {
        val q = queue()
        val cancelled = mutableListOf<Long>()
        q.onCancel = { cancelled += it }
        q.enqueue(items(5))
        q.takeNext(3)
        q.clear()
        assertEquals(listOf(1L, 2L, 3L), cancelled.sorted())
        assertTrue(q.entries.value.isEmpty())
        assertFalse(q.hasWork())
    }

    @Test
    fun `an interrupted transfer is picked back up rather than stranded`() {
        val q = queue()
        q.enqueue(items(3))
        q.takeNext(2)
        // The process died here: two entries are marked running with nothing
        // running them.
        q.requeueOrphans()
        assertEquals(0, q.runningCount())
        assertEquals(3, q.entries.value.count { it.status == DownloadStatus.QUEUED })
    }

    @Test
    fun `the queue round-trips through persistence`() {
        val q = queue()
        q.enqueue(items(50))
        val saved = q.savedJson()

        val restored = queue()
        restored.restore(saved)
        assertEquals(50, restored.entries.value.size)
        assertEquals(
            q.entries.value.map { it.item.trackId },
            restored.entries.value.map { it.item.trackId },
        )
        // Everything comes back waiting: an interrupted transfer restarts.
        assertTrue(restored.entries.value.all { it.status == DownloadStatus.QUEUED })
    }

    @Test
    fun `a Qobuz track is still Qobuz's after the queue is restored`() {
        // The id alone is a bare number TIDAL also uses, and the registry that
        // tells them apart may not be loaded yet when the queue comes back.
        val q = queue()
        q.enqueue(listOf(item(1).copy(isQobuz = true), item(2)))
        val saved = q.savedJson()

        val restored = queue()
        restored.restore(saved)
        assertEquals(listOf(true, false), restored.entries.value.map { it.item.isQobuz })
    }

    @Test
    fun `a queue saved before the Qobuz flag existed still restores`() {
        val q = queue()
        q.restore("""[{"trackId":7,"title":"Old","artistName":"Artist"}]""")
        assertEquals(listOf(7L), q.entries.value.map { it.item.trackId })
        assertFalse(q.entries.value.single().item.isQobuz)
    }

    @Test
    fun `restoring junk leaves the queue alone`() {
        val q = queue()
        q.enqueue(items(2))
        q.restore("not json")
        assertEquals(2, q.entries.value.size)
    }

    @Test
    fun `progress is clamped and only applies to a running entry`() {
        val q = queue()
        q.enqueue(items(2))
        q.takeNext(1)
        q.setProgress(1, 5f)
        q.setProgress(2, 0.5f)
        assertEquals(1f, q.entries.value.first { it.item.trackId == 1L }.progress, 0.0001f)
        assertEquals(0f, q.entries.value.first { it.item.trackId == 2L }.progress, 0.0001f)
    }

    @Test
    fun `progress is published a percent or a quarter second at a time`() {
        val q = queue()
        var now = 0L
        q.clock = { now }
        q.enqueue(items(1))
        q.takeNext(1)
        fun shown() = q.entries.value.single().progress

        // The first report of a transfer always shows.
        q.setProgress(1, 0.05f)
        assertEquals(0.05f, shown(), 0.0001f)
        // An 8 KB read of a 40 MB file is a fraction of a percent: held back.
        q.setProgress(1, 0.0502f)
        q.setProgress(1, 0.055f)
        assertEquals(0.05f, shown(), 0.0001f)
        // A whole percent goes out at once.
        q.setProgress(1, 0.061f)
        assertEquals(0.061f, shown(), 0.0001f)
        // A slow transfer still moves, once a quarter second has passed.
        now += 100
        q.setProgress(1, 0.062f)
        assertEquals(0.061f, shown(), 0.0001f)
        now += DownloadQueue.PROGRESS_INTERVAL_MS
        q.setProgress(1, 0.063f)
        assertEquals(0.063f, shown(), 0.0001f)
        // And the finish is never held back.
        q.setProgress(1, 1f)
        assertEquals(1f, shown(), 0.0001f)
    }

    @Test
    fun `a retried transfer reports its first progress again`() {
        val q = queue()
        q.clock = { 0L }
        q.enqueue(items(1))
        q.takeNext(1)
        q.setProgress(1, 0.5f)
        q.fail(1, retryable = true)
        q.takeNext(1)
        // Within a percent of the last attempt's last report, at the same
        // instant: still shown, because it is this attempt's first.
        q.setProgress(1, 0.05f)
        q.setProgress(1, 0.051f)
        assertEquals(0.05f, q.entries.value.single().progress, 0.0001f)
    }

    @Test
    fun `progress makes far fewer queue updates than reads`() {
        val q = queue()
        var now = 0L
        q.clock = { now }
        q.enqueue(items(3))
        q.takeNext(1)
        var updates = 0
        var last = q.entries.value
        // A 40 MB file in 8 KB reads, arriving a millisecond apart.
        val reads = 40_000_000 / 8_192
        for (read in 1..reads) {
            now++
            q.setProgress(1, 0.05f + 0.9f * read / reads)
            if (q.entries.value !== last) { updates++; last = q.entries.value }
        }
        assertTrue("$updates updates for $reads reads", updates in 1..200)
    }

    @Test
    fun `only a change to which tracks are queued asks for a save`() {
        val q = queue()
        q.enqueue(items(3))
        val afterEnqueue = q.saveRevision.value
        // Status changes are not saved, so they must not cost a write.
        q.takeNext(2)
        q.setProgress(1, 0.5f)
        q.fail(2, retryable = true)
        q.requeueOrphans()
        assertEquals(afterEnqueue, q.saveRevision.value)
        // Re-requesting tracks that are all queued already changes nothing.
        q.enqueue(items(2))
        assertEquals(afterEnqueue, q.saveRevision.value)
        q.complete(1)
        assertTrue(q.saveRevision.value > afterEnqueue)
        assertEquals(listOf(2L, 3L), restoredIds(q.savedJson()))
    }

    @Test
    fun `what is saved is always the queue as it stands`() {
        val q = queue()
        q.enqueue(items(5))
        q.takeNext(3)
        q.complete(1)
        q.remove(4)
        assertEquals(listOf(2L, 3L, 5L), restoredIds(q.savedJson()))
        q.clear()
        assertEquals(emptyList<Long>(), restoredIds(q.savedJson()))
    }

    @Test
    fun `a download asked for before the saved queue loads is kept`() {
        val saved = queue().apply { enqueue(items(2)) }.savedJson()
        val q = queue()
        // Queued in the first moments after launch, before the saved queue
        // was read; one of them is also in the saved queue.
        q.enqueue(listOf(item(2), item(9)))
        q.takeNext(1)
        q.restore(saved)
        assertEquals(listOf(1L, 2L, 9L), q.entries.value.map { it.item.trackId })
        // The track already running keeps running rather than starting over.
        assertEquals(DownloadStatus.DOWNLOADING, q.entries.value.first { it.item.trackId == 2L }.status)
        assertEquals(listOf(1L, 2L, 9L), restoredIds(q.savedJson()))
    }

    private fun restoredIds(json: String): List<Long> =
        queue().apply { restore(json) }.entries.value.map { it.item.trackId }
}
