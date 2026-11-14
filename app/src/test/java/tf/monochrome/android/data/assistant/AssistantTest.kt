package tf.monochrome.android.data.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tf.monochrome.android.ui.assistant.assistantRouteOf
import java.io.File

class AssistantTest {

    private val preferences = File("src/main/java/tf/monochrome/android/data/preferences/PreferencesManager.kt").readText()
    private val manual = File("src/main/assets/assistant/manual.md").readText()

    /** Every key PreferencesManager declares, by its DataStore name, with the type it is declared as. */
    private val declared: Map<String, Pair<String, String>> =
        Regex("""val (\w+)\s*=\s*(boolean|int|long|float|double|string|stringSet)PreferencesKey\(\s*"([^"]+)"""")
            .findAll(preferences).associate { it.groupValues[3] to (it.groupValues[1] to it.groupValues[2]) }

    private val synced: Set<String> = Regex("""val SETTINGS_SYNC_KEYS: Set<Preferences\.Key<\*>> = setOf\((.*?)\n        \)""", RegexOption.DOT_MATCHES_ALL)
        .find(preferences)!!.groupValues[1]
        .replace(Regex("//[^\n]*"), "")
        .let { block -> Regex("""\b[A-Z][A-Z0-9_]+\b""").findAll(block).map { it.value }.toSet() }

    @Test
    fun `every setting the assistant may touch is a synced one, stored as the table says`() {
        assertTrue(ASSISTANT_SETTING_KEYS.size > 50)
        for ((key, kind) in ASSISTANT_SETTING_KEYS) {
            val (constant, type) = declared[key] ?: error("$key is not a key PreferencesManager declares: run tools/assistant_settings/generate.py")
            assertTrue("$key is not a synced setting", constant in synced)
            val expected = when (type) {
                "boolean" -> AssistantSettings.Kind.BOOLEAN
                "int" -> AssistantSettings.Kind.INT
                "long" -> AssistantSettings.Kind.LONG
                "float" -> AssistantSettings.Kind.FLOAT
                "double" -> AssistantSettings.Kind.DOUBLE
                else -> AssistantSettings.Kind.TEXT
            }
            // A value stored under the wrong type crashes the app where the setting is next read.
            assertEquals("$key: run tools/assistant_settings/generate.py", expected, kind)
        }
    }

    @Test
    fun `no sign-in, address or other editor's document is in reach`() {
        for (key in ASSISTANT_SETTING_KEYS.keys) {
            val constant = declared.getValue(key).first
            assertFalse("$key must not be in reach", Regex("_JSON$|_URL$|ENDPOINT|^API_SERVERS$|USERNAME|TOKEN|PASSWORD|SECRET|KEY$").containsMatchIn(constant))
        }
        for (secret in listOf("claude_api_key", "api_server_passwords", "subsonic_server")) {
            assertFalse(secret in ASSISTANT_SETTING_KEYS)
        }
    }

    @Test
    fun `a value is stored as the setting's own type or not at all`() {
        fun done(name: String, value: Any?) = (AssistantSettings.typed(name, value) as? AssistantSettings.Change.Done)?.value
        // What Claude sends arrives as JSON: every number a Double or an Int, whatever the setting holds.
        assertEquals(true, done("gapless_playback", true))
        assertEquals(null, done("gapless_playback", "true"))
        assertEquals(null, done("gapless_playback", 1))
        assertEquals(3, done("crossfade_duration", 3.0))
        assertTrue(done("crossfade_duration", 3.0) is Int)
        assertEquals(null, done("crossfade_duration", 2.5))
        assertEquals(null, done("crossfade_duration", Double.NaN))
        val float = ASSISTANT_SETTING_KEYS.entries.first { it.value == AssistantSettings.Kind.FLOAT }.key
        assertTrue(done(float, 1) is Float)
        assertEquals(null, done(float, Double.POSITIVE_INFINITY))
        // A text setting can be read, never written; a name outside the table is not a setting.
        assertEquals(null, done("theme", "dark"))
        assertEquals(null, done("claude_api_key", "x"))
        assertEquals(null, done("gapless_playback", null))
    }

    @Test
    fun `the assistant is told the manual, every setting and nothing it cannot open`() {
        val system = AssistantTools.system(manual)
        assertTrue("Settings › Audio › Gapless playback" in system)
        for (key in ASSISTANT_SETTING_KEYS.keys) assertTrue(key, "- $key (" in system)
        assertTrue("theme (text, read only)" in system)
        assertTrue("gapless_playback (switch, changeable)" in system)
        // Every screen it may name leads somewhere, and is one the manual or the app has.
        for (screen in AssistantTools.SCREENS.keys) assertNotNull(screen, assistantRouteOf(screen))
        assertEquals(null, assistantRouteOf("nowhere"))
    }

    @Test
    fun `the tools are the five it runs, each naming what it needs`() {
        assertEquals(
            setOf(AssistantTools.GET_SETTINGS, AssistantTools.SET_SETTING, AssistantTools.OPEN_SCREEN, AssistantTools.PLAYBACK, AssistantTools.NOW_PLAYING),
            AssistantTools.DEFINITIONS.map { it.name }.toSet(),
        )
        for (tool in AssistantTools.DEFINITIONS) {
            assertTrue(tool.name, tool.properties.keys.containsAll(tool.required))
            assertTrue(tool.name, tool.description.length > 40)
        }
    }

    @Test
    fun `the model is the listener's choice, and Opus until they make one`() {
        assertEquals(ClaudeModel.OPUS, ClaudeModel.of(""))
        assertEquals(ClaudeModel.OPUS, ClaudeModel.of("something older"))
        for (model in ClaudeModel.entries) assertEquals(model, ClaudeModel.of(model.stored))
        assertEquals("claude-haiku-5-5", ClaudeModel.HAIKU.id)
        // A model's name is never a setting the assistant can reach, nor is the key.
        assertFalse("claude_model" in ASSISTANT_SETTING_KEYS)
    }

    @Test
    fun `what was read is said plainly, defaults and strangers included`() {
        val text = AssistantTools.describeValues(listOf("gapless_playback", "crossfade_duration", "made_up"), mapOf("gapless_playback" to false))
        assertEquals("gapless_playback: false\ncrossfade_duration: default (never changed)\nmade_up: there is no such setting in the list", text)
    }
}
