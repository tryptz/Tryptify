package tf.monochrome.android.visualizer

import android.app.ApplicationExitInfo
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetCrashGuardTest {

    @Test
    fun `reads the pid and path the bridge writes`() {
        val record = PresetCrashGuard.parseSentinel("4242\n/data/presets/Fractal/a - b.milk\n")
        assertEquals(4242, record?.pid)
        assertEquals("/data/presets/Fractal/a - b.milk", record?.presetPath)
    }

    @Test
    fun `a torn or empty sentinel is ignored, not blamed on a preset`() {
        assertNull(PresetCrashGuard.parseSentinel(null))
        assertNull(PresetCrashGuard.parseSentinel(""))
        assertNull(PresetCrashGuard.parseSentinel("4242\n"))
        assertNull(PresetCrashGuard.parseSentinel("not a pid\n/x.milk\n"))
    }

    @Test
    fun `native crashes and hangs flag the preset`() {
        assertTrue(PresetCrashGuard.isCrashExit(ApplicationExitInfo.REASON_CRASH_NATIVE, 34))
        assertTrue(PresetCrashGuard.isCrashExit(ApplicationExitInfo.REASON_ANR, 34))
    }

    @Test
    fun `ordinary deaths do not`() {
        for (reason in listOf(
            ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXIT_SELF,
            ApplicationExitInfo.REASON_SIGNALED,
            ApplicationExitInfo.REASON_OTHER,
            // A Java crash is the app's own bug; a preset cannot throw one.
            ApplicationExitInfo.REASON_CRASH,
        )) {
            assertFalse("reason $reason", PresetCrashGuard.isCrashExit(reason, 34))
        }
    }

    @Test
    fun `without an exit record the sentinel alone decides`() {
        assertTrue(PresetCrashGuard.isCrashExit(null, 34))
        // Android 10 and older keep no record at all.
        assertTrue(PresetCrashGuard.isCrashExit(ApplicationExitInfo.REASON_USER_REQUESTED, 29))
    }

    @Test
    fun `every shipped flag names a preset that ships`() {
        val root = File("src/main/projectm-assets/presets")
        assertTrue("preset pack missing at ${root.absolutePath}", root.isDirectory)
        val missing = KnownCrashPresets.paths.filterNot { File(root, it).isFile }
        assertTrue("flagged presets not in the pack: $missing", missing.isEmpty())
    }
}
