package tf.monochrome.android.audio.eq.shizuku

import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * What the app and the helper ([SystemPeqDaemon]) say to each other, over a
 * socket on the phone's own loopback address. Not a binder, because the
 * helper has to be reachable with Shizuku gone, and Shizuku is where a
 * binder to it would come from.
 *
 * Any app on the phone can open that port, so nothing is done before the
 * caller has said the token: a secret the app makes, hands to the helper
 * through Shizuku when it starts it, and keeps in its own storage. Without
 * it a connection is closed.
 *
 * A connection is also the app's sign of life: the helper leaves a while
 * after the last one closes ([GRACE_MS]), so an app that died does not
 * leave other apps' audio running through a correction nobody can turn off.
 *
 * Pure Kotlin: the two ends are held to each other on the JVM
 * ([SystemPeqWireTest]).
 */
internal object SystemPeqWire {
    /** First of the [PORTS] ports the helper may answer on; only on 127.0.0.1. */
    const val BASE_PORT = 47211
    const val PORTS = 5

    /** "TPQ1": what a caller says first, so a stray connection is known at once. */
    const val MAGIC = 0x54505131

    const val CMD_START: Byte = 1
    const val CMD_CURVE: Byte = 2
    const val CMD_STOP: Byte = 3
    const val CMD_STATUS: Byte = 4
    const val CMD_EXIT: Byte = 5

    /** How long the helper waits for the app to come back before it leaves. */
    const val GRACE_MS = 30_000L

    /** How long a caller has to say who it is. */
    const val HELLO_MS = 2_000

    /** The class the helper is started as; kept by name in `proguard-rules.pro`. */
    const val DAEMON_CLASS = "tf.monochrome.android.audio.eq.shizuku.SystemPeqDaemon"
    const val DAEMON_NAME = "tryptify_system_peq"

    private const val MAX_FLOATS = SystemPeqDsp.MAX_BANDS * SystemPeqDsp.FIELDS
    private val TOKEN = Regex("[0-9a-f]{32}")
    private val APK_PATH = Regex("/[A-Za-z0-9_./=~+-]+\\.apk")

    /** A token as the app makes them: 32 hex digits. */
    fun isToken(s: String): Boolean = TOKEN.matches(s)

    /** A path an installed APK can have, and nothing a shell would read as more than a path. */
    fun isApkPath(s: String): Boolean = APK_PATH.matches(s) && !s.contains("..")

    /**
     * The shell command that starts the helper from the app's APK, in a
     * session of its own where the phone has `setsid`, with nothing of the
     * caller's left open. Both arguments must have passed [isApkPath] and
     * [isToken]: they are put into a shell's command line.
     */
    fun launchCommand(apkPath: String, token: String, basePort: Int): String {
        require(isApkPath(apkPath)) { "not an APK path" }
        require(isToken(token)) { "not a token" }
        return "S=; command -v setsid >/dev/null 2>&1 && S=setsid; " +
            "CLASSPATH='$apkPath' \$S app_process /system/bin --nice-name=$DAEMON_NAME " +
            "$DAEMON_CLASS $token $basePort </dev/null >/dev/null 2>&1 &"
    }

    fun writeHello(out: DataOutputStream, token: String) {
        out.writeInt(MAGIC)
        out.writeUTF(token)
        out.flush()
    }

    /** Whether the caller said the magic and [token]. Compared in full, whatever differs. */
    fun readHello(input: DataInputStream, token: String): Boolean {
        if (input.readInt() != MAGIC) return false
        val said = input.readUTF()
        var same = said.length == token.length
        for (i in 0 until minOf(said.length, token.length)) if (said[i] != token[i]) same = false
        return same
    }

    fun writeFloats(out: DataOutputStream, values: FloatArray) {
        out.writeInt(values.size)
        for (v in values) out.writeFloat(v)
    }

    /** An array as [writeFloats] wrote it; null for a length no curve has. */
    fun readFloats(input: DataInputStream): FloatArray? {
        val n = input.readInt()
        if (n < 0 || n > MAX_FLOATS) return null
        return FloatArray(n) { input.readFloat() }
    }
}
