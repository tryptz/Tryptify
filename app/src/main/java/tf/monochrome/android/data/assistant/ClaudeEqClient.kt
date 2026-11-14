package tf.monochrome.android.data.assistant

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlockParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Asks Claude to lay out an equaliser, with the listener's own API key: a
 * picture of a graph, their filters and their wish go up, filters come back
 * ([ClaudeEq] has what is said and how the answer is read). One request, one
 * answer; nothing here changes the equaliser.
 */
@Singleton
class ClaudeEqClient @Inject constructor() {

    sealed interface Result {
        data class Proposed(val proposal: ClaudeEq.Proposal) : Result
        /** Why there is no proposal. */
        enum class Failed : Result { KEY_REFUSED, BUSY, NETWORK, DECLINED, UNREADABLE }
    }

    /** [jpeg] is the picture, already made small enough to send, or null for none. */
    suspend fun ask(apiKey: String, jpeg: ByteArray?, request: String): Result = withContext(Dispatchers.IO) {
        try {
            val content = buildList {
                if (jpeg != null) {
                    add(
                        ContentBlockParam.ofImage(
                            ImageBlockParam.builder()
                                .source(
                                    Base64ImageSource.builder()
                                        .data(java.util.Base64.getEncoder().encodeToString(jpeg))
                                        .mediaType(Base64ImageSource.MediaType.IMAGE_JPEG)
                                        .build()
                                )
                                .build()
                        )
                    )
                }
                add(ContentBlockParam.ofText(TextBlockParam.builder().text(request).build()))
            }
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(MAX_TOKENS)
                .system(ClaudeEq.SYSTEM)
                .outputConfig(
                    OutputConfig.builder()
                        // Reading a graph's scale and curve off a picture is the careful part.
                        .effort(OutputConfig.Effort.HIGH)
                        .format(JsonOutputFormat.builder().schema(schema()).build())
                        .build()
                )
                .addUserMessageOfContentBlockParams(content)
                .build()
            val client = AnthropicOkHttpClient.builder().apiKey(apiKey).build()
            val message = try {
                client.messages().create(params)
            } finally {
                client.close()
            }
            // A refusal or a cut-off answer has no filters in it: say which, do not parse what is there.
            val stopped = message.stopReason().orElse(null)
            if (stopped == StopReason.REFUSAL) return@withContext Result.Failed.DECLINED
            if (stopped == StopReason.MAX_TOKENS) return@withContext Result.Failed.UNREADABLE
            val text = buildString { for (block in message.content()) block.text().ifPresent { append(it.text()) } }
            ClaudeEq.parse(text)?.let { Result.Proposed(it) } ?: Result.Failed.UNREADABLE
        } catch (e: UnauthorizedException) {
            Result.Failed.KEY_REFUSED
        } catch (e: RateLimitException) {
            Result.Failed.BUSY
        } catch (e: AnthropicServiceException) {
            // Overloaded and other server-side failures pass; a request the API will not take does not.
            if (e.statusCode() >= 500 || e.statusCode() == 429) Result.Failed.BUSY else Result.Failed.UNREADABLE
        } catch (e: AnthropicIoException) {
            Result.Failed.NETWORK
        }
    }

    /** [ClaudeEq.SCHEMA] as the SDK takes a schema: its top-level keys, each as a JSON value. */
    private fun schema(): JsonOutputFormat.Schema {
        val builder = JsonOutputFormat.Schema.builder()
        for ((key, value) in Json.parseToJsonElement(ClaudeEq.SCHEMA) as JsonObject) {
            builder.putAdditionalProperty(key, JsonValue.from(plain(value)))
        }
        return builder.build()
    }

    /** A kotlinx JSON element as plain maps, lists and values, which is what the SDK's JSON value is made from. */
    private fun plain(element: JsonElement): Any? = when (element) {
        is JsonObject -> element.mapValues { plain(it.value) }
        is JsonArray -> element.map(::plain)
        is JsonPrimitive -> when {
            element.isString -> element.content
            else -> element.booleanOrNull ?: element.doubleOrNull
        }
    }

    private companion object {
        const val MODEL = "claude-opus-5-5"
        const val MAX_TOKENS = 16_000L
    }
}
