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

    /** Persist the current layout to internal storage. */
    fun save() {
        repository.save(_layout.value)
    }

    private inline fun update(block: (DevEditLayout) -> DevEditLayout) {
        _layout.value = block(_layout.value)
    }
}
