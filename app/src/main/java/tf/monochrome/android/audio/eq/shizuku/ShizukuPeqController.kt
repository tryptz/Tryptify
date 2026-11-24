package tf.monochrome.android.audio.eq.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import rikka.shizuku.Shizuku
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.audio.eq.SystemEqCurve
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ToneControls
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The app's end of the Shizuku system-wide equalizer: an option under
 * System-wide AutoEQ that plays other apps through the listener's real
 * parametric correction ([SystemPeqUserService], in Shizuku's process)
 * where the standard effect can only sample it at 31 bands.
 *
 * It is asked for by two switches, system-wide and the option, and is on
 * only while there is something to play. Everything else here is Shizuku
 * being a separate app the listener has to install, start after every
 * restart of the phone, and allow: [state] says which of those is in the
 * way, and until it reads [ShizukuPeqState.ACTIVE] the standard system-wide
 * effect goes on playing. That holds for Shizuku stopping mid-song as well
 * (leaving the Wi-Fi it was started on stops it on some phones): the
 * standard effect takes over at once, and this mode comes back by itself
 * when Shizuku is running again. So does it for the first start: the
 * standard system-wide effect goes on playing ([tf.monochrome.android.audio.eq.SystemAudioEqController]
 * stands down only for ACTIVE). It never falls silent for want of Shizuku.
 *
 * Shizuku's service goes when Shizuku goes, and Shizuku goes with wireless
 * debugging on some phones. So the service is asked first to start a helper
 * that is no part of Shizuku ([SystemPeqDaemon]), and the equalizer runs
 * there, reached over a loopback socket ([SystemPeqDaemonClient]). While
 * the helper answers, the mode is on whatever has become of Shizuku. Where
 * a helper cannot be started or reached, or dies twice, the equalizer runs
 * in the service as it did before there was one. A helper found still
 * running (the app was restarted, Shizuku has gone) is taken up again by
 * its token.
 *
 * Both die with the app: the service by its token
 * ([SystemPeqUserService.attach]), the helper a while after the app's
 * connection closes. [tf.monochrome.android.audio.eq.SystemEqService] is
 * what keeps the app.
 *
 * Nothing here runs unless the option is on: Shizuku is not asked whether
 * it is there, and no listener is registered.
 */
@Singleton
class ShizukuPeqController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: PreferencesManager,
) {
    // One thread for everything said to Shizuku and the service, in order.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(ShizukuPeqState.OFF)
    val state: StateFlow<ShizukuPeqState> = _state.asStateFlow()

    /** Whether other apps are playing through the parametric correction now. */
    val active: StateFlow<Boolean> = _state.map { it == ShizukuPeqState.ACTIVE }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private var started = false
    private var listening = false

    // What is wanted, and what there is. The scope's thread only.
    private var curve: SystemEqCurve.Parametric? = null
    private var optionOn = false
    private var service: ISystemPeqService? = null
    private var connection: ServiceConnection? = null
    private var binding = false
    private var failed = false
    private var sentCurve: SystemEqCurve.Parametric? = null
    private var watch: Job? = null
    private val clientToken = Binder()

    // The helper that outlives Shizuku, while it answers; and how often one
    // has been lost or would not play, after which the service is used.
    private var daemon: SystemPeqDaemonClient? = null
    private var daemonFailures = 0
    private var looked = false
    private val daemonToken: String by lazy { loadToken() }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, SystemPeqUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("system_peq")
            .debuggable(BuildConfig.DEBUG)
            // A new build of the app is a new service: Shizuku replaces a
            // running one of another version.
            .version(BuildConfig.VERSION_CODE * 100 + SERVICE_PROTOCOL)
    }

    // Shizuku coming and going is not the service failing. It is stopped by
    // the phone on some when the network changes (it is started over
    // wireless debugging, which Android ties to the Wi-Fi it was started
    // on), and the service goes with it. Which of "Shizuku died" and "the
    // service disconnected" is heard first is not fixed: taken in the other
    // order, the disconnect was kept as a failure, and with Shizuku running
    // again the mode stayed at "would not start" until a setting was changed.
    // So both clear it: Shizuku back means try again, by itself.
    private val binderReceived = Shizuku.OnBinderReceivedListener {
        scope.launch {
            failed = false
            sync()
        }
    }
    private val binderDead = Shizuku.OnBinderDeadListener {
        scope.launch {
            dropService()
            failed = false
            sync()
        }
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> scope.launch { sync() } }

    /** Begin watching the settings. Idempotent. */
    @OptIn(FlowPreview::class)
    fun start() {
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            combine(
                preferences.systemWideAutoEqEnabled,
                preferences.systemWidePeqShizuku,
                preferences.eqBandsJson,
                preferences.eqBandsRJson,
                preferences.eqStereoMode,
                preferences.eqPreamp,
                preferences.systemToneControls,
                preferences.eqAutoPreamp,
            ) { v ->
                val on = v[0] as Boolean && v[1] as Boolean
                Wanted(
                    option = on,
                    curve = if (!on) null else SystemEqCurve.parametric(
                        correctionL = decode(v[2] as String?),
                        correctionR = decode(v[3] as String?),
                        stereo = v[4] as Boolean,
                        tone = v[6] as ToneControls,
                        manualPreampDb = (v[5] as Double).toFloat(),
                        autoPreamp = v[7] as Boolean,
                    ),
                )
            }
                .distinctUntilChanged()
                .debounce(70L)
                .collectLatest {
                    optionOn = it.option
                    curve = it.curve
                    // A change of settings is the listener trying again.
                    failed = false
                    daemonFailures = 0
                    sync()
                }
        }
    }

    private data class Wanted(val option: Boolean, val curve: SystemEqCurve.Parametric?)

    /**
     * Asks Shizuku to let the app use it: its own dialog. For the settings
     * row, when [state] is [ShizukuPeqState.NEEDS_PERMISSION].
     */
    fun requestPermission() {
        scope.launch {
            runCatching { Shizuku.requestPermission(PERMISSION_REQUEST) }
                .onFailure { Log.w(TAG, "could not ask Shizuku: ${it.message}") }
        }
    }

    /** Looks again: for the settings screen coming to the front after a trip to the Shizuku app. */
    fun refresh() {
        scope.launch {
            failed = false
            daemonFailures = 0
            sync()
        }
    }

    // ── The scope's thread from here on ─────────────────────────────────────

    private fun sync() {
        if (!optionOn) {
            // Not asked for: nothing of Shizuku is touched, and what was is
            // let go. A helper too, one from before the app was restarted
            // included: it must not go on correcting with the switch off.
            if (looked || daemon != null) leaveDaemon()
            stopService()
            stopListening()
            _state.value = ShizukuPeqState.OFF
            return
        }
        if (Build.VERSION.SDK_INT < ShizukuPeqState.MIN_SDK) {
            _state.value = ShizukuPeqState.UNSUPPORTED
            return
        }
        val wanted = curve
        // A helper answers whatever has become of Shizuku: first.
        if (playThroughDaemon(wanted)) {
            // With it playing, Shizuku's service has nothing left to do.
            releaseBoundService()
            _state.value = if (wanted == null) ShizukuPeqState.OFF else ShizukuPeqState.ACTIVE
            return
        }
        listen()
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permitted = alive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        if (!alive || !permitted) {
            dropService()
        } else if (wanted == null) {
            // Allowed, with nothing to play: other apps are left alone.
            stopService()
        } else {
            val bound = service
            if (bound == null) {
                bind()
            } else if (wanted != sentCurve) {
                send(bound, wanted)
            }
        }
        publish(alive, permitted)
    }

    /**
     * Plays [wanted] through the helper, if there is one: the one in hand,
     * or one found running by its token. True when the helper has it (or,
     * with nothing wanted, has been told to leave); false when there is no
     * helper, and Shizuku's service is the way.
     */
    private fun playThroughDaemon(wanted: SystemEqCurve.Parametric?): Boolean {
        if (daemon == null && (wanted != null || !looked)) {
            // Looked for once a start of the app at least: it may be there
            // from before, with Shizuku gone since.
            daemon = SystemPeqDaemonClient.connect(daemonToken)
            looked = true
        }
        val helper = daemon ?: return false
        if (wanted == null) {
            // Nothing to play: other apps are left alone, and it leaves.
            helper.exit()
            daemon = null
            sentCurve = null
            return true
        }
        val playing = helper.status() == SystemPeqUserService.STATUS_RUNNING
        val ok = when {
            // Found playing (the app was restarted): the curve, not a new start.
            playing && wanted == sentCurve -> true
            playing -> helper.setCurve(wanted.left, wanted.right, wanted.preampDb)
            else -> {
                // Never two at once: a service that began playing while the
                // helper was still coming up gives the audio back first.
                runCatching { service?.stop() }
                helper.start(android.os.Process.myUid(), wanted.left, wanted.right, wanted.preampDb)
            }
        }
        if (ok == true) {
            sentCurve = wanted
            failed = false
            watchService()
            return true
        }
        // Gone, or it would not play: said, counted, and the service is the way.
        Log.w(TAG, "the helper ${if (ok == null) "is gone" else "would not play: ${helper.status()}"}")
        if (ok == false) helper.exit() else helper.close()
        daemon = null
        sentCurve = null
        daemonFailures++
        return false
    }

    /** Tells a helper, in hand or found by its token, to stop and leave. */
    private fun leaveDaemon() {
        val helper = daemon ?: SystemPeqDaemonClient.connect(daemonToken)
        looked = false
        daemon = null
        helper?.exit()
    }

    /**
     * Has [bound] start a helper and waits for it to answer. True when one
     * is in hand. Not tried again once one has been lost or refused twice:
     * a phone that will not keep a helper is not asked for one in a loop.
     */
    private suspend fun startDaemon(bound: ISystemPeqService): Boolean {
        if (daemonFailures >= MAX_DAEMON_FAILURES) return false
        val apk = runCatching { context.applicationInfo.sourceDir }.getOrNull() ?: return false
        val launched = runCatching { bound.launchDetached(apk, daemonToken, SystemPeqWire.BASE_PORT) }
            .onFailure { Log.w(TAG, "could not ask for a helper: ${it.message}") }
            .getOrDefault(false)
        if (!launched) {
            daemonFailures++
            return false
        }
        // A new process, a runtime to start: a few tries.
        repeat(DAEMON_TRIES) {
            delay(DAEMON_TRY_MS)
            SystemPeqDaemonClient.connect(daemonToken)?.let {
                daemon = it
                looked = true
                return true
            }
        }
        Log.w(TAG, "the helper did not answer")
        daemonFailures++
        return false
    }

    /** The helper's secret, made once and kept in the app's own storage. */
    private fun loadToken(): String {
        val file = java.io.File(context.noBackupFilesDir, TOKEN_FILE)
        runCatching { file.readText().trim() }.getOrNull()?.takeIf { SystemPeqWire.isToken(it) }?.let { return it }
        val bytes = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        runCatching { file.writeText(token) }
        return token
    }

    private fun publish(alive: Boolean, permitted: Boolean) {
        val running = service != null && sentCurve != null && !failed
        _state.value = if (curve == null && alive && permitted) {
            // On, allowed, and flat: nothing is in anyone's path.
            ShizukuPeqState.OFF
        } else {
            ShizukuPeqState.resolve(
                wanted = true,
                sdk = Build.VERSION.SDK_INT,
                installed = installed(),
                binderAlive = alive,
                permitted = permitted,
                serviceRunning = running,
                failed = failed,
            )
        }
    }

    private fun installed(): Boolean =
        runCatching { context.packageManager.getPackageInfo(ShizukuSetup.PACKAGE, 0) }.isSuccess

    private fun bind() {
        if (binding || failed) return
        binding = true
        lateinit var conn: ServiceConnection
        conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                scope.launch {
                    binding = false
                    if (connection !== conn) return@launch
                    val stub = binder?.takeIf { it.pingBinder() }?.let { ISystemPeqService.Stub.asInterface(it) }
                    if (stub == null) {
                        failed = true
                    } else {
                        service = stub
                        runCatching { stub.attach(clientToken) }
                        val wanted = curve
                        // The helper first, which Shizuku stopping does not
                        // stop; sync() plays through it and lets this
                        // service go. Without one, the service plays.
                        if (wanted != null && !startDaemon(stub)) send(stub, wanted, starting = true)
                    }
                    sync()
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                scope.launch {
                    if (connection !== conn) return@launch
                    service = null
                    sentCurve = null
                    binding = false
                    // It went by itself: said once, not started again in a
                    // loop. Unless Shizuku went with it, which is Shizuku
                    // stopping and not the service failing.
                    failed = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
                    sync()
                }
            }
        }
        connection = conn
        val ok = runCatching { Shizuku.bindUserService(args, conn) }
            .onFailure { Log.w(TAG, "could not bind the service: ${it.message}") }
            .isSuccess
        if (!ok) {
            binding = false
            connection = null
            failed = true
        }
    }

    /** Starts the service on [wanted], or hands a running one the new curve. */
    private fun send(bound: ISystemPeqService, wanted: SystemEqCurve.Parametric, starting: Boolean = sentCurve == null) {
        val ok = runCatching {
            if (starting) {
                bound.start(android.os.Process.myUid(), wanted.left, wanted.right, wanted.preampDb)
            } else {
                bound.setCurve(wanted.left, wanted.right, wanted.preampDb)
                true
            }
        }.onFailure { Log.w(TAG, "the service did not answer: ${it.message}") }.getOrDefault(false)
        if (ok) {
            sentCurve = wanted
            failed = false
            watchService()
        } else {
            val why = runCatching { bound.status() }.getOrNull()
            Log.w(TAG, "the service would not start: $why")
            sentCurve = null
            failed = true
            stopService(keepFailed = true)
        }
    }

    /** The engine's own word, every few seconds while it plays: it may stop by itself. */
    private fun watchService() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (true) {
                delay(WATCH_MS)
                if (sentCurve == null) return@launch
                val helper = daemon
                val bound = service
                val status = when {
                    helper != null -> helper.status()
                    bound != null -> runCatching { bound.status() }.getOrNull()
                    else -> return@launch
                }
                if (status == SystemPeqUserService.STATUS_RUNNING) continue
                Log.w(TAG, "the equalizer stopped: $status")
                if (helper != null) {
                    // The helper is gone or has stopped: counted, and
                    // Shizuku is asked again if it is there.
                    if (status == null) helper.close() else helper.exit()
                    daemon = null
                    sentCurve = null
                    daemonFailures++
                } else {
                    failed = true
                    stopService(keepFailed = true)
                }
                sync()
                return@launch
            }
        }
    }

    /** Stops the service and lets it go; other apps' audio is Android's again. */
    private fun stopService(keepFailed: Boolean = false) {
        val bound = service
        val conn = connection
        service = null
        connection = null
        sentCurve = null
        binding = false
        if (!keepFailed) failed = false
        runCatching { bound?.stop() }
        if (conn != null) runCatching { Shizuku.unbindUserService(args, conn, true) }
    }

    /**
     * Lets Shizuku's service go while the helper plays: its engine stopped
     * (it may have begun before the helper answered) and its process ended.
     * What the helper was sent is kept.
     */
    private fun releaseBoundService() {
        val bound = service
        val conn = connection
        if (bound == null && conn == null) return
        service = null
        connection = null
        binding = false
        runCatching { bound?.stop() }
        if (conn != null) runCatching { Shizuku.unbindUserService(args, conn, true) }
    }

    /** Shizuku is gone: the service went with it, and there is nobody to tell. */
    private fun dropService() {
        service = null
        connection = null
        sentCurve = null
        binding = false
    }

    private fun listen() {
        if (listening) return
        listening = runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permissionResult)
        }.onFailure { Log.w(TAG, "could not watch Shizuku: ${it.message}") }.isSuccess
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permissionResult)
        }
    }

    private fun decode(bandsJson: String?): List<EqBand> =
        if (bandsJson.isNullOrEmpty()) emptyList()
        else runCatching { json.decodeFromString<List<EqBand>>(bandsJson) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "ShizukuPeq"
        const val PERMISSION_REQUEST = 4201

        /** Raised when [ISystemPeqService] changes, so a service of the old one is replaced. */
        const val SERVICE_PROTOCOL = 2
        const val WATCH_MS = 4000L

        const val TOKEN_FILE = "system_peq_token"
        const val MAX_DAEMON_FAILURES = 2
        const val DAEMON_TRIES = 8
        const val DAEMON_TRY_MS = 400L
    }
}
