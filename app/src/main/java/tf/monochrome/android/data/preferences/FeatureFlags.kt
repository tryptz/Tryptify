package tf.monochrome.android.data.preferences

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether a flag travels with the account or stays on the device that set it.
 *
 * The distinction is not "is this important" but "would the answer still be
 * right on a different device". A resampler the listener turned on is a taste,
 * and belongs beside the rest of their settings. A USB output backend is a fact
 * about one DAC on one phone; restoring it onto a device without that DAC would
 * enable a path that cannot engage there, and the failure would look like a bug
 * in playback rather than a restored setting.
 */
enum class FlagSync {
    /** Never leaves the device: hardware-, diagnostic-, or session-scoped. */
    DEVICE_LOCAL,

    /** Exported and restored with the rest of the allow-listed settings. */
    ACCOUNT,
}

/**
 * The seam switches for work in progress. Each one is declared here *before*
 * the change it gates exists, so the pull request that introduces a seam can be
 * read as one thing — the new path, and the branch that keeps the old one — with
 * no flag plumbing mixed into it.
 *
 * Two rules make that safe, and both are held by [FeatureFlagRegistryTest]:
 *
 *  - **Every flag defaults off.** The flag-off branch is the shipped behaviour,
 *    so a build with no stored preferences behaves exactly as it did before the
 *    flag existed.
 *  - **A flag's sync policy is declared, not inherited.** [FlagSync.ACCOUNT]
 *    flags are folded into [PreferencesManager.SETTINGS_SYNC_KEYS] from this
 *    registry rather than listed there by hand, so the allow-list cannot drift
 *    away from the registry — there is only one list.
 *
 * A flag with nothing reading it yet is expected: `owner` names the change that
 * will read it. When that change ships, the flag graduates by being deleted and
 * its branch made unconditional — a flag is scaffolding, not a permanent
 * setting.
 */
enum class FeatureFlag(
    /** DataStore key. `flag_`-prefixed so flags are identifiable in a settings dump. */
    val key: String,
    /** What reads it, and what it turns on. */
    val owner: String,
    val sync: FlagSync = FlagSync.DEVICE_LOCAL,
    /** Off, for every flag that has not graduated. */
    val default: Boolean = false,
) {
    /** Structured playback-event recording into `playback_events`. */
    PLAYBACK_EVENTS("flag_playback_events", owner = "P0.0 structured events"),

    /** Aggregating decoder, processing and output facts into a verified path state. */
    AUDIO_PATH_SNAPSHOT("flag_audio_path_snapshot", owner = "P0.1 audio truth"),

    /** Routing `buildAudioSink` through the OutputBackend seam. */
    OUTPUT_BACKEND_SEAM("flag_output_backend_seam", owner = "P0.2 output backends"),

    /** The AAudio/Oboe output backend, as a third backend behind the seam. */
    AAUDIO_OUTPUT("flag_aaudio_output", owner = "P0.2 AAudio backend"),

    /** DSD over PCM on the USB path. DAC-dependent, so never restored onto another device. */
    DOP_OUTPUT("flag_dop_output", owner = "P0.3 DSD policy"),

    /** Queue survives process death, and shuffle restores from a persisted seed. */
    QUEUE_PERSISTENCE("flag_queue_persistence", owner = "P1.2 queue persistence"),

    /** Deterministic output-rate conversion ahead of the sink, on the SincKernel. */
    OUTPUT_RESAMPLER("flag_output_resampler", owner = "P0.4 SRC", sync = FlagSync.ACCOUNT),

    /** The shared quantisation stage, with a selectable dither shape. */
    OUTPUT_DITHER("flag_output_dither", owner = "P0.4 dither", sync = FlagSync.ACCOUNT),

    /** CUE sheets read as per-track entries over a single image file. */
    CUE_SHEETS("flag_cue_sheets", owner = "P0.3 CUE", sync = FlagSync.ACCOUNT),
    ;

    val preferenceKey: Preferences.Key<Boolean> = booleanPreferencesKey(key)

    /** The stored answer, or the default when nothing has been stored. */
    fun resolve(prefs: Preferences): Boolean = prefs[preferenceKey] ?: default

    companion object {
        /** The prefix every flag key carries. */
        const val KEY_PREFIX = "flag_"

        val ALL: List<FeatureFlag> = entries

        /**
         * The keys [PreferencesManager] folds into its sync allow-list. Derived
         * rather than copied: an [FlagSync.ACCOUNT] flag is on the allow-list
         * because it says it is, and a device-local one cannot be added by
         * forgetting which list it was in.
         */
        val ACCOUNT_SCOPED_KEYS: Set<Preferences.Key<Boolean>> =
            entries.filter { it.sync == FlagSync.ACCOUNT }.map { it.preferenceKey }.toSet()

        fun byKey(key: String): FeatureFlag? = entries.firstOrNull { it.key == key }
    }
}

/**
 * Reads and writes [FeatureFlag]s in the app's one preferences file.
 *
 * Deliberately not another block of hand-rolled keys inside [PreferencesManager]:
 * flags are short-lived and want a registry that can be enumerated, while the
 * settings beside them are permanent and want names. They share a DataStore
 * because a second `preferencesDataStore` on the same file throws, and a second
 * file would put account-scoped flags outside the reach of settings sync.
 */
@Singleton
class FeatureFlagStore @Inject constructor(
    preferencesManager: PreferencesManager,
) {
    private val dataStore = preferencesManager.store

    /** Whether [flag] is on, starting from its default and following writes. */
    fun isEnabled(flag: FeatureFlag): Flow<Boolean> =
        dataStore.data.map { flag.resolve(it) }.distinctUntilChanged()

    /** A one-shot read, for call sites that cannot hold a flow. */
    suspend fun isEnabledNow(flag: FeatureFlag): Boolean = flag.resolve(dataStore.data.first())

    /** Every flag and its current answer — the shape a diagnostic bundle wants. */
    suspend fun snapshot(): Map<FeatureFlag, Boolean> =
        dataStore.data.first().let { prefs -> FeatureFlag.ALL.associateWith { it.resolve(prefs) } }

    suspend fun setEnabled(flag: FeatureFlag, enabled: Boolean) {
        dataStore.edit { it[flag.preferenceKey] = enabled }
    }

    /** Forget the stored answer, so [flag] reads as its default again. */
    suspend fun clear(flag: FeatureFlag) {
        dataStore.edit { it.remove(flag.preferenceKey) }
    }
}
