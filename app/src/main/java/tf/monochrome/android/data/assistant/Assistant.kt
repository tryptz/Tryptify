package tf.monochrome.android.data.assistant

import android.content.Context
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The in-app assistant: a conversation with Claude, on the listener's own API
 * key, in which Claude knows what the app does (the manual it ships with)
 * and can act in it through a few tools: read and change settings, open a
 * screen, control playback.
 *
 * It asks before every change. [auto] lets changes through without asking;
 * it is held in memory only, so it is off again whenever the app has closed
 * (the owner's rule). Reading is never asked about.
 */
@Singleton
class Assistant @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
    private val settings: AssistantSettings,
) {
    /** Changes go through without asking, until the app closes. Never stored. */
    val auto = MutableStateFlow(false)

    /** What the screen the assistant is on lends it: the listener's yes or no, and the things only a screen can do. */
    interface Host {
        /** Shows [what] is about to be done and waits for the listener's answer. */
        suspend fun approve(what: String): Boolean
        fun openScreen(screen: String): Boolean
        /** Does [action] ([AssistantTools.PLAYBACK_ACTIONS]). */
        fun playback(action: String)
        /** What is playing, in a sentence, for Claude. */
        fun nowPlaying(): String
        /** Claude said [text]. */
        fun said(text: String)
        /** [what] was done (or declined), to show in the conversation. */
        fun did(what: String, done: Boolean)
    }

    enum class Failure { NO_KEY, KEY_REFUSED, BUSY, NETWORK, DECLINED, TOO_LONG, OTHER }

    /** The conversation so far, as it is sent each time. Kept by whoever owns the conversation. */
    class Conversation {
        internal val messages = ArrayList<MessageParam>()
        fun isEmpty() = messages.isEmpty()
        fun clear() = messages.clear()
    }

    private val system: String by lazy {
        val manual = context.assets.open("assistant/manual.md").bufferedReader().use { it.readText() }
        AssistantTools.system(manual)
    }

    /**
     * Sends [text] and carries the turn through: every tool Claude calls is
     * run (after the listener's yes, for a change) and answered, until Claude
     * has nothing more to do. Null when it ended well.
     */
    suspend fun send(conversation: Conversation, text: String, host: Host): Failure? = withContext(Dispatchers.IO) {
        val key = preferences.claudeApiKey.first()
        if (key.isEmpty()) return@withContext Failure.NO_KEY
        val before = conversation.messages.size
        conversation.messages += MessageParam.ofUser(text)
        val model = ClaudeModel.of(preferences.claudeModel.first()).id
        val client = AnthropicOkHttpClient.builder().apiKey(key).build()
        try {
            repeat(MAX_STEPS) {
                val params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(MAX_TOKENS)
                    // The manual and the tools never change within a conversation: cached after the first request.
                    .systemOfTextBlockParams(
                        listOf(
                            TextBlockParam.builder().text(system)
                                .cacheControl(CacheControlEphemeral.builder().build())
                                .build()
                        )
                    )
                    .apply { for (tool in tools) addTool(tool) }
                    .messages(conversation.messages)
                    .build()
                val message = client.messages().create(params)
                conversation.messages += message.toParam()
                for (block in message.content()) block.text().ifPresent { if (it.text().isNotBlank()) host.said(it.text().trim()) }
                when (message.stopReason().orElse(null)) {
                    StopReason.REFUSAL -> return@withContext Failure.DECLINED
                    StopReason.MAX_TOKENS, StopReason.MODEL_CONTEXT_WINDOW_EXCEEDED -> return@withContext Failure.TOO_LONG
                    StopReason.TOOL_USE -> Unit
                    else -> return@withContext null
                }
                // Every call is answered, in one message, or the next request is refused.
                val results = message.content().mapNotNull { it.toolUse().orElse(null) }.map { call ->
                    val input = runCatching { call._input().convert(Map::class.java) as Map<*, *> }.getOrDefault(emptyMap<Any, Any>())
                    val outcome = run(call.name(), input, host)
                    ContentBlockParam.ofToolResult(
                        ToolResultBlockParam.builder().toolUseId(call.id()).content(outcome.text).isError(!outcome.ok).build()
                    )
                }
                conversation.messages += MessageParam.ofUserOfContentBlockParams(results)
            }
            Failure.TOO_LONG
        } catch (e: UnauthorizedException) {
            forget(conversation, before)
            Failure.KEY_REFUSED
        } catch (e: RateLimitException) {
            forget(conversation, before)
            Failure.BUSY
        } catch (e: AnthropicServiceException) {
            forget(conversation, before)
            if (e.statusCode() >= 500) Failure.BUSY else Failure.OTHER
        } catch (e: AnthropicIoException) {
            forget(conversation, before)
            Failure.NETWORK
        } finally {
            client.close()
        }
    }

    /**
     * A turn that failed is taken back out, so the conversation that is sent
     * next is one the API will take: it must not end on a tool call nobody
     * answered. What was done in it stays done, and stays on the page.
     */
    private fun forget(conversation: Conversation, size: Int) {
        while (conversation.messages.size > size) conversation.messages.removeAt(conversation.messages.size - 1)
    }

    private class Outcome(val text: String, val ok: Boolean = true)

    private suspend fun run(tool: String, input: Map<*, *>, host: Host): Outcome = when (tool) {
        AssistantTools.GET_SETTINGS -> {
            val names = (input["names"] as? List<*>).orEmpty().filterIsInstance<String>()
            Outcome(AssistantTools.describeValues(names, settings.read(names)))
        }
        AssistantTools.NOW_PLAYING -> Outcome(host.nowPlaying())
        AssistantTools.SET_SETTING -> {
            val name = input["name"] as? String ?: ""
            when (val typed = AssistantSettings.typed(name, input["value"])) {
                is AssistantSettings.Change.Refused -> Outcome(typed.why, ok = false)
                is AssistantSettings.Change.Done -> asked("Set $name to ${typed.value}", host) {
                    settings.change(name, typed.value)
                    Outcome("$name is now ${typed.value}.")
                }
            }
        }
        AssistantTools.OPEN_SCREEN -> {
            val screen = input["screen"] as? String ?: ""
            if (screen !in AssistantTools.SCREENS) Outcome("There is no screen \"$screen\".", ok = false)
            else asked("Open ${AssistantTools.SCREENS.getValue(screen)}", host) {
                if (withContext(Dispatchers.Main) { host.openScreen(screen) }) Outcome("Opened.") else Outcome("It could not be opened just now.", ok = false)
            }
        }
        AssistantTools.PLAYBACK -> {
            val action = input["action"] as? String ?: ""
            if (action !in AssistantTools.PLAYBACK_ACTIONS) Outcome("There is no playback action \"$action\".", ok = false)
            else asked(AssistantTools.PLAYBACK_ACTIONS.getValue(action), host) {
                withContext(Dispatchers.Main) { host.playback(action) }
                Outcome("Done.")
            }
        }
        else -> Outcome("There is no tool \"$tool\".", ok = false)
    }

    /** Does [act] once the listener has said yes to [what], or at once on auto; says so either way. */
    private suspend fun asked(what: String, host: Host, act: suspend () -> Outcome): Outcome {
        if (!auto.value && !host.approve(what)) {
            host.did(what, done = false)
            return Outcome("The listener said no to this. Do not try it again unless they ask.", ok = false)
        }
        return act().also { host.did(what, done = it.ok) }
    }

    private val tools: List<Tool> = AssistantTools.DEFINITIONS.map { definition ->
        Tool.builder()
            .name(definition.name)
            .description(definition.description)
            .inputSchema(
                Tool.InputSchema.builder()
                    .properties(
                        Tool.InputSchema.Properties.builder()
                            .apply { for ((name, schema) in definition.properties) putAdditionalProperty(name, JsonValue.from(schema)) }
                            .build()
                    )
                    .required(definition.required)
                    .build()
            )
            .build()
    }

    private companion object {
        const val MAX_TOKENS = 16_000L
        const val MAX_STEPS = 12   // requests in one turn: a turn that needs more has lost its way
    }
}
