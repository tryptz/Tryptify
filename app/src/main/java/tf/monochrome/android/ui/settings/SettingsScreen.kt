package tf.monochrome.android.ui.settings

import tf.monochrome.android.ui.components.UiText
import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.ui.theme.goToPage
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.BuildConfig
import tf.monochrome.android.audio.PitchRatio
import java.util.Locale
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.ContentScale
import androidx.annotation.DrawableRes
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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import tf.monochrome.android.ui.navigation.APP_PAGE_TITLES
import tf.monochrome.android.ui.navigation.canTogglePageVisibility
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
import androidx.compose.ui.platform.LocalConfiguration
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
import tf.monochrome.android.ui.theme.selectableThemes
import tf.monochrome.android.ui.theme.themeDisplayNames
import tf.monochrome.android.visualizer.PresetRotationMode
import tf.monochrome.android.visualizer.ProjectMAudioBus
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource

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
//
// The labels here are ids — English, stable, what the search index and the dev
// editor key on. What a chip says comes from [settingsTabLabelRes].
private val settingsTabs = listOf("Appearance", "Visual Studio", "Audio", "Equalizer", "Library", "Downloads", "Connections", "Radio", "System", "About")

private val settingsTabLabels: Map<String, Int> = mapOf(
    "Appearance" to R.string.settings_tab_appearance,
    "Visual Studio" to R.string.settings_tab_visual_studio,
    "Audio" to R.string.settings_tab_audio,
    "Equalizer" to R.string.settings_tab_equalizer,
    "Library" to R.string.settings_tab_library,
    "Downloads" to R.string.settings_tab_downloads,
    "Connections" to R.string.settings_tab_connections,
    "Radio" to R.string.settings_tab_radio,
    "System" to R.string.settings_tab_system,
    "About" to R.string.settings_tab_about,
)

/** The chip text for the tab with id [label], in the reader's language. */
@StringRes
internal fun settingsTabLabelRes(label: String): Int =
    requireNotNull(settingsTabLabels[label]) { "no settings tab called \"$label\"" }

/**
 * Which tab carries a given label, for the search index to point at.
 *
 * Derived rather than written down, so reordering the tabs cannot leave every
 * search result landing one tab to the left. Throws on an unknown label, which
 * a test turns into a build failure rather than a result that goes nowhere.
 */
internal fun settingsTabIndex(label: String): Int =
    settingsPages.indexOf(label).also {
        require(it >= 0) { "no settings page called \"$label\"" }
    }

/**
 * Chips that open a screen of their own instead of a page, by the route they
 * open.
 *
 * Visual Studio was a page holding one row that opened the Player Visuals
 * Studio — a tap to reach the page and another to reach the only thing on it.
 * Its chip goes there directly now, and the pager does not have a page for it,
 * so a swipe never lands on an empty one either.
 */
private val settingsLinkTabs: Map<String, Screen> = mapOf(
    "Visual Studio" to Screen.LyricsFxStudio,
)

/** The route a chip opens when it is a link rather than a page, else null. */
internal fun settingsLinkRoute(label: String): String? = settingsLinkTabs[label]?.route

/** The tabs that are pages of the pager, in chip order. */
private val settingsPages: List<String> = settingsTabs.filter { it !in settingsLinkTabs }

/**
 * Index of the About tab, where the What's New panel lives. Derived from
 * [settingsTabs] rather than written down, so reordering the tabs can't leave a
 * caller pointing at the wrong page.
 */
val SETTINGS_TAB_ABOUT: Int = settingsTabIndex("About")

/** One selectable step in the Appearance › Font Size picker. */
private data class FontScalePreset(@StringRes val label: Int, val scale: Float)

// Five fixed steps replace the old 0.50-2.00 free slider. The range is
// deliberately narrower than before: below ~0.85 the mini player and lyrics
// clip, and above ~1.50 the settings rows and player controls start to
// overlap. Anyone who genuinely needs larger type should turn on "Use system
// font size", which honours the OS accessibility setting all the way up.
private val FONT_SCALE_PRESETS = listOf(
    FontScalePreset(R.string.settings_font_small, 0.85f),
    FontScalePreset(R.string.settings_font_default, 1.00f),
    FontScalePreset(R.string.settings_font_large, 1.15f),
    FontScalePreset(R.string.settings_font_larger, 1.30f),
    FontScalePreset(R.string.settings_font_largest, 1.50f),
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
        initialPage = initialTab.coerceIn(0, settingsPages.lastIndex),
        pageCount = { settingsPages.size },
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
            android.widget.Toast.makeText(messageContext, msg.resolve(messageContext), android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    val settingsAnchors = remember { SettingsAnchors() }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Resolved through the activity's context, which carries the app language
    // on every Android version; keyed on the configuration so a language
    // change re-runs the search instead of serving the old language's hits.
    val searchContext = LocalContext.current
    val searchConfig = LocalConfiguration.current
    val searchHits = remember(searchQuery, searchConfig) {
        searchSettings(searchQuery) { searchContext.getString(it) }
    }

    CompositionLocalProvider(LocalSettingsAnchors provides settingsAnchors) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.settings)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                }
            },
            actions = {
                IconButton(onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) searchQuery = ""
                }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = if (searchOpen) stringResource(R.string.settings_close_search) else stringResource(R.string.settings_find_a_setting),
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
        LaunchedEffect(selectedTab) {
            chipRow.animateScrollToItem(settingsTabs.indexOf(settingsPages[selectedTab]).coerceAtLeast(0))
        }
        LazyRow(
            state = chipRow,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(settingsTabs) { _, tab ->
                val link = settingsLinkTabs[tab]
                FilterChip(
                    selected = link == null && settingsPages[selectedTab] == tab,
                    onClick = {
                        if (link != null) {
                            navController.navigateTool(link)
                        } else {
                            settingsScope.launch { settingsPager.goToPage(settingsTabIndex(tab), animateTabs) }
                        }
                    },
                    label = { Text(stringResource(settingsTabLabelRes(tab)), style = MaterialTheme.typography.labelMedium) },
                    // The arrow the search pills use for "opens a screen": this
                    // chip leaves Settings rather than switching its page.
                    trailingIcon = if (link != null) {
                        {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize),
                            )
                        }
                    } else null,
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
            placeholder = stringResource(R.string.settings_find_a_setting),
            onClose = { searchOpen = false; searchQuery = "" },
            modifier = Modifier.weight(1f),
            barContent = {
                if (searchQuery.trim().length >= 2) {
                    if (searchHits.isEmpty()) {
                        Text(
                            text = stringResource(R.string.settings_no_setting_by_that_name),
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
                                                settingsAnchors.request(hit.displayTitle(searchContext))
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
                    tf.monochrome.android.devedit.DevEditScreen("settings/${devSlug(settingsPages[page])}") {
                        // By name, not position: the pages are the chips minus
                        // the links, so a position here would silently shift
                        // every time a chip became one.
                        when (settingsPages[page]) {
                            "Appearance" -> AppearanceTab(viewModel, navController)
                            "Audio" -> AudioTab(viewModel, navController)
                            "Equalizer" -> EqualizerTab(navController, viewModel)
                            "Library" -> LibrarySettingsTab(viewModel)
                            "Downloads" -> DownloadsTab(viewModel)
                            "Connections" -> ConnectionsTab(viewModel)
                            "Radio" -> tf.monochrome.android.ui.settings.radio.RadioSettingsTab()
                            "System" -> SystemTab(viewModel, navController)
                            "About" -> AboutTab(viewModel)
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
        SettingsGroupHeader(stringResource(R.string.settings_equalizer))
        // "Enable Equalizer" undersold it: the same switch is the master for the
        // AutoEQ headphone correction, the tone shelves and the graphic bands —
        // Target Curve and Headphone below it are all downstream of this one.
        SettingSwitchItem(
            title = stringResource(R.string.settings_enable_autoeq_equalizer),
            subtitle = stringResource(R.string.settings_apply_eq_processing_to_playback),
            checked = eqEnabled,
            onCheckedChange = { eqViewModel.toggleEq() }
        )
        // System-wide is a sub-toggle of the equalizer, not a peer of it: it
        // publishes the same correction to the device's global mix, so it means
        // nothing with the EQ off. It sat ABOVE its parent switch as an equal,
        // which read as the bigger, better switch of the two. Nested and revealed
        // under its parent, exactly as the player's audio-tools panel does it —
        // and `toggleEq` clears the flag on the way out, so the effect cannot
        // keep running behind a row that is no longer on screen.
        AnimatedVisibility(visible = eqEnabled) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp)) {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_system_wide_autoeq),
                    subtitle = stringResource(R.string.settings_apply_your_autoeq_tone_to_all_device_audio),
                    checked = systemWideAutoEq,
                    onCheckedChange = viewModel::setSystemWideAutoEq,
                    badge = stringResource(R.string.settings_beta),
                    caution = stringResource(R.string.settings_swaps_the_app_s_exact_correction_for_a_coarser),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        SettingItem(
            title = stringResource(R.string.settings_target_curve),
            subtitle = selectedTarget.label,
        )

        Box(Modifier.settingsAnchor(stringResource(R.string.search_autoeq_headphone_profile))) {
            SettingItem(
                title = stringResource(R.string.settings_headphone),
                subtitle = selectedHeadphone?.name ?: stringResource(R.string.settings_none_selected),
            )
        }

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
            Text(stringResource(R.string.settings_open_precision_autoeq))
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { navController.navigateTool(Screen.ParametricEq) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.settings_open_parametric_eq))
        }

        // ─── Saved Profiles ───
        if (allPresets.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            SettingsGroupHeader(stringResource(R.string.settings_saved_profiles))

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
                                contentDescription = stringResource(R.string.settings_active),
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
                                stringResource(R.string.settings_eq_preset_summary, pluralStringResource(R.plurals.settings_band_count, preset.bands.size, preset.bands.size), preset.targetName),
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
                                    contentDescription = stringResource(R.string.settings_delete_preset),
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
            title = { Text(stringResource(R.string.settings_delete_profile)) },
            text = { Text(stringResource(R.string.settings_delete_named, preset.name)) },
            confirmButton = {
                TextButton(onClick = {
                    eqViewModel.deletePreset(preset.id)
                    presetToDelete = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) { Text(stringResource(R.string.action_cancel)) }
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
/**
 * The app's display language: the phone's, or one of the translations, each
 * listed by its own name. Applying it recreates the activity, so the choice
 * shows at once; see [tf.monochrome.android.locale.AppLanguage].
 */
@Composable
private fun LanguageSetting() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val current = remember { tf.monochrome.android.locale.AppLanguage.current(context) }
    var open by remember { mutableStateOf(false) }
    val options = tf.monochrome.android.locale.AppLanguage.OPTIONS
    val followPhone = androidx.compose.ui.res.stringResource(tf.monochrome.android.R.string.settings_language_follow_phone)
    SettingsGroupHeader(androidx.compose.ui.res.stringResource(tf.monochrome.android.R.string.settings_language))
    Box(Modifier.settingsAnchor(stringResource(R.string.settings_language_title))) {
        SettingItem(
            title = androidx.compose.ui.res.stringResource(tf.monochrome.android.R.string.settings_language_title),
            subtitle = options.firstOrNull { it.tag == current }?.nativeName ?: followPhone,
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(followPhone) },
                onClick = {
                    open = false
                    tf.monochrome.android.locale.AppLanguage.set(context.findActivityOrSelf(), "")
                },
            )
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.nativeName) },
                    onClick = {
                        open = false
                        tf.monochrome.android.locale.AppLanguage.set(context.findActivityOrSelf(), option.tag)
                    },
                )
            }
        }
    }
}

