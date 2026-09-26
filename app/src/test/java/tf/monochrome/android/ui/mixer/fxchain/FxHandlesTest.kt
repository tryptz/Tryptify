package tf.monochrome.android.ui.mixer.fxchain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.audio.dsp.SnapinType
import tf.monochrome.android.ui.mixer.getParamDefs
import tf.monochrome.android.ui.mixer.parseEntry

/**
 * A graph handle is dragged by adding the finger's movement to the value's
 * position on its axis and mapping back. If the two directions disagree the
 * value creeps on its own while held; if a position lands off the panel the
 * handle cannot be grabbed. Both are held here, for every handle there is.
 */
class FxHandlesTest {

    private fun defaults(type: SnapinType): (Int) -> Float {
        val defs = getParamDefs(type)
        return { i -> defs.getOrNull(i)?.default ?: 0f }
    }

    @Test
    fun `every axis maps a value to the panel and back`() {
        for (type in SnapinType.values()) {
            val defs = getParamDefs(type)
            val p = defaults(type)
            for (handle in FxHandles.forType(type)) {
                for (axis in listOfNotNull(handle.x, handle.y)) {
                    val def = defs[axis.param]
                    for (k in 1..9) {
                        val v = def.min + (def.max - def.min) * k / 10f
                        val back = axis.fromFrac(axis.toFrac(v, p), p)
                        assertEquals("$type ${handle.label} ${def.name} at $v",
                            v, back, (def.max - def.min) * 1e-3f + 1e-3f)
                    }
                }
            }
        }
    }

    @Test
    fun `every handle starts on the panel`() {
        for (type in SnapinType.values()) {
            val p = defaults(type)
            for (handle in FxHandles.forType(type)) {
                val (x, y) = handle.position(p)
                assertTrue("$type ${handle.label} x=$x", x in -0.01f..1.01f)
                assertTrue("$type ${handle.label} y=$y", y in -0.01f..1.01f)
            }
        }
    }

    @Test
    fun `every graph has a handle, and every handle moves a real parameter`() {
        for (type in SnapinType.values()) {
            val handles = FxHandles.forType(type)
            assertTrue("$type has no handle", handles.isNotEmpty())
            val count = getParamDefs(type).size
            for (h in handles) {
                assertTrue("$type ${h.label} moves nothing", h.params.isNotEmpty())
                assertTrue("$type ${h.label} params ${h.params}", h.params.all { it in 0 until count })
            }
        }
        assertEquals(10, FxHandles.forType(SnapinType.EQ_10BAND).size)
    }

    @Test
    fun `typed values accept k and commas`() {
        assertEquals(1500f, parseEntry("1.5k")!!, 1e-3f)
        assertEquals(-3.5f, parseEntry(" -3,5 ")!!, 1e-6f)
        assertNull(parseEntry("loud"))
        assertNull(parseEntry(""))
    }
}
