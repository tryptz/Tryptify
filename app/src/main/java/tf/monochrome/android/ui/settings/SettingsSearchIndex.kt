package tf.monochrome.android.ui.settings

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
 * [keywords] carry the words someone would actually type that are not in the
 * title — "bass" for the equaliser, "battery" for performance, "cache" for
 * storage. Without them the search only finds settings whose name you already
 * knew, which is the case where you did not need to search.
 */
data class SettingsEntry(
    val title: String,
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
    entry("Theme", "Appearance", listOf("colour", "color", "dark", "light", "white")),
    entry("Light paper", "Appearance", listOf("white", "warm", "crisp", "paper", "glare")),
    entry(
        "Color transition", "Appearance",
        listOf("colour", "fade", "crossfade", "blend", "album", "speed", "duration"),
    ),
    entry("Dynamic colours", "Appearance", listOf("album", "art", "material you", "accent")),
    entry("Font scale", "Appearance", listOf("text size", "bigger", "smaller", "accessibility")),
    entry("Custom font", "Appearance", listOf("typeface", "import font")),
    entry("Player Visuals Studio", "Appearance", listOf("lyrics", "glass", "fx", "visuals", "beat"))
        .at(SettingsDestination.Route("lyrics_fx_studio")),
    entry("Now playing view", "Appearance", listOf("player", "layout", "lyrics")),
    entry("Blurred background", "Appearance", listOf("player", "artwork", "blur")),

    // ── Audio ───────────────────────────────────────────────────────────
    entry("Gapless playback", "Audio", listOf("gap", "continuous", "album")),
    entry("Crossfade", "Audio", listOf("fade", "transition", "blend")),
    entry("Normalisation", "Audio", listOf("loudness", "replaygain", "volume", "level")),
    entry("Playback speed", "Audio", listOf("tempo", "faster", "slower", "pitch")),
    entry("Multichannel downmix", "Audio", listOf("surround", "5.1", "atmos", "stereo")),
    entry("Spatial renderer", "Audio", listOf("atmos", "hrtf", "binaural", "spatial"))
        .at(SettingsDestination.Route("atmos_renderer")),
    entry("HRTF database", "Audio", listOf("binaural", "head", "spatial", "sofa"))
        .at(SettingsDestination.Route("hrtf_database")),
    entry("Crossfeed", "Audio", listOf("headphone", "stereo", "bauer", "fatigue"))
        .at(SettingsDestination.Route("crossfeed")),
    entry("Mixer", "Audio", listOf("dsp", "bus", "plugin", "insert", "channel"))
        .at(SettingsDestination.Route("mixer")),
    entry("Streaming quality", "Audio", listOf("bitrate", "wifi", "cellular", "data")),

    // ── Equalizer ───────────────────────────────────────────────────────
    entry("Equalizer", "Equalizer", listOf("eq", "bass", "treble", "bands", "graphic"))
        .at(SettingsDestination.Route("equalizer")),
    entry("Parametric EQ", "Equalizer", listOf("eq", "filter", "peaking", "shelf", "q"))
        .at(SettingsDestination.Route("parametric_eq")),
    entry("AutoEQ headphone profile", "Equalizer", listOf("headphone", "harman", "target", "correction")),
    entry("Spectrum analyzer", "Equalizer", listOf("fft", "visualiser", "frequency", "meter")),

    // ── Library ─────────────────────────────────────────────────────────
    entry("Library tab order", "Library", listOf("reorder", "tabs", "layout")),
    entry("Local folders", "Library", listOf("storage", "saf", "sd card", "path")),

    // ── Downloads ───────────────────────────────────────────────────────
    entry("Download quality", "Downloads", listOf("bitrate", "flac", "offline")),
    entry("Download lyrics", "Downloads", listOf("offline", "synced")),
    entry("Auto-download liked", "Downloads", listOf("offline", "favourites", "hearted")),
    entry("Download centre", "Downloads", listOf("queue", "progress", "offline"))
        .at(SettingsDestination.Route("downloads")),

    // ── Connections ─────────────────────────────────────────────────────
    entry("Last.fm scrobbling", "Connections", listOf("scrobble", "account", "history")),
    entry("ListenBrainz", "Connections", listOf("scrobble", "account", "history")),
    entry("Custom API endpoint", "Connections", listOf("server", "instance", "url")),
    entry("Qobuz instance", "Connections", listOf("server", "url", "source")),
    entry("Apple instance", "Connections", listOf("server", "url", "source")),
    entry("Source mode", "Connections", listOf("provider", "catalogue", "backend")),

    // ── Radio ───────────────────────────────────────────────────────────
    entry("AI radio", "Radio", listOf("station", "recommend", "queue", "seed")),
    entry("Radio planner", "Radio", listOf("station", "queue", "url")),
    entry("Radio weights", "Radio", listOf("tuning", "similarity", "novelty", "familiarity")),

    // ── System ──────────────────────────────────────────────────────────
    entry("Performance", "System", listOf("battery", "fps", "low power", "glass", "blur")),
    entry("Debug log", "System", listOf("logs", "diagnostics", "report", "crash"))
        .at(SettingsDestination.Route("debug_log")),
    entry("Developer mode", "System", listOf("dev", "advanced", "hidden")),
    entry("Backup and restore", "System", listOf("export", "import", "settings", "transfer")),
    entry("Clear cache", "System", listOf("storage", "space", "images")),

    // ── About ───────────────────────────────────────────────────────────
    entry("What's new", "About", listOf("changelog", "release", "version", "updates")),
    entry("Version", "About", listOf("build", "about", "release")),
    entry("Licences", "About", listOf("open source", "attribution", "credits")),
)

private fun entry(title: String, tabLabel: String, keywords: List<String>) = SettingsEntry(
    title = title,
    tabLabel = tabLabel,
    destination = SettingsDestination.Tab(settingsTabIndex(tabLabel)),
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
 */
fun searchSettings(query: String, limit: Int = 8): List<SettingsEntry> {
    val typed = query.trim().lowercase()
    if (typed.length < 2) return emptyList()
    return SettingsSearchIndex
        .mapNotNull { entry ->
            val title = entry.title.lowercase()
            val rank = when {
                title.startsWith(typed) -> 0
                title.contains(typed) -> 1
                entry.keywords.any { it.startsWith(typed) } -> 2
                entry.keywords.any { it.contains(typed) } -> 3
                entry.tabLabel.lowercase().startsWith(typed) -> 4
                else -> return@mapNotNull null
            }
            entry to rank
        }
        .sortedBy { it.second }
        .take(limit)
        .map { it.first }
}
