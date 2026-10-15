package tf.monochrome.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Window state borrowed by screens that overlap in a navigation. The incoming
 * screen is composed before the outgoing one is disposed, so the loans are
 * taken and given back out of order, and the app must end where it began.
 */
class WindowLoanTest {

    private val appRule = -1 // SCREEN_ORIENTATION_UNSPECIFIED: the app's own rule
    private val sensor = 4 // SCREEN_ORIENTATION_SENSOR: the galaxy turns with the phone

    /** One window's orientation, written the way the composables write it. */
    private inner class Window {
        var orientation = appRule
        val loan = WindowLoan<Int>()

        fun open(screen: Any) {
            loan.borrow(screen) { orientation }
            orientation = sensor
        }

        fun close(screen: Any) {
            loan.giveBack(screen)?.let { orientation = it }
        }
    }

    @Test
    fun `switching galaxies keeps the map turning, and leaving gives the app its rule back`() {
        val window = Window()
        val genre = Any()
        val library = Any()

        window.open(genre)
        // The switch: the library stage arrives before the genre stage leaves.
        window.open(library)
        window.close(genre)
        assertEquals("the library map still turns with the phone", sensor, window.orientation)

        window.close(library)
        assertEquals("the app is back on its own rule, not left turning", appRule, window.orientation)
    }

    @Test
    fun `switching back and forth ends where it began`() {
        val window = Window()
        var shown = Any()
        window.open(shown)
        repeat(5) {
            val next = Any()
            window.open(next)
            window.close(shown)
            shown = next
            assertEquals(sensor, window.orientation)
        }
        window.close(shown)
        assertEquals(appRule, window.orientation)
    }

    @Test
    fun `one screen alone puts back what was there`() {
        val window = Window()
        val genre = Any()
        window.open(genre)
        assertEquals(sensor, window.orientation)
        window.close(genre)
        assertEquals(appRule, window.orientation)
    }

    @Test
    fun `a screen that was taken over gives back nothing`() {
        val loan = WindowLoan<Int>()
        val first = Any()
        val second = Any()
        loan.borrow(first) { appRule }
        loan.borrow(second) { error("read only while nobody holds it") }
        assertNull(loan.giveBack(first))
        assertEquals(appRule, loan.giveBack(second))
        // Given back once; a second give-back is a no-op.
        assertNull(loan.giveBack(second))
    }

    @Test
    fun `the old save and restore left the whole app turning`() {
        // What GalaxyStage did before: each stage saved the value it found and
        // put it back on the way out.
        var orientation = appRule
        val genreSaved = orientation
        orientation = sensor
        val librarySaved = orientation // the genre map's value, not the app's
        orientation = sensor
        orientation = genreSaved // the genre stage leaves, under the library
        orientation = librarySaved // the library stage leaves
        assertEquals(sensor, orientation)
    }
}
