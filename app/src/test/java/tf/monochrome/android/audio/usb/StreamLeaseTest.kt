// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.usb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.usb.StreamClaimant.GAME
import tf.monochrome.android.audio.usb.StreamClaimant.PLAYER

/** The DAC's one stream between the main player and Trypt Mania. */
class StreamLeaseTest {

    @Test
    fun theLastToStartHoldsTheStream() {
        val lease = StreamLease()
        assertNull(lease.holder.value)
        assertTrue(lease.take(PLAYER) { true })
        assertEquals(PLAYER, lease.holder.value)
        // The game starts a song: it is the game's now, whatever the player thinks.
        assertTrue(lease.take(GAME) { true })
        assertEquals(GAME, lease.holder.value)
        assertTrue(lease.holds(GAME))
        assertTrue(lease.heldByAnother(PLAYER))
        assertFalse(lease.heldByAnother(GAME))
    }

    @Test
    fun aStartThatFailsLeavesNobodyHoldingIt() {
        val lease = StreamLease()
        lease.take(PLAYER) { true }
        // The DAC refused the game's format: the player's stream went down with the try.
        assertFalse(lease.take(GAME) { false })
        assertNull(lease.holder.value)
        assertFalse(lease.heldByAnother(PLAYER))
    }

    @Test
    fun onlyTheHolderWritesParksOrFlushes() {
        val lease = StreamLease()
        lease.take(GAME) { true }
        var playerWrote = 0
        var gameWrote = 0
        // The main player's renderer is still feeding, and its park timer still
        // armed, after the game took the stream: none of it may reach the DAC.
        assertNull(lease.tryAsHolder(PLAYER) { playerWrote++ })
        assertNull(lease.asHolder(PLAYER) { playerWrote++ })
        assertEquals(1, lease.tryAsHolder(GAME) { ++gameWrote })
        assertEquals(2, lease.asHolder(GAME) { ++gameWrote })
        assertEquals(0, playerWrote)
    }

    @Test
    fun aWriteNeverWaitsOnAStartInProgress() {
        val lease = StreamLease()
        lease.take(PLAYER) { true }
        val starting = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val other = Thread {
            lease.take(GAME) {
                starting.countDown()
                finish.await(5, TimeUnit.SECONDS)
                true
            }
        }
        other.start()
        assertTrue(starting.await(5, TimeUnit.SECONDS))
        // The player still reads as the holder, but the lock is the start's:
        // the write is refused rather than blocking the audio thread.
        assertNull(lease.tryAsHolder(PLAYER) { 1 })
        finish.countDown()
        other.join(5_000)
        assertEquals(GAME, lease.holder.value)
    }

    @Test
    fun lettingGoParksAndFreesTheStream() {
        val lease = StreamLease()
        lease.take(GAME) { true }
        var parked = 0
        // The player cannot let go of what it does not hold.
        assertFalse(lease.release(PLAYER) { parked++ })
        assertEquals(GAME, lease.holder.value)
        assertTrue(lease.release(GAME) { parked++ })
        assertEquals(1, parked)
        assertNull(lease.holder.value)
        // Letting go twice changes nothing.
        assertFalse(lease.release(GAME) { parked++ })
        assertEquals(1, parked)
    }

    @Test
    fun endingTheStreamIsForTheHolderOrAFreeStream() {
        val lease = StreamLease()
        var stopped = 0
        lease.take(GAME) { true }
        // The main player's queue was cleared under a run: the game's stream stays up.
        assertFalse(lease.end(PLAYER) { stopped++ })
        assertEquals(0, stopped)
        assertEquals(GAME, lease.holder.value)
        // The run ended and the parked stream is nobody's: the player may end it.
        lease.release(GAME) {}
        assertTrue(lease.end(PLAYER) { stopped++ })
        assertEquals(1, stopped)
        assertNull(lease.holder.value)
        // And the holder may, as ever.
        lease.take(PLAYER) { true }
        assertTrue(lease.end(PLAYER) { stopped++ })
        assertEquals(2, stopped)
        assertNull(lease.holder.value)
    }

    @Test
    fun anUnplugClearsIt() {
        val lease = StreamLease()
        lease.take(GAME) { true }
        lease.clear()
        assertNull(lease.holder.value)
        assertFalse(lease.holds(GAME))
    }
}
