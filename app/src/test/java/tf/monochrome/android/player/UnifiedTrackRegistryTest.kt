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

    private fun youtube(id: Long) = UnifiedTrack(
        id = "yt:video$id",
        title = "Result $id",
        durationSeconds = 213,
        artistName = "Channel",
        source = PlaybackSource.Scxyt("yt:video$id"),
        sourceType = SourceType.YOUTUBE,
    )

    /** Plays [searches] YouTube searches of 20 results each, numbered from [from]: each list is put, then queued. */
    private fun playSearches(searches: Int, from: Long = 1L) {
        repeat(searches) { s ->
            val first = from + s * 20L
            val results = (first until first + 20).map { id ->
                registry.put(id, youtube(id))
                Track(id = id, title = "Result $id")
            }
            queue.setQueue(results, 0)
        }
    }

    @Test
    fun `YouTube and SoundCloud songs outside the queue are held to a bound of their own, the newest kept`() {
        val scxytKeep = UnifiedTrackRegistry.KEEP_SCXYT_OUTSIDE_QUEUE
        // 125 searches played: 2,500 results, 500 more than the bound.
        playSearches((scxytKeep + 500) / 20)
        queue.setQueue(put(900_001L..900_010L), 0)

        assertEquals(scxytKeep, (1L..(scxytKeep + 500L)).count { registry[it] != null })
        // The latest searches stay whole; the first ones are gone.
        assertNotNull(registry[scxytKeep + 500L])
        assertNotNull(registry[501L])
        assertNull(registry[500L])
        assertNull(registry[1L])
        // One let go is still known for what it is: never sent to TIDAL.
        assertTrue(registry.isUnresolvedScxyt(1L))
        assertFalse(registry.isUnresolvedScxyt(501L))
    }

    @Test
    fun `a liked or playlisted YouTube song outlives that bound and still plays`() {
        registry.put(1L, youtube(1L))
        // Reading Liked Songs, or liking it, marks it.
        registry.markScxytHash(1L)
        queue.setQueue(listOf(Track(id = 1L, title = "Liked")), 0)
        playSearches((UnifiedTrackRegistry.KEEP_SCXYT_OUTSIDE_QUEUE + 500) / 20, from = 10_001L)
        queue.setQueue(put(900_001L..900_010L), 0)

        assertNotNull(registry[1L])
        assertFalse(registry.isUnresolvedScxyt(1L))
        // And one put back after it was let go (Recently Played read again) plays again.
        assertTrue(registry.isUnresolvedScxyt(10_001L))
        registry.put(10_001L, youtube(10_001L))
        assertFalse(registry.isUnresolvedScxyt(10_001L))
    }

    @Test
    fun `a YouTube or SoundCloud song is not pushed out by other sources' songs`() {
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
    fun `an id in the YouTube and SoundCloud range is never sent to TIDAL, marked or not`() {
        val id = tf.monochrome.android.domain.model.scxytLegacyId("yt:dQw4w9WgXcQ")
        // Synced from another device and not yet read as a like: nothing marked it.
        assertTrue(registry.isUnresolvedScxyt(id))
        assertFalse(registry.isUnresolvedScxyt(123_456_789L))
    }

    @Test
    fun `a small registry is left as it is`() {
        queue.setQueue(put(1L..10L), 0)
        queue.setQueue(put(11L..20L), 0)
        assertEquals(20, registry.size)
    }
}
