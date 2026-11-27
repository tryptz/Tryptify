package tf.monochrome.android.audio.eq.dac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DacEqCatalogTest {

    /** Gradle runs a module's tests from the module's folder. */
    private val records = sequenceOf("../third_party/contour/devices", "third_party/contour/devices")
        .map(::File).first { it.isDirectory }

    @Test
    fun `every device record of Contour's is listed, and nothing else`() {
        val there = records.listFiles { f -> f.extension == "json" }!!.map { it.nameWithoutExtension }.sorted()
        assertEquals(there, DacEqCatalog.RECORDS.sorted())
    }

    @Test
    fun `every listed record is a DAC Contour can drive`() {
        assertEquals(DacEqCatalog.RECORDS.size, DacEqCatalog.all.size)
        assertEquals(DacEqCatalog.all.size, DacEqCatalog.all.map { it.caps.name }.distinct().size)
        assertTrue(DacEqCatalog.all.all { it.caps.bands in 1..31 })
    }
}
