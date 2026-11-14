package tf.monochrome.android.ui.settings

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.domain.model.EarTune
import tf.monochrome.android.domain.model.RendererProfile

/**
 * The ear tuner's pad (`EarPadMap`) and what it keeps ([EarTune]): where the
 * dot and the handle are for a tuning, and what a touch makes of one.
 */
class EarTunerTest {
    @Test
    fun `the head as it is sits in the middle of the pad and changes nothing`() {
        val none = EarTune()
        assertEquals(0.5f, EarPadMap.dotX(none), 1e-6f)
        assertEquals(0.5f, EarPadMap.dotY(none), 1e-6f)
        assertFalse(none.shapes)
        // The band's place and width alone are not heard: only a level or a size is.
        assertFalse(none.copy(freqHz = 5000f, q = 6f).shapes)
        assertTrue(none.copy(gainDb = 3f).shapes)
        assertTrue(none.copy(size = 1.1f).shapes)
    }

    @Test
    fun `sideways is ear size and up is more height`() {
        val none = EarTune()
        assertEquals(EarTune.MIN_SIZE, EarPadMap.withDot(none, 0f, 0.5f).size, 1e-6f)
        assertEquals(EarTune.MAX_SIZE, EarPadMap.withDot(none, 1f, 0.5f).size, 1e-6f)
        assertEquals(EarTune.MAX_DB, EarPadMap.withDot(none, 0.5f, 0f).gainDb, 1e-6f)
        assertEquals(-EarTune.MAX_DB, EarPadMap.withDot(none, 0.5f, 1f).gainDb, 1e-6f)
        // A touch outside the pad is its edge, and the dot never moves the band's pitch or width.
        val out = EarPadMap.withDot(none.copy(freqHz = 6000f, q = 4f), 3f, -2f)
        assertEquals(EarTune.MAX_SIZE, out.size, 1e-6f)
        assertEquals(EarTune.MAX_DB, out.gainDb, 1e-6f)
        assertEquals(6000f, out.freqHz, 0f)
        assertEquals(4f, out.q, 0f)
    }

    @Test
    fun `the dot goes back to where it was put`() {
        for (x in listOf(0.1f, 0.37f, 0.5f, 0.82f)) for (y in listOf(0.05f, 0.5f, 0.71f, 0.95f)) {
            val tuned = EarPadMap.withDot(EarTune(), x, y)
            // The size is kept in whole percent and the level in half decibels.
            assertEquals(x, EarPadMap.dotX(tuned), 0.02f)
            assertEquals(y, EarPadMap.dotY(tuned), 0.012f)
        }
    }

    @Test
    fun `the ring is the width, and its handle sets it along the diagonal alone`() {
        val wide = EarTune(q = EarTune.MIN_Q)
        val narrow = EarTune(q = EarTune.MAX_Q)
        assertEquals(EarPadMap.RING_WIDE, EarPadMap.ring(wide), 1e-5f)
        assertEquals(EarPadMap.RING_NARROW, EarPadMap.ring(narrow), 1e-5f)
        // Pulling the handle out to where a wide ring's would be makes it wide.
        val start = EarTune(q = 2f)
        val pulled = EarPadMap.withHandle(start, EarPadMap.handleX(wide), EarPadMap.handleY(wide))
        assertEquals(EarTune.MIN_Q, pulled.q, 0.01f)
        val pushed = EarPadMap.withHandle(start, EarPadMap.handleX(narrow), EarPadMap.handleY(narrow))
        assertEquals(EarTune.MAX_Q, pushed.q, 0.05f)
        // And the handle is where its own tuning says: nothing jumps when it is first touched.
        val same = EarPadMap.withHandle(start, EarPadMap.handleX(start), EarPadMap.handleY(start))
        assertEquals(start.q, same.q, 0.01f)
        assertEquals(start.size, same.size, 0f)
        assertEquals(start.gainDb, same.gainDb, 0f)
        // Across the diagonal nothing changes: only along it.
        val across = EarPadMap.withHandle(start, EarPadMap.handleX(start) + 0.1f, EarPadMap.handleY(start) - 0.1f)
        assertEquals(start.q, across.q, 0.01f)
    }

    @Test
    fun `the handle is always inside the pad`() {
        for (x in 0..10) for (y in 0..10) for (q in listOf(EarTune.MIN_Q, 2f, EarTune.MAX_Q)) {
            val tuned = EarPadMap.withDot(EarTune(q = q), x / 10f, y / 10f)
            val hx = EarPadMap.handleX(tuned)
            val hy = EarPadMap.handleY(tuned)
            assertTrue("handle x $hx at $x,$y q $q", hx in 0.03f..0.97f)
            assertTrue("handle y $hy at $x,$y q $q", hy in 0.03f..0.97f)
        }
    }

    @Test
    fun `out of range is brought in and what is not a number is the default`() {
        val wild = EarTune(size = 9f, freqHz = 50f, gainDb = -80f, q = Float.NaN).clamped()
        assertEquals(EarTune.MAX_SIZE, wild.size, 0f)
        assertEquals(EarTune.MIN_HZ, wild.freqHz, 0f)
        assertEquals(-EarTune.MAX_DB, wild.gainDb, 0f)
        assertEquals(EarTune.DEFAULT_Q, wild.q, 0f)
        assertEquals(1f, EarTune(size = Float.POSITIVE_INFINITY).clamped().size, 0f)
    }

    @Test
    fun `a profile saved before the tuner opens with none, and one with it comes back`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString<RendererProfile>("{}")
        assertEquals(EarTune(), old.earTune)
        val tuned = RendererProfile(earTune = EarTune(size = 1.12f, freqHz = 6500f, gainDb = 4.5f, q = 3f))
        assertEquals(tuned.earTune, json.decodeFromString<RendererProfile>(json.encodeToString(RendererProfile.serializer(), tuned)).earTune)
        // The whole profile's clamp holds the tuner too.
        assertEquals(EarTune.MAX_DB, RendererProfile(earTune = EarTune(gainDb = 99f)).clamped().earTune.gainDb, 0f)
    }
}
