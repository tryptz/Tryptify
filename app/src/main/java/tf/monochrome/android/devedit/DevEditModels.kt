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
    // Version of the *bundled* default. When the asset's version is newer than
    // what a device last force-applied, the bundled layout overwrites local edits
    // once (see DevEditRepository.load). Bump this in the asset to push a new
    // forced default to everyone. 0 = unversioned/local.
    val version: Int = 0,
    // Screen size (in dp) the layout was designed on. When > 0, element offsets
    // are scaled to the current screen relative to this, so the arrangement
    // holds across phone sizes. 0 = treat offsets as absolute dp (no scaling).
    val refWidthDp: Float = 0f,
    val refHeightDp: Float = 0f,
)

@Serializable
data class ElementOverride(
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scale: Float = 1f,
    val hidden: Boolean = false,
)

@Serializable
data class FreeformBox(
    val id: String,
    val x: Float = 24f,
    val y: Float = 120f,
    // Square by default (1:1) so movable highlights match album-art-style ratios.
    val width: Float = 140f,
    val height: Float = 140f,
    val label: String = "Box",
    val colorArgb: Long = 0x66448AFFL,
)
