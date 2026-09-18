package tf.monochrome.ios

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Root composable for the iOS shell (M1 milestone).
 *
 * This is the bootstrap target for the CMP port. The full Android UI in
 * `app/src/main/java/.../ui` migrates here incrementally — navigation first
 * (it is already multiplatform-ready androidx.navigation), then data, then
 * playback. Until each layer lands, this renders a boot screen instead of
 * crashing on missing platform plumbing.
 */
@Composable
fun MonochromeApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "Monochrome — iOS port (M1 shell)")
            }
        }
    }
}
