package tf.monochrome.android.ui.settings

import androidx.annotation.StringRes
import tf.monochrome.android.R

/**
 * Where a settings search result takes you.
 *
 * Two shapes because settings live in two places. Most are rows on one of the
 * nine tabs; the rest are whole screens of their own — the equaliser, the
 * mixer, the Player Visuals Studio — reached from a row on a tab. A search that
 * could only land on tabs would answer "where is the parametric EQ?" with the
 * tab containing the button that opens it, which is one step short of the
 * answer.
 */
sealed interface SettingsDestination {
    /** A tab of the settings screen, by index into its tab list. */
    data class Tab(val index: Int) : SettingsDestination

    /** A screen of its own, by nav route. */
    data class Route(val route: String) : SettingsDestination
}

/**
 * One findable setting.
 *
 * [title] is the entry's English name and its identity: stable across
 * languages, which is what de-duplication and the tests key on.
 *
 * [keywords] carry the words someone would actually type that are not in the
 * title — "bass" for the equaliser, "battery" for performance, "cache" for
 * storage. Without them the search only finds settings whose name you already
 * knew, which is the case where you did not need to search.
 */
data class SettingsEntry(
    val title: String,
    /**
     * What the result says on screen, in the reader's language, and the text
     * its row anchors on. Usually the row's own title resource, so the search
     * scrolls to exactly that row; null for a name that is never translated
     * (Spotify, ListenBrainz, Wave Candy), shown as [title].
     */
    @StringRes val titleRes: Int?,
    val tabLabel: String,
    val destination: SettingsDestination,
    val keywords: List<String> = emptyList(),
)

/**
 * Every setting worth finding, and where it is.
 *
 * A declared list rather than something scraped from the composables. The rows
 * only exist during composition, inside `when` branches and conditionals, so
 * there is nothing to filter until a tab has been drawn — a registry populated
 * that way under-reports on first open, which is precisely when someone
 * searches. The cost is that this has to be extended when a setting is added,
 * and the test alongside it fails the build if an entry points nowhere.
 *
 * Tab indices come from [settingsTabIndex] rather than being written down, so
 * reordering the tabs cannot silently send every result to the wrong one.
 */
