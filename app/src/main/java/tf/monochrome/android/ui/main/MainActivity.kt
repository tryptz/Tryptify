package tf.monochrome.android.ui.main

import kotlinx.coroutines.flow.map
import tf.monochrome.android.ui.glyph.tvShader
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
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.graphics.luminance
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
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
    @Inject lateinit var lastFmAuthManager: tf.monochrome.android.data.auth.LastFmAuthManager
    @Inject lateinit var queueManager: QueueManager
    @Inject lateinit var trackSourceResolver: tf.monochrome.android.ui.components.TrackSourceResolver
    @Inject lateinit var performanceProfile: PerformanceProfile
    @Inject lateinit var libusbDriver: tf.monochrome.android.audio.usb.LibusbUacDriver
    @Inject lateinit var bypassVolumeController: tf.monochrome.android.audio.usb.BypassVolumeController
    @Inject lateinit var musicLinks: tf.monochrome.android.data.repository.MusicLinksRepository
    @Inject lateinit var outputSync: tf.monochrome.android.audio.sync.OutputSyncRepository
    @Inject lateinit var spectrumAnalyzer: tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
    @Inject lateinit var openedAudio: tf.monochrome.android.data.opened.OpenedAudioRepository
    @Inject lateinit var partyInvites: tf.monochrome.android.party.PartyInvites
    @Inject lateinit var sharedLinks: tf.monochrome.android.share.SharedLinks

    /** Set once the onboarding flag has been read; releases the splash screen. */
    @Volatile private var onboardingGateLoaded = false

    /**
     * Route the main app should open right after onboarding hands off
     * ("library", "settings?tab=7"), null for the default start screen.
     */
    private var pendingPostRoute by mutableStateOf<String?>(null)

    /**
     * The app-wide CRT's sliders as its picture is drawn this frame, music
     * links included, for [dispatchTouchEvent] to bend taps through a curved
     * tube; null when the effect is off. [appTubeBounds] is where its layer
     * sits in the window.
     */
    private var appTube: (() -> tf.monochrome.android.glyph.engine.GlyphTvTuning)? = null

    /**
     * The tube's shape as the gesture under way began on it. A linked curve or
     * tilt moves with the beat; bent through each frame's shape, a finger held
     * still would drift and a tap could turn into a scroll.
     */
    private var gestureTube: tf.monochrome.android.glyph.engine.GlyphTvTuning? = null
    private var appTubeLook = tf.monochrome.android.glyph.engine.GlyphTvShader.OFF
    private val appTubeBounds = android.graphics.RectF()

    // Registered-for-result launcher for POST_NOTIFICATIONS. Fires a one-shot
    // system prompt on Android 13+; the result doesn't block the UI either way
    // — if the user denies we just lose the playback notification, playback
    // itself still works.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    // The app's chosen language below Android 13; above it the system has
    // already applied it and this hands the context straight back.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(tf.monochrome.android.locale.AppLanguage.wrap(newBase))
    }

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
        // while the DAC level is what is heard (steersOutput: a DAC
        // claimed, its stream on it, and nothing of the app's own on
        // Android's output; see dispatchKeyEvent below); otherwise the
        // system handles them normally and AudioFlinger volume changes
        // apply to the delegate sink path as expected.
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
        // Re-created after a rotation or from Recents, the activity is handed
        // the same intent again: a song opened once must not start over.
        handleDeepLinkIntent(
            intent,
            openFiles = savedInstanceState == null &&
                intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0,
        )

        FrequencyTargets.init(applicationContext)

        // No display-mode preference is applied, and none should be added
        // back: this window deliberately holds no refresh-rate or resolution
        // vote, so the panel is left entirely to system policy and to any
        // per-app override the user has installed. Pinning a mode here — even
        // at the panel's ceiling — is a competing vote, and on a display whose
        // top rate is only offered at a lower resolution it is a slower one
        // than the override asked for, so the app ended up arguing its own
        // display down. What used to be Settings › System › Display › Frame
        // Rate and Resolution was removed for this reason. The one exception
        // is the listener's own: a Frame rate limit below Max asks for a
        // slower rate (never a mode, never a resolution), because that is
        // what they asked the app to do (FrameRateLimit.applyTo).

        // Root container wrapping the Compose content. (Real glass blur is
        // handled by Haze in-Compose; no native BlurView wrapper is needed.)
        // Holds every redraw to Settings › System › Performance › Frame rate
        // limit; a plain FrameLayout at Max.
        val rootContainer = tf.monochrome.android.performance.FrameLimitedLayout(this)
        val composeView = androidx.compose.ui.platform.ComposeView(this)
        rootContainer.addView(composeView, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        setContentView(rootContainer)
        // Settings › System › Performance › Frame rate limit: the whole app is
        // composed on the display's frame clock with the limit applied, so
        // every recomposition, animation and frame loop under it, Trypt
        // Mania's playfield included, moves on at most that many frames a
        // second. Not a display vote: the panel's rate is left alone.
        val displayClock = androidx.compose.ui.platform.AndroidUiDispatcher.CurrentThread[
            androidx.compose.runtime.MonotonicFrameClock,
        ]
        if (displayClock != null) {
            composeView.setParentCompositionContext(
                composeView.createLifecycleAwareWindowRecomposer(
                    androidx.compose.ui.platform.AndroidUiDispatcher.CurrentThread +
                        tf.monochrome.android.performance.CappedFrameClock(displayClock),
                    lifecycle,
                ),
            )
        }

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
            // Settings › System › Display › Full screen. Published as a
            // composition local as well as applied here, so the visualiser's
            // own fullscreen mode knows not to show the bars again on its way
            // out — see SystemBarsHidden.
            // Settings › Appearance › CRT effect: every screen through an old TV.
            val appTvShader by preferences.appTvShader
                .collectAsStateWithLifecycle(initialValue = tf.monochrome.android.glyph.engine.GlyphTvShader.OFF)
            val appTvOnLyrics by preferences.appTvOnLyrics.collectAsStateWithLifecycle(initialValue = false)
            val appTvStored = preferences.appTvTuning
                .collectAsStateWithLifecycle(initialValue = tf.monochrome.android.glyph.engine.GlyphTvTuning())
            // A slider being dragged wins until the store has its value.
            androidx.compose.runtime.LaunchedEffect(appTvStored.value) {
                if (tf.monochrome.android.ui.glyph.AppTvLive.tuning == appTvStored.value) {
                    tf.monochrome.android.ui.glyph.AppTvLive.tuning = null
                }
            }
            val appTvNow = remember(appTvStored) {
                { tf.monochrome.android.ui.glyph.AppTvLive.tuning ?: appTvStored.value }
            }
            // The app's own music links: the UI panels' glass (the mini player,
            // the tab bar, every pane) and the CRT effect, which are on every
            // screen. It holds the analyser only while one of those is linked;
            // the player's driver runs the lyrics and its own controls, and
            // reads through to this one for the rest.
            // The link open in an editor runs here too, so the CRT moves with it.
            val runningLinks by musicLinks.previewVisualLinks.collectAsStateWithLifecycle(initialValue = emptyList())
            val appLinks = remember(runningLinks) {
                runningLinks.filter { it.target.startsWith("panels.") || it.target.startsWith("tv.") }
            }
            // How late the output playing now plays (Settings › Audio ›
            // Bluetooth sync): the lyrics and every visual link wait for it.
            val outputSyncMs by outputSync.delayMs.collectAsStateWithLifecycle()
            val linkDelayMs = outputSyncMs.toFloat()
            val appLive = tf.monochrome.android.ui.player.rememberVisualLinkDriver(
                tap = spectrumAnalyzer,
                links = appLinks,
                active = true,
                delayMs = linkDelayMs,
            )
            // What the CRT shader draws: the tuning with its linked settings
            // live, read in the shader's layer block. Touches are bent through
            // the same, held for each gesture (dispatchTouchEvent).
            val appTvDrawn = remember(appTvNow, appLive) { { appLive.tv(appTvNow()) } }
            val immersiveFullScreen by preferences.immersiveFullScreen
                .collectAsStateWithLifecycle(initialValue = false)
            // The user's manual low-performance overrides, from Settings ›
            // System › Performance.
            val showExplicitBadges by preferences.showExplicitBadges
                .collectAsStateWithLifecycle(initialValue = true)
            val rowBadges by preferences.rowBadges
                .collectAsStateWithLifecycle(initialValue = tf.monochrome.android.data.preferences.RowBadges())
            val lowPerformance by preferences.lowPerformanceSettings
                .collectAsStateWithLifecycle(
                    initialValue = tf.monochrome.android.performance.LowPerformanceSettings()
                )
            // Settings › System › Performance: the frame rate limit, read by
            // the frame clock and the visualizer's render threads, and the
            // FPS counter drawn over everything.
            val frameRateLimit by preferences.frameRateLimit
                .collectAsStateWithLifecycle(initialValue = tf.monochrome.android.performance.FrameRateLimit.MAX)
            val showFpsCounter by preferences.showFpsCounter.collectAsStateWithLifecycle(initialValue = false)
            SideEffect {
                tf.monochrome.android.performance.FrameRateLimit.fps = frameRateLimit
                tf.monochrome.android.performance.FrameRateLimit.applyTo(window, frameRateLimit)
            }
            // The album palette crosses over at the Color transition length —
            // a few hundred milliseconds by default, not the audio blend, which
            // could make every track change a six-second repaint.
            val colorTransitionMs by preferences.colorTransitionMs
                .collectAsStateWithLifecycle(initialValue = ColorBlend.DEFAULT_MS)
            // Held as state, not unwrapped: `by` here would subscribe this whole
            // composable to a value that changes every frame of the colour
            // cross-fade, recomposing the root and every provider under it for
            // the length of the fade. MonochromeTheme passes it down and only
            // the surfaces that paint with it read it.
            // One palette for the whole app, from the playing track's cover or
            // from the song a screen like Trypt Mania has picked in its place
            // (DynamicColorSource): two at once coloured the screen from two
            // covers.
            val dynamicPalette = rememberDynamicPalette(
                coverUrl = tf.monochrome.android.ui.theme.DynamicColorSource.coverFor(currentTrack?.coverUrl),
                enabled = dynamicColorsEnabled,
                // Instant colour change with animations off: the palette is a
                // continuous cross-fade rather than a one-off transition, so it
                // goes on producing values for the whole blend window.
                blendMillis = if (lowPerformance.disableAnimations) 0
                else tf.monochrome.android.ui.theme.DynamicColorSource.blendFor(ColorBlend.millisFor(colorTransitionMs)),
            )

            // Handles both a bundled `asset:` font and an imported file path —
            // see loadAppFontFamily. Keyed on the id so switching fonts in
            // Settings re-reads immediately.
            val fontLoadContext = androidx.compose.ui.platform.LocalContext.current
            val customFontFamily = remember(customFontPath, fontLoadContext) {
                tf.monochrome.android.ui.theme.loadAppFontFamily(fontLoadContext, customFontPath)
            }
            // The typography table, and the fonts its styles name besides the main one.
            val typographySettings by preferences.typography
                .collectAsStateWithLifecycle(initialValue = tf.monochrome.android.domain.model.TypographySettings.DEFAULT)
            // Each (font, width) the styles draw at, loaded once: the main font at
            // its own width needs nothing, being customFontFamily itself.
            val typographyFonts = remember(typographySettings.fontWidths, customFontPath, fontLoadContext) {
                typographySettings.fontWidths
                    .filterNot { (id, width) -> id == null && width == 100 }
                    .associateWith { (id, width) ->
                        tf.monochrome.android.ui.theme.loadWidthFont(fontLoadContext, id ?: customFontPath, width)
                    }
            }

            // "Remove liquid glass" is folded into the detected profile rather
            // than checked separately: allowHazeBlur is the flag every haze
            // frost consults, so turning it off here reaches all of them
            // without touching one of them.
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
                tf.monochrome.android.ui.theme.LocalShowExplicitBadges provides showExplicitBadges,
                tf.monochrome.android.ui.theme.LocalRowBadges provides rowBadges,
                LocalImmersiveFullScreen provides immersiveFullScreen,
                tf.monochrome.android.ui.player.LocalOutputSyncMs provides linkDelayMs,
                // Every list row's source tag reads its catalog from here.
                tf.monochrome.android.ui.components.LocalTrackSource provides trackSourceResolver::of,
            ) {
                MonochromeTheme(
                    themeName = themeName,
                    fontScale = fontScale,
                    customFontFamily = customFontFamily,
                    typographySettings = typographySettings,
                    typographyFonts = typographyFonts,
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
                    SystemBarsHidden(immersiveFullScreen)
                    // The CRT effect over the whole app, drawn on the one layer
                    // every screen is in; a screen with its own (Trypt Mania's
                    // playfield) leaves its own off under it.
                    val tvRuns = tf.monochrome.android.ui.glyph.appTvShaderRuns(appTvShader)
                    // Over every screen, or handed to the lyric view alone.
                    val appTv = tvRuns && !appTvOnLyrics
                    val lyricsTv = remember(tvRuns, appTvOnLyrics, appTvShader, appTvDrawn) {
                        if (tvRuns && appTvOnLyrics) tf.monochrome.android.ui.glyph.LyricsTv(appTvShader, appTvDrawn) else null
                    }
                    SideEffect {
                        appTube = if (appTv) appTvDrawn else null
                        appTubeLook = appTvShader
                        tf.monochrome.android.debug.CrashBreadcrumbs.note(
                            "crt",
                            if (!tvRuns) "off" else "${appTvShader.id} over ${if (appTvOnLyrics) "lyrics" else "app"}",
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned {
                                val at = it.positionInWindow()
                                appTubeBounds.set(at.x, at.y, at.x + it.size.width, at.y + it.size.height)
                            }
                            .then(if (appTv) Modifier.tvShader(appTvShader, tv = appTvDrawn) else Modifier),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        CompositionLocalProvider(
                            tf.monochrome.android.ui.glyph.LocalAppTvShader provides appTv,
                            tf.monochrome.android.ui.glyph.LocalLyricsTv provides lyricsTv,
                            tf.monochrome.android.ui.glyph.LocalAppTube provides (if (appTv) appTvNow else null),
                            tf.monochrome.android.ui.player.LocalVisualLinks provides appLive,
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
                    // Over the CRT's layer, not in it, so it reads plainly.
                    if (showFpsCounter) {
                        tf.monochrome.android.performance.FpsCounter(
                            limit = frameRateLimit,
                            modifier = Modifier
                                .fillMaxSize()
                                .wrapContentSize(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(top = 4.dp, end = 8.dp),
                        )
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
     * the chain. So while a DAC is claimed for exclusive output (paused
     * or between tracks too, so a press before play still counts), we
     * consume the key event and step the DAC level instead (BypassVolumeController,
     * 2 dB a press); the step also brings up the glass volume pop-up,
     * since Android's own panel would show a volume that does nothing.
     * Not while the playing stream went to Android's output although a DAC
     * is claimed (a format the DAC has no alt for): then STREAM_MUSIC is
     * the volume heard, and the keys go to it.
     *
     * The level is the DAC's alone: it is not written into the player's
     * volume preference, which the non-exclusive path also plays at —
     * doing that left normal playback quiet after a session on a DAC.
     *
     * When bypass is NOT active (delegate sink path, or USB DAC
     * unplugged), we fall through to super and let the system do its
     * thing — STREAM_MUSIC volume actually reaches the speakers /
     * Bluetooth / non-exclusive USB output as expected. With the app in
     * the background the keys reach the DAC through the media session's
     * remote volume instead (PlaybackService).
     *
     * ACTION_DOWN only — ACTION_UP fires on every key release and would
     * double the step otherwise. A held key repeats ACTION_DOWN.
     */
    /**
     * Under the app-wide CRT's Curvature the picture is bowed but the screens
     * under it are not: a tap goes to the spot on them that is seen under the
     * finger, so a button near a corner is pressed where it shows. A gesture
     * keeps the shape it started on, from the first finger down to the last up.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> gestureTube = appTube?.invoke()
            // Turned off mid-gesture, the rest of it goes through flat; turned
            // on mid-gesture, it waits for the next finger down.
            else -> if (appTube == null) gestureTube = null
        }
        val tuning = gestureTube
        if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) gestureTube = null
        val bounds = appTubeBounds
        if (tuning == null || !tuning.reshaped || bounds.isEmpty || !tf.monochrome.android.ui.glyph.tubeDrawable(appTubeLook)) {
            return super.dispatchTouchEvent(ev)
        }
        val bent = tf.monochrome.android.ui.glyph.bentThroughTube(
            ev, tuning, bounds.left, bounds.top, bounds.width(), bounds.height(),
        )
        return try {
            super.dispatchTouchEvent(bent)
        } finally {
            bent.recycle()
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                              event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            if (isVolumeKey && bypassVolumeController.steersOutput(libusbDriver.isOpen.value)) {
                bypassVolumeController.stepLevel(
                    if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                )
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP) {
            // Swallow the matching UP event so the system doesn't
            // briefly show its own STREAM_MUSIC volume panel after we
            // already handled the DOWN. Same gating as the DOWN path
            // — when bypass is off, the system handles both.
            val isVolumeKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                              event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
            if (isVolumeKey && bypassVolumeController.steersOutput(libusbDriver.isOpen.value)) return true
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
     * Route what other apps send: an audio file opened with Tryptify, a song
     * or album shared from TrypT-hifi (tryptify.com/listen/…, played at once),
     * a listening party invite (the rooms server's https link), and the
     * auth callback deep-links, Last.fm (tryptify://lastfm-callback) and Supabase
     * (tf.monotrypt.android://login-callback). Handled from BOTH
     * onNewIntent (app already running) and onCreate (cold start after process
     * death, where the redirect delivers the callback as the launch intent —
     * previously dropped, so login silently failed).
     *
     * Anything else is dropped. It all used to go to Supabase, so a song
     * opened from a file manager showed as a failed sign-in.
     */
    private fun handleDeepLinkIntent(intent: Intent?, openFiles: Boolean = true) {
        val uri = intent?.data ?: return
        if (intent.action != Intent.ACTION_VIEW) return
        val scheme = uri.scheme?.lowercase()
        if (scheme == "content" || scheme == "file") {
            // Taken now, while this activity holds the permission to read it.
            if (openFiles) openedAudio.open(uri, intent.type)
            return
        }
        // A shared song, or a listening party invite. Only once, as a song
        // opened is: rotated or reopened from Recents, the same link does not
        // play or join again.
        if (scheme == "https") {
            if (openFiles && !sharedLinks.offer(uri)) partyInvites.offer(uri)
            return
        }
        lifecycleScope.launch {
            if (scheme == "tryptify" && uri.host == "lastfm-callback") {
                lastFmAuthManager.handleCallback(uri)
            } else if (scheme == "tf.monotrypt.android") {
                supabaseAuthManager.handleDeepLink(uri)
            }
        }
    }
}