/** The activity behind a Compose context, which may be wrapped; the context itself if there is none. */
private tailrec fun android.content.Context.findActivityOrSelf(): android.content.Context = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> if (baseContext === this) this else baseContext.findActivityOrSelf()
    else -> this
}

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
    val themePaper by viewModel.themePaper.collectAsStateWithLifecycle()
    val fontScale by viewModel.fontScale.collectAsStateWithLifecycle()
    val customFontUri by viewModel.customFontUri.collectAsStateWithLifecycle()
    val availableFonts by viewModel.availableFonts.collectAsStateWithLifecycle()
    val followSystemFontScale by viewModel.fontScaleFollowSystem.collectAsStateWithLifecycle()
    val glowBehindArt by viewModel.glowBehindArt.collectAsStateWithLifecycle()
    val artGlowRadius by viewModel.artGlowRadius.collectAsStateWithLifecycle()
    val artGlowBrightness by viewModel.artGlowBrightnessPct.collectAsStateWithLifecycle()
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

        LanguageSetting()

        SettingsGroupHeader(stringResource(R.string.settings_theme))
        SettingItem(title = stringResource(R.string.settings_color_theme), subtitle = themeDisplayNames[themeName] ?: themeName, onClick = { showThemeDropdown = true })
        DropdownMenu(expanded = showThemeDropdown, onDismissRequest = { showThemeDropdown = false }) {
            // What may be *chosen* is narrower than what may be *named*: the
            // subtitle above still reads the full catalogue, so a stored key
            // this device cannot offer is displayed rather than shown raw.
            selectableThemes().forEach { (key, displayName) ->
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
            onMillisChange = { viewModel.setColorTransitionMs(it) },
        )

        // Custom colours. When on, the two swatches below build the whole app's
        // scheme and the preset above is ignored — light or dark is decided by
        // the ground's own brightness, and every foreground is floored for
        // contrast against it, so even a poorly chosen pair stays legible.
        SettingSwitchItem(
            title = stringResource(R.string.settings_custom_colors),
            subtitle = stringResource(R.string.settings_pick_your_own_accent_and_background_overrides),
            checked = customThemeEnabled,
            onCheckedChange = { viewModel.setCustomThemeEnabled(it) },
        )
        AnimatedVisibility(visible = customThemeEnabled) {
            Column {
                ColorSwatchRow(
                    label = stringResource(R.string.settings_accent),
                    color = Color(customAccent),
                    onClick = { editingColor = CustomColorTarget.Accent },
                )
                ColorSwatchRow(
                    label = stringResource(R.string.settings_background),
                    color = Color(customBackground),
                    onClick = { editingColor = CustomColorTarget.Background },
                )
            }
        }
        when (editingColor) {
            CustomColorTarget.Accent -> ColorPickerDialog(
                initial = Color(customAccent),
                title = stringResource(R.string.settings_accent_color),
                onDismiss = { editingColor = null },
                onConfirm = {
                    viewModel.setCustomAccentColor(it.toArgb())
                    editingColor = null
                },
            )
            CustomColorTarget.Background -> ColorPickerDialog(
                initial = Color(customBackground),
                title = stringResource(R.string.settings_background_color),
                onDismiss = { editingColor = null },
                onConfirm = {
                    viewModel.setCustomBackgroundColor(it.toArgb())
                    editingColor = null
                },
            )
            null -> Unit
        }
        SettingSwitchItem(
            title = stringResource(R.string.settings_dynamic_colors),
            subtitle = stringResource(R.string.settings_tint_the_player_mini_player_and_lyrics_from),
            checked = dynamicColors,
            onCheckedChange = { viewModel.setDynamicColors(it) }
        )
        // The two below only mean anything once there is a palette to spend, so
        // they live inside the switch that produces one rather than sitting
        // greyed out beside it.
        AnimatedVisibility(visible = dynamicColors) {
            Column {
                SettingSwitchItem(
                    title = stringResource(R.string.settings_tint_the_menus_too),
                    subtitle = stringResource(R.string.settings_let_the_cover_set_the_app_s_accent_and),
                    checked = dynamicColorMenus,
                    onCheckedChange = { viewModel.setDynamicColorMenus(it) },
                )
                AnimatedVisibility(visible = dynamicColorMenus) {
                    SettingSwitchItem(
                        title = stringResource(R.string.settings_keep_the_theme_background),
                        subtitle = stringResource(R.string.settings_accent_only_the_background_stays_the_color_your),
                        checked = dynamicColorKeepBackground,
                        onCheckedChange = { viewModel.setDynamicColorKeepBackground(it) },
                    )
                }
            }
        }
        SettingSwitchItem(
            title = stringResource(R.string.settings_glow_behind_album_art),
            subtitle = stringResource(R.string.settings_bloom_the_bass_reactive_glow_around_the_album),
            checked = glowBehindArt,
            onCheckedChange = { viewModel.setGlowBehindArt(it) }
        )
        // The cover bloom's own size and strength, separate from the Studio's
        // Glow section (the bloom behind the lyrics). Inside the switch that
        // draws them, like the Dynamic Colors rows above. Until one is moved
        // they read the lyric glow, so they open on what is already on screen.
        AnimatedVisibility(visible = glowBehindArt) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                IntSettingSlider(
                    value = artGlowRadius,
                    valueRange = 0f..160f,
                    onCommit = { viewModel.setArtGlowRadius(it) },
                    label = { stringResource(R.string.settings_glow_radius_value, it) },
                    subtitle = stringResource(R.string.settings_how_far_the_halo_reaches_past_the_cover_s_edge),
                )
                IntSettingSlider(
                    value = artGlowBrightness,
                    valueRange = 0f..60f,
                    onCommit = { viewModel.setArtGlowBrightness(it) },
                    label = { stringResource(R.string.settings_glow_brightness_value, it) },
                    subtitle = stringResource(R.string.settings_peak_strength_of_the_halo_on_a_kick),
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_typography))

        // Font size: five fixed steps, or hand over to the OS setting.
        SettingSwitchItem(
            title = stringResource(R.string.settings_use_system_font_size),
            subtitle = stringResource(R.string.settings_follow_the_size_set_in_android_settings_display),
            checked = followSystemFontScale,
            onCheckedChange = { viewModel.setFontScaleFollowSystem(it) }
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                stringResource(R.string.settings_font_size),
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
                        label = { Text(stringResource(preset.label)) }
                    )
                }
            }

            Text(
                stringResource(R.string.settings_preview_the_quick_brown_fox_jumps_over_the_lazy),
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
            .displayNameOf(customFontUri) ?: stringResource(R.string.settings_inter_default)

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
                        stringResource(R.string.settings_font_library),
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
                    contentDescription = if (fontLibraryExpanded) stringResource(R.string.settings_collapse_font_library)
                        else stringResource(R.string.settings_expand_font_library),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (fontLibraryExpanded) {
                // The built-in default, so switching back is a pick like any
                // other rather than a separate "Reset" the user has to find.
                FontRow(
                    name = "Inter",
                    note = stringResource(R.string.settings_the_default_neutral_ui_grotesque),
                    selected = customFontUri == null,
                    onSelect = { viewModel.resetDefaultFont() },
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.settings_included),
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
                        stringResource(R.string.settings_imported),
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
                    Text(stringResource(R.string.settings_import_a_font_ttf_otf))
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
                    contentDescription = stringResource(R.string.settings_delete_font, name),
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
    val playerDynamicColor by viewModel.playerDynamicColor.collectAsStateWithLifecycle()
    val playerBlurredBackground by viewModel.playerBlurredBackground.collectAsStateWithLifecycle()

    SettingsGroupHeader(stringResource(R.string.settings_display))
    SettingSwitchItem(
        title = stringResource(R.string.settings_show_explicit_badges),
        subtitle = stringResource(R.string.settings_display_e_badge_on_explicit_tracks),
        checked = explicit,
        onCheckedChange = { viewModel.setShowExplicitBadges(it) }
    )
    val romaji by viewModel.romajiLyrics.collectAsStateWithLifecycle()
    SettingSwitchItem(
        title = stringResource(R.string.settings_romaji_lyrics),
        subtitle = stringResource(R.string.settings_transliterate_japanese_lyrics_to_latin),
        checked = romaji,
        onCheckedChange = { viewModel.setRomajiLyrics(it) }
    )

    // Word-level lyrics provider — which karaoke-timing source(s) run when
    // TIDAL has no synced lyrics. "Both" tries NetEase first, then Kugou.
    val lyricsProvider by viewModel.lyricsWordProvider.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.settings_word_level_lyrics_provider),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.settings_karaoke_timing_source_when_your_instance_has_no),
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
    SettingsGroupHeader(stringResource(R.string.settings_now_playing))
    val viewMode by viewModel.nowPlayingViewMode.collectAsStateWithLifecycle()
    var showModeDropdown by remember { mutableStateOf(false) }
    SettingItem(
        title = stringResource(R.string.settings_view_mode), 
        subtitle = stringResource(R.string.settings_view_mode_summary, viewModeLabel(viewMode)), 
        onClick = { showModeDropdown = true }
    )
    DropdownMenu(expanded = showModeDropdown, onDismissRequest = { showModeDropdown = false }) {
        NowPlayingViewMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(viewModeLabel(mode)) },
                onClick = { viewModel.setNowPlayingViewMode(mode); showModeDropdown = false }
            )
        }
    }
    SettingSwitchItem(
        title = stringResource(R.string.settings_dynamic_player_color),
        subtitle = stringResource(R.string.settings_tint_the_player_from_album_art_needs_dynamic),
        checked = playerDynamicColor,
        onCheckedChange = { viewModel.setPlayerDynamicColor(it) }
    )
    SettingSwitchItem(
        title = stringResource(R.string.settings_blurred_album_background),
        subtitle = stringResource(R.string.settings_behind_the_player_and_the_mixer_the_album_art),
        checked = playerBlurredBackground,
        onCheckedChange = { viewModel.setPlayerBlurredBackground(it) }
    )

    Spacer(modifier = Modifier.height(16.dp))
}

