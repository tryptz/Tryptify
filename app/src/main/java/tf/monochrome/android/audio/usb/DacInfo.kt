package tf.monochrome.android.audio.usb

import android.hardware.usb.UsbDevice

/**
 * Who the DAC actually is, read straight off the USB device the moment the
 * driver owns it.
 *
 * Everything here comes from the device's descriptors — no class-specific
 * control transfers, no libusb round-trips — so it is available the instant
 * [LibusbUacDriver.open] succeeds and is safe to show before any stream is
 * negotiated. The negotiated stream itself (rate/bits/UAC version/clock)
 * lives in [BypassDiagnostics]; this answers the question one row before
 * that: *which* DAC are we talking to.
 *
 * `manufacturerName` / `productName` are string descriptors the framework
 * caches at enumeration and reads without holding the device. `serialNumber`
 * is permission-gated by the platform (API 29+ especially), so it is read
 * only while we hold the grant and is nullable everywhere.
 */
data class DacInfo(
    val manufacturer: String?,
    val product: String?,
    val serialNumber: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceId: Int,
    /** USB standard version the device advertises, e.g. "2.00" / "1.10". */
    val usbVersion: String?,
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
) {
    /**
     * The name to show. "Focal Bathys" from manufacturer + product, with
     * graceful fallbacks for DACs that ship empty string descriptors (they
     * exist — some cheap dongles report neither, in which case VID:PID is
     * the only honest identity we have).
     */
    val displayName: String
        get() = listOf(manufacturer?.takeIf { it.isNotBlank() },
                       product?.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString(" ")
            .ifBlank { "USB DAC $idHex" }

    /** "1d6b:01003"-style identity for support requests and logcat. */
    val idHex: String
        get() = "%04x:%04x".format(vendorId, productId)

    /** Compact descriptor line: identity + USB version, e.g.
     *  "1d6b:01003 · USB 2.00 · class 0/0/0". */
    val descriptorLine: String
        get() = buildString {
            append(idHex)
            usbVersion?.takeIf { it.isNotBlank() }?.let {
                append(" · USB ")
                append(it)
            }
            append(" · class ")
            append(deviceClass)
            append("/")
            append(deviceSubClass)
            append("/")
            append(deviceProtocol)
        }

    companion object {
        /**
         * Reads everything the platform exposes without error. Any string
         * descriptor may throw or return null depending on API level and
         * permission state; a missing field degrades the display, so each
         * is read defensively and the whole call returns null only when
         * there is no device at all.
         */
        fun fromDevice(device: UsbDevice): DacInfo? {
            if (device == null) return null
            val serial: String? = try {
                // Permission-gated by the platform; we hold the grant once the
                // device is opened, but a revoked-mid-flight grant must not
                // take the whole card down.
                device.serialNumber
            } catch (_: SecurityException) {
                null
            }
            return DacInfo(
                manufacturer = runCatching { device.manufacturerName }.getOrNull(),
                product = runCatching { device.productName }.getOrNull(),
                serialNumber = serial,
                vendorId = device.vendorId,
                productId = device.productId,
                deviceId = device.deviceId,
                usbVersion = runCatching { device.version }.getOrNull(),
                deviceClass = device.deviceClass,
                deviceSubClass = device.deviceSubclass,
                deviceProtocol = device.deviceProtocol,
            )
        }
    }
}
