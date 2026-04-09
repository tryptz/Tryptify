package tf.monochrome.android.ui.mixer.canvas

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.rememberTextMeasurer
import tf.monochrome.android.ui.mixer.canvas.CanvasRenderer.drawGrid
import tf.monochrome.android.ui.mixer.canvas.connection.ConnectionAnimator.drawConnectionPulse
import tf.monochrome.android.ui.mixer.canvas.connection.SplineRenderer.drawConnectionSpline
import tf.monochrome.android.ui.mixer.canvas.model.CanvasOffset
import tf.monochrome.android.ui.mixer.canvas.model.CanvasState
import tf.monochrome.android.ui.mixer.canvas.model.DspNode
import tf.monochrome.android.ui.mixer.canvas.model.NodeColorScheme
import tf.monochrome.android.ui.mixer.canvas.model.NodeId
import tf.monochrome.android.ui.mixer.canvas.node.drawBusInputNode
import tf.monochrome.android.ui.mixer.canvas.node.drawMasterBusNode
import tf.monochrome.android.ui.mixer.canvas.node.drawOutputNode
import tf.monochrome.android.ui.mixer.canvas.node.drawPluginNode
import tf.monochrome.android.ui.mixer.canvas.node.inputPortPosition
import tf.monochrome.android.ui.mixer.canvas.node.outputPortPosition
import kotlin.math.abs

/**
 * Node-based DSP workflow studio canvas.
 *
 * Gesture model:
 *   - Single finger on node → drag node (wires follow automatically)
 *   - Single finger on empty space → pan canvas
 *   - Two fingers → pinch-zoom + pan
 *   - Tap node → select
 *   - Double-tap plugin → open editor
 *   - Long-press plugin → delete mode
 *
 * Wires are always fixed connections between nodes — never free-flying.
 */