val SettingsSearchIndex: List<SettingsEntry> = listOf(
    // ── Appearance ──────────────────────────────────────────────────────
    // Keywords in every language on offer, so somebody stuck in a language
    // they cannot read can type the word for "language" in their own.
    entry(
        "Language", R.string.settings_language_title, "Appearance",
        listOf(
            "locale", "translation", "english", "chinese", "japanese", "french", "spanish", "turkish", "german",
            "语言", "語言", "中文", "言語", "日本語", "langue", "français", "idioma", "español", "dil", "türkçe",
            "sprache", "deutsch",
        ),
    ),
    entry("Theme", R.string.settings_theme, "Appearance", listOf("colour", "color", "dark", "light", "white")),
    entry("Light paper", R.string.settings_light_paper, "Appearance", listOf("white", "warm", "crisp", "paper", "glare")),
    entry(
        "Color transition", R.string.settings_color_transition, "Appearance",
        listOf("colour", "fade", "crossfade", "blend", "album", "speed", "duration"),
    ),
    entry("Dynamic colours", R.string.settings_dynamic_colors, "Appearance", listOf("album", "art", "material you", "accent")),
    entry("Font scale", R.string.settings_font_size, "Appearance", listOf("text size", "bigger", "smaller", "accessibility")),
    entry("Custom font", R.string.settings_font_library, "Appearance", listOf("typeface", "import font")),
    entry("Now playing view", R.string.settings_view_mode, "Appearance", listOf("player", "layout", "lyrics")),
    entry("Blurred background", R.string.settings_blurred_album_background, "Appearance", listOf("player", "artwork", "blur")),
    entry("Glow behind album art", R.string.settings_glow_behind_album_art, "Appearance", listOf("bloom", "halo", "cover", "kick", "bass", "pump")),
    entry("Glow radius", R.string.settings_glow_radius, "Appearance", listOf("bloom", "halo", "cover", "size", "reach", "spread")),
    entry("Glow brightness", R.string.settings_glow_brightness, "Appearance", listOf("bloom", "halo", "cover", "strength", "intensity")),
    entry("Dynamic Player Color", R.string.settings_dynamic_player_color, "Appearance", listOf("album", "art", "tint", "accent", "player")),
    entry("Custom colors", R.string.settings_custom_colors, "Appearance", listOf("colour", "accent", "background", "pick", "override")),
    entry("Use system font size", R.string.settings_use_system_font_size, "Appearance", listOf("text size", "accessibility", "os", "display")),
    entry("Romaji Lyrics", R.string.settings_romaji_lyrics, "Appearance", listOf("japanese", "transliterate", "latin", "kana", "lyrics")),
    entry("Show Explicit Badges", R.string.settings_show_explicit_badges, "Appearance", listOf("explicit", "badge", "parental", "e")),
    entry("Legacy player", R.string.settings_legacy_player, "Appearance", listOf("old", "flat", "classic", "pre-glass", "design")),
    entry("Remove liquid glass", R.string.settings_remove_liquid_glass, "Appearance", listOf("flat", "opaque", "blur", "performance", "glass")),
    entry("Disable animations", R.string.settings_disable_animations, "Appearance", listOf("motion", "reduce", "still", "accessibility")),

    // ── Visual Studio ───────────────────────────────────────────────────
    entry(
        "Player Visuals Studio", R.string.settings_player_visuals_studio,
        "Visual Studio",
        listOf(
            "lyrics", "glass", "fx", "visuals", "beat",
            // The Ambient tab. Somebody looking for the visualizer over the
            // artwork will search for what they saw, not for "studio".
            "ambient", "milkdrop", "visualizer", "overlay", "blend",
        ),
    )
        .at(SettingsDestination.Route("lyrics_fx_studio")),

    // ── Appearance › Spectrum and visualizer ────────────────────────────
    // The projectM section used to live under Appearance; it moved to the
    // Player Visuals Studio's Visualizer tab (which also hosts the ambient
    // overlay), so these rows route to that screen instead of scrolling a
    // settings tab that no longer contains them. "Spectrum over the
    // artwork" and "Animated spectrum" stayed under Appearance — they are
    // the equalizer/lifecycle rows, not the visualizer engine.
    entry(
        "Show Spectrum Analyzer", R.string.settings_show_spectrum_analyzer, "Visual Studio",
        listOf("fft", "bars", "frequency", "meter", "ambient"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),
    entry(
        "Use projectM Visualizer", R.string.settings_use_projectm_visualizer, "Visual Studio",
        listOf("milkdrop", "visualiser", "projectm", "preset", "engine", "ambient"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),
    entry(
        "Default Preset", R.string.settings_default_preset, "Visual Studio",
        listOf("visualiser", "milkdrop", "projectm", "startup"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),
    entry(
        "Texture Size", R.string.settings_texture_size, "Visual Studio",
        listOf("visualiser", "resolution", "quality", "graphics", "projectm"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),
    entry(
        "Touch Waveform", R.string.settings_touch_waveform, "Visual Studio",
        listOf("visualiser", "waveform", "finger", "draw", "projectm"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),
    // Distinct from System › "Full screen", which is the app-wide immersive
    // switch. Both are real and the tab label tells them apart in results.
    entry(
        "Fullscreen", R.string.settings_fullscreen, "Visual Studio",
        listOf("visualiser", "projectm", "fill screen", "now playing"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),
    entry(
        "Show FPS", R.string.settings_show_fps, "Visual Studio",
        listOf("visualiser", "framerate", "counter", "performance", "projectm"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),
    entry(
        "Disable vsync", R.string.settings_disable_vsync, "Visual Studio",
        listOf("visualiser", "refresh", "tearing", "framerate", "projectm"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),
    entry(
        "Engine Status", R.string.settings_engine_status, "Visual Studio",
        listOf("visualiser", "projectm", "assets", "version", "diagnostics"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),

    // ── Audio ───────────────────────────────────────────────────────────
    entry("Gapless playback", R.string.settings_gapless_playback, "Audio", listOf("gap", "continuous", "album")),
    entry("Crossfade", R.string.search_crossfade, "Audio", listOf("fade", "transition", "blend")),
    entry("Playback speed", R.string.settings_playback_speed, "Audio", listOf("tempo", "faster", "slower", "pitch")),
    entry("Multichannel downmix", R.string.settings_downmix_multichannel_to_stereo, "Audio", listOf("surround", "5.1", "atmos", "stereo")),
    entry("Spatial renderer", R.string.settings_atmos_renderer_configuration, "Audio", listOf("atmos", "hrtf", "binaural", "spatial"))
        .at(SettingsDestination.Route("atmos_renderer")),
    entry("HRTF database", R.string.search_hrtf_database, "Audio", listOf("binaural", "head", "spatial", "sofa"))
        .at(SettingsDestination.Route("hrtf_database")),
    entry("Crossfeed", R.string.crossfeed, "Audio", listOf("headphone", "stereo", "bauer", "fatigue"))
        .at(SettingsDestination.Route("crossfeed")),
    entry("Mixer", R.string.status_mixer, "Audio", listOf("dsp", "bus", "plugin", "insert", "channel"))
        .at(SettingsDestination.Route("mixer")),
    entry("Streaming quality", R.string.settings_streaming_quality, "Audio", listOf("bitrate", "wifi", "cellular", "data")),
    entry("Preserve Pitch", R.string.settings_preserve_pitch, "Audio", listOf("speed", "tempo", "key", "chipmunk", "semitone")),
    entry("Never Resample Between Tracks", R.string.settings_never_resample_between_tracks, "Audio", listOf("sample rate", "gap", "dac", "bit perfect")),
    entry("USB DAC bit-perfect routing", R.string.settings_usb_dac_bit_perfect_routing, "Audio", listOf("usb", "dac", "exclusive", "bit perfect", "output")),
    entry(
        "Hi-res output (Bluetooth & speaker)", R.string.settings_hi_res_output_bluetooth_speaker,
        "Audio",
        listOf("bluetooth", "24-bit", "96khz", "ldac", "float", "high resolution", "bit depth"),
    ),

    // ── Equalizer ───────────────────────────────────────────────────────
    entry("Equalizer", R.string.settings_equalizer, "Equalizer", listOf("eq", "bass", "treble", "bands", "graphic"))
        .at(SettingsDestination.Route("equalizer")),
    entry("Parametric EQ", R.string.search_parametric_eq, "Equalizer", listOf("eq", "filter", "peaking", "shelf", "q"))
        .at(SettingsDestination.Route("parametric_eq")),
    entry("AutoEQ headphone profile", R.string.search_autoeq_headphone_profile, "Equalizer", listOf("headphone", "harman", "target", "correction")),
    entry("Spectrum analyzer", R.string.settings_spectrum_analyzer, "Equalizer", listOf("fft", "visualiser", "frequency", "meter")),
    entry("System-wide AutoEQ", R.string.settings_system_wide_autoeq, "Equalizer", listOf("global", "device", "all audio", "correction", "beta")),

    entry("Wave Candy", null, "Equalizer", listOf("waveform", "oscilloscope", "scope", "kick", "punch", "stereo", "mono")),

    // On the Player Visuals Studio's Visualizer tab, with the spectrum it draws.
    entry(
        "Spectrum waterfall", R.string.settings_spectrum_waterfall,
        "Visual Studio",
        listOf("waterfall", "depth", "fade", "angle", "perspective", "ridgeline", "3d", "history", "spectrogram"),
    ).at(SettingsDestination.Route("lyrics_fx_studio")),

    // ── Library ─────────────────────────────────────────────────────────
    // Renamed with the header it points at: SettingsSearchIndexTest greps the
    // settings screens for every title here, so the two move together or the
    // build fails. "library" is a keyword now because it left the title.
    entry("Nav bar", R.string.settings_tab_bar, "Library", listOf("navigation", "tab bar", "tabs", "buttons", "discover", "radio", "playlists", "favorites", "downloads", "local", "bottom bar")),
    entry("Hide mini player when the nav bar shows", R.string.settings_mini_player_hide_with_tabs, "Library", listOf("mini player", "nav bar", "tab bar", "scroll", "hide", "bottom bar")),
    entry("Library sections", R.string.settings_library_sections, "Library", listOf("reorder", "order", "hide", "pages", "local", "playlists", "favorites", "downloads")),
    entry("Local folders", R.string.settings_local_media_scanning, "Library", listOf("storage", "saf", "sd card", "path")),

    // ── Downloads ───────────────────────────────────────────────────────
    entry("Download quality", R.string.settings_download_quality, "Downloads", listOf("bitrate", "flac", "offline")),
    entry("Download lyrics", R.string.settings_download_lyrics, "Downloads", listOf("offline", "synced")),
    entry("Auto-download liked", R.string.settings_auto_download_liked_songs, "Downloads", listOf("offline", "favourites", "hearted")),
    entry("Download centre", R.string.search_download_centre, "Downloads", listOf("queue", "progress", "offline"))
        .at(SettingsDestination.Route("downloads")),
    entry("Save location", R.string.settings_save_location, "Downloads", listOf("folder", "path", "sd card", "storage", "where")),

    // ── Connections ─────────────────────────────────────────────────────
    entry("Last.fm scrobbling", R.string.search_lastfm_scrobbling, "Connections", listOf("scrobble", "account", "history")),
    entry("ListenBrainz", null, "Connections", listOf("scrobble", "account", "history")),
    entry(
        "APIs", R.string.search_apis, "Connections",
        listOf("server", "instance", "url", "endpoint", "source", "catalogue", "catalog",
            "tidal", "qobuz", "apple", "deezer", "hifi", "add"),
    ),
    entry("Show what I'm playing", R.string.settings_show_what_i_m_playing, "Connections", listOf("discord", "presence", "rich", "status", "playing")),
    entry("Spotify", null, "Connections", listOf("import", "playlist", "account", "transfer")),

    // ── Radio ───────────────────────────────────────────────────────────
    entry("AI radio", R.string.search_ai_radio, "Radio", listOf("station", "recommend", "queue", "seed")),
    entry("Radio weights", R.string.search_radio_weights, "Radio", listOf("tuning", "similarity", "novelty", "familiarity")),

    // ── System ──────────────────────────────────────────────────────────
    entry("Performance", R.string.settings_performance, "System", listOf("battery", "fps", "low power", "glass", "blur")),
    entry(
        "Full screen", R.string.settings_full_screen,
        "System",
        listOf("immersive", "hide status bar", "notification bar", "navigation bar", "gesture bar"),
    ),
    entry("Debug log", R.string.settings_view_debug_log, "System", listOf("logs", "diagnostics", "report", "crash"))
        .at(SettingsDestination.Route("debug_log")),
    entry("Backup and restore", R.string.settings_backup_restore, "System", listOf("export", "import", "settings", "transfer")),
    entry("Clear cache", R.string.settings_clear_cache, "System", listOf("storage", "space", "images")),
    entry("Check for updates", R.string.settings_check_for_updates, "System", listOf("update", "version", "github", "release", "newer")),
    entry("Restart onboarding", R.string.settings_restart_onboarding, "System", listOf("setup", "first run", "wizard", "again", "tutorial")),

    // ── About ───────────────────────────────────────────────────────────
    entry("What's new", R.string.settings_what_s_new, "About", listOf("changelog", "release", "version", "updates")),
    entry("Version", R.string.search_version, "About", listOf("build", "about", "release")),
)

private fun entry(title: String, @StringRes titleRes: Int?, tabLabel: String, keywords: List<String>) = SettingsEntry(
    title = title,
    titleRes = titleRes,
    tabLabel = tabLabel,
    // A chip that opens a screen has no page to land on; its entries go to
    // that screen.
    destination = settingsLinkRoute(tabLabel)?.let { SettingsDestination.Route(it) }
        ?: SettingsDestination.Tab(settingsTabIndex(tabLabel)),
    keywords = keywords,
)

private fun SettingsEntry.at(destination: SettingsDestination) = copy(destination = destination)

/**
 * Matches for what has been typed, best first.
 *
 * A title that starts with the query beats one that merely contains it, which
 * beats a keyword hit — someone typing "the" wants Theme, not every setting
 * whose description happens to contain the word. Keyword matches rank last but
 * still rank, which is what makes "bass" find the equaliser.
 *
 * [resolve] turns a string resource into the reader's language. Titles and tab
 * names are matched both as they read on screen and in English: the screen is
 * what someone sees, and English is what a tutorial, a forum post or the
 * changelog will have told them to look for.
 */
fun searchSettings(query: String, limit: Int = 8, resolve: (Int) -> String = { "" }): List<SettingsEntry> {
    val typed = query.trim().lowercase()
    if (typed.length < 2) return emptyList()
    return SettingsSearchIndex
        .mapNotNull { entry ->
            val titles = listOfNotNull(entry.title, entry.titleRes?.let(resolve))
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
            val tabs = listOf(entry.tabLabel, resolve(settingsTabLabelRes(entry.tabLabel)))
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
            val rank = when {
                titles.any { it.startsWith(typed) } -> 0
                titles.any { it.contains(typed) } -> 1
                entry.keywords.any { it.startsWith(typed) } -> 2
                entry.keywords.any { it.contains(typed) } -> 3
                tabs.any { it.startsWith(typed) } -> 4
                else -> return@mapNotNull null
            }
            entry to rank
        }
        .sortedBy { it.second }
        .take(limit)
        .map { it.first }
}

/** The result's title as it reads on screen, and as its row anchors. */
@androidx.compose.runtime.Composable
fun SettingsEntry.displayTitle(): String =
    titleRes?.let { androidx.compose.ui.res.stringResource(it) } ?: title

/** [displayTitle] outside composition, for the anchor request a tap makes. */
fun SettingsEntry.displayTitle(context: android.content.Context): String =
    titleRes?.let { context.getString(it) } ?: title
