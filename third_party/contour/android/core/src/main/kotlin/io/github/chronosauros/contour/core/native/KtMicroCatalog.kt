package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.DeviceCapabilities
import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.ShelfForm
import io.github.chronosauros.contour.core.ShelfLimits
import io.github.chronosauros.contour.core.devices.GeneratedDevices
import io.github.chronosauros.contour.core.devices.KtMicroData
import io.github.chronosauros.contour.core.devices.PendingRecords
import io.github.chronosauros.contour.core.devices.UsbIdentity

/**
 * KT Micro register DACs: the Kiwi Ears Allegro Mini (chip KT_0211L: the one KT identity with a hardware read, DacLab 20.10.2026, and a vendor tool whose limits are known, Kiwi Ears Equalizer,
 * equalizer.kiwiears.com) and the JCally JM12 (chip KT02H20; same VID:PID, told apart by the USB product name). Register facts: devicePEQ 0617f382 (0BSD) ktmicroUsbHidHandler.js and
 * usbDeviceConfig.js, as ported from advBeta. No broader KT catalog, no probe of unknown KT identities.
 *
 * Every model has its own record (the device records via GeneratedDevices, one [KtMicroData] each): ranges, types, band registers, write ack, rates, Global Gain steps (null = none), `reconnectAfterSave`,
 * `hardwareVerified`. What all KT records share (report id, payload size, read and save commands, the EQ switch and digital gain registers) is asserted equal by the generator and lives once in [KtMicroCodec].
 * Allegro Mini limits are the vendor tool's, not devicePEQ's: 5 PK bands, +-12 dB, Q 0.1-10, 20-20000 Hz as REGISTER frequencies, an EQ switch (register 0x24), no preamp (its "Global Gain" is the analog DAC gain,
 * register 0x3B, written only by [KtMicroCodec.compileGlobalGain]). The JM12 offers PEAK and both shelves and no Global Gain; its limits beyond devicePEQ are candidates until the bench says. Exact USB names only.
 *
 * Frequency: the DSP designs its biquads for a fixed 24 000 Hz and runs them at the stream rate, so a band plays at register f x stream / 24 000 (measured 25.10.2026, runs 2026-10-25-kiwi-ears-allegro-mini-07 and -08).
 * Contour assumes a 48 kHz stream and writes the register f = intended f x 24 000 / 48 000 ([Model.registerFrequency]); a read is converted back ([Model.intendedFrequency]). The editor's frequency range is the
 * intended one: the register range x 2, capped at 20 000 Hz (Allegro Mini 40-20000 Hz; the JM12's register range starts at 10 Hz: 20-20000 Hz).
 */
object KtMicroCatalog {
    private val allegroData = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI
    private val jm12Data = GeneratedDevices.JCALLY_JM12
    private val ad1Data = GeneratedDevices.KIWI_EARS_AD1
    const val VENDOR_ID = GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_VENDOR_ID
    data class RegisterPair(val gainFrequency: Int, val qType: Int)
    data class RegisterMap(val name: String, val bands: List<RegisterPair>, val enableRegister: Int,
                           val writeAck: Int, val digitalGainRegister: Int)

