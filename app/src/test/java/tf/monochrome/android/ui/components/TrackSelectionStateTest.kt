package tf.monochrome.android.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The selection bar's "Select all": shown while a row on screen is not yet
 * picked, gone when every one is (or there are none), and adding the whole
 * list when tapped without dropping what was picked elsewhere.
 */
class TrackSelectionStateTest {

    private data class Row(val id: Long)

    @Test
    fun `nothing to select on an empty list`() {
        val selection = TrackSelectionState<Long>()
        assertNull(selection.selectAllOf(emptyList<Row>()) { it.id })
    }

    @Test
    fun `select all picks every row and then hides`() {
        val selection = TrackSelectionState<Long>()
        val rows = listOf(Row(1), Row(2), Row(3))
        selection.toggle(2)
        val selectAll = selection.selectAllOf(rows) { it.id }
        assertNotNull(selectAll)
        selectAll!!.invoke()
        assertEquals(setOf(1L, 2L, 3L), selection.selectedIds)
        assertNull(selection.selectAllOf(rows) { it.id })
    }

    @Test
    fun `select all keeps a pick from outside the list`() {
        val selection = TrackSelectionState<Long>()
        selection.toggle(9)
        selection.selectAllOf(listOf(Row(1), Row(2))) { it.id }!!.invoke()
        assertEquals(setOf(9L, 1L, 2L), selection.selectedIds)
    }
}
