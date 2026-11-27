package io.github.chronosauros.contour.core

import io.github.chronosauros.contour.core.devices.GeneratedDevices
import io.github.chronosauros.contour.core.native.FiioCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The stable app recognises exactly seven USB identities; every other one is plain unrecognised (NO DAC). */
class DeviceRecognitionTest {
    private val k13 = 0x2972 to FiioCatalog.K13_R2R_PRODUCT_ID
    private val melody = 0x2972 to FiioCatalog.MELODY_PRODUCT_ID
    private val tinyA = 0x2972 to FiioCatalog.TINY_A_PRODUCT_ID
    private val expected = mapOf(
        (0x3302 to 0xC20F) to DeviceTarget.MICRO, // CrinEar Protocol Micro
        (0x3302 to 0x43CC) to DeviceTarget.MAX,   // CrinEar Protocol Max
        (0x3302 to 0x43E8) to DeviceTarget.TRN,   // TRN Black Pearl (own write sequence, factory-flat spares)
        (0x2972 to 0x0104) to DeviceTarget.KA15,  // FiiO KA15
        k13 to DeviceTarget.K13,                  // FiiO K13 R2R (product ID: FiioCatalog.K13_R2R_PRODUCT_ID, measured on hardware)
        melody to DeviceTarget.MELODY,            // SnowSky Melody (measured on hardware)
        tinyA to DeviceTarget.TINY_A,             // SnowSky Tiny A (measured on hardware)
    )

    @Test fun `stable build recognises exactly Protocol Micro Protocol Max TRN Black Pearl FiiO KA15 FiiO K13 R2R SnowSky Melody and Tiny A`() {
        expected.forEach { (id, target) -> assertEquals(target, DeviceTarget.find(id.first, id.second)) }
        // Every PID of both vendors, both byte orders of the three IDs, and the advBeta-only identities.
        val vendors = listOf(0x3302, 0x2972, 0x0A12, 0x0BDA, 0x31B2, 0x152A, 0x35D8, 0x262A, 0x0000, 0xFFFF)
        val found = HashSet<Pair<Int, Int>>()
        for (vid in vendors) for (pid in 0..0xFFFF) if (DeviceTarget.find(vid, pid) != null) found += vid to pid
        for (pid in listOf(0xC20F, 0x43CC, 0x43E8, 0x0104, 0x0F2C, 0xCC43, 0x0401, k13.second, melody.second, tinyA.second)) for (vid in 0..0xFFFF)
            if (DeviceTarget.find(vid, pid) != null) found += vid to pid
        assertEquals(expected.keys, found)
        // Near misses: the PID next to the TRN Black Pearl, other FiiO captures, KT Micro, Fosi DS3, Qudelix, swapped bytes.
        for ((vid, pid) in listOf(0x3302 to 0x43E9, 0x2972 to 0x0128, 0x2972 to 0x0105, 0x2972 to 0x0127,
                0x31B2 to 0x0111, 0x152A to 0x88DB, 0x0A12 to 0x4005, 0x0233 to 0x0F2C, 0x7229 to 0x0401).filter { it != k13 && it != melody && it != tinyA })
            assertNull(DeviceTarget.find(vid, pid), "%04X:%04X".format(vid, pid))
        // Structure behind "exactly": three WalkPlay entries and four native FiiO rules.
        assertEquals(listOf(DeviceProtocol.MICRO, DeviceProtocol.MAX, DeviceProtocol.TRN, DeviceProtocol.SPACE_2, DeviceProtocol.EPZ_TP65), DeviceProtocol.entries.toList()) // the Space II and the TP65 are found by findDevice only
        assertEquals(listOf("FIIO KA15", "FIIO K13 R2R", "SNOWSKY Melody", "SNOWSKY TINY A", "JadeAudio JA11", "RETRO NANO", "JadeAudio JIEZI"), FiioCatalog.rules.map { it.productName })
        assertEquals(10610 to 260, FiioCatalog.KA15.capture!!.let { it.vendorId to it.productId })
        assertNull(FiioCatalog.JA11.capture); assertNull(DeviceTarget.find(0x2972, 0x0102)) // the JA11 is found by findDevice only: the 1.5 list stays
        assertNull(FiioCatalog.RETRO_NANO.capture); assertNull(DeviceTarget.find(0x0A12, 0x4007)) // the RETRO NANO (a generic Bluetooth-chip VID) and the JIEZI: findDevice only
        assertNull(FiioCatalog.JIEZI.capture); assertNull(DeviceTarget.find(0x2972, 0x0151))
        assertNull(FiioCatalog.K13.capture) // no capture: its identity is the product ID constant
        assertNull(FiioCatalog.MELODY.capture); assertNull(FiioCatalog.TINY_A.capture)
    }

