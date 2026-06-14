package tf.monochrome.android.devedit

import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditOff
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

/** Controller for DevEdit, provided at the app root. Null when unavailable. */
val LocalDevEditController = staticCompositionLocalOf<DevEditController?> { null }

/** Id of the screen currently hosting DevEditable elements. */
val LocalDevEditScreenId = compositionLocalOf { "" }

private val DevAccent = Color(0xFF8ED081)

/**
 * App-root wrapper. Provides [LocalDevEditController] to the whole tree and
 * overlays the floating DevEdit toolbar while edit mode is on.
 */
@Composable
fun DevEditRoot(controller: DevEditController, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDevEditController provides controller) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            val master by controller.masterEnabled.collectAsState()
            val editing by controller.editingScreens.collectAsState()
            val current by controller.currentScreen.collectAsState()
            if (master && current in editing) {
                DevEditToolbar(
                    controller = controller,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

@Composable
private fun DevEditToolbar(controller: DevEditController, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val screen by controller.currentScreen.collectAsState()
    Surface(
        modifier = modifier.statusBarsPadding().padding(8.dp),
        shape = RoundedCornerShape(999.dp),
        color = Color.Black.copy(alpha = 0.82f),
        contentColor = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, DevAccent.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "DevEdit · ${screen.ifEmpty { "—" }}",
                style = MaterialTheme.typography.labelMedium,
                color = DevAccent,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            IconButton(onClick = { if (screen.isNotEmpty()) controller.addBox(screen) }) {
                Icon(Icons.Default.Add, contentDescription = "Add box", tint = Color.White)
            }
            IconButton(onClick = {
                controller.save()
                Toast.makeText(context, "Layout saved", Toast.LENGTH_SHORT).show()
            }) {
                Icon(Icons.Default.Save, contentDescription = "Save layout", tint = Color.White)
            }
            IconButton(onClick = { if (screen.isNotEmpty()) controller.resetScreen(screen) }) {
                Icon(Icons.Default.RestartAlt, contentDescription = "Reset screen", tint = Color.White)
            }
            IconButton(onClick = { if (screen.isNotEmpty()) controller.toggleScreenEditing(screen) }) {
                Icon(Icons.Default.Close, contentDescription = "Stop editing screen", tint = Color.White)
            }
        }
    }
}

/**
 * Marks the start of a DevEdit-aware screen. Registers [screenId] as the active
 * screen (so the toolbar's add/reset target it) and renders the freeform box
 * layer over [content] while editing.
 */
@Composable
fun DevEditScreen(screenId: String, content: @Composable () -> Unit) {
    val controller = LocalDevEditController.current
    if (controller != null) {
        DisposableEffect(screenId) {
            controller.setCurrentScreen(screenId)
            onDispose { }
        }
    }
    CompositionLocalProvider(LocalDevEditScreenId provides screenId) {
        Box(modifier = Modifier.fillMaxSize()) {
            content()
            if (controller != null) {
                val master by controller.masterEnabled.collectAsState()
                val editing by controller.editingScreens.collectAsState()
                val isEditing = master && screenId in editing
                if (isEditing) FreeformBoxLayer(controller, screenId)
                if (master) {
                    DevEditScreenButton(
                        editing = isEditing,
                        onClick = { controller.toggleScreenEditing(screenId) },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }
        }
    }
}

/**
 * The per-screen "edit this screen" button. Rendered by [DevEditScreen] on every
 * DevEdit-aware screen whenever the master unlock is on. Toggling it puts just
 * this screen into edit mode, highlighting every editable parameter.
 */
@Composable
private fun DevEditScreenButton(
    editing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.padding(8.dp),
        shape = RoundedCornerShape(999.dp),
        color = if (editing) DevAccent else Color.Black.copy(alpha = 0.72f),
        contentColor = if (editing) Color.Black else DevAccent,
        shadowElevation = 6.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, DevAccent.copy(alpha = 0.6f)),
    ) {
        Row(
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = if (editing) Icons.Default.EditOff else Icons.Default.Edit,
                contentDescription = if (editing) "Stop editing" else "Edit layout",
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = if (editing) "Editing" else "Edit",
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * Wraps a piece of UI so it can be dragged, hidden, and persisted in DevEdit
 * mode. Outside edit mode it just applies the saved offset (or removes the
 * element entirely if hidden), so production rendering cost is negligible.
 */
@Composable
fun DevEditable(
    elementId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val controller = LocalDevEditController.current
    val screen = LocalDevEditScreenId.current
    if (controller == null || screen.isEmpty()) {
        Box(modifier = modifier) { content() }
        return
    }

    val layout by controller.layout.collectAsState()
    val master by controller.masterEnabled.collectAsState()
    val editing by controller.editingScreens.collectAsState()
    val active = master && screen in editing
    val override = layout.elements["$screen/$elementId"] ?: ElementOverride()

    if (override.hidden && !active) return

    val offsetMod = Modifier.offset {
        IntOffset(override.offsetX.dp.roundToPx(), override.offsetY.dp.roundToPx())
    }

    if (!active) {
        Box(modifier = modifier.then(offsetMod)) { content() }
        return
    }

    val glowTransition = rememberInfiniteTransition(label = "devGlow")
    val glow by glowTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "devGlowAlpha",
    )
    val density = LocalDensity.current
    Box(
        modifier = modifier
            .then(offsetMod)
            .background(DevAccent.copy(alpha = glow * 0.10f), RoundedCornerShape(6.dp))
            .border(2.dp, DevAccent.copy(alpha = glow), RoundedCornerShape(6.dp))
            .pointerInput(elementId) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val dx = with(density) { drag.x.toDp().value }
                    val dy = with(density) { drag.y.toDp().value }
                    controller.moveElement(screen, elementId, dx, dy)
                }
            },
    ) {
        Box(modifier = if (override.hidden) Modifier.alpha(0.35f) else Modifier) { content() }
        Surface(
            modifier = Modifier.align(Alignment.TopStart),
            shape = RoundedCornerShape(bottomEnd = 8.dp),
            color = Color.Black.copy(alpha = 0.7f),
            contentColor = Color.White,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = elementId,
                    style = MaterialTheme.typography.labelSmall,
                    color = DevAccent,
                    modifier = Modifier.padding(start = 8.dp),
                )
                IconButton(
                    onClick = { controller.setHidden(screen, elementId, !override.hidden) },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (override.hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (override.hidden) "Show" else "Hide",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun FreeformBoxLayer(controller: DevEditController, screen: String) {
    val layout by controller.layout.collectAsState()
    val boxes = layout.boxes[screen] ?: emptyList()
    Box(modifier = Modifier.fillMaxSize()) {
        boxes.forEach { box ->
            FreeformBoxView(controller, screen, box)
        }
    }
}

@Composable
private fun FreeformBoxView(controller: DevEditController, screen: String, box: FreeformBox) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset(box.x.dp, box.y.dp)
            .size(box.width.dp, box.height.dp)
            .background(Color(box.colorArgb), RoundedCornerShape(8.dp))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .pointerInput(box.id) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val dx = with(density) { drag.x.toDp().value }
                    val dy = with(density) { drag.y.toDp().value }
                    controller.moveBox(screen, box.id, dx, dy)
                }
            },
    ) {
        Text(
            text = box.label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier.align(Alignment.Center),
        )
        IconButton(
            onClick = { controller.removeBox(screen, box.id) },
            modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
        ) {
            Icon(Icons.Default.Delete, contentDescription = "Delete box", tint = Color.White, modifier = Modifier.size(16.dp))
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(24.dp)
                .pointerInput(box.id) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        val dw = with(density) { drag.x.toDp().value }
                        val dh = with(density) { drag.y.toDp().value }
                        controller.resizeBox(screen, box.id, dw, dh)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.OpenInFull, contentDescription = "Resize box", tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}
