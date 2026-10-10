package tf.monochrome.android.audio.eq

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tf.monochrome.android.audio.pipeline.OutputKind

/**
 * Per-device AutoEQ, Poweramp-style: a preset assigned to a kind of output,
 * or to one device by name, which comes first.
 */
class OutputEqTest {

    private val bathys = OutputId(OutputSlot.BLUETOOTH, "Focal Bathys")
    private val buds = OutputId(OutputSlot.BLUETOOTH, "Galaxy Buds Pro 3")
    private val bluetooth = OutputId(OutputSlot.BLUETOOTH)
    private val speaker = OutputId(OutputSlot.SPEAKER)

    @Test
    fun `a named device's preset comes before its kind's`() {
        val assignments = mapOf(bluetooth.key to "harman", bathys.key to "bathys-filters")
        assertEquals("bathys-filters", OutputEq.resolve(bathys, assignments))
        // Any other Bluetooth device falls back to the kind's.
        assertEquals("harman", OutputEq.resolve(buds, assignments))
    }

    @Test
    fun `an output nobody assigned leaves the EQ alone`() {
        val assignments = mapOf(bathys.key to "bathys-filters")
        assertNull(OutputEq.resolve(speaker, assignments))
        assertNull(OutputEq.resolve(buds, assignments))
    }

    @Test
    fun `the speaker can be given no correction while headphones keep theirs`() {
        val assignments = mapOf(speaker.key to OutputEq.EQ_OFF, bluetooth.key to "harman", bathys.key to OutputEq.EQ_OFF)
        assertEquals(OutputEq.EQ_OFF, OutputEq.resolve(speaker, assignments))
        assertEquals("harman", OutputEq.resolve(buds, assignments))
        // Off is an assignment like any other, so a device's beats its kind's.
        assertEquals(OutputEq.EQ_OFF, OutputEq.resolve(bathys, assignments))
    }

    @Test
    fun `devices are named only where the name tells them apart`() {
        assertEquals(bathys, OutputEq.idFor(OutputKind.BLUETOOTH_CLASSIC, " Focal Bathys "))
        assertEquals(OutputId(OutputSlot.BLUETOOTH, "Buds"), OutputEq.idFor(OutputKind.BLUETOOTH_LE, "Buds"))
        assertEquals(OutputId(OutputSlot.USB, "FiiO K11"), OutputEq.idFor(OutputKind.USB, "FiiO K11"))
        // The speaker and wired headphones report the phone's name, or nothing.
        assertEquals(speaker, OutputEq.idFor(OutputKind.SPEAKER, "Pixel 9"))
        assertEquals(OutputId(OutputSlot.WIRED), OutputEq.idFor(OutputKind.WIRED, "Pixel 9"))
        // A blank name is no name.
        assertEquals(bluetooth, OutputEq.idFor(OutputKind.BLUETOOTH_CLASSIC, "  "))
        // No slot: a TV, a call's hands-free link.
        assertNull(OutputEq.idFor(OutputKind.HDMI, "TV"))
        assertNull(OutputEq.idFor(OutputKind.BLUETOOTH_SCO, "Car"))
    }

    @Test
    fun `OK assigns exactly the ticked outputs and takes each from its old preset`() {
        val before = mapOf(
            speaker.key to "flat",
            bathys.key to "old",
            buds.key to "bathys-filters",
        )
        val after = OutputEq.assign(before, "bathys-filters", setOf(bathys, bluetooth))
        assertEquals(
            mapOf(
                speaker.key to "flat", // untouched
                bathys.key to "bathys-filters", // taken from "old"
                bluetooth.key to "bathys-filters",
                // buds was unticked, so it no longer plays this preset
            ),
            after,
        )
        // Unassign is OK with nothing ticked.
        assertEquals(mapOf(speaker.key to "flat", bathys.key to "old"), OutputEq.assign(before, "bathys-filters", emptySet()))
    }

    @Test
    fun `a deleted preset leaves no output pointing at it`() {
        val before = mapOf(speaker.key to "flat", bathys.key to "gone", bluetooth.key to "gone")
        assertEquals(mapOf(speaker.key to "flat"), OutputEq.forget(before, "gone"))
    }

    @Test
    fun `keys read back as the outputs they were written for`() {
        val odd = OutputId(OutputSlot.USB, "DAC: rev 2: black")
        for (id in listOf(speaker, bluetooth, bathys, odd)) {
            assertEquals(id, OutputId.fromKey(id.key))
        }
        assertNull(OutputId.fromKey("slot:CHROMECAST"))
        assertNull(OutputId.fromKey("device:USB:"))
        assertNull(OutputId.fromKey("garbage"))
    }

    @Test
    fun `remembered devices are most recent first, named only, and bounded`() {
        var known = emptyList<OutputId>()
        known = OutputEq.remember(known, bathys)
        known = OutputEq.remember(known, buds)
        known = OutputEq.remember(known, speaker) // a kind, not a device
        known = OutputEq.remember(known, bathys)
        assertEquals(listOf(bathys, buds), known)

        val many = (1..30).fold(emptyList<OutputId>()) { acc, i ->
            OutputEq.remember(acc, OutputId(OutputSlot.USB, "DAC $i"))
        }
        assertEquals(OutputEq.MAX_KNOWN_DEVICES, many.size)
        assertEquals("DAC 30", many.first().name)
    }

    @Test
    fun `stored outputs survive the round trip through JSON`() {
        val json = Json { ignoreUnknownKeys = true }
        val list = listOf(bathys, OutputId(OutputSlot.USB, "FiiO K11"))
        assertEquals(list, json.decodeFromString<List<OutputId>>(json.encodeToString(list)))
    }
}
