package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.navigation.NavController

/**
 * Backwards-compatible entry point for the now-playing destination. The screen
 * itself was redesigned and split into [MainPlayerRoute] (state) and
 * [MainPlayerScreen] (layout); this wrapper keeps the navigation graph and any
 * existing callers working without change.
 */
@Composable
fun NowPlayingScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
) {
    tf.monochrome.android.devedit.DevEditScreen(screenId = "player") {
        MainPlayerRoute(
            navController = navController,
            playerViewModel = playerViewModel,
        )
    }
}
