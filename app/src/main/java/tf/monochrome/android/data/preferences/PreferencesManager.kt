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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.domain.model.AudioQuality
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.domain.model.NowPlayingViewMode
import tf.monochrome.android.domain.model.ToneControls
import tf.monochrome.android.performance.LowPerformanceSettings
import tf.monochrome.android.performance.PerformanceProfile
import tf.monochrome.android.radio.RadioPlannerWeights
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "monochrome_prefs")

/** Which catalog(s) drive search and discovery surfaces. BOTH runs TIDAL + Qobuz
 *  + Apple Music; the *_ONLY modes restrict to a single catalog. */
enum class SourceMode { BOTH, TIDAL_ONLY, QOBUZ_ONLY }

/**
 * Format asked of the Apple wrapper. These are the wrapper's own format codes,
 * not a mapping of [AudioQuality] — Apple's ladder doesn't line up with the
 * Qobuz/TIDAL tiers, so it is selected separately.
 */
enum class AppleQuality(val code: String, val label: String, val summary: String) {
    HIRES_LOSSLESS("hires-lossless", "Hi-Res Lossless", "ALAC up to 24-bit/192 kHz — largest files"),
    ALAC("alac", "Lossless", "ALAC up to 24-bit/48 kHz — lossless, smaller"),
    AAC("aac", "High Efficiency", "AAC 256 kbps — lossy, smallest"),
}

/**
 * Which word-level lyrics provider(s) to use when TIDAL has no synced lyrics.
 * BOTH tries NetEase first, then Kugou — each is the other's fallback.
 */
/**
 * The raw preference values [PreferencesManager.lyricsFx] reads, snapshotted so
 * the flow can dedupe on them before deserializing. Exists purely so a write to
 * an unrelated preference doesn't re-parse the Lyrics FX blob.
 */
private data class LyricsFxRaw(
    val json: String?,
    val rotation: Float?,
    val waveSpeed: Float?,
    val shadowDepth: Float?,
    val bassReact: Float?,
)

