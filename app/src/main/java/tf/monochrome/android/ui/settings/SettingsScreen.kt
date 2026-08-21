package tf.monochrome.android.ui.settings

import tf.monochrome.android.ui.theme.goToPage
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.BuildConfig
import java.util.Locale
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import tf.monochrome.android.R
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import tf.monochrome.android.ui.components.ColorPickerDialog
import tf.monochrome.android.ui.components.ColorSwatchRow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Button
import android.content.Context
import androidx.compose.ui.text.input.PasswordVisualTransformation
import tf.monochrome.android.domain.model.NowPlayingViewMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.domain.model.AudioQuality
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.rememberMotionFloat
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.text.style.TextOverflow
import tf.monochrome.android.ui.eq.EqViewModel
import tf.monochrome.android.ui.eq.EqProfileMiniGraph
import tf.monochrome.android.domain.model.EqPreset
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.theme.themeDisplayNames
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.LocalMiniPlayerInset
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.components.SearchOverlay
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.border
import tf.monochrome.android.ui.theme.lightSchemeFor
import tf.monochrome.android.ui.theme.Paper
import tf.monochrome.android.ui.theme.ColorBlend
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import kotlinx.coroutines.delay

// Ordered by how often they're reached for, not by how the code grew:
// the look of the app, then how it sounds, then what it plays, then the
// plumbing. "Interface" is gone — merged into Appearance, see AppearanceTab —
// and "Instances" + "Scrobbling" are gone, merged into Connections along with
// System's old "Account & Sync" group, so every account lives in one place.
//
// Positions are NOT stable across releases. Anything that needs to open a
// specific tab must go through a named constant derived from this list (see
// SETTINGS_TAB_ABOUT), never a literal — a hardcoded index has silently broken
// twice now, once per reorder.
private val settingsTabs = listOf("Appearance", "Audio", "Equalizer", "Library", "Downloads", "Connections", "Radio", "System", "About")

/**
 * Which tab carries a given label, for the search index to point at.
 *
 * Derived rather than written down, so reordering the tabs cannot leave every
 * search result landing one tab to the left. Throws on an unknown label, which
 * a test turns into a build failure rather than a result that goes nowhere.
 */
internal fun settingsTabIndex(label: String): Int =
    settingsTabs.indexOf(label).also {
        require(it >= 0) { "no settings tab called \"$label\"" }
    }

/**
 * Index of the About tab, where the What's New panel lives. Derived from
 * [settingsTabs] rather than written down, so reordering the tabs can't leave a
 * caller pointing at the wrong page.
 */
val SETTINGS_TAB_ABOUT: Int = settingsTabs.indexOf("About")

/** One selectable step in the Appearance › Font Size picker. */
private data class FontScalePreset(val label: String, val scale: Float)

