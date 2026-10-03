package tf.monochrome.android.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The app's own display language, independent of the phone's.
 *
 * Two mechanisms, because the platform grew one halfway through this app's
 * supported range:
 *
 *  - **Android 13+** has per-app languages built in. [LocaleManager] stores the
 *    choice, the system applies it to every component of the app (activity,
 *    service notifications, widget), restores it after an update, and lists the
 *    app under Settings › System › Languages › App languages — that list comes
 *    from the `localeConfig` the build generates from the `values-*` folders.
 *    Setting it recreates the activity, which is how the UI picks it up.
 *  - **Below 13** there is no such service. The tag is kept in a plain
 *    SharedPreferences file — it has to be read synchronously in
 *    `attachBaseContext`, before anything else exists, which rules out the
 *    DataStore the rest of the settings live in — and [wrap] builds the
 *    activity's context with it.
 *
 * An empty tag means "follow the phone", which is the default and the only
 * value that is never stored as a locale.
 */
object AppLanguage {

    /**
     * One language on offer. [nativeName] is the language's name in itself —
     * 日本語, not "Japanese" — so whoever lands in a language they cannot read
     * can still find their own in the list.
     */
    data class Option(val tag: String, val nativeName: String)

    /**
     * The languages there are translations for, in the picker's order. Keep in
     * step with the `values-*` folders: a tag here with no folder behind it
     * would switch the app to English while claiming otherwise.
     *
     * Chinese is Simplified (`zh-CN`), the script of the mainland and of
     * Singapore. A Traditional reader (Taiwan, Hong Kong) whose phone asks for
     * zh-TW does not match it and gets English rather than a script they did not
     * choose; they can still pick it here.
     */
    val OPTIONS: List<Option> = listOf(
        Option("en", "English"),
        Option("zh-CN", "简体中文"),
        Option("ja", "日本語"),
        Option("fr", "Français"),
        Option("es", "Español"),
        Option("tr", "Türkçe"),
        Option("de", "Deutsch"),
    )

    /** The tag in effect: one of [OPTIONS]' tags, or "" for the phone's language. */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales
            if (locales == null || locales.isEmpty) return ""
            return matchOption(locales[0].toLanguageTag())
        }
        return matchOption(prefs(context).getString(KEY, "") ?: "")
    }

    /**
     * Switches the app to [tag] ("" for the phone's language).
     *
     * On 13+ the system recreates the activity itself. Below that the caller's
     * activity has to be recreated, which this does when [context] is one.
     */
    fun set(context: Context, tag: String) {
        val clean = matchOption(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (clean.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(clean)
            return
        }
        prefs(context).edit().putString(KEY, clean).apply()
        (context as? android.app.Activity)?.recreate()
    }

    /**
     * The activity's base context in the chosen language, for below 13; on 13+
     * the system has already done this and [base] is returned untouched.
     *
     * Also moves [Locale.getDefault], as the system does when it applies a
     * language: `String.format` and the number formatting in the UI read it, so
     * a German screen writes "1,5 dB" rather than mixing conventions.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = prefs(base).getString(KEY, "") ?: ""
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /**
     * [tag] as the [OPTIONS] tag it means, or "" if it means none of them.
     *
     * Exact match first, then by language alone, so a stored or system-reported
     * `de-AT` or `fr-CA` still lands on German or French. Chinese is the one
     * language where the region carries the script: `zh-TW` and `zh-HK` (and an
     * explicit `zh-Hant`) are Traditional and must not be taken for the
     * Simplified translation.
     */
    fun matchOption(tag: String): String {
        if (tag.isBlank()) return ""
        OPTIONS.firstOrNull { it.tag.equals(tag, ignoreCase = true) }?.let { return it.tag }
        val locale = Locale.forLanguageTag(tag)
        val language = locale.language
        if (language == "zh") {
            val traditional = locale.script.equals("Hant", ignoreCase = true) ||
                (locale.script.isEmpty() && locale.country.uppercase() in TRADITIONAL_REGIONS)
            return if (traditional) "" else "zh-CN"
        }
        return OPTIONS.firstOrNull { Locale.forLanguageTag(it.tag).language == language }?.tag ?: ""
    }

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "app_language"
    private const val KEY = "tag"
}
