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
 * effect goes on playing ([tf.monochrome.android.audio.eq.SystemAudioEqController]
 * stands down only for ACTIVE). It never falls silent for want of Shizuku.
 *
 * The service dies with the app (its token, [SystemPeqUserService.attach]);
 * [tf.monochrome.android.audio.eq.SystemEqService] is what keeps the app.
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

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, SystemPeqUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("system_peq")
            .debuggable(BuildConfig.DEBUG)
            // A new build of the app is a new service: Shizuku replaces a
            // running one of another version.
            .version(BuildConfig.VERSION_CODE * 100 + SERVICE_PROTOCOL)
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { scope.launch { sync() } }
    private val binderDead = Shizuku.OnBinderDeadListener {
        scope.launch {
            dropService()
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
            sync()
        }
    }

    // ── The scope's thread from here on ─────────────────────────────────────

    private fun sync() {
        if (!optionOn) {
            // Not asked for: nothing of Shizuku is touched, and what was is let go.
            stopService()
            stopListening()
            _state.value = ShizukuPeqState.OFF
            return
        }
        if (Build.VERSION.SDK_INT < ShizukuPeqState.MIN_SDK) {
            _state.value = ShizukuPeqState.UNSUPPORTED
            return
        }
        listen()
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        val permitted = alive && runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        val wanted = curve
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
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

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
                        curve?.let { send(stub, it, starting = true) }
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
                    // It went by itself: said once, not started again in a loop.
                    failed = true
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

    /** The service's own word, every few seconds while it plays: it may stop by itself. */
    private fun watchService() {
        if (watch?.isActive == true) return
        watch = scope.launch {
            while (true) {
                delay(WATCH_MS)
                val bound = service ?: return@launch
                if (sentCurve == null) return@launch
                val status = runCatching { bound.status() }.getOrNull()
                if (status != SystemPeqUserService.STATUS_RUNNING) {
                    Log.w(TAG, "the service stopped: $status")
                    failed = true
                    stopService(keepFailed = true)
                    sync()
                    return@launch
                }
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
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST = 4201

        /** Raised when [ISystemPeqService] changes, so a service of the old one is replaced. */
        const val SERVICE_PROTOCOL = 1
        const val WATCH_MS = 4000L
    }
}
