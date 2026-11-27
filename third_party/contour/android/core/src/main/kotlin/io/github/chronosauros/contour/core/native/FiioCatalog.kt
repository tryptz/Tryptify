package io.github.chronosauros.contour.core.native

import io.github.chronosauros.contour.core.FilterType
import io.github.chronosauros.contour.core.ShelfForm
import io.github.chronosauros.contour.core.devices.GeneratedDevices

/** Source rules are evidence, not transport authorisation or hardware support claims. */
data class FiioCaptureIdentity(val vendorId: Int, val productId: Int, val productName: String, val fixturePath: String)
data class FiioConfig(
    val productName: String,
    val vendorIds: Set<Int>,
    val reportId: Int,
    val maxFilters: Int,
    val minGainDb: Double,
    val maxGainDb: Double,
    val minQ: Double,
    val maxQ: Double,
    val saveCommand: Int,
    val userSlots: Set<Int>,
    val declaredWritableSlots: Set<Int>,
    val stockSlots: Set<Int>,
    val slotLabels: Map<Int, String>,
    val bypassSlot: Int?,
    val disconnectOnSave: Boolean,
    val peakingGainCompensation: Boolean,
    val shelfAlphaCompensation: Boolean,
    val sourceUrl: String,
    val constraintsUrl: String,
    val constraintsRef: String,
    val upstreamExperimental: Boolean,
    val bestGuessProductName: Boolean,
    // How the DAC builds a shelf (record `peq.shelfForm`): "q" for every FiiO DAC measured so far (the DAC builds the shelf from its registers, RBJ alpha = sin(w0)/(2Q)); the graph and AUTO follow it, the registers never do.
    val shelfForm: ShelfForm,
    val capture: FiioCaptureIdentity?,
    val codecBlockers: List<String>,
    val evidenceCaveats: List<String>,
    val status: String = "beta-unverified",
    // Default = what the upstream handler writes and the KA15 capture shows: PK=0, LSQ=1, HSQ=2. The JSON advertises
    // more types, but a device takes the others only with captured bytes: BP/LP/HP/AP are set per device from
    // [FIIO_ALL_TYPE_CODES] (research/snowsky-names-types-2026-10-14/FINDINGS.md). The KA15 and the Melody stay on these three: a device gets pass types only when FiiO's own app offers them for it.
    val typeCodes: Map<FilterType, Int> = mapOf(FilterType.PEAK to 0, FilterType.LOW_SHELF to 1, FilterType.HIGH_SHELF to 2),
    // Conservative source connector input range, not an acoustic/firmware guarantee.
    val minFrequencyHz: Int = 20,
    val maxFrequencyHz: Int = 20000,
    // Hardware-observed (KA15, 03.10.2026): the DAC ignores a lower filter count and keeps reading back
    // maxFilters, so the unused tail must be written as neutral filters instead of shortening the count.
    val fixedBandCount: Boolean = false,
    // KA15, Melody and K13 R2R read Q back slightly off: KA15 605 -> 607 and 390 -> 391 on hardware, the Melody 300 -> 301
    // (PC probe 13.10.2026), the K13 R2R one lower, 990 -> 989 and 424 -> 423 (phone run 2026-10-25-fiio-k13-r2r-phone-nothing, Nothing, 25.10.2026;
    // a peak's register is intended Q x sqrt2, so 7.0 and 3.0 landed on those). Allowed drift is 1 + q/250 hundredths; more, or any other field, is a mismatch. The same drift is allowed at the
    // Q range edges when reading (KA15 USER2, Pixel 21.10.2026: 0.10 -> 0.09, 10.00 -> 10.03, 9.99 -> 10.02), see FiioCodec.validateRegisters.
    val qReadbackSlack: Boolean = false,
    // KA15 echoes every AA write; reading each echo keeps request and reply in lockstep.
    val consumeWriteEchoes: Boolean = false,
    // Pause after every AA write, for DACs that do not echo writes (consumeWriteEchoes paces the KA15 instead).
    val writeGapMs: Int = 0,
    // Wait after the flash save (0x19) before the first read: the KA15 stays busy about 2 s after it (devices/fiio-ka15.json pacing, hang analysis 22.10.2026: a name-read poll
    // 845 ms after the save met a DAC that never answered again), the other models answer within 800 ms. Per model, from the device record's pacing.afterSaveMs.
    val afterSaveMs: Int = 800,
    // Official web app (fiiocontrol.fiio.com, captured 03.10.2026): byte 3 = host sequence, byte before EE =
    // CRC-8/MAXIM over header..data. The device also answers zeros, so this is fidelity, not a requirement.
    val checksumFrames: Boolean = false,
    // Hardware-observed (KA15 on Android, 03.10.2026): HID replies stop while no USB audio is streaming;
    // the app keeps a silent output track on the DAC for the whole HID session.
    val needsAudioStream: Boolean = false,
    // Official web app (KA15 capture, 03.10.2026): command 0x30 reads and writes the USER slot names,
    // index = position in the sorted userSlots (USER1 = 0).
    val userSlotNames: Boolean = false,
    // Lets the user choose a USER slot without FiiO name commands (no 0x30 reads or writes). Set per device, after a hardware test.
    val userSlotPicker: Boolean = false,
    // Hardware-measured (KA15, 04.10.2026, line-in sweeps against Close EQ): LS/HS shelves play Q = register / sqrt(2)
    // for every type, frequency, gain and Q, so the register holds intended Q * shelfQScale. PK Q is played as stored.
    // Not combinable with shelfAlphaCompensation (the devicePEQ slope law measured wrong on the KA15).
    val shelfQScale: Double = 1.0,
    // Hardware-measured (K13 R2R, 25.10.2026, run 2026-10-25-fiio-k13-r2r-02-mac-eqtest): the peaking filter plays Q = register / sqrt(2) (shelves and pass filters play the register as Q),
    // so the register holds intended Q * peakQScale. Not combinable with peakingGainCompensation.
    val peakQScale: Double = 1.0,
    // Hardware-measured (KA15, 04.10.2026): with any USER preset active, output = input - 12 dB + preamp register,
    // so the register holds intended preamp + preampOffsetDb.
    val preampOffsetDb: Double = 0.0,
    // Hardware-measured (SnowSky Melody and Tiny A, 22.10.2026): the DAC keeps whole dB of the preamp only (a write of -3.5 dB reads back -3.0 and the send was NOT VERIFIED), so the plan
    // rounds the preamp DOWN to a whole dB before it is sent (-3.5 -> -4, quieter, as the WalkPlay family's `floor(preamp + 1e-6)`) and the read-back compares whole dB. Record `preamp.wholeDb`.
    val preampWholeDb: Boolean = false,
    // Read, write and persistence confirmed on the real DAC (KA15 04.10.2026, K13 R2R 10.10.2026, Melody 13.10.2026, Tiny A 14.10.2026): the
    // Tune page drops its "not hardware tested" line. [status] stays the catalog's source-evidence label.
    val hardwareVerified: Boolean = false,
    // SnowSky firmware (Melody 1.20, Tiny A 1.10; PC probes 13.10.2026): the slot reply goes stale after band or preamp
    // traffic on a USER slot and SAVE answers without an AA echo, so these DACs take the [FiioMelodyExchange] path.
    val snowskyExchange: Boolean = false,
    // Measured on the SnowSky RETRO NANO (13.11.2026, Mac): while the unit sits on its Close EQ slot it answers the preamp query (0x17) with one frame `bb 0b .. .. 18 01 00 .. ee`
    // (command 0x18, one data byte 0) instead of a 0x17 reply. That frame, and only that frame, on the bypass slot of a model with this flag, reads as "no EQ active": the session
    // takes the preamp as register 0 and reads no bands (nothing plays there). Any other DAC, slot or reply still has to be a 0x17 reply.
    val bypassPreampAnswersAsCount: Boolean = false,
    // Short device name for messages on the [snowskyExchange] path ("Reconnect Melody").
    val shortName: String? = null,
    // SnowSky names (FiiO web app capture on the Tiny A, 14.10.2026): command 0x30 is addressed by the slot id (a0-a2),
    // not the KA15 position; the reply carries the slot and an 8-byte field; a write sends the name unpadded and the DAC
    // clears the rest of the field. Names are read after the band reads, so they never sit before a slot query.
    val nameBySlotId: Boolean = false,
    // Longest USER name the DAC stores: KA15 7, SnowSky 8 (the FiiO web app allows 8).
    val nameLength: Int = 7,
    // The DAC Settings page is generated from this list (see [DacSettings]); empty = the device has no such page.
    val settings: List<DacSetting> = emptyList(),
    // The DAC probe (review builds): pause between its 1-byte GETs, FiiO Control's own deviceCommandInterval for the model (Tiny A 50 ms,
    // Melody 100 ms, KA15 200 ms; FIIO-CONTROL-APP.md). null = FiiO Control drives this model with another protocol (K13 R2R: 24-bit command
    // IDs), so our 1-byte GETs mean nothing there and the probe skips the FiiO frame layer.
    val probeGapMs: Int? = 100,
    // Reads shown in the DAC Settings page header (sample rate, firmware), the review preview's values per setting id (the hardware values).
    val info: List<DacInfo> = emptyList(),
    val settingsPreview: Map<String, Int> = emptyMap(),
) {
    /** Intended (acoustic) preamp range: the register range minus [preampOffsetDb]. */
    val preampMinDb: Double get() = minGainDb - preampOffsetDb
    val preampMaxDb: Double get() = maxGainDb - preampOffsetDb
    /** Intended (acoustic) shelf Q range: the register Q range divided by [shelfQScale]. */
    val shelfQMin: Double get() = minQ / shelfQScale
    val shelfQMax: Double get() = maxQ / shelfQScale
    /** Intended (acoustic) peak Q range: the register Q range divided by [peakQScale]. */
    val peakQMin: Double get() = minQ / peakQScale
    val peakQMax: Double get() = maxQ / peakQScale
    val hardwareGates: List<String> get() = listOf(
        "Verify exact USB identity and HID interface/descriptor/report size before I/O",
        "Verify selection, complete backup, post-save native readback on actual firmware",
        "Persistence across power cycle and acoustic behaviour unverified",
    )
}

