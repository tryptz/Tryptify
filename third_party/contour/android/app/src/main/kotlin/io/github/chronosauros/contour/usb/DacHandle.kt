package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import io.github.chronosauros.contour.core.DeviceTarget

/**
 * The DAC the controller and [DacClient] work on. Always a USB device ([usb]) except in a review build, where `--es simulate <dac>` ([DacSimulation]) makes one with
 * no USB at all: [simulated] then opens its connection (given the session guard of the operation) and nothing reaches the USB host. [key] names the unit for the sessions.
 */
class DacHandle(val usb: UsbDevice?, val target: DeviceTarget, val key: String, val simulated: ((guard: () -> Unit) -> HidIo)? = null) {
    companion object {
        /** The handle of a USB device Contour recognises ([DeviceIdentity]); null for any other device. */
        fun of(device: UsbDevice): DacHandle? = DeviceIdentity.target(device)?.let {
            DacHandle(device, it, "${device.deviceName}:${device.deviceId}:${device.vendorId}:${device.productId}")
        }
    }
}
