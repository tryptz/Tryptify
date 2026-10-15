package tf.monochrome.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import tf.monochrome.android.data.preferences.RowBadges

/**
 * "Song row badges" (Settings › Appearance › Display), in the form the song
 * rows need it. Ambient for the reason [LocalShowExplicitBadges] is: every
 * list in the app draws a song row, and none of them has an opinion about
 * which marks it carries.
 *
 * Defaults to every badge shown, so a row rendered outside the provider (a
 * preview, a test) looks as every row did before the setting existed.
 */
val LocalRowBadges = compositionLocalOf { RowBadges() }

/** Which badges a song row draws. */
@Composable
@ReadOnlyComposable
fun rowBadges(): RowBadges = LocalRowBadges.current
