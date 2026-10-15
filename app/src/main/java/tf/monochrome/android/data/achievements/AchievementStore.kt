package tf.monochrome.android.data.achievements

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.achievementDataStore: DataStore<Preferences> by preferencesDataStore(name = "achievements")

/** What the listener has tried, as the achievements count it. */
data class FeatureLog(
    /** Settings page ids, the English ones SettingsScreen keys on. */
    val settingsTabs: Set<String> = emptySet(),
    /** SnapinType names. */
    val plugins: Set<String> = emptySet(),
    /** ISO 3166 alpha-2 codes, upper case. */
    val radioCountries: Set<String> = emptySet(),
    /** Lyrics FX preset names. */
    val lyricsPresets: Set<String> = emptySet(),
    val autoEqRuns: Int = 0,
    /** The most plugins one effect chain has held. */
    val longestChain: Int = 0,
    /** When each achievement was first seen complete, by its name. */
    val unlockedAt: Map<String, Long> = emptyMap(),
)

/**
 * The local log behind the achievements: which settings tabs, mixer plugins,
 * radio countries and Lyrics FX presets have been tried.
 *
 * A DataStore of its own rather than more keys in PreferencesManager: nothing
 * here is a setting, none of it syncs, and clearing settings should not take
 * someone's trophies with it.
 *
 * The record calls return at once and write in the background, so a call site
 * is one line in whatever function performs the action, suspend or not.
 */
@Singleton
class AchievementStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.achievementDataStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val log: Flow<FeatureLog> = store.data.map { p ->
        FeatureLog(
            settingsTabs = p[SETTINGS_TABS].orEmpty(),
            plugins = p[PLUGINS].orEmpty(),
            radioCountries = p[RADIO_COUNTRIES].orEmpty(),
            lyricsPresets = p[LYRICS_PRESETS].orEmpty(),
            autoEqRuns = p[AUTO_EQ_RUNS] ?: 0,
            longestChain = p[LONGEST_CHAIN] ?: 0,
            unlockedAt = decodeUnlocked(p[UNLOCKED].orEmpty()),
        )
    }

    fun settingsTabOpened(tabId: String) = add(SETTINGS_TABS, tabId)

    fun pluginInserted(typeName: String, chainSize: Int) {
        add(PLUGINS, typeName)
        scope.launch { store.edit { it[LONGEST_CHAIN] = maxOf(it[LONGEST_CHAIN] ?: 0, chainSize) } }
    }

    fun radioCountryHeard(countryCode: String?) {
        val code = countryCode?.trim()?.uppercase()
        if (code != null && code.length == 2) add(RADIO_COUNTRIES, code)
    }

    fun lyricsPresetApplied(name: String) {
        if (name.isNotBlank()) add(LYRICS_PRESETS, name)
    }

    fun autoEqApplied() {
        scope.launch { store.edit { it[AUTO_EQ_RUNS] = (it[AUTO_EQ_RUNS] ?: 0) + 1 } }
    }

    /** Stamps achievements seen complete for the first time; earlier stamps are kept. */
    suspend fun markUnlocked(firstSeen: Map<String, Long>) {
        if (firstSeen.isEmpty()) return
        store.edit { p ->
            val known = decodeUnlocked(p[UNLOCKED].orEmpty())
            p[UNLOCKED] = encodeUnlocked(firstSeen + known)
        }
    }

    private fun add(key: Preferences.Key<Set<String>>, value: String) {
        scope.launch { store.edit { it[key] = it[key].orEmpty() + value } }
    }

    companion object {
        private val SETTINGS_TABS = stringSetPreferencesKey("settings_tabs")
        private val PLUGINS = stringSetPreferencesKey("mixer_plugins")
        private val RADIO_COUNTRIES = stringSetPreferencesKey("radio_countries")
        private val LYRICS_PRESETS = stringSetPreferencesKey("lyrics_presets")
        private val AUTO_EQ_RUNS = intPreferencesKey("auto_eq_runs")
        private val LONGEST_CHAIN = intPreferencesKey("longest_chain")
        private val UNLOCKED = stringSetPreferencesKey("unlocked_at")

        /** "NAME=epochMillis" entries; a malformed one is dropped rather than failing the read. */
        internal fun decodeUnlocked(entries: Set<String>): Map<String, Long> =
            entries.mapNotNull { e ->
                val i = e.lastIndexOf('=')
                if (i <= 0) null else e.substring(i + 1).toLongOrNull()?.let { e.substring(0, i) to it }
            }.toMap()

        internal fun encodeUnlocked(map: Map<String, Long>): Set<String> =
            map.entries.mapTo(HashSet()) { "${it.key}=${it.value}" }
    }
}
