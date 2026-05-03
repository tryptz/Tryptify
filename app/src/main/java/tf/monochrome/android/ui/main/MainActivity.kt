package tf.monochrome.android.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.media.AudioManager
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import eightbitlab.com.blurview.BlurTarget
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.ui.navigation.MonochromeNavHost
import tf.monochrome.android.ui.theme.MonochromeTheme
import tf.monochrome.android.ui.theme.rememberDynamicPalette
import java.io.File
import javax.inject.Inject
import tf.monochrome.android.audio.eq.FrequencyTargets
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.performance.PerformanceProfile

val LocalBlurTarget = compositionLocalOf<BlurTarget?> { null }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var supabaseAuthManager: SupabaseAuthManager
    @Inject lateinit var queueManager: QueueManager
    @Inject lateinit var performanceProfile: PerformanceProfile
    @Inject lateinit var libusbDriver: tf.monochrome.android.audio.usb.LibusbUacDriver
    @Inject lateinit var bypassVolumeController: tf.monochrome.android.audio.usb.BypassVolumeController

    // Registered-for-result launcher for POST_NOTIFICATIONS. Fires a one-shot
    // system prompt on Android 13+; the result doesn't block the UI either way
    // — if the user denies we just lose the playback notification, playback
    // itself still works.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Make the system route hardware volume keys to STREAM_MUSIC
        // by default. We re-route them to BypassVolumeController only
        // when the libusb iso pump is actively streaming (see
        // dispatchKeyEvent below); otherwise the system handles them
        // normally and AudioFlinger volume changes apply to the
        // delegate sink path as expected.
        volumeControlStream = AudioManager.STREAM_MUSIC

        maybeRequestNotificationPermission()

        // Restore existing Supabase session on startup
        lifecycleScope.launch {
            supabaseAuthManager.initialize()
        }

        FrequencyTargets.init(applicationContext)

        // Force maximum available refresh rate (e.g. 120Hz)
        window.attributes = window.attributes.apply {
            val displayModes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.supportedModes ?: emptyArray()
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.supportedModes
            }
            
            displayModes.maxByOrNull { it.refreshRate }?.let { bestMode ->
                preferredDisplayModeId = bestMode.modeId
            }
        }

        // Create BlurTarget to wrap Compose content for BlurView backdrop blur
        val blurTarget = BlurTarget(this)
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        blurTarget.addView(composeView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(blurTarget)

        composeView.setContent {
            val themeName by preferences.theme.collectAsState(initial = "monochrome_dark")
            val fontScale by preferences.fontScale.collectAsState(initial = 1.0f)
            val customFontPath by preferences.customFontUri.collectAsState(initial = null)
            val dynamicColorsEnabled by preferences.dynamicColors.collectAsState(initial = false)
            val currentTrack by queueManager.currentTrack.collectAsState()
            val dynamicPalette by rememberDynamicPalette(
                coverUrl = currentTrack?.coverUrl,
                enabled = dynamicColorsEnabled
            )

            val customFontFamily = remember(customFontPath) {
                customFontPath?.let { path ->
                    val file = File(path)
                    if (file.exists()) {
                        try {
                            FontFamily(
                                Font(file, FontWeight.Light),
                                Font(file, FontWeight.Normal),
                                Font(file, FontWeight.Medium),
                                Font(file, FontWeight.SemiBold),
                                Font(file, FontWeight.Bold)
                            )
                        } catch (_: Exception) { null }
                    } else null
                }
            }

            CompositionLocalProvider(
                LocalBlurTarget provides blurTarget,
                LocalPerformanceProfile provides performanceProfile,
            ) {
                MonochromeTheme(
                    themeName = themeName,
                    fontScale = fontScale,
                    customFontFamily = customFontFamily,
                    dynamicPalette = dynamicPalette
                ) {
                    // Re-apply edge-to-edge with a SystemBarStyle tuned to
                    // the current theme. Light themes need dark icons so
                    // they stay legible on white system bars; dark themes
                    // want light icons on transparent scrims. enableEdgeToEdge
                    // is idempotent — safe to call on every background change.
                    val background = MaterialTheme.colorScheme.background
                    SideEffect {
                        val isLight = background.luminance() > 0.5f
                        val style = if (isLight) {
                            SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                        } else {
                            SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                        }
                        this@MainActivity.enableEdgeToEdge(
                            statusBarStyle = style,
                            navigationBarStyle = style
                        )
                    }
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        MonochromeNavHost()
                    }
                }
            }
        }
    }

    /**
     * On Android 13+ the manifest POST_NOTIFICATIONS entry isn't sufficient —
     * the user must be prompted once. Without the grant, Media3's foreground
     * playback notification is silently dropped, so the user sees no lock
     * screen / shade controls and the system can reclaim the service mid-track.
     * Called once per cold start; the ActivityResult contract throttles
     * reprompts automatically when the user has already decided.
     */
    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * Hardware-volume-key interception. The phone's volume rocker
     * normally only steers AudioFlinger's STREAM_MUSIC, which has no
     * audible effect when the libusb bypass path is hot — the iso
     * pump writes PCM directly to the DAC and AudioFlinger isn't in
     * the chain. So when the iso pump is streaming, we consume the
     * key event, nudge the BypassVolumeController, persist the new
     * value to preferences (so the slider in NowPlaying mirrors it
     * and the value survives restart), and return true.
     *
     * When bypass is NOT active (delegate sink path, or USB DAC
     * unplugged), we fall through to super and let the system do its
     * thing — STREAM_MUSIC volume actually reaches the speakers /
     * Bluetooth / non-exclusive USB output as expected.
     *
     * Step size is 1/25 ≈ 4% per press, picked to roughly match the
     * granularity of Android's STREAM_MUSIC slider on most phones
     * so the press cadence feels familiar. ACTION_DOWN only —
     * ACTION_UP fires on every key release and would double the
     * step otherwise.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                              event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            if (isVolumeKey && libusbDriver.isStreaming.value) {
                val current = bypassVolumeController.getVolume()
                val step = 1f / 25f
                val next = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    current + step
                } else {
                    current - step
                }
                val clamped = next.coerceIn(0f, 1f)
                bypassVolumeController.setVolume(clamped)
                lifecycleScope.launch {
                    preferences.setVolume(clamped.toDouble())
                }
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            // Swallow the matching UP event so the system doesn't
            // briefly show its own STREAM_MUSIC volume panel after we
            // already handled the DOWN. Same gating as the DOWN path
            // — when bypass is off, the system handles both.
            val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                              event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            if (isVolumeKey && libusbDriver.isStreaming.value) return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Called when the activity is relaunched by the Appwrite OAuth callback.
     * Since launchMode=singleTop, the activity is not recreated — we must
     * manually trigger refreshUser() here to pick up the new session.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Handle Supabase OAuth callback deep-link
        val uri = intent.data ?: return
        lifecycleScope.launch {
            supabaseAuthManager.handleDeepLink(uri)
        }
    }
}