    /**
     * One KT model. The five constructor properties are what the golden dumps; everything else of the record is reached through [data] (no backing field: the record is looked up by the USB product
     * name, which is unique across the models).
     */
    data class Model(
        val productName: String,
        val productIds: Set<Int>,
        val registers: RegisterMap,
        /** The USB device drops off the bus after SAVE; the proof of a write is a read after it is back. The record's value, [PendingRecords] while the record says "unknown" (the Allegro Mini). */
        val reconnectAfterSave: Boolean = PendingRecords.ALLEGRO_RECONNECT_AFTER_SAVE,
        /** False until the owner's unit has taken a write from the phone app and read it back: the JCally JM12 has (Nothing Phone (3a) 10.11.2026, run 2026-11-10-jcally-jm12-11-phone: five-band edge profile VERIFIED after the reconnect, cold read equal, EQ switch OFF/ON VERIFIED), and the Allegro Mini has (Nothing Phone 25.10.2026, run 2026-10-25-kiwi-ears-allegro-mini-05-phone-nothing: registers = intended f x 24000 / 48000, VERIFIED; EQ switch and Global Gain on the Pixel run 2026-10-24-kiwi-ears-allegro-mini-04-phone). */
        val hardwareVerified: Boolean = false,
    ) {
        internal val data: KtMicroData get() = dataByProductName.getValue(productName)
        /** The display name of the record ("Kiwi Ears Allegro Mini", "JCally JM12"). */
        val name: String get() = data.name
        val nativeTypes: Set<FilterType> get() = data.types
        val bandCount: Int get() = registers.bands.size
        val minGainDb: Double get() = data.gainMinDb
        val maxGainDb: Double get() = data.gainMaxDb
        val minQ: Double get() = data.qMin
        val maxQ: Double get() = data.qMax
        /** The register Q of a shelf is the intended Q times this (record `peq.shelfQScale`); 1.0 where the record offers no shelf. */
        val shelfQScale: Double get() = data.shelfQScale
        /** How the DAC builds a shelf (record `peq.shelfForm`): the JM12 builds it from its registers (Q); the Allegro Mini offers none and uses the slope form. */
        val shelfForm: ShelfForm get() = data.shelfForm
        /** The rate the DSP designs its biquads for (record `peq.designRateHz`), and the stream rate Contour assumes (record `peq.assumedStreamHz`). */
        val designRateHz: Double get() = data.designRateHz
        val assumedStreamHz: Double get() = data.assumedStreamHz
        /** The register frequency (unrounded) that plays [intendedHz] at the assumed stream rate. */
        fun registerFrequency(intendedHz: Double): Double = intendedHz * designRateHz / assumedStreamHz
        /** The frequency a band with this register plays at the assumed stream rate. */
        fun intendedFrequency(register: Int): Double = register * assumedStreamHz / designRateHz
        /** The intended (acoustic) frequency range: the register range (Allegro Mini: the vendor tool's 20-20000 Hz) converted, and capped at the upper register edge. */
        val minFrequencyHz: Double get() = intendedFrequency(data.freqMinHz.toInt())
        val maxFrequencyHz: Double get() = minOf(data.freqMaxHz, intendedFrequency(data.freqMaxHz.toInt()))
        /** The Global Gain steps (register 0x3B) the vendor tool offers; null = this model has no Global Gain (never written, no row). The layout of the register is not proven on hardware, so a read is shown only inside this range. */
        val globalGainUserSteps: IntRange? get() = data.globalGainUserSteps
        /** The app's send turns the EQ switch ON when it reads OFF (record `driver.sendTurnsEqOn`, JM12: a factory unit ships with it OFF); false = left as found (Allegro Mini). */
        val sendTurnsEqOn: Boolean get() = data.sendTurnsEqOn
        /** What the app's send passes as `eqEnabled` to [KtMicroSession.write]: true (a write only when the switch reads OFF) or null (never touch register 0x24). */
        val sendEqEnabled: Boolean? get() = if (sendTurnsEqOn) true else null
        /** A shelf's own limits (record `peq.shelfGainDb`, `peq.shelfQ`), narrower than the model's, or null. */
        val shelfLimits: ShelfLimits? get() = data.shelfLimits
        /** The gain a band of [type] may have (the model's, narrowed for a shelf by [shelfLimits]) and its intended Q range (the register range divided by the type's Q scale, narrowed the same way). */
        fun gainRange(type: FilterType): ClosedFloatingPointRange<Double> = caps().gainRange(type)
        fun qRange(type: FilterType): ClosedFloatingPointRange<Double> = caps().qRange(type)
        private fun caps() = DeviceCapabilities(name, 0, 0, bandCount, minGainDb, maxGainDb, minQ, maxQ, minFrequencyHz, maxFrequencyHz, nativeTypes, false, shelfForm = shelfForm, shelfQScale = shelfQScale, shelfLimits = shelfLimits)
        /** The vendor software offers a reset (CLEAR 0x43) for this model: false = Contour never builds that frame for it. */
        val resetOffered: Boolean get() = data.resetOffered
        /** Bytes 1-3 of register 0x24 are device status, not setting: a switch write sends `[eq, 0, 0, 0]` and verification compares byte 0 only (JM12). False: echoed and compared whole (Allegro Mini). */
        val eqEnableStatusBytes: Boolean get() = data.eqEnableStatusBytes
    }

