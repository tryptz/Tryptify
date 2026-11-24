package tf.monochrome.android.audio.eq.shizuku

import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * The Shizuku system-wide equalizer itself: other apps' media audio taken
 * out of Android's mixer, played through the listener's parametric
 * correction ([SystemPeqDsp]) and put back.
 *
 * It runs in a process with the shell's identity, never in the app: either
 * the service Shizuku starts ([SystemPeqUserService]) or the helper that
 * service starts to outlive it ([SystemPeqDaemon]). The shell may do what an
 * app may not: register an audio policy that loops other apps' playback
 * back to a recorder. The method is Quve's (github.com/nanbeimerco/Quve,
 * GPL-3.0), written here after its `AudioLoopbackUserService`; this file is
 * under GPL-3.0 with it. Every call into Android below is a hidden one,
 * made by reflection, and may stop existing in any Android release: each is
 * tried, and a failure leaves other apps' audio as it was and says so in
 * [status].
 *
 * The one rule that matters: **a policy that is registered with nothing
 * reading it is silence in every other app.** So whatever goes wrong, at
 * start or hours later, ends in [releaseLocked], which unregisters it.
 * Android does the same when the process dies.
 *
 * What is taken: playback with the usage media, game or unknown, from every
 * app but this process (or its own output would be taken again, a loop)
 * and the app itself (its own equalizer corrects its own music, exactly
 * and at the song's own rate and depth). Calls, alarms and notifications
 * are not touched. Android hands the capture over as 48 kHz 16-bit stereo,
 * whatever the other app played.
 *
 * Started with root, the process is uid 0, and a rule cannot leave uid 0
 * out, so the loop would feed itself: it is refused there.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class SystemPeqEngine {

    private val lock = Any()
    private val dsp = SystemPeqDsp(SAMPLE_RATE)

    // Under [lock].
    private var policy: Any? = null
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var thread: Thread? = null
    private var shellContext: Context? = null
    private var audioManager: AudioManager? = null

    @Volatile private var running = false
    @Volatile private var stopping = false
    @Volatile private var lastError: String? = null

    init {
        // Killed by a signal, the policy must still come off.
        runCatching { Runtime.getRuntime().addShutdownHook(Thread { synchronized(lock) { releaseLocked() } }) }
    }

    /**
     * Takes other apps' media audio and plays it through the curve given;
     * [appUid] is left out of what is taken. False when it could not start,
     * and [status] then says why.
     */
    fun start(appUid: Int, left: FloatArray, right: FloatArray, preampDb: Float): Boolean =
        synchronized(lock) {
            stopLocked()
            lastError = null
            val myUid = Process.myUid()
            if (myUid == 0) {
                lastError = ERROR_ROOT
                return false
            }
            dsp.setCurve(left, right, preampDb)
            try {
                val ctx = shellContext()
                val audioMix = registerLocked(ctx, intArrayOf(myUid, appUid))
                val rec = recordFor(ctx, audioMix)
                if (rec.state != AudioRecord.STATE_INITIALIZED) error("the capture did not open")
                record = rec
                val out = outputTrack(ctx)
                if (out.state != AudioTrack.STATE_INITIALIZED) error("the output did not open")
                track = out
                stopping = false
                running = true
                thread = Thread({ loop(rec, out) }, "tryptify-system-peq").also { it.start() }
                true
            } catch (t: Throwable) {
                val cause = t.cause ?: t
                Log.w(TAG, "could not start", cause)
                lastError = "${cause.javaClass.simpleName}: ${cause.message}"
                releaseLocked()
                false
            }
        }

    /** The curve to play from now on. */
    fun setCurve(left: FloatArray, right: FloatArray, preampDb: Float) {
        dsp.setCurve(left, right, preampDb)
    }

    /** Gives other apps' audio back to Android as it was. */
    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    /** [STATUS_RUNNING], [STATUS_STOPPED], or [STATUS_ERROR] and why. */
    fun status(): String = when {
        running -> STATUS_RUNNING
        lastError != null -> "$STATUS_ERROR$lastError"
        else -> STATUS_STOPPED
    }

    // ── The loop ────────────────────────────────────────────────────────────

    private fun loop(rec: AudioRecord, out: AudioTrack) {
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
        val block = ShortArray(BLOCK_FRAMES * 2)
        try {
            rec.startRecording()
            out.play()
            while (running) {
                val n = rec.read(block, 0, block.size)
                if (n < 0) {
                    if (!stopping) lastError = "capture error $n"
                    break
                }
                if (n == 0) continue
                dsp.process(block, n / 2)
                var written = 0
                while (written < n && running) {
                    val w = out.write(block, written, n - written)
                    if (w < 0) {
                        if (!stopping) lastError = "output error $w"
                        running = false
                        break
                    }
                    written += w
                }
            }
        } catch (t: Throwable) {
            if (!stopping) {
                Log.w(TAG, "loop ended", t)
                lastError = "${t.javaClass.simpleName}: ${t.message}"
            }
        } finally {
            running = false
            // Ended by itself: nothing reads the capture any more, so the
            // policy comes off now. Ended by stop(), stop() is doing it and
            // holds the lock while it waits for this thread.
            if (!stopping) synchronized(lock) { releaseLocked() }
        }
    }

    private fun stopLocked() {
        stopping = true
        running = false
        // A read in progress returns when the recorder stops.
        runCatching { record?.stop() }
        val t = thread
        thread = null
        if (t != null && t !== Thread.currentThread()) runCatching { t.join(JOIN_MS) }
        releaseLocked()
    }

    /** Everything let go, the policy last and whatever else failed. */
    private fun releaseLocked() {
        running = false
        runCatching { record?.stop() }
        runCatching { record?.release() }
        record = null
        runCatching { track?.stop() }
        runCatching { track?.release() }
        track = null
        val registered = policy
        policy = null
        if (registered != null) {
            runCatching {
                val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                val manager = audioManager
                if (manager != null) {
                    AudioManager::class.java.getMethod("unregisterAudioPolicy", policyClass).invoke(manager, registered)
                } else {
                    AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass)
                        .apply { isAccessible = true }
                        .invoke(null, registered)
                }
            }.onFailure { Log.w(TAG, "could not unregister the policy", it.cause ?: it) }
        }
    }

    // ── Android's hidden side ───────────────────────────────────────────────

    /** A context with the shell's name, which is who this process is. */
    private fun shellContext(): Context {
        shellContext?.let { return it }
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
        val system = activityThread.getMethod("getSystemContext").invoke(current) as Context
        val shell = ShellContext(system.createPackageContext(SHELL_PACKAGE, 0))
        audioManager = shell.getSystemService(AudioManager::class.java)
        shellContext = shell
        return shell
    }

    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        override fun createAttributionContext(attributionTag: String?): Context = this
        override fun getAttributionSource(): AttributionSource {
            val builder = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setPid(Process.myPid())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) builder.setDeviceId(0)
            return builder.build()
        }
        override fun getDeviceId(): Int = 0
    }

    /**
     * Registers the policy: media, game and unknown playback, but for
     * [excludedUids], looped back. Returns the mix, which names the capture.
     */
    private fun registerLocked(ctx: Context, excludedUids: IntArray): Any {
        val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addRule = ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
        for (usage in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)) {
            addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), RULE_MATCH_ATTRIBUTE_USAGE)
        }
        val exclude = ruleBuilderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
        for (uid in excludedUids.filter { it > 0 }.distinct()) {
            exclude.invoke(ruleBuilder, RULE_MATCH_UID, Integer.valueOf(uid))
        }
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
        val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)!!

        val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(ctx)
        policyBuilderClass.getMethod("setLooper", Looper::class.java).invoke(policyBuilder, Looper.getMainLooper())
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val built = policyBuilderClass.getMethod("build").invoke(policyBuilder)!!

        val register = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyClass)
        register.isAccessible = true
        val result = register.invoke(null, built) as Int
        if (result != AUDIO_MANAGER_SUCCESS) error("the policy was refused ($result)")
        policy = built
        return mix
    }

    /** The recorder the policy's capture is read from. */
    private fun recordFor(ctx: Context, mix: Any): AudioRecord {
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        // The policy's own way first.
        runCatching {
            val direct = policyClass.getMethod("createAudioRecordSink", mixClass).invoke(policy, mix) as? AudioRecord
            if (direct != null && direct.state == AudioRecord.STATE_INITIALIZED) return direct
            runCatching { direct?.release() }
        }.onFailure { Log.d(TAG, "no direct sink: ${it.cause ?: it}") }

        // Else a recorder on the remote submix the mix was registered at.
        val registration = mixClass.getMethod("getRegistration").invoke(mix) as String
        val fixedVolume = AudioRecord::class.java.getField("SUBMIX_FIXED_VOLUME").get(null) as String
        val attributes = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attributes, MediaRecorder.AudioSource.REMOTE_SUBMIX)
        val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        addTag.invoke(attributes, "addr=$registration")
        addTag.invoke(attributes, fixedVolume)

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val smallest = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder()
            .setContext(ctx)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(smallest, BUFFER_BYTES))
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(builder, attributes.build())
        return builder.build()
    }

    /** Where the corrected audio is played: as media, so the media volume is its volume. */
    private fun outputTrack(ctx: Context): AudioTrack {
        val smallest = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(smallest, BUFFER_BYTES))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) builder.setContext(ctx)
        return builder.build()
    }

    companion object {
        private const val TAG = "SystemPeqEngine"
        private const val SHELL_PACKAGE = "com.android.shell"

        /** What a loopback capture is handed over as. */
        const val SAMPLE_RATE = 48000

        /** 10 ms a block. */
        private const val BLOCK_FRAMES = 480
        private const val BUFFER_BYTES = BLOCK_FRAMES * 2 * 2 * 4
        private const val JOIN_MS = 1200L

        // android.media.audiopolicy's own numbers; they are not public, and
        // have been these since the classes were written.
        private const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
        private const val RULE_MATCH_UID = 0x1 shl 2
        private const val ROUTE_FLAG_LOOP_BACK = 0x1 shl 1
        private const val AUDIO_MANAGER_SUCCESS = 0

        const val STATUS_RUNNING = "running"
        const val STATUS_STOPPED = "stopped"
        const val STATUS_ERROR = "error: "

        /** [status]'s reason when Shizuku was started with root. */
        const val ERROR_ROOT = "root"
    }
}
