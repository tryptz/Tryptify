package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.Proven
import io.github.chronosauros.contour.core.ShelfForm
import io.github.chronosauros.contour.core.devices.GeneratedDevices

/**
 * Native Moondrop (report 0x4B, 64 bytes with the ID). Only the Dawn Pro 2 (USB name "DAWN PRO2") is here: the one
 * Moondrop identity read on hardware (DacLab, 21.10.2026). Names and limits are the manufacturer's (MOONDROP Hub, hub.moondroplab.com,
 * read 21.10.2026), not devicePEQ's: 8 bands, types PK, LS, HS, LP, HP (no BP, no AP), gain -18..+12 dB, Q 0.1-10, 20-20000 Hz, one
 * writable PEQ banks = EQ index 7 ("PEQ mode") and 9 (the owner's unit, firmware 1.5, stays on 9 and takes the Hub's write there). Other EQ indices are the unit's own presets: never written.
 *
 * Registers, as the manufacturer names them: 0x0F EQ index, 0x09 band, 0x0A coefficient commit, 0x01 EQ save, 0x03 GLOBAL GAIN
 * (DAC offset, -18..0 dB; advBeta called this "pregain"), 0x23 PRE-GAIN (the preamp, -128..0 dB), 0x04 offset save.
 * Never sent: 0x05 (flash clear, `01 05 01 04`), 0xFF (firmware update entry), 0x04 (offset save: the manufacturer's write does not use it).
 */
object MoondropCatalog {
    // Every number below is the record's (devices/moondrop-dawn-pro-2.json via GeneratedDevices); what stays here is the shape of the model and the lookup.
    private val dawn = GeneratedDevices.MOONDROP_DAWN_PRO_2
    const val VENDOR_ID = GeneratedDevices.MOONDROP_DAWN_PRO_2_VENDOR_ID
    const val REPORT_ID = GeneratedDevices.MOONDROP_DAWN_PRO_2_REPORT_ID
    /** Native type codes: 1 LS, 2 PK, 3 HS, 4 LP, 5 HP (0 = disabled exists in the manufacturer's table; never written). */
    val TYPES: Map<Int, FilterType> = dawn.nativeTypes
    /** The record's offered types: PEAK only; its shelves (LS, HS) and pass filters (LP, HP) are the record's `peq.heldTypes`, offered behind [SHELVES_VERIFIED] and [PASS_FILTERS_VERIFIED]. */
    private val RECORD_OFFERED_TYPES: Set<FilterType> = dawn.offeredTypes

    /**
     * THE verification flag of LOW PASS and HIGH PASS on the Dawn Pro 2 in the app. True since 25.10.2026: the same DacLab run that measured the shelves played both PASS with the frames unchanged
     * (run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k, 48 kHz: LP 8000 Hz Q0.71 max 0.128 dB, HP 100 Hz Q0.71 max 0.081 dB), and the phone wrote and read back the Dawn's 8 bands with the same codec
     * (run 2026-10-25-contour16-r7-phone-nothing, VERIFIED). The codec writes them as the plain RBJ low-pass and high-pass at 96 kHz with gain 0 on the wire ([MoondropCodec.planFor]). While false, a release
     * offered PEAK only and a review build ([Proven.on]) offered them. One line to disable: set this to false.
     */
    const val PASS_FILTERS_VERIFIED = true

    /**
     * THE verification flag of LOW SHELF and HIGH SHELF on the Dawn Pro 2 in the app. True since 25.10.2026: the same run found the shelf law from the registers it read back (LS 100 Hz Q0.71 +5 with HS 8000 Hz Q1 -6:
     * the register plays Q / sqrt2, max 0.044 dB; the plain-Q law misses one shelf case by 1.13 dB), the record's `peq.shelfQScale` = sqrt(2) applies it ([MoondropCodec.bandWrite]: the shelf register is the
     * intended Q times sqrt(2)), `MoondropShelfQTest` pins the register, and the phone transport is the run above. While false, a release offered PEAK (and the pass filters) only. One line to disable: set this to false.
     */
    const val SHELVES_VERIFIED = true

