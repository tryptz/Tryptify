package tf.monochrome.android.audio.eq.shizuku

import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

/**
 * The helper that keeps the Shizuku system-wide equalizer playing when
 * Shizuku itself is gone.
 *
 * Shizuku is started over wireless debugging, and on some phones it is
 * stopped when wireless debugging is (leaving the Wi-Fi it was started on
 * is enough), its services with it. This is not one of its services. It is
 * a plain process the service starts ([SystemPeqUserService.launchDetached]:
 * `app_process` on the app's own APK, in a session of its own), with the
 * shell's identity it was started under, and it answers the app on a
 * loopback socket ([SystemPeqWire]) where a service answers over Shizuku's
 * binder. Whether a phone that stops Shizuku lets this live is the phone's
 * to say: it is an experiment (the owner asked for it, 2026-11-24), and the
 * app falls back to the service, and to the standard effect, wherever it
 * does not.
 *
 * It leaves by itself: told to ([SystemPeqWire.CMD_EXIT]), or a while after
 * the app's last connection closed with none since
 * ([SystemPeqWire.GRACE_MS]), the equalizer stopped first either way. So an
 * app that was killed, or uninstalled, does not leave other apps' audio
 * running through a correction nobody can turn off.
 *
 * Under GPL-3.0 with [SystemPeqEngine].
 */
@Keep
object SystemPeqDaemon {
    private const val TAG = "SystemPeqDaemon"

    @Volatile private var lastClientGone = 0L
    @Volatile private var clientHere = false

    @JvmStatic
    fun main(args: Array<String>) {
        val token = args.getOrNull(0)
        val basePort = args.getOrNull(1)?.toIntOrNull()
        if (token == null || basePort == null || !SystemPeqWire.isToken(token) ||
            Build.VERSION.SDK_INT < ShizukuPeqState.MIN_SDK
        ) {
            exitProcess(2)
        }
        leaveCallersGroup()
        // The engine's audio policy is called back on the main looper.
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val server = bind(basePort)
        if (server == null) {
            Log.w(TAG, "no free port from $basePort")
            exitProcess(3)
        }
        val engine = SystemPeqEngine()
        lastClientGone = System.currentTimeMillis()
        Thread({ serve(server, token, engine) }, "system-peq-serve").start()
        Thread({ watchForNobody(engine) }, "system-peq-grace").apply { isDaemon = true }.start()
        Log.i(TAG, "answering on ${server.localPort}")
        Looper.loop()
    }

    private fun bind(basePort: Int): ServerSocket? {
        for (port in basePort until basePort + SystemPeqWire.PORTS) {
            runCatching { return ServerSocket(port, 2, InetAddress.getLoopbackAddress()) }
        }
        return null
    }

    /**
     * Shizuku's server moves itself out of adb's control group so that adb
     * being restarted does not take it along; the same is tried here, for
     * the same reason, wherever the shell may write. It may nowhere, and
     * that is not an error.
     */
    private fun leaveCallersGroup() {
        val pid = android.os.Process.myPid().toString()
        for (path in arrayOf("/acct/cgroup.procs", "/dev/cg2_bpf/cgroup.procs", "/sys/fs/cgroup/cgroup.procs")) {
            if (runCatching { File(path).writeText(pid) }.isSuccess) {
                Log.i(TAG, "moved to $path")
                return
            }
        }
    }

    /** One caller at a time: the app has one connection, and holds it. */
    private fun serve(server: ServerSocket, token: String, engine: SystemPeqEngine) {
        while (true) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            runCatching { talk(socket, token, engine) }
            runCatching { socket.close() }
            if (clientHere) {
                clientHere = false
                lastClientGone = System.currentTimeMillis()
            }
        }
    }

    private fun talk(socket: Socket, token: String, engine: SystemPeqEngine) {
        socket.soTimeout = SystemPeqWire.HELLO_MS
        val input = DataInputStream(socket.getInputStream().buffered())
        val out = DataOutputStream(socket.getOutputStream().buffered())
        if (!SystemPeqWire.readHello(input, token)) return
        out.writeBoolean(true)
        out.flush()
        clientHere = true
        // From here the app may say nothing for as long as nothing changes.
        socket.soTimeout = 0
        while (true) {
            when (input.readByte()) {
                SystemPeqWire.CMD_START -> {
                    val uid = input.readInt()
                    val left = SystemPeqWire.readFloats(input) ?: return
                    val right = SystemPeqWire.readFloats(input) ?: return
                    val preamp = input.readFloat()
                    out.writeBoolean(engine.start(uid, left, right, preamp))
                }
                SystemPeqWire.CMD_CURVE -> {
                    val left = SystemPeqWire.readFloats(input) ?: return
                    val right = SystemPeqWire.readFloats(input) ?: return
                    engine.setCurve(left, right, input.readFloat())
                    out.writeBoolean(true)
                }
                SystemPeqWire.CMD_STOP -> {
                    engine.stop()
                    out.writeBoolean(true)
                }
                SystemPeqWire.CMD_STATUS -> out.writeUTF(engine.status())
                SystemPeqWire.CMD_EXIT -> {
                    engine.stop()
                    out.writeBoolean(true)
                    out.flush()
                    exitProcess(0)
                }
                else -> return
            }
            out.flush()
        }
    }

    private fun watchForNobody(engine: SystemPeqEngine) {
        while (true) {
            Thread.sleep(1000)
            if (!clientHere && System.currentTimeMillis() - lastClientGone > SystemPeqWire.GRACE_MS) {
                Log.i(TAG, "nobody for ${SystemPeqWire.GRACE_MS} ms: leaving")
                engine.stop()
                exitProcess(0)
            }
        }
    }
}
