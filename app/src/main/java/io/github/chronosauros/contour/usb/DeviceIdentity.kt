// Tryptify's own, in the place of Contour's file of this name (which
// tools/contour/vendor.sh leaves out): the one place a USB device becomes one
// of Contour's DACs, as Contour's USB files ask for it (DacHandle,
// HidTransport). Contour is asked first. A device it does not know may be one
// the app drives with a known DAC's commands: DacEqUntested says which.
package io.github.chronosauros.contour.usb

import android.hardware.usb.UsbDevice
import io.github.chronosauros.contour.core.DeviceTarget
import tf.monochrome.android.audio.eq.dac.DacEqUntested

internal object DeviceIdentity {
    fun target(device: UsbDevice): DeviceTarget? =
        DacEqUntested.target(device.vendorId, device.productId, device.productName)
}
