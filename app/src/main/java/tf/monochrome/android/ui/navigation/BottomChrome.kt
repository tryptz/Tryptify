package tf.monochrome.android.ui.navigation

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the floating bottom chrome — the tab bar, and the mini player
 * stacked above it while something is loaded — needs at the bottom of a screen.
 *
 * Measured from where the reading screen ends. A pager page and a full-bleed
 * route run under the system navigation bar, so for them this includes it; a
 * pushed screen stops above it, so for it this does not. A screen that pads the
 * navigation bar itself as well must consume those insets first, or the bar is
 * counted twice. The value is the bar *expanded*: folded on scroll it is
 * shorter, and a padding that followed it would jolt the list mid-scroll.
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
 * Zero where no chrome is drawn — the player, the mixer, Oxford, car mode — so
 * no screen carries dead space for a bar that is not there.
 */
val LocalBottomChromeInset = compositionLocalOf<Dp> { 0.dp }

/**
 * A scrolling list's bottom padding: its last row clear of the floating chrome,
 * with a little air under it. What a page's list should use instead of a
 * hand-picked number — the old 80dp fell short of the system bar plus the mini
 * player on every pager page, which run under both.
 */
val bottomChromePadding: Dp
    @androidx.compose.runtime.Composable
    @androidx.compose.runtime.ReadOnlyComposable
    get() = LocalBottomChromeInset.current + 16.dp

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
 * The id of the track playing right now, so a row can show that it is the one.
 *
 * Published rather than passed because a song row appears in a dozen screens —
 * playlists, favourites, an album, a folder, search — and threading "is this
 * the playing one" through every one of their call sites would be a parameter
 * on every list in the app for a one-row highlight.
 *
 * It is the *legacy* id, because the queue holds `Track`s: `currentTrack.id`
 * compares directly, and a UnifiedTrack row asks its own `legacyId`.
 *
 * Read it inside the row. A track change then recomposes the rows and nothing
 * else, which is cheap at the rate tracks change.
 */
val LocalNowPlayingTrackId = compositionLocalOf<Long?> { null }

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
