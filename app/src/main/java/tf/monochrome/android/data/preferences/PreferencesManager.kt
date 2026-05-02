package tf.monochrome.android.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.ReplayGainMode
import tf.monochrome.android.performance.PerformanceProfile
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "monochrome_prefs")

/** Which catalog(s) drive search and discovery surfaces. */
enum class SourceMode { BOTH, TIDAL_ONLY, QOBUZ_ONLY }

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val performanceProfile: PerformanceProfile,
) {
    private val dataStore = context.dataStore

    companion object {
        private const val MAX_SEARCH_HISTORY_SIZE = 10

        // Audio quality
        private val WIFI_QUALITY = stringPreferencesKey("wifi_quality")
        private val CELLULAR_QUALITY = stringPreferencesKey("cellular_quality")

        // ReplayGain
        private val REPLAY_GAIN_MODE = stringPreferencesKey("replay_gain_mode")
        private val REPLAY_GAIN_PREAMP = doublePreferencesKey("replay_gain_preamp")

        // Player state
        private val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        private val REPEAT_MODE = intPreferencesKey("repeat_mode")
        private val VOLUME = doublePreferencesKey("volume")

        // Instance cache
        private val INSTANCES_CACHE = stringPreferencesKey("instances_cache")
        private val INSTANCES_CACHE_TIMESTAMP = longPreferencesKey("instances_cache_timestamp")

        // Theme
        private val THEME = stringPreferencesKey("theme")
        private val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")

        // Scrobbling
        private val LASTFM_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
        private val LASTFM_USERNAME = stringPreferencesKey("lastfm_username")
        private val LASTFM_ENABLED = booleanPreferencesKey("lastfm_enabled")
        private val LISTENBRAINZ_TOKEN = stringPreferencesKey("listenbrainz_token")
        private val LISTENBRAINZ_ENABLED = booleanPreferencesKey("listenbrainz_enabled")

        // Custom API endpoint
        private val CUSTOM_API_ENDPOINT = stringPreferencesKey("custom_api_endpoint")
        private val QOBUZ_INSTANCE_URL = stringPreferencesKey("qobuz_instance_url")
        private val QOBUZ_AUTH_COOKIE = stringPreferencesKey("qobuz_auth_cookie")
        private val DEV_MODE_ENABLED = booleanPreferencesKey("dev_mode_enabled")
        private val SOURCE_MODE = stringPreferencesKey("source_mode")

        // Interface
        private val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        private val SHOW_EXPLICIT_BADGES = booleanPreferencesKey("show_explicit_badges")
        private val CONFIRM_CLEAR_QUEUE = booleanPreferencesKey("confirm_clear_queue")

        // Audio extras
        private val NORMALIZATION_ENABLED = booleanPreferencesKey("normalization_enabled")
        private val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")

        // Downloads
        private val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        private val DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")

        // Playback speed
        private val PLAYBACK_SPEED = stringPreferencesKey("playback_speed")
        private val PRESERVE_PITCH = booleanPreferencesKey("preserve_pitch")

        // Appearance extras
        private val FONT_SCALE = floatPreferencesKey("font_scale")
        private val CUSTOM_FONT_URI = stringPreferencesKey("custom_font_uri")

        // Google Auth
        private val GOOGLE_USER_ID = stringPreferencesKey("google_user_id")
        private val GOOGLE_DISPLAY_NAME = stringPreferencesKey("google_display_name")
        private val GOOGLE_EMAIL = stringPreferencesKey("google_email")
        private val GOOGLE_PHOTO_URL = stringPreferencesKey("google_photo_url")

        // Parity features
        private val VISUALIZER_SENSITIVITY = intPreferencesKey("visualizer_sensitivity")
        private val VISUALIZER_BRIGHTNESS = intPreferencesKey("visualizer_brightness")
        private val ROMAJI_LYRICS = booleanPreferencesKey("romaji_lyrics")
        private val DOWNLOAD_LYRICS = booleanPreferencesKey("download_lyrics")
        private val NOW_PLAYING_VIEW_MODE = stringPreferencesKey("now_playing_view_mode")
        private val VISUALIZER_ENGINE_ENABLED = booleanPreferencesKey("visualizer_engine_enabled")
        private val VISUALIZER_AUTO_SHUFFLE = booleanPreferencesKey("visualizer_auto_shuffle")
        private val VISUALIZER_PRESET_ID = stringPreferencesKey("visualizer_preset_id")
        private val VISUALIZER_ROTATION_SECONDS = intPreferencesKey("visualizer_rotation_seconds")
        private val VISUALIZER_TEXTURE_SIZE = intPreferencesKey("visualizer_texture_size")
        private val VISUALIZER_MESH_X = intPreferencesKey("visualizer_mesh_x")
        private val VISUALIZER_MESH_Y = intPreferencesKey("visualizer_mesh_y")
        private val VISUALIZER_TARGET_FPS = intPreferencesKey("visualizer_target_fps")
        private val VISUALIZER_VSYNC_ENABLED = booleanPreferencesKey("visualizer_vsync_enabled")
        private val VISUALIZER_SHOW_FPS = booleanPreferencesKey("visualizer_show_fps")
        private val VISUALIZER_FULLSCREEN = booleanPreferencesKey("visualizer_fullscreen")
        private val VISUALIZER_TOUCH_WAVEFORM = booleanPreferencesKey("visualizer_touch_waveform")
        private val VISUALIZER_FAVORITE_PRESETS = stringSetPreferencesKey("visualizer_favorite_presets")

        // AI
        private val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        private val AI_RADIO_ENABLED = booleanPreferencesKey("ai_radio_enabled")

        // PocketBase
        private val POCKETBASE_TOKEN = stringPreferencesKey("pocketbase_token")
        private val POCKETBASE_USER_ID = stringPreferencesKey("pocketbase_user_id")
        private val POCKETBASE_EMAIL = stringPreferencesKey("pocketbase_email")
        // Home screen cache

        // EQ / AutoEQ
        private val EQ_TUTORIAL_SEEN = booleanPreferencesKey("eq_tutorial_seen")
        private val EQ_ENABLED = booleanPreferencesKey("eq_enabled")
        private val EQ_ACTIVE_PRESET_ID = stringPreferencesKey("eq_active_preset_id")
        private val EQ_TARGET_ID = stringPreferencesKey("eq_target_id")
        private val EQ_PREAMP = doublePreferencesKey("eq_preamp")
        private val EQ_BANDS_JSON = stringPreferencesKey("eq_bands_json")
        private val EQ_CUSTOM_TARGETS_JSON = stringPreferencesKey("eq_custom_targets_json")
        private val EQ_SELECTED_HEADPHONE_ID = stringPreferencesKey("eq_selected_headphone_id")
        private val EQ_SELECTED_HEADPHONE_NAME = stringPreferencesKey("eq_selected_headphone_name")

        // Parametric EQ (independent of AutoEQ)
        private val PARAM_EQ_ENABLED = booleanPreferencesKey("param_eq_enabled")
        private val PARAM_EQ_ACTIVE_PRESET_ID = stringPreferencesKey("param_eq_active_preset_id")
        private val PARAM_EQ_PREAMP = doublePreferencesKey("param_eq_preamp")
        private val PARAM_EQ_BANDS_JSON = stringPreferencesKey("param_eq_bands_json")

        // Library / Local Media
        private val SCAN_ON_APP_OPEN = booleanPreferencesKey("scan_on_app_open")
        private val MIN_TRACK_DURATION_MS = longPreferencesKey("min_track_duration_ms")
        private val EXCLUDED_PATHS_JSON = stringPreferencesKey("excluded_paths_json")
        private val BACKGROUND_SCAN_INTERVAL = stringPreferencesKey("background_scan_interval")
        private val USER_FOLDER_ROOTS_JSON = stringPreferencesKey("user_folder_roots_json")

        // DSP Mixer
        private val DSP_ENABLED = booleanPreferencesKey("dsp_enabled")
        private val DSP_STATE_JSON = stringPreferencesKey("dsp_state_json")
        private val DSP_BLOCK_SIZE = intPreferencesKey("dsp_block_size")
        private val USB_BIT_PERFECT_ENABLED = booleanPreferencesKey("usb_bit_perfect_enabled")
        private val USB_EXCLUSIVE_BIT_PERFECT_ENABLED =
            booleanPreferencesKey("usb_exclusive_bit_perfect_enabled")
        // Powers of two mirroring the user-facing chip row in Settings.
        // Native engine's static MAX_BLOCK_SIZE caps the largest entry; bump
        // both together if you add another step.
        val DSP_BLOCK_SIZES = listOf(128, 256, 512, 1024, 2048, 4096, 8192, 16384)

        // Library tab order
        private val LIBRARY_TAB_ORDER = stringPreferencesKey("library_tab_order")

        // Car mode
        private val CAR_MODE_BAND_COUNT = intPreferencesKey("car_mode_band_count")

        // Search
        private val SEARCH_HISTORY_JSON = stringPreferencesKey("search_history_json")

        // Spectrum analyzer
        private val SPECTRUM_ANALYZER_ENABLED = booleanPreferencesKey("spectrum_analyzer_enabled")
        private val SPECTRUM_SHOW_ON_NOW_PLAYING = booleanPreferencesKey("spectrum_show_on_now_playing")
        private val SPECTRUM_FFT_SIZE = intPreferencesKey("spectrum_fft_size")

        // Device / session (Supabase sync)
        private val DEVICE_LOCAL_ID = stringPreferencesKey("device_local_id")
        private val DEVICE_REMOTE_ID = stringPreferencesKey("device_remote_id")
    }

    private val json = Json { ignoreUnknownKeys = true }

    // Audio Quality
    val wifiQuality: Flow<AudioQuality> = dataStore.data.map { prefs ->
        prefs[WIFI_QUALITY]?.let { AudioQuality.valueOf(it) } ?: AudioQuality.HI_RES
    }

    val cellularQuality: Flow<AudioQuality> = dataStore.data.map { prefs ->
        prefs[CELLULAR_QUALITY]?.let { AudioQuality.valueOf(it) } ?: AudioQuality.HIGH
    }

    suspend fun setWifiQuality(quality: AudioQuality) {
        dataStore.edit { it[WIFI_QUALITY] = quality.name }
    }

    suspend fun setCellularQuality(quality: AudioQuality) {
        dataStore.edit { it[CELLULAR_QUALITY] = quality.name }
    }

    // ReplayGain
    val replayGainMode: Flow<ReplayGainMode> = dataStore.data.map { prefs ->
        prefs[REPLAY_GAIN_MODE]?.let { ReplayGainMode.valueOf(it) } ?: ReplayGainMode.OFF
    }

    val replayGainPreamp: Flow<Double> = dataStore.data.map { prefs ->
        prefs[REPLAY_GAIN_PREAMP] ?: 0.0
    }

    suspend fun setReplayGainMode(mode: ReplayGainMode) {
        dataStore.edit { it[REPLAY_GAIN_MODE] = mode.name }
    }

    suspend fun setReplayGainPreamp(preamp: Double) {
        dataStore.edit { it[REPLAY_GAIN_PREAMP] = preamp }
    }

    // Player state
    val shuffleEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHUFFLE_ENABLED] ?: false
    }

    val repeatMode: Flow<Int> = dataStore.data.map { prefs ->
        prefs[REPEAT_MODE] ?: 0
    }

    val volume: Flow<Double> = dataStore.data.map { prefs ->
        prefs[VOLUME] ?: 1.0
    }

    suspend fun setShuffleEnabled(enabled: Boolean) {
        dataStore.edit { it[SHUFFLE_ENABLED] = enabled }
    }

    suspend fun setRepeatMode(mode: Int) {
        dataStore.edit { it[REPEAT_MODE] = mode }
    }

    suspend fun setVolume(volume: Double) {
        dataStore.edit { it[VOLUME] = volume }
    }

    // Instance cache
    val instancesCache: Flow<String?> = dataStore.data.map { prefs ->
        prefs[INSTANCES_CACHE]
    }

    val instancesCacheTimestamp: Flow<Long> = dataStore.data.map { prefs ->
        prefs[INSTANCES_CACHE_TIMESTAMP] ?: 0L
    }

    suspend fun saveInstancesCache(json: String) {
        dataStore.edit {
            it[INSTANCES_CACHE] = json
            it[INSTANCES_CACHE_TIMESTAMP] = System.currentTimeMillis()
        }
    }

    // Theme
    val theme: Flow<String> = dataStore.data.map { prefs ->
        prefs[THEME] ?: "monochrome_dark"
    }

    val dynamicColors: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLORS] ?: false
    }

    suspend fun setTheme(theme: String) {
        dataStore.edit { it[THEME] = theme }
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLORS] = enabled }
    }

    // Scrobbling - Last.fm
    val lastFmSessionKey: Flow<String?> = dataStore.data.map { prefs ->
        prefs[LASTFM_SESSION_KEY]
    }

    val lastFmUsername: Flow<String?> = dataStore.data.map { prefs ->
        prefs[LASTFM_USERNAME]
    }

    val lastFmEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[LASTFM_ENABLED] ?: false
    }

    suspend fun setLastFmSession(sessionKey: String, username: String) {
        dataStore.edit {
            it[LASTFM_SESSION_KEY] = sessionKey
            it[LASTFM_USERNAME] = username
            it[LASTFM_ENABLED] = true
        }
    }

    suspend fun clearLastFmSession() {
        dataStore.edit {
            it.remove(LASTFM_SESSION_KEY)
            it.remove(LASTFM_USERNAME)
            it[LASTFM_ENABLED] = false
        }
    }

    // Scrobbling - ListenBrainz
    val listenBrainzToken: Flow<String?> = dataStore.data.map { prefs ->
        prefs[LISTENBRAINZ_TOKEN]
    }

    val listenBrainzEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[LISTENBRAINZ_ENABLED] ?: false
    }

    suspend fun setListenBrainzToken(token: String) {
        dataStore.edit {
            it[LISTENBRAINZ_TOKEN] = token
            it[LISTENBRAINZ_ENABLED] = true
        }
    }

    suspend fun clearListenBrainzToken() {
        dataStore.edit {
            it.remove(LISTENBRAINZ_TOKEN)
            it[LISTENBRAINZ_ENABLED] = false
        }
    }

    // Custom API
    val customApiEndpoint: Flow<String?> = dataStore.data.map { prefs ->
        prefs[CUSTOM_API_ENDPOINT]
    }

    suspend fun setCustomApiEndpoint(endpoint: String?) {
        dataStore.edit {
            if (endpoint != null) {
                it[CUSTOM_API_ENDPOINT] = endpoint
            } else {
                it.remove(CUSTOM_API_ENDPOINT)
            }
        }
    }

    // Qobuz instance — used for downloads. Independent of Dev Mode: any
    // value set here is honored whenever the download path is invoked.
    val qobuzInstanceUrl: Flow<String?> = dataStore.data.map { prefs ->
        prefs[QOBUZ_INSTANCE_URL]
    }

    suspend fun setQobuzInstanceUrl(endpoint: String?) {
        dataStore.edit {
            if (endpoint != null) {
                it[QOBUZ_INSTANCE_URL] = endpoint
            } else {
                it.remove(QOBUZ_INSTANCE_URL)
            }
        }
    }

    // Optional session cookie pinned by the user from their browser. The
    // trypt-hifi backend issues a session cookie on login and expects it on
    // every /api/ request; without it the backend returns 401. Stored as the
    // raw header value (one or more "name=value" pairs separated by "; ").
    val qobuzAuthCookie: Flow<String?> = dataStore.data.map { prefs ->
        prefs[QOBUZ_AUTH_COOKIE]
    }

    suspend fun setQobuzAuthCookie(cookie: String?) {
        dataStore.edit {
            if (cookie != null) {
                it[QOBUZ_AUTH_COOKIE] = cookie
            } else {
                it.remove(QOBUZ_AUTH_COOKIE)
            }
        }
    }

    /**
     * Which catalog(s) drive search/discovery. BOTH (default) is the
     * existing fan-out behavior; TIDAL_ONLY skips the Qobuz call so search
     * doesn't surface Qobuz hits; QOBUZ_ONLY skips the TIDAL pool. Stream
     * playback and downloads still follow the per-track PlaybackSource —
     * the setting only governs which catalogs feed search results.
     */
    val sourceMode: Flow<SourceMode> = dataStore.data.map { prefs ->
        prefs[SOURCE_MODE]?.let { runCatching { SourceMode.valueOf(it) }.getOrNull() }
            ?: SourceMode.BOTH
    }

    suspend fun setSourceMode(mode: SourceMode) {
        dataStore.edit { it[SOURCE_MODE] = mode.name }
    }

    val devModeEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DEV_MODE_ENABLED] ?: false
    }

    suspend fun setDevModeEnabled(enabled: Boolean) {
        dataStore.edit { it[DEV_MODE_ENABLED] = enabled }
    }

    // --- Interface ---
    val gaplessPlayback: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[GAPLESS_PLAYBACK] ?: true
    }
    suspend fun setGaplessPlayback(enabled: Boolean) {
        dataStore.edit { it[GAPLESS_PLAYBACK] = enabled }
    }

    val showExplicitBadges: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHOW_EXPLICIT_BADGES] ?: true
    }
    suspend fun setShowExplicitBadges(enabled: Boolean) {
        dataStore.edit { it[SHOW_EXPLICIT_BADGES] = enabled }
    }

    val confirmClearQueue: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[CONFIRM_CLEAR_QUEUE] ?: true
    }
    suspend fun setConfirmClearQueue(enabled: Boolean) {
        dataStore.edit { it[CONFIRM_CLEAR_QUEUE] = enabled }
    }

    // --- Audio extras ---
    val normalizationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[NORMALIZATION_ENABLED] ?: false
    }
    suspend fun setNormalizationEnabled(enabled: Boolean) {
        dataStore.edit { it[NORMALIZATION_ENABLED] = enabled }
    }

    val crossfadeDuration: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CROSSFADE_DURATION] ?: 0
    }
    suspend fun setCrossfadeDuration(seconds: Int) {
        dataStore.edit { it[CROSSFADE_DURATION] = seconds }
    }

    // --- Downloads ---
    val downloadQuality: Flow<AudioQuality> = dataStore.data.map { prefs ->
        prefs[DOWNLOAD_QUALITY]?.let { AudioQuality.valueOf(it) } ?: AudioQuality.HI_RES
    }
    suspend fun setDownloadQuality(quality: AudioQuality) {
        dataStore.edit { it[DOWNLOAD_QUALITY] = quality.name }
    }

    val downloadFolderUri: Flow<String?> = dataStore.data.map { it[DOWNLOAD_FOLDER_URI] }
    suspend fun setDownloadFolderUri(uri: String?) {
        dataStore.edit {
            if (uri != null) it[DOWNLOAD_FOLDER_URI] = uri
            else it.remove(DOWNLOAD_FOLDER_URI)
        }
    }

    // --- Playback speed ---
    val playbackSpeed: Flow<Float> = dataStore.data.map { prefs ->
        prefs[PLAYBACK_SPEED]?.toFloatOrNull() ?: 1.0f
    }
    suspend fun setPlaybackSpeed(speed: Float) {
        dataStore.edit { it[PLAYBACK_SPEED] = speed.toString() }
    }

    val preservePitch: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[PRESERVE_PITCH] ?: true
    }
    suspend fun setPreservePitch(enabled: Boolean) {
        dataStore.edit { it[PRESERVE_PITCH] = enabled }
    }

    // --- Font scale ---
    val fontScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[FONT_SCALE] ?: 1.0f
    }
    suspend fun setFontScale(scale: Float) {
        dataStore.edit { it[FONT_SCALE] = scale.coerceIn(0.5f, 2.0f) }
    }

    // --- Custom font ---
    val customFontUri: Flow<String?> = dataStore.data.map { prefs ->
        prefs[CUSTOM_FONT_URI]
    }
    suspend fun setCustomFontUri(uri: String?) {
        dataStore.edit {
            if (uri != null) it[CUSTOM_FONT_URI] = uri
            else it.remove(CUSTOM_FONT_URI)
        }
    }

    // --- Search ---
    val searchHistory: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[SEARCH_HISTORY_JSON]?.let { raw ->
            runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
        } ?: emptyList()
    }

    suspend fun addSearchHistoryQuery(query: String) {
        val normalizedQuery = query.trim()
        if (normalizedQuery.isBlank()) return
        dataStore.edit { prefs ->
            val existing = prefs[SEARCH_HISTORY_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
            }.orEmpty()
            val updated = buildList {
                add(normalizedQuery)
                addAll(existing.filterNot { it.equals(normalizedQuery, ignoreCase = true) })
            }.take(MAX_SEARCH_HISTORY_SIZE)
            prefs[SEARCH_HISTORY_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun clearSearchHistory() {
        dataStore.edit { it.remove(SEARCH_HISTORY_JSON) }
    }

    // --- Google Auth ---
    val googleUserId: Flow<String?> = dataStore.data.map { it[GOOGLE_USER_ID] }
    val googleDisplayName: Flow<String?> = dataStore.data.map { it[GOOGLE_DISPLAY_NAME] }
    val googleEmail: Flow<String?> = dataStore.data.map { it[GOOGLE_EMAIL] }
    val googlePhotoUrl: Flow<String?> = dataStore.data.map { it[GOOGLE_PHOTO_URL] }

    suspend fun setGoogleProfile(userId: String, displayName: String?, email: String?, photoUrl: String?) {
        dataStore.edit {
            it[GOOGLE_USER_ID] = userId
            displayName?.let { name -> it[GOOGLE_DISPLAY_NAME] = name }
            email?.let { e -> it[GOOGLE_EMAIL] = e }
            photoUrl?.let { url -> it[GOOGLE_PHOTO_URL] = url }
        }
    }

    suspend fun clearGoogleProfile() {
        dataStore.edit {
            it.remove(GOOGLE_USER_ID)
            it.remove(GOOGLE_DISPLAY_NAME)
            it.remove(GOOGLE_EMAIL)
            it.remove(GOOGLE_PHOTO_URL)
        }
    }

    // --- Parity features ---
    val visualizerSensitivity: Flow<Int> = dataStore.data.map { it[VISUALIZER_SENSITIVITY] ?: 50 }
    val visualizerBrightness: Flow<Int> = dataStore.data.map { it[VISUALIZER_BRIGHTNESS] ?: 80 }
    val romajiLyrics: Flow<Boolean> = dataStore.data.map { it[ROMAJI_LYRICS] ?: false }
    val downloadLyrics: Flow<Boolean> = dataStore.data.map { it[DOWNLOAD_LYRICS] ?: false }
    val visualizerEngineEnabled: Flow<Boolean> = dataStore.data.map { it[VISUALIZER_ENGINE_ENABLED] ?: true }
    val visualizerAutoShuffle: Flow<Boolean> = dataStore.data.map { it[VISUALIZER_AUTO_SHUFFLE] ?: true }
    val visualizerPresetId: Flow<String?> = dataStore.data.map { it[VISUALIZER_PRESET_ID] }
    val visualizerRotationSeconds: Flow<Int> = dataStore.data.map { it[VISUALIZER_ROTATION_SECONDS] ?: 20 }
    val visualizerTextureSize: Flow<Int> = dataStore.data.map { it[VISUALIZER_TEXTURE_SIZE] ?: 1024 }
    val visualizerMeshX: Flow<Int> = dataStore.data.map { it[VISUALIZER_MESH_X] ?: 32 }
    val visualizerMeshY: Flow<Int> = dataStore.data.map { it[VISUALIZER_MESH_Y] ?: 24 }
    val visualizerTargetFps: Flow<Int> = dataStore.data.map {
        // First-run / never-set → fall back to the resolved performance tier's
        // ceiling (LOW=30, MID=60, HIGH=120). Once the user touches the setting,
        // DataStore keeps their override across device-tier changes.
        it[VISUALIZER_TARGET_FPS] ?: performanceProfile.visualizerFps
    }
    // When false, the visualizer GL surface calls eglSwapInterval(0) and the
    // native renderer is allowed to exceed display refresh, capped only by
    // visualizerTargetFps. Default true (display-synced) — turning it off
    // increases battery / heat.
    val visualizerVsyncEnabled: Flow<Boolean> = dataStore.data.map { it[VISUALIZER_VSYNC_ENABLED] ?: true }
    val visualizerShowFps: Flow<Boolean> = dataStore.data.map { it[VISUALIZER_SHOW_FPS] ?: false }
    val visualizerFullscreen: Flow<Boolean> = dataStore.data.map { it[VISUALIZER_FULLSCREEN] ?: false }
    val visualizerTouchWaveform: Flow<Boolean> = dataStore.data.map { it[VISUALIZER_TOUCH_WAVEFORM] ?: true }

    suspend fun setVisualizerSensitivity(value: Int) {
        dataStore.edit { it[VISUALIZER_SENSITIVITY] = value }
    }
    suspend fun setVisualizerBrightness(value: Int) {
        dataStore.edit { it[VISUALIZER_BRIGHTNESS] = value }
    }
    suspend fun setRomajiLyrics(enabled: Boolean) {
        dataStore.edit { it[ROMAJI_LYRICS] = enabled }
    }
    suspend fun setDownloadLyrics(enabled: Boolean) {
        dataStore.edit { it[DOWNLOAD_LYRICS] = enabled }
    }
    suspend fun setVisualizerEngineEnabled(enabled: Boolean) {
        dataStore.edit { it[VISUALIZER_ENGINE_ENABLED] = enabled }
    }
    suspend fun setVisualizerAutoShuffle(enabled: Boolean) {
        dataStore.edit { it[VISUALIZER_AUTO_SHUFFLE] = enabled }
    }
    suspend fun setVisualizerPresetId(presetId: String?) {
        dataStore.edit {
            if (presetId.isNullOrBlank()) it.remove(VISUALIZER_PRESET_ID)
            else it[VISUALIZER_PRESET_ID] = presetId
        }
    }
    suspend fun setVisualizerRotationSeconds(seconds: Int) {
        dataStore.edit { it[VISUALIZER_ROTATION_SECONDS] = seconds.coerceIn(5, 120) }
    }
    suspend fun setVisualizerTextureSize(size: Int) {
        dataStore.edit { it[VISUALIZER_TEXTURE_SIZE] = size }
    }
    suspend fun setVisualizerMeshX(value: Int) {
        dataStore.edit { it[VISUALIZER_MESH_X] = value }
    }
    suspend fun setVisualizerMeshY(value: Int) {
        dataStore.edit { it[VISUALIZER_MESH_Y] = value }
    }
    suspend fun setVisualizerTargetFps(value: Int) {
        dataStore.edit { it[VISUALIZER_TARGET_FPS] = value }
    }
    suspend fun setVisualizerVsyncEnabled(value: Boolean) {
        dataStore.edit { it[VISUALIZER_VSYNC_ENABLED] = value }
    }
    suspend fun setVisualizerShowFps(enabled: Boolean) {
        dataStore.edit { it[VISUALIZER_SHOW_FPS] = enabled }
    }
    suspend fun setVisualizerFullscreen(enabled: Boolean) {
        dataStore.edit { it[VISUALIZER_FULLSCREEN] = enabled }
    }
    suspend fun setVisualizerTouchWaveform(enabled: Boolean) {
        dataStore.edit { it[VISUALIZER_TOUCH_WAVEFORM] = enabled }
    }

    val visualizerFavoritePresets: Flow<Set<String>> = dataStore.data.map {
        it[VISUALIZER_FAVORITE_PRESETS] ?: emptySet()
    }
    suspend fun toggleVisualizerFavoritePreset(presetId: String) {
        dataStore.edit { prefs ->
            val current = prefs[VISUALIZER_FAVORITE_PRESETS] ?: emptySet()
            prefs[VISUALIZER_FAVORITE_PRESETS] = if (presetId in current) {
                current - presetId
            } else {
                current + presetId
            }
        }
    }

    val nowPlayingViewMode: Flow<NowPlayingViewMode> = dataStore.data.map { prefs ->
        prefs[NOW_PLAYING_VIEW_MODE]?.let { NowPlayingViewMode.valueOf(it) } ?: NowPlayingViewMode.COVER_ART
    }
    suspend fun setNowPlayingViewMode(mode: NowPlayingViewMode) {
        dataStore.edit { it[NOW_PLAYING_VIEW_MODE] = mode.name }
    }

    // --- AI ---
    val geminiApiKey: Flow<String?> = dataStore.data.map { it[GEMINI_API_KEY] }
    val aiRadioEnabled: Flow<Boolean> = dataStore.data.map { it[AI_RADIO_ENABLED] ?: false }

    suspend fun setGeminiApiKey(key: String?) {
        dataStore.edit {
            if (key.isNullOrBlank()) it.remove(GEMINI_API_KEY)
            else it[GEMINI_API_KEY] = key
        }
    }

    suspend fun setAiRadioEnabled(enabled: Boolean) {
        dataStore.edit { it[AI_RADIO_ENABLED] = enabled }
    }


    // --- PocketBase ---
    val pocketBaseToken: Flow<String?> = dataStore.data.map { it[POCKETBASE_TOKEN] }
    val pocketBaseUserId: Flow<String?> = dataStore.data.map { it[POCKETBASE_USER_ID] }
    val pocketBaseEmail: Flow<String?> = dataStore.data.map { it[POCKETBASE_EMAIL] }

    suspend fun setPocketBaseAuth(token: String, userId: String, email: String) {
        dataStore.edit {
            it[POCKETBASE_TOKEN] = token
            it[POCKETBASE_USER_ID] = userId
            it[POCKETBASE_EMAIL] = email
        }
    }

    suspend fun clearPocketBaseAuth() {
        dataStore.edit {
            it.remove(POCKETBASE_TOKEN)
            it.remove(POCKETBASE_USER_ID)
            it.remove(POCKETBASE_EMAIL)
        }
    }

    // --- EQ / AutoEQ ---
    val eqTutorialSeen: Flow<Boolean> = dataStore.data.map { it[EQ_TUTORIAL_SEEN] ?: false }
    suspend fun setEqTutorialSeen(seen: Boolean) {
        dataStore.edit { it[EQ_TUTORIAL_SEEN] = seen }
    }

    val eqEnabled: Flow<Boolean> = dataStore.data.map { it[EQ_ENABLED] ?: false }
    val eqActivePresetId: Flow<String?> = dataStore.data.map { it[EQ_ACTIVE_PRESET_ID] }
    val eqTargetId: Flow<String> = dataStore.data.map { it[EQ_TARGET_ID] ?: "harman_oe_2018" }
    val eqPreamp: Flow<Double> = dataStore.data.map { it[EQ_PREAMP] ?: 0.0 }
    val eqBandsJson: Flow<String?> = dataStore.data.map { it[EQ_BANDS_JSON] }

    suspend fun setEqEnabled(enabled: Boolean) {
        dataStore.edit { it[EQ_ENABLED] = enabled }
    }

    suspend fun setEqActivePreset(presetId: String?) {
        dataStore.edit {
            if (presetId != null) {
                it[EQ_ACTIVE_PRESET_ID] = presetId
            } else {
                it.remove(EQ_ACTIVE_PRESET_ID)
            }
        }
    }

    suspend fun setEqTarget(targetId: String) {
        dataStore.edit { it[EQ_TARGET_ID] = targetId }
    }

    suspend fun setEqPreamp(preamp: Double) {
        dataStore.edit { it[EQ_PREAMP] = preamp }
    }

    suspend fun setEqBands(bandsJson: String?) {
        dataStore.edit {
            if (bandsJson != null) {
                it[EQ_BANDS_JSON] = bandsJson
            } else {
                it.remove(EQ_BANDS_JSON)
            }
        }
    }

    val eqCustomTargetsJson: Flow<String> = dataStore.data.map { it[EQ_CUSTOM_TARGETS_JSON] ?: "[]" }
    suspend fun setEqCustomTargets(json: String) {
        dataStore.edit { it[EQ_CUSTOM_TARGETS_JSON] = json }
    }

    val eqSelectedHeadphoneId: Flow<String?> = dataStore.data.map { it[EQ_SELECTED_HEADPHONE_ID] }
    val eqSelectedHeadphoneName: Flow<String?> = dataStore.data.map { it[EQ_SELECTED_HEADPHONE_NAME] }
    suspend fun setEqSelectedHeadphone(id: String, name: String) {
        dataStore.edit {
            it[EQ_SELECTED_HEADPHONE_ID] = id
            it[EQ_SELECTED_HEADPHONE_NAME] = name
        }
    }
    suspend fun clearEqSelectedHeadphone() {
        dataStore.edit {
            it.remove(EQ_SELECTED_HEADPHONE_ID)
            it.remove(EQ_SELECTED_HEADPHONE_NAME)
        }
    }

    // --- Parametric EQ (independent of AutoEQ) ---
    val paramEqEnabled: Flow<Boolean> = dataStore.data.map { it[PARAM_EQ_ENABLED] ?: false }
    val paramEqActivePresetId: Flow<String?> = dataStore.data.map { it[PARAM_EQ_ACTIVE_PRESET_ID] }
    val paramEqPreamp: Flow<Double> = dataStore.data.map { it[PARAM_EQ_PREAMP] ?: 0.0 }
    val paramEqBandsJson: Flow<String?> = dataStore.data.map { it[PARAM_EQ_BANDS_JSON] }

    suspend fun setParamEqEnabled(enabled: Boolean) {
        dataStore.edit { it[PARAM_EQ_ENABLED] = enabled }
    }

    suspend fun setParamEqActivePreset(presetId: String?) {
        dataStore.edit {
            if (presetId != null) {
                it[PARAM_EQ_ACTIVE_PRESET_ID] = presetId
            } else {
                it.remove(PARAM_EQ_ACTIVE_PRESET_ID)
            }
        }
    }

    suspend fun setParamEqPreamp(preamp: Double) {
        dataStore.edit { it[PARAM_EQ_PREAMP] = preamp }
    }

    suspend fun setParamEqBands(bandsJson: String?) {
        dataStore.edit {
            if (bandsJson != null) {
                it[PARAM_EQ_BANDS_JSON] = bandsJson
            } else {
                it.remove(PARAM_EQ_BANDS_JSON)
            }
        }
    }

    // --- DSP Mixer ---
    val dspEnabled: Flow<Boolean> = dataStore.data.map { it[DSP_ENABLED] ?: false }
    suspend fun setDspEnabled(enabled: Boolean) {
        dataStore.edit { it[DSP_ENABLED] = enabled }
    }

    val dspStateJson: Flow<String?> = dataStore.data.map { it[DSP_STATE_JSON] }
    suspend fun setDspStateJson(json: String?) {
        dataStore.edit {
            if (json.isNullOrBlank()) it.remove(DSP_STATE_JSON)
            else it[DSP_STATE_JSON] = json
        }
    }

    /**
     * Per-block frame count the MixBusProcessor passes into nativeProcess.
     * Smaller = lower latency + higher CPU; larger = lower CPU + slightly
     * higher latency. Restricted to powers of two between 128 and 2048;
     * unknown values fall back to 1024 (the sane default that matches
     * Android's typical AudioTrack period).
     */
    val dspBlockSize: Flow<Int> = dataStore.data.map { prefs ->
        val v = prefs[DSP_BLOCK_SIZE] ?: 1024
        if (v in DSP_BLOCK_SIZES) v else 1024
    }
    suspend fun setDspBlockSize(value: Int) {
        if (value !in DSP_BLOCK_SIZES) return
        dataStore.edit { it[DSP_BLOCK_SIZE] = value }
    }

    /**
     * When on, PlaybackService pins the player's output to the
     * currently-attached USB Audio Class DAC (if any) via
     * setPreferredAudioDevice, bypassing the system's mix-rate downsampler
     * for sample rates the DAC supports natively. No-op when no USB output
     * is attached.
     */
    val usbBitPerfectEnabled: Flow<Boolean> = dataStore.data.map { it[USB_BIT_PERFECT_ENABLED] ?: false }
    suspend fun setUsbBitPerfectEnabled(enabled: Boolean) {
        dataStore.edit { it[USB_BIT_PERFECT_ENABLED] = enabled }
    }

    /**
     * Exclusive UAC2 path — libusb-backed, bypasses Android's audio
     * framework entirely (UAPP-style). Distinct from
     * [usbBitPerfectEnabled] which only pins routing inside the
     * framework. Default false; requires the user to also have
     * "Disable USB audio routing" on in Developer Options for the
     * libusb claim to succeed on most non-rooted devices.
     */
    val usbExclusiveBitPerfectEnabled: Flow<Boolean> =
        dataStore.data.map { it[USB_EXCLUSIVE_BIT_PERFECT_ENABLED] ?: false }
    suspend fun setUsbExclusiveBitPerfectEnabled(enabled: Boolean) {
        dataStore.edit { it[USB_EXCLUSIVE_BIT_PERFECT_ENABLED] = enabled }
    }

    // --- Library / Local Media ---
    val scanOnAppOpen: Flow<Boolean> = dataStore.data.map { it[SCAN_ON_APP_OPEN] ?: true }
    suspend fun setScanOnAppOpen(enabled: Boolean) {
        dataStore.edit { it[SCAN_ON_APP_OPEN] = enabled }
    }

    val minTrackDurationMs: Flow<Long> = dataStore.data.map { it[MIN_TRACK_DURATION_MS] ?: 30_000L }
    suspend fun setMinTrackDurationMs(durationMs: Long) {
        dataStore.edit { it[MIN_TRACK_DURATION_MS] = durationMs }
    }

    val excludedPathsJson: Flow<String> = dataStore.data.map { it[EXCLUDED_PATHS_JSON] ?: "[]" }
    suspend fun setExcludedPaths(pathsJson: String) {
        dataStore.edit { it[EXCLUDED_PATHS_JSON] = pathsJson }
    }

    val userFolderRoots: Flow<Set<String>> = dataStore.data.map { prefs ->
        val raw = prefs[USER_FOLDER_ROOTS_JSON] ?: return@map emptySet()
        runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
    }

    suspend fun addUserFolderRoot(path: String) {
        dataStore.edit { prefs ->
            val current = prefs[USER_FOLDER_ROOTS_JSON]
                ?.let { runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull() }
                ?: emptySet()
            prefs[USER_FOLDER_ROOTS_JSON] = json.encodeToString(current + path)
        }
    }

    suspend fun removeUserFolderRoot(path: String) {
        dataStore.edit { prefs ->
            val current = prefs[USER_FOLDER_ROOTS_JSON]
                ?.let { runCatching { json.decodeFromString<Set<String>>(it) }.getOrNull() }
                ?: return@edit
            prefs[USER_FOLDER_ROOTS_JSON] = json.encodeToString(current - path)
        }
    }

    val backgroundScanInterval: Flow<String> = dataStore.data.map {
        it[BACKGROUND_SCAN_INTERVAL] ?: "daily"
    }
    suspend fun setBackgroundScanInterval(interval: String) {
        dataStore.edit { it[BACKGROUND_SCAN_INTERVAL] = interval }
    }

    // --- Library tab order ---
    val libraryTabOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[LIBRARY_TAB_ORDER]?.split(",")?.filter { it.isNotBlank() }
            ?: listOf("overview", "local", "playlists", "favorites", "downloads")
    }
    suspend fun setLibraryTabOrder(order: List<String>) {
        dataStore.edit { it[LIBRARY_TAB_ORDER] = order.joinToString(",") }
    }

    // --- Car mode ---
    val carModeBandCount: Flow<Int> = dataStore.data.map { it[CAR_MODE_BAND_COUNT] ?: 10 }
    suspend fun setCarModeBandCount(count: Int) {
        dataStore.edit { it[CAR_MODE_BAND_COUNT] = count.coerceIn(3, 32) }
    }

    // --- Spectrum analyzer ---
    val spectrumAnalyzerEnabled: Flow<Boolean> = dataStore.data.map {
        it[SPECTRUM_ANALYZER_ENABLED] ?: true
    }
    suspend fun setSpectrumAnalyzerEnabled(enabled: Boolean) {
        dataStore.edit { it[SPECTRUM_ANALYZER_ENABLED] = enabled }
    }

    val spectrumShowOnNowPlaying: Flow<Boolean> = dataStore.data.map {
        it[SPECTRUM_SHOW_ON_NOW_PLAYING] ?: true
    }
    suspend fun setSpectrumShowOnNowPlaying(enabled: Boolean) {
        dataStore.edit { it[SPECTRUM_SHOW_ON_NOW_PLAYING] = enabled }
    }

    val spectrumFftSize: Flow<Int> = dataStore.data.map {
        it[SPECTRUM_FFT_SIZE] ?: 8192
    }
    suspend fun setSpectrumFftSize(size: Int) {
        val clamped = when {
            size <= 4096 -> 4096
            size <= 8192 -> 8192
            else -> 16384
        }
        dataStore.edit { it[SPECTRUM_FFT_SIZE] = clamped }
    }

    // --- Device / session (Supabase sync) ---
    val deviceLocalId: Flow<String?> = dataStore.data.map { it[DEVICE_LOCAL_ID] }
    suspend fun setDeviceLocalId(id: String) {
        dataStore.edit { it[DEVICE_LOCAL_ID] = id }
    }

    val deviceRemoteId: Flow<String?> = dataStore.data.map { it[DEVICE_REMOTE_ID] }
    suspend fun setDeviceRemoteId(id: String?) {
        dataStore.edit {
            if (id.isNullOrBlank()) it.remove(DEVICE_REMOTE_ID)
            else it[DEVICE_REMOTE_ID] = id
        }
    }

    // --- Clear all prefs (System) ---
    suspend fun clearAllData() {
        dataStore.edit { it.clear() }
    }
}
