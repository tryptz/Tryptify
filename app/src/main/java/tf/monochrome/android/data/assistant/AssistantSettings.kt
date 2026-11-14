package tf.monochrome.android.data.assistant

import tf.monochrome.android.data.preferences.PreferencesManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The settings the assistant may read and change: the listener's synced
 * choices ([ASSISTANT_SETTING_KEYS]), never a sign-in, an address or another
 * editor's whole document. Switches and numbers can be changed; a text
 * setting can only be read, since which texts it accepts is not known here
 * and a wrong one could not be told from a right one.
 */
@Singleton
class AssistantSettings @Inject constructor(private val preferences: PreferencesManager) {

    enum class Kind(val changeable: Boolean) {
        BOOLEAN(true), INT(true), LONG(true), FLOAT(true), DOUBLE(true), TEXT(false)
    }

    /** What changing a setting came to. */
    sealed interface Change {
        data class Done(val value: Any) : Change
        data class Refused(val why: String) : Change
    }

    /** The stored values of [names]; one that is not the assistant's to read, or was never changed, is not in it. */
    suspend fun read(names: Collection<String>): Map<String, Any> =
        preferences.storedValues(names.filter { it in ASSISTANT_SETTING_KEYS })

    suspend fun change(name: String, value: Any?): Change =
        when (val typed = typed(name, value)) {
            is Change.Done -> {
                preferences.storeValue(name, typed.value)
                typed
            }
            is Change.Refused -> typed
        }

    companion object {
        /**
         * [value] as the type [name] is stored as, or why not. A value of the
         * wrong type is refused, not bent into shape: stored wrongly it would
         * crash the app where the setting is next read.
         */
        fun typed(name: String, value: Any?): Change {
            val kind = ASSISTANT_SETTING_KEYS[name] ?: return Change.Refused("There is no setting \"$name\" that can be changed here.")
            if (!kind.changeable) return Change.Refused("\"$name\" can be read but not changed here: open its screen for the listener instead.")
            val number = (value as? Number)?.toDouble()?.takeIf { it.isFinite() }
            val whole = number?.takeIf { it == Math.floor(it) && Math.abs(it) < 2e9 }
            return when (kind) {
                Kind.BOOLEAN -> (value as? Boolean)?.let { Change.Done(it) } ?: Change.Refused("\"$name\" is a switch: give true or false.")
                Kind.INT -> whole?.let { Change.Done(it.toInt()) } ?: Change.Refused("\"$name\" is a whole number.")
                Kind.LONG -> whole?.let { Change.Done(it.toLong()) } ?: Change.Refused("\"$name\" is a whole number.")
                Kind.FLOAT -> number?.let { Change.Done(it.toFloat()) } ?: Change.Refused("\"$name\" is a number.")
                Kind.DOUBLE -> number?.let { Change.Done(it) } ?: Change.Refused("\"$name\" is a number.")
                Kind.TEXT -> Change.Refused("\"$name\" cannot be changed here.")
            }
        }
    }
}
