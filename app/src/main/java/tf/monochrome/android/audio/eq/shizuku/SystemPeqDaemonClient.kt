package tf.monochrome.android.audio.eq.shizuku

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The app's connection to the helper ([SystemPeqDaemon]). One question, one
 * answer, from one thread at a time ([ShizukuPeqController]'s). Every call
 * says whether the helper answered: one that did not is gone, and the
 * connection is closed.
 */
internal class SystemPeqDaemonClient private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val out: DataOutputStream,
) {
    /** Starts the equalizer; null when the helper is gone, else whether it started. */
    fun start(appUid: Int, left: FloatArray, right: FloatArray, preampDb: Float): Boolean? = ask(START_MS) {
        out.writeByte(SystemPeqWire.CMD_START.toInt())
        out.writeInt(appUid)
        SystemPeqWire.writeFloats(out, left)
        SystemPeqWire.writeFloats(out, right)
        out.writeFloat(preampDb)
        out.flush()
        input.readBoolean()
    }

    fun setCurve(left: FloatArray, right: FloatArray, preampDb: Float): Boolean? = ask(ANSWER_MS) {
        out.writeByte(SystemPeqWire.CMD_CURVE.toInt())
        SystemPeqWire.writeFloats(out, left)
        SystemPeqWire.writeFloats(out, right)
        out.writeFloat(preampDb)
        out.flush()
        input.readBoolean()
    }

    fun stop(): Boolean? = ask(START_MS) {
        out.writeByte(SystemPeqWire.CMD_STOP.toInt())
        out.flush()
        input.readBoolean()
    }

    /** The engine's own word; null when the helper is gone. */
    fun status(): String? = ask(ANSWER_MS) {
        out.writeByte(SystemPeqWire.CMD_STATUS.toInt())
        out.flush()
        input.readUTF()
    }

    /** Tells the helper to stop the equalizer and leave. */
    fun exit() {
        ask(START_MS) {
            out.writeByte(SystemPeqWire.CMD_EXIT.toInt())
            out.flush()
            input.readBoolean()
        }
        close()
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun <T> ask(timeoutMs: Int, question: () -> T): T? = runCatching {
        socket.soTimeout = timeoutMs
        question()
    }.onFailure { close() }.getOrNull()

    companion object {
        private const val CONNECT_MS = 300
        private const val ANSWER_MS = 3_000

        /** Starting registers a policy and opens two streams. */
        private const val START_MS = 8_000

        /**
         * The helper that knows [token], on whichever of its ports it took;
         * null when there is none. Quick where nothing listens: a loopback
         * port nobody has is refused at once.
         */
        fun connect(token: String, basePort: Int = SystemPeqWire.BASE_PORT): SystemPeqDaemonClient? {
            for (port in basePort until basePort + SystemPeqWire.PORTS) {
                val socket = Socket()
                val client = runCatching {
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), CONNECT_MS)
                    socket.soTimeout = ANSWER_MS
                    val input = DataInputStream(socket.getInputStream().buffered())
                    val out = DataOutputStream(socket.getOutputStream().buffered())
                    SystemPeqWire.writeHello(out, token)
                    if (input.readBoolean()) SystemPeqDaemonClient(socket, input, out) else null
                }.getOrNull()
                if (client != null) return client
                runCatching { socket.close() }
            }
            return null
        }
    }
}
