package tf.monochrome.android

import android.app.Application
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
import tf.monochrome.android.performance.DeviceCapabilities
import tf.monochrome.android.performance.PerformanceProfile
import javax.inject.Inject

@HiltAndroidApp
class MonochromeApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    companion object {
        /**
         * Resolved performance envelope for this process. Set exactly once by the
         * companion-object `init` block below; read by `newImageLoader` here and by
         * `PerformanceModule` for Hilt consumers. `@Volatile` for visibility across
         * the audio / main / worker threads that all run off `Dispatchers.Default`.
         */
        @Volatile
        lateinit var profile: PerformanceProfile
            private set

        init {
            // Detection must run before Dispatchers.Default wakes up — the Kotlin
            // scheduler reads `kotlinx.coroutines.scheduler.*.pool.size` exactly
            // once on first access. `companion object { init {} }` fires when the
            // Application class is loaded, which precedes onCreate() and any
            // coroutine dispatch.
            val (tier, snapshot) = DeviceCapabilities.detect()
            val picked = PerformanceProfile.forTier(tier)
            profile = picked
            System.setProperty("kotlinx.coroutines.scheduler.core.pool.size", picked.corePoolSize.toString())
            System.setProperty("kotlinx.coroutines.scheduler.max.pool.size", picked.maxPoolSize.toString())
            Log.i(
                "MonoPerf",
                "tier=$tier cores=${snapshot.cores} big=${snapshot.bigCores} " +
                    "maxFreq=${snapshot.maxFreqMhz}MHz ram=${snapshot.ramMb}MB " +
                    "pool=${picked.corePoolSize}/${picked.maxPoolSize} " +
                    "spectrumFps=${picked.spectrumFps} haze=${picked.allowHazeBlur}"
            )
        }
    }

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var authManager: SupabaseAuthManager

    @Inject
    lateinit var deviceRegistry: DeviceRegistry

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
        // Restore auth on app start, then register this device against whichever
        // user is signed in. The collector re-fires on sign-in / sign-out.
        appScope.launch {
            authManager.initialize()
            authManager.userProfile
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { profile ->
                    if (profile != null) deviceRegistry.registerCurrentDevice()
                    else deviceRegistry.clearOnSignOut()
                }
        }
    }
}
