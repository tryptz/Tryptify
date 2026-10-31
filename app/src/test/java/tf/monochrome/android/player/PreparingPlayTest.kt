package tf.monochrome.android.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The play button's spinner while a YouTube or SoundCloud song is made ready:
 * on for the song the listener waits for, off when it is ready, failed or
 * given up, and never left on, or taken off, by a play a newer one replaced.
 */
class PreparingPlayTest {

    private val preparing = PreparingPlay()

    @Test
    fun `it shows while the song is made ready, and goes when it is`() {
        val turn = preparing.begin(prepares = true)
        assertTrue(preparing.waiting.value)
        assertTrue(preparing.isLatest(turn))
        preparing.end(turn)
        assertFalse(preparing.waiting.value)
    }

    @Test
    fun `a song that needs no preparing shows nothing`() {
        preparing.begin(prepares = false)
        assertFalse(preparing.waiting.value)
    }

    @Test
    fun `an older play coming back late neither clears the newer one's spinner nor plays`() {
        val first = preparing.begin(prepares = true)
        val second = preparing.begin(prepares = true)
        preparing.end(first)
        assertTrue(preparing.waiting.value)
        assertFalse(preparing.isLatest(first))
        assertTrue(preparing.isLatest(second))
        preparing.end(second)
        assertFalse(preparing.waiting.value)
    }

    @Test
    fun `a newer tap on a song that needs none takes the spinner off at once`() {
        val youtube = preparing.begin(prepares = true)
        preparing.begin(prepares = false)
        assertFalse(preparing.waiting.value)
        // The YouTube fetch failing later changes nothing.
        preparing.end(youtube)
        assertFalse(preparing.waiting.value)
    }

    @Test
    fun `the newest play still waiting keeps it on after an older one ends`() {
        val tidal = preparing.begin(prepares = false)
        val youtube = preparing.begin(prepares = true)
        preparing.end(tidal)
        assertTrue(preparing.waiting.value)
        preparing.end(youtube)
        assertFalse(preparing.waiting.value)
    }
}
