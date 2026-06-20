package tf.monochrome.android.devedit

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for DevEdit mode. Holds the in-memory layout (loaded
 * from internal storage at construction) plus the transient edit-mode flag and
 * the currently visible screen. Mutations update memory immediately; [save]
 * persists the current layout to internal storage.
 */
@Singleton
class DevEditController @Inject constructor(
    private val repository: DevEditRepository,
) {
    // Master unlock, driven by the Settings toggle. When false there are no
    // edit buttons, overlays, or highlights anywhere in the app.
    private val _masterEnabled = MutableStateFlow(false)
    val masterEnabled: StateFlow<Boolean> = _masterEnabled.asStateFlow()

    // Screens whose per-screen edit button is currently toggled on. Transient
    // (not persisted) — only the layout itself is saved to internal storage.
    private val _editingScreens = MutableStateFlow<Set<String>>(emptySet())
    val editingScreens: StateFlow<Set<String>> = _editingScreens.asStateFlow()

    private val _layout = MutableStateFlow(repository.load())
    val layout: StateFlow<DevEditLayout> = _layout.asStateFlow()

    private val _currentScreen = MutableStateFlow("")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    // When on, dragging locks element offsets and freeform boxes to a grid.
    private val _snapToGrid = MutableStateFlow(_layout.value.snapToGrid)
    val snapToGrid: StateFlow<Boolean> = _snapToGrid.asStateFlow()

    val gridStep: Float get() = _layout.value.gridStep.takeIf { it > 0f } ?: 16f

    fun toggleSnapToGrid() {
        val value = !_snapToGrid.value
        _snapToGrid.value = value
        update { it.copy(snapToGrid = value) }
    }

    /** Round a dp value to the nearest grid step when snapping is on. */
    fun snap(value: Float): Float =
        if (_snapToGrid.value) Math.round(value / gridStep) * gridStep else value

    /** Snap a moved element's stored offset to the grid (no-op when snap off). */
    fun snapElementToGrid(screen: String, element: String) {
        val k = key(screen, element)
        val cur = _layout.value.elements[k] ?: return
        update {
            it.copy(elements = it.elements + (k to cur.copy(
                offsetX = snap(cur.offsetX),
                offsetY = snap(cur.offsetY),
            )))
        }
    }

    /** Snap an element's scale to fixed steps (no-op when snap off). */
    fun snapElementScaleToGrid(screen: String, element: String) {
        val k = key(screen, element)
        val cur = _layout.value.elements[k] ?: return
        update { it.copy(elements = it.elements + (k to cur.copy(scale = snapScale(cur.scale)))) }
    }

    /** Round a scale factor to [SCALE_STEP] increments when snapping is on. */
    fun snapScale(value: Float): Float =
        if (_snapToGrid.value) (Math.round(value / SCALE_STEP) * SCALE_STEP).coerceIn(0.3f, 3f) else value

    /** Snap a box's stored position and size to the grid (no-op when snap off). */
    fun snapBoxToGrid(screen: String, id: String) {
        val list = (_layout.value.boxes[screen] ?: return).map {
            if (it.id == id) it.copy(
                x = snap(it.x),
                y = snap(it.y),
                width = snap(it.width).coerceAtLeast(gridStep),
                height = snap(it.height).coerceAtLeast(gridStep),
            ) else it
        }
        update { it.copy(boxes = it.boxes + (screen to list)) }
    }

    fun setMasterEnabled(value: Boolean) {
        _masterEnabled.value = value
        if (!value) _editingScreens.value = emptySet()
    }

    /** Toggle active edit mode for a single screen (the per-screen button). */
    fun toggleScreenEditing(screen: String) {
        _editingScreens.value = if (screen in _editingScreens.value) {
            _editingScreens.value - screen
        } else {
            _editingScreens.value + screen
        }
    }

    fun setCurrentScreen(id: String) { _currentScreen.value = id }

    // Current screen size in dp (reported by DevEditRoot). Stamped into the
    // layout on save/export so it can be scaled to other phones on load.
    private var screenWidthDp = 0f
    private var screenHeightDp = 0f
    fun setScreenSize(widthDp: Float, heightDp: Float) {
        screenWidthDp = widthDp
        screenHeightDp = heightDp
    }

    /** Stamp the current screen size as the layout's design reference. */
    private fun withRef(layout: DevEditLayout): DevEditLayout =
        if (screenWidthDp > 0f && screenHeightDp > 0f) {
            layout.copy(refWidthDp = screenWidthDp, refHeightDp = screenHeightDp)
        } else layout

    private fun key(screen: String, element: String) = "$screen/$element"

    fun moveElement(screen: String, element: String, dx: Float, dy: Float) {
        val k = key(screen, element)
        val cur = _layout.value.elements[k] ?: ElementOverride()
        update {
            it.copy(
                elements = it.elements + (k to cur.copy(
                    offsetX = cur.offsetX + dx,
                    offsetY = cur.offsetY + dy,
                )),
            )
        }
    }

    fun scaleElement(screen: String, element: String, delta: Float) {
        val k = key(screen, element)
        val cur = _layout.value.elements[k] ?: ElementOverride()
        val next = (cur.scale + delta).coerceIn(0.3f, 3f)
        update { it.copy(elements = it.elements + (k to cur.copy(scale = next))) }
    }

    fun setHidden(screen: String, element: String, hidden: Boolean) {
        val k = key(screen, element)
        val cur = _layout.value.elements[k] ?: ElementOverride()
        update { it.copy(elements = it.elements + (k to cur.copy(hidden = hidden))) }
    }

    fun addBox(screen: String) {
        val box = FreeformBox(id = UUID.randomUUID().toString())
        val list = (_layout.value.boxes[screen] ?: emptyList()) + box
        update { it.copy(boxes = it.boxes + (screen to list)) }
    }

    fun moveBox(screen: String, id: String, dx: Float, dy: Float) {
        val list = (_layout.value.boxes[screen] ?: return).map {
            if (it.id == id) it.copy(x = it.x + dx, y = it.y + dy) else it
        }
        update { it.copy(boxes = it.boxes + (screen to list)) }
    }

    fun resizeBox(screen: String, id: String, dw: Float, dh: Float) {
        val list = (_layout.value.boxes[screen] ?: return).map {
            if (it.id == id) {
                it.copy(
                    width = (it.width + dw).coerceAtLeast(40f),
                    height = (it.height + dh).coerceAtLeast(40f),
                )
            } else it
        }
        update { it.copy(boxes = it.boxes + (screen to list)) }
    }

    fun removeBox(screen: String, id: String) {
        val list = (_layout.value.boxes[screen] ?: return).filterNot { it.id == id }
        update { it.copy(boxes = it.boxes + (screen to list)) }
    }

    /** Clear all overrides and freeform boxes for a single screen. */
    fun resetScreen(screen: String) {
        update { layout ->
            layout.copy(
                elements = layout.elements.filterKeys { !it.startsWith("$screen/") },
                boxes = layout.boxes - screen,
            )
        }
    }

    /** Persist the current layout (stamped with this screen's size) to storage. */
    fun save() {
        val stamped = withRef(_layout.value)
        _layout.value = stamped
        repository.save(stamped)
    }

    /** Current layout (stamped with this screen's size) serialized to JSON. */
    fun exportJson(): String = repository.exportJson(withRef(_layout.value))

    private inline fun update(block: (DevEditLayout) -> DevEditLayout) {
        _layout.value = block(_layout.value)
    }

    private companion object {
        const val SCALE_STEP = 0.1f
    }
}
