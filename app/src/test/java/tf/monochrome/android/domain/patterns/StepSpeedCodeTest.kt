package tf.monochrome.android.domain.patterns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The one-byte speed code, which exists in two places and has to mean the same
 * thing in both.
 *
 * Kotlin writes it into Room and hands it to the JNI upload; C++ reads it on
 * the audio thread in `stepSpeedFromCode`. Nothing checks that those two
 * agree at build time, so the numbers below are the check — they are the same
 * assertions the native suite makes, transcribed. If somebody retunes the
 * curve on one side, one of these fails rather than a parameter lock quietly
 * playing back at the wrong tempo.
 */
class StepSpeedCodeTest {

/**
     * Every octave boundary lands on a whole code.
     *
     * This is the property the encoding was designed around. Unlinked speed
     * does not touch pitch, so a half-time lock that is 0.55% off does not
     * sound flat, it drifts — about 44 ms over four bars at 120 BPM, heard as
     * flam against the loop underneath.
     */
    @Test
    fun `octave boundaries are exact codes`() {
        assertEquals(1, Step.speedToCode(0.25f))
        assertEquals(64, Step.speedToCode(0.5f))
        assertEquals(127, Step.speedToCode(1.0f))
        assertEquals(190, Step.speedToCode(2.0f))
        assertEquals(253, Step.speedToCode(4.0f))

        assertEquals(0.25f, Step.speedFromCode(1), 1e-6f)
        assertEquals(0.5f, Step.speedFromCode(64), 1e-6f)
        assertEquals(1.0f, Step.speedFromCode(127), 1e-6f)
        assertEquals(2.0f, Step.speedFromCode(190), 1e-6f)
        assertEquals(4.0f, Step.speedFromCode(253), 1e-5f)
    }

    /**
     * Unity has to land on a code exactly, not near one. A step locked to
     * "no change" that came back as 0.996x would detune the whole channel by a
     * hair on every trig that carries a lock.
     */
    @Test
    fun `unity round-trips exactly`() {
        assertEquals(1.0f, Step.speedFromCode(Step.speedToCode(1.0f)), 0f)
    }

    /** Code 0 is "no lock", not the bottom of the range. */
    @Test
    fun `zero means follow the channel`() {
        assertEquals(1.0f, Step.speedFromCode(0), 0f)
        assertEquals(1.0f, Step.OFF.speed, 0f)
        assertEquals(0, Step.OFF.speedCode)
    }

    @Test
    fun `musical values round-trip within a percent`() {
        val values = listOf(0.25f, 0.5f, 0.667f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f, 4f)
        for (speed in values) {
            val back = Step.speedFromCode(Step.speedToCode(speed))
            // Half a step of the curve is the worst any value can be off by.
            assertTrue(
                "$speed came back as $back",
                abs(back - speed) <= speed * 0.006f,
            )
        }
    }

    /**
     * Every code has to be a step, not a plateau. A curve with repeats would
     * mean part of the slider's travel does nothing.
     */
    @Test
    fun `the mapping is strictly increasing across its whole range`() {
        var previous = Step.speedFromCode(1)
        for (code in 2..Step.MAX_SPEED_CODE) {
            val value = Step.speedFromCode(code)
            assertTrue("code $code did not increase", value > previous)
            previous = value
        }
    }

    /**
     * The spacing has to be even in ratio terms, which is what makes a drag
     * feel linear. Uneven spacing would make the slider crawl at one end and
     * jump at the other.
     */
    @Test
    fun `every step is the same proportional distance`() {
        val expected = 2.0.pow(1.0 / Step.SPEED_CODES_PER_OCTAVE).toFloat()
        for (code in 2..Step.MAX_SPEED_CODE) {
            val ratio = Step.speedFromCode(code) / Step.speedFromCode(code - 1)
            assertEquals("code $code", expected, ratio, 1e-4f)
        }
        // ~1.1% per code: a fifth of a semitone, under the threshold where a
        // tempo change reads as a staircase.
        assertTrue(expected < 1.012f)
    }

    @Test
    fun `out of range values clamp rather than wrap`() {
        assertEquals(1, Step.speedToCode(0.01f))
        assertEquals(Step.MAX_SPEED_CODE, Step.speedToCode(99f))
        assertEquals(4.0f, Step.speedFromCode(9999), 1e-5f)
        // 254 and 255 exist in the byte but not in the range.
        assertEquals(4.0f, Step.speedFromCode(255), 1e-5f)
        // A negative code cannot arrive from the engine, which sends an
        // unsigned byte. If one ever did it means "no lock", not "slowest".
        assertEquals(1.0f, Step.speedFromCode(-5), 0f)
    }

    // ── the lock bit ────────────────────────────────────────────────────

    @Test
    fun `setting a speed sets the lock bit and clearing it clears both`() {
        val locked = Step.OFF.withSpeed(0.5f)
        assertTrue(locked.locks and Step.LOCK_SPEED != 0)
        assertEquals(0.5f, locked.speed, 0.01f)

        val cleared = locked.withoutSpeed()
        assertFalse(cleared.locks and Step.LOCK_SPEED != 0)
        assertEquals(0, cleared.speedCode)
        assertEquals(1.0f, cleared.speed, 0f)
    }

    /** Clearing speed must not disturb the other locks. */
    @Test
    fun `clearing speed leaves the other locks alone`() {
        val step = Step.OFF
            .copy(locks = Step.LOCK_PITCH or Step.LOCK_PAN)
            .withSpeed(2f)
            .withoutSpeed()
        assertEquals(Step.LOCK_PITCH or Step.LOCK_PAN, step.locks)
    }

    /** The lock bits have to stay distinct powers of two, and match the engine. */
    @Test
    fun `lock bits are distinct`() {
        val bits = listOf(Step.LOCK_PITCH, Step.LOCK_PAN, Step.LOCK_START, Step.LOCK_SPEED)
        assertEquals(listOf(1, 2, 4, 8), bits)
        // Hoisted out of the assert: with the fold inline, overload resolution
        // picks JUnit's (long, long) from the literal before it knows the
        // lambda returns an Int.
        val combined = bits.fold(0) { acc, bit -> acc or bit }
        assertEquals(15, combined)
    }
}
