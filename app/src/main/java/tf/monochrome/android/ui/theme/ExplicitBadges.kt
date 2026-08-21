package tf.monochrome.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * "Show Explicit Badges" (Settings › Appearance › Display), in the form the
 * track rows need it.
 *
 * Ambient rather than a parameter because the badge is drawn by [TrackItem],
 * which is reached from every list in the app and is passed a track and
 * nothing else. Threading a boolean down to it would mean touching every
 * screen that shows a song, for a preference none of them have an opinion
 * about.
 *
 * Dynamic, not `static`, for the same reason [LocalLowPerformance] is: it
 * flips while the app is running and every row has to redraw when it does.
 *
 * Defaults to true, so a composable rendered outside the provider (a preview,
 * a test) shows the badge, which is the behaviour every caller had before the
 * preference was connected to anything.
 */
val LocalShowExplicitBadges = compositionLocalOf { true }

/** Whether the 'E' badge should be drawn on explicit tracks. */
@Composable
@ReadOnlyComposable
fun showExplicitBadges(): Boolean = LocalShowExplicitBadges.current