object FiioCatalog {
    // The numbers of every rule below (limits, slots, codes, pacing, flags, upstream pins, settings placement, capture identity) are the device
    // records in devices/*.json, turned into Kotlin by :core:generateDeviceCatalog (GeneratedDevices, see devices/README.md); the evidence for each
    // value (hardware notes, conflicts) lives in the record. What stays here is the choice and order of the rules and the behaviour behind the flags.

    /** The upstream commit the oracle (catalog-oracle.json) and every rule's source URL are pinned to. */
    const val PINNED_COMMIT = "0617f382e76629792a5933e6933e4b396a756a93"

    /** USB product ID of the FiiO K13 R2R (vendor id and product id both come from its record; the Android attach filter is generated from the same record). */
    const val K13_R2R_PRODUCT_ID = GeneratedDevices.FIIO_K13_R2R_PRODUCT_ID

    /** USB product IDs of the SnowSky Melody and Tiny A, from their records. */
    const val MELODY_PRODUCT_ID = GeneratedDevices.SNOWSKY_MELODY_PRODUCT_ID
    const val TINY_A_PRODUCT_ID = GeneratedDevices.SNOWSKY_TINY_A_PRODUCT_ID

    /** USB product ID of the FiiO JadeAudio JA11 (found by VID, PID and the exact USB name). */
    const val JA11_PRODUCT_ID = GeneratedDevices.FIIO_JA11_PRODUCT_ID

