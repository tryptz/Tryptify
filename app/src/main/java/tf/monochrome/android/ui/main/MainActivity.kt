package tf.monochrome.android.ui.main

import android.Manifest
import android.annotation.SuppressLint
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
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.player.QueueManager
import tf.monochrome.android.ui.navigation.MonochromeNavHost
import tf.monochrome.android.ui.onboarding.OnboardingScreen
import tf.monochrome.android.ui.theme.MonochromeTheme
import tf.monochrome.android.ui.theme.ColorBlend
import tf.monochrome.android.ui.theme.rememberDynamicPalette
import javax.inject.Inject
import tf.monochrome.android.audio.eq.FrequencyTargets
import tf.monochrome.android.performance.LocalPerformanceProfile
import tf.monochrome.android.performance.PerformanceProfile

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var supabaseAuthManager: SupabaseAuthManager
    @Inject lateinit var spotifyAuthManager: tf.monochrome.android.data.auth.SpotifyAuthManager
    @Inject lateinit var lastFmAuthManager: tf.monochrome.android.data.auth.LastFmAuthManager
    @Inject lateinit var queueManager: QueueManager
    @Inject lateinit var performanceProfile: PerformanceProfile
    @Inject lateinit var libusbDriver: tf.monochrome.android.audio.usb.LibusbUacDriver
    @Inject lateinit var bypassVolumeController: tf.monochrome.android.audio.usb.BypassVolumeController

    /** Set once the onboarding flag has been read; releases the splash screen. */
    @Volatile private var onboardingGateLoaded = false

    /**
     * Route the main app should open right after onboarding hands off
     * ("library", "settings?tab=7"), null for the default start screen.
     */
    private var pendingPostRoute by mutableStateOf<String?>(null)

    // Registered-for-result launcher for POST_NOTIFICATIONS. Fires a one-shot
    // system prompt on Android 13+; the result doesn't block the UI either way
    // — if the user denies we just lose the playback notification, playback
    // itself still works.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        // Hold the system splash until the onboarding flag has been read, so
        // neither the wizard nor the main UI flashes in before we know which
        // one to show.
        splash.setKeepOnScreenCondition { !onboardingGateLoaded }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Make the system route hardware volume keys to STREAM_MUSIC
        // by default. We re-route them to BypassVolumeController only
        // when the libusb iso pump is actively streaming (see
        // dispatchKeyEvent below); otherwise the system handles them
        // normally and AudioFlinger volume changes apply to the
        // delegate sink path as expected.
        volumeControlStream = AudioManager.STREAM_MUSIC

        // Notification permission waits for onboarding: firing the system
        // dialog on top of the Welcome screen would stack two prompts. For
        // already-onboarded users the flag is true immediately and this
        // behaves exactly like the old unconditional call.
        lifecycleScope.launch {
            preferences.onboardingComplete.first()
            onboardingGateLoaded = true
            preferences.onboardingComplete.first { it }
            maybeRequestNotificationPermission()
        }

        // Restore existing Supabase session on startup
        lifecycleScope.launch {
            supabaseAuthManager.initialize()
        }

        // A cold start triggered by the OAuth redirect delivers the callback as
        // the launch intent (onNewIntent never fires) — handle it here too so
        // login completes instead of silently failing.
        handleDeepLinkIntent(intent)

        FrequencyTargets.init(applicationContext)

        // Apply the user's app-wide frame-rate / resolution preference by
        // selecting a matching panel display mode. Defaults (0/0) mean
        // unlocked refresh at native resolution — the old forced-max
        // behaviour. Re-applies live whenever either setting changes.
        lifecycleScope.launch {
            combine(preferences.appTargetFps, preferences.appRenderResolution) { fps, res ->
                fps to res
            }.collect { (fps, res) -> applyDisplayMode(fps, res) }
        }

        // Root container wrapping the Compose content. (Real glass blur is
        // handled by Haze in-Compose; no native BlurView wrapper is needed.)
        val rootContainer = FrameLayout(this)
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        rootContainer.addView(composeView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(rootContainer)

        composeView.setContent {
            val themeName by preferences.theme.collectAsStateWithLifecycle(initialValue = "monochrome_dark")
            val storedFontScale by preferences.fontScale.collectAsStateWithLifecycle(initialValue = 1.0f)
            val followSystemFontScale by preferences.fontScaleFollowSystem.collectAsStateWithLifecycle(initialValue = false)
            // "Follow system" hands typography over to the OS accessibility font
            // size. Configuration.fontScale already reflects the user's Display >
            // Font size setting, so no extra permission or listener is needed —
            // a config change recomposes this and the type updates live.
            val systemFontScale = androidx.compose.ui.platform.LocalConfiguration.current.fontScale
            val fontScale = if (followSystemFontScale) systemFontScale else storedFontScale
            val customFontPath by preferences.customFontUri.collectAsStateWithLifecycle(initialValue = null)
            val dynamicColorsEnabled by preferences.dynamicColors.collectAsStateWithLifecycle(initialValue = false)
            // Whether that palette is also allowed to repaint the menus, and
            // whether it gets the ground as well as the accent.
            val dynamicColorMenus by preferences.dynamicColorMenus.collectAsStateWithLifecycle(initialValue = false)
            val dynamicColorKeepBackground by preferences.dynamicColorKeepBackground
                .collectAsStateWithLifecycle(initialValue = false)
            val themePaper by preferences.themePaper.collectAsStateWithLifecycle(initialValue = "crisp")
            // Custom colours override the preset when the switch is on. Read here
            // so a change repaints the whole app the same frame the store emits.
            val customThemeEnabled by preferences.customThemeEnabled.collectAsStateWithLifecycle(initialValue = false)
            val customAccent by preferences.customAccentColor.collectAsStateWithLifecycle(initialValue = 0xFF5865F2.toInt())
            val customBackground by preferences.customBackgroundColor.collectAsStateWithLifecycle(initialValue = 0xFF101014.toInt())
            val currentTrack by queueManager.currentTrack.collectAsStateWithLifecycle()
            // The user's manual low-performance overrides, from Settings ›
            // System › Performance.
            val lowPerformance by preferences.lowPerformanceSettings
                .collectAsStateWithLifecycle(
                    initialValue = tf.monochrome.android.performance.LowPerformanceSettings()
                )
            // The album palette crosses over at the speed the audio does, so a
            // blended transition doesn't have the colours land on the new track
            // while the old one is still playing.
            val blendSeconds by preferences.crossfadeDuration.collectAsStateWithLifecycle(initialValue = 0)
            val colorTransitionMs by preferences.colorTransitionMs
                .collectAsStateWithLifecycle(initialValue = ColorBlend.MATCH_BLEND)
            val dynamicPalette by rememberDynamicPalette(
                coverUrl = currentTrack?.coverUrl,
                enabled = dynamicColorsEnabled,
                // Instant colour change with animations off: the palette is a
                // continuous cross-fade, not a one-off transition, so it keeps
                // the theme recomposing for the whole blend window.
                blendMillis = if (lowPerformance.disableAnimations) 0
                else ColorBlend.millisFor(blendSeconds, colorTransitionMs),
            )

            // Handles both a bundled `asset:` font and an imported file path —
            // see loadAppFontFamily. Keyed on the id so switching fonts in
            // Settings re-reads immediately.
            val fontLoadContext = androidx.compose.ui.platform.LocalContext.current
            val customFontFamily = remember(customFontPath, fontLoadContext) {
                tf.monochrome.android.ui.theme.loadAppFontFamily(fontLoadContext, customFontPath)
            }

            // "Remove liquid glass" is folded into the detected profile rather
            // than checked separately: allowHazeBlur is already the flag every
            // `Modifier.liquidGlass` call site consults, so turning it off here
            // reaches all of them — list rows, the mini player, the nav pill,
            // the audio-tools sheet — without touching one of them.
            val effectiveProfile = remember(performanceProfile, lowPerformance.disableLiquidGlass) {
                if (lowPerformance.disableLiquidGlass) {
                    performanceProfile.copy(allowHazeBlur = false)
                } else {
                    performanceProfile
                }
            }

            CompositionLocalProvider(
                LocalPerformanceProfile provides effectiveProfile,
                tf.monochrome.android.performance.LocalLowPerformance provides lowPerformance,
            ) {
                MonochromeTheme(
                    themeName = themeName,
                    fontScale = fontScale,
                    customFontFamily = customFontFamily,
                    dynamicPalette = dynamicPalette,
                    paper = if (themePaper == "warm") {
                        tf.monochrome.android.ui.theme.Paper.Warm
                    } else {
                        tf.monochrome.android.ui.theme.Paper.Crisp
                    },
                    customColors = if (customThemeEnabled) {
                        tf.monochrome.android.ui.theme.CustomThemeColors(
                            accent = customAccent,
                            background = customBackground,
                        )
                    } else {
                        null
                    },
                    dynamicMenus = dynamicColorsEnabled && dynamicColorMenus,
                    dynamicMenusKeepBackground = dynamicColorKeepBackground,
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
                        // First-run gate. null = flag not read yet (splash is
                        // still covering); false = wizard; true = main app.
                        // Collected (not read once) so Settings flipping the
                        // flag back re-enters onboarding on the next frame.
                        val onboardingComplete by preferences.onboardingComplete
                            .collectAsStateWithLifecycle(initialValue = null)
                        when (onboardingComplete) {
                            null -> Unit
                            false -> OnboardingScreen(
                                onFinished = { pendingPostRoute = it }
                            )
                            true -> MonochromeNavHost(initialRoute = pendingPostRoute)
                        }
                    }
                }
            }
        }
    }

    /**
     * Picks the display mode closest to the requested short-side resolution
     * and refresh rate; 0 means no cap (native resolution / maximum refresh).
     * Panels expose a fixed mode list, so a request maps to the nearest
     * supported mode — e.g. "720p" on a panel that only exposes 1080p and
     * 1440p modes applies 1080p.
     */
    private fun applyDisplayMode(targetFps: Int, targetShortSide: Int) {
        val modes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.supportedModes ?: emptyArray()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.supportedModes
        }
        if (modes.isEmpty()) return
        val nativeShort = modes.maxOf { minOf(it.physicalWidth, it.physicalHeight) }
        val wantShort = if (targetShortSide <= 0) nativeShort else targetShortSide
        val byShortSide = modes.groupBy { minOf(it.physicalWidth, it.physicalHeight) }
        val chosenShort = byShortSide.keys.minByOrNull { kotlin.math.abs(it - wantShort) } ?: return
        val candidates = byShortSide.getValue(chosenShort)
        val chosen = if (targetFps <= 0) {
            candidates.maxByOrNull { it.refreshRate }
        } else {
            candidates.minByOrNull { kotlin.math.abs(it.refreshRate - targetFps) }
        } ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = chosen.modeId }
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
    @SuppressLint("RestrictedApi")
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
                // Floor at 1/25 so vol-down presses can never drive the
                // app to total silence — without an in-flow volume
                // slider the user has no way back from a silenced
                // state, and a stale 0.0 in preferences silences the
                // app on every subsequent launch.
                val clamped = next.coerceIn(step, 1f)
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
        handleDeepLinkIntent(intent)
    }

    /**
     * Route auth callback deep-links: Spotify (tryptify://spotify-callback),
     * Last.fm (tryptify://lastfm-callback) and Supabase
     * (tf.monotrypt.android://login-callback). Handled from BOTH
     * onNewIntent (app already running) and onCreate (cold start after process
     * death, where the redirect delivers the callback as the launch intent —
     * previously dropped, so login silently failed).
     */
    private fun handleDeepLinkIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        lifecycleScope.launch {
            if (uri.scheme == "tryptify" && uri.host == "spotify-callback") {
                spotifyAuthManager.handleCallback(uri)
            } else if (uri.scheme == "tryptify" && uri.host == "lastfm-callback") {
                lastFmAuthManager.handleCallback(uri)
            } else {
                supabaseAuthManager.handleDeepLink(uri)
            }
        }
    }
}
