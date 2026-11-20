package tf.monochrome.android.audio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The note on disk while the USB DAC is being taken, so that a restart of
 * the whole phone in that moment is known at the next start (a phone
 * restarted on 2026-11-20 and left no log).
 */
class DacClaimNoteTest {

    @get:Rule val dir = TemporaryFolder()

    private val bootA = DacClaimNote.Boot(count = 41, bootedAtMs = 1_700_000_000_000L)
    private val bootB = DacClaimNote.Boot(count = 42, bootedAtMs = 1_700_000_900_000L)
    private val claimAt = 1_700_000_500_000L

    private fun note() = DacClaimNote(dir.root.resolve("dac_claim_note"))

    @Test
    fun `a note left by the last boot says the phone restarted`() {
        note().write(bootA, claimAt, "96000 Hz, 24-bit, 2ch, DAC Focal Bathys; Android busy: no")
        val report = note().atStart(bootB)!!
        assertTrue(report.phoneRestarted)
        assertEquals(
            "The phone restarted within 15 s of the app taking the USB DAC " +
                "(claim at 2023-11-14 22:21:40 UTC: 96000 Hz, 24-bit, 2ch, DAC Focal Bathys; Android busy: no)",
            report.text,
        )
    }

    @Test
    fun `a note left in this boot is the app's own process ending`() {
        note().write(bootA, claimAt, "48000 Hz")
        val report = note().atStart(bootA)!!
        assertFalse(report.phoneRestarted)
        assertTrue(report.text.startsWith("The app's process ended within 15 s of taking the USB DAC"))
    }

    @Test
    fun `it is said once`() {
        note().write(bootA, claimAt, "48000 Hz")
        note().atStart(bootB)
        assertNull(note().atStart(bootB))
    }

    @Test
    fun `a claim gone by without trouble leaves nothing`() {
        val n = note()
        n.write(bootA, claimAt, "48000 Hz")
        n.clear()
        assertNull(n.atStart(bootB))
    }

    @Test
    fun `nothing written is nothing to say`() {
        assertNull(note().atStart(bootB))
    }

    @Test
    fun `without a boot count the boots are told apart by when they began`() {
        val then = DacClaimNote.Boot(-1, 1_700_000_000_000L)
        assertFalse(DacClaimNote.restarted(then, DacClaimNote.Boot(-1, 1_700_000_000_000L + 20_000L)))
        assertTrue(DacClaimNote.restarted(then, DacClaimNote.Boot(-1, 1_700_000_000_000L + 600_000L)))
        // A count on one side only is no count.
        assertTrue(DacClaimNote.restarted(then, DacClaimNote.Boot(7, 1_700_000_000_000L + 600_000L)))
    }

    @Test
    fun `a line break in the detail cannot forge a field`() {
        val text = DacClaimNote.encode(bootA, claimAt, "x\nboot=42")
        assertTrue(DacClaimNote.report(text, bootA)!!.text.endsWith("x boot=42)"))
        assertFalse(DacClaimNote.report(text, bootA)!!.phoneRestarted)
    }

    @Test
    fun `a broken note is ignored`() {
        assertNull(DacClaimNote.report("garbage", bootB))
        assertNull(DacClaimNote.report("boot=x\nbootedAt=1\nat=2\n", bootB))
    }
}