    /**
     * The USB attach prompt (the generated device_filter.xml) lists the seven 1.5 identities in their old order, then the three 1.6 ones, in decimal;
     * the list is the same as `GeneratedDevices.USB_IDS`, and every one of those identities is a DeviceTarget.
     */
    @Test fun `device filter lists exactly the recognised identities`() {
        val xml = java.io.File(requireNotNull(System.getProperty("contour.usbFilterXml"))).readText()
        val listed = Regex("""<usb-device\s+vendor-id="(\d+)"\s+product-id="(\d+)"""").findAll(xml)
            .map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        val added = listOf(0x35D8 to 0x011D, 0x31B2 to 0x0111, 0x3302 to 0x12C5, 0x2972 to 0x0102, 0x35D8 to 0x1499, 0x0A12 to 0x4007, 0x2972 to 0x0151, 0x3302 to 0x20E3, 0x3302 to 0x4381, 0x3302 to 0x4325) // Dawn Pro 2, Allegro Mini, Echo-A, FiiO JA11, Moondrop DUSK-SP, SnowSky RETRO NANO, FiiO JIEZI, Kiwi Ears AD1, Tanchjim Space II, EPZ TP65 (experimental tester entry)
        assertEquals(expected.keys.toList() + added, listed)
        assertEquals(GeneratedDevices.USB_IDS.map { it.vendorId to it.productId }.distinct(), listed) // one line per VID:PID: the JM12 shares the Allegro Mini's
        for ((vid, pid) in added) assertNull(DeviceTarget.find(vid, pid), "%04X:%04X is not in the 1.5 list".format(vid, pid))
    }

    /** `findDevice` knows all eighteen records; the Dawn Pro 2, the Allegro Mini, the JM12 (the last two share 31B2:0111), the JA11, the DUSK-SP, the RETRO NANO, the JIEZI, the AD1, the Space II and the TP65 need their exact USB product name, the Echo-A does not. */
    @Test fun `findDevice recognises the eighteen records, the JM12 and the Allegro Mini told apart by the USB name`() {
        expected.forEach { (id, target) -> assertEquals(target, DeviceTarget.findDevice(id.first, id.second, null)) }
        assertEquals(DeviceTarget.DAWN_PRO_2, DeviceTarget.findDevice(0x35D8, 0x011D, GeneratedDevices.MOONDROP_DAWN_PRO_2.productName))
        assertEquals(DeviceTarget.ALLEGRO_MINI, DeviceTarget.findDevice(0x31B2, 0x0111, GeneratedDevices.KIWI_EARS_ALLEGRO_MINI.productName))
        assertEquals(DeviceTarget.ECHO_A, DeviceTarget.findDevice(0x3302, 0x12C5, null))
        assertNull(DeviceTarget.findDevice(0x35D8, 0x011D, null))
        assertNull(DeviceTarget.findDevice(0x31B2, 0x0111, null))
        assertEquals(DeviceTarget.JCALLY_JM12, DeviceTarget.findDevice(0x31B2, 0x0111, "KT02H20 HIFI Audio"))
        assertEquals(DeviceTarget.ALLEGRO_MINI, DeviceTarget.findDevice(0x31B2, 0x0111, "Kiwi Ears-Allegro Mini"))
        for (other in listOf("KT02H20", "kt02h20 hifi audio", "Kiwi Ears-Allegro PRO", "")) assertNull(DeviceTarget.findDevice(0x31B2, 0x0111, other), other)
        assertNull(DeviceTarget.findDevice(0x31B2, 0x0112, "KT02H20 HIFI Audio")); assertNull(DeviceTarget.find(0x31B2, 0x0111))
        // Every identity of the records resolves to its own target, and no two records resolve to one target.
        val targets = GeneratedDevices.USB_IDS.map { u -> DeviceTarget.findDevice(u.vendorId, u.productId, u.productName) ?: error("${u.recordId} is not recognised") }
        assertEquals(18, targets.toSet().size)
        assertEquals(DeviceTarget.MOONDROP_DUSK, DeviceTarget.findDevice(0x35D8, 0x1499, "DUSK-SP")); assertNull(DeviceTarget.findDevice(0x35D8, 0x1499, null))
        assertEquals(DeviceTarget.RETRO_NANO, DeviceTarget.findDevice(0x0A12, 0x4007, "RETRO NANO")); assertNull(DeviceTarget.findDevice(0x0A12, 0x4007, null)); assertNull(DeviceTarget.findDevice(0x0A12, 0x4007, "Retro Nano"))
        assertEquals(DeviceTarget.KIWI_EARS_AD1, DeviceTarget.findDevice(0x3302, 0x20E3, "AD1")); assertNull(DeviceTarget.findDevice(0x3302, 0x20E3, null)); assertNull(DeviceTarget.find(0x3302, 0x20E3))
        assertEquals(DeviceTarget.SPACE_2, DeviceTarget.findDevice(0x3302, 0x4381, "TANCHJIM-SPACE II")); assertNull(DeviceTarget.findDevice(0x3302, 0x4381, null)); assertNull(DeviceTarget.find(0x3302, 0x4381))
        // The TP65 (experimental tester entry): its own PID and its exact USB name; another name on its PID, and its name on the Space II's PID, match nothing
        assertEquals(DeviceTarget.EPZ_TP65, DeviceTarget.findDevice(0x3302, 0x4325, "TP65")); assertNull(DeviceTarget.findDevice(0x3302, 0x4325, null)); assertNull(DeviceTarget.find(0x3302, 0x4325))
        assertNull(DeviceTarget.findDevice(0x3302, 0x4325, "tp65")); assertNull(DeviceTarget.findDevice(0x3302, 0x4325, "TANCHJIM-SPACE II")); assertNull(DeviceTarget.findDevice(0x3302, 0x4381, "TP65"))
        assertEquals(DeviceTarget.FIIO_JIEZI, DeviceTarget.findDevice(0x2972, 0x0151, "JadeAudio JIEZI")); assertNull(DeviceTarget.findDevice(0x2972, 0x0151, null)); assertNull(DeviceTarget.findDevice(0x2972, 0x0151, "JadeAudio JA11"))
    }

    /** The TP65 tester entry since 1.8.1-tp65test3: written in every build (its own sequence, WalkPlaySessionTest); its preamp read is `80 03 01` (the form read on a unit), every other WalkPlay DAC keeps `80 03 00`. */
    @Test fun `the TP65 writes in a release and asks for its preamp with 80 03 01`() {
        Proven.asChannel(BuildChannel.RELEASE) { assertFalse(DeviceTarget.EPZ_TP65.readOnly); assertFalse(DeviceTarget.SPACE_2.readOnly); assertFalse(DeviceTarget.MAX.readOnly) }
        Proven.asChannel(BuildChannel.PERF) { assertFalse(DeviceTarget.EPZ_TP65.readOnly) }
        fun head(b: ByteArray) = b.copyOf(5).joinToString(" ") { "%02x".format(it) }
        assertEquals("4b 80 03 01 00", head(DeviceProtocol.EPZ_TP65.preampRequest()))
        for (p in DeviceProtocol.entries.filter { it != DeviceProtocol.EPZ_TP65 }) assertEquals("4b 80 03 00 00", head(p.preampRequest()), p.name)
        assertEquals(WalkPlay.REPORT_SIZE, DeviceProtocol.EPZ_TP65.preampRequest().size)
        // the reply its owner read on Windows: 4B 80 03 02 00 00, zero padded to 64 bytes -> offset 0 dB
        val reply = ByteArray(WalkPlay.REPORT_SIZE).also { r -> intArrayOf(0x4B, 0x80, 0x03, 0x02, 0x00, 0x00).forEachIndexed { i, v -> r[i] = v.toByte() } }
        assertTrue(DeviceProtocol.EPZ_TP65.isReply(reply, WalkPlay.CMD_GLOBAL_GAIN))
        assertEquals(0, DeviceProtocol.EPZ_TP65.parsePreamp(reply))
    }

    @Test fun `every record's target is kept and found again by its record id, one target per record`() {
        val ids = GeneratedDevices.USB_IDS.map { it.recordId }.distinct()
        val targets = ids.map { id -> checkNotNull(DeviceTarget.ofRecord(id)) { "no target for $id" } }
        assertEquals(ids.size, targets.toSet().size)
        for ((id, t) in ids.zip(targets)) assertEquals(id, DeviceTarget.recordIdOf(t))
        // the targets the app finds on the bus are the ones kept
        for (t in expected.values + DeviceTarget.FAMILIES + listOf(DeviceTarget.SPACE_2, DeviceTarget.KIWI_EARS_AD1, DeviceTarget.FIIO_JIEZI, DeviceTarget.RETRO_NANO, DeviceTarget.FIIO_JA11))
            assertEquals(t, DeviceTarget.ofRecord(checkNotNull(DeviceTarget.recordIdOf(t)) { "no record id for ${t.caps.name}" }))
        assertNull(DeviceTarget.ofRecord("no-such-dac"))
    }
}
