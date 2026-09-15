package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.AudioCodec
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.PlaybackSource
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.domain.model.Track
import tf.monochrome.android.domain.model.UnifiedTrack

class PlaybackSnapshotTest {

    private fun track(id: Long, title: String = "Track $id") = Track(id = id, title = title)

    private fun unified(id: String, source: PlaybackSource, type: SourceType) = UnifiedTrack(
        id = id,
        title = "Title",
        durationSeconds = 200,
        artistName = "Artist",
        source = source,
        sourceType = type,
    )

    // ── Serialization ───────────────────────────────────────────────
    //
    // PlaybackSource is sealed, discriminated by @SerialName. A rename there
    // would not fail the build — it would send every restored local file down
    // the legacy catalog path to play a different song under the right title.
    // Hence a test per source.

    private fun assertSourceRoundTrips(source: PlaybackSource, type: SourceType) {
        val snapshot = PersistedQueue(
            version = PLAYBACK_SNAPSHOT_VERSION,
            entries = listOf(PersistedQueueEntry(track(1), unified("u1", source, type))),
        )
        val decoded = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot))
        assertNotNull(decoded)
        assertEquals(source, decoded!!.entries.single().unified?.source)
    }

    @Test
    fun `a local file survives the round trip`() {
        assertSourceRoundTrips(
            PlaybackSource.LocalFile(
                filePath = "/storage/emulated/0/Music/a.flac",
                codec = AudioCodec.FLAC,
                sampleRate = 44_100,
                bitDepth = 16,
            ),
            SourceType.LOCAL,
        )
    }

    @Test
    fun `a live station survives the round trip`() {
        assertSourceRoundTrips(
            PlaybackSource.RadioStream(stationUuid = "abc", url = "https://example/stream"),
            SourceType.LIVE_RADIO,
        )
    }

    @Test
    fun `a catalog track survives the round trip`() {
        assertSourceRoundTrips(
            PlaybackSource.QobuzCached(qobuzId = 42, preferredQuality = AudioQuality.LOSSLESS),
            SourceType.QOBUZ,
        )
    }

    @Test
    fun `an entry with no unified track is allowed`() {
        val snapshot = PersistedQueue(
            version = PLAYBACK_SNAPSHOT_VERSION,
            entries = listOf(PersistedQueueEntry(track(7))),
        )
        val decoded = PlaybackSnapshotCodec.decode(PlaybackSnapshotCodec.encode(snapshot))
        assertNotNull(decoded)
        assertEquals(7L, decoded!!.entries.single().track.id)
        assertNull(decoded.entries.single().unified)
    }

    // ── Refusing to act on a bad snapshot ───────────────────────────
    //
    // Each must read as "no snapshot" rather than throw or restore something
    // wrong. Landing where they are today is the acceptable failure.

    @Test
    fun `malformed json is no snapshot`() {
        assertNull(PlaybackSnapshotCodec.decode("{ not json"))
    }

    @Test
    fun `empty and null are no snapshot`() {
        assertNull(PlaybackSnapshotCodec.decode(""))
        assertNull(PlaybackSnapshotCodec.decode(null))
    }

    @Test
    fun `a snapshot from a different version is no snapshot`() {
        val raw = PlaybackSnapshotCodec.encode(
            PersistedQueue(
                version = PLAYBACK_SNAPSHOT_VERSION + 1,
                entries = listOf(PersistedQueueEntry(track(1))),
            )
        )
        assertNull(PlaybackSnapshotCodec.decode(raw))
    }

    @Test
    fun `an empty queue is no snapshot`() {
        val raw = PlaybackSnapshotCodec.encode(PersistedQueue(version = PLAYBACK_SNAPSHOT_VERSION))
        assertNull(PlaybackSnapshotCodec.decode(raw))
    }

    // ── Queue ordering ──────────────────────────────────────────────

    @Test
    fun `an unshuffled queue stores no ordering`() {
        val queue = listOf(track(1), track(2), track(3))
        assertTrue(QueueOrdering.encode(queue, queue).isEmpty())
    }

    @Test
    fun `a shuffled queue round-trips to its original order`() {
        val original = listOf(track(1), track(2), track(3), track(4))
        val shuffled = listOf(track(3), track(1), track(4), track(2))
        val order = QueueOrdering.encode(shuffled, original)
        assertEquals(original.map { it.id }, QueueOrdering.decode(shuffled, order).map { it.id })
    }

    @Test
    fun `duplicate tracks map to distinct positions`() {
        // Matching by id collapses both copies onto whichever it found first.
        val original = listOf(track(1), track(2), track(1))
        val shuffled = listOf(track(2), track(1), track(1))
        val order = QueueOrdering.encode(shuffled, original)
        assertEquals(listOf(1, 0, 2), order)
        assertEquals(original.map { it.id }, QueueOrdering.decode(shuffled, order).map { it.id })
    }

    @Test
    fun `originals the queue no longer holds are dropped`() {
        // originalQueue survives setQueue and toggleShuffle but not
        // removeFromQueue or move, so the two really diverge — as a permutation
        // it would resurrect deleted tracks when shuffle is toggled off.
        val original = listOf(track(1), track(2), track(3))
        val current = listOf(track(3), track(1))
        val order = QueueOrdering.encode(current, original)
        assertEquals(listOf(1L, 3L), QueueOrdering.decode(current, order).map { it.id })
    }

    @Test
    fun `an empty ordering decodes as identity`() {
        val queue = listOf(track(1), track(2))
        assertEquals(queue, QueueOrdering.decode(queue, emptyList()))
    }

    @Test
    fun `an out-of-range ordering does not throw`() {
        val queue = listOf(track(1), track(2))
        assertEquals(listOf(2L), QueueOrdering.decode(queue, listOf(1, 9)).map { it.id })
    }

    // ── Windowing a long queue ──────────────────────────────────────

    @Test
    fun `a queue under the cap is stored whole`() {
        val window = QueueWindow.around(size = 40, currentIndex = 12)
        assertEquals(0, window.from)
        assertEquals(40, window.until)
        assertEquals(12, window.currentIndex)
    }

    @Test
    fun `a long queue keeps the current track and is capped`() {
        val window = QueueWindow.around(size = 5_000, currentIndex = 2_000)
        assertEquals(QueueWindow.MAX_PERSISTED_ENTRIES, window.until - window.from)
        assertTrue(window.currentIndex in 0 until (window.until - window.from))
        // Rebased, not the original index.
        assertEquals(QueueWindow.KEEP_BEHIND, window.currentIndex)
    }

    @Test
    fun `a play head near the start does not run off the front`() {
        val window = QueueWindow.around(size = 5_000, currentIndex = 3)
        assertEquals(0, window.from)
        assertEquals(3, window.currentIndex)
    }

    @Test
    fun `a play head near the end still stores a full window`() {
        val window = QueueWindow.around(size = 5_000, currentIndex = 4_999)
        assertEquals(QueueWindow.MAX_PERSISTED_ENTRIES, window.until - window.from)
        assertEquals(5_000, window.until)
        assertEquals(299, window.currentIndex)
    }

    @Test
    fun `an empty queue windows to nothing`() {
        val window = QueueWindow.around(size = 0, currentIndex = -1)
        assertEquals(0, window.until - window.from)
        assertEquals(-1, window.currentIndex)
    }

    // ── When a position is worth writing ────────────────────────────

    @Test
    fun `a flush always writes`() {
        assertTrue(
            PositionWriteThrottle.shouldWrite(
                lastWriteAt = 1_000, now = 1_010,
                lastPositionMs = 5_000, positionMs = 5_010, flush = true,
            )
        )
    }

    @Test
    fun `a small move moments later does not write`() {
        assertFalse(
            PositionWriteThrottle.shouldWrite(
                lastWriteAt = 1_000, now = 1_300,
                lastPositionMs = 5_000, positionMs = 5_300, flush = false,
            )
        )
    }

    @Test
    fun `a move after the interval writes`() {
        assertTrue(
            PositionWriteThrottle.shouldWrite(
                lastWriteAt = 1_000, now = 1_000 + PositionWriteThrottle.MIN_INTERVAL_MS,
                lastPositionMs = 5_000, positionMs = 10_000, flush = false,
            )
        )
    }

    @Test
    fun `going backwards always writes`() {
        // A seek back or a track change: the stored position is wrong about
        // which second of which song, so it can't wait for the interval.
        assertTrue(
            PositionWriteThrottle.shouldWrite(
                lastWriteAt = 1_000, now = 1_010,
                lastPositionMs = 120_000, positionMs = 0, flush = false,
            )
        )
    }

    @Test
    fun `a long wait with no movement does not write`() {
        assertFalse(
            PositionWriteThrottle.shouldWrite(
                lastWriteAt = 1_000, now = 60_000,
                lastPositionMs = 5_000, positionMs = 5_000, flush = false,
            )
        )
    }
}
