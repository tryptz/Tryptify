package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

/**
 * What the registry keeps once the queue moves on. A tap on one song of the
 * library queues the whole library, and every one of those stayed here for the
 * life of the process.
 */
class UnifiedTrackRegistryTest {

    private val queue = QueueManager()
    private val registry = UnifiedTrackRegistry(queue)
    private val keep = UnifiedTrackRegistry.KEEP_OUTSIDE_QUEUE

    private fun local(id: Long) = UnifiedTrack(
        id = "local_$id",
        title = "Song $id",
        durationSeconds = 200,
        artistName = "Artist",
        source = PlaybackSource.LocalFile("/music/$id.flac", AudioCodec.FLAC, 44_100),
        sourceType = SourceType.LOCAL,
    )

    /** Put [ids] here, as a screen does before it plays them. */
    private fun put(ids: LongRange): List<Track> = ids.map { id ->
        registry.put(id, local(id))
        Track(id = id, title = "Song $id")
    }

    @Test
    fun `a new queue lets go of the old one's songs past what is kept outside it`() {
        queue.setQueue(put(1L..5_000L), 0)
        assertEquals(5_000, registry.size)

        queue.setQueue(put(10_001L..10_010L), 0)
        assertEquals(10 + keep, registry.size)
        // The new queue whole, and the old one's last to be put.
        (10_001L..10_010L).forEach { assertNotNull(registry[it]) }
        assertNotNull(registry[5_000L])
        assertNull(registry[1L])
    }

    @Test
    fun `a song put outside the queue outlives an older queue's songs`() {
        // Recently Played puts its rows here each time it is read.
        put(20_001L..20_100L)
        // Then the whole library is queued, and then an album.
        queue.setQueue(put(1L..5_000L), 0)
        queue.setQueue(put(30_001L..30_010L), 0)
        (20_001L..20_100L).forEach { assertNotNull(registry[it]) }
        assertEquals(10 + keep, registry.size)
    }

    @Test
    fun `the queue's pre-shuffle order counts as the queue`() {
        val all = put(1L..3_000L)
        // A restored session whose queue holds part of what shuffle off brings back.
        queue.restore(
            queue = all.take(10),
            originalQueue = all,
            currentIndex = 0,
            shuffleEnabled = true,
            repeatMode = tf.monochrome.android.domain.model.RepeatMode.OFF,
        )
        assertEquals(3_000, registry.size)
        assertTrue((1L..3_000L).all { registry[it] != null })
    }

    @Test
    fun `clearing the queue keeps only what is kept outside it`() {
        queue.setQueue(put(1L..3_000L), 0)
        queue.clearQueue()
        assertEquals(keep, registry.size)
        assertNotNull(registry[3_000L])
    }

    @Test
    fun `a YouTube or SoundCloud song is never let go, its hashed id being the only way back`() {
        val youtube = UnifiedTrack(
            id = "yt:dQw4w9WgXcQ",
            title = "Liked from search",
            durationSeconds = 213,
            artistName = "Artist",
            source = PlaybackSource.Scxyt("yt:dQw4w9WgXcQ"),
            sourceType = SourceType.YOUTUBE,
        )
        registry.put(youtube.legacyId, youtube)
        queue.setQueue(put(1L..5_000L), 0)
        queue.setQueue(put(10_001L..10_010L), 0)
        assertEquals(youtube, registry[youtube.legacyId])
    }

    @Test
    fun `a liked YouTube song known only by its hash is unplayable until its own id comes back`() {
        val youtube = UnifiedTrack(
            id = "yt:dQw4w9WgXcQ",
            title = "Liked before a restart",
            durationSeconds = 213,
            artistName = "Artist",
            source = PlaybackSource.Scxyt("yt:dQw4w9WgXcQ"),
            sourceType = SourceType.YOUTUBE,
        )
        // After a restart the like has only the hash, marked by its source.
        registry.markScxytHash(youtube.legacyId)
        assertTrue(registry.isUnresolvedScxyt(youtube.legacyId))
        // A TIDAL song nobody marked is untouched.
        assertFalse(registry.isUnresolvedScxyt(12_345L))
        // Search or Recently Played puts the song back: playable again.
        registry.put(youtube.legacyId, youtube)
        assertFalse(registry.isUnresolvedScxyt(youtube.legacyId))
    }

    @Test
    fun `a small registry is left as it is`() {
        queue.setQueue(put(1L..10L), 0)
        queue.setQueue(put(11L..20L), 0)
        assertEquals(20, registry.size)
    }
}
