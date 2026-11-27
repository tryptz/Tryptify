package io.github.chronosauros.contour.core.native

import java.io.IOException

/** SnowSky request boundary (Melody, Tiny A: [FiioConfig.snowskyExchange]), shared by Android and the timed packet replay. No mutation retries.
 * The 11.10.2026 log reads an AA selection echo 324 ms after TX (its actual arrival time is unknown).
 * Allow 500 ms, including short empty USB reads, and discard late input before the next request.
 * Captured replies carry a device counter rather than the zero host field; quiet input is required,
 * not proof of correlation. Recovery cannot revive firmware that remains silent.
 */
class FiioMelodyExchange(
    private val inputPayloadSize: Int,
    private val guard: () -> Unit,
    private val nowMs: () -> Long,
    private val send: (Int, ByteArray) -> Unit,
    private val receive: (Int) -> ByteArray?,
    private val name: String = "Melody",
) {
    companion object {
        fun appliesTo(config: FiioConfig?) = config?.snowskyExchange == true
        fun name(config: FiioConfig) = config.shortName ?: config.productName
    }
    // New connections also quarantine input left behind by a failed previous operation.
    private var needsRecovery = true

    private fun drain() {
        val quietMs = if (needsRecovery) 400L else 5L
        val deadline = nowMs() + 1000L
        var quietSince = nowMs()
        var dropped = 0
        while (true) {
            guard()
            val now = nowMs()
            if (now - quietSince >= quietMs) return
            if (now >= deadline || dropped >= 16)
                throw IOException("$name input did not settle; active slot unknown. Reconnect $name and READ DAC; not verified")
            val raw = receive(minOf(50L, quietMs - (now - quietSince), deadline - now).toInt().coerceAtLeast(1))
            guard()
            if (raw != null) { dropped++; quietSince = nowMs() }
        }
    }

    fun request(id: Int, payload: ByteArray, matches: (ByteArray) -> Boolean): ByteArray {
        var complete = false
        try {
            drain()
            guard(); send(id, payload); guard()
            val deadline = nowMs() + 500L
            // Both time and packet budgets are finite, including a stream of unrelated/invalid reports.
            var received = 0
            while (received < 64) {
                guard()
                val left = deadline - nowMs()
                if (left <= 0) throw timeout()
                val raw = receive(minOf(left, 50L).toInt())
                guard()
                if (raw != null) received++
                if (raw != null && raw.isNotEmpty() && (raw[0].toInt() and 255) == id) {
                    require(raw.size == inputPayloadSize + 1) { "Malformed $name input report length" }
                    val reply = raw.copyOfRange(1, raw.size)
                    if (matches(reply)) {
                        complete = true
                        return reply
                    }
                }
            }
            throw timeout()
        } finally { needsRecovery = !complete }
    }

    private fun timeout() = IOException("Native request timed out or returned invalid state; $name active slot unknown. Retry READ DAC; if still silent, unplug/reconnect $name (not verified)")
}
