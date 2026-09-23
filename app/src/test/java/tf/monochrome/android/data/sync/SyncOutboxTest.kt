package tf.monochrome.android.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pending-change list is what stops the cloud and the device undoing each
 * other: a delete the cloud hasn't confirmed must stay pending, and must stay
 * visible to the pull, until it is confirmed — and nothing else.
 */
class SyncOutboxTest {

    private fun change(
        key: String,
        op: SyncOp = SyncOp.DELETE,
        kind: SyncKind = SyncKind.FAVORITE_TRACK,
        user: String = "u1",
    ) = PendingChange(userId = user, kind = kind, key = key, op = op)

    @Test
    fun `a later edit to the same row replaces the earlier one`() {
        val unliked = change("7", SyncOp.DELETE)
        val relinked = change("7", SyncOp.UPSERT)
        val pending = Outbox.record(Outbox.record(emptyList(), unliked), relinked)
        assertEquals(listOf(relinked), pending)
        assertTrue(Outbox.pendingDeletes(pending, "u1", SyncKind.FAVORITE_TRACK).isEmpty())
    }

    @Test
    fun `an unconfirmed delete is visible to the pull`() {
        val pending = Outbox.record(emptyList(), change("7"))
        assertEquals(setOf("7"), Outbox.pendingDeletes(pending, "u1", SyncKind.FAVORITE_TRACK))
        // Scoped by kind: a favourite album with the same id is not affected.
        assertTrue(Outbox.pendingDeletes(pending, "u1", SyncKind.FAVORITE_ALBUM).isEmpty())
    }

    @Test
    fun `settling a sent edit keeps a newer edit to the same row`() {
        val sent = change("7", SyncOp.UPSERT)
        // Recorded while `sent` was in flight: same row, same op, new stamp.
        val newer = change("7", SyncOp.UPSERT)
        val pending = Outbox.record(Outbox.record(emptyList(), sent), newer)
        assertEquals(listOf(newer), Outbox.settle(pending, sent))
    }

    @Test
    fun `settling removes exactly the confirmed edit`() {
        val a = change("1")
        val b = change("2")
        val pending = Outbox.record(Outbox.record(emptyList(), a), b)
        assertEquals(listOf(b), Outbox.settle(pending, a))
    }

    @Test
    fun `edits are never shared between accounts`() {
        val mine = change("7", user = "u1")
        val theirs = change("7", user = "u2")
        val pending = Outbox.record(Outbox.record(emptyList(), mine), theirs)
        assertEquals(2, pending.size)
        assertEquals(listOf(mine), Outbox.forUser(pending, "u1"))
        assertEquals(setOf("7"), Outbox.pendingDeletes(pending, "u2", SyncKind.FAVORITE_TRACK))
    }

    @Test
    fun `playlists flush before their entries`() {
        val entry = change(playlistTrackKey("p1", 5), SyncOp.UPSERT, SyncKind.PLAYLIST_TRACK)
        val playlist = change("p1", SyncOp.UPSERT, SyncKind.PLAYLIST)
        val pending = Outbox.record(Outbox.record(emptyList(), entry), playlist)
        assertEquals(listOf(playlist, entry), Outbox.forUser(pending, "u1"))
    }

    @Test
    fun `deleting a playlist drops its pending entry edits`() {
        val entry = change(playlistTrackKey("p1", 5), SyncOp.UPSERT, SyncKind.PLAYLIST_TRACK)
        val otherEntry = change(playlistTrackKey("p2", 5), SyncOp.UPSERT, SyncKind.PLAYLIST_TRACK)
        val deleted = change("p1", SyncOp.DELETE, SyncKind.PLAYLIST)
        val pending = listOf(entry, otherEntry).fold(emptyList<PendingChange>(), Outbox::record)
        assertEquals(listOf(otherEntry, deleted), Outbox.record(pending, deleted))
    }

    @Test
    fun `playlist entry keys round-trip`() {
        val key = playlistTrackKey("0b6c1d2e-aaaa-bbbb-cccc-1234567890ab", -42L)
        assertEquals("0b6c1d2e-aaaa-bbbb-cccc-1234567890ab" to -42L, parsePlaylistTrackKey(key))
        assertNull(parsePlaylistTrackKey("no-slash"))
        assertNull(parsePlaylistTrackKey("p1/not-a-number"))
    }
}
