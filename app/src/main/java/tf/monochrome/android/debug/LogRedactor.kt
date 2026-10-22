// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.debug

import tf.monochrome.android.BuildConfig

/**
 * Takes sign-ins out of log text before it is kept, shown, exported or
 * written to a crash file in Downloads: OAuth tokens and codes, a Subsonic
 * server's token, salt or password (`t`, `s`, `p`, `u`), API keys and
 * signatures in addresses, `Bearer` tokens, anything shaped like a JWT, and
 * the built-in rooms server's secret path (its host stays). The rest of the
 * line is kept, so a log still says what happened.
 */
object LogRedactor {
    private const val HIDDEN = "<redacted>"

    /** Query and fragment values that are a sign-in, whatever the address. */
    private val param = Regex(
        """([?&#;](?:access_token|refresh_token|id_token|provider_token|provider_refresh_token|code|token|apikey|api_key|key|""" +
            """password|passwd|secret|sig|signature|arl|t|s|p|u)=)[^&#\s"'<>]+""",
        RegexOption.IGNORE_CASE,
    )
    private val bearer = Regex("""(?i)\b(bearer\s+)[A-Za-z0-9._~+/=-]{8,}""")
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{4,}""")

    /** [secrets] in full, each to what may be shown of it (a server's host for its secret address). */
    fun redact(text: String, secrets: Map<String, String> = builtInSecrets): String {
        if (text.isEmpty()) return text
        var out = text
        for ((secret, shown) in secrets) if (secret.isNotEmpty()) out = out.replace(secret, shown)
        out = jwt.replace(out, HIDDEN)
        out = bearer.replace(out) { it.groupValues[1] + HIDDEN }
        out = param.replace(out) { it.groupValues[1] + HIDDEN }
        return out
    }

    /** The build's rooms server: its address carries a secret path, so only the host is ever shown. */
    private val builtInSecrets: Map<String, String> by lazy {
        val rooms = BuildConfig.BUILT_IN_ROOMS_SERVER.trimEnd('/')
        if (rooms.isEmpty()) emptyMap() else {
            val host = rooms.substringAfter("://").substringBefore('/')
            mapOf(rooms to "https://$host/$HIDDEN")
        }
    }
}
