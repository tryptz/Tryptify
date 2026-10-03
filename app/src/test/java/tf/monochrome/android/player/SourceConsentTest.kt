package tf.monochrome.android.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A TIDAL pick plays from Qobuz only after the listener says yes. */
class SourceConsentTest {

    private val offer = QobuzOffer(tidalId = 42L, title = "Song", artist = "Artist")

    @Test
    fun `nothing is allowed until asked and answered`() {
        val consent = SourceConsent()
        assertFalse(consent.isAllowed(42L))
        consent.post(offer)
        assertEquals(offer, consent.offer.value)
        assertFalse(consent.isAllowed(42L))
    }

    @Test
    fun `yes allows that track and closes the question`() {
        val consent = SourceConsent()
        consent.post(offer)
        consent.allow(42L)
        assertTrue(consent.isAllowed(42L))
        assertFalse(consent.isAllowed(43L))
        assertNull(consent.offer.value)
    }

    @Test
    fun `an allowed track is not asked about again`() {
        val consent = SourceConsent()
        consent.allow(42L)
        consent.post(offer)
        assertNull(consent.offer.value)
    }

    @Test
    fun `no leaves it skipped`() {
        val consent = SourceConsent()
        consent.post(offer)
        consent.dismiss()
        assertNull(consent.offer.value)
        assertFalse(consent.isAllowed(42L))
    }
}
