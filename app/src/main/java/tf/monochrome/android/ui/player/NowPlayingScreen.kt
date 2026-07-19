package tf.monochrome.android.ui.player

import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import tf.monochrome.android.ui.theme.DynamicColorScope

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
    // Dynamic (album-art) colours apply to the player + its lyrics only, never
    // the menus — so the full player and everything it hosts opt in here.
    DynamicColorScope {
        tf.monochrome.android.devedit.DevEditScreen(screenId = "player") {
            MainPlayerRoute(
                navController = navController,
                playerViewModel = playerViewModel,
            )
        }
    }
}