/**
 * Everything under Spectrum Analyzer, Audio Visualizer, Visualizer Graphics and
 * Preset rotation.
 *
 * Split out of [InterfaceControls], which was 356 lines collecting twenty-eight
 * pieces of state at its top — so moving the brightness slider invalidated the
 * "Show Explicit Badges" switch, and every other row on the screen, because
 * they all sat in one composable scope. Each half reads only its own flows now,
 * and neither recomposes for the other's changes.
 *
 * It was also the single largest method in the app by compiler cost: a device
 * log shows ART allocating 4.7 MB to JIT `InterfaceControls` the first time
 * Settings opened, with 102 frames skipped around it. Two smaller methods are
 * cheaper to compile and land in the profile independently.
 *
 * No longer rendered here: the whole visualizer — this block plus the
 * ambient overlay controls — lives on the Player Visuals Studio's
 * "Visualizer" tab now. Kept in this file (internal) because it shares the
 * settings-row helpers below.
 */
@Composable
internal fun VisualizerSettings(
    viewModel: SettingsViewModel,
    /** Opens the glass preset browser the hosting screen draws over itself. */
    onOpenPresetBrowser: () -> Unit,
) {
    val sensitivity by viewModel.visualizerSensitivity.collectAsStateWithLifecycle()
    val brightness by viewModel.visualizerBrightness.collectAsStateWithLifecycle()
    val engineEnabled by viewModel.visualizerEngineEnabled.collectAsStateWithLifecycle()
    val presetId by viewModel.visualizerPresetId.collectAsStateWithLifecycle()
    val rotationSeconds by viewModel.visualizerRotationSeconds.collectAsStateWithLifecycle()
    val rotationMode by viewModel.visualizerPresetRotationMode.collectAsStateWithLifecycle()
    val textureSize by viewModel.visualizerTextureSize.collectAsStateWithLifecycle()
    val meshX by viewModel.visualizerMeshX.collectAsStateWithLifecycle()
    val meshY by viewModel.visualizerMeshY.collectAsStateWithLifecycle()
    val targetFps by viewModel.visualizerTargetFps.collectAsStateWithLifecycle()
    val audioDelayMs by viewModel.visualizerAudioDelayMs.collectAsStateWithLifecycle()
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
    val selectedPresetName = presets.firstOrNull { it.id == presetId }?.displayName ?: stringResource(R.string.settings_auto_select_bundled_preset)
    var showTextureDropdown by remember { mutableStateOf(false) }
    var showFftDropdown by remember { mutableStateOf(false) }

    // Presets install lazily; make sure the preset dropdown has data.
    LaunchedEffect(Unit) {
        viewModel.prepareVisualizerEngine()
    }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_spectrum_analyzer))
        SettingSwitchItem(
            title = stringResource(R.string.settings_show_spectrum_analyzer),
            subtitle = stringResource(R.string.settings_display_live_audio_spectrum_on_the_player_and),
            checked = spectrumEnabled,
            onCheckedChange = { viewModel.setSpectrumAnalyzerEnabled(it) }
        )
        SettingSwitchItem(
            title = stringResource(R.string.settings_show_on_now_playing),
            subtitle = stringResource(R.string.settings_overlay_the_spectrum_on_the_album_art_hero),
            checked = spectrumShowOnNowPlaying,
            onCheckedChange = { viewModel.setSpectrumShowOnNowPlaying(it) }
        )
        val fftLabel = when (spectrumFftSize) {
            4096 -> stringResource(R.string.settings_fft_low)
            16384 -> stringResource(R.string.settings_fft_high)
            else -> stringResource(R.string.settings_fft_medium)
        }
        SettingItem(
            title = stringResource(R.string.settings_fft_size),
            subtitle = fftLabel,
            onClick = { showFftDropdown = true }
        )
        DropdownMenu(expanded = showFftDropdown, onDismissRequest = { showFftDropdown = false }) {
            listOf(
                4096 to stringResource(R.string.settings_fft_low),
                8192 to stringResource(R.string.settings_fft_medium),
                16384 to stringResource(R.string.settings_fft_high)
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
        }

        // The spectrum's own preview lives in the waterfall section now: with
        // depth, fade and angle beside it, a second, smaller copy above would
        // be the same picture without the explanation.
        Spacer(modifier = Modifier.height(16.dp))
        // One arbiter for the two live previews below, so only the one most
        // on screen runs. See PreviewArbiter.
        val previewArbiter = remember { PreviewArbiter() }
        androidx.compose.runtime.CompositionLocalProvider(LocalPreviewArbiter provides previewArbiter) {
        val waterfall by viewModel.spectrumWaterfall.collectAsStateWithLifecycle()
        SpectrumWaterfallSettingsSection(
            settings = waterfall,
            onChange = viewModel::setSpectrumWaterfall,
            liveBins = if (spectrumEnabled) ({ spectrumBins }) else null,
        )

        Spacer(modifier = Modifier.height(16.dp))
        val waveCandy by viewModel.waveCandy.collectAsStateWithLifecycle()
        WaveCandySettingsSection(
            settings = waveCandy,
            onChange = viewModel::setWaveCandy,
            // Drawn from the same analyzer, so it previews while that runs.
            preview = spectrumEnabled,
        )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_audio_visualizer))
        SettingSwitchItem(
            title = stringResource(R.string.settings_use_projectm_visualizer),
            subtitle = stringResource(R.string.settings_use_the_native_opengl_renderer_when_the_bridge),
            checked = engineEnabled,
            onCheckedChange = { viewModel.setVisualizerEngineEnabled(it) }
        )
        // The preset library is the player's glass browser, opened over this
        // screen: categories, authors, favourites and search across all of
        // it. The dropdown it replaces listed every preset in one menu — over
        // nine thousand rows with no way to search or group them.
        SettingItem(
            title = stringResource(R.string.settings_default_preset),
            subtitle = selectedPresetName,
            onClick = onOpenPresetBrowser,
        )
        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_visualizer_graphics))
        
        SettingItem(
            title = stringResource(R.string.settings_texture_size),
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
            label = { stringResource(R.string.settings_mesh_x, it) },
        )

        IntSettingSlider(
            value = meshY,
            valueRange = 8f..128f,
            onCommit = { viewModel.setVisualizerMeshY(it) },
            label = { stringResource(R.string.settings_mesh_y, it) },
        )

        IntSettingSlider(
            value = targetFps,
            valueRange = 30f..240f,
            onCommit = { viewModel.setVisualizerTargetFps(it) },
            label = { stringResource(R.string.settings_target_fps, it) },
            subtitle = if (vsyncEnabled) {
                stringResource(R.string.settings_target_fps_vsync)
            } else {
                stringResource(R.string.settings_target_fps_free)
            },
        )

        // The visualizer is fed from inside the playback chain, so it always
        // sees audio slightly before the output device plays it. Wired output
        // hides that; Bluetooth does not.
        IntSettingSlider(
            value = audioDelayMs,
            valueRange = 0f..ProjectMAudioBus.MAX_DELAY_MS.toFloat(),
            onCommit = { viewModel.setVisualizerAudioDelayMs(it) },
            label = { if (it == 0) stringResource(R.string.settings_audio_delay_off) else stringResource(R.string.settings_audio_delay_ms, it) },
            subtitle = stringResource(R.string.settings_holds_the_visualizer_back_to_match_what_you_hear),
        )

        SettingSwitchItem(
            title = stringResource(R.string.settings_disable_vsync),
            subtitle = stringResource(R.string.settings_let_the_visualizer_exceed_display_refresh_capped),
            checked = !vsyncEnabled,
            onCheckedChange = { viewModel.setVisualizerVsyncEnabled(!it) }
        )

        SettingSwitchItem(
            title = stringResource(R.string.settings_show_fps),
            subtitle = stringResource(R.string.settings_display_visualizer_framerate_counter),
            checked = showFps,
            onCheckedChange = { viewModel.setVisualizerShowFps(it) }
        )

        SettingSwitchItem(
            title = stringResource(R.string.settings_fullscreen),
            subtitle = stringResource(R.string.settings_fill_screen_in_now_playing_visualizer_view),
            checked = fullscreen,
            onCheckedChange = { viewModel.setVisualizerFullscreen(it) }
        )
        SettingSwitchItem(
            title = stringResource(R.string.settings_touch_waveform),
            subtitle = stringResource(R.string.settings_draw_audio_waveforms_between_touch_points_on_the),
            checked = touchWaveform,
            onCheckedChange = { viewModel.setVisualizerTouchWaveform(it) }
        )
        SettingItem(
            title = stringResource(R.string.settings_engine_status),
            subtitle = stringResource(R.string.settings_engine_status_summary, engineStatus.badge, engineStatus.assetVersion),
            onClick = {}
        )

        SettingsGroupHeader(stringResource(R.string.settings_preset_rotation))
        Text(
            text = when (rotationMode) {
                PresetRotationMode.Off ->
                    stringResource(R.string.settings_rotation_off_desc)
                PresetRotationMode.Timer ->
                    stringResource(R.string.settings_rotation_timer_desc)
                PresetRotationMode.Track ->
                    stringResource(R.string.settings_rotation_track_desc)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val modes = PresetRotationMode.entries
            modes.forEachIndexed { index, mode ->
                SegmentedButton(
                    selected = rotationMode == mode,
                    onClick = { viewModel.setVisualizerPresetRotationMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                ) {
                    Text(
                        when (mode) {
                            PresetRotationMode.Off -> stringResource(R.string.state_off)
                            PresetRotationMode.Timer -> stringResource(R.string.settings_rotation_timer)
                            PresetRotationMode.Track -> stringResource(R.string.settings_rotation_track)
                        }
                    )
                }
            }
        }

        // Only the timer has an interval to set.
        if (rotationMode == PresetRotationMode.Timer) {
            IntSettingSlider(
                value = rotationSeconds,
                valueRange = 5f..120f,
                onCommit = { viewModel.setVisualizerRotationSeconds(it) },
                label = { stringResource(R.string.settings_every_seconds, it) },
            )
        }

        IntSettingSlider(
            value = sensitivity,
            valueRange = 0f..100f,
            onCommit = { viewModel.setVisualizerSensitivity(it) },
            label = { stringResource(R.string.settings_sensitivity_value, it) },
            subtitle = stringResource(R.string.settings_controls_intensity_high_epilepsy_warning),
        )

        IntSettingSlider(
            value = brightness,
            valueRange = 0f..100f,
            onCommit = { viewModel.setVisualizerBrightness(it) },
            label = { stringResource(R.string.settings_brightness_value, it) },
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
    label: @Composable (Int) -> String,
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
            title = { Text(stringResource(R.string.settings_your_last_fm_api_key)) },
            text = {
                Column {
                    Text(
                        // Two different things share the word "key" here, and a
                        // listener who conflates them gets silent failures. This
                        // is an *application* key, not the session key above and
                        // not an account login.
                        stringResource(R.string.settings_lastfm_key_needed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_genre_charts_don_t_need_this_they_read_public),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Without this in the registration, Last.fm shows a page
                    // asking the listener to return to the app by hand and
                    // never redirects — the connection just never completes,
                    // with nothing on either side saying why.
                    Text(
                        stringResource(R.string.settings_lastfm_callback, viewModel.lastFmCallbackUrl),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyInput, onValueChange = { keyInput = it },
                        label = { Text(stringResource(R.string.settings_api_key)) }, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = secretInput, onValueChange = { secretInput = it },
                        label = { Text(stringResource(R.string.settings_shared_secret)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setLastFmApiCredentials(keyInput, secretInput)
                    showApiKeyDialog = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showApiKeyDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    if (showLbDialog) {
        var tokenInput by rememberSaveable { mutableStateOf(lbToken ?: "") }
        AlertDialog(
            onDismissRequest = { showLbDialog = false },
            title = { Text(stringResource(R.string.settings_listenbrainz_token)) },
            text = {
                OutlinedTextField(
                    value = tokenInput, onValueChange = { tokenInput = it },
                    label = { Text(stringResource(R.string.settings_user_token)) }, modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (tokenInput.isNotBlank()) viewModel.setListenBrainzToken(tokenInput)
                    else viewModel.clearListenBrainzToken()
                    showLbDialog = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showLbDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    Box(Modifier.settingsAnchor(stringResource(R.string.search_lastfm_scrobbling))) {
        SettingsGroupHeader("Last.fm")
    }
    SettingItem(
        title = stringResource(R.string.settings_your_api_key),
        subtitle = when {
            apiKey.isNotBlank() && apiSecret.isNotBlank() ->
                stringResource(R.string.settings_lastfm_key_set)
            apiKey.isNotBlank() ->
                stringResource(R.string.settings_lastfm_secret_missing)
            chartsKeyAvailable ->
                stringResource(R.string.settings_lastfm_not_set_charts)
            else ->
                stringResource(R.string.settings_lastfm_not_set)
        },
        onClick = { showApiKeyDialog = true }
    )
    SettingItem(
        title = "Last.fm",
        subtitle = when {
            lastFmEnabled -> stringResource(R.string.settings_connected_as, lastFmUsername ?: stringResource(R.string.settings_user))
            lastFmConnecting -> stringResource(R.string.settings_waiting_for_lastfm)
            else -> stringResource(R.string.settings_lastfm_tap_to_authorise)
        },
        // No text box. A session key is not something a person has — it comes
        // out of auth.getSession, which needs the browser handshake this
        // starts. Asking anyone to paste one was asking for something only a
        // developer with a terminal could produce.
        onClick = { if (!lastFmEnabled) viewModel.connectLastFm(context) }
    )
    if (lastFmEnabled) {
        TextButton(onClick = { viewModel.clearLastFmSession() }) {
            Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
        }
    }
    lastFmAuthError?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        TextButton(onClick = { viewModel.clearLastFmError() }) { Text(stringResource(R.string.action_dismiss)) }
    }

    Spacer(modifier = Modifier.height(16.dp))
    SettingsGroupHeader("ListenBrainz")
    SettingItem(
        title = "ListenBrainz",
        subtitle = if (lbEnabled) stringResource(R.string.connected) else stringResource(R.string.settings_not_connected),
        onClick = { showLbDialog = true }
    )
    if (lbEnabled) {
        TextButton(onClick = { viewModel.clearListenBrainzToken() }) {
            Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
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
        SettingsGroupHeader(stringResource(R.string.settings_playback))
        SettingSwitchItem(
            title = stringResource(R.string.settings_gapless_playback),
            subtitle = stringResource(R.string.settings_remove_silence_between_tracks),
            checked = gapless,
            onCheckedChange = { viewModel.setGaplessPlayback(it) }
        )

        SettingSwitchItem(
            title = stringResource(R.string.settings_never_resample_between_tracks),
            subtitle = stringResource(R.string.settings_a_track_at_a_different_sample_rate_starts_after),
            checked = gaplessNoResample,
            onCheckedChange = { viewModel.setGaplessNoResample(it) }
        )

        // Sits under the gapless toggle because the two decide the same thing:
        // what happens between one track and the next. They're also mutually
        // exclusive in effect — any blend above 0s overlaps the tracks, so the
        // gapless hand-off steps aside for it.
        Text(
            text = if (crossfade == 0) stringResource(R.string.settings_blend_gapless) else stringResource(R.string.settings_blend_seconds, crossfade),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.settingsAnchor(stringResource(R.string.search_crossfade)),
        )
        Text(
            text = if (crossfade == 0) {
                stringResource(R.string.settings_blend_zero_desc)
            } else {
                stringResource(R.string.settings_blend_desc, crossfade)
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
        SettingsGroupHeader(stringResource(R.string.settings_streaming_quality))
        SettingItem(title = stringResource(R.string.settings_wi_fi_streaming), subtitle = wifiQuality.displayName, onClick = { showWifiDropdown = true })
        DropdownMenu(expanded = showWifiDropdown, onDismissRequest = { showWifiDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setWifiQuality(q); showWifiDropdown = false })
            }
        }

        SettingItem(title = stringResource(R.string.settings_cellular_streaming), subtitle = cellularQuality.displayName, onClick = { showCellularDropdown = true })
        DropdownMenu(expanded = showCellularDropdown, onDismissRequest = { showCellularDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setCellularQuality(q); showCellularDropdown = false })
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_audio_processing))
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

        // One group, not two. "Spatial Audio" held nothing but the Atmos row,
        // and that row is the multichannel renderer's own settings — channel
        // map, downmix coefficients, binaural render. It belongs against the
        // switch that decides whether any of it runs, so it sits directly
        // above the downmix toggle instead of under a heading of its own.
        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_output))
        DspBlockSizeSelector(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
        UsbBitPerfectToggle(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
        SettingItem(
            title = stringResource(R.string.settings_atmos_renderer_configuration),
            subtitle = stringResource(R.string.settings_channel_map_coefficient_downmix_optional_sofa),
            onClick = { navController.navigateTool(Screen.AtmosRenderer) },
        )

        Spacer(modifier = Modifier.height(8.dp))
        MultichannelDownmixToggle(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
        ChannelDetectorCard(viewModel)

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_playback_speed))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Slider(
                value = playbackSpeed,
                onValueChange = { newSpeed ->
                    // Was rounded to 0.01 before storing, which put an exact
                    // equal-tempered interval as much as 13.5 cents out. Store
                    // the ratio at full precision and snap it onto a semitone
                    // only when the drag is already within a few cents of one;
                    // the field keeps showing two decimals.
                    val exact = PitchRatio.snap(newSpeed)
                    speedText = String.format(Locale.US, "%.2f", exact)
                    viewModel.setPlaybackSpeed(exact)
                },
                valueRange = PitchRatio.MIN_SPEED..PitchRatio.MAX_SPEED,
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
                Text(stringResource(R.string.action_reset))
            }
        }

        // With preserve-pitch off the speed ratio is also the interval, so
        // name it — landing on an exact semitone is the point of the snapping
        // above, and "1.12x" alone doesn't say whether you did.
        if (!preservePitch) {
            Text(
                text = if (PitchRatio.isOnSemitone(playbackSpeed)) {
                    val semitones = PitchRatio.nearestSemitone(playbackSpeed)
                    pluralStringResource(
                        R.plurals.settings_pitch_exact, kotlin.math.abs(semitones), PitchRatio.formatSemitones(semitones),
                    )
                } else {
                    stringResource(R.string.settings_pitch_fraction, PitchRatio.semitonesFor(playbackSpeed))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        SettingSwitchItem(
            title = stringResource(R.string.settings_preserve_pitch),
            subtitle = stringResource(R.string.settings_keep_original_pitch_when_changing_speed),
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
        text = stringResource(R.string.settings_dsp_block_size),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.settings_smaller_lower_latency_more_cpu_default_1024),
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
        title = stringResource(R.string.settings_downmix_multichannel_to_stereo),
        subtitle = if (enabled) {
            stringResource(R.string.settings_downmix_on)
        } else {
            stringResource(R.string.settings_downmix_off)
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
        title = if (recording) stringResource(R.string.settings_stop_debug_recording) else stringResource(R.string.settings_debug_recording),
        subtitle = when {
            recording -> stringResource(R.string.settings_debug_recording_active)
            lastSaved != null -> stringResource(R.string.settings_debug_recording_saved, lastSaved!!)
            else -> stringResource(R.string.settings_debug_recording_idle)
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
        text = stringResource(R.string.settings_channel_detector),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    val s = state
    if (s == null) {
        Text(
            text = stringResource(R.string.settings_idle_play_a_track_to_detect_its_channel_layout),
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
        title = stringResource(R.string.settings_usb_dac_bit_perfect_routing),
        subtitle = when {
            !enabled -> stringResource(R.string.settings_usb_off)
            deviceName != null -> stringResource(R.string.settings_usb_on_device, deviceName!!)
            else -> stringResource(R.string.settings_usb_on_waiting)
        },
        checked = enabled,
        onCheckedChange = { viewModel.setUsbBitPerfectEnabled(it) },
    )

    val hiResHal by viewModel.hiResHalOutputEnabled.collectAsStateWithLifecycle()
    SettingSwitchItem(
        title = stringResource(R.string.settings_hi_res_output_bluetooth_speaker),
        subtitle = if (hiResHal) {
            stringResource(R.string.settings_hires_on)
        } else {
            stringResource(R.string.settings_hires_off)
        },
        checked = hiResHal,
        onCheckedChange = { viewModel.setHiResHalOutputEnabled(it) },
    )

    val exclusiveEnabled by viewModel.usbExclusiveBitPerfectEnabled.collectAsStateWithLifecycle()
    val exclusiveStatus by viewModel.usbExclusiveStatus.collectAsStateWithLifecycle()
    val diagnostics by viewModel.usbBypassDiagnostics.collectAsStateWithLifecycle()
    val failure by viewModel.usbBypassFailure.collectAsStateWithLifecycle()
    val supportedRates by viewModel.usbBypassSupportedRates.collectAsStateWithLifecycle()
    val dacInfo by viewModel.usbDacInfo.collectAsStateWithLifecycle()
    SettingSwitchItem(
        title = stringResource(R.string.settings_exclusive_usb_dac_bypass_android_audio),
        subtitle = exclusiveSubtitle(
            exclusiveEnabled, exclusiveStatus, failure
        ),
        checked = exclusiveEnabled,
        onCheckedChange = { viewModel.setUsbExclusiveBitPerfectEnabled(it) },
    )
    // Only render the diagnostic card when the toggle is on AND we
    // have something honest to say — either an active stream, a
    // categorised failure, a known rate inventory, or a DAC we can
    // actually name. Hidden the rest of the time so the toggle row
    // stays clean.
    if (exclusiveEnabled &&
        (diagnostics != null || failure != null || supportedRates.isNotEmpty() || dacInfo != null)) {
        BypassDiagnosticsCard(
            diagnostics = diagnostics,
            failure = failure,
            supportedRates = supportedRates,
            dacInfo = dacInfo,
        )
    }
}

/**
 * Renders a compact info card beneath the exclusive-USB toggle:
 *   - DAC identity (when a device is owned): manufacturer/product name,
 *     VID:PID, USB version, class triple, serial when granted
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
    dacInfo: tf.monochrome.android.audio.usb.DacInfo? = null,
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
            if (dacInfo != null) {
                Text(
                    text = "DAC",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = dacInfo.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(dacInfo.descriptorLine)
                        dacInfo.serialNumber?.takeIf { it.isNotBlank() }?.let {
                            append(" · SN ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (diagnostics != null || failure != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            if (diagnostics != null) {
                Text(
                    text = stringResource(R.string.settings_active_stream),
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
                    text = stringResource(R.string.settings_bypass_failed),
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
                    text = stringResource(R.string.settings_supported_rates),
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
@Composable
private fun exclusiveSubtitle(
    enabled: Boolean,
    status: tf.monochrome.android.audio.usb.UsbExclusiveController.Status,
    failure: tf.monochrome.android.audio.usb.StartFailure?,
): String {
    if (!enabled) {
        return stringResource(R.string.settings_exclusive_off)
    }
    return when (status) {
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Disabled ->
            stringResource(R.string.settings_exclusive_starting)
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.NoDevice ->
            stringResource(R.string.settings_exclusive_no_device)
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.AwaitingPermission ->
            stringResource(R.string.settings_exclusive_permission)
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.DeviceOpen ->
            stringResource(R.string.settings_exclusive_open)
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.InterfaceClaimed ->
            stringResource(R.string.settings_exclusive_claimed)
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Streaming ->
            stringResource(R.string.settings_exclusive_streaming)
        // The Error subtitle used to hardcode the kernel-claim story.
        // Now we defer to whichever StartFailure category the native
        // side reported — claim failures, rate-negotiation failures,
        // alloc failures all surface as their own actionable line. If
        // somehow we're in Error with no failure recorded (shouldn't
        // happen but: defensive), fall back to the old text.
        tf.monochrome.android.audio.usb.UsbExclusiveController.Status.Error ->
            failure?.actionableMessage()?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.settings_exclusive_error)
    }
}

// ─── Tab 6: Downloads ──────────────────────────────────────────────────
@Composable
private fun DownloadsTab(viewModel: SettingsViewModel) {
    val downloadQuality by viewModel.downloadQuality.collectAsStateWithLifecycle()
    val downloadFolder by viewModel.downloadFolderUri.collectAsStateWithLifecycle()
    var showQualityDropdown by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val downloadedCount by viewModel.downloadedCount.collectAsStateWithLifecycle()
    val downloadedSize by viewModel.downloadedSize.collectAsStateWithLifecycle()
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
            title = { Text(stringResource(R.string.settings_clear_downloads)) },
            // Named, not "all downloaded tracks". A number and a size are what
            // tell you whether this is the three podcasts you meant or the
            // album you spent an evening on a hotel connection fetching.
            text = {
                Text(
                    pluralStringResource(
                        R.plurals.settings_clear_downloads_warning, downloadedCount, downloadedCount,
                        downloadedSize?.let { stringResource(R.string.settings_size_suffix, it) } ?: "",
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllDownloads()
                    showClearDialog = false
                }) { Text(stringResource(R.string.settings_delete_all), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    SettingsTabContent {
        // Moved off Library, which had it under a "Liked Songs" header of its
        // own. Liking a song is a library action; downloading one is not, and
        // every other download setting was already here.
        SettingsGroupHeader(stringResource(R.string.settings_automatic_downloads))
        val autoDownloadLiked by viewModel.autoDownloadLikedSongs.collectAsStateWithLifecycle()
        SettingSwitchItem(
            title = stringResource(R.string.settings_auto_download_liked_songs),
            subtitle = stringResource(R.string.settings_keep_a_copy_of_every_song_you_like_from_now_on),
            checked = autoDownloadLiked,
            onCheckedChange = { viewModel.setAutoDownloadLikedSongs(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_download_quality))
        SettingItem(title = stringResource(R.string.settings_quality), subtitle = downloadQuality.displayName, onClick = { showQualityDropdown = true })
        DropdownMenu(expanded = showQualityDropdown, onDismissRequest = { showQualityDropdown = false }) {
            AudioQuality.entries.forEach { q ->
                DropdownMenuItem(text = { Text(q.displayName) }, onClick = { viewModel.setDownloadQuality(q); showQualityDropdown = false })
            }
        }

        val dlLyrics by viewModel.downloadLyrics.collectAsStateWithLifecycle()
        SettingSwitchItem(
            title = stringResource(R.string.settings_download_lyrics),
            subtitle = stringResource(R.string.settings_bundle_lrc_files_with_downloaded_tracks),
            checked = dlLyrics,
            onCheckedChange = { viewModel.setDownloadLyrics(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_download_folder))

        // remember so the DocumentFile ContentResolver lookup runs only when the
        // folder actually changes, not on every recomposition of this screen.
        val customFolderLabel = stringResource(R.string.settings_custom_folder)
        val internalStorageLabel = stringResource(R.string.settings_internal_storage_default)
        val folderDisplay = remember(downloadFolder, context, customFolderLabel) {
            downloadFolder?.let { folder ->
                try {
                    val uri = folder.toUri()
                    val docFile = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                    docFile?.name ?: customFolderLabel
                } catch (_: Exception) { customFolderLabel }
            } ?: internalStorageLabel
        }

        SettingItem(
            title = stringResource(R.string.settings_save_location),
            subtitle = folderDisplay,
            onClick = { folderPickerLauncher.launch(null) }
        )

        if (downloadFolder != null) {
            TextButton(onClick = { viewModel.setDownloadFolderUri(null) }) {
                Text(stringResource(R.string.settings_reset_to_default), color = MaterialTheme.colorScheme.primary)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        // Not "Storage" — System has a group by that name for the cache, and
        // two identically-titled headers in different tabs read as the same
        // setting reachable from two places. This one is about the files.
        SettingsGroupHeader(stringResource(R.string.settings_downloaded_files))
        // The warning goes above the button, not only in the dialog it opens.
        // A confirmation you meet after committing to the tap is a speed bump;
        // what stops the wrong tap is knowing beforehand that there is
        // something here to lose, and how much of it.
        if (downloadedCount > 0) {
            SettingCaution(
                pluralStringResource(
                    R.plurals.settings_clear_downloads_caution, downloadedCount, downloadedCount,
                    downloadedSize?.let { stringResource(R.string.settings_size_suffix, it) } ?: "",
                )
            )
        } else {
            Text(
                stringResource(R.string.settings_nothing_downloaded),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        OutlinedButton(
            onClick = { showClearDialog = true },
            // A destructive-looking button that does nothing still costs a
            // moment of worry to press.
            enabled = downloadedCount > 0,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_clear_all_downloads))
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
            title = { Text(stringResource(R.string.settings_spectrum_over_the_artwork)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_with_a_channel_set_the_spectrum_is_drawn_across),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_use_a_channel_you_don_t_mind_filling_up_a),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = input, onValueChange = { input = it },
                        label = { Text(stringResource(R.string.settings_channel_id)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDiscordUploadChannel(input)
                    showChannelDialog = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showChannelDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    var showDialog by rememberSaveable { mutableStateOf(false) }

    if (showDialog) {
        var tokenInput by rememberSaveable { mutableStateOf(token) }
        var appIdInput by rememberSaveable { mutableStateOf(applicationId) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(stringResource(R.string.settings_discord_token)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.settings_this_is_your_discord_account_token_not_a),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_discord_has_no_presence_api_for_phones_so_the),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = tokenInput, onValueChange = { tokenInput = it },
                        label = { Text(stringResource(R.string.settings_user_token)) },
                        // Replacing one is the common case, not entering the
                        // first: tokens rotate on every password change, and
                        // selecting seventy characters by hand to overwrite
                        // them is a worse job than it looks.
                        trailingIcon = {
                            if (tokenInput.isNotEmpty()) {
                                IconButton(onClick = { tokenInput = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.settings_clear_token))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = appIdInput, onValueChange = { appIdInput = it },
                        label = { Text(stringResource(R.string.settings_application_id_optional)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.settings_album_art_needs_an_application_id_a_presence_set),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDiscordCredentials(tokenInput, appIdInput)
                    showDialog = false
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    SettingsGroupHeader("Discord")
    SettingItem(
        title = stringResource(R.string.settings_token),
        subtitle = when {
            token.isBlank() -> stringResource(R.string.settings_discord_not_set)
            discordUser != null && applicationId.isBlank() ->
                stringResource(R.string.settings_discord_user_no_art, discordUser!!)
            discordUser != null -> "$discordUser"
            applicationId.isBlank() -> stringResource(R.string.settings_discord_set_no_art)
            else -> stringResource(R.string.settings_set)
        },
        onClick = { showDialog = true }
    )
    SettingSwitchItem(
        title = stringResource(R.string.settings_show_what_i_m_playing),
        subtitle = when {
            token.isBlank() -> stringResource(R.string.settings_discord_add_token_first)
            status == tf.monochrome.android.data.presence.DiscordPresenceManager.Status.CONNECTED ->
                stringResource(R.string.settings_discord_on_showing)
            status == tf.monochrome.android.data.presence.DiscordPresenceManager.Status.CONNECTING ->
                stringResource(R.string.settings_connecting)
            status == tf.monochrome.android.data.presence.DiscordPresenceManager.Status.FAILED ->
                stringResource(R.string.settings_couldnt_connect)
            enabled -> stringResource(R.string.settings_discord_on_waiting)
            else -> stringResource(R.string.state_off)
        },
        checked = enabled,
        onCheckedChange = { viewModel.setDiscordPresenceEnabled(it && token.isNotBlank()) }
    )
    SettingItem(
        title = stringResource(R.string.settings_spectrum_over_the_artwork),
        subtitle = if (uploadChannel.isBlank()) {
            stringResource(R.string.settings_spectrum_circle)
        } else {
            stringResource(R.string.settings_posting_to_channel, uploadChannel)
        },
        onClick = { showChannelDialog = true },
    )
    SettingSwitchItem(
        title = stringResource(R.string.settings_animated_spectrum),
        subtitle = stringResource(R.string.settings_a_moving_spectrum_beside_the_artwork_matched_to),
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
        TextButton(onClick = { viewModel.clearDiscordError() }) { Text(stringResource(R.string.action_dismiss)) }
    }
    if (token.isNotBlank()) {
        TextButton(onClick = { viewModel.clearDiscordCredentials() }) {
            Text(stringResource(R.string.settings_forget_token), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ConnectionsTab(viewModel: SettingsViewModel) {
    SettingsTabContent {
        ApiServersSection(viewModel)
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
            subtitle = stringResource(R.string.settings_connected_as, userName ?: "…"),
        )
        OutlinedButton(onClick = { spotifyViewModel.disconnect() }) {
            Text(stringResource(R.string.settings_disconnect_spotify))
        }
    } else {
        Text(
            stringResource(R.string.settings_connect_your_spotify_account_to_import_playlists),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Button(
            onClick = { spotifyViewModel.connect(context) },
            enabled = !connecting
        ) {
            Text(if (connecting) stringResource(R.string.settings_connecting) else stringResource(R.string.settings_connect_spotify))
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
            title = { Text(stringResource(R.string.settings_sign_out)) },
            text = { Text(stringResource(R.string.settings_you_ll_stop_syncing_favorites_and_playlists)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    viewModel.logout()
                }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    SettingsGroupHeader(stringResource(R.string.account))
    if (isLoggedIn) {
        SettingItem(
            title = stringResource(R.string.settings_signed_in_as),
            subtitle = userEmail ?: stringResource(R.string.unknown),
        )
        OutlinedButton(onClick = { showSignOutDialog = true }) {
            Text(stringResource(R.string.sign_out))
        }
    } else {
        Text(
            stringResource(R.string.settings_sign_in_to_sync_favorites_and_playlists_across),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            stringResource(R.string.settings_use_the_account_page_to_sign_in_with_google_or),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Icon(Icons.Default.Check, contentDescription = stringResource(R.string.settings_online), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
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
                            android.widget.Toast.makeText(context, context.getString(R.string.settings_failed_to_read_file), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        android.widget.Toast.makeText(context, context.getString(R.string.settings_error_reading_file), android.widget.Toast.LENGTH_SHORT).show()
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
                        context.getString(if (ok) R.string.settings_backup_saved else R.string.settings_failed_to_save_backup),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    if (showClearAllDialog) {
        AlertDialog(
            onDismissRequest = { showClearAllDialog = false },
            title = { Text(stringResource(R.string.settings_reset_settings_cache)) },
            // Reworded to match what clearAllData() actually does: it resets
            // preferences and wipes the cache but leaves the Room library
            // untouched, and the app does not restart. The old copy promised
            // to clear "all local data" and restart, neither of which happened.
            text = { Text(stringResource(R.string.settings_this_resets_all_app_settings_and_clears_cached)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearAllData()
                    showClearAllDialog = false
                }) { Text(stringResource(R.string.action_reset), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    SettingsTabContent {
        SettingsGroupHeader(stringResource(R.string.settings_storage))
        SettingItem(title = stringResource(R.string.settings_cache_size), subtitle = cacheSize)
        OutlinedButton(onClick = { viewModel.clearCache() }) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_clear_cache))
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader(stringResource(R.string.settings_data))
        SettingItem(
            title = stringResource(R.string.settings_restart_onboarding),
            subtitle = stringResource(R.string.settings_run_the_first_time_setup_again_your_library_and),
            onClick = { viewModel.restartOnboarding() }
        )
        OutlinedButton(
            onClick = { showClearAllDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.settings_reset_settings_cache))
        }

        Spacer(modifier = Modifier.height(20.dp))
        SettingsGroupHeader(stringResource(R.string.settings_backup_restore))
        Text(
            stringResource(R.string.settings_export_or_import_your_library_and_history_as_a),
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
                Text(stringResource(R.string.settings_export_json))
            }
            OutlinedButton(onClick = {
                filePickerLauncher.launch(arrayOf("application/json", "*/*"))
            }) {
                Text(stringResource(R.string.settings_import_json))
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        // Frame rate and resolution are whole-app settings and were sitting in
        // Appearance under a "Display" header, next to the explicit-badge
        // toggle. They cost battery and heat, which makes them System's
        // business — the visualizer's own GPU settings stay with the visualizer.
        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_performance))

        // Low performance mode. The master owns nothing of its own — it writes
        // the three switches below it, and they write it back — so the row is
        // a shortcut, not a fourth piece of state that can drift.
        val lowPerformanceMode by viewModel.lowPerformanceMode.collectAsStateWithLifecycle()
        val disableAnimations by viewModel.disableAnimations.collectAsStateWithLifecycle()
        val legacyPlayer by viewModel.legacyPlayer.collectAsStateWithLifecycle()
        val disableLiquidGlass by viewModel.disableLiquidGlass.collectAsStateWithLifecycle()
        SettingSwitchItem(
            title = stringResource(R.string.settings_low_performance_mode),
            subtitle = stringResource(R.string.settings_turns_off_animations_the_liquid_glass_effect_and),
            checked = lowPerformanceMode,
            onCheckedChange = { viewModel.setLowPerformanceMode(it) }
        )
        Column(modifier = Modifier.padding(start = 16.dp)) {
            SettingSwitchItem(
                title = stringResource(R.string.settings_disable_animations),
                subtitle = stringResource(R.string.settings_no_transitions_bounces_glass_motion_or_colour),
                checked = disableAnimations,
                onCheckedChange = { viewModel.setDisableAnimations(it) }
            )
            SettingSwitchItem(
                title = stringResource(R.string.settings_legacy_player),
                subtitle = stringResource(R.string.settings_use_the_flat_player_design_from_before_liquid),
                checked = legacyPlayer,
                onCheckedChange = { viewModel.setLegacyPlayer(it) }
            )
            SettingSwitchItem(
                title = stringResource(R.string.settings_remove_liquid_glass),
                subtitle = stringResource(R.string.settings_flat_opaque_surfaces_instead_of_blurred),
                checked = disableLiquidGlass,
                onCheckedChange = { viewModel.setDisableLiquidGlass(it) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_display))

        // Game-style full screen: no status bar, no gesture bar. Every screen
        // pads from WindowInsets, so hiding the bars collapses those insets and
        // the content grows into the space on its own.
        val immersiveFullScreen by viewModel.immersiveFullScreen.collectAsStateWithLifecycle()
        SettingSwitchItem(
            title = stringResource(R.string.settings_full_screen),
            subtitle = stringResource(R.string.settings_hides_the_notification_bar_and_the_bottom),
            checked = immersiveFullScreen,
            onCheckedChange = { viewModel.setImmersiveFullScreen(it) }
        )

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_diagnostics))
        SettingItem(
            title = stringResource(R.string.settings_view_debug_log),
            subtitle = stringResource(R.string.settings_live_logcat_stream_for_this_process_copy_or),
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
        SupportSection(
            onKofi = { openDonationUrl(context, SupportLinks.KO_FI) },
            onPatreon = { openDonationUrl(context, SupportLinks.PATREON) },
        )

        Spacer(modifier = Modifier.height(28.dp))
        WhatsNewPanel(highlight = arrivedUnread)

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_updates))
        SettingItem(
            title = stringResource(R.string.settings_check_for_updates),
            subtitle = stringResource(R.string.settings_look_on_github_for_a_newer_release_now),
            onClick = { viewModel.checkForUpdatesNow() },
        )

        Spacer(modifier = Modifier.height(24.dp))
        SettingsGroupHeader(stringResource(R.string.settings_about_tryptify))
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.settings_version_line, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.settingsAnchor(stringResource(R.string.search_version)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.settings_open_source_ad_free_music_streaming),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The tip jar, and who's asking. */
@Composable
private fun SupportSection(onKofi: () -> Unit, onPatreon: () -> Unit) {
    SettingsGroupHeader(stringResource(R.string.settings_support_the_app))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Image(
            painter = painterResource(id = R.drawable.trypt_pfp),
            contentDescription = stringResource(R.string.settings_trypt_avatar),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.settings_tryptify_is_built_and_maintained_by_trypt_if_it),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(modifier = Modifier.height(20.dp))
        // Two ways to give, side by side and equally weighted, because they
        // are equally good from here — one is a one-off, the other is monthly,
        // and the app has no business steering that. Outlined rather than the
        // single filled button Ko-fi used to be: two filled buttons compete,
        // and a filled one beside an outlined one is a recommendation.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SupportButton(
                label = "Ko-fi",
                logo = R.drawable.logo_kofi,
                onClick = onKofi,
                modifier = Modifier.weight(1f),
            )
            SupportButton(
                label = "Patreon",
                logo = R.drawable.logo_patreon,
                onClick = onPatreon,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * One donation destination: its mark, then its name.
 *
 * The logo is tinted to the button's own content colour rather than carrying
 * the brand's — see the note in `logo_kofi.xml`. Both marks are corals close
 * enough that, side by side at 18dp, they would read as one brand twice.
 */
@Composable
private fun SupportButton(
    label: String,
    @DrawableRes logo: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(
            painter = painterResource(id = logo),
            contentDescription = null,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(label)
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
            bottom = 16.dp + LocalBottomChromeInset.current + navBar,
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
internal fun SettingsGroupHeader(title: String) {
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
fun SettingSwitchItem(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    badge: String? = null,
    caution: String? = null,
) {
    // [badge] and [caution] sit beside and under the row rather than being folded
    // into [title] and [subtitle]: the title is the anchor id that settings search
    // scrolls to and the DevEdit element id, so a "(beta)" spliced into it would
    // quietly move both. A caution also has to out-shout a subtitle to do its job
    // — the whole point is that it is read BEFORE the switch is flipped.
    tf.monochrome.android.devedit.DevEditable("sw_${devSlug(title)}", Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().settingsAnchor(title)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                        if (badge != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            SettingBadge(badge)
                        }
                    }
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = checked, onCheckedChange = onCheckedChange)
            }
            if (caution != null) SettingCaution(caution)
        }
    }
}

/**
 * One page in Settings › Library › Page Order: its name, whether it is shown,
 * and the controls to move it or gray it out.
 *
 * Not [SettingSwitchItem]: that row gives its whole width to a title, a subtitle
 * and one switch, and this one needs a title plus three controls. An eye rather
 * than a Switch for the same reason — a Switch wedged between a label and two
 * arrows crowds a narrow screen.
 *
 * The arrows stay live on a hidden row. A page you cannot move is a page whose
 * position you cannot fix before showing it again.
 */
@Composable
private fun PageOrderRow(
    title: String,
    visible: Boolean,
    canToggle: Boolean,
    onToggleVisible: () -> Unit,
    // A tab row only shows or hides; its place in the bar is fixed.
    reorderable: Boolean = true,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    onUp: () -> Unit = {},
    onDown: () -> Unit = {},
) {
    val dim = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    tf.monochrome.android.devedit.DevEditable("page_${devSlug(title)}", Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().settingsAnchor(title).padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
                    .copy(alpha = if (visible) 1f else 0.38f),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleVisible, enabled = canToggle) {
                Icon(
                    if (visible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    // Disabled on the last visible Library section: hiding it
                    // would leave the Library tab nothing to open.
                    contentDescription = if (visible) "Hide $title" else "Show $title",
                    tint = if (canToggle) MaterialTheme.colorScheme.onSurface else dim,
                )
            }
            if (reorderable) IconButton(onClick = onUp, enabled = canMoveUp) {
                Icon(
                    Icons.Default.KeyboardArrowUp,
                    contentDescription = stringResource(R.string.settings_move_up, title),
                    tint = if (canMoveUp) MaterialTheme.colorScheme.onSurface else dim,
                )
            }
            if (reorderable) IconButton(onClick = onDown, enabled = canMoveDown) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.settings_move_down, title),
                    tint = if (canMoveDown) MaterialTheme.colorScheme.onSurface else dim,
                )
            }
        }
    }
}

/** A small pill beside a setting's title — "BETA" and the like. */
@Composable
private fun SettingBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.20f),
    ) {
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}

/**
 * The warning under a setting that can make things worse rather than better.
 *
 * Deliberately not a subtitle: a subtitle describes what a switch does and gets
 * skimmed past on the way to the switch. This is for the ones where the honest
 * answer is "most people should leave this alone", and it has to be legible
 * enough to stop that hand.
 */
@Composable
private fun SettingCaution(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp, end = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
    }
}

// ─── Tab 5: Library Settings ──────────────────────────────────────────
@Composable
private fun LibrarySettingsTab(viewModel: SettingsViewModel) {
    val pageOrder by viewModel.pageOrder.collectAsStateWithLifecycle()
    val hiddenPages by viewModel.hiddenPages.collectAsStateWithLifecycle()

    // Every other tab wraps in SettingsTabContent; this one rolled its own
    // LazyColumn and its own section headings, so its groups sat at a
    // different indent and size to the rest of Settings. Normalised: one
    // wrapper, SettingsGroupHeader throughout — which also gives the rows
    // stable DevEdit ids like everywhere else.
    SettingsTabContent {
        SettingsGroupHeader(stringResource(R.string.settings_local_media_scanning))

        Spacer(modifier = Modifier.height(8.dp))
        val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
        val scanContext = LocalContext.current
        OutlinedButton(
            onClick = {
                viewModel.rescanLibrary()
                android.widget.Toast.makeText(scanContext, scanContext.getString(R.string.settings_scanning_library), android.widget.Toast.LENGTH_SHORT).show()
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isScanning) "Scanning…" else "Rescan Library Now")
        }

        Spacer(modifier = Modifier.height(16.dp))
        // This was one "Page Order" list over every page, back when pages were
        // picked from a list on Home. With the tab bar the stored order does two
        // separate jobs, so it is shown as two groups. Both titles are entries
        // in SettingsSearchIndex — a test greps these files for every one.
        SettingsGroupHeader(stringResource(R.string.settings_tab_bar))
        Text(
            stringResource(R.string.settings_home_library_and_search_are_always_there_turn),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        listOf(
            tf.monochrome.android.ui.navigation.Screen.Discover.route,
            tf.monochrome.android.ui.navigation.RADIO_PAGE_ID,
        ).forEach { pageId ->
            PageOrderRow(
                title = tf.monochrome.android.ui.navigation.pageTitle(pageId),
                visible = pageId !in hiddenPages,
                canToggle = canTogglePageVisibility(pageOrder, hiddenPages, pageId),
                reorderable = false,
                onToggleVisible = { viewModel.setPageVisible(pageId, pageId in hiddenPages) },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        SettingsGroupHeader(stringResource(R.string.settings_library_sections))
        Text(
            stringResource(R.string.settings_the_order_of_the_switcher_at_the_top_of_library),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        // Hidden sections included and kept in their slot, so showing one again
        // puts it back where it was rather than at the end.
        val sections = pageOrder.filter { it in tf.monochrome.android.ui.navigation.LIBRARY_PAGE_IDS }
        sections.forEachIndexed { index, pageId ->
            PageOrderRow(
                title = tf.monochrome.android.ui.navigation.pageTitle(pageId),
                visible = pageId !in hiddenPages,
                canToggle = canTogglePageVisibility(pageOrder, hiddenPages, pageId),
                canMoveUp = index > 0,
                canMoveDown = index < sections.lastIndex,
                onUp = { viewModel.moveLibrarySection(pageId, -1) },
                onDown = { viewModel.moveLibrarySection(pageId, +1) },
                onToggleVisible = { viewModel.setPageVisible(pageId, pageId in hiddenPages) },
            )
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
        title = stringResource(R.string.settings_what_s_new),
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
                // Two levels of heading, and they answer different questions.
                // The outer one is what KIND of change it is — what is new, what
                // behaves differently, what is gone — because that is the thing
                // a reader is usually scanning for and a flat list makes all
                // three the same search. The inner one is what part of the app
                // it touches.
                //
                // Unclassified entries print first, with no heading at all, the
                // way the whole list did before there were groups: the releases
                // already shipped were written flat, and inventing a label for
                // each of them after the fact would be a hundred guesses.
                val grouped = listOf<WhatsNewKind?>(null) + WhatsNewKind.entries
                grouped.forEach { kind ->
                    val entries = release.entries.filter { it.kind == kind }
                    if (entries.isEmpty()) return@forEach

                    if (kind != null) {
                        Text(
                            text = kind.heading,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 16.dp, bottom = 2.dp),
                        )
                    }

                    // Sections are announced when they start, so consecutive
                    // entries under one heading print it once. Reset per kind:
                    // the same section can open again under the next one.
                    var section: String? = null
                    entries.forEach { entry ->
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
        SettingsGroupHeader(stringResource(R.string.settings_playlist_import))
        val spotifyViewModel: SpotifyImportViewModel = hiltViewModel()
        val spotifyConnected by spotifyViewModel.isConnected.collectAsStateWithLifecycle()
        val spotifyUserName by spotifyViewModel.userName.collectAsStateWithLifecycle()
        val importProgress by spotifyViewModel.importProgress.collectAsStateWithLifecycle()
        val isImporting by spotifyViewModel.isImporting.collectAsStateWithLifecycle()
        var showSpotifyPicker by remember { mutableStateOf(false) }
        var importUrl by remember { mutableStateOf("") }

        val onImportResult: (Boolean, UiText) -> Unit = { success, msg ->
            android.widget.Toast.makeText(context, msg.resolve(context), android.widget.Toast.LENGTH_LONG).show()
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
            label = { Text(stringResource(R.string.settings_spotify_playlist_url)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = spotifyConnected,
            supportingText = if (!spotifyConnected) {
                { Text(stringResource(R.string.settings_connect_spotify_on_the_connections_tab_to_enable)) }
            } else null
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { spotifyViewModel.importByUrl(context, importUrl, onResult = onImportResult) },
                enabled = spotifyConnected && importUrl.isNotBlank() && !isImporting
            ) {
                Text(stringResource(R.string.settings_import_playlist))
            }
            OutlinedButton(
                onClick = {
                    spotifyViewModel.loadMyPlaylists()
                    showSpotifyPicker = true
                },
                enabled = spotifyConnected && !isImporting
            ) {
                Text(stringResource(R.string.settings_browse_my_playlists))
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
                text = entry.displayTitle(),
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
                text = stringResource(settingsTabLabelRes(entry.tabLabel)),
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
    Column(modifier = Modifier.fillMaxWidth().settingsAnchor(stringResource(R.string.settings_light_paper))) {
        Text(
            text = stringResource(R.string.settings_light_paper),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_the_ground_every_light_theme_is_printed_on_crisp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PaperSwatch(
                label = stringResource(R.string.settings_crisp),
                paper = Paper.Crisp,
                selected = paper != "warm",
                onClick = { onPaperChange("crisp") },
                modifier = Modifier.weight(1f),
            )
            PaperSwatch(
                label = stringResource(R.string.settings_warm),
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
 * The first stop was **Match blend**, and it was the default: the fade took its
 * length from "Blend Between Tracks" so the picture and the sound moved
 * together. That pairing is real, and the price of it was not obvious from the
 * slider — a four-second blend is an ordinary setting, and it made the left end
 * of this control read "Match blend · 4.00 s" and repaint the whole window for
 * four seconds on every track change. The stops start at zero now and the
 * default is a few hundred milliseconds; anyone who wants the old behaviour can
 * read their blend and dial the same number.
 *
 * The value is committed on release rather than on every pixel of the drag: it
 * is written to disk and read by the player, the mini player and the theme, and
 * a drag across the whole track would otherwise be forty writes.
 */
@Composable
private fun ColorTransitionSetting(
    millis: Int,
    onMillisChange: (Int) -> Unit,
) {
    val stops = ColorBlend.steps
    // An unrecognised stored value — an older build's -1 for "match blend", or
    // a hand-edited one — is resolved to a real length first, so the thumb
    // lands on the stop that value actually behaves like rather than off the
    // end of the track.
    val stored = stops.indexOf(ColorBlend.millisFor(millis)).coerceAtLeast(0)
    var position by remember(stored) { mutableFloatStateOf(stored.toFloat()) }
    val selected = stops[position.roundToInt().coerceIn(stops.indices)]

    fun seconds(ms: Int) = String.format(Locale.US, "%.2f s", ms / 1000f)
    val readout = if (selected == 0) "Instant" else seconds(selected)

    Column(modifier = Modifier.fillMaxWidth().settingsAnchor(stringResource(R.string.settings_color_transition))) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.settings_color_transition),
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
            text = stringResource(R.string.settings_how_long_the_album_s_colours_take_to_cross_over),
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

/** What the player's view-mode picker calls each mode, in the reader's language. */
@Composable
private fun viewModeLabel(mode: NowPlayingViewMode): String = stringResource(
    when (mode) {
        NowPlayingViewMode.COVER_ART -> R.string.settings_view_cover_art
        NowPlayingViewMode.LYRICS -> R.string.mode_lyrics
        NowPlayingViewMode.QUEUE -> R.string.queue
        NowPlayingViewMode.VISUALIZER -> R.string.visualizer
    }
)