@Composable
fun DspCanvas(
    state: CanvasState,
    dspEnabled: Boolean,
    audioAmplitude: Float,
    onViewportPan: (Offset) -> Unit,
    onViewportZoom: (Float, Offset) -> Unit,
    onNodeSelected: (NodeId?) -> Unit,
    onNodeDragStart: (NodeId) -> Unit,
    onNodeDrag: (NodeId, CanvasOffset) -> Unit,
    onNodeDragEnd: (NodeId) -> Unit,
    onNodeDoubleTap: (NodeId) -> Unit,
    onNodeLongPress: (NodeId) -> Unit,
    onDeleteConfirmed: (NodeId) -> Unit,
    onDeleteCancelled: () -> Unit,
    onCanvasTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val haptic = LocalHapticFeedback.current

    // Keep fresh references to state and callbacks without restarting pointerInput
    val currentState by rememberUpdatedState(state)
    val currentOnViewportPan by rememberUpdatedState(onViewportPan)
    val currentOnViewportZoom by rememberUpdatedState(onViewportZoom)
    val currentOnNodeSelected by rememberUpdatedState(onNodeSelected)
    val currentOnNodeDragStart by rememberUpdatedState(onNodeDragStart)
    val currentOnNodeDrag by rememberUpdatedState(onNodeDrag)
    val currentOnNodeDragEnd by rememberUpdatedState(onNodeDragEnd)
    val currentOnNodeDoubleTap by rememberUpdatedState(onNodeDoubleTap)
    val currentOnNodeLongPress by rememberUpdatedState(onNodeLongPress)
    val currentOnDeleteConfirmed by rememberUpdatedState(onDeleteConfirmed)
    val currentOnDeleteCancelled by rememberUpdatedState(onDeleteCancelled)
    val currentOnCanvasTap by rememberUpdatedState(onCanvasTap)

    val infiniteTransition = rememberInfiniteTransition(label = "connectionPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseProgress"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .background(NodeColorScheme.CanvasBackground)
            // Single unified gesture handler for all touch interactions
            .pointerInput(Unit) {
                awaitEachGesture {
                    val firstDown = awaitFirstDown(requireUnconsumed = false)
                    val startPos = firstDown.position
                    val startTime = firstDown.uptimeMillis

                    // Hit test at touch down
                    val hitNode = CanvasGestureHandler.hitTestNode(startPos, currentState)

                    var draggingNode: NodeId? = null
                    var totalDrag = Offset.Zero
                    var isMultiTouch = false
                    var longPressTriggered = false

                    // Start node drag if we hit a node
                    if (hitNode != null) {
                        draggingNode = hitNode
                        currentOnNodeDragStart(hitNode)
                    }

                    // Process subsequent events
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        val activePointers = event.changes.filter { it.pressed }

                        if (activePointers.isEmpty()) {
                            // All pointers up — determine what happened
                            if (draggingNode != null) {
                                currentOnNodeDragEnd(draggingNode!!)
                            }

                            val dragDist = totalDrag.getDistance()
                            val elapsed = event.changes.firstOrNull()?.uptimeMillis?.minus(startTime) ?: 0L

                            // Classify as tap if minimal movement and short duration
                            if (dragDist < 15f && elapsed < 300L && !longPressTriggered) {
                                // Check delete button first
                                val deleteHit = CanvasGestureHandler.hitTestDeleteButton(startPos, currentState)
                                if (deleteHit != null) {
                                    currentOnDeleteConfirmed(deleteHit)
                                } else if (currentState.deleteTargetId != null) {
                                    currentOnDeleteCancelled()
                                } else if (hitNode != null) {
                                    currentOnNodeSelected(hitNode)
                                } else {
                                    currentOnNodeSelected(null)
                                    currentOnCanvasTap()
                                }
                            }
                            break
                        }

                        if (activePointers.size >= 2) {
                            // ── Multi-touch: zoom + pan ────────────────────
                            isMultiTouch = true
                            if (draggingNode != null) {
                                currentOnNodeDragEnd(draggingNode!!)
                                draggingNode = null
                            }

                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid(useCurrent = true)

                            if (zoom != 1f) currentOnViewportZoom(zoom, centroid)
                            if (pan != Offset.Zero) currentOnViewportPan(pan)

                            event.changes.forEach { it.consume() }
                        } else {
                            // ── Single finger ──────────────────────────────
                            val change = activePointers.first()
                            val delta = change.positionChange()
                            val elapsed = change.uptimeMillis - startTime

                            // Long press detection (500ms hold with minimal movement)
                            if (!longPressTriggered && elapsed > 500L && totalDrag.getDistance() < 15f) {
                                longPressTriggered = true
                                if (hitNode != null) {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    currentOnNodeLongPress(hitNode)
                                }
                            }

                            if (delta != Offset.Zero && !longPressTriggered) {
                                totalDrag += delta

                                if (draggingNode != null && !isMultiTouch) {
                                    // Drag node — wires follow automatically
                                    val canvasDelta = CanvasOffset(
                                        delta.x / currentState.viewportScale,
                                        delta.y / currentState.viewportScale
                                    )
                                    currentOnNodeDrag(draggingNode!!, canvasDelta)
                                } else if (draggingNode == null) {
                                    // Pan canvas
                                    currentOnViewportPan(delta)
                                }
                                change.consume()
                            }
                        }
                    }
                }
            }
            // Double-tap detection (separate because awaitEachGesture can't detect it)
            .pointerInput(Unit) {
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val upEvent = awaitPointerEvent()
                    val change = upEvent.changes.firstOrNull() ?: return@awaitEachGesture
                    if (!change.pressed) {
                        val dragDist = (change.position - down.position).getDistance()
                        val elapsed = change.uptimeMillis - down.uptimeMillis
                        if (dragDist < 15f && elapsed < 300L) {
                            val now = change.uptimeMillis
                            val distFromLast = (down.position - lastTapPos).getDistance()
                            if (now - lastTapTime < 400L && distFromLast < 40f) {
                                // Double tap detected
                                val hitNode = CanvasGestureHandler.hitTestNode(down.position, currentState)
                                if (hitNode != null) {
                                    currentOnNodeDoubleTap(hitNode)
                                }
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                lastTapPos = down.position
                            }
                        }
                    }
                }
            }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val scale = state.viewportScale
        val offsetX = state.viewportOffset.x
        val offsetY = state.viewportOffset.y

        // ── Layer 0: Grid (drawn in screen space) ───────────────────────
        drawGrid(state, canvasWidth, canvasHeight)

        // ── Apply viewport offset so nodes and wires share the same transform.
        // Node renderers already multiply by scale, so we only translate by offset.
        drawContext.transform.translate(offsetX, offsetY)

        // Port positions in scaled canvas space (matching how nodes render)
        fun portToScaled(port: Offset) = Offset(port.x * scale, port.y * scale)

        // ── Layer 1: Connection wires (always fixed to nodes) ───────────
        for (connection in state.connections) {
            val fromNode = state.nodes[connection.fromId] ?: continue
            val toNode = state.nodes[connection.toId] ?: continue

            val fromPort = portToScaled(outputPortPosition(fromNode))
            val toPort = when (toNode) {
                is DspNode.BusMaster -> portToScaled(inputPortPosition(toNode, connection.busIndex))
                else -> portToScaled(inputPortPosition(toNode))
            }

            val color = NodeColorScheme.connectionColor(connection.busIndex, fromNode)
            drawConnectionSpline(fromPort, toPort, color, scale)

            if (dspEnabled) {
                drawConnectionPulse(
                    from = fromPort,
                    to = toPort,
                    color = color,
                    progress = pulseProgress,
                    scale = scale,
                    amplitude = audioAmplitude,
                    isActive = true
                )
            }
        }

        // ── Layer 2: Nodes ──────────────────────────────────────────────
        for ((id, node) in state.nodes) {
            if (!CanvasGestureHandler.isNodeVisible(node, state, canvasWidth, canvasHeight)) {
                continue
            }

            val isSelected = id == state.selectedNodeId
            val isDeleteTarget = id == state.deleteTargetId

            when (node) {
                is DspNode.BusInput -> drawBusInputNode(
                    node = node, isSelected = isSelected,
                    textMeasurer = textMeasurer, scale = scale
                )
                is DspNode.Plugin -> drawPluginNode(
                    node = node, isSelected = isSelected,
                    isDeleteTarget = isDeleteTarget,
                    textMeasurer = textMeasurer, scale = scale, vizData = null
                )
                is DspNode.BusMaster -> drawMasterBusNode(
                    node = node, isSelected = isSelected,
                    textMeasurer = textMeasurer, scale = scale
                )
                is DspNode.Output -> drawOutputNode(
                    node = node, isSelected = isSelected,
                    textMeasurer = textMeasurer, scale = scale
                )
            }
        }
    }
}
