package tf.monochrome.android.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The album cross-fade, held to the property that makes it cheap.
 *
 * The fade used to hand out a new palette on every displayed frame, and every
 * distinct palette costs a rebuilt colour scheme and a recomposition of
 * everything reading it. Quantising the fraction means frames that would land
 * on the same colours compare equal instead, and `derivedStateOf` drops them
 * before any of that happens — so what is asserted here is not a number of
 * milliseconds but the count of values that escape, which is the thing the cost
 * is actually proportional to.
 */
class PaletteBlendTest {

    private val from = DynamicPalette(
        primary = Color(0xFF2266DD),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF16304F),
        secondary = Color(0xFF44AA88),
        onSecondary = Color.Black,
        background = Color(0xFF101014),
    )

    private val to = DynamicPalette(
        primary = Color(0xFFDD5522),
        onPrimary = Color.Black,
        primaryContainer = Color(0xFF4F2616),
        secondary = Color(0xFFAA8844),
        onSecondary = Color.White,
        background = Color(0xFFF4F0EA),
    )

    /** A 120 Hz panel, which is what the fade is being made cheap for. */
    private fun framesFor(durationMillis: Int): List<Float> {
        val frames = durationMillis * 120 / 1000
        return (0..frames).map { it.toFloat() / frames }
    }

    private fun distinctPalettes(durationMillis: Int): Int =
        framesFor(durationMillis)
            .map { lerp(from, to, quantiseFraction(it, durationMillis)) }
            .distinct()
            .size

    @Test
    fun `both endpoints pass through exactly`() {
        // 1f especially: it is the real target palette, and a fade settling on a
        // rounded approximation would leave the track slightly the wrong colour
        // for as long as it played.
        assertEquals(0f, quantiseFraction(0f, 8_000), 0f)
        assertEquals(1f, quantiseFraction(1f, 8_000), 0f)
        assertEquals(to, lerp(from, to, quantiseFraction(1f, 8_000)))
        assertEquals(from, lerp(from, to, quantiseFraction(0f, 8_000)))
    }

    @Test
    fun `frames inside one step produce an equal palette`() {
        // 8s holds 480 steps, so a step spans 1/480. Two fractions inside one
        // must be indistinguishable, or derivedStateOf will notify for both.
        val a = quantiseFraction(0.5f, 8_000)
        val b = quantiseFraction(0.5f + 1f / 2_000f, 8_000)
        assertEquals(a, b, 0f)
        assertEquals(lerp(from, to, a), lerp(from, to, b))
    }

    @Test
    fun `a step apart still moves`() {
        // The saving must come from dropping duplicate frames, not from running
        // the fade so coarsely that it stops tracking the audio.
        val a = quantiseFraction(0.5f, 8_000)
        val b = quantiseFraction(0.5f + 1f / 240f, 8_000)
        assertNotEquals(lerp(from, to, a), lerp(from, to, b))
    }

    @Test
    fun `a long fade emits about half the frames it is shown`() {
        val frames = framesFor(8_000)

        // The quantiser's own arithmetic, which is exactly derivable: 8s holds
        // 8000 * 60 / 1000 = 480 steps, so the fractions are 0/480 through
        // 479/480 plus an untouched 1f at the end.
        val fractions = frames.map { quantiseFraction(it, 8_000) }.distinct()
        assertEquals(481, fractions.size)

        // Palettes are counted separately and only bounded, never pinned to a
        // number. A Color packs its channels as half floats, so neighbouring
        // fractions are free to coincide once they are colours rather than
        // arithmetic — which is a saving, not a fault, and not something to
        // hard-code a count against.
        val distinct = distinctPalettes(8_000)
        assertTrue("a palette per fraction at most", distinct <= fractions.size)
        assertTrue(
            "quantising must roughly halve the values a 120 Hz fade emits, " +
                "got $distinct of ${frames.size}",
            distinct < frames.size * 0.6,
        )
    }

    @Test
    fun `a short fade is thinned too, not left alone`() {
        // The gapless default. 600ms at 120 Hz is 73 frames, 36 steps.
        val distinct = distinctPalettes(ColorBlend.GAPLESS_MS)
        assertTrue(
            "a 600ms fade should still shed frames, not pass all 73 through",
            distinct < framesFor(ColorBlend.GAPLESS_MS).size * 0.6,
        )
    }

    @Test
    fun `the fade never steps backwards`() {
        // Colours that were never fully on screen must not be revisited, and a
        // snapped fraction must never overshoot the animation it is tracking.
        var previous = 0f
        for (f in framesFor(8_000)) {
            val q = quantiseFraction(f, 8_000)
            assertTrue("quantised fraction went backwards at $f", q >= previous)
            assertTrue("quantised fraction ran ahead of the animation at $f", q <= f)
            previous = q
        }
    }

    @Test
    fun `a fade too short to hold a step is left alone`() {
        // Animations-off drives blendMillis to 0. There is nothing to remove,
        // and dividing by the step count would be a divide by zero.
        assertEquals(0.37f, quantiseFraction(0.37f, 0), 0f)
        assertEquals(0.37f, quantiseFraction(0.37f, 10), 0f)
    }
}
