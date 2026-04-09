package tf.monochrome.android.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import tf.monochrome.android.data.auth.SupabaseAuthManager
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.ui.navigation.MonochromeNavHost
import tf.monochrome.android.ui.theme.MonochromeTheme
import java.io.File
import javax.inject.Inject
import tf.monochrome.android.audio.eq.FrequencyTargets

val LocalBlurTarget = compositionLocalOf<BlurTarget?> { null }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferences: PreferencesManager
    @Inject lateinit var supabaseAuthManager: SupabaseAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

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

            CompositionLocalProvider(LocalBlurTarget provides blurTarget) {
                MonochromeTheme(
                    themeName = themeName,
                    fontScale = fontScale,
                    customFontFamily = customFontFamily
                ) {
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
