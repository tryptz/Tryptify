package tf.monochrome.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.device.DeviceRegistry
import tf.monochrome.android.debug.CrashLogger
import tf.monochrome.android.debug.DebugLogCollector
import tf.monochrome.android.performance.DeviceCapabilities
import tf.monochrome.android.performance.PerformanceProfile
import javax.inject.Inject

@HiltAndroidApp
class MonochromeApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    companion object {
        /**
         * Resolved performance envelope for this process. Initialized at class-load
         * time (i.e. before `Application.onCreate` and before `Dispatchers.Default`
         * wakes) so the scheduler pool properties below can be set from it.
         *
         * `val` is safe here — Kotlin guarantees eager initialization of top-level
         * and companion-object `val`s with a static initializer (safe publication
         * across threads), and we never reassign after first-boot detection.
         */
        @JvmStatic
        val profile: PerformanceProfile =
            PerformanceProfile.forTier(DeviceCapabilities.detect().first)

        init {
            // Detection must run before Dispatchers.Default wakes up — the Kotlin
            // scheduler reads `kotlinx.coroutines.scheduler.*.pool.size` exactly
            // once on first access. `companion object { init {} }` fires when the
            // Application class is loaded, which precedes onCreate() and any
            // coroutine dispatch.
            System.setProperty("kotlinx.coroutines.scheduler.core.pool.size", profile.corePoolSize.toString())
            System.setProperty("kotlinx.coroutines.scheduler.max.pool.size", profile.maxPoolSize.toString())
            val snapshot = DeviceCapabilities.detect().second
            Log.i(
                "MonoPerf",
                "tier=${profile.tier} cores=${snapshot.cores} big=${snapshot.bigCores} " +
                    "maxFreq=${snapshot.maxFreqMhz}MHz ram=${snapshot.ramMb}MB " +
                    "pool=${profile.corePoolSize}/${profile.maxPoolSize} " +
                    "spectrumFps=${profile.spectrumFps} haze=${profile.allowHazeBlur}"
            )
        }
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var authManager: SupabaseAuthManager

    @Inject
    lateinit var deviceRegistry: DeviceRegistry

    @Inject
    lateinit var debugLogCollector: DebugLogCollector

    @Inject
    lateinit var crashLogger: CrashLogger

    @Inject
    lateinit var usbExclusiveController: tf.monochrome.android.audio.usb.UsbExclusiveController

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Single ImageLoader for the whole app. Without this, every Coil call site
     * spins up a default loader and the in-memory + on-disk caches don't survive
     * navigation, so identical artwork gets re-decoded on every screen change.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val p = profile
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, p.coilMemoryPercent)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(p.coilDiskBytes)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        // Catch uncaught exceptions and dump the in-memory log + stack trace
        // to Downloads/monotrypt-crash-<timestamp>.log before chaining to the
        // system handler. Installed before anything else so a crash anywhere
        // in onCreate is captured.
        crashLogger.install()
        // Pre-create the Now Playing channel so the first foreground-service
        // notification from Media3 has a named channel instead of showing up
        // under "default" in Android Settings → App → Notifications. Media3
        // auto-creates the channel lazily otherwise, but with a generic name.
        registerPlaybackNotificationChannel()
        // Start the in-app logcat collector as early as possible so the "View
        // debug log" screen has a buffered history from (almost) app start.
        debugLogCollector.start()
        // Warm up libmonochrome_dsp on a background thread so the linker work
        // (a few-MB dlopen + relocations) doesn't land on the UI thread when
        // Hilt instantiates MixBusProcessor / InflatorEffect / CompressorEffect
        // singletons. Audio processing happens on its own thread later, which
        // will block on this load if the warm-up hasn't completed yet — but in
        // the typical case the linker is done long before the first audio
        // buffer arrives.
        appScope.launch {
            tf.monochrome.android.audio.dsp.DspNativeLoader.ensureLoaded()
        }
        // libusb gets the same off-thread warm-up so the dlopen + libusb_init
        // don't land on the audio thread the first time the user toggles
        // exclusive USB output.
        appScope.launch {
            runCatching { tf.monochrome.android.audio.usb.UsbNativeLoader.ensureLoaded() }
        }
        // Wire the Exclusive USB DAC toggle to actual driver lifecycle
        // and start publishing real status to Settings UI. Without this,
        // the toggle is a persisted boolean with no observable effect.
        usbExclusiveController.start()
        // Restore auth on app start, then register this device against whichever
        // user is signed in. The collector re-fires on sign-in / sign-out.
        appScope.launch {
            authManager.initialize()
            authManager.userProfile
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { user ->
                    if (user != null) deviceRegistry.registerCurrentDevice()
                    else deviceRegistry.clearOnSignOut()
                }
        }
    }

    private fun registerPlaybackNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // Match Media3's default channel id so our pre-registered channel is
        // the one used by MediaNotification.Provider — no custom provider
        // needed for the one-time name/description customization.
        val channel = NotificationChannel(
            PLAYBACK_CHANNEL_ID,
            "Now Playing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Playback controls shown in the notification shade and on the lock screen."
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }
}

private const val PLAYBACK_CHANNEL_ID = "default_channel_id"
