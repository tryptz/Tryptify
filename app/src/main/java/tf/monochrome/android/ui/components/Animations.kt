package tf.monochrome.android.ui.components
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import tf.monochrome.android.ui.theme.reduceMotion

fun Modifier.bounceClick(
    scaleDown: Float = 0.95f,
    onClick: () -> Unit
) = composed {
    val interactionSource = remember { MutableInteractionSource() }

    // With animations off, drop the whole spring: no press state to collect, no
    // animation to run, and no graphicsLayer to allocate. This modifier is on
    // every card and lazy-list row in the app, so skipping the layer here is
    // worth more than the same skip anywhere else.
    if (reduceMotion()) {
        return@composed this.clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
    }

    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bounceScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick
        )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.bounceCombinedClick(
    scaleDown: Float = 0.95f,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit
) = composed {
    val interactionSource = remember { MutableInteractionSource() }

    if (reduceMotion()) {
        return@composed this.combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
    }

    val isPressed by interactionSource.collectIsPressedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isPressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "bounceScale"
    )

    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
}
