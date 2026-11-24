package tf.monochrome.android.audio.eq.shizuku

import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The Shizuku system-wide equalizer as Shizuku starts it: a service in a
 * process of Shizuku's own, with the shell's identity, which the app reaches
 * over [ISystemPeqService]. The equalizer is [SystemPeqEngine].
 *
 * This process goes when Shizuku goes, and Shizuku goes when wireless
 * debugging does on some phones (the owner's among them, 2026-11-24). So its
 * first job is to start a helper that does not ([launchDetached],
 * [SystemPeqDaemon]); the app then talks to the helper and lets this service
 * go. Where no helper can be started or reached, the engine runs here, and
 * stops with Shizuku as it always did.
 *
 * Under GPL-3.0 with the engine, after Quve's `AudioLoopbackUserService`.
 */
@Keep
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class SystemPeqUserService : ISystemPeqService.Stub() {

    private val lock = Any()
    private val engine = SystemPeqEngine()
    private val destroyed = AtomicBoolean(false)

    // Under [lock].
    private var clientToken: IBinder? = null
    private var clientDeath: IBinder.DeathRecipient? = null

    override fun start(appUid: Int, left: FloatArray?, right: FloatArray?, preampDb: Float): Boolean =
        engine.start(appUid, left ?: FloatArray(0), right ?: FloatArray(0), preampDb)

    override fun setCurve(left: FloatArray?, right: FloatArray?, preampDb: Float) {
        engine.setCurve(left ?: FloatArray(0), right ?: FloatArray(0), preampDb)
    }

    override fun stop() = engine.stop()

    override fun status(): String = engine.status()

    override fun attach(clientToken: IBinder?) {
        synchronized(lock) {
            runCatching { clientDeath?.let { this.clientToken?.unlinkToDeath(it, 0) } }
            this.clientToken = clientToken
            clientDeath = null
            if (clientToken == null) return
            val death = IBinder.DeathRecipient { destroy() }
            // Dead already: the same as dying now.
            if (runCatching { clientToken.linkToDeath(death, 0) }.isFailure) {
                destroy()
            } else {
                clientDeath = death
            }
        }
    }

    /**
     * Starts [SystemPeqDaemon] from the app's own APK at [apkPath], in a
     * session of its own so that it is no child of this process's fate. It
     * answers on the first free port from [basePort] to who knows [token].
     * True when the command was run; whether a helper is there is found out
     * by reaching it.
     */
    override fun launchDetached(apkPath: String?, token: String?, basePort: Int): Boolean {
        if (apkPath.isNullOrEmpty() || token.isNullOrEmpty()) return false
        // What goes into a shell command is held to what it may be: a path
        // of an installed APK and a hex token.
        if (!SystemPeqWire.isApkPath(apkPath) || !SystemPeqWire.isToken(token)) return false
        val command = SystemPeqWire.launchCommand(apkPath, token, basePort)
        return runCatching {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", command)).waitFor()
            true
        }.onFailure { Log.w(TAG, "could not start the helper", it) }.getOrDefault(false)
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) return
        engine.stop()
        exitProcess(0)
    }

    companion object {
        private const val TAG = "SystemPeqService"

        /** What a loopback capture is handed over as. */
        const val SAMPLE_RATE = SystemPeqEngine.SAMPLE_RATE
        const val STATUS_RUNNING = SystemPeqEngine.STATUS_RUNNING
        const val STATUS_STOPPED = SystemPeqEngine.STATUS_STOPPED
        const val STATUS_ERROR = SystemPeqEngine.STATUS_ERROR
        const val ERROR_ROOT = SystemPeqEngine.ERROR_ROOT
    }
}
