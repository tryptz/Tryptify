package tf.monochrome.android.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.navigateTool
import androidx.compose.foundation.layout.Box

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    // Passed through to the Overview section Home draws; it never moves the
    // pager itself — the tab bar does that now.
    pages: List<String>,
    onSelectPage: (String) -> Unit,
    downloadCenter: tf.monochrome.android.ui.downloads.DownloadCenterViewModel = hiltViewModel(),
    settingsViewModel: tf.monochrome.android.ui.settings.SettingsViewModel = hiltViewModel(),
) {
    val homeContext = androidx.compose.ui.platform.LocalContext.current
    val activeDownloads by downloadCenter.active.collectAsStateWithLifecycle()
    val downloadProgress by downloadCenter.overallProgress.collectAsStateWithLifecycle()
    var showDownloadsMonitor by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    // Update notice. Reads straight off the settings store so opening About
    // from anywhere — the bar, or the user's own navigation — clears it.
    val whatsNewSeen by settingsViewModel.whatsNewSeenVersion.collectAsStateWithLifecycle()
    val whatsNewNeverShow by settingsViewModel.whatsNewNeverShow.collectAsStateWithLifecycle()
    val showWhatsNew = tf.monochrome.android.ui.settings.WhatsNew
        .shouldNotify(whatsNewSeen, whatsNewNeverShow)
    val whatsNewVersionName = tf.monochrome.android.ui.settings.WhatsNew
        .current?.versionName.orEmpty()

    // The tip bar, offered every DonatePrompt.EVERY_N_SONGS songs. Ranked
    // below both update notices: those say something changed, this is a
    // request, and two bars stacked at the top of Home is one too many.
    val donatePlays by settingsViewModel.donatePlaysSincePrompt.collectAsStateWithLifecycle()
    val donateNeverShow by settingsViewModel.donateNeverShow.collectAsStateWithLifecycle()
    val showDonate = tf.monochrome.android.ui.components.DonatePrompt
        .shouldShow(donatePlays, donateNeverShow)

    // A release waiting on GitHub outranks the notes for the build already
    // installed: "there's a newer version" is the more useful of the two, and
    // showing both at once would be two bars saying almost the same thing.
    val availableUpdate by settingsViewModel.availableUpdate.collectAsStateWithLifecycle()
    val showUpdateBar by settingsViewModel.showUpdateBar.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { settingsViewModel.refreshUpdateStatus() }

    if (showDownloadsMonitor) {
        tf.monochrome.android.ui.downloads.DownloadsMonitorSheet(
            downloads = activeDownloads,
            onCancel = downloadCenter::cancel,
            onCancelAll = downloadCenter::cancelAll,
            onDismiss = { showDownloadsMonitor = false },
            onRetry = downloadCenter::retry,
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        tf.monochrome.android.devedit.DevEditable("home_header", Modifier.fillMaxWidth()) {
            TopAppBar(
                title = {
                    Text(
                        text = "Tryptify",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                // No search icon: Search is its own page now, the round button
                // beside the tab bar, and it took the catalogue search and its
                // history with it.
                actions = {
                    tf.monochrome.android.ui.downloads.DownloadTopBarIndicator(
                        activeCount = activeDownloads.size,
                        overallProgress = downloadProgress,
                        onClick = { showDownloadsMonitor = true },
                    )
                    IconButton(onClick = { navController.navigateTool(Screen.Settings, Screen.Settings.createRoute()) }) {
                        Icon(
                            Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { navController.navigateTool(Screen.Profile) }) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Profile",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                )
            )
        }

        // ── Home is the Overview ────────────────────────────
        //
        // It was a list of every page while pages were picked from Home; the
        // tab bar does that job now. What Home shows instead is what Overview
        // showed — Recently Played, then Liked Songs — which is what a Home in
        // a music app is for. It is LibraryScreen's own Overview section rather
        // than a copy, so selection, the context menu and "See All" behave the
        // same as they did on the Library page it came from.
        //
        // The banners sit above it rather than inside it, one dismissible row
        // apiece, so the list's own rows are the library and nothing else.
        Column(modifier = Modifier.fillMaxSize()) {
                val update = availableUpdate
                if (showUpdateBar && update != null) {
                    tf.monochrome.android.ui.components.WhatsNewBar(
                        title = "Version ${update.versionName} is available",
                        subtitle = "Tap to see the release on GitHub",
                        onOpen = {
                            settingsViewModel.dismissUpdate()
                            runCatching {
                                homeContext.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(update.releaseUrl),
                                    )
                                )
                            }
                        },
                        onDismiss = { settingsViewModel.dismissUpdate() },
                        onNeverShow = { settingsViewModel.neverShowWhatsNew() },
                    )
                } else if (showWhatsNew) {
                    tf.monochrome.android.ui.components.WhatsNewBar(
                        title = "Updated to $whatsNewVersionName",
                        subtitle = "See what's new",
                        onOpen = {
                            settingsViewModel.markWhatsNewSeen()
                            navController.navigateSafe(
                                Screen.Settings.createRoute(
                                    tf.monochrome.android.ui.settings.SETTINGS_TAB_ABOUT
                                )
                            )
                        },
                        onDismiss = { settingsViewModel.markWhatsNewSeen() },
                        onNeverShow = { settingsViewModel.neverShowWhatsNew() },
                    )
                } else if (showDonate) {
                    tf.monochrome.android.ui.components.DonateBar(
                        // Taking either offer puts the bar away for another run
                        // of songs, but never for good: somebody who tipped
                        // once has not asked to stop being asked, and the
                        // checkbox is there for those who have.
                        onKofi = {
                            settingsViewModel.dismissDonatePrompt(neverAgain = false)
                            tf.monochrome.android.ui.settings.openDonationUrl(
                                homeContext,
                                tf.monochrome.android.ui.settings.SupportLinks.KO_FI,
                            )
                        },
                        onPatreon = {
                            settingsViewModel.dismissDonatePrompt(neverAgain = false)
                            tf.monochrome.android.ui.settings.openDonationUrl(
                                homeContext,
                                tf.monochrome.android.ui.settings.SupportLinks.PATREON,
                            )
                        },
                        onDismiss = { neverAgain ->
                            settingsViewModel.dismissDonatePrompt(neverAgain)
                        },
                    )
                }

                tf.monochrome.android.ui.library.LibraryScreen(
                    navController = navController,
                    playerViewModel = playerViewModel,
                    sectionId = tf.monochrome.android.ui.library.OVERVIEW_SECTION,
                    pages = pages,
                    onSelectPage = onSelectPage,
                    embedded = true,
                )
        }
    }
}


// The About tab index used to be written down here as a literal and silently
// broke every time Settings was reordered. SETTINGS_TAB_ABOUT is derived from
// the tab list itself, so there is nothing left to keep in sync.
