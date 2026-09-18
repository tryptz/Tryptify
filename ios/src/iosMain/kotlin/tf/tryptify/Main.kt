package tf.tryptify.ios

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * Swift entry point. Xcode's generated MainViewController calls this.
 * Keep this shim tiny — every real decision belongs in MonochromeApp or
 * deeper, so the shell stays compilable by Kotlin/Native without iOS
 * toolchain-side surprises.
 */
@Suppress("unused", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    MonochromeApp()
}
