package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The game's DSP seed is read only when a Trypt Mania player is there to
 * take it, and a player alive still takes every change.
 */
class LazySeedTest {

    /** A reader that counts its reads, as PlaybackService's reads the engine's state back. */
    private class Reader(var value: Int) {
        var reads = 0
        val read: () -> Int = { reads += 1; value }
    }

    @Test
    fun withNoPlayerAChangeIsNotRead() {
        val seeds = LazySeed<Int>()
        val reader = Reader(1)
        repeat(50) { reader.value = it; assertNull(seeds.publish(reader.read, listening = false)) }
        assertEquals(0, reader.reads)
    }

    @Test
    fun aPlayerThatComesReadsTheLatestOnce() {
        val seeds = LazySeed<Int>()
        val reader = Reader(1)
        seeds.publish(reader.read, listening = false)
        reader.value = 7
        seeds.publish(reader.read, listening = false)
        assertEquals(7, seeds.current())
        // The next player, before any change, takes the same without a read.
        assertEquals(7, seeds.current())
        assertEquals(1, reader.reads)
    }

    @Test
    fun aPlayerAliveTakesEveryChange() {
        val seeds = LazySeed<Int>()
        val reader = Reader(0)
        for (v in 1..5) {
            reader.value = v
            assertEquals(v, seeds.publish(reader.read, listening = true))
        }
        assertEquals(5, reader.reads)
    }

    @Test
    fun anEqualSeedIsNoChange() {
        val seeds = LazySeed<Int>()
        val reader = Reader(3)
        assertEquals(3, seeds.publish(reader.read, listening = true))
        assertNull(seeds.publish(reader.read, listening = true))
    }

    @Test
    fun aChangeMadeWhileNobodyListenedIsWhatTheNextPlayerStartsFrom() {
        // A player took 3 and went; the setting moved to 4 with nobody there; then back to 3 with a new player alive.
        val seeds = LazySeed<Int>()
        val reader = Reader(3)
        seeds.publish(reader.read, listening = true)
        reader.value = 4
        seeds.publish(reader.read, listening = false)
        assertEquals(4, seeds.current())
        reader.value = 3
        assertEquals(3, seeds.publish(reader.read, listening = true))
    }

    @Test
    fun nothingPublishedIsNothing() {
        assertNull(LazySeed<Int>().current())
    }

    @Test
    fun retiringReadsWhatIsStaleAndLetsTheReaderGo() {
        val seeds = LazySeed<Int>()
        val reader = Reader(2)
        seeds.publish(reader.read, listening = false)
        seeds.retire()
        assertEquals(1, reader.reads)
        reader.value = 9
        assertEquals(2, seeds.current())
        assertEquals(1, reader.reads)
    }
}
