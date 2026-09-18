package tf.monochrome.ios

import platform.UIKit.UIDevice

actual fun platformName(): String =
    "iOS ${UIDevice.currentDevice.systemVersion}"
