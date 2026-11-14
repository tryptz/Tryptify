package tf.monochrome.android.data.assistant

/**
 * What the assistant is told and what it can call: the words of its system
 * prompt and the definitions of its tools. No network and no Android here,
 * so it is tested.
 */
object AssistantTools {
    const val GET_SETTINGS = "get_settings"
    const val SET_SETTING = "set_setting"
    const val OPEN_SCREEN = "open_screen"
    const val PLAYBACK = "playback"
    const val NOW_PLAYING = "now_playing"

    /** The screens that can be opened, by the name Claude uses, each with what it is called to the listener. */
    val SCREENS: Map<String, String> = linkedMapOf(
        "home" to "Home", "search" to "Search", "discover" to "Discover", "library" to "Library",
        "downloads" to "Downloads", "now_playing" to "the player", "settings" to "Settings",
        "equalizer" to "the AutoEQ equalizer", "parametric_eq" to "the Parametric EQ", "mixer" to "the Mixer",
        "crossfeed" to "Crossfeed", "atmos_renderer" to "the Atmos renderer", "hrtf_database" to "the HRTF database",
        "room_database" to "the room database", "lyrics_fx_studio" to "the Player Visuals Studio",
        "music_links" to "Music links", "theme_store" to "the Theme Store", "widget_settings" to "the widget's settings",
        "stats" to "Stats", "profile" to "your profile", "listening_party" to "Listening parties",
        "library_galaxy" to "the library galaxy", "genre_map" to "the genre map", "trypt_mania" to "Trypt Mania",
        "debug_log" to "the debug log",
    )

    /** What playback can be told to do, each as it is put to the listener. */
    val PLAYBACK_ACTIONS: Map<String, String> = linkedMapOf(
        "play_pause" to "Play or pause", "next" to "Skip to the next song", "previous" to "Go back a song",
    )

    class Definition(val name: String, val description: String, val properties: Map<String, Any>, val required: List<String>)

    val DEFINITIONS: List<Definition> = listOf(
        Definition(
            GET_SETTINGS,
            "Reads settings by name. Call it before changing a setting, to know what it is now, and to answer what something is set to. A setting that was never changed from its default has no stored value and comes back as \"default\".",
            mapOf("names" to mapOf("type" to "array", "items" to mapOf("type" to "string"), "description" to "Names from the settings list.")),
            listOf("names"),
        ),
        Definition(
            SET_SETTING,
            "Changes one switch or number. The listener is shown the change and may say no. Only settings marked changeable in the settings list can be changed; for anything else, open its screen.",
            mapOf(
                "name" to mapOf("type" to "string", "description" to "A changeable name from the settings list."),
                "value" to mapOf("description" to "true or false for a switch, a number for a number."),
            ),
            listOf("name", "value"),
        ),
        Definition(
            OPEN_SCREEN,
            "Takes the listener to a screen of the app. Use it when what they want is done on that screen and not through a setting here. The listener is shown where they are being taken and may say no.",
            mapOf("screen" to mapOf("type" to "string", "enum" to SCREENS.keys.toList())),
            listOf("screen"),
        ),
        Definition(
            PLAYBACK,
            "Controls what is playing. The listener is shown the action and may say no.",
            mapOf("action" to mapOf("type" to "string", "enum" to PLAYBACK_ACTIONS.keys.toList())),
            listOf("action"),
        ),
        Definition(NOW_PLAYING, "Says what is playing now, and whether it is playing or paused.", emptyMap(), emptyList()),
    )

    /** The system prompt: who the assistant is, how it works here, then the manual and what it can reach. */
    fun system(manual: String): String = buildString {
        append(
            """You are the assistant inside Tryptify, a music player for Android. The person talking to you is using the app on their phone right now and wants help with it: finding where something is, understanding what a feature does, deciding how to set something up, or having you do it for them.

You can act in the app through your tools: read settings, change a switch or a number, open a screen, and control playback. Every change you make is shown to the person first and they can say no, unless they have turned on Auto. If they say no, leave it and ask what they would like instead. When they ask you to do something you have a tool for, do it; do not tell them the steps and stop. When there is no tool for it, open the screen where it is done and tell them what to tap there, using the names in the manual below.

Read a setting before you change it, and say what you changed and what it was, so they can ask you to put it back. Change only what they asked for. If what they want could mean two different settings, ask which.

Only say what the manual and the settings list below support. If they ask about something that is not there, say you do not know that part of the app and offer to open the nearest screen. Do not invent settings, screens or features.

You are read on a phone: keep answers short, in plain words, with no headings. A settings path reads as Settings › Tab › Row.

"""
        )
        append("# The manual\n\n").append(manual.trim()).append("\n\n")
        append("# The settings list\n\nEach setting by its name and what it is stored as. \"changeable\" ones can be given to set_setting; the rest can be read only.\n\n")
        for ((name, kind) in ASSISTANT_SETTING_KEYS) {
            val what = when (kind) {
                AssistantSettings.Kind.BOOLEAN -> "switch, changeable"
                AssistantSettings.Kind.TEXT -> "text, read only"
                AssistantSettings.Kind.INT, AssistantSettings.Kind.LONG -> "whole number, changeable"
                AssistantSettings.Kind.FLOAT, AssistantSettings.Kind.DOUBLE -> "number, changeable"
            }
            append("- ").append(name).append(" (").append(what).append(")\n")
        }
        append("\nA number has no stated range here: read its value first, change it in small steps unless asked otherwise, and keep to the kind of value it holds.\n")
    }

    /** What reading [names] found, as text for Claude. */
    fun describeValues(names: List<String>, stored: Map<String, Any>): String =
        if (names.isEmpty()) "No names were given."
        else names.joinToString("\n") { name ->
            when {
                name !in ASSISTANT_SETTING_KEYS -> "$name: there is no such setting in the list"
                name in stored -> "$name: ${stored.getValue(name)}"
                else -> "$name: default (never changed)"
            }
        }
}
