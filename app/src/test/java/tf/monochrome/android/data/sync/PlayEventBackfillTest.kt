package tf.monochrome.android.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.data.db.entity.PlayEventEntity

/**
 * Uploading plays the cloud never accepted has to be safe to repeat: it runs on
 * every launch, and a play whose upload landed but whose reply was lost must be
 * matched to its cloud row, never inserted a second time.
 */
class PlayEventBackfillTest {

    private fun play(rowId: Long, trackId: Long, playedAt: Long) =
        PlayEventEntity(rowId = rowId, trackId = trackId, title = "t$trackId", playedAt = playedAt)

    private fun cloud(id: Long, trackId: Long, playedAt: Long) =
        SbPlayEventKey(id = id, track_id = trackId, played_at_ms = playedAt)

    @Test
    fun `a play the cloud already holds is matched, not inserted`() {
        val landed = play(1, trackId = 10, playedAt = 1_000)
        val stranded = play(2, trackId = 11, playedAt = 2_000)
        val match = matchPlays(listOf(landed, stranded), listOf(cloud(500, 10, 1_000)))
        assertEquals(listOf(landed to 500L), match.matched)
        assertEquals(listOf(stranded), match.unmatched)
    }

    @Test
    fun `a match needs the same track and the same millisecond`() {
        val local = play(1, trackId = 10, playedAt = 1_000)
        val match = matchPlays(
            listOf(local),
            listOf(cloud(500, 10, 1_001), cloud(501, 99, 1_000)),
        )
        assertTrue(match.matched.isEmpty())
        assertEquals(listOf(local), match.unmatched)
    }

    @Test
    fun `each cloud row is claimed by one play only`() {
        // Two plays of one track in the same millisecond are two rows on both
        // sides. With one cloud row, one play adopts it and the other is sent.
        val first = play(1, trackId = 10, playedAt = 1_000)
        val second = play(2, trackId = 10, playedAt = 1_000)
        val match = matchPlays(listOf(first, second), listOf(cloud(500, 10, 1_000)))
        assertEquals(listOf(first to 500L), match.matched)
        assertEquals(listOf(second), match.unmatched)

        val both = matchPlays(listOf(first, second), listOf(cloud(500, 10, 1_000), cloud(501, 10, 1_000)))
        assertEquals(listOf(first to 500L, second to 501L), both.matched)
        assertTrue(both.unmatched.isEmpty())
    }

    @Test
    fun `inserted rows map back to the plays that sent them`() {
        // The insert's reply is matched the same way, so the ids land on the
        // right local rows whatever order the reply comes back in.
        val a = play(1, trackId = 10, playedAt = 1_000)
        val b = play(2, trackId = 11, playedAt = 2_000)
        val reply = listOf(cloud(601, 11, 2_000), cloud(600, 10, 1_000))
        assertEquals(listOf(a to 600L, b to 601L), matchPlays(listOf(a, b), reply).matched)
    }

    @Test
    fun `every upload row carries every NOT NULL column`() {
        // A bulk insert names the union of all rows' keys, and a row missing
        // one sends NULL for it. Encode with the settings that drop the most —
        // no defaults, no explicit nulls — and every NOT NULL column must
        // still be present, on a sparse row as on a full one.
        val json = Json { encodeDefaults = false; explicitNulls = false }
        val sparse = PlayEventEntity(trackId = 1, title = "", duration = 0, artistName = "", playedAt = 5)
        val full = PlayEventEntity(
            trackId = 2, title = "Song", duration = 200, artistId = 3, artistName = "Artist",
            albumId = 4, albumTitle = "Album", albumCover = "cover", audioQuality = "LOSSLESS",
            source = "tidal", playedAt = 6,
        )
        val notNull = setOf("user_id", "track_id", "title", "duration", "artist_name", "played_at_ms", "started_at")
        listOf(sparse, full).forEach { row ->
            val keys = json.encodeToJsonElement(row.toUpload("u1")).jsonObject.keys
            assertTrue("missing ${notNull - keys}", keys.containsAll(notNull))
        }
    }
}
