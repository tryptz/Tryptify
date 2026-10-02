package tf.monochrome.android.ui.components

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Text a ViewModel hands the UI without deciding its words.
 *
 * A ViewModel's own context is the application's, and below Android 13 that
 * one is not in the language the listener picked in Settings — only the
 * activity is (see `AppLanguage.wrap`). So a message built there would come out
 * in the phone's language until the process restarts. A ViewModel says *which*
 * message instead, and whoever shows it resolves it against the activity.
 *
 * [Raw] is for text that is already final — a file name, an error message
 * from somewhere else — and is shown as it is.
 */
sealed interface UiText {
    fun resolve(context: Context): String

    /** [args] may themselves be [UiText] — a count phrase inside a sentence — and are resolved first. */
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText {
        override fun resolve(context: Context): String = context.getString(id, *args.resolved(context))
    }

    /** A count, in each language's own plural forms. [args] default to the count itself. */
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText {
        override fun resolve(context: Context): String =
            context.resources.getQuantityString(id, count, *args.resolved(context))
    }

    data class Raw(val text: String) : UiText {
        override fun resolve(context: Context): String = text
    }
}

private fun List<Any>.resolved(context: Context): Array<Any> =
    map { if (it is UiText) it.resolve(context) else it }.toTypedArray()

/**
 * What went wrong, for an error state: the failure's own message when it has
 * one — it is usually the most specific thing there is to say — otherwise our
 * own [fallback], in the listener's language.
 */
fun errorText(error: Throwable, @StringRes fallback: Int): UiText =
    error.message?.takeIf { it.isNotBlank() }?.let { UiText.Raw(it) } ?: UiText.Res(fallback)
