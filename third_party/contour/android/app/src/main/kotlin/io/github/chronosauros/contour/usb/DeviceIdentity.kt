package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import io.github.chronosauros.contour.core.DeviceTarget

/**
 * The one place the app turns a USB device into a [DeviceTarget]: the exact VID:PID of the Contour 1.5 DACs, and for the 1.6 families
 * (Moondrop Dawn Pro 2, Kiwi Ears Allegro Mini, Moondrop Echo-A) the USB product name as well. A device that is neither is not a DAC to Contour.
 */
internal object DeviceIdentity {
    fun target(device: UsbDevice): DeviceTarget? = DeviceTarget.findDevice(device.vendorId, device.productId, device.productName)
}
