package tf.monochrome.android.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The room database lists only rooms: a real room heard by a head. Which
 * files those are was measured on every file of the collection's room folders
 * (2026-11-18); the names here are the collection's own.
 */
class RoomFilesTest {
    @Test
    fun `only the folders with rooms a phone can take are listed`() {
        assertEquals(setOf("oldenburg", "thk"), SofaHrtfApi.ROOM_FOLDERS)
    }

    @Test
    fun `a head in a room is a room, an array or an anechoic set is not`() {
        // thk: a KU100 in a control room and two broadcast studios.
        assertTrue(SofaHrtfApi.isRoom("thk", "BRIR_CR1_KU_MICS_L"))
        assertTrue(SofaHrtfApi.isRoom("thk", "BRIR_SBS_KU_ROTM_PAC"))
        // Its sphere arrays, and its free-field HRIR sets.
        assertFalse(SofaHrtfApi.isRoom("thk", "DRIR_CR1_VSA_50RS_L"))
        assertFalse(SofaHrtfApi.isRoom("thk", "HRIR_L2702"))
        assertFalse(SofaHrtfApi.isRoom("thk", "KU100_L2702"))
        // oldenburg: the office, not the anechoic chamber (the owner's screenshot).
        assertTrue(SofaHrtfApi.isRoom("oldenburg", "OfficeII"))
        assertFalse(SofaHrtfApi.isRoom("oldenburg", "Kayser2009_Anechoic"))
        // Arrays, whatever their file is called.
        assertFalse(SofaHrtfApi.isRoom("bbc maida vale", "MV5_AS1_Eigen_S_PA-3A"))
        assertFalse(SofaHrtfApi.isRoom("dechorate", "room000000_src9_arr2"))
        assertFalse(SofaHrtfApi.isRoom("tuburo", "RIR_AddAbsorbers_ArrayCentre"))
    }

    @Test
    fun `an index's size is read in bytes`() {
        assertEquals(12345L, SofaHrtfApi.sizeBytes("12345"))
        assertEquals(861L * 1024, SofaHrtfApi.sizeBytes("861K"))
        assertEquals((9.6 * 1024 * 1024).toLong(), SofaHrtfApi.sizeBytes("9.6M"))
        assertEquals((1.2 * 1024 * 1024 * 1024).toLong(), SofaHrtfApi.sizeBytes(" 1.2G "))
        assertEquals(null, SofaHrtfApi.sizeBytes("-"))
        assertEquals(null, SofaHrtfApi.sizeBytes(null))
        // The biggest room listed (thk, 332 MB) is under what a download may be; a 950 MB one is not.
        assertTrue(SofaHrtfApi.sizeBytes("332M")!! <= SofaHrtfApi.MAX_SOFA_FILE_BYTES)
        assertFalse(SofaHrtfApi.sizeBytes("950M")!! <= SofaHrtfApi.MAX_SOFA_FILE_BYTES)
    }
}