// Five fixed steps replace the old 0.50-2.00 free slider. The range is
// deliberately narrower than before: below ~0.85 the mini player and lyrics
// clip, and above ~1.50 the settings rows and player controls start to
// overlap. Anyone who genuinely needs larger type should turn on "Use system
// font size", which honours the OS accessibility setting all the way up.
private val FONT_SCALE_PRESETS = listOf(
    FontScalePreset("Small", 0.85f),
    FontScalePreset("Default", 1.00f),
    FontScalePreset("Large", 1.15f),
    FontScalePreset("Larger", 1.30f),
    FontScalePreset("Largest", 1.50f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    initialTab: Int = 0,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    // Tabs are swipeable pages. The chip row is a selector onto the SAME pager
    // state rather than a second source of truth, so a swipe and a tap can't
    // disagree. rememberPagerState saves its own page across process death,
    // which is what the old rememberSaveable int was doing here.
    val settingsPager = rememberPagerState(
        initialPage = initialTab.coerceIn(0, settingsTabs.lastIndex),
        pageCount = { settingsTabs.size },
    )
    val settingsScope = rememberCoroutineScope()
    // Tab changes slide normally; with "Disable animations" on they jump.
    val animateTabs = !tf.monochrome.android.ui.theme.reduceMotion()
    val selectedTab = settingsPager.currentPage

    // Toast one-shot ViewModel messages (font import, backup import, …) from
    // one always-composed collector, regardless of which tab is showing.
    val messageContext = LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.messages.collect { msg ->
            android.widget.Toast.makeText(messageContext, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val settingsAnchors = remember { SettingsAnchors() }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val searchHits = remember(searchQuery) { searchSettings(searchQuery) }

    CompositionLocalProvider(LocalSettingsAnchors provides settingsAnchors) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) searchQuery = ""
                }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = if (searchOpen) "Close search" else "Find a setting",
                        tint = if (searchOpen) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current,
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        // Settings are nine tabs and a handful of screens of their own, which
        // is more places than anyone should have to remember. The index behind
        // this knows where each one lives, so a result can land on the tab that
        // holds it *or* open the screen it actually is.
        //
        // Tab row. A LazyRow rather than the old horizontalScroll(Row) so it can
        // scroll the selected chip into view — swiping out to About (last of nine)
        // would otherwise leave the highlighted chip off-screen behind you.
        //
        // Above the search rather than under it. The bar floats, and floating it
        // over the chips buried the one control that says which of the nine tabs
        // you are on — while searching, which is exactly when you are about to
        // be moved between them. The form below is what the glass should be
        // frosting; the tab rail is chrome, and chrome stays put.
        val chipRow = rememberLazyListState()
        LaunchedEffect(selectedTab) { chipRow.animateScrollToItem(selectedTab) }
        LazyRow(
            state = chipRow,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(settingsTabs) { index, tab ->
                FilterChip(
                    selected = selectedTab == index,
                    onClick = { settingsScope.launch { settingsPager.goToPage(index, animateTabs) } },
                    label = { Text(tab, style = MaterialTheme.typography.labelMedium) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        }

        // The bar floats over the *form* rather than pushing it down: laid out
        // as a row of this Column it shoved the whole form down the screen every
        // time it opened, and had the page's background behind it with nothing
        // to frost.
        SearchOverlay(
            open = searchOpen,
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Find a setting",
            onClose = { searchOpen = false; searchQuery = "" },
            modifier = Modifier.weight(1f),
            barContent = {
                if (searchQuery.trim().length >= 2) {
                    if (searchHits.isEmpty()) {
                        Text(
                            text = "No setting by that name.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(searchHits) { hit ->
                                SettingsHitPill(
                                    entry = hit,
                                    onClick = {
                                        searchOpen = false
                                        searchQuery = ""
                                        when (val d = hit.destination) {
                                            is SettingsDestination.Tab -> {
                                                // Ask before switching: the tab
                                                // reports the row's position as
                                                // it lays out, and a request
                                                // made afterwards would arrive
                                                // one frame too late.
                                                settingsAnchors.request(hit.title)
                                                settingsScope.launch {
                                                    settingsPager.goToPage(d.index, animateTabs)
                                                }
                                            }
                                            is SettingsDestination.Route ->
                                                navController.navigateSafe(d.route)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            },
        ) { searchTopInset ->
            // The form runs full height *under* the floating bar rather than
            // being pushed below it. Pushing it down left an empty strip behind
            // the glass, and a sheet of glass with nothing behind it to blur
            // paints its own base colour — the solid rectangle this used to show.
            // The inset instead becomes top padding on each tab's own scroll
            // (below), so real settings sit behind the glass and the first row
            // still starts clear of it.
            CompositionLocalProvider(LocalSettingsSearchInset provides searchTopInset) {
                HorizontalPager(
                    state = settingsPager,
                    modifier = Modifier.fillMaxWidth().fillMaxSize(),
                    // Each tab is a full settings form; keeping neighbours composed
                    // would mean building all nine of them up front.
                    beyondViewportPageCount = 0,
                ) { page ->
                    tf.monochrome.android.devedit.DevEditScreen("settings/${devSlug(settingsTabs[page])}") {
                        when (page) {
                            0 -> AppearanceTab(viewModel, navController)
                            1 -> AudioTab(viewModel, navController)
                            2 -> EqualizerTab(navController, viewModel)
                            3 -> LibrarySettingsTab(viewModel)
                            4 -> DownloadsTab(viewModel)
                            5 -> ConnectionsTab(viewModel)
                            6 -> tf.monochrome.android.ui.settings.radio.RadioSettingsTab()
                            7 -> SystemTab(viewModel, navController)
                            8 -> AboutTab(viewModel)
                        }
                    }
                }
            }
        }
    }
    }
}

// ─── Tab 4: Equalizer ──────────────────────────────────────────────────
@Composable
private fun EqualizerTab(
    navController: NavController,
    viewModel: SettingsViewModel,
    eqViewModel: EqViewModel = hiltViewModel(),
) {
    // Was a lone group on the Audio tab, one tab away from every other EQ
    // control. Whether the curve applies to the whole device is a question
    // about the equalizer, so it is asked here.
    val systemWideAutoEq by viewModel.systemWideAutoEqEnabled.collectAsStateWithLifecycle()
    val eqEnabled by eqViewModel.eqEnabled.collectAsStateWithLifecycle()
    val selectedTarget by eqViewModel.selectedTarget.collectAsStateWithLifecycle()
    val selectedHeadphone by eqViewModel.selectedHeadphone.collectAsStateWithLifecycle()
    val allPresets by eqViewModel.allPresets.collectAsStateWithLifecycle()
    val activePreset by eqViewModel.activePreset.collectAsStateWithLifecycle()
    var presetToDelete by remember { mutableStateOf<EqPreset?>(null) }

    SettingsTabContent {
        SettingsGroupHeader("Equalizer")
        SettingSwitchItem(
            title = "System-wide AutoEQ",
            subtitle = "Apply your AutoEQ + tone to all device audio (device-permitting)",
            checked = systemWideAutoEq,
            onCheckedChange = viewModel::setSystemWideAutoEq,
        )
        SettingSwitchItem(
            title = "Enable Equalizer",
            subtitle = "Apply EQ processing to playback",
            checked = eqEnabled,
            onCheckedChange = { eqViewModel.toggleEq() }
        )

        Spacer(modifier = Modifier.height(12.dp))

        SettingItem(
            title = "Target Curve",
            subtitle = selectedTarget.label,
        )

        SettingItem(
            title = "Headphone",
            subtitle = selectedHeadphone?.name ?: "None selected",
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Navigate straight to the tool. These used to re-enter Settings on a
        // hardcoded `settings?tab=4` first, to force Back to land on this tab
        // — which broke both buttons twice over. The rewritten Settings entry
        // was not RESUMED yet when navigateTool ran, so its isSettled() guard
        // swallowed the second call and the EQ page never opened; and tab 4 is
        // Downloads now, not Equalizer. Nothing is lost by dropping it: the
        // pager state is saveable, so Back restores the tab you left from.
        OutlinedButton(
            onClick = { navController.navigateTool(Screen.Equalizer) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Precision AutoEQ")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { navController.navigateTool(Screen.ParametricEq) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Open Parametric EQ")
        }

        // ─── Saved Profiles ───
        if (allPresets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            SettingsGroupHeader("Saved Profiles")

            allPresets.forEach { preset ->
                val isActive = activePreset?.id == preset.id
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .liquidGlass(shape = RoundedCornerShape(10.dp))
                        .bounceClick(onClick = { eqViewModel.loadPreset(preset.id) })
                ) {
                    EqProfileMiniGraph(
                        bands = preset.bands,
                        preamp = preset.preamp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isActive) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = "Active",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                preset.name,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${preset.bands.size} bands · ${preset.targetName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (preset.isCustom) {
                            IconButton(
                                onClick = { presetToDelete = preset },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete preset",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    presetToDelete?.let { preset ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            title = { Text("Delete Profile") },
            text = { Text("Delete \"${preset.name}\"?") },
            confirmButton = {
                TextButton(onClick = {
                    eqViewModel.deletePreset(preset.id)
                    presetToDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

// ─── Tab 1: Appearance ─────────────────────────────────────────────────
//
// Appearance and Interface used to be two tabs, and the split never held up:
// "Interface" ended up as the place a setting went when no tab obviously owned
// it — theme and typography on one page, then the fonts' own display toggles,
// the now-playing look, the visualizer and the graphics settings on another.
// They are one page now, in reading order from the app-wide look down to the
// individual surfaces. The one group that genuinely belonged elsewhere,
// Playback, moved to Audio.
@Composable
private fun AppearanceTab(viewModel: SettingsViewModel, navController: NavController) {
    SettingsTabContent {
        AppearanceControls(viewModel)
        Spacer(modifier = Modifier.height(16.dp))
        InterfaceControls(viewModel, navController)
    }
}

/** Which of the two custom colours a picker is open for. */
private enum class CustomColorTarget { Accent, Background }

@Composable
private fun AppearanceControls(viewModel: SettingsViewModel) {
    val themeName by viewModel.theme.collectAsStateWithLifecycle()
    val dynamicColors by viewModel.dynamicColors.collectAsStateWithLifecycle()
    val dynamicColorMenus by viewModel.dynamicColorMenus.collectAsStateWithLifecycle()
    val dynamicColorKeepBackground by viewModel.dynamicColorKeepBackground.collectAsStateWithLifecycle()
    val colorTransitionMs by viewModel.colorTransitionMs.collectAsStateWithLifecycle()
    // Only to show what "Match blend" currently works out to; the slider does
    // not change it.
    val crossfadeSeconds by viewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val themePaper by viewModel.themePaper.collectAsStateWithLifecycle()
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    val customFontUri by viewModel.customFontUri.collectAsStateWithLifecycle()
    val availableFonts by viewModel.availableFonts.collectAsStateWithLifecycle()
    val followSystemFontScale by viewModel.fontScaleFollowSystem.collectAsStateWithLifecycle()
    val glowBehindArt by viewModel.glowBehindArt.collectAsStateWithLifecycle()
    val customThemeEnabled by viewModel.customThemeEnabled.collectAsStateWithLifecycle()
    val customAccent by viewModel.customAccentColor.collectAsStateWithLifecycle()
    val customBackground by viewModel.customBackgroundColor.collectAsStateWithLifecycle()
    var showThemeDropdown by remember { mutableStateOf(false) }
    // Which picker is open, if any — accent or ground.
    var editingColor by remember { mutableStateOf<CustomColorTarget?>(null) }

    // File picker for .ttf font import
    val context = LocalContext.current
    val fontPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importFont(it) }
    }

        SettingsGroupHeader("Theme")
        SettingItem(title = "Color Theme", subtitle = themeDisplayNames[themeName] ?: themeName, onClick = { showThemeDropdown = true })
        DropdownMenu(expanded = showThemeDropdown, onDismissRequest = { showThemeDropdown = false }) {
            themeDisplayNames.forEach { (key, displayName) ->
                DropdownMenuItem(text = { Text(displayName) }, onClick = { viewModel.setTheme(key); showThemeDropdown = false })
            }
        }
        // Every light theme is printed on one of two papers. It is a choice
        // about glare rather than about any one theme, so it is one control
        // here rather than a variant of each of the fifteen.
        LightPaperSetting(
            paper = themePaper,
            onPaperChange = { viewModel.setThemePaper(it) },
        )

        // Directly under the paper swatches, because it is the other half of the
        // same question: what the app looks like, and how long it takes to get
        // there when the track changes.
        ColorTransitionSetting(
            millis = colorTransitionMs,
            blendSeconds = crossfadeSeconds,
            onMillisChange = { viewModel.setColorTransitionMs(it) },
        )

        // Custom colours. When on, the two swatches below build the whole app's
        // scheme and the preset above is ignored — light or dark is decided by
        // the ground's own brightness, and every foreground is floored for
        // contrast against it, so even a poorly chosen pair stays legible.
        SettingSwitchItem(
            title = "Custom colors",
            subtitle = "Pick your own accent and background. Overrides the theme above",
            checked = customThemeEnabled,
            onCheckedChange = { viewModel.setCustomThemeEnabled(it) },
        )
        AnimatedVisibility(visible = customThemeEnabled) {
            Column {
                ColorSwatchRow(
                    label = "Accent",
                    color = Color(customAccent),
                    onClick = { editingColor = CustomColorTarget.Accent },
                )
                ColorSwatchRow(
                    label = "Background",
                    color = Color(customBackground),
                    onClick = { editingColor = CustomColorTarget.Background },
                )
            }
        }
        when (editingColor) {
            CustomColorTarget.Accent -> ColorPickerDialog(
                initial = Color(customAccent),
                title = "Accent color",
                onDismiss = { editingColor = null },
                onConfirm = {
                    viewModel.setCustomAccentColor(it.toArgb())
                    editingColor = null
                },
            )
            CustomColorTarget.Background -> ColorPickerDialog(
                initial = Color(customBackground),
                title = "Background color",
                onDismiss = { editingColor = null },
                onConfirm = {
                    viewModel.setCustomBackgroundColor(it.toArgb())
                    editingColor = null
                },
            )
            null -> Unit
        }
        SettingSwitchItem(
            title = "Dynamic Colors",
            subtitle = "Tint the player, mini player and lyrics from album art. The menus keep the theme color. Off, everything uses the theme color",
            checked = dynamicColors,
            onCheckedChange = { viewModel.setDynamicColors(it) }
        )
        // The two below only mean anything once there is a palette to spend, so
        // they live inside the switch that produces one rather than sitting
        // greyed out beside it.
        AnimatedVisibility(visible = dynamicColors) {
            Column {
                SettingSwitchItem(
                    title = "Tint the menus too",
                    subtitle = "Let the cover set the app's accent and background everywhere, not just the player",
                    checked = dynamicColorMenus,
                    onCheckedChange = { viewModel.setDynamicColorMenus(it) },
                )
                AnimatedVisibility(visible = dynamicColorMenus) {
                    SettingSwitchItem(
                        title = "Keep the theme background",
                        subtitle = "Accent only. The background stays the color your theme chose",
                        checked = dynamicColorKeepBackground,
                        onCheckedChange = { viewModel.setDynamicColorKeepBackground(it) },
                    )
                }
            }
        }
        SettingSwitchItem(
            title = "Glow behind album art",
            subtitle = "Bloom the bass-reactive glow around the album cover too, pumping with the kick.",
            checked = glowBehindArt,
            onCheckedChange = { viewModel.setGlowBehindArt(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Typography")

        // Font size: five fixed steps, or hand over to the OS setting.
        SettingSwitchItem(
            title = "Use system font size",
            subtitle = "Follow the size set in Android Settings › Display › Font size",
            checked = followSystemFontScale,
            onCheckedChange = { viewModel.setFontScaleFollowSystem(it) }
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "Font Size",
                style = MaterialTheme.typography.bodyLarge,
                color = if (followSystemFontScale) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface
            )

            // A legacy value from the old free-form slider (e.g. 1.37) won't equal
            // any step, so select the closest one instead of leaving nothing
            // highlighted. Picking a chip then writes the exact step back.
            val selectedPreset = remember(fontScale) {
                FONT_SCALE_PRESETS.minByOrNull { kotlin.math.abs(it.scale - fontScale) }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FONT_SCALE_PRESETS.forEach { preset ->
                    FilterChip(
                        selected = !followSystemFontScale && preset == selectedPreset,
                        enabled = !followSystemFontScale,
                        onClick = { viewModel.setFontScale(preset.scale) },
                        label = { Text(preset.label) }
                    )
                }
            }

            Text(
                "Preview: The quick brown fox jumps over the lazy dog",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Font Library. Collapsed by default: with ten bundled faces plus
        // anything imported it is the longest thing on this tab, and it is not
        // what most people opened Appearance for. The header carries the active
        // font's name so the section still answers "what am I using?" shut.
        var fontLibraryExpanded by rememberSaveable { mutableStateOf(false) }
        val activeFontName = tf.monochrome.android.ui.theme.BundledFonts
            .displayNameOf(customFontUri) ?: "Inter (default)"

        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { fontLibraryExpanded = !fontLibraryExpanded }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Font Library",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        activeFontName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    imageVector = if (fontLibraryExpanded) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (fontLibraryExpanded) "Collapse font library"
                        else "Expand font library",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (fontLibraryExpanded) {
                // The built-in default, so switching back is a pick like any
                // other rather than a separate "Reset" the user has to find.
                FontRow(
                    name = "Inter",
                    note = "The default. Neutral UI grotesque.",
                    selected = customFontUri == null,
                    onSelect = { viewModel.resetDefaultFont() },
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Included",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
                viewModel.bundledFonts.forEach { font ->
                    val id = tf.monochrome.android.ui.theme.BundledFonts.idOf(font)
                    FontRow(
                        name = font.displayName,
                        note = font.note,
                        selected = customFontUri == id,
                        onSelect = { viewModel.selectBundledFont(font) },
                    )
                }

                if (availableFonts.isNotEmpty()) {
                    Text(
                        "Imported",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    availableFonts.forEach { file ->
                        FontRow(
                            name = file.nameWithoutExtension,
                            note = null,
                            selected = file.absolutePath == customFontUri,
                            onSelect = { viewModel.selectFont(file) },
                            onDelete = { viewModel.removeFont(file) },
                        )
                    }
                }

                OutlinedButton(
                    onClick = {
                        fontPickerLauncher.launch(
                            arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "application/octet-stream")
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text("Import a font (.ttf / .otf)")
                }
            }
        }
    
}

/**
 * One row in the Font Library — the default, a bundled face, or an import.
 * [onDelete] is only passed for imports; bundled fonts live in the APK and
 * have nothing to delete.
 */
@Composable
private fun FontRow(
    name: String,
    note: String?,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Check,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
            )
            if (note != null) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete $name",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun InterfaceControls(viewModel: SettingsViewModel, navController: NavController) {
    val explicit by viewModel.showExplicitBadges.collectAsStateWithLifecycle()
    val sensitivity by viewModel.visualizerSensitivity.collectAsStateWithLifecycle()
    val brightness by viewModel.visualizerBrightness.collectAsStateWithLifecycle()
    val engineEnabled by viewModel.visualizerEngineEnabled.collectAsStateWithLifecycle()
    val autoShuffle by viewModel.visualizerAutoShuffle.collectAsStateWithLifecycle()
    val presetId by viewModel.visualizerPresetId.collectAsStateWithLifecycle()
    val rotationSeconds by viewModel.visualizerRotationSeconds.collectAsStateWithLifecycle()
    val textureSize by viewModel.visualizerTextureSize.collectAsStateWithLifecycle()
    val meshX by viewModel.visualizerMeshX.collectAsStateWithLifecycle()
    val meshY by viewModel.visualizerMeshY.collectAsStateWithLifecycle()
    val targetFps by viewModel.visualizerTargetFps.collectAsStateWithLifecycle()
    val vsyncEnabled by viewModel.visualizerVsyncEnabled.collectAsStateWithLifecycle()
    val showFps by viewModel.visualizerShowFps.collectAsStateWithLifecycle()
    val fullscreen by viewModel.visualizerFullscreen.collectAsStateWithLifecycle()
    val touchWaveform by viewModel.visualizerTouchWaveform.collectAsStateWithLifecycle()
    val engineStatus by viewModel.visualizerEngineStatus.collectAsStateWithLifecycle()
    val presets by viewModel.visualizerPresets.collectAsStateWithLifecycle()
    val spectrumEnabled by viewModel.spectrumAnalyzerEnabled.collectAsStateWithLifecycle()
    val spectrumShowOnNowPlaying by viewModel.spectrumShowOnNowPlaying.collectAsStateWithLifecycle()
    val spectrumFftSize by viewModel.spectrumFftSize.collectAsStateWithLifecycle()
    val spectrumBins by viewModel.spectrumBins.collectAsStateWithLifecycle()
    val playerDynamicColor by viewModel.playerDynamicColor.collectAsStateWithLifecycle()
    val playerBlurredBackground by viewModel.playerBlurredBackground.collectAsStateWithLifecycle()
    val selectedPresetName = presets.firstOrNull { it.id == presetId }?.displayName ?: "Auto-select bundled preset"
    var showTextureDropdown by remember { mutableStateOf(false) }
    var showPresetDropdown by remember { mutableStateOf(false) }
    var showFftDropdown by remember { mutableStateOf(false) }

    // Presets install lazily; make sure the preset dropdown has data.
    LaunchedEffect(Unit) {
        viewModel.prepareVisualizerEngine()
    }

        SettingsGroupHeader("Display")
        SettingSwitchItem(
            title = "Show Explicit Badges",
            subtitle = "Display 'E' badge on explicit tracks",
            checked = explicit,
            onCheckedChange = { viewModel.setShowExplicitBadges(it) }
        )
        val romaji by viewModel.romajiLyrics.collectAsStateWithLifecycle()
        SettingSwitchItem(
            title = "Romaji Lyrics",
            subtitle = "Transliterate Japanese lyrics to Latin characters",
            checked = romaji,
            onCheckedChange = { viewModel.setRomajiLyrics(it) }
        )

        // Word-level lyrics provider — which karaoke-timing source(s) run when
        // TIDAL has no synced lyrics. "Both" tries NetEase first, then Kugou.
        val lyricsProvider by viewModel.lyricsWordProvider.collectAsStateWithLifecycle()
        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(
                text = "Word-level lyrics provider",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Karaoke-timing source when your instance has no synced lyrics. Both = each falls back to the other.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            val providerOptions = listOf(
                tf.monochrome.android.data.preferences.LyricsWordProvider.NETEASE_ONLY,
                tf.monochrome.android.data.preferences.LyricsWordProvider.KUGOU_ONLY,
                tf.monochrome.android.data.preferences.LyricsWordProvider.BOTH,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                providerOptions.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = lyricsProvider == mode,
                        onClick = { viewModel.setLyricsWordProvider(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index, providerOptions.size),
                    ) {
                        Text(mode.displayName)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Now Playing")
        val viewMode by viewModel.nowPlayingViewMode.collectAsStateWithLifecycle()
        var showModeDropdown by remember { mutableStateOf(false) }
        SettingItem(
            title = "View Mode", 
            subtitle = "Action when clicking album art: ${viewMode.displayName}", 
            onClick = { showModeDropdown = true }
        )
        DropdownMenu(expanded = showModeDropdown, onDismissRequest = { showModeDropdown = false }) {
            NowPlayingViewMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.displayName) },
                    onClick = { viewModel.setNowPlayingViewMode(mode); showModeDropdown = false }
                )
            }
        }
        SettingSwitchItem(
            title = "Dynamic Player Color",
            subtitle = "Tint the player from album art (needs Dynamic Colors on); off keeps the player on the theme color",
            checked = playerDynamicColor,
            onCheckedChange = { viewModel.setPlayerDynamicColor(it) }
        )
        SettingSwitchItem(
            title = "Blurred Album Background",
            subtitle = "Behind the player and the mixer: the album art stretched and heavily blurred",
            checked = playerBlurredBackground,
            onCheckedChange = { viewModel.setPlayerBlurredBackground(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Now Playing Appearance")
        SettingItem(
            title = "Player Visuals Studio",
            subtitle = "Live editor for the lyric type / 3D wave / beat FX, plus the player and mini-player glass",
            onClick = { navController.navigateTool(Screen.LyricsFxStudio) },
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Spectrum Analyzer")
        SettingSwitchItem(
            title = "Show Spectrum Analyzer",
            subtitle = "Display live audio spectrum on the player and the parametric EQ screen",
            checked = spectrumEnabled,
            onCheckedChange = { viewModel.setSpectrumAnalyzerEnabled(it) }
        )
        SettingSwitchItem(
            title = "Show on Now Playing",
            subtitle = "Overlay the spectrum on the album-art hero",
            checked = spectrumShowOnNowPlaying,
            onCheckedChange = { viewModel.setSpectrumShowOnNowPlaying(it) }
        )
        val fftLabel = when (spectrumFftSize) {
            4096 -> "Low (4096)"
            16384 -> "High (16384)"
            else -> "Medium (8192)"
        }
        SettingItem(
            title = "FFT Size",
            subtitle = fftLabel,
            onClick = { showFftDropdown = true }
        )
        DropdownMenu(expanded = showFftDropdown, onDismissRequest = { showFftDropdown = false }) {
            listOf(
                4096 to "Low (4096)",
                8192 to "Medium (8192)",
                16384 to "High (16384)"
            ).forEach { (size, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        viewModel.setSpectrumFftSize(size)
                        showFftDropdown = false
                    }
                )
            }
        }
        if (spectrumEnabled) {
            androidx.compose.runtime.DisposableEffect(Unit) {
                viewModel.acquireSpectrum()
                onDispose { viewModel.releaseSpectrum() }
            }
            Spacer(modifier = Modifier.height(8.dp))
            tf.monochrome.android.ui.player.SpectrumOverlay(
                bins = spectrumBins,
                color = MaterialTheme.colorScheme.primary,
                height = 96.dp
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Audio Visualizer")
        SettingSwitchItem(
            title = "Use projectM Visualizer",
            subtitle = "Use the native OpenGL renderer when the bridge is available",
            checked = engineEnabled,
            onCheckedChange = { viewModel.setVisualizerEngineEnabled(it) }
        )
        SettingSwitchItem(
            title = "Auto-shuffle Presets",
            subtitle = "Rotate bundled presets automatically during playback",
            checked = autoShuffle,
            onCheckedChange = { viewModel.setVisualizerAutoShuffle(it) }
        )
        SettingItem(
            title = "Default Preset",
            subtitle = selectedPresetName,
            onClick = { showPresetDropdown = true }
        )
        DropdownMenu(expanded = showPresetDropdown, onDismissRequest = { showPresetDropdown = false }) {
            DropdownMenuItem(
                text = { Text("Auto-select bundled preset") },
                onClick = {
                    viewModel.setVisualizerPresetId(null)
                    showPresetDropdown = false
                }
            )
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = { Text(preset.displayName) },
                    onClick = {
                        viewModel.setVisualizerPresetId(preset.id)
                        showPresetDropdown = false
                    }
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Visualizer Graphics")
        
        SettingItem(
            title = "Texture Size",
            subtitle = "$textureSize",
            onClick = { showTextureDropdown = true }
        )
        DropdownMenu(expanded = showTextureDropdown, onDismissRequest = { showTextureDropdown = false }) {
            listOf(256, 512, 1024, 2048, 4096).forEach { size ->
                DropdownMenuItem(
                    text = { Text(size.toString()) },
                    onClick = {
                        viewModel.setVisualizerTextureSize(size)
                        showTextureDropdown = false
                    }
                )
            }
        }
        
        IntSettingSlider(
            value = meshX,
            valueRange = 8f..128f,
            onCommit = { viewModel.setVisualizerMeshX(it) },
            label = { "Mesh X: $it" },
        )

        IntSettingSlider(
            value = meshY,
            valueRange = 8f..128f,
            onCommit = { viewModel.setVisualizerMeshY(it) },
            label = { "Mesh Y: $it" },
        )

        IntSettingSlider(
            value = targetFps,
            valueRange = 30f..144f,
            onCommit = { viewModel.setVisualizerTargetFps(it) },
            label = { if (vsyncEnabled) "Target FPS: $it" else "Target FPS: $it (vsync off)" },
        )

        SettingSwitchItem(
            title = "Disable vsync",
            subtitle = "Let the visualizer exceed display refresh (capped by Target FPS). Increases battery and heat. Adreno honours this; some GPUs ignore it.",
            checked = !vsyncEnabled,
            onCheckedChange = { viewModel.setVisualizerVsyncEnabled(!it) }
        )

        SettingSwitchItem(
            title = "Show FPS",
            subtitle = "Display visualizer framerate counter",
            checked = showFps,
            onCheckedChange = { viewModel.setVisualizerShowFps(it) }
        )

        SettingSwitchItem(
            title = "Fullscreen",
            subtitle = "Fill screen in Now Playing visualizer view",
            checked = fullscreen,
            onCheckedChange = { viewModel.setVisualizerFullscreen(it) }
        )
        SettingSwitchItem(
            title = "Touch Waveform",
            subtitle = "Draw audio waveforms between touch points on the visualizer",
            checked = touchWaveform,
            onCheckedChange = { viewModel.setVisualizerTouchWaveform(it) }
        )
        SettingItem(
            title = "Engine Status",
            subtitle = "${engineStatus.badge} • assets ${engineStatus.assetVersion}",
            onClick = {}
        )

        IntSettingSlider(
            value = rotationSeconds,
            valueRange = 5f..120f,
            onCommit = { viewModel.setVisualizerRotationSeconds(it) },
            label = { "Preset Rotation: ${it}s" },
        )

        IntSettingSlider(
            value = sensitivity,
            valueRange = 0f..100f,
            onCommit = { viewModel.setVisualizerSensitivity(it) },
            label = { "Sensitivity: $it%" },
            subtitle = "Controls intensity (High = Epilepsy Warning)",
        )

        IntSettingSlider(
            value = brightness,
            valueRange = 0f..100f,
            onCommit = { viewModel.setVisualizerBrightness(it) },
            label = { "Brightness: $it%" },
        )

        Spacer(modifier = Modifier.height(16.dp))
    
}

/**
 * Integer settings slider that writes its value once, on release, instead of on
 * every drag frame. A local float drives the label and thumb live; [onCommit]
 * (a DataStore write) fires only from onValueChangeFinished. The local state is
 * re-seeded whenever [value] changes externally so it stays in sync.
 */
@Composable
private fun IntSettingSlider(
    value: Int,
    valueRange: ClosedFloatingPointRange<Float>,
    onCommit: (Int) -> Unit,
    label: (Int) -> String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        label(local.toInt()),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (subtitle != null) {
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { onCommit(local.toInt()) },
        valueRange = valueRange,
        modifier = modifier.fillMaxWidth(),
    )
}

// ─── Tab 3: Scrobbling ─────────────────────────────────────────────────
/**
 * Last.fm + ListenBrainz rows, unwrapped from any scroll container so
 * [ConnectionsTab] can compose them alongside the other account groups —
 * [SettingsTabContent] is a LazyColumn and two of those cannot nest.
 *
 * Depends on nothing but [viewModel]: the two dialogs below own all of their
 * own state, so this block is safe to place anywhere.
 */
@Composable
private fun ScrobblingControls(viewModel: SettingsViewModel) {
    val lastFmEnabled by viewModel.lastFmEnabled.collectAsStateWithLifecycle()
    val lastFmUsername by viewModel.lastFmUsername.collectAsStateWithLifecycle()
    val lbEnabled by viewModel.listenBrainzEnabled.collectAsStateWithLifecycle()
    val lbToken by viewModel.listenBrainzToken.collectAsStateWithLifecycle()
    val apiKey by viewModel.lastFmApiKey.collectAsStateWithLifecycle()
    val apiSecret by viewModel.lastFmApiSecret.collectAsStateWithLifecycle()
    val chartsKeyAvailable by viewModel.chartsKeyAvailable.collectAsStateWithLifecycle()

    val lastFmConnecting by viewModel.lastFmConnecting.collectAsStateWithLifecycle()
    val lastFmAuthError by viewModel.lastFmAuthError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var showLbDialog by rememberSaveable { mutableStateOf(false) }
    var showApiKeyDialog by rememberSaveable { mutableStateOf(false) }

    if (showApiKeyDialog) {
        var keyInput by rememberSaveable { mutableStateOf(apiKey) }
        var secretInput by rememberSaveable { mutableStateOf(apiSecret) }
        AlertDialog(
            onDismissRequest = { showApiKeyDialog = false },
            title = { Text("Your Last.fm API key") },
            text = {
                Column {
                    Text(
                        // Two different things share the word "key" here, and a
                        // listener who conflates them gets silent failures. This
                        // is an *application* key, not the session key above and
                        // not an account login.
                        "Needed to scrobble. Scrobbles are signed with your own " +
                            "secret and posted to your own listening history, so " +
                            "they use your credentials rather than a key shared " +
                            "by everyone. Create a pair free at " +
                            "last.fm/api/account/create.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Genre charts don't need this. They read public data " +
                            "with a key built into the app. Entering one here " +
                            "makes charts use yours instead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Without this in the registration, Last.fm shows a page
                    // asking the listener to return to the app by hand and
                    // never redirects — the connection just never completes,
                    // with nothing on either side saying why.
                    Text(
                        "Set the application's Callback URL to " +
                            viewModel.lastFmCallbackUrl +
                            " so authorising returns to Tryptify.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyInput, onValueChange = { keyInput = it },
                        label = { Text("API key") }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secretInput, onValueChange = { secretInput = it },
                        label = { Text("Shared secret") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setLastFmApiCredentials(keyInput, secretInput)
                    showApiKeyDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showLbDialog) {
        var tokenInput by rememberSaveable { mutableStateOf(lbToken ?: "") }
        AlertDialog(
            onDismissRequest = { showLbDialog = false },
            title = { Text("ListenBrainz Token") },
            text = {
                OutlinedTextField(
                    value = tokenInput, onValueChange = { tokenInput = it },
                    label = { Text("User Token") }, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tokenInput.isNotBlank()) viewModel.setListenBrainzToken(tokenInput)
                    else viewModel.clearListenBrainzToken()
                    showLbDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showLbDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsGroupHeader("Last.fm")
    SettingItem(
        title = "Your API key",
        subtitle = when {
            apiKey.isNotBlank() && apiSecret.isNotBlank() ->
                "Set. Scrobbling can sign as you"
            apiKey.isNotBlank() ->
                "Key set, secret missing. Scrobbling still can't sign"
            chartsKeyAvailable ->
                "Not set. Charts use the built-in key; scrobbling needs yours"
            else ->
                "Not set. Needed for scrobbling and for all-time charts"
        },
        onClick = { showApiKeyDialog = true }
    )
    SettingItem(
        title = "Last.fm",
        subtitle = when {
            lastFmEnabled -> "Connected as ${lastFmUsername ?: "user"}"
            lastFmConnecting -> "Waiting for Last.fm…"
            else -> "Not connected. Tap to authorise in your browser"
        },
        // No text box. A session key is not something a person has — it comes
        // out of auth.getSession, which needs the browser handshake this
        // starts. Asking anyone to paste one was asking for something only a
        // developer with a terminal could produce.
        onClick = { if (!lastFmEnabled) viewModel.connectLastFm(context) }
    )
    if (lastFmEnabled) {
        TextButton(onClick = { viewModel.clearLastFmSession() }) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    }
    lastFmAuthError?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(onClick = { viewModel.clearLastFmError() }) { Text("Dismiss") }
    }

    Spacer(modifier = Modifier.height(16.dp))
    SettingsGroupHeader("ListenBrainz")
    SettingItem(
        title = "ListenBrainz",
        subtitle = if (lbEnabled) "Connected" else "Not connected",
        onClick = { showLbDialog = true }
    )
    if (lbEnabled) {
        TextButton(onClick = { viewModel.clearListenBrainzToken() }) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    }
}

// ─── Tab 4: Audio ──────────────────────────────────────────────────────
@Composable
private fun AudioTab(viewModel: SettingsViewModel, navController: NavController) {
    // Moved here from "Interface". What happens between one track and the next
    // is an audio decision, not an interface one — it sat under Interface only
    // because that tab had become the place settings went when no tab obviously
    // owned them.
    val gapless by viewModel.gaplessPlayback.collectAsStateWithLifecycle()
    val crossfade by viewModel.crossfadeDuration.collectAsStateWithLifecycle()
    val gaplessNoResample by viewModel.gaplessNoResample.collectAsStateWithLifecycle()
    val wifiQuality by viewModel.wifiQuality.collectAsStateWithLifecycle()
    val cellularQuality by viewModel.cellularQuality.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val preservePitch by viewModel.preservePitch.collectAsStateWithLifecycle()
    var showWifiDropdown by remember { mutableStateOf(false) }
    var showCellularDropdown by remember { mutableStateOf(false) }
    // Plain local state (NOT keyed on playbackSpeed) so typing isn't reset by
    // the value round-tripping back from the ViewModel; sync from external
    // changes (slider/reset) only while the field is unfocused, and commit on
    // Done / focus-loss instead of on every keystroke (which dropped playback
    // to 0.01x mid-entry).
    var speedText by remember { mutableStateOf(String.format(Locale.US, "%.2f", playbackSpeed)) }
    var speedFocused by remember { mutableStateOf(false) }
    LaunchedEffect(playbackSpeed) {
        if (!speedFocused) speedText = String.format(Locale.US, "%.2f", playbackSpeed)
    }
    val commitSpeed = {
        val parsed = speedText.toFloatOrNull()?.coerceIn(0.01f, 100f)
        if (parsed != null) {
            viewModel.setPlaybackSpeed(parsed)
            speedText = String.format(Locale.US, "%.2f", parsed)
        } else {
            speedText = String.format(Locale.US, "%.2f", playbackSpeed)
        }
    }

    SettingsTabContent {
        SettingsGroupHeader("Playback")
        SettingSwitchItem(
            title = "Gapless Playback",
            subtitle = "Remove silence between tracks",
            checked = gapless,
            onCheckedChange = { viewModel.setGaplessPlayback(it) }
        )

        SettingSwitchItem(
            title = "Never Resample Between Tracks",
            subtitle = "A track at a different sample rate starts after a brief gap " +
                "instead of being resampled to match, keeping output bit-perfect. " +
                "Turn off to keep every transition seamless and let the system resample.",
            checked = gaplessNoResample,
            onCheckedChange = { viewModel.setGaplessNoResample(it) }
        )

        // Sits under the gapless toggle because the two decide the same thing:
        // what happens between one track and the next. They're also mutually
        // exclusive in effect — any blend above 0s overlaps the tracks, so the
        // gapless hand-off steps aside for it.
        Text(
            text = if (crossfade == 0) "Blend Between Tracks: Gapless" else "Blend Between Tracks: ${crossfade}s",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (crossfade == 0) {
                "At zero, tracks run straight into each other with no gap. " +
                    "Add time to overlap them instead."
            } else {
                "The outgoing track fades out while the next fades in over ${crossfade}s."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = crossfade.toFloat(),
            onValueChange = { viewModel.setCrossfadeDuration(it.toInt()) },
            valueRange = 0f..12f,
            steps = 11,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Streaming Quality")
        SettingItem(title = "Wi-Fi Streaming", subtitle = wifiQuality.displayName, onClick = { showWifiDropdown = true })
        DropdownMenu(expanded = showWifiDropdown, onDismissRequest = { showWifiDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setWifiQuality(q); showWifiDropdown = false })
            }
        }

        SettingItem(title = "Cellular Streaming", subtitle = cellularQuality.displayName, onClick = { showCellularDropdown = true })
        DropdownMenu(expanded = showCellularDropdown, onDismissRequest = { showCellularDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setCellularQuality(q); showCellularDropdown = false })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Audio Processing")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = { navController.navigateTool(Screen.Oxford, "oxford?tab=0") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Seap Compressor")
            }
            OutlinedButton(
                onClick = { navController.navigateTool(Screen.Oxford, "oxford?tab=1") },
                modifier = Modifier.weight(1f),
            ) {
                Text("Seap Inflator")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Spatial Audio")
        SettingItem(
            title = "Atmos Renderer Configuration",
            subtitle = "Channel map, coefficient downmix & optional SOFA binaural render",
            onClick = { navController.navigateTool(Screen.AtmosRenderer) },
        )

        // Everything below used to sit under "Spatial Audio" too, which only
        // ever described the Atmos row above it. The DSP block size, the USB
        // routing and the downmix are what leaves the engine and how — their
        // own group.
        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Output")
        DspBlockSizeSelector(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
        UsbBitPerfectToggle(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
        MultichannelDownmixToggle(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
        ChannelDetectorCard(viewModel)

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Playback Speed")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Slider(
                value = playbackSpeed,
                onValueChange = { newSpeed ->
                    val rounded = (Math.round(newSpeed * 100) / 100f)
                    speedText = String.format(Locale.US, "%.2f", rounded)
                    viewModel.setPlaybackSpeed(rounded)
                },
                valueRange = 0.25f..3.0f,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = speedText,
                onValueChange = { speedText = it },
                modifier = Modifier
                    .width(80.dp)
                    .onFocusChanged {
                        if (!it.isFocused && speedFocused) commitSpeed()
                        speedFocused = it.isFocused
                    },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commitSpeed() }),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Text("x", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            TextButton(onClick = {
                viewModel.setPlaybackSpeed(1.0f)
                speedText = "1.00"
            }) {
                Text("Reset")
            }
        }

        SettingSwitchItem(
            title = "Preserve Pitch",
            subtitle = "Keep original pitch when changing speed",
            checked = preservePitch,
            onCheckedChange = { viewModel.setPreservePitch(it) }
        )
    }
}



// User-facing DSP buffer (block) size selector. Smaller = lower latency
// + more JNI / native overhead per second; larger = lower CPU at the
// cost of slightly later parameter response. Mirrors the buffer-size
// dropdown most pro-audio apps expose.
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun DspBlockSizeSelector(viewModel: SettingsViewModel) {
    val current by viewModel.dspBlockSize.collectAsStateWithLifecycle()
    Text(
        text = "DSP Block Size",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = "Smaller = lower latency, more CPU. Default 1024.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        viewModel.dspBlockSizes.forEach { size ->
            FilterChip(
                selected = size == current,
                onClick = { viewModel.setDspBlockSize(size) },
                label = { Text(formatBlockSize(size)) },
            )
        }
    }
}

/** "8192" → "8K", "16384" → "16K" so the chip row stays readable. */
private fun formatBlockSize(size: Int): String = when {
    size >= 1024 && size % 1024 == 0 -> "${size / 1024}K"
    else -> size.toString()
}

/**
 * Multichannel (5.1/7.1) handling: fold down to stereo through the DSP/EQ
 * chain (default), or pass multichannel PCM through to the device with
 * DSP/EQ bypassed for those tracks. Applies from the next track / seek.
 */
@Composable
private fun MultichannelDownmixToggle(viewModel: SettingsViewModel) {
    val enabled by viewModel.multichannelDownmixEnabled.collectAsStateWithLifecycle()
    SettingSwitchItem(
        title = "Downmix multichannel to stereo",
        subtitle = if (enabled) {
            "Multichannel tracks fold into stereo (fixed matrix) and run through DSP/EQ."
        } else {
            "Off. Multichannel passes to the device untouched; DSP/EQ bypassed for those tracks."
        },
        checked = enabled,
        onCheckedChange = { viewModel.setMultichannelDownmixEnabled(it) },
    )
}

/**
 * Debug screen recorder: captures the display at its native panel
 * resolution and refresh rate with the app's own media audio recorded
 * digitally in stereo (AudioPlaybackCapture — no microphone). Output goes
 * to Movies/Tryptify. Android 10+ only; RECORD_AUDIO is requested on first
 * use purely for the playback-capture API — declining it records video-only.
 */
@Composable
private fun DebugScreenRecorderRow() {
    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
    val context = LocalContext.current
    val recording by tf.monochrome.android.debug.DebugScreenRecordService.recording.collectAsStateWithLifecycle()
    val lastSaved by tf.monochrome.android.debug.DebugScreenRecordService.lastSavedName.collectAsStateWithLifecycle()

    val projectionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == android.app.Activity.RESULT_OK && data != null) {
            tf.monochrome.android.debug.DebugScreenRecordService.start(
                context, result.resultCode, data,
            )
        }
    }
    val audioPermLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Granted → stereo media audio; declined → video-only. Either way,
        // continue to the system's screen-capture consent.
        val mpm = context.getSystemService(android.media.projection.MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    SettingItem(
        title = if (recording) "Stop debug screen recording" else "Debug screen recording",
        subtitle = when {
            recording -> "Recording… native resolution/fps, stereo media audio. Tap to stop."
            lastSaved != null -> "Saved: $lastSaved. Tap to record again."
            else -> "Native resolution & fps, stereo internal audio (no mic) → Movies/Tryptify."
        },
        onClick = {
            if (recording) {
                tf.monochrome.android.debug.DebugScreenRecordService.stop(context)
            } else {
                audioPermLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
            }
        },
    )
}

/**
 * Live channel detector: shows the playing track's channel count, the
 * assumed layout name (5.1 / 7.1 / 9.1.6 …) and one chip per channel that
 * lights up while that channel carries signal (peak above −60 dBFS).
 * Metering runs only while this card is visible (acquire/release, same
 * contract as the spectrum tap).
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChannelDetectorCard(viewModel: SettingsViewModel) {
    val state by viewModel.channelDetectorState.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        viewModel.acquireChannelDetector()
        onDispose { viewModel.releaseChannelDetector() }
    }
    Text(
        text = "Channel detector",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val s = state
    if (s == null) {
        Text(
            text = "Idle. Play a track to detect its channel layout.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val rate = if (s.sampleRate % 1000 == 0) "${s.sampleRate / 1000} kHz" else "${s.sampleRate} Hz"
    val enc = if (s.isFloat) "float" else "16-bit"
    Text(
        text = "${s.layoutName} · ${s.channelCount} ch · $rate · $enc",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        s.channelNames.forEachIndexed { i, name ->
            val db = s.peaksDb.getOrElse(i) {
                tf.monochrome.android.audio.dsp.ChannelDetectorProcessor.PEAK_FLOOR_DB
            }
            val active =
                db > tf.monochrome.android.audio.dsp.ChannelDetectorProcessor.ACTIVE_THRESHOLD_DB
            // 0..1 level from the −60..0 dBFS meter range, eased between the
            // ~10 Hz meter ticks so the glow breathes instead of stepping.
            val level by androidx.compose.animation.core.animateFloatAsState(
                targetValue = ((db + 60f) / 60f).coerceIn(0f, 1f),
                animationSpec = androidx.compose.animation.core.tween(120),
                label = "chGlow$i",
            )
            val glowColor = MaterialTheme.colorScheme.primary
            val label = if (active) "$name ${db.toInt()}" else name
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = if (active) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier
                    // Soft radial glow behind the chip, brightness + reach
                    // scaled by the channel's live level. Drawn before clip()
                    // so it can spill past the chip bounds (FlowRow doesn't
                    // clip children).
                    .drawBehind {
                        if (level > 0.01f) {
                            val radius = size.maxDimension * (0.7f + 0.6f * level)
                            drawCircle(
                                brush = androidx.compose.ui.graphics.Brush.radialGradient(
                                    colors = listOf(
                                        glowColor.copy(alpha = 0.55f * level),
                                        glowColor.copy(alpha = 0f),
                                    ),
                                    center = center,
                                    radius = radius,
                                ),
                                radius = radius,
                                center = center,
                            )
                        }
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary.copy(
                                alpha = 0.45f + 0.55f * level
                            )
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Settings switch that pins the player's output to an attached USB Audio
 * Class DAC via setPreferredAudioDevice. The device-name line below the
 * switch updates live as the DAC plugs / unplugs.
 */
@Composable
private fun UsbBitPerfectToggle(viewModel: SettingsViewModel) {
    val enabled by viewModel.usbBitPerfectEnabled.collectAsStateWithLifecycle()
    val deviceName by viewModel.usbOutputDeviceName.collectAsStateWithLifecycle()
    SettingSwitchItem(
        title = "USB DAC bit-perfect routing",
        subtitle = when {
            !enabled -> "Off. Uses system audio output."
            deviceName != null -> "On → $deviceName"
            else -> "On. Plug in a USB DAC to start routing."
        },
        checked = enabled,
        onCheckedChange = { viewModel.setUsbBitPerfectEnabled(it) },
    )

    val exclusiveEnabled by viewModel.usbExclusiveBitPerfectEnabled.collectAsStateWithLifecycle()
    val exclusiveStatus by viewModel.usbExclusiveStatus.collectAsStateWithLifecycle()
    val diagnostics by viewModel.usbBypassDiagnostics.collectAsStateWithLifecycle()
    val failure by viewModel.usbBypassFailure.collectAsStateWithLifecycle()
    val supportedRates by viewModel.usbBypassSupportedRates.collectAsStateWithLifecycle()
    SettingSwitchItem(
        title = "Exclusive USB DAC (bypass Android audio)",
        subtitle = exclusiveSubtitle(
            exclusiveEnabled, exclusiveStatus, failure
        ),
        checked = exclusiveEnabled,
        onCheckedChange = { viewModel.setUsbExclusiveBitPerfectEnabled(it) },
    )
    // Only render the diagnostic card when the toggle is on AND we
    // have something honest to say — either an active stream, a
    // categorised failure, or a known rate inventory. Hidden the rest
    // of the time so the toggle row stays clean.
    if (exclusiveEnabled &&
        (diagnostics != null || failure != null || supportedRates.isNotEmpty())) {
        BypassDiagnosticsCard(
            diagnostics = diagnostics,
            failure = failure,
            supportedRates = supportedRates,
        )
    }
}

/**
 * Renders a compact info card beneath the exclusive-USB toggle:
 *   - Active stream specs (when streaming): rate / bits / channels /
 *     UAC version / device speed / async-feedback presence / clock id
 *   - Failure detail (when not streaming and a start attempt failed)
 *   - Supported-rates list from the device's GET_RANGE table
 *
 * Card styling matches the existing Settings cards. Stays terse: a
 * Bathys at 192/24 should fit the active-stream block in two lines so
 * the toggle below it isn't pushed off-screen.
 */
@Composable
private fun BypassDiagnosticsCard(
    diagnostics: tf.monochrome.android.audio.usb.BypassDiagnostics?,
    failure: tf.monochrome.android.audio.usb.StartFailure?,
    supportedRates: List<tf.monochrome.android.audio.usb.ClockRateRange>,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (diagnostics != null) {
                Text(
                    text = "Active stream",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(diagnostics.rateLabel())
                        append(" · ")
                        append(diagnostics.bitsPerSample)
                        append("-bit · ")
                        append(diagnostics.channels)
                        append("ch · ")
                        append(diagnostics.uacLabel())
                        append(" ")
                        append(diagnostics.speedLabel())
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    // Second line: less critical metadata. Async
                    // feedback presence is the big one — it's the
                    // difference between rate-locked playback and the
                    // host drifting against the DAC's clock.
                    text = buildString {
                        append("alt ")
                        append(diagnostics.altSetting)
                        if (diagnostics.clockSourceId != 0) {
                            append(" · clock #")
                            append(diagnostics.clockSourceId)
                        }
                        append(" · ")
                        append(
                            if (diagnostics.hasFeedbackEndpoint)
                                "async feedback ✓"
                            else "no feedback EP (open-loop)"
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (failure != null && failure.code !=
                tf.monochrome.android.audio.usb.StartError.Ok) {
                Text(
                    text = "Bypass failed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = failure.actionableMessage(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (failure.detail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        // The native detail is technical (libusb error
                        // codes, control-transfer info). Useful for
                        // anyone debugging via logcat — render in mono
                        // to make it visually distinct from the
                        // user-facing actionable message above.
                        text = failure.detail,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (supportedRates.isNotEmpty()) {
                if (diagnostics != null || failure != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                Text(
                    text = "Supported rates",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    // Most DACs expose discrete rates with min==max,
                    // so deduplicating on the label produces the clean
                    // "44.1 / 48 / 88.2 / 96 / 176.4 / 192 kHz" line
                    // that Hi-Fi people care about. Continuous-range
                    // PLLs render as "44.1–768 kHz" untouched.
                    text = supportedRates
                        .map { it.label() }
                        .distinct()
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Renders the controller's real state instead of just echoing the
 * toggle. Status flows through:
 *  Disabled → NoDevice → AwaitingPermission → DeviceOpen
 *  (→ InterfaceClaimed → Streaming once Stage 2/3 land).
 *
 * Honest about the Stage-1 ceiling: even when everything works, the
 * iso pump isn't running yet so audio is still going through the
 * standard sink. The DeviceOpen string says so.
 */
private fun exclusiveSubtitle(
    enabled: Boolean,
    status: tf.monochrome.android.audio.usb.UsbExclusiveController.Status,
    failure: tf.monochrome.android.audio.usb.StartFailure?,
): String {
    if (!enabled) {
        return "Off. UAPP-style libusb output. Needs Developer Options " +
            "→ Disable USB audio routing → ON, otherwise Android's audio " +
            "HAL will keep grabbing the DAC and fight us for it."
    }
    return when (status) {
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Disabled ->
            "Starting up…"
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.NoDevice ->
            "On, waiting for a USB DAC to be plugged in."
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.AwaitingPermission ->
            "DAC detected. Accept the system USB-permission prompt."
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.DeviceOpen ->
            "DAC handle acquired ✓. Bypass engages on the next " +
            "track (or skip the current track to engage now)."
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.InterfaceClaimed ->
            "Streaming interface claimed ✓"
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Streaming ->
            "Bit-perfect: bypassing Android audio ✓ (EQ / DSP still active)"
        // The Error subtitle used to hardcode the kernel-claim story.
        // Now we defer to whichever StartFailure category the native
        // side reported — claim failures, rate-negotiation failures,
        // alloc failures all surface as their own actionable line. If
        // somehow we're in Error with no failure recorded (shouldn't
        // happen but: defensive), fall back to the old text.
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Error ->
            failure?.actionableMessage()?.takeIf { it.isNotBlank() }
                ?: ("Bypass couldn't engage. See logcat tagged " +
                    "'LibusbUacDriver' for details.")
    }
}

// ─── Tab 6: Downloads ──────────────────────────────────────────────────
@Composable
private fun DownloadsTab(viewModel: SettingsViewModel) {
    val downloadQuality by viewModel.downloadQuality.collectAsStateWithLifecycle()
    val downloadFolder by viewModel.downloadFolderUri.collectAsStateWithLifecycle()
    var showQualityDropdown by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val folderPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // Persist permission across reboots
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setDownloadFolderUri(uri.toString())
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear Downloads") },
            text = { Text("This will delete all downloaded tracks from your device. This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllDownloads()
                    showClearDialog = false
                }) { Text("Delete All", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsTabContent {
        // Moved off Library, which had it under a "Liked Songs" header of its
        // own. Liking a song is a library action; downloading one is not, and
        // every other download setting was already here.
        SettingsGroupHeader("Automatic Downloads")
        val autoDownloadLiked by viewModel.autoDownloadLikedSongs.collectAsStateWithLifecycle()
        SettingSwitchItem(
            title = "Auto-Download Liked Songs",
            subtitle = "Keep a copy of every song you like from now on. " +
                "Songs you liked earlier are left alone. Turning this on " +
                "never starts a bulk download.",
            checked = autoDownloadLiked,
            onCheckedChange = { viewModel.setAutoDownloadLikedSongs(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Download Quality")
        SettingItem(title = "Quality", subtitle = downloadQuality.displayName, onClick = { showQualityDropdown = true })
        DropdownMenu(expanded = showQualityDropdown, onDismissRequest = { showQualityDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setDownloadQuality(q); showQualityDropdown = false })
            }
        }

        val dlLyrics by viewModel.downloadLyrics.collectAsStateWithLifecycle()
        SettingSwitchItem(
            title = "Download Lyrics",
            subtitle = "Bundle .lrc files with downloaded tracks",
            checked = dlLyrics,
            onCheckedChange = { viewModel.setDownloadLyrics(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Download Folder")

        // remember so the DocumentFile ContentResolver lookup runs only when the
        // folder actually changes, not on every recomposition of this screen.
        val folderDisplay = remember(downloadFolder, context) {
            downloadFolder?.let { folder ->
                try {
                    val uri = folder.toUri()
                    val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    docFile?.name ?: "Custom folder"
                } catch (_: Exception) { "Custom folder" }
            } ?: "Internal app storage (default)"
        }

        SettingItem(
            title = "Save location",
            subtitle = folderDisplay,
            onClick = { folderPickerLauncher.launch(null) }
        )

        if (downloadFolder != null) {
            TextButton(onClick = { viewModel.setDownloadFolderUri(null) }) {
                Text("Reset to default", color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        // Not "Storage" — System has a group by that name for the cache, and
        // two identically-titled headers in different tabs read as the same
        // setting reachable from two places. This one is about the files.
        SettingsGroupHeader("Downloaded Files")
        OutlinedButton(
            onClick = { showClearDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear All Downloads")
        }
    }
}

// ─── Tab 6: Connections ────────────────────────────────────────────────
/**
 * Every account and endpoint in one place: which catalogs to search, the
 * self-hosted server URLs behind them, the two scrobblers, the Spotify link
 * that Library's playlist import rides on, and the app sign-in that syncs
 * favourites across devices.
 *
 * Replaces the old Instances and Scrobbling tabs and absorbs System's
 * "Account & Sync" group. Each section is a `...Controls` function rather than
 * a tab of its own, because [SettingsTabContent] is a LazyColumn and nesting
 * two of them throws on measure — so exactly one wrapper lives here.
 */

/**
 * Discord presence — the switch, the token, and the warning it needs.
 *
 * The warning is not boilerplate and is deliberately not collapsible. Every
 * other connection in this app hands over a scoped credential: a Last.fm
 * session key scrobbles and nothing else, a ListenBrainz token submits listens.
 * A Discord user token is the account. It reads every DM, joins servers and
 * spends money, and Discord has no scoped alternative that can set a presence —
 * there is no mobile Rich Presence API at all, which is why this works the way
 * it does. Someone switching this on should know that before they paste, not
 * after.
 */
@Composable
private fun DiscordPresenceControls(viewModel: SettingsViewModel) {
    val enabled by viewModel.discordPresenceEnabled.collectAsStateWithLifecycle()
    val token by viewModel.discordToken.collectAsStateWithLifecycle()
    val applicationId by viewModel.discordApplicationId.collectAsStateWithLifecycle()
    val status by viewModel.discordStatus.collectAsStateWithLifecycle()
    val error by viewModel.discordError.collectAsStateWithLifecycle()
    val discordUser by viewModel.discordUsername.collectAsStateWithLifecycle()
    val animated by viewModel.discordPresenceAnimated.collectAsStateWithLifecycle()
    val uploadChannel by viewModel.discordUploadChannel.collectAsStateWithLifecycle()
    var showChannelDialog by rememberSaveable { mutableStateOf(false) }

    if (showChannelDialog) {
        var input by rememberSaveable { mutableStateOf(uploadChannel) }
        AlertDialog(
            onDismissRequest = { showChannelDialog = false },
            title = { Text("Spectrum over the artwork") },
            text = {
                Column {
                    Text(
                        "With a channel set, the spectrum is drawn across the album " +
                            "art itself instead of in the small circle. That means an " +
                            "image per track, and Discord only shows images it can " +
                            "fetch, so each one is posted here as an attachment and " +
                            "the card points at it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Use a channel you don't mind filling up. A private server of " +
                            "your own is the usual answer. Enable Developer Mode in " +
                            "Discord, then right-click the channel and Copy Channel ID. " +
                            "Leave it empty to keep the plain cover and the circle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        label = { Text("Channel ID") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDiscordUploadChannel(input)
                    showChannelDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showChannelDialog = false }) { Text("Cancel") }
            },
        )
    }

    var showDialog by rememberSaveable { mutableStateOf(false) }

    if (showDialog) {
        var tokenInput by rememberSaveable { mutableStateOf(token) }
        var appIdInput by rememberSaveable { mutableStateOf(applicationId) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Discord token") },
            text = {
                Column {
                    Text(
                        "This is your Discord account token, not a password and not " +
                            "an app password. Anything holding it can read your messages " +
                            "and act as you. Tryptify keeps it on this device only and " +
                            "never syncs it, but paste it only if that trade is one you " +
                            "want to make.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Discord has no presence API for phones, so the only way to do " +
                            "this is to connect as you. That is against Discord's terms, " +
                            "and while presence-only use has gone unpunished for years, " +
                            "the risk of losing the account is yours.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tokenInput, onValueChange = { tokenInput = it },
                        label = { Text("User token") },
                        // Replacing one is the common case, not entering the
                        // first: tokens rotate on every password change, and
                        // selecting seventy characters by hand to overwrite
                        // them is a worse job than it looks.
                        trailingIcon = {
                            if (tokenInput.isNotEmpty()) {
                                IconButton(onClick = { tokenInput = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear token")
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appIdInput, onValueChange = { appIdInput = it },
                        label = { Text("Application ID (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Album art needs an application ID. A presence set this way can " +
                            "only show an image Discord itself hosts, and an application " +
                            "is what turns a cover URL into one. Without it you still get " +
                            "the track, artist, album and progress bar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDiscordCredentials(tokenInput, appIdInput)
                    showDialog = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsGroupHeader("Discord")
    SettingItem(
        title = "Token",
        subtitle = when {
            token.isBlank() -> "Not set. Needed to show what you're playing"
            discordUser != null && applicationId.isBlank() ->
                "$discordUser, album art off (no application ID)"
            discordUser != null -> "$discordUser"
            applicationId.isBlank() -> "Set. Album art off (no application ID)"
            else -> "Set"
        },
        onClick = { showDialog = true }
    )
    SettingSwitchItem(
        title = "Show what I'm playing",
        subtitle = when {
            token.isBlank() -> "Add a token first"
            status == tf.monochrome.android.data.presence.DiscordPresenceManager.Status.CONNECTED ->
                "On. Your profile is showing the current track"
            status == tf.monochrome.android.data.presence.DiscordPresenceManager.Status.CONNECTING ->
                "Connecting…"
            status == tf.monochrome.android.data.presence.DiscordPresenceManager.Status.FAILED ->
                "Couldn't connect"
            enabled -> "On. Connects when something plays"
            else -> "Off"
        },
        checked = enabled,
        onCheckedChange = { viewModel.setDiscordPresenceEnabled(it && token.isNotBlank()) }
    )
    SettingItem(
        title = "Spectrum over the artwork",
        subtitle = if (uploadChannel.isBlank()) {
            "Off. The spectrum sits in the small circle"
        } else {
            "Posting to channel $uploadChannel"
        },
        onClick = { showChannelDialog = true },
    )
    SettingSwitchItem(
        title = "Animated spectrum",
        subtitle = "A moving spectrum beside the artwork, matched to the genre's " +
            "rhythm and the cover's colour. Off leaves the card still.",
        checked = animated,
        onCheckedChange = { viewModel.setDiscordPresenceAnimated(it) }
    )
    error?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(onClick = { viewModel.clearDiscordError() }) { Text("Dismiss") }
    }
    if (token.isNotBlank()) {
        TextButton(onClick = { viewModel.clearDiscordCredentials() }) {
            Text("Forget token", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ConnectionsTab(viewModel: SettingsViewModel) {
    SettingsTabContent {
        CatalogControls(viewModel)
        Spacer(modifier = Modifier.height(20.dp))
        ScrobblingControls(viewModel)
        Spacer(modifier = Modifier.height(20.dp))
        DiscordPresenceControls(viewModel)
        Spacer(modifier = Modifier.height(20.dp))
        SpotifyAccountControls()
        Spacer(modifier = Modifier.height(20.dp))
        AccountControls(viewModel)
    }
}

/**
 * Spotify connect / disconnect. Only the account link lives here — the import
 * UI it feeds stays on Library, next to the playlists it creates. Both read the
 * same [SpotifyImportViewModel]: `hiltViewModel()` resolves against the Settings
 * NavBackStackEntry, which is shared by every tab, so connecting here is
 * immediately visible to the importer there.
 */
@Composable
private fun SpotifyAccountControls() {
    val context = LocalContext.current
    val spotifyViewModel: SpotifyImportViewModel = hiltViewModel()
    val connected by spotifyViewModel.isConnected.collectAsStateWithLifecycle()
    val userName by spotifyViewModel.userName.collectAsStateWithLifecycle()
    val connecting by spotifyViewModel.isConnecting.collectAsStateWithLifecycle()
    val authError by spotifyViewModel.authError.collectAsStateWithLifecycle()

    SettingsGroupHeader("Spotify")
    if (connected) {
        SettingItem(
            title = "Spotify",
            subtitle = "Connected as ${userName ?: "…"}",
        )
        OutlinedButton(onClick = { spotifyViewModel.disconnect() }) {
            Text("Disconnect Spotify")
        }
    } else {
        Text(
            "Connect your Spotify account to import playlists from Library. "
                + "Note: only Spotify accounts allowlisted for this app can "
                + "connect while it is in Development mode.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(
            onClick = { spotifyViewModel.connect(context) },
            enabled = !connecting
        ) {
            Text(if (connecting) "Connecting…" else "Connect Spotify")
        }
    }
    authError?.let { error ->
        Text(
            error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/**
 * App sign-in, moved off System — it is an account, and every other account is
 * here now. Owns its own confirm dialog, so it depends on nothing but
 * [viewModel].
 */
@Composable
private fun AccountControls(viewModel: SettingsViewModel) {
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()
    val userEmail by viewModel.userEmail.collectAsStateWithLifecycle()

    var showSignOutDialog by remember { mutableStateOf(false) }
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text("Sign out?") },
            text = { Text("You'll stop syncing favorites and playlists across devices until you sign back in.") },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    viewModel.logout()
                }) { Text("Sign Out", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsGroupHeader("Account")
    if (isLoggedIn) {
        SettingItem(
            title = "Signed in as",
            subtitle = userEmail ?: "Unknown",
        )
        OutlinedButton(onClick = { showSignOutDialog = true }) {
            Text("Sign Out")
        }
    } else {
        Text(
            "Sign in to sync favorites and playlists across devices.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            "Use the Account page to sign in with Google or email.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Catalog picker + the self-hosted server URLs, unwrapped from any scroll
 * container so [ConnectionsTab] can stack it with the scrobbler, Spotify and
 * account groups — [SettingsTabContent] is a LazyColumn and two cannot nest.
 *
 * Depends on nothing but [viewModel]; both text fields own their own state.
 */
@Composable
private fun CatalogControls(viewModel: SettingsViewModel) {
    val customEndpoint by viewModel.customEndpoint.collectAsStateWithLifecycle()
    val qobuzEndpoint by viewModel.qobuzEndpoint.collectAsStateWithLifecycle()
    val sourceMode by viewModel.sourceMode.collectAsStateWithLifecycle()
    var customInput by remember(customEndpoint) { mutableStateOf(customEndpoint ?: "") }
    var qobuzInput by remember(qobuzEndpoint) { mutableStateOf(qobuzEndpoint ?: "") }

    SettingsGroupHeader("Catalog Source")
    // Source mode picker — controls which catalogs feed search/discovery.
    // Plays/downloads still follow the per-track PlaybackSource so a
    // download you triggered earlier keeps working regardless of this
    // setting.
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "Catalog source",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Which catalogs power Search and Browse.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        val sourceOptions = listOf(
            tf.monochrome.android.data.preferences.SourceMode.BOTH to "Both",
            tf.monochrome.android.data.preferences.SourceMode.TIDAL_ONLY to "TIDAL only",
            tf.monochrome.android.data.preferences.SourceMode.QOBUZ_ONLY to "Qobuz only",
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            sourceOptions.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = sourceMode == mode,
                    onClick = { viewModel.setSourceMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, sourceOptions.size),
                ) {
                    Text(label)
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(20.dp))
    SettingsGroupHeader("Servers")

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Tidal HiFi URL",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Your own Tidal HiFi server, used for search, browse, and streaming.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        // Snapshot the latest input/saved value into stable holders so the
        // onFocusChanged closure captured by the OutlinedTextField doesn't
        // need to re-allocate on every keystroke recomposition.
        val latestInput = rememberUpdatedState(customInput)
        val latestSaved = rememberUpdatedState(customEndpoint)
        OutlinedTextField(
            value = customInput,
            onValueChange = { customInput = it },
            placeholder = {
                Text(
                    "API endpoint",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                viewModel.setCustomEndpoint(latestInput.value.trim().ifBlank { null })
            }),
            modifier = Modifier
                .widthIn(max = 240.dp)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) {
                        val trimmed = latestInput.value.trim().ifBlank { null }
                        if (trimmed != latestSaved.value) {
                            viewModel.setCustomEndpoint(trimmed)
                        }
                    }
                }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Qobuz URL",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Used for downloads. Honored whenever set, independent of Dev Mode.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        val latestQobuzInput = rememberUpdatedState(qobuzInput)
        val latestQobuzSaved = rememberUpdatedState(qobuzEndpoint)
        OutlinedTextField(
            value = qobuzInput,
            onValueChange = { qobuzInput = it },
            placeholder = {
                Text(
                    "Qobuz instance",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                viewModel.setQobuzEndpoint(latestQobuzInput.value.trim().ifBlank { null })
            }),
            modifier = Modifier
                .widthIn(max = 240.dp)
                .onFocusChanged { focusState ->
                    if (!focusState.isFocused) {
                        val trimmed = latestQobuzInput.value.trim().ifBlank { null }
                        if (trimmed != latestQobuzSaved.value) {
                            viewModel.setQobuzEndpoint(trimmed)
                        }
                    }
                }
        )
    }
}

@Composable
private fun InstanceCard(url: String, version: String?) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .liquidGlass(shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Cloud, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                if (version != null) {
                    Text("v$version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Default.Check, contentDescription = "Online", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        }
    }
}

// ─── Tab 7: System ─────────────────────────────────────────────────────
@Composable
private fun SystemTab(viewModel: SettingsViewModel, navController: NavController) {
    val cacheSize by viewModel.cacheSize.collectAsStateWithLifecycle()
    var showClearAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    val content = context.contentResolver.openInputStream(it)?.bufferedReader()?.use { reader ->
                        reader.readText()
                    }
                    if (!content.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            // Success/failure is reported by viewModel.messages
                            // once the import actually finishes — not here,
                            // where it fired even for a corrupt backup.
                            viewModel.importLibrary(content)
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "Failed to read file", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, "Error reading file", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Export writes to a SAF-chosen file, not the clipboard: a large library's
    // backup JSON exceeds the binder transaction limit and setPrimaryClip()
    // hard-crashed exactly the users with the most data to protect.
    var pendingExportJson by remember { mutableStateOf<String?>(null) }
    val exportSaveLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExportJson
        pendingExportJson = null
        if (uri != null && json != null) {
            coroutineScope.launch(Dispatchers.IO) {
                val ok = try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    true
                } catch (e: Exception) {
                    false
                }
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(
                        context,
                        if (ok) "Backup saved" else "Failed to save backup",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text("Reset Settings & Cache") },
            // Reworded to match what clearAllData() actually does: it resets
            // preferences and wipes the cache but leaves the Room library
            // untouched, and the app does not restart. The old copy promised
            // to clear "all local data" and restart, neither of which happened.
            text = { Text("This resets all app settings and clears cached data. Your saved library, playlists, favorites, and downloads are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllData()
                    showClearAllDialog = false
                }) { Text("Reset", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text("Cancel") }
            }
        )
    }

    SettingsTabContent {
        SettingsGroupHeader("Storage")
        SettingItem(title = "Cache Size", subtitle = cacheSize)
        OutlinedButton(onClick = { viewModel.clearCache() }) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Clear Cache")
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Data")
        SettingItem(
            title = "Restart onboarding",
            subtitle = "Run the first-time setup again. Your library and settings are kept.",
            onClick = { viewModel.restartOnboarding() }
        )
        OutlinedButton(
            onClick = { showClearAllDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Reset Settings & Cache")
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader("Backup & Restore")
        Text(
            "Export or import your library and history as a JSON file",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                viewModel.exportLibrary { json ->
                    // Stash the JSON, then let the user pick a destination file
                    // (safe for any size, unlike the old clipboard copy).
                    pendingExportJson = json
                    exportSaveLauncher.launch("monochrome-backup.json")
                }
            }) {
                Text("Export JSON")
            }
            OutlinedButton(onClick = {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
            }) {
                Text("Import JSON")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        // Frame rate and resolution are whole-app settings and were sitting in
        // Appearance under a "Display" header, next to the explicit-badge
        // toggle. They cost battery and heat, which makes them System's
        // business — the visualizer's own GPU settings stay with the visualizer.
        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Performance")

        // Low performance mode. The master owns nothing of its own — it writes
        // the three switches below it, and they write it back — so the row is
        // a shortcut, not a fourth piece of state that can drift.
        val lowPerformanceMode by viewModel.lowPerformanceMode.collectAsStateWithLifecycle()
        val disableAnimations by viewModel.disableAnimations.collectAsStateWithLifecycle()
        val legacyPlayer by viewModel.legacyPlayer.collectAsStateWithLifecycle()
        val disableLiquidGlass by viewModel.disableLiquidGlass.collectAsStateWithLifecycle()
        SettingSwitchItem(
            title = "Low performance mode",
            subtitle = "Turns off animations, the liquid glass effect and the new player design in one go. Saves battery and helps on older or slower devices.",
            checked = lowPerformanceMode,
            onCheckedChange = { viewModel.setLowPerformanceMode(it) }
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            SettingSwitchItem(
                title = "Disable animations",
                subtitle = "No transitions, bounces, glass motion or colour blends anywhere in the app.",
                checked = disableAnimations,
                onCheckedChange = { viewModel.setDisableAnimations(it) }
            )
            SettingSwitchItem(
                title = "Legacy player",
                subtitle = "Use the flat player design from before liquid glass.",
                checked = legacyPlayer,
                onCheckedChange = { viewModel.setLegacyPlayer(it) }
            )
            SettingSwitchItem(
                title = "Remove liquid glass",
                subtitle = "Flat, opaque surfaces instead of blurred, refractive glass throughout the app.",
                checked = disableLiquidGlass,
                onCheckedChange = { viewModel.setDisableLiquidGlass(it) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        val appFps by viewModel.appTargetFps.collectAsStateWithLifecycle()
        val appResolution by viewModel.appRenderResolution.collectAsStateWithLifecycle()
        // App-wide frame rate and panel resolution, applied by selecting a
        // matching display mode in MainActivity. Resolution options only list
        // classes the panel can reach; a request maps to the nearest mode.
        var showFpsDropdown by remember { mutableStateOf(false) }
        SettingItem(
            title = "Frame Rate",
            subtitle = "Whole app: " + if (appFps == 0) "Unlocked (display max)" else "${appFps}fps",
            onClick = { showFpsDropdown = true }
        )
        DropdownMenu(expanded = showFpsDropdown, onDismissRequest = { showFpsDropdown = false }) {
            listOf(0, 60, 120).forEach { fps ->
                DropdownMenuItem(
                    text = { Text(if (fps == 0) "Unlocked" else "${fps}fps") },
                    onClick = { viewModel.setAppTargetFps(fps); showFpsDropdown = false }
                )
            }
        }

        val context = LocalContext.current
        var showResDropdown by remember { mutableStateOf(false) }
        val nativeShortSide = remember {
            val modes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.display?.supportedModes
            } else null
            modes?.maxOfOrNull { minOf(it.physicalWidth, it.physicalHeight) }
                ?: minOf(
                    context.resources.displayMetrics.widthPixels,
                    context.resources.displayMetrics.heightPixels,
                )
        }
        val resolutionOptions = remember(nativeShortSide) {
            listOf(0) + listOf(720, 1080, 1440, 2160).filter { it <= nativeShortSide }
        }
        fun resolutionLabel(shortSide: Int) = when {
            shortSide == 0 -> "Native"
            shortSide >= 2160 -> "4K (2160p)"
            shortSide >= 1440 -> "2K (1440p)"
            shortSide >= 1080 -> "1080p"
            else -> "720p"
        }
        SettingItem(
            title = "Resolution",
            subtitle = "Whole app: ${resolutionLabel(appResolution)}. Nearest panel mode is used",
            onClick = { showResDropdown = true }
        )
        DropdownMenu(expanded = showResDropdown, onDismissRequest = { showResDropdown = false }) {
            resolutionOptions.forEach { shortSide ->
                DropdownMenuItem(
                    text = { Text(resolutionLabel(shortSide)) },
                    onClick = { viewModel.setAppRenderResolution(shortSide); showResDropdown = false }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Diagnostics")
        SettingItem(
            title = "View debug log",
            subtitle = "Live logcat stream for this process. Copy or export as a file for bug reports",
            onClick = { navController.navigateTool(Screen.DebugLog) },
        )

        // Moved from Audio, where it had ended up under the "Spatial Audio"
        // header. A screen recorder is a diagnostic, not an audio setting.
        Spacer(modifier = Modifier.height(8.dp))
        DebugScreenRecorderRow()
    }
}

// ─── Tab 9: About ──────────────────────────────────────────────────────
@Composable
private fun AboutTab(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    // Latched in the ViewModel before anything writes over it, so the badge
    // survives the effect below marking the notes read.
    val arrivedUnread by viewModel.whatsNewWasUnread.collectAsStateWithLifecycle()
    // Reaching the panel counts as having read it, however the user got here.
    LaunchedEffect(Unit) { viewModel.markWhatsNewSeen() }
    SettingsTabContent {
        // Support first. It used to sit at the bottom of About, below the
        // release notes and the update controls — which is to say, below the
        // fold on every phone, where nobody scrolled to find it.
        SupportSection(onTip = { openDonationUrl(context, "https://ko-fi.com/trypt") })

        Spacer(modifier = Modifier.height(28.dp))
        WhatsNewPanel(highlight = arrivedUnread)

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader("Updates")
        SettingItem(
            title = "Check for updates",
            subtitle = "Look on GitHub for a newer release now",
            onClick = { viewModel.checkForUpdatesNow() },
        )

        Spacer(modifier = Modifier.height(24.dp))
        SettingsGroupHeader("About Tryptify")
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Tryptify version ${BuildConfig.VERSION_NAME} · 2026",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Open-source, ad-free music streaming",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The tip jar, and who's asking. */
@Composable
private fun SupportSection(onTip: () -> Unit) {
    SettingsGroupHeader("Support the app")
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Image(
            painter = painterResource(id = R.drawable.trypt_pfp),
            contentDescription = "trypt avatar",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Tryptify is built and maintained by trypt. If it's "
                + "earned a place in your day, a tip keeps the lights on "
                + "and the next features shipping.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onTip,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Tip on Ko-fi")
        }
    }
}

// Opens an external donation/support URL in the browser (or a Custom Tab, if the
// user's default browser supports it). Wrapped so a device with no browser can't
// crash the app — it surfaces a Toast instead.
private fun openDonationUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open the link", Toast.LENGTH_SHORT).show()
    }
}

// ─── Shared components ─────────────────────────────────────────────────
/**
 * Every settings tab's scroll container.
 *
 * The tail padding is what lets this screen run *under* the mini player instead
 * of stopping above it. The nav host used to reserve the bar's height outside
 * the screen, which left a band of flat theme background behind the bar for its
 * glass to lens — so the bar read as an opaque container however transparent it
 * was set. The space is reserved here instead, where it is scrollable: rows
 * pass behind the glass, and the last one still comes clear of it.
 *
 * Reserved whether or not anything is playing. The cost when nothing is is a
 * little empty space past the final row, which is only reachable by scrolling
 * to it; the cost of getting it wrong the other way is a setting nobody can
 * reach.
 */
@Composable
private fun SettingsTabContent(content: @Composable () -> Unit) {
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val anchors = LocalSettingsAnchors.current
    val listState = rememberLazyListState()
    var listTop by remember { mutableFloatStateOf(0f) }

    // Scroll to whatever the search asked for, once the tab has laid out and
    // the row has reported where it is. Offsets are in root coordinates, so the
    // list's own top has to come off before it means anything to the scroll.
    LaunchedEffect(anchors?.foundAt) {
        val y = anchors?.foundAt ?: return@LaunchedEffect
        listState.animateScrollBy(y - listTop - ANCHOR_HEADROOM)
        anchors.clear()
    }

    // Give up on a request nothing answers. An index title that no longer
    // matches any row — a renamed setting — would otherwise leave the request
    // set for the rest of the session, and the next row to compose with that
    // title, on any tab, would be scrolled to out of nowhere.
    LaunchedEffect(anchors?.target) {
        if (anchors?.target == null) return@LaunchedEffect
        delay(ANCHOR_TIMEOUT_MS)
        if (anchors.foundAt == null) anchors.clear()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { listTop = it.positionInRoot().y },
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            // Clears the floating search bar when it is open. As content padding
            // rather than a Spacer or an offset, so the rows scroll up *under*
            // the bar's glass — giving it something real to frost — instead of
            // stopping at a hard line below it.
            top = 16.dp + LocalSettingsSearchInset.current,
            bottom = 16.dp + LocalMiniPlayerInset.current + navBar,
        ),
    ) {
        item { content() }
    }
}


// Slugify a label into a stable DevEdit element id (e.g. "Gapless Playback" →
// "gapless_playback"). Used so wrapping the shared setting rows yields stable,
// human-readable ids that persist across launches.
internal fun devSlug(text: String): String =
    text.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "item" }

@Composable
private fun SettingsGroupHeader(title: String) {
    tf.monochrome.android.devedit.DevEditable("hdr_${devSlug(title)}", Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .settingsAnchor(title)
                .padding(bottom = 8.dp, top = 4.dp)
        )
    }
}

/**
 * A group header that's actually lit — a tinted strip with a highlight drifting
 * across it, and an optional version badge.
 *
 * The plain [SettingsGroupHeader] is a line of coloured text, which is right for
 * the eight headers nobody needs to notice. This one is for the release notes:
 * it's the reason most people open this tab, and after Support was moved above
 * it, it needed to be findable at a glance on the way past.
 *
 * The sheen comes from [rememberMotionFloat], so "disable animations" leaves the
 * strip lit but perfectly still rather than driving a gradient forever.
 */
@Composable
private fun LitGroupHeader(title: String, badge: String? = null) {
    val primary = MaterialTheme.colorScheme.primary
    val sweep = rememberMotionFloat(
        initialValue = 0f,
        targetValue = 1f,
        durationMillis = 5200,
        label = "litHeaderSheen",
        repeatMode = RepeatMode.Restart,
        still = 0.5f,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(MonoDimens.shapeMd)
            .drawBehind {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(primary.copy(alpha = 0.18f), primary.copy(alpha = 0.05f)),
                    ),
                )
                // A soft band travelling left to right. Read from the draw scope
                // rather than recomposed, so the motion never invalidates the
                // settings list behind it.
                val head = size.width * (sweep.value * 1.7f - 0.35f)
                val half = size.width * 0.22f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            primary.copy(alpha = 0.22f),
                            Color.Transparent,
                        ),
                        startX = head - half,
                        endX = head + half,
                    ),
                )
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = primary,
            modifier = Modifier.weight(1f),
        )
        if (badge != null) {
            Surface(
                shape = MonoDimens.shapePill,
                color = primary,
            ) {
                Text(
                    text = "New in $badge",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
fun SettingItem(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    tf.monochrome.android.devedit.DevEditable("item_${devSlug(title)}", Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .settingsAnchor(title)
                // Only clickable (with ripple) when there's an action — an
                // empty onClick used to ripple like a picker but do nothing.
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 12.dp)
        ) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun SettingSwitchItem(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    tf.monochrome.android.devedit.DevEditable("sw_${devSlug(title)}", Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().settingsAnchor(title).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

// ─── Tab 5: Library Settings ──────────────────────────────────────────
@Composable
private fun LibrarySettingsTab(viewModel: SettingsViewModel) {
    val libraryTabOrder by viewModel.libraryTabOrder.collectAsStateWithLifecycle()

    // Every other tab wraps in SettingsTabContent; this one rolled its own
    // LazyColumn and its own section headings, so its groups sat at a
    // different indent and size to the rest of Settings. Normalised: one
    // wrapper, SettingsGroupHeader throughout — which also gives the rows
    // stable DevEdit ids like everywhere else.
    SettingsTabContent {
        SettingsGroupHeader("Local Media Scanning")

        Spacer(modifier = Modifier.height(8.dp))
        val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
        val scanContext = LocalContext.current
        OutlinedButton(
            onClick = {
                viewModel.rescanLibrary()
                android.widget.Toast.makeText(scanContext, "Scanning library…", android.widget.Toast.LENGTH_SHORT).show()
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isScanning) "Scanning…" else "Rescan Library Now")
        }

        if (libraryTabOrder.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            SettingsGroupHeader("Library Tab Order")
            Text(
                "Reorder the tabs shown in the Library screen",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            libraryTabOrder.forEachIndexed { index, sectionId ->
                val displayName = sectionId.replaceFirstChar { it.titlecase(Locale.getDefault()) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { viewModel.moveLibraryTab(index, index - 1) },
                        enabled = index > 0
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = "Move up",
                            tint = if (index > 0) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                    IconButton(
                        onClick = { viewModel.moveLibraryTab(index, index + 1) },
                        enabled = index < libraryTabOrder.size - 1
                    ) {
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Move down",
                            tint = if (index < libraryTabOrder.size - 1) MaterialTheme.colorScheme.onSurface
                                   else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                        )
                    }
                }
            }
        }

        // Moved off System, where it sat between the app sign-in and the
        // backup controls. Importing playlists builds your library, so it
        // lives with the library.
        Spacer(modifier = Modifier.height(16.dp))
        PlaylistImportSection()
    }
}

/**
 * "What's New" — the user-facing summary of each release, newest first.
 *
 * Sits directly under Support, because between them they are the only two
 * things on this tab anybody comes looking for, and it's where the update
 * notice sends people.
 */
@Composable
private fun WhatsNewPanel(highlight: Boolean) {
    val releases = WhatsNew.releases
    if (releases.isEmpty()) return

    LitGroupHeader(
        title = "What's New",
        // Only badged when you haven't read this build's notes yet. A permanent
        // "new" flag is one nobody looks at twice.
        badge = releases.first().versionName.takeIf { highlight },
    )
    // One release is dozens of entries, and the older ones are history rather
    // than news — left open they bury the version somebody actually just
    // installed under everything that came before it. Each version is a row you
    // can fold, and only the newest starts open.
    releases.forEachIndexed { index, release ->
        var expanded by rememberSaveable(release.versionCode) { mutableStateOf(index == 0) }
        val turn by animateFloatAsState(
            targetValue = if (expanded) 180f else 0f,
            label = "whatsNewChevron",
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Version ${release.versionName}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            // The count is what makes a folded row worth reading: it says how
            // much is behind it without opening it.
            Text(
                text = "${release.entries.size} changes",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(turn),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                // Sections are announced when they start, so consecutive entries
                // under one heading print it once. Entries with no section carry
                // straight on as the flat list they were.
                var section: String? = null
                release.entries.forEach { entry ->
                    if (entry.section != null && entry.section != section) {
                        Text(
                            text = entry.section,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                        )
                    }
                    section = entry.section
                    Column(modifier = Modifier.padding(vertical = 6.dp)) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = entry.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Import playlists from a connected Spotify account.
 *
 * Was a group on the System tab, between the app sign-in and the backup
 * controls. It produces playlists, so it belongs with the library — and
 * System is now only cache, data, performance, backup and diagnostics.
 */
@Composable
private fun PlaylistImportSection() {
        // SystemTab declared this once at the top and the block borrowed it;
        // lifted out, the section has to own it.
        val context = LocalContext.current
        SettingsGroupHeader("Playlist Import")
        val spotifyViewModel: SpotifyImportViewModel = hiltViewModel()
        val spotifyConnected by spotifyViewModel.isConnected.collectAsStateWithLifecycle()
        val spotifyUserName by spotifyViewModel.userName.collectAsStateWithLifecycle()
        val importProgress by spotifyViewModel.importProgress.collectAsStateWithLifecycle()
        val isImporting by spotifyViewModel.isImporting.collectAsStateWithLifecycle()
        var showSpotifyPicker by remember { mutableStateOf(false) }
        var importUrl by remember { mutableStateOf("") }

        val onImportResult: (Boolean, String) -> Unit = { success, msg ->
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
            if (success) importUrl = ""
        }

        // Connect / disconnect lives on the Connections tab with every other
        // account; this section keeps only the import it feeds. Both read the
        // same SpotifyImportViewModel, so linking there lights this up without
        // leaving Settings.
        Text(
            if (spotifyConnected) {
                "Connected as ${spotifyUserName ?: "…"}. Paste a playlist link, or pick from your library."
            } else {
                "Connect Spotify on the Connections tab to import your playlists."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))
        androidx.compose.material3.OutlinedTextField(
            value = importUrl,
            onValueChange = { importUrl = it },
            label = { Text("Spotify playlist URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = spotifyConnected,
            supportingText = if (!spotifyConnected) {
                { Text("Connect Spotify on the Connections tab to enable URL import") }
            } else null
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { spotifyViewModel.importByUrl(context, importUrl, onResult = onImportResult) },
                enabled = spotifyConnected && importUrl.isNotBlank() && !isImporting
            ) {
                Text("Import Playlist")
            }
            OutlinedButton(
                onClick = {
                    spotifyViewModel.loadMyPlaylists()
                    showSpotifyPicker = true
                },
                enabled = spotifyConnected && !isImporting
            ) {
                Text("Browse My Playlists")
            }
        }

        when (val progress = importProgress) {
            is tf.monochrome.android.data.import_.ImportProgress.Fetching -> {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Fetching playlist from ${progress.source}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is tf.monochrome.android.data.import_.ImportProgress.Matching -> {
                Spacer(modifier = Modifier.height(8.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { progress.current.toFloat() / progress.total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Matching ${progress.current}/${progress.total}, ${progress.matched} found",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is tf.monochrome.android.data.import_.ImportProgress.Done -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Imported ${progress.matched} of ${progress.total} tracks into '${progress.playlistName}'",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (progress.unmatched.isNotEmpty()) {
                    var showUnmatched by remember { mutableStateOf(false) }
                    TextButton(onClick = { showUnmatched = !showUnmatched }) {
                        Text(if (showUnmatched) "Hide unmatched tracks" else "Show ${progress.unmatched.size} unmatched tracks")
                    }
                    if (showUnmatched) {
                        progress.unmatched.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            is tf.monochrome.android.data.import_.ImportProgress.Failed -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    progress.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            tf.monochrome.android.data.import_.ImportProgress.Idle -> {}
        }

        if (showSpotifyPicker) {
            val spotifyPlaylists by spotifyViewModel.myPlaylists.collectAsStateWithLifecycle()
            val playlistsLoading by spotifyViewModel.playlistsLoading.collectAsStateWithLifecycle()
            val playlistsError by spotifyViewModel.playlistsError.collectAsStateWithLifecycle()
            tf.monochrome.android.ui.components.SpotifyPlaylistPickerDialog(
                playlists = spotifyPlaylists,
                isLoading = playlistsLoading,
                error = playlistsError,
                onPick = { playlistId, name, strict ->
                    showSpotifyPicker = false
                    spotifyViewModel.importPlaylist(context, playlistId, name, strict, onImportResult)
                },
                onPickLikedSongs = { strict ->
                    showSpotifyPicker = false
                    spotifyViewModel.importLikedSongs(context, strict, onImportResult)
                },
                onDismiss = { showSpotifyPicker = false }
            )
        }
}

/** One search hit: the setting, and where it lives. */
@Composable
private fun SettingsHitPill(entry: SettingsEntry, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.bounceClick(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = if (entry.destination is SettingsDestination.Route) {
                    Icons.AutoMirrored.Filled.ArrowForward
                } else {
                    Icons.Default.Search
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = entry.title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp),
            )
            Spacer(Modifier.width(6.dp))
            // Where it will take you. A pill that only says "Crossfade" makes
            // you tap it to find out whether you are about to change tab or
            // leave the screen.
            Text(
                text = entry.tabLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * How far above the wanted row the scroll stops.
 *
 * Landing with the row flush against the top edge reads as having overshot —
 * the group heading it belongs to is gone and there is no sense of where in the
 * tab you are.
 */
private val ANCHOR_HEADROOM = 96f

/** How long a scroll request waits for a row to answer it before it is dropped. */
private const val ANCHOR_TIMEOUT_MS = 700L

/**
 * Which paper the light themes are printed on.
 *
 * Two swatches rather than a switch, because the difference is a colour and a
 * switch would have to describe it in words — "warm off-white" means very
 * little next to seeing the two side by side. The swatches are the real
 * backgrounds, taken from the generator, so what is shown is what will happen.
 *
 * Always visible, including on a dark theme. It applies to whichever light
 * theme comes next, and "system" means that can be the next time the sun goes
 * down; hiding the control until it is already relevant means finding it in the
 * dark.
 */
@Composable
private fun LightPaperSetting(paper: String, onPaperChange: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().settingsAnchor("Light paper")) {
        Text(
            text = "Light paper",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "The ground every light theme is printed on. Crisp is true white; " +
                "warm is off-white, for less glare.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PaperSwatch(
                label = "Crisp",
                paper = Paper.Crisp,
                selected = paper != "warm",
                onClick = { onPaperChange("crisp") },
                modifier = Modifier.weight(1f),
            )
            PaperSwatch(
                label = "Warm",
                paper = Paper.Warm,
                selected = paper == "warm",
                onClick = { onPaperChange("warm") },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))
    }
}

/**
 * How long the album's colours take to cross over, as a slider that says the
 * time in seconds.
 *
 * Its first stop is **Match blend**, not zero, and that is the default. Left
 * there the app keeps deriving the fade from "Blend Between Tracks", which is
 * the pairing [ColorBlend] exists to hold: with a blend set, the queue advances
 * at `duration - blend`, so a fade of exactly that length lands on the last
 * sample of the outgoing track. Any number here breaks that on purpose, which is
 * a fine thing to want and a poor thing to do by accident — hence a stop that
 * means "leave it alone" rather than a number that happens to match today.
 *
 * The label shows what Match blend currently works out to, because "match blend"
 * on its own does not tell you whether that is 0.6 s or twelve seconds.
 *
 * The value is committed on release rather than on every pixel of the drag: it
 * is written to disk and read by the player, the mini player and the theme, and
 * a drag across the whole track would otherwise be forty writes.
 */
@Composable
private fun ColorTransitionSetting(
    millis: Int,
    blendSeconds: Int,
    onMillisChange: (Int) -> Unit,
) {
    val stops = ColorBlend.steps
    // An unrecognised stored value (an older build's, a hand-edited one) lands
    // on Match blend rather than off the end of the track.
    val stored = stops.indexOf(millis).coerceAtLeast(0)
    var position by remember(stored) { mutableFloatStateOf(stored.toFloat()) }
    val selected = stops[position.roundToInt().coerceIn(stops.indices)]

    fun seconds(ms: Int) = String.format(Locale.US, "%.2f s", ms / 1000f)
    val readout = when {
        selected == ColorBlend.MATCH_BLEND ->
            "Match blend · ${seconds(ColorBlend.millisFor(blendSeconds))}"
        selected == 0 -> "Instant"
        else -> seconds(selected)
    }

    Column(modifier = Modifier.fillMaxWidth().settingsAnchor("Color transition")) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Color transition",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = readout,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = "How long the album's colours take to cross over when the track changes. " +
                "Match blend keeps them in step with Blend Between Tracks.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = position,
            onValueChange = { position = it },
            onValueChangeFinished = {
                onMillisChange(stops[position.roundToInt().coerceIn(stops.indices)])
            },
            valueRange = 0f..(stops.size - 1).toFloat(),
            // One detent per stop. Slider counts the points *between* the ends,
            // so it is two fewer than there are stops.
            steps = stops.size - 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun PaperSwatch(
    label: String,
    paper: Paper,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Built from the same accent the app is currently themed with, so the
    // swatch previews this theme's light variant rather than a generic white.
    val accent = MaterialTheme.colorScheme.primary
    val preview = remember(paper, accent) { lightSchemeFor(accent, paper) }
    Column(
        modifier = modifier
            .clip(MonoDimens.shapeMd)
            .background(preview.background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else preview.outline,
                shape = MonoDimens.shapeMd,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        // A card and a line of text on the real ground: the whole point of the
        // choice is how a surface and its ink sit on the page, which a flat
        // block of background colour cannot show.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(MonoDimens.shapeSm)
                .background(preview.surfaceVariant),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = preview.onSurface,
        )
        Text(
            text = "Aa",
            style = MaterialTheme.typography.bodySmall,
            color = preview.onSurfaceVariant,
        )
    }
}
