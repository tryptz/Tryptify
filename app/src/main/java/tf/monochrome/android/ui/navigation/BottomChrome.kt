package tf.monochrome.android.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the floating mini player needs at the bottom of a screen.
 *
 * The nav host used to reserve this *outside* every detail screen, which
 * letterboxed them: the strip behind the bar was flat theme background, so the
 * bar's glass had nothing but a solid colour to lens and read as an opaque
 * container however transparent it was set.
 *
 * The reserve now belongs to each screen's own scroll, where it is scrollable.
 * Content passes behind the glass — which is the whole point of glass — and the
 * last row still comes clear of the bar. Add it to a list's bottom
 * `contentPadding`, or after a `verticalScroll` where it becomes trailing space
 * inside the scrollable content.
 *
 * Zero when nothing is playing, so no screen carries dead space for a bar that
 * is not there.
 */
val LocalMiniPlayerInset = compositionLocalOf<Dp> { 0.dp }

/**
 * The app's one backdrop layer, for anything that wants to frost what is
 * behind it.
 *
 * The nav host already marks the whole content layer as a haze source — it is
 * what the mini player blurs. Publishing it here means a floating bar on any
 * screen can lens the real page under it without that screen having to stand up
 * a source of its own, which is the plumbing that kept glass panels pinned to
 * the two map screens that happened to have one.
 *
 * Null outside the nav host: previews and tests get a bar that is still glass,
 * just not a blurring one.
 */
val LocalAppHaze = compositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/**
 * The glass the app's own chrome is made of — the mini player's settings.
 *
 * There are two tunable glass materials in the app: the player's, for the
 * transport and its panels, and the mini player's, for the bar that floats over
 * every screen. Everything else that is a floating sheet of glass on an ordinary
 * screen belongs to the second one — it sits beside the mini player, often on
 * the same page, and a search bar built from the defaults while the bar beside
 * it carries the listener's tuned settings is two materials pretending to be
 * one.
 *
 * [tf.monochrome.android.ui.player.LocalPlayerGlass] could not do this job: the
 * player route overrides it with the *player's* settings for its own chrome,
 * which is right there and wrong everywhere else, and outside the few places the
 * nav host provided it, it fell back to defaults that no one chose.
 */
val LocalMiniPlayerGlass = compositionLocalOf {
    tf.monochrome.android.domain.model.PlayerGlassSettings()
}