    private val usbByRecord: List<Pair<UsbIdentity, KtMicroData>> = listOf(
        GeneratedDevices.KIWI_EARS_ALLEGRO_MINI_USB to allegroData,
        GeneratedDevices.JCALLY_JM12_USB to jm12Data,
        GeneratedDevices.KIWI_EARS_AD1_USB to ad1Data,
    )

    /** Band i: gain-and-frequency register `base + 2 i`, Q-and-type register `base + 2 i + 1` (the record's `driver.bandRegisters`). */
    private fun registerMap(chip: String, d: KtMicroData) =
        RegisterMap(chip, List(d.bands) { RegisterPair(d.bandRegisterBase + 2 * it, d.bandRegisterBase + 2 * it + 1) }, d.enableRegister, d.writeAck, d.digitalGainRegister)
    val kt0211 = registerMap("KT_0211L", allegroData)
    val kt02h20 = registerMap("KT02H20", jm12Data)
    /** The Kiwi Ears AD1 answered the register reads like the JM12 (13.11.2026); its chip is not named, so its map carries the model name. */
    val ad1Map = registerMap("AD1", ad1Data)

    private fun modelsOf(d: KtMicroData, map: RegisterMap, verified: Boolean): List<Model> =
        (listOf(d.productName) + d.nameAliases).map { Model(it, setOf(d.productId), map, d.reconnectAfterSave ?: PendingRecords.ALLEGRO_RECONNECT_AFTER_SAVE, hardwareVerified = verified) }

    /** The owner's Allegro Mini reports "Kiwi Ears-Allegro Mini"; devicePEQ also lists the spelling without the hyphen (the record's product name and its alias). */
    val models: List<Model> = modelsOf(allegroData, kt0211, verified = true)

    /**
     * The JCally JM12 reports "KT02H20 HIFI Audio". Verified on the owner's unit: the perf app on a Nothing Phone (3a) wrote a five-band edge profile and read it back VERIFIED after the reconnect, the cold read after an
     * unplug and plug-in equalled the frames sent, and the EQ switch OFF and ON were VERIFIED from the phone (run 2026-11-10-jcally-jm12-11-phone; Mac runs -02, -03, -10).
     */
    val jm12Models: List<Model> = modelsOf(jm12Data, kt02h20, verified = true)

    /**
     * The Kiwi Ears AD1 (3302:20E3, found by its USB name). Verified on the owner's unit: the perf app on a Nothing A065 wrote a five-band edge profile (within the record's narrowed limits), the SAVE re-enumerated
     * the unit and the read after the reconnect was VERIFIED, a replug kept the profile and EQ ON, and the EQ switch OFF was VERIFIED from the DAC page (phone check 13.11.2026; Mac runs 2026-11-13-kiwi-ears-ad1-01-mac to -03-mac-max).
     */
    val ad1Models: List<Model> = modelsOf(ad1Data, ad1Map, verified = true)

    /** Every KT model of every record: what a plan, a switch write or a reset restore must be for. */
    val allModels: List<Model> = models + jm12Models + ad1Models

    private val dataByProductName: Map<String, KtMicroData> = listOf(allegroData, jm12Data, ad1Data).flatMap { d -> (listOf(d.productName) + d.nameAliases).map { it to d } }.toMap()
        .also { require(it.size == allModels.size) { "two KT models share a USB product name" } }

    /** Exact VID, exact PID and the literal USB product name across all KT models; no VID-only or fuzzy-name route: the records' `usb` identities, name match included. */
    fun find(vendorId: Int, productId: Int, productName: String?): Model? =
        usbByRecord.firstNotNullOfOrNull { (usb, _) ->
            if (!usb.matches(vendorId, productId, productName)) null else allModels.singleOrNull { it.productName == productName }
        }
}