    /** LOW PASS and HIGH PASS are offered on the Dawn Pro 2: proven ([PASS_FILTERS_VERIFIED]) or a review build ([Proven.on]). */
    val passFiltersOn: Boolean get() = Proven.on(PASS_FILTERS_VERIFIED)

    /** LOW SHELF and HIGH SHELF are offered on the Dawn Pro 2: proven ([SHELVES_VERIFIED]) or a review build ([Proven.on]). */
    val shelvesOn: Boolean get() = Proven.on(SHELVES_VERIFIED)

    /** What Contour offers on the Dawn Pro 2 now: PEAK, plus LOW PASS and HIGH PASS where [passFiltersOn], plus LOW SHELF and HIGH SHELF where [shelvesOn]. */
    val OFFERED_TYPES: Set<FilterType> get() = RECORD_OFFERED_TYPES +
        (if (passFiltersOn) setOf(FilterType.LOW_PASS, FilterType.HIGH_PASS) else emptySet()) +
        (if (shelvesOn) setOf(FilterType.LOW_SHELF, FilterType.HIGH_SHELF) else emptySet())
    data class Model(
        val productName: String,
        val productIds: Set<Int>,
        val bandCount: Int,
        val reportId: Int,
        /** The EQ index the manufacturer's tool asks for with the first frame of its write ("PEQ mode"). */
        val writableIndex: Int,
        /** Every EQ index whose bank this code writes; any other index is one of the unit's own presets. */
        val writableIndexes: Set<Int>,
        val minGainDb: Double, val maxGainDb: Double,
        /** The register Q range; the Q a shelf offers is this divided by [shelfQScale] (the register holds intended Q x the scale). */
        val minQ: Double, val maxQ: Double,
        /** The register Q of a shelf over the intended Q (record `peq.shelfQScale`); 1.0 = the register is the Q. Peaks and pass filters play the register as Q. */
        val shelfQScale: Double,
        /** How the DAC builds a shelf (record `peq.shelfForm`): the Dawn Pro 2 plays the registers (Q). */
        val shelfForm: ShelfForm,
        val minFrequencyHz: Double, val maxFrequencyHz: Double,
        /** Pre-gain (0x23) range in dB, and the global gain (0x03) range. */
        val preGainMinDb: Double, val preGainMaxDb: Double,
        val globalGainMinDb: Double, val globalGainMaxDb: Double,
        /** HID report size with the ID, from the descriptor read on 21.10.2026 (Input and Output 64 bytes). */
        val reportBytes: Int,
        /** True once the unit has taken an EQ write from the phone app and read it back (Dawn Pro 2: Nothing Phone 25.10.2026, run 2026-10-25-moondrop-dawn-pro-2-07-phone-nothing, Write Cfg on index 9, VERIFIED twice). Pass filters and pre-gain have their own flags. */
        val hardwareVerified: Boolean = false,
    ) {
        /** The active EQ index [index] is a bank Contour writes. */
        fun isWritable(index: Int): Boolean = index in writableIndexes
        val nativeTypes: Set<Int> get() = TYPES.keys
        val types: Set<FilterType> get() = TYPES.values.toSet()
    }

    val dawnPro2 = Model(dawn.productName, setOf(dawn.productId), dawn.bands, dawn.reportId, dawn.writableIndex, dawn.writableIndexes,
        dawn.gainMinDb, dawn.gainMaxDb, dawn.qMin, dawn.qMax, dawn.shelfQScale, dawn.shelfForm, dawn.freqMinHz, dawn.freqMaxHz,
        dawn.preGainMinDb, dawn.preGainMaxDb, dawn.globalGainMinDb, dawn.globalGainMaxDb, dawn.reportBytes, hardwareVerified = true)
    val models = listOf(dawnPro2)

    /** Exact VID, exact PID and the literal USB product name ("DAWN PRO 2" with a space is another, WalkPlay-routed name): the record's `usb` identity, name match included. */
    fun find(vendorId: Int, productId: Int, productName: String?): Model? =
        if (GeneratedDevices.MOONDROP_DAWN_PRO_2_USB.matches(vendorId, productId, productName)) dawnPro2 else null
}
