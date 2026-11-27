package io.github.chronosauros.contour.core.devices

import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.ShelfForm
import io.github.chronosauros.contour.core.ShelfLimits

/*
 * The plain data the catalog generator (:core:generateDeviceCatalog, android/core/devices.gradle.kts) fills from the device records. The classes are
 * hand-written so the generator emits constructor calls only; the catalogs and drivers read these instead of typing the numbers again.
 * Behaviour stays in the drivers. A value the code uses while its record still says "unknown" is not here: see [PendingRecords].
 */

/** Which lookup of DeviceTarget finds a record: `find` is the 1.5 list (VID:PID only), `findDevice` adds the 1.6 devices (some need the USB product name). */
internal enum class Recognition { FIND, FIND_DEVICE }

/**
 * How a record is found on USB. [productName] is the literal USB product string (null = the record has none); [matchByName] says the name must
 * match as well (the product string or one of [nameAliases], exactly), otherwise VID:PID alone is enough.
 */
internal data class UsbIdentity(
    val recordId: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String?,
    val matchByName: Boolean,
    val nameAliases: List<String>,
    val recognition: Recognition,
) {
    fun matches(vendorId: Int, productId: Int, productName: String?): Boolean =
        this.vendorId == vendorId && this.productId == productId &&
            (!matchByName || (productName != null && (productName == this.productName || productName in nameAliases)))
}

/** A factory-flat band: a peak at 0 dB, this frequency, this Q (the register Q for the TRN). */
internal data class FlatBand(val freqHz: Int, val q: Double)

/** A stable WalkPlay record (Micro, Max, TRN): the caps, the preamp policy, the factory flat of the spare slots and the read bounds. */
internal data class WalkPlayData(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    /** The literal USB product string; null = not recorded. */
    val usbName: String?,
    val schemeNo: Int,
    val reportId: Int,
    /** The length byte of a filter write. */
    val bandLen: Int,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val types: Set<FilterType>,
    /** The register Q of a shelf is the intended Q times this (record `peq.shelfQScale`; the TRN: sqrt(2)); 1.0 = the register is the Q. */
    val shelfQScale: Double,
    /** How the DAC builds a shelf (record `peq.shelfForm`): the Micro plays Contour's coefficient bytes (slope), the Max and the TRN build it from the registers (Q). */
    val shelfForm: ShelfForm,
    /** The rate the DSP runs the coefficients Contour sends at (record `peq.coefficientRateHz`; the Micro: 93 750 Hz, the vendor's tool designs for 96 000). */
    val coefficientRateHz: Double,
    val preampWholeDb: Boolean,
    val preampMinDb: Int,
    val preampMaxDb: Int,
    val factoryFlat: List<FlatBand>,
    /** What a read accepts (the register format, not the editor caps); null = the record gives none and the read is held to the caps. */
    val readBoundsHz: IntRange?,
    val readBoundsDb: ClosedFloatingPointRange<Double>?,
) {
    fun caps() = DeviceCapabilities(name, vendorId, productId, bands, gainMinDb, gainMaxDb, qMin, qMax, freqMinHz, freqMaxHz, types, preampWholeDb,
        shelfQScale = shelfQScale, shelfForm = shelfForm, coefficientRateHz = coefficientRateHz)
}

/** The WalkPlay record of a device Contour only reads (the Echo-A): what its profile and its caps take from the record; the rest is [PendingRecords]. */
internal data class ReadOnlyWalkPlayData(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val usbName: String,
    val schemeNo: Int,
    val reportId: Int,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val types: Set<FilterType>,
    /** How the DAC builds a shelf (record `peq.shelfForm`; the Echo-A plays Contour's coefficient bytes like the Micro: slope). */
    val shelfForm: ShelfForm,
    /** The rate the DSP runs the coefficients at (record `peq.coefficientRateHz`), measured on the Echo-A like on the Micro. */
    val coefficientRateHz: Double,
)

/** The Moondrop record (Dawn Pro 2). [offeredTypes] are what Contour offers; [nativeTypes] every code the manufacturer's tool knows (offered and held). */
internal data class MoondropData(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String,
    val reportId: Int,
    val reportBytes: Int,
    /** The EQ index the Hub's Write Cfg first frame asks for (record `driver.peqBank`). */
    val writableIndex: Int,
    /** Every EQ index whose bank Contour writes (record `slots.writableIndexes`): the vendor catalog's PEQ index and the one the owner's unit stays on. */
    val writableIndexes: Set<Int>,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val offeredTypes: Set<FilterType>,
    val nativeTypes: Map<Int, FilterType>,
    /** The register Q of a shelf is the intended Q times this (record `peq.shelfQScale`; measured on the Dawn Pro 2, run 2026-10-25-moondrop-dawn-pro-2-08-mac-eqtest-48k: sqrt(2)); 1.0 = the register is the Q. */
    val shelfQScale: Double,
    /** How the DAC builds a shelf (record `peq.shelfForm`; the Dawn Pro 2 plays the registers: Q). */
    val shelfForm: ShelfForm,
    val preGainMinDb: Double,
    val preGainMaxDb: Double,
    val globalGainMinDb: Double,
    val globalGainMaxDb: Double,
)

