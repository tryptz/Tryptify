package tf.monochrome.android.data.preferences

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.preferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry's invariants, and the one that costs the most if it slips: a
 * flag's sync policy and the settings allow-list agreeing about that flag.
 *
 * They agree by construction — the allow-list folds in
 * [FeatureFlag.ACCOUNT_SCOPED_KEYS] — so these tests are here to catch someone
 * un-deriving it later, which is exactly how the two lists drifted apart in the
 * first place everywhere this pattern has been tried by hand.
 */
class FeatureFlagRegistryTest {

    @Test
    fun `every flag defaults off`() {
        // The flag-off branch is the shipped behaviour. A flag that defaults on
        // has graduated and should be deleted, not left switched on: whatever it
        // gates is then unconditional and the branch can go.
        FeatureFlag.ALL.forEach {
            assertFalse("${it.name} must default off until it graduates", it.default)
        }
    }

    @Test
    fun `flag keys are unique`() {
        val byKey = FeatureFlag.ALL.groupBy { it.key }.filterValues { it.size > 1 }
        assertTrue("keys claimed twice: ${byKey.keys}", byKey.isEmpty())
    }

    @Test
    fun `flag keys carry the flag prefix`() {
        // So a flag is recognisable in a settings dump or a synced blob without
        // having to look it up here.
        FeatureFlag.ALL.forEach {
            assertTrue(
                "${it.name} key '${it.key}' must start with '${FeatureFlag.KEY_PREFIX}'",
                it.key.startsWith(FeatureFlag.KEY_PREFIX),
            )
        }
    }

    @Test
    fun `flag keys do not collide with the settings allow-list`() {
        // A flag sharing a name with a setting would have the setting's value
        // read as its state, and the allow-list would export one as the other.
        val settingNames = PreferencesManager.SETTINGS_SYNC_KEYS
            .map { it.name }
            .toSet() - FeatureFlag.ACCOUNT_SCOPED_KEYS.map { it.name }.toSet()
        FeatureFlag.ALL.forEach {
            assertFalse("${it.key} collides with a settings key", it.key in settingNames)
        }
    }

    @Test
    fun `account-scoped flags are on the settings allow-list`() {
        FeatureFlag.ALL.filter { it.sync == FlagSync.ACCOUNT }.forEach {
            assertTrue(
                "${it.name} declares ACCOUNT but is not synced",
                it.preferenceKey in PreferencesManager.SETTINGS_SYNC_KEYS,
            )
        }
    }

    @Test
    fun `device-local flags are absent from the settings allow-list`() {
        // The one that actually bites: restoring a hardware-dependent flag onto
        // a device without that hardware enables a path that cannot engage there.
        FeatureFlag.ALL.filter { it.sync == FlagSync.DEVICE_LOCAL }.forEach {
            assertFalse(
                "${it.name} is device-local but would be synced",
                it.preferenceKey in PreferencesManager.SETTINGS_SYNC_KEYS,
            )
        }
    }

    @Test
    fun `the allow-list contains no unregistered flag keys`() {
        // The other direction: a key that looks like a flag but has no entry
        // here is one nothing can enumerate, reset, or report in a bundle.
        PreferencesManager.SETTINGS_SYNC_KEYS
            .map { it.name }
            .filter { it.startsWith(FeatureFlag.KEY_PREFIX) }
            .forEach { assertTrue("$it is synced but not registered", FeatureFlag.byKey(it) != null) }
    }

    @Test
    fun `every flag resolves in both states`() {
        FeatureFlag.ALL.forEach { flag ->
            assertFalse(
                "${flag.name} unset must read as its default",
                flag.resolve(preferencesOf()),
            )
            assertTrue(
                "${flag.name} stored true must read on",
                flag.resolve(mutablePreferencesOf(flag.preferenceKey to true)),
            )
            assertFalse(
                "${flag.name} stored false must read off",
                flag.resolve(mutablePreferencesOf(flag.preferenceKey to false)),
            )
        }
    }

    @Test
    fun `a flag reads only its own key`() {
        val other = booleanPreferencesKey("flag_not_a_real_flag")
        FeatureFlag.ALL.forEach { flag ->
            assertFalse(
                "${flag.name} must ignore an unrelated key",
                flag.resolve(mutablePreferencesOf(other to true)),
            )
        }
    }

    @Test
    fun `byKey round-trips and rejects unknown keys`() {
        FeatureFlag.ALL.forEach { assertSame(it, FeatureFlag.byKey(it.key)) }
        assertNull(FeatureFlag.byKey("flag_does_not_exist"))
        assertNull(FeatureFlag.byKey("wifi_quality"))
    }

    @Test
    fun `the registry is not empty`() {
        // Guards the tests above from passing vacuously if the registry is
        // emptied by a graduation pass.
        assertEquals(FeatureFlag.entries.size, FeatureFlag.ALL.size)
        assertTrue(FeatureFlag.ALL.isNotEmpty())
    }
}
