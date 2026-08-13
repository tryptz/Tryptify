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
