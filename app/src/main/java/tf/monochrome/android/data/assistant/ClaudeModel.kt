package tf.monochrome.android.data.assistant

/**
 * The model the assistant and Ask Claude use, the listener's choice: what
 * they ask is billed to their own key, and the models differ in price by
 * more than tenfold. [id] is the API's name for it, [stored] what is kept.
 */
enum class ClaudeModel(val id: String, val stored: String) {
    OPUS("claude-opus-5-5", "opus"),
    SONNET("claude-sonnet-5-5", "sonnet"),
    HAIKU("claude-haiku-5-5", "haiku");

    companion object {
        val DEFAULT = OPUS
        fun of(stored: String?): ClaudeModel = entries.firstOrNull { it.stored == stored } ?: DEFAULT
    }
}
