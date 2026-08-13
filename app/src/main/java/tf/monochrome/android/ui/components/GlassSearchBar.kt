package tf.monochrome.android.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass

/**
 * The app's search bar: a floating pane of glass over whatever is behind it.
 *
 * There were six different ones before this — a Material `TextField` with its
 * container knocked out on Home, another with a pill of glass under it in
 * Discover, plain outlined fields in the library and the headphone picker, and
 * the globe's own hand-built bar, which was the only one that actually looked
 * like the rest of the app. They disagreed on height, on shape, on whether
 * there was a clear button, and on whether the page behind them showed through
 * at all.
 *
 * This is the globe's, made shared. It frosts the app's own backdrop layer
 * rather than a per-screen one, which is what let the glass panels out of the
 * two map screens that happened to have a haze source.
 *
 * [content] hangs under the field inside the same pane — suggestion pills,
 * recent searches, filter chips — so a bar with results under it is one sheet
 * of glass rather than a bar with a second thing floating below it.
 */
@Composable
fun GlassSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /**
     * What to frost. Null means the caller has no backdrop of its own, and the
     * bar takes the plain translucent glass — deliberately *not* the app-wide
     * source, which most bars are drawn inside and so cannot sample.
     */
    hazeState: HazeState? = null,
    /**
     * The material. The mini player's settings by default, not the player's:
     * this bar floats over an ordinary screen, usually with that very bar on it,
     * and the two should be cut from the same glass. Reading LocalPlayerGlass
     * here gave the untouched defaults on every screen the nav host did not
     * explicitly wrap, so nothing anyone tuned in the Studio ever reached them.
     */
    glass: PlayerGlassSettings = LocalMiniPlayerGlass.current,
    /** Focus and open the keyboard on first composition. For bars that appear on demand. */
    autoFocus: Boolean = false,
    onSubmit: () -> Unit = {},
    /**
     * What the trailing button does when the field is already empty. A bar that
     * can be dismissed closes; one that is a permanent part of its screen has
     * nothing to do and hides the button instead.
     */
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }

    LaunchedEffect(autoFocus) {
        if (autoFocus) runCatching { focus.requestFocus() }
    }

    GlassPanel(
        hazeState = hazeState,
        glass = glass,
        modifier = modifier,
        // Anchored at the top of its screen, so the system bar it should hold
        // clear of is the status bar, which its caller already handles.
        avoidNavigationBar = false,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSubmit()
                            keyboard?.hide()
                        },
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    // BasicTextField rather than a Material one because those
                    // bring a container, an indicator and their own vertical
                    // padding, all of which have to be knocked back out to sit
                    // on glass — and the knocking out was where the six bars
                    // drifted apart from each other.
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        inner()
                    },
                )
                if (query.isNotEmpty() || onClose != null) {
                    IconButton(
                        onClick = {
                            if (query.isEmpty()) onClose?.invoke() else onQueryChange("")
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = if (query.isEmpty()) "Close search" else "Clear",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}
