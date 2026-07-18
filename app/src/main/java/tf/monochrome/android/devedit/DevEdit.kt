package tf.monochrome.android.devedit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * DevEdit (the in-app drag-to-arrange layout tool) has been removed. These two
 * functions remain only as thin pass-throughs so the many call sites across the
 * app keep compiling unchanged:
 *
 * - [DevEditScreen] renders its content directly.
 * - [DevEditable] just applies the modifier it was handed and renders its
 *   content — exactly what production rendering did when edit mode was off, so
 *   every screen's layout is identical to before.
 *
 * No controller, persistence, overlays or saved overrides remain; the arranged
 * layouts are now the hard-coded defaults.
 */
@Composable
fun DevEditScreen(screenId: String, content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize()) { content() }
}

@Composable
fun DevEditable(
    elementId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier = modifier) { content() }
}
