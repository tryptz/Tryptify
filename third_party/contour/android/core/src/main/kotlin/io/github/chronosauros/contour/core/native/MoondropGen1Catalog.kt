package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.Band
import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.devices.GeneratedDevices
import io.github.chronosauros.contour.core.devices.MoondropGen1Data

/**
 * Moondrop gen-1 cables (report 0x01 over the control pipe): the DUSK-SP is the one model, from its record (`devices/moondrop-dusk.json`, via [GeneratedDevices]). The protocol is the one
 * FreeDSP-Studio calls gen 1 (tools/app/src/dsp.rs, MIT, tested by its author on a DUSK-SP) and devicePEQ "Conexant"; the Moondrop FreeDSP, MAY and ECHO-B speak it too and are not
 * in Contour. NOT the Dawn Pro 2's 0x4B protocol ([MoondropCatalog]).
 *
 * Nine bands, each a meta record (type 0 PK / 1 low shelf / 2 high shelf, integer Hz, Q x 256, whole-dB gain) and five coefficient sets (44.1, 48, 96, 192, 384 kHz); the sound comes from the
 * coefficients, the meta record is a label the tools read back. Contour writes the Custom EQ (index 0) only; 1-5 are firmware presets.
 */
object MoondropGen1Catalog {
    private val dusk = GeneratedDevices.MOONDROP_DUSK
    const val VENDOR_ID = GeneratedDevices.MOONDROP_DUSK_VENDOR_ID
    const val REPORT_ID = 1
    /** The report with its ID, Input and Output (the HID report descriptor read on the unit 11.11.2026). */
    const val REPORT_BYTES = 62

    /** One gen-1 model. The four constructor properties are what the golden dumps; the rest of the record is reached through [data]. */
    data class Model(
        val productName: String,
        val productIds: Set<Int>,
        val reportBytes: Int,
        /** True once a cable has taken an EQ write from the phone app and read it back (`driver.hardwareVerified` of the record): until then the app only reads it in a release. */
        val hardwareVerified: Boolean,
    ) {
        internal val data: MoondropGen1Data get() = dusk
        val name: String get() = data.name
        val bandCount: Int get() = data.bands
        val minGainDb: Double get() = data.gainMinDb
        val maxGainDb: Double get() = data.gainMaxDb
        val minQ: Double get() = data.qMin
        val maxQ: Double get() = data.qMax
        val minFrequencyHz: Double get() = data.freqMinHz
        val maxFrequencyHz: Double get() = data.freqMaxHz
        val types: Set<FilterType> get() = data.types
        /** The firmware presets and the Custom EQ by index; the Custom EQ ([customIndex]) is the one bank Contour writes. */
        val presetLabels: Map<Int, String> get() = data.presetLabels
        val customIndex: Int get() = data.customIndex
        val stockPicker: Boolean get() = data.stockPicker
        /** The DAC page lists the presets as buttons on the page itself, not behind a dropdown (record `slots.stockPickerInline`). */
        val stockPickerInline: Boolean get() = data.stockPickerInline
        /** The factory bank by band position: what a band Contour does not use is written as (PEAK, 0 dB, [factoryQ]). */
        val factoryFrequenciesHz: List<Int> get() = data.factoryFrequenciesHz
        val factoryQ: Double get() = data.factoryQ
        val replyWaitMs: Long get() = data.replyWaitMs
        val writeGapMs: Long get() = data.writeGapMs
        val afterCommitMs: Long get() = data.afterCommitMs
        val afterSelectMs: Long get() = data.afterSelectMs
        /** The commit restarts the cable: it leaves the bus about this long after the commit's reply and is back after [restartAwayMinMs]..[restartAwayMaxMs] (measured); the rig's waits for it. */
        val restartLeavesAfterMs: Long get() = data.restartLeavesAfterMs
        val restartAwayMinMs: Long get() = data.restartAwayMinMs
        val restartAwayMaxMs: Long get() = data.restartAwayMaxMs
        val reconnectGoneWaitMs: Long get() = data.reconnectGoneWaitMs
        val reconnectBackWaitMs: Long get() = data.reconnectBackWaitMs
        /** The preset the cable plays after every restart and every plug-in (record `slots.powerUpPreset`). */
        val powerUpPreset: Int get() = data.powerUpPreset

        /**
         * The bands the firmware preset [index] plays (record `slots.presetBands`: Crinacle's published values, all PEAK, no preamp), or null for the Custom EQ and any index the record does not list.
         * The cable cannot report them: this table is what the app shows as playing while the cable is on that preset.
         */
        fun presetBands(index: Int): List<Band>? = data.presetBands[index]?.mapIndexed { i, b -> Band("preset$index-${i + 1}", FilterType.PEAK, b.freqHz, b.gainDb, b.q) }

        /** The name of preset [index] ("Custom EQ", "DUSK-Default", ...). */
        fun presetName(index: Int): String = presetLabels[index] ?: "preset $index"
        /** The index is one of the firmware presets (not the Custom EQ): the cable plays what is in its firmware, whatever the Custom bank holds. */
        fun isFirmwarePreset(index: Int): Boolean = index != customIndex
        fun caps(): DeviceCapabilities = DeviceCapabilities(name, VENDOR_ID, productIds.min(), bandCount, minGainDb, maxGainDb, minQ, maxQ, minFrequencyHz, maxFrequencyHz, types, false, shelfForm = data.shelfForm)
    }

    val duskSp = Model(dusk.productName, setOf(dusk.productId), dusk.reportBytes, hardwareVerified = dusk.hardwareVerified)
    val models = listOf(duskSp)

    /** Exact VID, exact PID and the literal USB product name ("DUSK-SP"): the record's `usb` identity, name match included. */
    fun find(vendorId: Int, productId: Int, productName: String?): Model? =
        if (GeneratedDevices.MOONDROP_DUSK_USB.matches(vendorId, productId, productName)) duskSp else null
}
