// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

/**
 * The rooms server every install has, for Trypt Mania's multiplayer and
 * listening parties (`BuildConfig.BUILT_IN_ROOMS_SERVER`, from
 * local.properties or the environment, never the repository; a build without
 * one has none). It is always in the list under Settings › Connections and
 * cannot be removed: [ensure] puts it back on every read and every save. It
 * can still be moved and checked again.
 *
 * The TryptMania Parser chart server is never built in (the owner's rule).
 * Builds before did put it in the list, so [withoutRetired] takes that entry
 * out once, recognised by its host's SHA-256 alone
 * (`BuildConfig.RETIRED_CPU_HOST_SHA256`); one the listener adds again later
 * stays.
 */
object BuiltInServer {

    /**
     * [servers] with [builtIn] in it; null when it is there already, or the
     * build has none. An entry for the same host (one added by hand, or
     * under an older secret) becomes it, in its place; otherwise it goes last.
     */
    fun ensure(servers: List<ApiServer>, builtIn: String, services: Set<ApiService> = SERVICES): List<ApiServer>? {
        val url = ApiServers.normalizeUrl(builtIn) ?: return null
        if (servers.any { it.url == url }) return null
        val host = hostOf(url)
        val at = servers.indexOfFirst { hostOf(it.url) == host }
        if (at < 0) return servers + entry(url, services)
        val kept = servers[at]
        return servers.filterIndexed { i, s -> i == at || hostOf(s.url) != host }
            .map { if (it === kept) it.copy(url = url, services = it.services + services) else it }
    }

    /** [servers] with the build's rooms server ([roomsServer]) in it; null when nothing changed, or the build ships none. */
    fun ensureAll(servers: List<ApiServer>, roomsServer: String): List<ApiServer>? = ensure(servers, roomsServer, ROOMS_SERVICES)

    /**
     * [servers] without the retired chart server, the entries whose host's
     * SHA-256 is [retiredHostSha256]; null when there is none, or no hash.
     */
    fun withoutRetired(servers: List<ApiServer>, retiredHostSha256: String): List<ApiServer>? {
        if (retiredHostSha256.isBlank()) return null
        val kept = servers.filterNot { sha256(hostOf(it.url)) == retiredHostSha256.lowercase() }
        return kept.takeIf { it.size != servers.size }
    }

    private fun sha256(s: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Whether [url] is one of the build's servers, which the list does not offer to remove. */
    fun isBuiltIn(url: String, vararg builtIn: String): Boolean =
        builtIn.any { own -> ApiServers.normalizeUrl(own)?.let { it == url } == true }

    /**
     * The entry as added: a CPU stem and lyrics server, as it says of itself,
     * so Slow finds it before it has been asked, and Fast tries it last; a
     * rooms server says nothing of a device.
     */
    fun entry(url: String, services: Set<ApiService> = SERVICES): ApiServer =
        ApiServer(url = url, services = services, stemDevice = if (ApiService.STEMS in services) "cpu" else null)

    /** What the build's chart server serves: it separates for charts and times lyrics. */
    private val SERVICES = setOf(ApiService.STEMS, ApiService.LYRICS)

    /** What the build's rooms server serves. */
    private val ROOMS_SERVICES = setOf(ApiService.MULTIPLAYER)

    /** What the list shows for it: its host, never its secret path. */
    fun shownAs(url: String): String = hostOf(url)

    private fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').lowercase()
}
