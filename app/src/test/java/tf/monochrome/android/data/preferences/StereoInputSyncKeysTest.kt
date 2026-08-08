package tf.monochrome.android.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings-sync allow-list is the boundary between "follows the user across
 * devices" and "stays on this handset". Stereo Input straddles it: the feature
 * switch, mode, trim and channel mode are preferences, but the selected input
 * device is an `AudioDeviceInfo.id` — assigned by the platform per boot and per
 * attach, so it means nothing on another device (or even after a replug) and
 * must never be synced.
 */
class StereoInputSyncKeysTest {

    private val syncedNames = PreferencesManager.SETTINGS_SYNC_KEYS.map { it.name }.toSet()

    @Test
    fun `stereo input preferences sync`() {
        listOf(
            "stereo_input_enabled",
            "stereo_input_mode",
            "stereo_input_trim_db",
            "stereo_input_channel_mode",
        ).forEach { key ->
            assertTrue("$key should be in SETTINGS_SYNC_KEYS", key in syncedNames)
        }
    }

    @Test
    fun `the selected input device id never leaves the handset`() {
        assertFalse(
            "stereo_input_device_id is hardware-local and must not sync",
            "stereo_input_device_id" in syncedNames,
        )
    }
}
