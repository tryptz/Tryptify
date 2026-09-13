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
import tf.monochrome.android.data.local.coil.AudioFileCoverFetcher
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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

    @Inject
    lateinit var systemAudioEqController: tf.monochrome.android.audio.eq.SystemAudioEqController

    @Inject
    lateinit var settingsSyncCoordinator: tf.monochrome.android.data.sync.SettingsSyncCoordinator

    @Inject
    lateinit var libraryRestoreCoordinator: tf.monochrome.android.data.sync.LibraryRestoreCoordinator

    @Inject
    lateinit var artworkStoreMigration: tf.monochrome.android.data.local.tags.ArtworkStoreMigration

    @Inject
    lateinit var playbackStateRepository: tf.monochrome.android.player.PlaybackStateRepository

    // Providers, not `lateinit var`. Every field above is built by Hilt during
    // Application construction, on the startup path, before onCreate returns —
    // eleven singletons and their graphs. These three exist only to be warmed
    // on a background coroutine, so field-injecting them would move their
    // construction cost onto the very path the warm-up is meant to clear. The
    // Provider is resolved inside the launch below instead.
    @Inject
    lateinit var genreGraph: javax.inject.Provider<tf.monochrome.android.data.repository.GenreGraphRepository>

    @Inject
    lateinit var projectMAssets: javax.inject.Provider<tf.monochrome.android.visualizer.ProjectMAssetInstaller>

    @Inject
    lateinit var preferencesProvider: javax.inject.Provider<tf.monochrome.android.data.preferences.PreferencesManager>

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
            .components {
                // Pulls embedded album art straight from audio files when
                // the cached extraction missed (or got evicted), so local
                // and downloaded tracks always show their cover in the
                // player even before MediaScanner / our scanner have
                // populated the artwork cache.
                add(AudioFileCoverFetcher.Factory(this@MonochromeApp))
            }
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
        // System-wide AutoEQ: when the toggle is on, attach a global output-mix
        // effect that applies the AutoEQ correction to all device audio. No-op
        // (and self-releasing) while the toggle is off or the device rejects it.
        systemAudioEqController.start()
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
        // Auto-save app settings to the user's Supabase row: pull on sign-in,
        // then push (debounced) whenever an allow-listed setting changes.
        settingsSyncCoordinator.start(appScope)
        // Settings have repaired themselves on sign-in for a while; the library
        // did not, and an account whose playlists were intact in the cloud
        // still showed an empty Playlists tab. Now both do.
        libraryRestoreCoordinator.start(appScope)
        // The queue, track and second the app was last closed on. Paused:
        // nothing resolves or plays until the user presses play.
        playbackStateRepository.start(appScope)
        // Covers lived in cacheDir/artwork, which Android empties whenever it
        // likes — and did, and a launch that found them gone answered with a
        // full rescan. That is why the app seemed to reindex itself on every
        // start. This carries an existing install's art into filesDir, once.
        appScope.launch {
            runCatching { artworkStoreMigration.migrateIfNeeded() }
        }
        warmFirstUseCaches()
    }

    /**
     * Work that is otherwise paid for by whoever opens a screen first.
     *
     * After the first frame, never before it. A splash held until the caches
     * are warm does not remove the wait, it moves it to every cold start —
     * including the ones that only wanted the pause button — which is why the
     * platform guidance is not to hold a splash for background loading. The
     * splash here stays gated on one boolean.
     *
     * Warmed selectively, not exhaustively. `genre_history.json` (1.9 MB) and
     * `world_radio.json` are bigger than either of these and are deliberately
     * left alone: both are already suspending and cached, and neither is
     * touched until a screen is opened on purpose. Pre-empting those would be
     * battery spent on something the listener may never look at, which is the
     * failure mode of "warm everything" and the reason this is a list rather
     * than a loop.
     *
     * Nothing here is load-bearing. Every one of these paths still works
     * unwarmed, on its caller's thread, exactly as it did before.
     */
    private fun warmFirstUseCaches() {
        // ~280 KB of JSON behind a blocking `by lazy` that the first search
        // resolves — see GenreGraphRepository.warm.
        appScope.launch {
            runCatching { genreGraph.get().warm() }
        }
        // Unzips the whole preset pack and writes a catalog the first time the
        // visualizer opens. Gated on the toggle: doing it for someone who has
        // turned the engine off is pure waste, and it defaults to on, so the
        // people who benefit are not made to ask.
        appScope.launch {
            runCatching {
                if (preferencesProvider.get().visualizerEngineEnabled.first()) {
                    projectMAssets.get().ensureInstalled()
                }
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
