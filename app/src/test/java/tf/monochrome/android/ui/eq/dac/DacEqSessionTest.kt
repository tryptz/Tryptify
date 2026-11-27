package tf.monochrome.android.ui.eq.dac

import io.github.chronosauros.contour.core.DeviceTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.audio.usb.daceq.DacLink
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType

/**
 * The DAC EQ page's own logic against Contour's simulated DACs: plug one in,
 * the page reads it, an EQ is loaded and fitted, the held button sends it,
 * and the page says what the DAC answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DacEqSessionTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun stop() = scope.cancel()

    private val tenPeaks = listOf(
        31f to 4.5f, 62f to 3.0f, 125f to -1.5f, 250f to -2.5f, 500f to 1.0f,
        1000f to -1.0f, 2000f to 2.5f, 4000f to -4.0f, 8000f to 3.0f, 12000f to -2.0f,
    ).mapIndexed { i, (f, g) -> EqBand(i, FilterType.PEAKING, f, g, 1.41f) }

    private fun page(dac: SimulatedDac) = DacEqSession(dac, scope)

    private fun DacEqSession.settled(what: (DacEqUiState) -> Boolean = { true }): DacEqUiState = runBlocking {
        withTimeout(30_000) { state.first { it.busy == DacEqBusy.IDLE && what(it) } }
    }

    @Test
    fun `a DAC that is plugged in is read by itself, and nothing is sent`() {
        val dac = SimulatedDac(DeviceTarget.KA15)
        val page = page(dac)
        assertNull(page.state.value.held)
        dac.plugIn()
        val state = page.settled { it.held != null }
        assertNull(state.readError)
        assertEquals(DeviceTarget.KA15.destinationSlot, state.slot)
        assertEquals(0, dac.sends)
    }

    @Test
    fun `a DAC waiting for Android's permission is not read`() {
        val dac = SimulatedDac(DeviceTarget.KA15)
        val page = page(dac)
        dac.plugIn(allowed = false)
        Thread.sleep(300)
        assertNull(page.state.value.held)
        assertEquals(DacEqBusy.IDLE, page.state.value.busy)
        page.requestPermission()
        assertNotNull(page.settled { it.held != null }.held)
    }

    @Test
    fun `an EQ with too many bands is fitted, sent, proven and then on the DAC`() {
        val dac = SimulatedDac(DeviceTarget.MICRO)
        val page = page(dac)
        dac.plugIn()
        page.settled { it.held != null }

        page.load("HD 600", tenPeaks, emptyList(), emptyList())
        val loaded = page.settled { it.fit != null }
        assertTrue(loaded.fit!!.refit)
        assertTrue(loaded.bands.size in 1..DeviceTarget.MICRO.caps.bands)
        assertEquals(emptyList<String>(), loaded.issues)
        assertTrue(loaded.preampAuto)
        // The automatic preamp covers the boost, on the DAC's whole-dB grid.
        assertTrue("${loaded.sendPreampDb} for ${loaded.peakBoostDb}", loaded.sendPreampDb <= -loaded.peakBoostDb + 0.01f)
        assertFalse(loaded.onDac)

        page.send()
        val sent = page.settled { it.outcome != null }
        assertEquals(DacEqOutcome.Verified, sent.outcome)
        assertEquals(1, dac.sends)
        assertTrue(sent.onDac)
        assertNotNull(sent.held)

        // The first edit: no longer what the DAC holds, and the verdict goes.
        page.updateBand(sent.bands.first().copy(gain = sent.bands.first().gain - 1f))
        val edited = page.settled()
        assertFalse(edited.onDac)
        assertNull(edited.outcome)
        assertNull(edited.fit)
    }

    @Test
    fun `a DAC that restarts on save is waited for and proven`() {
        val dac = SimulatedDac(DeviceTarget.ALLEGRO_MINI)
        val page = page(dac)
        dac.plugIn()
        page.settled { it.held != null }
        page.load(null, tenPeaks.take(3), emptyList(), emptyList())
        val loaded = page.settled { it.fit != null }
        assertEquals(emptyList<String>(), loaded.issues)
        // No preamp to write: 0 dB goes out, and the page knows the boost.
        assertEquals(0f, loaded.sendPreampDb)
        assertTrue(loaded.peakBoostDb > 1f)

        val seen = mutableListOf<DacEqBusy>()
        val watching = Thread { runBlocking { withTimeout(30_000) { page.state.first { seen += it.busy; it.outcome != null } } } }.apply { start() }
        page.send()
        assertEquals(DacEqOutcome.Verified, page.settled { it.outcome != null }.outcome)
        watching.join(5_000)
        assertTrue("$seen", DacEqBusy.RESTARTING in seen)
    }

    /** The page forgot the DAC when it left the bus to save, and with it the slot a send goes to. */
    @Test
    fun `a DAC that leaves the bus to save is still known when it is back`() {
        val target = DeviceTarget.FIIO_JA11
        val dac = SimulatedDac(target)
        val page = page(dac)
        dac.plugIn()
        page.settled { it.held != null }
        page.load("JA11", tenPeaks.take(3), emptyList(), emptyList())
        assertEquals(emptyList<String>(), page.settled { it.fit != null }.issues)
        page.send()
        val sent = page.settled { it.outcome != null }
        assertEquals(DacEqOutcome.Verified, sent.outcome)
        assertEquals(target.destinationSlot, sent.slot)
        assertNotNull(sent.held)
        assertTrue(sent.onDac)
        // Back on the bus, it is not taken for a new DAC and read once more.
        Thread.sleep(400)
        assertEquals(DacEqBusy.IDLE, page.state.value.busy)
        assertEquals(DacEqOutcome.Verified, page.state.value.outcome)
    }

    @Test
    fun `a DAC whose preamp is not written still takes an EQ with boosts`() {
        val dac = SimulatedDac(DeviceTarget.EPZ_TP65)
        val page = page(dac)
        dac.plugIn()
        page.settled { it.held != null }
        page.load(null, tenPeaks.take(4), emptyList(), emptyList())
        val loaded = page.settled { it.fit != null }
        assertEquals(emptyList<String>(), loaded.issues)
        assertEquals(0f, loaded.sendPreampDb)
        page.send()
        assertEquals(DacEqOutcome.Verified, page.settled { it.outcome != null }.outcome)
    }

    @Test
    fun `bands stay inside the DAC's limits however they are edited`() {
        val target = DeviceTarget.MICRO
        val dac = SimulatedDac(target)
        val page = page(dac)
        dac.plugIn()
        page.settled { it.held != null }
        page.load(null, tenPeaks.take(2), emptyList(), emptyList())
        page.settled { it.fit != null }

        page.dragBand(0, freq = 5f, gain = 40f)
        var band = page.settled().bands.first { it.id == 0 }
        assertEquals(target.caps.freqMinHz.toFloat(), band.freq)
        assertEquals(target.caps.gainMaxDb.toFloat(), band.gain)

        page.updateBand(band.copy(q = 500f))
        band = page.settled().bands.first { it.id == 0 }
        assertTrue(band.q.toDouble() in target.caps.qRange(io.github.chronosauros.contour.core.FilterType.PEAK))

        repeat(20) { page.addBand() }
        val full = page.settled()
        assertEquals(target.caps.bands, full.bands.size)
        assertEquals(emptyList<String>(), full.issues.filterNot { "needs" in it || "reamp" in it })
    }

    @Test
    fun `what was read is forgotten when the DAC is pulled out`() {
        val dac = SimulatedDac(DeviceTarget.KA15)
        val page = page(dac)
        dac.plugIn()
        page.settled { it.held != null }
        dac.unplug()
        val gone = page.settled { it.held == null }
        assertNull(gone.slot)
        assertTrue(page.link.value is DacLink.None)
        // The EQ being edited is the listener's, not the DAC's: it stays.
        page.send()
        assertEquals(0, dac.sends)
    }
}
