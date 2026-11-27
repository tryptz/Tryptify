package io.github.chronosauros.contour.core.devices

/**
 * Values the code uses while the device record still says "unknown" for them, because no bench log and no vendor code has them yet. Each one names the
 * record field it belongs to; when the record gets a sourced value the constant moves into the record and is deleted here (the generator then
 * supplies it through GeneratedDevices). Nothing here is a claim about the device beyond "this is what the code has always done".
 *
 * Also not in a record, by construction: the Echo-A is driven by the Protocol Micro's code, so its preamp policy is the Micro's
 * (`crinear-protocol-micro` preamp, via DeviceProtocol.MICRO); the record's own `preamp` fields are "unknown".
 */
internal object PendingRecords {
    /** `moondrop-echo-a` peq.freqHz ("unknown"): no source names a frequency range for the Echo-A; the Protocol Micro's range is applied to its caps (the planner's limit once the write is enabled; the read-only caps until then). */
    val ECHO_A_FREQ_HZ: ClosedFloatingPointRange<Double> = 20.0..20_000.0

    /**
     * `kiwi-ears-allegro-mini` driver.reconnectAfterSave ("unknown"): advBeta's value - the USB device leaves the bus after SAVE, so a write counts as
     * done only after a read when it is back. The vendor tool says nothing about re-enumeration (scope item: capture the SAVE on the unit).
     */
    const val ALLEGRO_RECONNECT_AFTER_SAVE: Boolean = true

    /**
     * `moondrop-dawn-pro-2` has no field for the range of the EQ index (register 0x0F, "slots" holds the writable bank only). The DAC Settings
     * page shows the index the DAC reports; this range only bounds the row and is provisional (0..15 held since the first Dawn Pro 2 code).
     */
    val DAWN_EQ_INDEX_RANGE: IntRange = 0..15
}