/** A KT Micro record (Allegro Mini, JCally JM12): up to five bands as register pairs consecutive from [bandRegisterBase] (gain and frequency, then Q and type). One per record; each model has its own. */
internal data class KtMicroData(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String,
    val nameAliases: List<String>,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val types: Set<FilterType>,
    val bandRegisterBase: Int,
    val enableRegister: Int,
    val digitalGainRegister: Int,
    val writeAck: Int,
    /** The Global Gain steps the vendor tool offers (register 0x3B); null = the record offers no Global Gain and the code never writes 0x3B for it. */
    val globalGainUserSteps: IntRange?,
    /** The rate the DSP designs its biquads for (record `peq.designRateHz`, 24 000 Hz measured): a band plays at register frequency x stream rate / design rate. */
    val designRateHz: Double,
    /** The stream rate Contour assumes (record `peq.assumedStreamHz`, 48 000 Hz) when it converts an intended frequency to the register. */
    val assumedStreamHz: Double,
    /** The register Q of a shelf is the intended Q times this (record `peq.shelfQScale`; 1.0 = the register is the Q). Only meaningful for a record whose `peq.types` has a shelf. */
    val shelfQScale: Double,
    /** How the DAC builds a shelf (record `peq.shelfForm`; the JM12: Q; the Allegro Mini offers no shelf, its record says "unknown" and the code uses the slope form). */
    val shelfForm: ShelfForm,
    /** `driver.reconnectAfterSave`: the USB device leaves the bus after SAVE. null = the record says "unknown" and the code's own value is used ([PendingRecords]). */
    val reconnectAfterSave: Boolean?,
    /** `reset.offered`: the vendor software offers CLEAR (0x43) and Contour's bench may build it. False = Contour never sends it to this model. */
    val resetOffered: Boolean,
    /** `driver.eqEnableStatusBytes`: bytes 1-3 of the EQ switch register (0x24) are device status that changes on its own (JM12, run 2026-11-10-jcally-jm12-01-mac). A write sends `[eq, 0, 0, 0]` and a read-back is compared on byte 0 only. False = the bytes are echoed and compared (Allegro Mini). */
    val eqEnableStatusBytes: Boolean,
    /** `driver.sendTurnsEqOn`: the app's send turns the EQ switch (0x24) ON when it reads OFF; false = it is left as found (Allegro Mini). */
    val sendTurnsEqOn: Boolean,
    /** `peq.shelfGainDb` and `peq.shelfQ`: a shelf's own, narrower limits (measured); null = a shelf has the device-wide ones. */
    val shelfLimits: ShelfLimits? = null,
)

/** One band of a firmware preset of a gen-1 cable: a PEAK filter in Hz, dB and Q. */
internal data class Gen1PresetBand(val freqHz: Double, val gainDb: Double, val q: Double)

/**
 * The Moondrop gen-1 record (DUSK-SP; MAY, FreeDSP and Echo-B speak the same protocol): nine bands, a meta record and five coefficient sets per band, a firmware preset list,
 * and the control-pipe pacing. [factoryBank] is what the Custom EQ holds from the factory, by band position (PEAK, 0 dB, Q 1.0 at these frequencies): a band Contour does not use is written as that.
 */
internal data class MoondropGen1Data(
    val id: String,
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val productName: String,
    val reportId: Int,
    val reportBytes: Int,
    val bands: Int,
    val gainMinDb: Double,
    val gainMaxDb: Double,
    val qMin: Double,
    val qMax: Double,
    val freqMinHz: Double,
    val freqMaxHz: Double,
    val types: Set<FilterType>,
    /** The cable plays the biquad coefficients Contour computes: slope (record `peq.shelfForm`, which the generator requires to say "slope"). */
    val shelfForm: ShelfForm,
    /** The firmware presets and the Custom EQ by index (record `slots.labels`): 0 is the Custom EQ Contour writes. */
    val presetLabels: Map<Int, String>,
    /** The Custom EQ index (record `driver.customIndex`). */
    val customIndex: Int,
    /** `slots.stockPicker`: the DAC page offers the firmware presets as a row. */
    val stockPicker: Boolean,
    /** `slots.stockPickerInline`: the DAC page shows the presets as buttons on the page. */
    val stockPickerInline: Boolean,
    /** The factory bank by band position (record `slots.factoryBank`): the frequency of each band, all PEAK, 0 dB, Q = [factoryQ]. */
    val factoryFrequenciesHz: List<Int>,
    val factoryQ: Double,
    /** `pacing`: the wait between SET_REPORT and GET_REPORT of every request, the pause after a band or coefficient write, after the commit of a write, and after a preset select. */
    val replyWaitMs: Long,
    val writeGapMs: Long,
    val afterCommitMs: Long,
    val afterSelectMs: Long,
    /** The commit stores the Custom bank and restarts the cable (measured, runs 2026-11-11-moondrop-dusk-02/04-mac): it leaves the bus [restartLeavesAfterMs] after the commit's reply and is back after [restartAwayMinMs]..[restartAwayMaxMs]; the rig waits [reconnectGoneWaitMs] for it to leave and [reconnectBackWaitMs] for it to return. */
    val restartLeavesAfterMs: Long,
    val restartAwayMinMs: Long,
    val restartAwayMaxMs: Long,
    val reconnectGoneWaitMs: Long,
    val reconnectBackWaitMs: Long,
    /** `slots.powerUpPreset`: the preset the cable plays after every restart and every plug-in (1, DUSK-Default). */
    val powerUpPreset: Int,
    /** `slots.presetBands`: the bands each firmware preset plays by index (all PEAK, no preamp), which the cable cannot report; the Custom EQ has no entry. */
    val presetBands: Map<Int, List<Gen1PresetBand>>,
    /** `driver.hardwareVerified`: false keeps the app from writing the cable in a release build (a review build writes it). */
    val hardwareVerified: Boolean,
)