    /** USB product IDs of the SnowSky RETRO NANO (0A12:4007) and the FiiO JadeAudio JIEZI (2972:0151); both are found by VID, PID and the exact USB name. */
    const val RETRO_NANO_PRODUCT_ID = GeneratedDevices.SNOWSKY_RETRO_NANO_PRODUCT_ID
    const val JIEZI_PRODUCT_ID = GeneratedDevices.FIIO_JIEZI_PRODUCT_ID

    /** Contour stable: the FiiO KA15, the K13 R2R, the SnowSky Melody and Tiny A, then the FiiO JA11, the SnowSky RETRO NANO and the FiiO JIEZI, in this order (the oracle test takes the first two). A new
     * FiiO-family record is generated as soon as it exists in devices/, but is part of the catalog only when it is listed here.
     */
    val rules: List<FiioConfig> = listOf(
        GeneratedDevices.FIIO_KA15,
        GeneratedDevices.FIIO_K13_R2R,
        GeneratedDevices.SNOWSKY_MELODY,
        GeneratedDevices.SNOWSKY_TINY_A,
        GeneratedDevices.FIIO_JA11,
        GeneratedDevices.SNOWSKY_RETRO_NANO,
        GeneratedDevices.FIIO_JIEZI,
    )

    /** The SnowSky Melody and Tiny A by their exact USB identities (see DeviceTarget.find). */
    val MELODY: FiioConfig = GeneratedDevices.SNOWSKY_MELODY
    val TINY_A: FiioConfig = GeneratedDevices.SNOWSKY_TINY_A

    /** The KA15 by its exact USB identity, from its record (stable builds route by VID:PID, see DeviceTarget.find). */
    val KA15: FiioConfig = GeneratedDevices.FIIO_KA15

    /** The K13 R2R by its exact USB identity from its record (see DeviceTarget.find). */
    val K13: FiioConfig = GeneratedDevices.FIIO_K13_R2R

    /** The FiiO JadeAudio JA11, found by VID, PID and the exact USB name from its record (see DeviceTarget.findDevice); the app drives it with [FiioJa11.CONFIG]. */
    val JA11: FiioConfig = GeneratedDevices.FIIO_JA11

    /** The SnowSky RETRO NANO (0A12:4007) and the FiiO JadeAudio JIEZI, found by VID, PID and the exact USB name from their records (see DeviceTarget.findDevice); [FiioConfig.hardwareVerified] false until a unit has taken a write. */
    val RETRO_NANO: FiioConfig = GeneratedDevices.SNOWSKY_RETRO_NANO
    val JIEZI: FiioConfig = GeneratedDevices.FIIO_JIEZI

    /** Positive route solely for the captured exact VID/PID/name identity; the HID descriptor is still
     * required before any I/O. Every unknown PID/name returns null; no probing writes.
     */
    fun capturedRoute(vendorId: Int, productId: Int, productName: String): FiioConfig? =
        rules.singleOrNull { rule ->
            rule.codecBlockers.isEmpty() && rule.capture?.let {
                it.vendorId == vendorId && it.productId == productId && it.productName == productName
            } == true
        }
}
