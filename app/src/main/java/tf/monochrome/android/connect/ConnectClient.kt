// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.connect

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Where Tryptify Connect stands, for the player's Devices panel. */
data class ConnectState(
    val status: Status = Status.OFF,
    val devices: List<ConnectDevice> = emptyList(),
    val now: ConnectNow? = null,
) {
    enum class Status { OFF, CONNECTING, ON, SIGNED_OUT }
}

/**
 * This phone's one connection to Tryptify Connect (server/connect): which of
 * the listener's devices are online, and what plays on which.
 *
 * Light on purpose. It is open only while [ConnectController] wants it (the
 * app on screen, or music playing, when the phone is awake anyway); a
 * message goes out only when something changes; the keep-alive is a "ping"
 * every 50 s that Cloudflare answers without waking the room.
 */
@Singleton
class ConnectClient @Inject constructor(
    private val httpClient: HttpClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<ConnectIncoming>(extraBufferCapacity = 32)

    /** Commands, releases, takeovers and refusals: each to be acted on once. */
    val events: SharedFlow<ConnectIncoming> = _events.asSharedFlow()

    private var job: Job? = null

    @Volatile private var send: (suspend (String) -> Unit)? = null
    @Volatile private var offsetMs = 0L

    /** The server's clock, as this phone reckons it. */
    fun serverNowMs(): Long = System.currentTimeMillis() + offsetMs

    val connected: Boolean get() = job?.isActive == true

    /**
     * Opens the connection as [deviceId] called [name], signing in with [token]
     * (called again before each try, so a refreshed session is used), and keeps
     * it open, coming back after a drop, until [close].
     */
    fun open(deviceId: String, name: String, token: suspend () -> String?, onOpen: suspend () -> Unit) {
        if (job?.isActive == true) return
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                val t = runCatching { token() }.getOrNull()
                if (t == null) {
                    _state.value = ConnectState(status = ConnectState.Status.SIGNED_OUT)
                    return@launch
                }
                _state.update { it.copy(status = ConnectState.Status.CONNECTING) }
                val welcomed = runCatching { session(deviceId, name, t, onOpen) }
                    .onFailure { Log.w(TAG, "connect: ${it.message}") }
                    .getOrDefault(false)
                if (!isActive) break
                _state.update { it.copy(status = ConnectState.Status.CONNECTING, devices = emptyList()) }
                attempt = if (welcomed) 0 else (attempt + 1).coerceAtMost(6)
                delay(1000L shl attempt) // 1 s after a drop, up to a minute while it cannot get through
            }
        }
    }

    fun close() {
        job?.cancel()
        job = null
        send = null
        _state.update { it.copy(status = ConnectState.Status.OFF, devices = emptyList()) }
    }

    private suspend fun session(deviceId: String, name: String, token: String, onOpen: suspend () -> Unit): Boolean {
        val q = "device=${enc(deviceId)}&name=${enc(name)}&kind=android"
        val ws = httpClient.webSocketSession("$URL?$q") { header("Authorization", "Bearer $token") }
        var welcomed = false
        val pinger = scope.launch {
            while (isActive) {
                delay(PING_MS)
                runCatching { ws.send(Frame.Text("ping")) }
            }
        }
        try {
            send = { text -> ws.send(Frame.Text(text)) }
            ws.incoming.consumeEach { frame ->
                if (frame !is Frame.Text) return@consumeEach
                val msg = ConnectProtocol.parse(frame.readText()) ?: return@consumeEach
                msg.serverTime?.let { offsetMs = it - System.currentTimeMillis() }
                when (msg.type) {
                    "welcome" -> {
                        welcomed = true
                        _state.value = ConnectState(ConnectState.Status.ON, msg.devices.orEmpty(), msg.now)
                        onOpen()
                    }
                    "devices" -> _state.update { it.copy(devices = msg.devices.orEmpty()) }
                    "now" -> _state.update { it.copy(now = msg.now) }
                    else -> _events.tryEmit(msg)
                }
            }
        } finally {
            send = null
            pinger.cancel()
            runCatching { ws.close() }
        }
        return welcomed
    }

    /** Sends [text] if connected; false when it could not go. */
    suspend fun tell(text: String): Boolean {
        val s = send ?: return false
        return runCatching { s(text) }.isSuccess
    }

    /** This phone's own state, as the others will see it, kept locally too. */
    fun remember(now: ConnectNow) = _state.update { it.copy(now = now) }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val TAG = "TryptifyConnect"
        const val URL = "wss://connect.tryptify.com/v1/connect"
        private const val PING_MS = 50_000L
    }
}
