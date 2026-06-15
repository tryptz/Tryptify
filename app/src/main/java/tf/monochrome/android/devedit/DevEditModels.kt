package tf.monochrome.android.devedit

import kotlinx.serialization.Serializable

/**
 * Persisted layout overrides for DevEdit mode. All positions and sizes are in
 * density-independent pixels (dp) so a saved layout survives across devices.
 *
 * - [elements] keyed by "screenId/elementId" — per-element drag offset + hide.
 * - [boxes] keyed by screenId — user-added freeform placeholder boxes.
 */
@Serializable
data class DevEditLayout(
    val elements: Map<String, ElementOverride> = emptyMap(),
    val boxes: Map<String, List<FreeformBox>> = emptyMap(),
    val snapToGrid: Boolean = false,
    val gridStep: Float = 16f,
)

@Serializable
data class ElementOverride(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val hidden: Boolean = false,
)

@Serializable
data class FreeformBox(
    val id: String,
    val x: Float = 24f,
    val y: Float = 120f,
    val width: Float = 160f,
    val height: Float = 72f,
    val label: String = "Box",
    val colorArgb: Long = 0x66448AFFL,
)
