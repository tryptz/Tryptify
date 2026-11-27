package io.github.chronosauros.contour.core.native

/**
 * The result of a write of a family that has its own session next to [NativeSession] (KT Micro, native Moondrop, WalkPlay).
 * [verified]: the read-back equals what was written. [pending]: the DAC drops off the bus after the save, so the proof is a
 * read after it comes back (the caller reads again and compares; [readback] is null then). [reason]: why not verified, or a note.
 */
data class WriteOutcome<S>(val verified: Boolean, val pending: Boolean, val reason: String?, val readback: S?)

/** The guarded port calls every family session uses; the same rules as [NativeSession]'s private helpers. */
internal class NativeIo(val port: NativePort) {
    fun <T> guarded(op: () -> T): T { port.guard(); return op().also { port.guard() } }
    fun pause(ms: Long) { if (ms > 0) guarded { port.pause(ms) } }
    fun send(id: Int, payload: ByteArray) { guarded { port.send(id, NativeHidReports.Kind.OUTPUT, payload) } }
    fun request(id: Int, payload: ByteArray, check: (ByteArray) -> Boolean): ByteArray = guarded {
        port.request(id, payload, check).also { require(check(it)) { "Uncorrelated native reply" } }
    }

    /** The message [NativePort.request] throws for a reply that did not come (HidTransport.requestNative on the phone). */
    fun isReplyTimeout(e: java.io.IOException) = e.message?.startsWith("Native request timed out") == true

    /** Read-only queries are idempotent, so a silent DAC may be asked again. Never wrap a mutation in this. */
    fun <T> retryTimeouts(attempts: Int = 8, gapMs: Long = 400, op: () -> T): T {
        var last: java.io.IOException? = null
        repeat(attempts) { i ->
            try { return op() } catch (e: java.io.IOException) {
                if (!isReplyTimeout(e)) throw e
                last = e
                if (i < attempts - 1) pause(gapMs)
            }
        }
        throw requireNotNull(last)
    }
}
