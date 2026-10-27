// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.net

import java.io.IOException

/**
 * Unencrypted http:// is for servers at home only (the owner's rule):
 * everything on the internet goes over https://, so nobody on the same
 * Wi-Fi, a café's or a hotel's, can read a sign-in or what is played.
 *
 * At home is localhost, the private and link-local address ranges, the
 * 100.64/10 range Tailscale and other VPNs hand out, IPv6 unique-local and
 * link-local addresses, and the names a home network gives (`.local`, `.lan`,
 * `.home.arpa`, `.internal`). Android's own network security config cannot
 * say "any private address", so the app's HTTP stacks ask here: the API
 * client (NetworkModule), the player's downloads, the image loader and the
 * pack importer.
 *
 * And a Subsonic plain password (`p=`, servers that refuse tokens) never goes
 * over http://, home or not.
 */
object CleartextPolicy {

    /** Why [url] may not be fetched, or null when it may. */
    fun refusal(url: String): String? {
        val scheme = url.substringBefore("://", "").lowercase()
        if (scheme != "http") return null
        val host = hostOf(url)
        if (!isAtHome(host)) return "http:// is only for servers on your own network; this one needs https://"
        if (sendsPassword(url)) return "this server's sign-in is the password itself, so it needs https://"
        return null
    }

    /** Throws when [url] may not be fetched. */
    fun require(url: String) {
        refusal(url)?.let { throw CleartextRefused(it) }
    }

    /**
     * For audio and pictures: an internet radio station or a cover over plain
     * http:// carries no sign-in, and many stations have no https at all, so
     * only an address that carries one (a server's token, password, key or
     * signature) is held to [refusal]'s rule.
     */
    fun mediaRefusal(url: String): String? =
        // An https:// address is never refused, so most never get its query read.
        if (url.startsWith("http://", ignoreCase = true) && carriesSignIn(url)) refusal(url) else null

    fun requireForMedia(url: String) {
        mediaRefusal(url)?.let { throw CleartextRefused(it) }
    }

    class CleartextRefused(message: String) : IOException(message)

    /** The host of [url], lowercase, without brackets, user info or port. */
    fun hostOf(url: String): String {
        val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val host = if (authority.startsWith("[")) authority.substringAfter('[').substringBefore(']') else authority.substringBefore(':')
        return host.lowercase().trimEnd('.')
    }

    fun isAtHome(name: String): Boolean {
        val host = name.lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (HOME_SUFFIXES.any { host.endsWith(it) }) return true
        ipv4(host)?.let { (a, b) ->
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (':' in host) {
            val h = host.substringBefore('%')
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
                h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
        }
        return false
    }

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.length in 1..3 } ?: return null }
        return n[0] to n[1]
    }

    private fun queryKeys(url: String): List<String> =
        url.substringAfter('?', "").substringBefore('#').split('&').filter { '=' in it && it.length > it.indexOf('=') + 1 }
            .map { it.substringBefore('=').lowercase() }

    private fun sendsPassword(url: String): Boolean = "p" in queryKeys(url)

    private fun carriesSignIn(url: String): Boolean = queryKeys(url).any { it in SIGN_IN_KEYS }

    private val SIGN_IN_KEYS = setOf("t", "s", "p", "u", "token", "access_token", "key", "apikey", "api_key", "sig", "signature", "password")

    private val HOME_SUFFIXES = listOf(".local", ".lan", ".home.arpa", ".internal", ".home")
}