enum class LyricsWordProvider(val displayName: String) {
    NETEASE_ONLY("NetEase"),
    KUGOU_ONLY("Kugou"),
    BOTH("Both"),
}

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val performanceProfile: PerformanceProfile,
) {
    private val dataStore = context.dataStore

    companion object {
        private const val MAX_SEARCH_HISTORY_SIZE = 10

        /** The app's indigo, and a near-black ground: a sane dark default pair. */
        private const val DEFAULT_CUSTOM_ACCENT = 0xFF5865F2.toInt()
        private const val DEFAULT_CUSTOM_BACKGROUND = 0xFF101014.toInt()

        // Fewer than the catalogue's, because these are pills in a row rather
        // than a list: past half a dozen they are off the end of the screen and
        // the oldest are being kept for nobody.
        private const val MAX_RADIO_SEARCH_HISTORY_SIZE = 6

        // Audio quality
        private val WIFI_QUALITY = stringPreferencesKey("wifi_quality")
        private val CELLULAR_QUALITY = stringPreferencesKey("cellular_quality")


        // Player state
        private val SHUFFLE_ENABLED = booleanPreferencesKey("shuffle_enabled")
        private val REPEAT_MODE = intPreferencesKey("repeat_mode")
        private val VOLUME = doublePreferencesKey("volume")

        // Instance cache
        private val INSTANCES_CACHE = stringPreferencesKey("instances_cache")
        private val INSTANCES_CACHE_TIMESTAMP = longPreferencesKey("instances_cache_timestamp")

        // Theme
        private val THEME = stringPreferencesKey("theme")
        private val THEME_PAPER = stringPreferencesKey("theme_paper")
        private val DYNAMIC_COLORS = booleanPreferencesKey("dynamic_colors")
        // Whether the album palette is allowed past the player and into the
        // app-wide scheme, and whether it gets the ground as well as the accent.
        private val DYNAMIC_COLORS_MENUS = booleanPreferencesKey("dynamic_colors_menus")
        private val DYNAMIC_COLORS_KEEP_BACKGROUND =
            booleanPreferencesKey("dynamic_colors_keep_background")
        // How long the album colours take to cross over, in ms, or
        // ColorBlend.MATCH_BLEND to go on following "Blend Between Tracks".
        private val COLOR_TRANSITION_MS = intPreferencesKey("color_transition_ms")
        // Custom colours: when on, an accent and a ground the listener picked
        // replace whichever preset is selected. Stored as ARGB ints.
        private val CUSTOM_THEME_ENABLED = booleanPreferencesKey("custom_theme_enabled")
        private val CUSTOM_ACCENT = intPreferencesKey("custom_accent_color")
        private val CUSTOM_BACKGROUND = intPreferencesKey("custom_background_color")

        // Scrobbling
        private val LASTFM_SESSION_KEY = stringPreferencesKey("lastfm_session_key")
        private val LASTFM_USERNAME = stringPreferencesKey("lastfm_username")
        private val LASTFM_API_KEY = stringPreferencesKey("lastfm_api_key")
        private val LASTFM_API_SECRET = stringPreferencesKey("lastfm_api_secret")
        private val LASTFM_ENABLED = booleanPreferencesKey("lastfm_enabled")
        private val LISTENBRAINZ_TOKEN = stringPreferencesKey("listenbrainz_token")
        private val LISTENBRAINZ_ENABLED = booleanPreferencesKey("listenbrainz_enabled")

        // Discord presence. Deliberately NOT in SETTINGS_SYNC_KEYS: a Discord
        // user token is an unscoped credential for the whole account, and
        // syncing it would copy it onto every device signed into Tryptify and
        // through the sync backend on the way. It stays on the device it was
        // typed into.
        private val DISCORD_TOKEN = stringPreferencesKey("discord_token")
        private val DISCORD_APPLICATION_ID = stringPreferencesKey("discord_application_id")
        private val DISCORD_PRESENCE_ENABLED = booleanPreferencesKey("discord_presence_enabled")
        private val DISCORD_PRESENCE_ANIMATED = booleanPreferencesKey("discord_presence_animated")
        private val DISCORD_UPLOAD_CHANNEL = stringPreferencesKey("discord_upload_channel")

        // Custom API endpoint
        private val CUSTOM_API_ENDPOINT = stringPreferencesKey("custom_api_endpoint")
        private val QOBUZ_INSTANCE_URL = stringPreferencesKey("qobuz_instance_url")
        private val APPLE_INSTANCE_URL = stringPreferencesKey("apple_instance_url")
        private val APPLE_WRAPPER_URL = stringPreferencesKey("apple_wrapper_url")
        private val APPLE_WRAPPER_SECRET = stringPreferencesKey("apple_wrapper_secret")
        private val APPLE_ATMOS_PREFERRED = booleanPreferencesKey("apple_atmos_preferred")
        private val APPLE_QUALITY = stringPreferencesKey("apple_quality")
        private val DEV_MODE_ENABLED = booleanPreferencesKey("dev_mode_enabled")
        private val SOURCE_MODE = stringPreferencesKey("source_mode")

        // Lyrics 3D appearance (legacy per-field keys, read for migration only)
        private val LYRICS_3D_ROTATION = floatPreferencesKey("lyrics_3d_rotation")
        private val LYRICS_3D_WAVE_SPEED = floatPreferencesKey("lyrics_3d_wave_speed")
        private val LYRICS_3D_SHADOW_DEPTH = floatPreferencesKey("lyrics_3d_shadow_depth")
        private val LYRICS_BASS_REACT = floatPreferencesKey("lyrics_bass_react")
        // Full Player Visuals Studio settings as one JSON blob (takes precedence).
        private val LYRICS_FX_JSON = stringPreferencesKey("lyrics_fx_json")
        private val GLOBE_FX_JSON = stringPreferencesKey("globe_fx_json")
        // User-saved Lyrics FX presets (a JSON array of {name, settings}).
        private val LYRICS_FX_CUSTOM_PRESETS_JSON = stringPreferencesKey("lyrics_fx_custom_presets_json")
        // Player-chrome (transport button) liquid-glass settings, one JSON blob.
        private val PLAYER_GLASS_JSON = stringPreferencesKey("player_glass_json")
        // User-saved Player Glass themes (a JSON array of {name, settings}).
        private val PLAYER_GLASS_CUSTOM_PRESETS_JSON = stringPreferencesKey("player_glass_custom_presets_json")
        // Mini-player liquid-glass settings — same shape as PLAYER_GLASS_JSON but
        // tuned independently (Player Visuals Studio › "Mini Player" tab).
        private val MINI_PLAYER_GLASS_JSON = stringPreferencesKey("mini_player_glass_json")

        // Atmos renderer profile (mode / target layout / HRTF profile). Kept
        // device-local — the layout tracks the connected DAC and the HRTF is a
        // local measurement — so it is deliberately NOT in SETTINGS_SYNC_KEYS.
        private val RENDERER_PROFILE_JSON = stringPreferencesKey("renderer_profile_json")

        // Player / display
        private val PLAYER_DYNAMIC_COLOR = booleanPreferencesKey("player_dynamic_color")
        private val PLAYER_BLURRED_BACKGROUND = booleanPreferencesKey("player_blurred_background")
        private val APP_TARGET_FPS = intPreferencesKey("app_target_fps")
        private val APP_RENDER_RESOLUTION = intPreferencesKey("app_render_resolution")
        // Device-local, like the two above it: which bars a panel has and
        // whether you want them is a property of the phone in your hand, not of
        // the account. Deliberately absent from SETTINGS_SYNC_KEYS.
        private val IMMERSIVE_FULL_SCREEN = booleanPreferencesKey("immersive_full_screen")

        // Low performance mode. The automatic DeviceTier already trims effects on
        // weak hardware; these are the user's own override, for a flagship on a
        // long day out or a GPU whose driver hates the AGSL glass shader.
        // LOW_PERFORMANCE_MODE is a convenience master: it has no independent
        // effect, it just reads/writes the three real switches together.
        // Discover's "familiar ↔ adventurous" knob, 0..1. Synced: unlike the
        // performance switches this is taste, not hardware, and it should
        // follow the listener between devices.

        // Genres the listener has hearted on the map. Taste, like the knob
        // above, so it syncs. The recents beside it are history and stay local,
        // matching how the app treats play history everywhere else.
        private val DISCOVERY_HEARTED_GENRES = stringSetPreferencesKey("discovery_hearted_genres")
        private val FAVOURITE_STATIONS = stringSetPreferencesKey("world_radio_favourite_stations")
        private val DISCOVERY_RECENT_GENRES = stringPreferencesKey("discovery_recent_genres")
        private val DISCOVERY_SORT = stringPreferencesKey("discovery_sort")

        /** How many genres the "recently played" rail remembers. */
        private const val MAX_RECENT_GENRES = 12

        private val LOW_PERFORMANCE_MODE = booleanPreferencesKey("low_performance_mode")
        private val DISABLE_ANIMATIONS = booleanPreferencesKey("disable_animations")
        private val LEGACY_PLAYER = booleanPreferencesKey("legacy_player")
        private val DISABLE_LIQUID_GLASS = booleanPreferencesKey("disable_liquid_glass")

        // Interface
        private val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        private val GAPLESS_NO_RESAMPLE = booleanPreferencesKey("gapless_no_resample")
        private val WHATS_NEW_SEEN_VERSION = intPreferencesKey("whats_new_seen_version")
        private val WHATS_NEW_NEVER_SHOW = booleanPreferencesKey("whats_new_never_show")
        private val UPDATE_LAST_CHECKED_AT = longPreferencesKey("update_last_checked_at")
        private val UPDATE_LATEST_VERSION = stringPreferencesKey("update_latest_version")
        private val UPDATE_LATEST_URL = stringPreferencesKey("update_latest_url")
        private val UPDATE_DISMISSED_VERSION = stringPreferencesKey("update_dismissed_version")
        private val SHOW_EXPLICIT_BADGES = booleanPreferencesKey("show_explicit_badges")

        // Audio extras
        private val NORMALIZATION_ENABLED = booleanPreferencesKey("normalization_enabled")
        private val CROSSFADE_DURATION = intPreferencesKey("crossfade_duration")

        // Downloads
        private val DOWNLOAD_QUALITY = stringPreferencesKey("download_quality")
        private val DOWNLOAD_FOLDER_URI = stringPreferencesKey("download_folder_uri")

        // Playback speed
        private val PLAYBACK_SPEED = stringPreferencesKey("playback_speed")
        private val PRESERVE_PITCH = booleanPreferencesKey("preserve_pitch")
        private val PITCH_SEMITONES = stringPreferencesKey("pitch_semitones")

        // Appearance extras
        private val FONT_SCALE = floatPreferencesKey("font_scale")
        // When true, FONT_SCALE is ignored and the OS accessibility font size is
        // used instead (Configuration.fontScale).
        private val FONT_SCALE_FOLLOW_SYSTEM = booleanPreferencesKey("font_scale_follow_system")
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
        private val LYRICS_WORD_PROVIDER = stringPreferencesKey("lyrics_word_provider")
        private val DOWNLOAD_LYRICS = booleanPreferencesKey("download_lyrics")
        private val AUTO_DOWNLOAD_LIKED = booleanPreferencesKey("auto_download_liked")
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

        // Radio ranking weights, all scored on-device by LocalRadioPlanner.
        private val RADIO_WEIGHT_LOCAL_LIBRARY = floatPreferencesKey("radio_weight_local_library")
        private val RADIO_WEIGHT_QOBUZ = floatPreferencesKey("radio_weight_qobuz")
        private val RADIO_WEIGHT_SPOTIFY_DISCOVERY = floatPreferencesKey("radio_weight_spotify_discovery")
        private val RADIO_WEIGHT_CANONICAL_VERSION_BIAS = floatPreferencesKey("radio_weight_canonical_version_bias")
        private val RADIO_WEIGHT_NOVELTY = floatPreferencesKey("radio_weight_novelty")
        private val RADIO_WEIGHT_FAMILIARITY = floatPreferencesKey("radio_weight_familiarity")
        private val RADIO_WEIGHT_ARTIST_SIMILARITY = floatPreferencesKey("radio_weight_artist_similarity")
        private val RADIO_WEIGHT_GENRE_TAG_SIMILARITY = floatPreferencesKey("radio_weight_genre_tag_similarity")
        private val RADIO_WEIGHT_ERA_CONSISTENCY = floatPreferencesKey("radio_weight_era_consistency")
        private val RADIO_WEIGHT_AVOID_RECENTLY_PLAYED = floatPreferencesKey("radio_weight_avoid_recently_played")
        private val RADIO_WEIGHT_DISCOVERY_DISTANCE = floatPreferencesKey("radio_weight_discovery_distance")

        // Spotify (PKCE OAuth tokens for playlist import)
        private val SPOTIFY_ACCESS_TOKEN = stringPreferencesKey("spotify_access_token")
        private val SPOTIFY_REFRESH_TOKEN = stringPreferencesKey("spotify_refresh_token")
        private val SPOTIFY_TOKEN_EXPIRES_AT = longPreferencesKey("spotify_token_expires_at")
        private val SPOTIFY_USER_NAME = stringPreferencesKey("spotify_user_name")

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
        // Per-ear AutoEQ: right-channel bands + the 2-channel switch. The R
        // list persists even while the switch is off, so toggling stereo off
        // and back on is non-destructive.
        private val EQ_BANDS_R_JSON = stringPreferencesKey("eq_bands_r_json")
        private val EQ_STEREO_MODE = booleanPreferencesKey("eq_stereo_mode")
        private val EQ_MEASUREMENT_R_JSON = stringPreferencesKey("eq_measurement_r_json")
        private val EQ_CUSTOM_TARGETS_JSON = stringPreferencesKey("eq_custom_targets_json")
        private val EQ_SELECTED_HEADPHONE_ID = stringPreferencesKey("eq_selected_headphone_id")
        private val EQ_SELECTED_HEADPHONE_NAME = stringPreferencesKey("eq_selected_headphone_name")
        private val EQ_MEASUREMENT_JSON = stringPreferencesKey("eq_measurement_json")
        private val EQ_UPLOADED_HEADPHONES_JSON = stringPreferencesKey("eq_uploaded_headphones_json")
        // Automatic preamp: preamp tracks -(largest band boost) so the filter
        // sum can never push the signal above 0 dBFS. Manual slider disabled
        // while on.
        private val EQ_AUTO_PREAMP = booleanPreferencesKey("eq_auto_preamp")
        // System-wide AutoEQ: apply the correction to ALL device audio via a
        // global output-mix effect (Wavelet-style), not just this app's playback.
        private val SYSTEM_WIDE_AUTOEQ_ENABLED = booleanPreferencesKey("system_wide_autoeq_enabled")
        // Bass/treble tone shelves layered after the AutoEQ in that same effect.
        private val SYSTEM_TONE_CONTROLS_JSON = stringPreferencesKey("system_tone_controls_json")

        // Parametric EQ (independent of AutoEQ)
        private val PARAM_EQ_ENABLED = booleanPreferencesKey("param_eq_enabled")
        private val PARAM_EQ_ACTIVE_PRESET_ID = stringPreferencesKey("param_eq_active_preset_id")
        private val PARAM_EQ_PREAMP = doublePreferencesKey("param_eq_preamp")
        private val PARAM_EQ_BANDS_JSON = stringPreferencesKey("param_eq_bands_json")

        // Library / Local Media
        private val EXCLUDED_PATHS_JSON = stringPreferencesKey("excluded_paths_json")
        private val USER_FOLDER_ROOTS_JSON = stringPreferencesKey("user_folder_roots_json")

        // DSP Mixer
        private val DSP_ENABLED = booleanPreferencesKey("dsp_enabled")
        private val DSP_STATE_JSON = stringPreferencesKey("dsp_state_json")
        private val MIXER_CHANNEL_DYNAMIC = booleanPreferencesKey("mixer_channel_dynamic")
        private val DSP_BLOCK_SIZE = intPreferencesKey("dsp_block_size")
        private val DOWNLOAD_QUEUE_JSON = stringPreferencesKey("download_queue_json")
        private val USB_BIT_PERFECT_ENABLED = booleanPreferencesKey("usb_bit_perfect_enabled")
        private val USB_EXCLUSIVE_BIT_PERFECT_ENABLED =
            booleanPreferencesKey("usb_exclusive_bit_perfect_enabled")
        private val MULTICHANNEL_DOWNMIX_ENABLED =
            booleanPreferencesKey("multichannel_downmix_enabled")
        // Powers of two mirroring the user-facing chip row in Settings.
        // Native engine's static MAX_BLOCK_SIZE caps the largest entry; bump
        // both together if you add another step.
        val DSP_BLOCK_SIZES = listOf(128, 256, 512, 1024, 2048, 4096, 8192, 16384)

        // Library tab order
        private val LIBRARY_TAB_ORDER = stringPreferencesKey("library_tab_order")

        // Library sort selections (serialized "<KEY>:asc" / "<KEY>:desc")
        private val SONG_SORT = stringPreferencesKey("library_song_sort")
        private val ALBUM_SORT = stringPreferencesKey("library_album_sort")
        private val ARTIST_SORT = stringPreferencesKey("library_artist_sort")

        // Car mode
        private val CAR_MODE_BAND_COUNT = intPreferencesKey("car_mode_band_count")

        // Search
        private val SEARCH_HISTORY_JSON = stringPreferencesKey("search_history_json")
        private val RADIO_SEARCH_HISTORY_JSON = stringPreferencesKey("radio_search_history_json")

        // Spectrum analyzer
        private val SPECTRUM_ANALYZER_ENABLED = booleanPreferencesKey("spectrum_analyzer_enabled")
        private val SPECTRUM_SHOW_ON_NOW_PLAYING = booleanPreferencesKey("spectrum_show_on_now_playing")
        private val SPECTRUM_FFT_SIZE = intPreferencesKey("spectrum_fft_size")

        // Device / session (Supabase sync)
        private val DEVICE_LOCAL_ID = stringPreferencesKey("device_local_id")
        private val DEVICE_REMOTE_ID = stringPreferencesKey("device_remote_id")

        // Onboarding
        private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")

        // ── Settings cloud-sync allow-list ───────────────────────────────────
        // ONLY these preferences are exported to the user's Supabase settings
        // row. It is an allow-list on purpose: anything NOT here (auth tokens &
        // OAuth secrets, device ids, per-device GPU/fps tuning, device-local
        // file/SAF paths, caches, transient player state, one-shot flags, and
        // the legacy lyrics keys superseded by LYRICS_FX_JSON) never leaves the
        // device — and a newly added key defaults to "not synced" until it's
        // deliberately added here.
        val SETTINGS_SYNC_KEYS: Set<Preferences.Key<*>> = setOf(
            WIFI_QUALITY, CELLULAR_QUALITY,
            THEME, THEME_PAPER, DYNAMIC_COLORS,
            DYNAMIC_COLORS_MENUS, DYNAMIC_COLORS_KEEP_BACKGROUND, COLOR_TRANSITION_MS,
            CUSTOM_THEME_ENABLED, CUSTOM_ACCENT, CUSTOM_BACKGROUND,
            FONT_SCALE, FONT_SCALE_FOLLOW_SYSTEM,
            GAPLESS_PLAYBACK, GAPLESS_NO_RESAMPLE, SHOW_EXPLICIT_BADGES,
            NORMALIZATION_ENABLED, CROSSFADE_DURATION, MULTICHANNEL_DOWNMIX_ENABLED,
            PLAYBACK_SPEED, PRESERVE_PITCH, PITCH_SEMITONES,
            DOWNLOAD_QUALITY, DOWNLOAD_LYRICS, AUTO_DOWNLOAD_LIKED,
            LASTFM_ENABLED, LASTFM_USERNAME, LISTENBRAINZ_ENABLED,
            CUSTOM_API_ENDPOINT, QOBUZ_INSTANCE_URL, APPLE_INSTANCE_URL, APPLE_WRAPPER_URL, SOURCE_MODE, DEV_MODE_ENABLED,
            NOW_PLAYING_VIEW_MODE, PLAYER_DYNAMIC_COLOR, PLAYER_BLURRED_BACKGROUND,
            ROMAJI_LYRICS, LYRICS_WORD_PROVIDER,
            LYRICS_FX_JSON, LYRICS_FX_CUSTOM_PRESETS_JSON, GLOBE_FX_JSON, PLAYER_GLASS_JSON,
            PLAYER_GLASS_CUSTOM_PRESETS_JSON, MINI_PLAYER_GLASS_JSON,
            VISUALIZER_SENSITIVITY, VISUALIZER_BRIGHTNESS,
            VISUALIZER_ENGINE_ENABLED, VISUALIZER_AUTO_SHUFFLE, VISUALIZER_PRESET_ID,
            VISUALIZER_ROTATION_SECONDS, VISUALIZER_SHOW_FPS, VISUALIZER_FULLSCREEN,
            VISUALIZER_TOUCH_WAVEFORM, VISUALIZER_FAVORITE_PRESETS,
            SPECTRUM_ANALYZER_ENABLED, SPECTRUM_SHOW_ON_NOW_PLAYING, SPECTRUM_FFT_SIZE,
            EQ_ENABLED, EQ_ACTIVE_PRESET_ID, EQ_TARGET_ID, EQ_PREAMP, EQ_BANDS_JSON,
            EQ_CUSTOM_TARGETS_JSON, EQ_SELECTED_HEADPHONE_ID, EQ_SELECTED_HEADPHONE_NAME,
            EQ_UPLOADED_HEADPHONES_JSON, EQ_AUTO_PREAMP,
            EQ_BANDS_R_JSON, EQ_STEREO_MODE, SYSTEM_TONE_CONTROLS_JSON,
            PARAM_EQ_ENABLED, PARAM_EQ_ACTIVE_PRESET_ID, PARAM_EQ_PREAMP, PARAM_EQ_BANDS_JSON,
            DSP_ENABLED, DSP_STATE_JSON, MIXER_CHANNEL_DYNAMIC,
            LIBRARY_TAB_ORDER, CAR_MODE_BAND_COUNT,
            AI_RADIO_ENABLED,
            RADIO_WEIGHT_LOCAL_LIBRARY, RADIO_WEIGHT_QOBUZ, RADIO_WEIGHT_SPOTIFY_DISCOVERY,
            RADIO_WEIGHT_CANONICAL_VERSION_BIAS, RADIO_WEIGHT_NOVELTY, RADIO_WEIGHT_FAMILIARITY,
            RADIO_WEIGHT_ARTIST_SIMILARITY, RADIO_WEIGHT_GENRE_TAG_SIMILARITY,
            RADIO_WEIGHT_ERA_CONSISTENCY,
            RADIO_WEIGHT_AVOID_RECENTLY_PLAYED, RADIO_WEIGHT_DISCOVERY_DISTANCE,
            DISCOVERY_HEARTED_GENRES,
            DISCOVERY_SORT,
        )
        private val SETTINGS_SYNC_KEY_NAMES: Set<String> = SETTINGS_SYNC_KEYS.map { it.name }.toSet()
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

    // Player state
    val shuffleEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHUFFLE_ENABLED] ?: false
    }

    val repeatMode: Flow<Int> = dataStore.data.map { prefs ->
        prefs[REPEAT_MODE] ?: 0
    }

    val volume: Flow<Double> = dataStore.data.map { prefs ->
        // A stored 0.0 silences the app on launch (the slider can't be
        // grabbed if you can't hear what's playing). Treat exact silence
        // as a stale/uninitialised state and fall back to full volume.
        val stored = prefs[VOLUME] ?: 1.0
        if (stored <= 0.0) 1.0 else stored
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

    /**
     * Which paper the light themes are printed on — "crisp" true white, or
     * "warm" off-white. One axis across every light variant rather than a
     * setting per theme: it is a preference about glare and about how a screen
     * should feel, not about Nord.
     */
    val themePaper: Flow<String> = dataStore.data.map { prefs ->
        prefs[THEME_PAPER] ?: "crisp"
    }

    suspend fun setThemePaper(paper: String) {
        dataStore.edit { it[THEME_PAPER] = paper }
    }

    suspend fun setDynamicColors(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLORS] = enabled }
    }

    /**
     * Whether the album palette also drives the app's own accent and ground,
     * not just the player. Off by default: the menus following whatever is
     * playing is a deliberate taste, not the sane default.
     */
    val dynamicColorMenus: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLORS_MENUS] ?: false
    }

    suspend fun setDynamicColorMenus(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLORS_MENUS] = enabled }
    }

    /**
     * The bypass for the ground half of [dynamicColorMenus]: the accent still
     * follows the cover, the background stays where the theme put it.
     */
    val dynamicColorKeepBackground: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DYNAMIC_COLORS_KEEP_BACKGROUND] ?: false
    }

    suspend fun setDynamicColorKeepBackground(enabled: Boolean) {
        dataStore.edit { it[DYNAMIC_COLORS_KEEP_BACKGROUND] = enabled }
    }

    /**
     * How long the album colours take to cross over, in milliseconds, or
     * [tf.monochrome.android.ui.theme.ColorBlend.MATCH_BLEND] (the default) to
     * keep deriving it from "Blend Between Tracks".
     */
    val colorTransitionMs: Flow<Int> = dataStore.data.map { prefs ->
        prefs[COLOR_TRANSITION_MS] ?: tf.monochrome.android.ui.theme.ColorBlend.MATCH_BLEND
    }

    suspend fun setColorTransitionMs(millis: Int) {
        dataStore.edit { it[COLOR_TRANSITION_MS] = millis }
    }

    /**
     * Whether the listener's own two colours override the selected preset.
     *
     * Off by default: the presets are the designed path, and a custom scheme is
     * only as good as the pair someone picks. When it is on, [customAccentColor]
     * and [customBackgroundColor] are what the theme is built from.
     */
    val customThemeEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[CUSTOM_THEME_ENABLED] ?: false
    }

    suspend fun setCustomThemeEnabled(enabled: Boolean) {
        dataStore.edit { it[CUSTOM_THEME_ENABLED] = enabled }
    }

    /** The accent for the custom scheme. Defaults to the app's own indigo. */
    val customAccentColor: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CUSTOM_ACCENT] ?: DEFAULT_CUSTOM_ACCENT
    }

    suspend fun setCustomAccentColor(argb: Int) {
        dataStore.edit { it[CUSTOM_ACCENT] = argb }
    }

    /** The ground for the custom scheme; its luminance decides dark vs light. */
    val customBackgroundColor: Flow<Int> = dataStore.data.map { prefs ->
        prefs[CUSTOM_BACKGROUND] ?: DEFAULT_CUSTOM_BACKGROUND
    }

    suspend fun setCustomBackgroundColor(argb: Int) {
        dataStore.edit { it[CUSTOM_BACKGROUND] = argb }
    }

    // Scrobbling - Last.fm
    val lastFmSessionKey: Flow<String?> = dataStore.data.map { prefs ->
        prefs[LASTFM_SESSION_KEY]
    }

    /**
     * The key genre **charts** read with — the listener's own if they entered
     * one, otherwise the key built into this app.
     *
     * Sharing one key here is safe because a tag chart is a public read: no
     * account, no signature, nothing attributable to a person. The override is
     * the escape hatch if the shared key is ever rate-limited or revoked, and
     * how someone building this themselves supplies their own.
     */
    val lastFmChartsApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[LASTFM_API_KEY]?.takeIf { it.isNotBlank() } ?: BuildConfig.LASTFM_API_KEY
    }

    /**
     * The credentials **scrobbling** signs with — the listener's own, or nothing.
     *
     * Deliberately no fallback to the bundled key. A scrobble is a write against
     * a named person's listening history, signed with the shared secret, and a
     * secret shipped inside an APK is extractable by anyone who looks: whoever
     * pulled it could sign traffic that Last.fm attributes to this application,
     * and the resulting suspension would land on every listener at once. Each
     * person registers their own pair, so the blast radius of a leak is one
     * account — their own.
     */
    val lastFmApiKey: Flow<String> = dataStore.data.map { prefs ->
        prefs[LASTFM_API_KEY].orEmpty()
    }

    val lastFmApiSecret: Flow<String> = dataStore.data.map { prefs ->
        prefs[LASTFM_API_SECRET].orEmpty()
    }

    suspend fun setLastFmApiCredentials(apiKey: String, apiSecret: String) {
        dataStore.edit {
            it[LASTFM_API_KEY] = apiKey.trim()
            it[LASTFM_API_SECRET] = apiSecret.trim()
        }
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

    // --- Discord presence ---
    val discordToken: Flow<String> = dataStore.data.map { prefs ->
        prefs[DISCORD_TOKEN].orEmpty()
    }

    /**
     * The Discord application whose media proxy mints album-art assets.
     *
     * Optional, and the presence works without it — just without artwork. A
     * gateway-set activity can only carry an image Discord itself hosts, and
     * the only route from an arbitrary cover URL to one of those runs through
     * an application's external-assets endpoint.
     */
    val discordApplicationId: Flow<String> = dataStore.data.map { prefs ->
        prefs[DISCORD_APPLICATION_ID].orEmpty()
    }

    val discordPresenceEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DISCORD_PRESENCE_ENABLED] ?: false
    }

    suspend fun setDiscordCredentials(token: String, applicationId: String) {
        dataStore.edit {
            // Normalised on the way in, not on the way out: a token pasted with
            // a stray quote or an "Authorization:" label in front of it is
            // indistinguishable from a wrong one once Discord has refused it.
            it[DISCORD_TOKEN] =
                tf.monochrome.android.data.presence.DiscordPresence.normalizeToken(token)
            it[DISCORD_APPLICATION_ID] = applicationId.trim()
        }
    }

    suspend fun setDiscordPresenceEnabled(enabled: Boolean) {
        dataStore.edit { it[DISCORD_PRESENCE_ENABLED] = enabled }
    }

    /**
     * Whether the presence card carries the animated spectrum badge.
     *
     * Separate from the presence itself because it is a different thing to want
     * off: the badge is decoration, and someone may want the track shown
     * without a moving graphic on their profile all day.
     */
    val discordPresenceAnimated: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[DISCORD_PRESENCE_ANIMATED] ?: true
    }

    suspend fun setDiscordPresenceAnimated(enabled: Boolean) {
        dataStore.edit { it[DISCORD_PRESENCE_ANIMATED] = enabled }
    }

    /**
     * A channel to post the composited artwork into, for its URL.
     *
     * Drawing the spectrum across the cover means building an image per track,
     * and Discord will only render an image it can fetch. A phone has no public
     * address, so the file is posted as an attachment and the resulting CDN link
     * is what the card points at. Empty means don't: the card keeps the plain
     * cover and the circular badge.
     */
    val discordUploadChannel: Flow<String> = dataStore.data.map { prefs ->
        prefs[DISCORD_UPLOAD_CHANNEL].orEmpty()
    }

    suspend fun setDiscordUploadChannel(id: String) {
        dataStore.edit { it[DISCORD_UPLOAD_CHANNEL] = id.trim() }
    }

    suspend fun clearDiscordCredentials() {
        dataStore.edit {
            it.remove(DISCORD_TOKEN)
            it.remove(DISCORD_APPLICATION_ID)
            it[DISCORD_PRESENCE_ENABLED] = false
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

    // Apple Music instance — the TrypT HiFi server that exposes /api/apple/*.
    // Usually the same server as the Qobuz instance, so InstanceManager falls back
    // to the Qobuz URL when this is unset (see appleInstanceOrNull).
    val appleInstanceUrl: Flow<String?> = dataStore.data.map { prefs ->
        prefs[APPLE_INSTANCE_URL]
    }

    suspend fun setAppleInstanceUrl(endpoint: String?) {
        dataStore.edit {
            if (endpoint != null) {
                it[APPLE_INSTANCE_URL] = endpoint
            } else {
                it.remove(APPLE_INSTANCE_URL)
            }
        }
    }

    // Tailnet-direct wrapper/agent: when set, Apple tracks decrypt + stream
    // straight from the home decrypt-agent over Tailscale (no cloud). Holds the
    // agent's base URL; the secret matches the agent's AGENT_SECRET.
    val appleWrapperUrl: Flow<String?> = dataStore.data.map { it[APPLE_WRAPPER_URL] }

    suspend fun setAppleWrapperUrl(endpoint: String?) {
        dataStore.edit {
            if (endpoint != null) it[APPLE_WRAPPER_URL] = endpoint else it.remove(APPLE_WRAPPER_URL)
        }
    }

    val appleWrapperSecret: Flow<String?> = dataStore.data.map { it[APPLE_WRAPPER_SECRET] }

    suspend fun setAppleWrapperSecret(secret: String?) {
        dataStore.edit {
            if (secret != null) it[APPLE_WRAPPER_SECRET] = secret else it.remove(APPLE_WRAPPER_SECRET)
        }
    }

    /**
     * Prefer the Dolby Atmos master for Apple tracks. Atmos is a separate
     * encode (EC-3, spatial) rather than a quality tier of ALAC, so it is a
     * toggle rather than another step on [appleQuality]: when on, Apple
     * downloads ask the wrapper for `atmos` and only fall back to the chosen
     * stereo format if the track has no Atmos master.
     */
    val appleAtmosPreferred: Flow<Boolean> = dataStore.data.map { it[APPLE_ATMOS_PREFERRED] ?: false }

    suspend fun setAppleAtmosPreferred(enabled: Boolean) {
        dataStore.edit { it[APPLE_ATMOS_PREFERRED] = enabled }
    }

    /**
     * Format requested from the Apple wrapper, independent of the Qobuz/TIDAL
     * [downloadQuality] tier — Apple's ladder is its own (`hires-lossless`,
     * `alac`, `aac`) and doesn't map cleanly onto HI_RES/LOSSLESS/HIGH.
     * Defaults to ALAC: lossless, and universally available.
     */
    val appleQuality: Flow<AppleQuality> = dataStore.data.map { prefs ->
        prefs[APPLE_QUALITY]?.let { runCatching { AppleQuality.valueOf(it) }.getOrNull() }
            ?: AppleQuality.ALAC
    }

    suspend fun setAppleQuality(quality: AppleQuality) {
        dataStore.edit { it[APPLE_QUALITY] = quality.name }
    }

    /**
     * Which catalog(s) drive search/discovery. BOTH (default) is the
     * existing fan-out behavior; TIDAL_ONLY skips the Qobuz call so search
     * doesn't surface Qobuz hits; QOBUZ_ONLY skips the TIDAL pool. Stream
     * playback and downloads still follow the per-track PlaybackSource —
     * the setting only governs which catalogs feed search results.
     */
    val sourceMode: Flow<SourceMode> = dataStore.data.map { prefs ->
        // A stored "APPLE_ONLY" from before Apple was dropped no longer names a
        // constant, so valueOf throws, getOrNull swallows it and it reads as
        // BOTH — which is what anyone left on it should get, since neither the
        // picker nor search can represent Apple any more.
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

    /**
     * Refuse to resample across a gapless transition. On by default.
     *
     * A track that plays at a different sample rate forces the output to be
     * torn down and rebuilt, which is the very gap gapless removes. The only
     * way round it is to resample everything to one fixed rate — exactly what
     * the bit-perfect USB path exists not to do — so by default that one
     * transition takes the gap, which is what every bit-perfect player does.
     * Turn this off to keep the hand-off seamless and let the system resample.
     */
    val gaplessNoResample: Flow<Boolean> = dataStore.data.map { it[GAPLESS_NO_RESAMPLE] ?: true }

    /** versionCode whose "What's New" the user has already seen. 0 = none. */
    val whatsNewSeenVersion: Flow<Int> = dataStore.data.map { it[WHATS_NEW_SEEN_VERSION] ?: 0 }

    /** Set once the user asks never to be told about updates again. */
    val whatsNewNeverShow: Flow<Boolean> = dataStore.data.map { it[WHATS_NEW_NEVER_SHOW] ?: false }

    suspend fun setGaplessNoResample(enabled: Boolean) {
        dataStore.edit { it[GAPLESS_NO_RESAMPLE] = enabled }
    }

    suspend fun setWhatsNewSeenVersion(versionCode: Int) {
        dataStore.edit { it[WHATS_NEW_SEEN_VERSION] = versionCode }
    }

    // --- Update availability (GitHub Releases) ---
    //
    // Device-local only, and deliberately not in SETTINGS_SYNC_KEYS: these
    // describe what *this* install has checked and dismissed, and syncing them
    // would hide an update on a device that hasn't been offered it yet.

    val updateLastCheckedAt: Flow<Long> = dataStore.data.map { it[UPDATE_LAST_CHECKED_AT] ?: 0L }
    val updateLatestVersion: Flow<String?> = dataStore.data.map { it[UPDATE_LATEST_VERSION] }
    val updateLatestUrl: Flow<String?> = dataStore.data.map { it[UPDATE_LATEST_URL] }

    /** Version the user has waved away; the bar stays gone until a newer one. */
    val updateDismissedVersion: Flow<String?> = dataStore.data.map { it[UPDATE_DISMISSED_VERSION] }

    suspend fun setUpdateLastCheckedAt(atMs: Long) {
        dataStore.edit { it[UPDATE_LAST_CHECKED_AT] = atMs }
    }

    suspend fun setUpdateLatestVersion(version: String) {
        dataStore.edit { it[UPDATE_LATEST_VERSION] = version }
    }

    suspend fun setUpdateLatestUrl(url: String) {
        dataStore.edit { it[UPDATE_LATEST_URL] = url }
    }

    suspend fun setUpdateDismissedVersion(version: String) {
        dataStore.edit { it[UPDATE_DISMISSED_VERSION] = version }
    }

    suspend fun setWhatsNewNeverShow(enabled: Boolean) {
        dataStore.edit { it[WHATS_NEW_NEVER_SHOW] = enabled }
    }

    val showExplicitBadges: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[SHOW_EXPLICIT_BADGES] ?: true
    }
    suspend fun setShowExplicitBadges(enabled: Boolean) {
        dataStore.edit { it[SHOW_EXPLICIT_BADGES] = enabled }
    }


    // --- Audio extras ---
    val normalizationEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[NORMALIZATION_ENABLED] ?: false
    }
    suspend fun setNormalizationEnabled(enabled: Boolean) {
        dataStore.edit { it[NORMALIZATION_ENABLED] = enabled }
    }

    /**
     * The pending download queue, serialized. Persisted so a queue of any size
     * outlives the process that made it — a fifty-track album should not need
     * re-requesting because the app was swapped out mid-download.
     */
    val downloadQueueJson: Flow<String?> = dataStore.data.map { it[DOWNLOAD_QUEUE_JSON] }

    suspend fun setDownloadQueueJson(json: String) {
        dataStore.edit { it[DOWNLOAD_QUEUE_JSON] = json }
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

    /**
     * Transposition applied independently of the tempo, in semitones.
     *
     * Separate from [playbackSpeed] because it is a different operation: speed
     * resamples (exact ratio, tempo moves with it), this transposes through a
     * phase vocoder (tempo stays, accuracy bounded by the analysis block).
     * Stored as a string for the same reason the speed is — a float rendered
     * through DataStore's own encoding would round-trip lossily.
     */
    val pitchSemitones: Flow<Float> = dataStore.data.map { prefs ->
        prefs[PITCH_SEMITONES]?.toFloatOrNull() ?: 0f
    }
    suspend fun setPitchSemitones(semitones: Float) {
        dataStore.edit { it[PITCH_SEMITONES] = semitones.toString() }
    }
    suspend fun setPreservePitch(enabled: Boolean) {
        dataStore.edit { it[PRESERVE_PITCH] = enabled }
    }

    // --- Font scale ---
    // The UI offers five fixed steps (see FONT_SCALE_PRESETS) rather than a free
    // slider. The stored value is still a plain float so pre-existing arbitrary
    // scales keep working and simply snap to the nearest step in the picker.
    val fontScale: Flow<Float> = dataStore.data.map { prefs ->
        prefs[FONT_SCALE] ?: 1.0f
    }
    suspend fun setFontScale(scale: Float) {
        dataStore.edit { it[FONT_SCALE] = scale.coerceIn(0.5f, 2.0f) }
    }

    /** When true the app follows the OS font size and ignores [fontScale]. */
    val fontScaleFollowSystem: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[FONT_SCALE_FOLLOW_SYSTEM] ?: false
    }
    suspend fun setFontScaleFollowSystem(enabled: Boolean) {
        dataStore.edit { it[FONT_SCALE_FOLLOW_SYSTEM] = enabled }
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
    val searchHistory: Flow<List<String>> = dataStore.data
        .map { it[SEARCH_HISTORY_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            raw?.let { s ->
                runCatching { json.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())
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

    /**
     * What was last looked up on the world radio globe.
     *
     * Its own key rather than a share of the catalogue's history above: one is
     * artists and albums, the other is places and station names, and offering
     * either as a suggestion for the other is noise in both directions.
     */
    val radioSearchHistory: Flow<List<String>> = dataStore.data
        .map { it[RADIO_SEARCH_HISTORY_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            raw?.let { s ->
                runCatching { json.decodeFromString<List<String>>(s) }.getOrDefault(emptyList())
            } ?: emptyList()
        }

    suspend fun addRadioSearchQuery(query: String) {
        val normalized = query.trim()
        if (normalized.isBlank()) return
        dataStore.edit { prefs ->
            val existing = prefs[RADIO_SEARCH_HISTORY_JSON]?.let { raw ->
                runCatching { json.decodeFromString<List<String>>(raw) }.getOrDefault(emptyList())
            }.orEmpty()
            // Move-to-front and case-insensitive dedupe, so searching the same
            // place twice keeps one pill rather than two that differ by a
            // capital letter.
            val updated = buildList {
                add(normalized)
                addAll(existing.filterNot { it.equals(normalized, ignoreCase = true) })
            }.take(MAX_RADIO_SEARCH_HISTORY_SIZE)
            prefs[RADIO_SEARCH_HISTORY_JSON] = json.encodeToString(updated)
        }
    }

    suspend fun clearRadioSearchHistory() {
        dataStore.edit { it.remove(RADIO_SEARCH_HISTORY_JSON) }
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
    val lyricsWordProvider: Flow<LyricsWordProvider> = dataStore.data.map { prefs ->
        prefs[LYRICS_WORD_PROVIDER]
            ?.let { raw -> runCatching { LyricsWordProvider.valueOf(raw) }.getOrNull() }
            ?: LyricsWordProvider.BOTH
    }
    val downloadLyrics: Flow<Boolean> = dataStore.data.map { it[DOWNLOAD_LYRICS] ?: false }

    /**
     * Download a song as it's liked. Off by default, and deliberately
     * forward-only: turning it on downloads nothing that is already liked.
     * Sweeping an existing Liked Songs list would enqueue thousands of workers
     * at once, which is exactly the stampede that takes the app down — new
     * likes arrive one at a time and stay within what WorkManager expects.
     */
    val autoDownloadLikedSongs: Flow<Boolean> = dataStore.data.map { it[AUTO_DOWNLOAD_LIKED] ?: false }
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
    suspend fun setLyricsWordProvider(mode: LyricsWordProvider) {
        dataStore.edit { it[LYRICS_WORD_PROVIDER] = mode.name }
    }
    suspend fun setDownloadLyrics(enabled: Boolean) {
        dataStore.edit { it[DOWNLOAD_LYRICS] = enabled }
    }
    suspend fun setAutoDownloadLikedSongs(enabled: Boolean) {
        dataStore.edit { it[AUTO_DOWNLOAD_LIKED] = enabled }
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

    // --- Radio ranking weights ---

    val radioPlannerWeights: Flow<RadioPlannerWeights> = dataStore.data.map { prefs ->
        val defaults = RadioPlannerWeights.DEFAULT
        RadioPlannerWeights(
            localLibrary = prefs[RADIO_WEIGHT_LOCAL_LIBRARY] ?: defaults.localLibrary,
            qobuz = prefs[RADIO_WEIGHT_QOBUZ] ?: defaults.qobuz,
            spotifyDiscovery = prefs[RADIO_WEIGHT_SPOTIFY_DISCOVERY] ?: defaults.spotifyDiscovery,
            canonicalVersionBias = prefs[RADIO_WEIGHT_CANONICAL_VERSION_BIAS] ?: defaults.canonicalVersionBias,
            novelty = prefs[RADIO_WEIGHT_NOVELTY] ?: defaults.novelty,
            familiarity = prefs[RADIO_WEIGHT_FAMILIARITY] ?: defaults.familiarity,
            artistSimilarity = prefs[RADIO_WEIGHT_ARTIST_SIMILARITY] ?: defaults.artistSimilarity,
            genreTagSimilarity = prefs[RADIO_WEIGHT_GENRE_TAG_SIMILARITY] ?: defaults.genreTagSimilarity,
            eraConsistency = prefs[RADIO_WEIGHT_ERA_CONSISTENCY] ?: defaults.eraConsistency,
            avoidRecentlyPlayed = prefs[RADIO_WEIGHT_AVOID_RECENTLY_PLAYED] ?: defaults.avoidRecentlyPlayed,
            discoveryDistance = prefs[RADIO_WEIGHT_DISCOVERY_DISTANCE] ?: defaults.discoveryDistance,
        ).clamped()
    }

    suspend fun setRadioPlannerWeights(weights: RadioPlannerWeights) {
        val clamped = weights.clamped()
        dataStore.edit { prefs ->
            prefs[RADIO_WEIGHT_LOCAL_LIBRARY] = clamped.localLibrary
            prefs[RADIO_WEIGHT_QOBUZ] = clamped.qobuz
            prefs[RADIO_WEIGHT_SPOTIFY_DISCOVERY] = clamped.spotifyDiscovery
            prefs[RADIO_WEIGHT_CANONICAL_VERSION_BIAS] = clamped.canonicalVersionBias
            prefs[RADIO_WEIGHT_NOVELTY] = clamped.novelty
            prefs[RADIO_WEIGHT_FAMILIARITY] = clamped.familiarity
            prefs[RADIO_WEIGHT_ARTIST_SIMILARITY] = clamped.artistSimilarity
            prefs[RADIO_WEIGHT_GENRE_TAG_SIMILARITY] = clamped.genreTagSimilarity
            prefs[RADIO_WEIGHT_ERA_CONSISTENCY] = clamped.eraConsistency
            prefs[RADIO_WEIGHT_AVOID_RECENTLY_PLAYED] = clamped.avoidRecentlyPlayed
            prefs[RADIO_WEIGHT_DISCOVERY_DISTANCE] = clamped.discoveryDistance
        }
    }

    suspend fun resetRadioPlannerWeights() {
        setRadioPlannerWeights(RadioPlannerWeights.DEFAULT)
    }


    // --- Spotify ---
    val spotifyAccessToken: Flow<String?> = dataStore.data.map { it[SPOTIFY_ACCESS_TOKEN] }
    val spotifyRefreshToken: Flow<String?> = dataStore.data.map { it[SPOTIFY_REFRESH_TOKEN] }
    val spotifyTokenExpiresAt: Flow<Long> = dataStore.data.map { it[SPOTIFY_TOKEN_EXPIRES_AT] ?: 0L }
    val spotifyUserName: Flow<String?> = dataStore.data.map { it[SPOTIFY_USER_NAME] }

    suspend fun setSpotifyTokens(accessToken: String, refreshToken: String, expiresAtMillis: Long) {
        dataStore.edit {
            it[SPOTIFY_ACCESS_TOKEN] = accessToken
            it[SPOTIFY_REFRESH_TOKEN] = refreshToken
            it[SPOTIFY_TOKEN_EXPIRES_AT] = expiresAtMillis
        }
    }

    suspend fun setSpotifyUserName(name: String?) {
        dataStore.edit {
            if (name.isNullOrBlank()) it.remove(SPOTIFY_USER_NAME)
            else it[SPOTIFY_USER_NAME] = name
        }
    }

    suspend fun clearSpotifyTokens() {
        dataStore.edit {
            it.remove(SPOTIFY_ACCESS_TOKEN)
            it.remove(SPOTIFY_REFRESH_TOKEN)
            it.remove(SPOTIFY_TOKEN_EXPIRES_AT)
            it.remove(SPOTIFY_USER_NAME)
        }
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

    // --- Onboarding ---
    // Missing key reads false so both fresh installs and updates from builds
    // that never wrote it get routed into the first-run flow exactly once.
    val onboardingComplete: Flow<Boolean> = dataStore.data.map { it[ONBOARDING_COMPLETE] ?: false }
    suspend fun setOnboardingComplete(complete: Boolean) {
        dataStore.edit { it[ONBOARDING_COMPLETE] = complete }
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
    val eqAutoPreamp: Flow<Boolean> = dataStore.data.map { it[EQ_AUTO_PREAMP] ?: false }
    val eqBandsJson: Flow<String?> = dataStore.data.map { it[EQ_BANDS_JSON] }
    val eqBandsRJson: Flow<String?> = dataStore.data.map { it[EQ_BANDS_R_JSON] }
    val eqStereoMode: Flow<Boolean> = dataStore.data.map { it[EQ_STEREO_MODE] ?: false }

    /** System-wide AutoEQ master toggle (global output-mix effect). Off by default. */
    val systemWideAutoEqEnabled: Flow<Boolean> =
        dataStore.data.map { it[SYSTEM_WIDE_AUTOEQ_ENABLED] ?: false }

    suspend fun setSystemWideAutoEqEnabled(enabled: Boolean) {
        dataStore.edit { it[SYSTEM_WIDE_AUTOEQ_ENABLED] = enabled }
    }

    /** Bass/treble tone shelves for the system-wide effect (after AutoEQ). */
    val systemToneControls: Flow<ToneControls> = dataStore.data
        .map { it[SYSTEM_TONE_CONTROLS_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            raw?.let { jsonStr ->
                runCatching { json.decodeFromString<ToneControls>(jsonStr).clamped() }
                    .getOrDefault(ToneControls.DEFAULT)
            } ?: ToneControls.DEFAULT
        }

    suspend fun setSystemToneControls(controls: ToneControls) {
        dataStore.edit {
            it[SYSTEM_TONE_CONTROLS_JSON] = json.encodeToString(controls.clamped())
        }
    }

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

    suspend fun setEqAutoPreamp(enabled: Boolean) {
        dataStore.edit { it[EQ_AUTO_PREAMP] = enabled }
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

    suspend fun setEqBandsR(bandsJson: String?) {
        dataStore.edit {
            if (bandsJson != null) {
                it[EQ_BANDS_R_JSON] = bandsJson
            } else {
                it.remove(EQ_BANDS_R_JSON)
            }
        }
    }

    suspend fun setEqStereoMode(enabled: Boolean) {
        dataStore.edit { it[EQ_STEREO_MODE] = enabled }
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

    // Cached parsed FR points from the last loaded measurement, JSON-encoded.
    // Lets the EQ screen restore the curve on cold start without re-fetching.
    val eqMeasurementJson: Flow<String?> = dataStore.data.map { it[EQ_MEASUREMENT_JSON] }
    suspend fun setEqMeasurementJson(json: String?) {
        dataStore.edit {
            if (json != null) it[EQ_MEASUREMENT_JSON] = json
            else it.remove(EQ_MEASUREMENT_JSON)
        }
    }

    // Right-channel counterpart of the cached measurement, for 2-channel mode.
    val eqMeasurementRJson: Flow<String?> = dataStore.data.map { it[EQ_MEASUREMENT_R_JSON] }
    suspend fun setEqMeasurementRJson(json: String?) {
        dataStore.edit {
            if (json != null) it[EQ_MEASUREMENT_R_JSON] = json
            else it.remove(EQ_MEASUREMENT_R_JSON)
        }
    }

    // User-uploaded headphone measurements, JSON-encoded as List<Headphone>.
    // Each entry carries its own parsed FR points so it works fully offline.
    val eqUploadedHeadphonesJson: Flow<String> =
        dataStore.data.map { it[EQ_UPLOADED_HEADPHONES_JSON] ?: "[]" }
    suspend fun setEqUploadedHeadphonesJson(json: String) {
        dataStore.edit { it[EQ_UPLOADED_HEADPHONES_JSON] = json }
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
     * Mixer channel coloring mode. false (default) = curated fixed palette
     * (distinct per-bus colors); true = colors derived from the current
     * album/theme accent so the strips track the dynamic player color.
     */
    val mixerChannelDynamic: Flow<Boolean> = dataStore.data.map { it[MIXER_CHANNEL_DYNAMIC] ?: false }
    suspend fun setMixerChannelDynamic(enabled: Boolean) {
        dataStore.edit { it[MIXER_CHANNEL_DYNAMIC] = enabled }
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

    /**
     * Fold multichannel (5.1/7.1/16 ch) tracks down to stereo (fixed gain
     * matrix) at the head of the AudioProcessor chain. Default true — the DSP/EQ
     * stages are stereo-only. When false, multichannel PCM passes through
     * to AudioTrack untouched (the device downmixes or outputs natively)
     * and DSP/EQ are bypassed for those tracks.
     */
    val multichannelDownmixEnabled: Flow<Boolean> =
        dataStore.data.map { it[MULTICHANNEL_DOWNMIX_ENABLED] ?: true }
    suspend fun setMultichannelDownmixEnabled(enabled: Boolean) {
        dataStore.edit { it[MULTICHANNEL_DOWNMIX_ENABLED] = enabled }
    }

    // --- Library / Local Media ---
    val excludedPathsJson: Flow<String> = dataStore.data.map { it[EXCLUDED_PATHS_JSON] ?: "[]" }
    suspend fun setExcludedPaths(pathsJson: String) {
        dataStore.edit { it[EXCLUDED_PATHS_JSON] = pathsJson }
    }

    val userFolderRoots: Flow<Set<String>> = dataStore.data
        .map { it[USER_FOLDER_ROOTS_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            if (raw == null) emptySet()
            else runCatching { json.decodeFromString<Set<String>>(raw) }.getOrDefault(emptySet())
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

    // --- Library tab order ---
    val libraryTabOrder: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[LIBRARY_TAB_ORDER]?.split(",")?.filter { it.isNotBlank() }
            ?: listOf("overview", "local", "playlists", "favorites", "downloads")
    }
    suspend fun setLibraryTabOrder(order: List<String>) {
        dataStore.edit { it[LIBRARY_TAB_ORDER] = order.joinToString(",") }
    }

    // --- Library sort selections (persist Songs/Albums/Artists sort order) ---
    val songSort: Flow<String?> = dataStore.data.map { it[SONG_SORT] }
    val albumSort: Flow<String?> = dataStore.data.map { it[ALBUM_SORT] }
    val artistSort: Flow<String?> = dataStore.data.map { it[ARTIST_SORT] }
    suspend fun setSongSort(value: String) { dataStore.edit { it[SONG_SORT] = value } }
    suspend fun setAlbumSort(value: String) { dataStore.edit { it[ALBUM_SORT] = value } }
    suspend fun setArtistSort(value: String) { dataStore.edit { it[ARTIST_SORT] = value } }

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

    // --- Lyrics 3D appearance ---
    val lyrics3dRotation: Flow<Float> = dataStore.data.map { it[LYRICS_3D_ROTATION] ?: 12f }
    val lyrics3dWaveSpeed: Flow<Float> = dataStore.data.map { it[LYRICS_3D_WAVE_SPEED] ?: 1f }
    val lyrics3dShadowDepth: Flow<Float> = dataStore.data.map { it[LYRICS_3D_SHADOW_DEPTH] ?: 0.7f }
    suspend fun setLyrics3dRotation(value: Float) {
        dataStore.edit { it[LYRICS_3D_ROTATION] = value.coerceIn(0f, 20f) }
    }
    suspend fun setLyrics3dWaveSpeed(value: Float) {
        dataStore.edit { it[LYRICS_3D_WAVE_SPEED] = value.coerceIn(0.25f, 3f) }
    }
    suspend fun setLyrics3dShadowDepth(value: Float) {
        dataStore.edit { it[LYRICS_3D_SHADOW_DEPTH] = value.coerceIn(0f, 1f) }
    }

    val lyricsBassReact: Flow<Float> = dataStore.data.map { it[LYRICS_BASS_REACT] ?: 0.8f }
    suspend fun setLyricsBassReact(value: Float) {
        dataStore.edit { it[LYRICS_BASS_REACT] = value.coerceIn(0f, 1f) }
    }

    /**
     * Full Player Visuals Studio settings. The JSON blob wins; installs that only
     * ever used the old four sliders fall back to those legacy keys so their
     * tuned look carries over the first time the Studio opens.
     */
    val lyricsFx: Flow<LyricsFxSettings> = dataStore.data
        // Select the keys this flow actually depends on, dedupe, and only then
        // deserialize. DataStore emits the WHOLE Preferences snapshot on every
        // write, so without this the JSON below was re-parsed every time any
        // unrelated setting anywhere in the app changed.
        .map { prefs ->
            LyricsFxRaw(
                json = prefs[LYRICS_FX_JSON],
                rotation = prefs[LYRICS_3D_ROTATION],
                waveSpeed = prefs[LYRICS_3D_WAVE_SPEED],
                shadowDepth = prefs[LYRICS_3D_SHADOW_DEPTH],
                bassReact = prefs[LYRICS_BASS_REACT],
            )
        }
        .distinctUntilChanged()
        .map { raw ->
            raw.json
                ?.let { s -> runCatching { json.decodeFromString<LyricsFxSettings>(s) }.getOrNull() }
                ?.clamped()
                ?: LyricsFxSettings(
                    rotationDegrees = raw.rotation ?: 12f,
                    waveSpeed = raw.waveSpeed ?: 1f,
                    shadowDepth = raw.shadowDepth ?: 0.7f,
                    bassReact = raw.bassReact ?: 0.8f,
                ).clamped()
        }

    suspend fun setLyricsFx(settings: LyricsFxSettings) {
        val clamped = settings.clamped()
        dataStore.edit { it[LYRICS_FX_JSON] = json.encodeToString(clamped) }
    }

    /**
     * How the world globe's outlines react to the music. Same shape as the
     * lyrics blob above — one JSON key, decoded lazily, and clamped on the way
     * both in and out so a hand-edited or future-versioned value can never hand
     * the renderer an amplitude it will draw the Earth inside out with.
     */
    val globeFx: Flow<tf.monochrome.android.domain.model.GlobeFxSettings> = dataStore.data
        .map { it[GLOBE_FX_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            raw
                ?.let { s ->
                    runCatching {
                        json.decodeFromString<tf.monochrome.android.domain.model.GlobeFxSettings>(s)
                    }.getOrNull()
                }
                ?.clamped()
                ?: tf.monochrome.android.domain.model.GlobeFxSettings()
        }

    suspend fun setGlobeFx(settings: tf.monochrome.android.domain.model.GlobeFxSettings) {
        dataStore.edit { it[GLOBE_FX_JSON] = json.encodeToString(settings.clamped()) }
    }

    /** User-saved Lyrics FX presets (empty until the user saves one). */
    val customLyricsFxPresets: Flow<List<tf.monochrome.android.domain.model.LyricsFxPreset>> =
        dataStore.data
            .map { it[LYRICS_FX_CUSTOM_PRESETS_JSON] }
            .distinctUntilChanged()
            .map { raw ->
                raw
                    ?.let { s ->
                        runCatching {
                            json.decodeFromString<List<tf.monochrome.android.domain.model.LyricsFxPreset>>(s)
                        }.getOrNull()
                    }
                    ?.map { it.copy(settings = it.settings.clamped()) }
                    ?: emptyList()
            }

    suspend fun setCustomLyricsFxPresets(presets: List<tf.monochrome.android.domain.model.LyricsFxPreset>) {
        dataStore.edit { it[LYRICS_FX_CUSTOM_PRESETS_JSON] = json.encodeToString(presets) }
    }

    /** Player-chrome (transport button) liquid-glass settings. */
    val playerGlass: Flow<tf.monochrome.android.domain.model.PlayerGlassSettings> = dataStore.data
        .map { it[PLAYER_GLASS_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            raw
                ?.let { s -> runCatching { json.decodeFromString<tf.monochrome.android.domain.model.PlayerGlassSettings>(s) }.getOrNull() }
                ?.clamped()
                ?: tf.monochrome.android.domain.model.PlayerGlassSettings.DEFAULT
        }

    suspend fun setPlayerGlass(settings: tf.monochrome.android.domain.model.PlayerGlassSettings) {
        dataStore.edit { it[PLAYER_GLASS_JSON] = json.encodeToString(settings.clamped()) }
    }

    /** Mini-player glass settings (its own blob, same shape as [playerGlass]). */
    val miniPlayerGlass: Flow<tf.monochrome.android.domain.model.PlayerGlassSettings> = dataStore.data
        .map { it[MINI_PLAYER_GLASS_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            raw
                ?.let { s -> runCatching { json.decodeFromString<tf.monochrome.android.domain.model.PlayerGlassSettings>(s) }.getOrNull() }
                ?.clamped()
                ?: tf.monochrome.android.domain.model.PlayerGlassSettings.DEFAULT
        }

    suspend fun setMiniPlayerGlass(settings: tf.monochrome.android.domain.model.PlayerGlassSettings) {
        dataStore.edit { it[MINI_PLAYER_GLASS_JSON] = json.encodeToString(settings.clamped()) }
    }

    /** Atmos renderer profile (mode / target layout / HRTF profile id). */
    val rendererProfile: Flow<tf.monochrome.android.domain.model.RendererProfile> = dataStore.data
        .map { it[RENDERER_PROFILE_JSON] }
        .distinctUntilChanged()
        .map { raw ->
            raw
                ?.let { s -> runCatching { json.decodeFromString<tf.monochrome.android.domain.model.RendererProfile>(s) }.getOrNull() }
                ?.clamped()
                ?: tf.monochrome.android.domain.model.RendererProfile.DEFAULT
        }

    suspend fun setRendererProfile(profile: tf.monochrome.android.domain.model.RendererProfile) {
        dataStore.edit { it[RENDERER_PROFILE_JSON] = json.encodeToString(profile.clamped()) }
    }

    /** User-saved Player Glass themes (empty until the user saves one). */
    val customPlayerGlassPresets: Flow<List<tf.monochrome.android.domain.model.PlayerGlassPreset>> =
        dataStore.data
            .map { it[PLAYER_GLASS_CUSTOM_PRESETS_JSON] }
            .distinctUntilChanged()
            .map { raw ->
                raw
                    ?.let { s ->
                        runCatching {
                            json.decodeFromString<List<tf.monochrome.android.domain.model.PlayerGlassPreset>>(s)
                        }.getOrNull()
                    }
                    ?.map { it.copy(settings = it.settings.clamped()) }
                    ?: emptyList()
            }

    suspend fun setCustomPlayerGlassPresets(presets: List<tf.monochrome.android.domain.model.PlayerGlassPreset>) {
        dataStore.edit { it[PLAYER_GLASS_CUSTOM_PRESETS_JSON] = json.encodeToString(presets) }
    }

    // ── Settings cloud-sync (export / import the allow-listed prefs) ─────────

    /** A tagged-JSON snapshot of only the [SETTINGS_SYNC_KEYS] prefs. */
    suspend fun exportSettingsJson(): String =
        SettingsSyncCodec.encode(syncSnapshotOf(dataStore.data.first()))

    /** The allow-listed subset of a Preferences snapshot, keyed by name. */
    private fun syncSnapshotOf(prefs: Preferences): Map<String, Any> =
        prefs.asMap()
            .filterKeys { it in SETTINGS_SYNC_KEYS }
            .mapKeys { it.key.name }

    /**
     * Apply a settings snapshot pulled from the cloud, in a single atomic edit.
     * Only keys on the allow-list are written (a hostile/stale blob can't set
     * excluded or unknown keys), and each is stored under a freshly reconstructed
     * key of the decoded value's type.
     */
    suspend fun importSettingsJson(payload: String) {
        val decoded = SettingsSyncCodec.decode(payload)
        if (decoded.isEmpty()) return
        dataStore.edit { prefs ->
            decoded.forEach { (name, value) ->
                if (name !in SETTINGS_SYNC_KEY_NAMES) return@forEach
                when (value) {
                    is Boolean -> prefs[booleanPreferencesKey(name)] = value
                    is Int -> prefs[intPreferencesKey(name)] = value
                    is Long -> prefs[longPreferencesKey(name)] = value
                    is Float -> prefs[floatPreferencesKey(name)] = value
                    is Double -> prefs[doublePreferencesKey(name)] = value
                    is String -> prefs[stringPreferencesKey(name)] = value
                    is Set<*> -> prefs[stringSetPreferencesKey(name)] = value.map { it.toString() }.toSet()
                }
            }
        }
    }

    /**
     * Emits the allow-listed settings snapshot whenever any synced pref changes.
     * The [SettingsSyncCoordinator] debounces this to push changes to the cloud.
     */
    val settingsSyncSnapshot: Flow<String> = dataStore.data.map { prefs ->
        SettingsSyncCodec.encode(syncSnapshotOf(prefs))
    }

    // --- Player appearance ---
    val playerDynamicColor: Flow<Boolean> = dataStore.data.map { it[PLAYER_DYNAMIC_COLOR] ?: true }
    suspend fun setPlayerDynamicColor(enabled: Boolean) {
        dataStore.edit { it[PLAYER_DYNAMIC_COLOR] = enabled }
    }

    // Full-screen blurred, stretched album-art background behind the player
    // (Apple-Music / Spotify style). ON by default — this also feeds the glass
    // shader's uBackdropMix, so the default liquid glass lenses real album
    // tones instead of the flat wash.
    val playerBlurredBackground: Flow<Boolean> = dataStore.data.map { it[PLAYER_BLURRED_BACKGROUND] ?: true }
    suspend fun setPlayerBlurredBackground(enabled: Boolean) {
        dataStore.edit { it[PLAYER_BLURRED_BACKGROUND] = enabled }
    }

    // --- Display: app-wide frame rate & panel resolution ---
    // 0 = unlocked (display max) / native resolution. Resolution is stored as
    // the target shortest side in px (720 / 1080 / 1440 / 2160).
    val appTargetFps: Flow<Int> = dataStore.data.map { it[APP_TARGET_FPS] ?: 0 }
    val appRenderResolution: Flow<Int> = dataStore.data.map { it[APP_RENDER_RESOLUTION] ?: 0 }
    suspend fun setAppTargetFps(fps: Int) {
        dataStore.edit { it[APP_TARGET_FPS] = fps }
    }
    suspend fun setAppRenderResolution(shortSide: Int) {
        dataStore.edit { it[APP_RENDER_RESOLUTION] = shortSide }
    }

    /** Hide the status and navigation bars app-wide; a swipe reveals them. */
    val immersiveFullScreen: Flow<Boolean> =
        dataStore.data.map { it[IMMERSIVE_FULL_SCREEN] ?: false }
    suspend fun setImmersiveFullScreen(enabled: Boolean) {
        dataStore.edit { it[IMMERSIVE_FULL_SCREEN] = enabled }
    }

    // --- Discover ---
    val discoveryHeartedGenres: Flow<Set<String>> = dataStore.data.map {
        it[DISCOVERY_HEARTED_GENRES] ?: emptySet()
    }

    suspend fun toggleHeartedGenre(genreId: String) {
        dataStore.edit { prefs ->
            val current = prefs[DISCOVERY_HEARTED_GENRES] ?: emptySet()
            prefs[DISCOVERY_HEARTED_GENRES] =
                if (genreId in current) current - genreId else current + genreId
        }
    }

    /**
     * Radio stations kept from the globe, by radio-browser uuid.
     *
     * The uuid rather than the stream URL, because the URL is the part that
     * changes: a station that moves host keeps its identity in the directory,
     * and a favourite that stored the old address would quietly stop working.
     */
    val favouriteStations: Flow<Set<String>> = dataStore.data.map {
        it[FAVOURITE_STATIONS] ?: emptySet()
    }

    suspend fun toggleFavouriteStation(stationUuid: String) {
        dataStore.edit { prefs ->
            val current = prefs[FAVOURITE_STATIONS] ?: emptySet()
            prefs[FAVOURITE_STATIONS] =
                if (stationUuid in current) current - stationUuid else current + stationUuid
        }
    }

    /**
     * Genres played or opened from the map, most recent first.
     *
     * A newline-joined string rather than a `stringSet` because the order *is*
     * the data — a set would come back in whatever order DataStore felt like
     * and "recently listened to" would be a lie.
     */
    val discoveryRecentGenres: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[DISCOVERY_RECENT_GENRES].orEmpty().split("\n").filter { it.isNotBlank() }
    }

    suspend fun noteGenreVisited(genreId: String) {
        dataStore.edit { prefs ->
            val current = prefs[DISCOVERY_RECENT_GENRES].orEmpty()
                .split("\n").filter { it.isNotBlank() }
            prefs[DISCOVERY_RECENT_GENRES] =
                (listOf(genreId) + current.filterNot { it == genreId })
                    .take(MAX_RECENT_GENRES)
                    .joinToString("\n")
        }
    }

    val discoverySort: Flow<String> = dataStore.data.map { it[DISCOVERY_SORT].orEmpty() }

    suspend fun setDiscoverySort(id: String) {
        dataStore.edit { it[DISCOVERY_SORT] = id }
    }

    // --- Low performance mode ---
    // Deliberately NOT in SETTINGS_SYNC_KEYS: like frame rate and resolution
    // this is per-device tuning. A phone that needs the glass off shouldn't
    // turn it off on the user's tablet too.
    val disableAnimations: Flow<Boolean> = dataStore.data.map { it[DISABLE_ANIMATIONS] ?: false }
    val legacyPlayer: Flow<Boolean> = dataStore.data.map { it[LEGACY_PLAYER] ?: false }
    val disableLiquidGlass: Flow<Boolean> = dataStore.data.map { it[DISABLE_LIQUID_GLASS] ?: false }

    /**
     * The master switch. It owns no state of its own that the three below don't
     * already carry — it is stored only so the row can be read back without
     * recomputing, and it is kept true exactly while all three are true.
     */
    val lowPerformanceMode: Flow<Boolean> = dataStore.data.map { it[LOW_PERFORMANCE_MODE] ?: false }

    /** Everything the UI layer needs to know, as one value. */
    val lowPerformanceSettings: Flow<LowPerformanceSettings> = dataStore.data.map { prefs ->
        LowPerformanceSettings(
            disableAnimations = prefs[DISABLE_ANIMATIONS] ?: false,
            legacyPlayer = prefs[LEGACY_PLAYER] ?: false,
            disableLiquidGlass = prefs[DISABLE_LIQUID_GLASS] ?: false,
        )
    }

    suspend fun setLowPerformanceMode(enabled: Boolean) {
        dataStore.edit {
            it[LOW_PERFORMANCE_MODE] = enabled
            it[DISABLE_ANIMATIONS] = enabled
            it[LEGACY_PLAYER] = enabled
            it[DISABLE_LIQUID_GLASS] = enabled
        }
    }

    suspend fun setDisableAnimations(enabled: Boolean) =
        editLowPerformanceFlag(DISABLE_ANIMATIONS, enabled)

    suspend fun setLegacyPlayer(enabled: Boolean) =
        editLowPerformanceFlag(LEGACY_PLAYER, enabled)

    suspend fun setDisableLiquidGlass(enabled: Boolean) =
        editLowPerformanceFlag(DISABLE_LIQUID_GLASS, enabled)

    // Writing one of the three re-derives the master in the same transaction, so
    // the master switch can never disagree with the rows underneath it.
    private suspend fun editLowPerformanceFlag(
        key: Preferences.Key<Boolean>,
        enabled: Boolean,
    ) {
        dataStore.edit { prefs ->
            prefs[key] = enabled
            prefs[LOW_PERFORMANCE_MODE] = LowPerformanceSettings(
                disableAnimations = prefs[DISABLE_ANIMATIONS] ?: false,
                legacyPlayer = prefs[LEGACY_PLAYER] ?: false,
                disableLiquidGlass = prefs[DISABLE_LIQUID_GLASS] ?: false,
            ).allEnabled
        }
    }

    // --- Clear all prefs (System) ---
    suspend fun clearAllData() {
        dataStore.edit { it.clear() }
    }
}
