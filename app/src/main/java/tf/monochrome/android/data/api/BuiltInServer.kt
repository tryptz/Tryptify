// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.data.api

/**
 * The servers a build can ship: a TryptMania Parser on a CPU (Railway),
 * Trypt Mania's Slow mode, so charting works with nothing set up.
 *
 * Its address, secret path and all, comes from the build
 * (`BuildConfig.BUILT_IN_CPU_SERVER`, from local.properties), never from the
 * repository, and only when the build asks for it with
 * `-Ptryptmania.withCpuServer=true`; by default a build has none. The rooms server for Trypt
 * Mania's multiplayer (`BUILT_IN_ROOMS_SERVER`) ships the same way. It is always in the list under
 * Settings › Connections and cannot be removed: [ensure] puts it back on
 * every read and every save. It can still be moved and checked again.
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

    /**
     * [servers] with every server the build ships: the chart server
     * ([cpuServer]) and the rooms server ([roomsServer]); null when nothing
     * changed, or the build ships none.
     */
    fun ensureAll(servers: List<ApiServer>, cpuServer: String, roomsServer: String): List<ApiServer>? {
        val withCpu = ensure(servers, cpuServer)
        val withRooms = ensure(withCpu ?: servers, roomsServer, ROOMS_SERVICES)
        return withRooms ?: withCpu
    }

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
