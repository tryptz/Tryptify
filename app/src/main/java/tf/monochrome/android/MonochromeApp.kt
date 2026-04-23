package tf.monochrome.android

import android.app.Application
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
import javax.inject.Inject

@HiltAndroidApp
class MonochromeApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

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
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .crossfade(true)
            .build()

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
