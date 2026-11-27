package tf.monochrome.android.audio.usb.daceq

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.test.core.app.ApplicationProvider
import io.github.chronosauros.contour.core.DacImport
import io.github.chronosauros.contour.core.DeviceTarget
import io.github.chronosauros.contour.core.native.NativePort
import io.github.chronosauros.contour.usb.DacClient
import io.github.chronosauros.contour.usb.DacHandle
import io.github.chronosauros.contour.usb.sim.SimDusk
import io.github.chronosauros.contour.usb.sim.SimFiio
import io.github.chronosauros.contour.usb.sim.SimIo
import io.github.chronosauros.contour.usb.sim.SimKtMicro
import io.github.chronosauros.contour.usb.sim.SimMoondrop
import io.github.chronosauros.contour.usb.sim.SimWalkPlay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tf.monochrome.android.audio.eq.AutoEqEngine
import tf.monochrome.android.audio.eq.dac.DacEqCatalog
import tf.monochrome.android.audio.eq.dac.DacEqFit
import tf.monochrome.android.audio.eq.dac.DacEqMapping
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.FilterType
import kotlin.math.abs
import kotlin.math.pow

/**
 * An EQ of the app's, all the way to a DAC and back, with no DAC: Contour's
 * simulated DACs (third_party/contour, the ones its review builds use) stand
 * where the USB endpoint would be, and everything above that is what a real
 * send runs: the fit, the mapping, Contour's client, its sessions and codecs,
 * the read-back and the compare, and the read that brings the EQ back in.
 *
 * The simulators answer frames in the order the protocol says. They are not
 * the firmware: this proves the page's own code against Contour's, not a DAC.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DacEqSimulatedSendTest {

    private val client = DacClient(
        ApplicationProvider.getApplicationContext<Context>().getSystemService(UsbManager::class.java),
    )

    private fun simulated(target: DeviceTarget): DacHandle {
        val dac: NativePort = when {
            target.fiio != null -> SimFiio(target)
            target.moondrop != null -> SimMoondrop()
            target.ktMicro != null -> SimKtMicro()
            target.gen1 != null -> SimDusk()
            else -> SimWalkPlay(requireNotNull(target.walkplayProfile))
        }
        return DacHandle(null, target, "simulated:${target.caps.name}", simulated = { guard -> SimIo(target, dac, guard) })
    }

    /** A shelf at each end and peaks between: every kind of band the app has. */
    private val eq = listOf(
        EqBand(0, FilterType.LOWSHELF, 105f, 4f, 0.7071f),
        EqBand(1, FilterType.PEAKING, 220f, -2.5f, 1.2f),
        EqBand(2, FilterType.PEAKING, 1500f, 1.5f, 2f),
        EqBand(3, FilterType.PEAKING, 3200f, -3f, 3f),
        EqBand(4, FilterType.HIGHSHELF, 9000f, -2f, 0.7071f),
    )

    private val freqs = FloatArray(120) { (20.0 * 1000.0.pow(it / 119.0)).toFloat() }
    private val grid = AutoEqEngine.ResponseGrid(freqs, DacEqMapping.DISPLAY_SAMPLE_RATE)
    private fun distance(a: List<EqBand>, b: List<EqBand>, bHigher: Float): Float {
        val ca = grid.sum(a.filter { it.enabled })
        val cb = grid.sum(b.filter { it.enabled })
        return freqs.indices.maxOf { abs(cb[it] + bHigher - ca[it]) }
    }

    @Test
    fun `every DAC takes the EQ, proves it and gives it back`() = runBlocking {
        for (target in DacEqCatalog.all) {
            val name = target.caps.name
            val dac = simulated(target)
            val sent = DacEqFit.fit(eq, target).bands
            val writesPreamp = DacEqMapping.writesPreamp(target)
            val profile = DacEqMapping.profile("Test", sent, if (writesPreamp) null else 0.0, target, now = 1L)
            assertEquals("$name refuses the fitted EQ", emptyList<String>(), target.issues(profile))

            client.readDevice(dac) // a send is always after a read on the page
            val written = client.writeProfile(dac, profile, targetSlot = target.destinationSlot)
            val pending = written.pending
            val back = if (pending == null) {
                assertTrue("$name: not verified: ${written.mismatches}", written.verified)
                requireNotNull(written.readBack)
            } else {
                // A DAC that restarts on save: proven by the first read after.
                pending.afterReturn?.invoke(dac)
                client.readDevice(dac).also { assertEquals("$name after its save", emptyList<String>(), pending.verify(it)) }
            }
            assertTrue("$name: the read-back is not the profile", back.matches(profile))

            val held = back.importExact()
            assertTrue("$name: cannot be read back in: $held", held is DacImport.Ready)
            val read = DacEqMapping.fromDac((held as DacImport.Ready).eq, target)
            assertEquals("$name", 0, read.passFilters)
            // What the DAC holds is the EQ on its own grid (a whole hertz, a
            // tenth or a 256th of a dB), so the curves agree to about a
            // tenth of a dB. The level is compared with the preamp in:
            // a DAC with no high shelf of its own holds one as a low shelf
            // the other way up, and the difference in its preamp.
            val form = target.caps.shelfForm
            val levelSent = if (writesPreamp) profile.effectivePreampDb(form).toFloat() else 0f
            val levelRead = if (writesPreamp) read.preampDb ?: 0f else 0f
            val off = distance(sent, read.bands, levelRead - levelSent)
            // The DUSK-SP's record of a band keeps its gain in whole dB.
            val grain = if (target.gen1 != null) 0.6f else 0.15f
            assertTrue("$name: read back $off dB from what was sent", off < grain)
            if (!target.emulatesHighShelf) assertEquals("$name preamp", levelSent, levelRead, 0.051f)
        }
    }
}
