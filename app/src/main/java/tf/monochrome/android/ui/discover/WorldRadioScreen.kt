package tf.monochrome.android.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import tf.monochrome.android.domain.model.GlobeFxSettings
import tf.monochrome.android.domain.model.RadioCity
import tf.monochrome.android.domain.model.RadioCountry
import tf.monochrome.android.domain.model.RadioStation
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerViewModel
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.reduceMotion
import java.util.Locale
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

/**
 * World radio — the Earth as one picture, with every city you can tune into.
 *
 * A globe rather than a flat map, and drawn rather than photographed. An
 * orthographic projection is the honest one for a sphere you spin with your
 * thumb: what you see is what you would see from space, the far side is behind
 * the near side rather than smeared across the edges, and nothing has to lie
 * about Greenland. The coastlines are Natural Earth's, ~5,100 points of public
 * domain vector, so the whole thing follows the app's palette and its light and
 * dark themes instead of shipping a photograph that follows neither.
 *
 * The dots are the point. Every one is a city that **actually broadcasts** —
 * verified at build time against the radio-browser directory, 1,611 of 6,298
 * candidate cities — and its size is how many stations were found there. A city
 * nobody broadcasts from is absent rather than drawn empty, because a dot on
 * this globe is a claim that you can tune into something, and an empty one would
 * be a lie you can tap.
 *
 * What the panel lists is fetched live, not baked. Stream URLs rot far faster
 * than an app ships — the directory has thousands go dark between releases — so
 * the geography travels in the APK and the stations are asked for when you tap.
 * That is also why the panel distinguishes "couldn't reach the directory" from
 * "nothing here": they are different claims and only one of them is about the
 * city.
 *
 * The panel is the genre map's own card, from the same shared component, and for
 * the same reason it exists there: it floats over a full-bleed canvas above the
 * mini player, and two sheets of glass tuned differently an inch apart look like
 * a bug.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldRadioScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: WorldRadioViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val globe by viewModel.globe.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val stations by viewModel.stations.collectAsStateWithLifecycle()
    val nearby by viewModel.nearby.collectAsStateWithLifecycle()
    val favourites by viewModel.favourites.collectAsStateWithLifecycle()

    val density = LocalDensity.current
    val instant = reduceMotion()
    val scope = rememberCoroutineScope()

    var camera by remember { mutableStateOf(GlobeCamera()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    // Seeded rather than zero, for the same reason the genre map seeds its own:
    // the panel only exists once a city is selected, so a measured-only value
    // would make the very first selection spin to the wrong place and correct
    // itself a frame later.
    var panelHeightPx by remember { mutableIntStateOf(with(density) { 260.dp.roundToPx() }) }
    var flight by remember { mutableStateOf<Job?>(null) }

    // Read at gesture time rather than captured. Keying the pointer handlers on
    // the camera would rebuild them on every frame of a spin and drop the
    // pointer stream mid-gesture — the bug the genre map documents.
    val liveCamera = rememberUpdatedState(camera)
    val liveCities = rememberUpdatedState(globe.cities)
    val liveScale = rememberUpdatedState(globe.scale)

    val mapHaze = rememberHazeState()
    val playing by playerViewModel.currentTrack.collectAsStateWithLifecycle()
    val panelBottomInset = if (playing != null) MINI_PLAYER_RESERVE else 0.dp
    val glassSettings by playerViewModel.miniPlayerGlass.collectAsStateWithLifecycle()

    var topBarHeightPx by remember { mutableIntStateOf(0) }

    val storedFx by viewModel.globeFx.collectAsStateWithLifecycle()
    var showFxSheet by remember { mutableStateOf(false) }

    // The sliders write here and the globe reads here, so a drag repaints at
    // once. Persisting used to happen on every frame of the drag — a DataStore
    // write, a flow emission and a recomposition, sixty times a second — which
    // is what made the sliders crawl. The write is now debounced behind the
    // live preview, the same split the EQ's knobs already use.
    var globeFx by remember { mutableStateOf(storedFx) }
    LaunchedEffect(storedFx) { globeFx = storedFx }
    LaunchedEffect(globeFx) {
        if (globeFx != storedFx) {
            delay(FX_PERSIST_DEBOUNCE_MS)
            viewModel.updateGlobeFx { globeFx }
        }
    }

    // Projected geometry, reused by every draw that only changes colour.
    val outlines = remember { OutlineCache() }

    val searchOpen by viewModel.searchOpen.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()

    fun spinTo(city: RadioCity) {
        flight?.cancel()
        if (canvasSize == IntSize.Zero) return
        // The strip the card will occupy once it is up. Measured, not guessed —
        // and re-run when the measurement changes, which is what the height
        // quantum in the effect below is for.
        val panelStrip = with(density) { panelBottomInset.toPx() } + panelHeightPx
        flight = scope.launch {
            spinToCity(
                city = city,
                scale = globe.scale,
                from = camera,
                instant = instant,
                size = canvasSize,
                topInset = topBarHeightPx.toFloat(),
                bottomInset = panelStrip,
                onFrame = { camera = it },
            )
        }
    }

    // Selecting a city — by tapping the globe, or a nearby chip — turns the
    // Earth to face it. Keyed on the panel's measured height as well as the id,
    // so opening the panel re-aims rather than leaving the city underneath it.
    LaunchedEffect(selected?.id, canvasSize, panelHeightPx / FLIGHT_HEIGHT_QUANTUM) {
        selected?.let { spinTo(it) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("World radio") },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { viewModel.toggleSearch() }) {
                    Icon(
                        Icons.Default.Search,
                        contentDescription = if (searchOpen) "Close search" else "Search stations",
                        tint = if (searchOpen) MaterialTheme.colorScheme.primary
                        else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { showFxSheet = true }) {
                    Icon(Icons.Default.GraphicEq, contentDescription = "Outline glow")
                }
                IconButton(onClick = { flight?.cancel(); camera = GlobeCamera() }) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = "Recentre")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            modifier = Modifier.onSizeChanged { topBarHeightPx = it.height },
        )

        // The bar drops out of the app bar rather than replacing it, so the
        // globe never loses its title or its two toggles while you type — and
        // the predictions sit directly under the field they came from.
        AnimatedVisibility(
            visible = searchOpen,
            enter = if (instant) EnterTransition.None else expandVertically() + fadeIn(),
            exit = if (instant) ExitTransition.None else shrinkVertically() + fadeOut(),
        ) {
            CompositionLocalProvider(LocalPlayerGlass provides glassSettings) {
                StationSearchBar(
                    query = query,
                    state = suggestions,
                    hazeState = mapHaze,
                    glass = glassSettings,
                    onQueryChange = { viewModel.setQuery(it) },
                    onPickStation = { viewModel.playSearchResult(it, playerViewModel) },
                    onPickCity = { viewModel.select(it) },
                    onOpenCountry = { viewModel.openCountry(it) },
                    onClose = { viewModel.toggleSearch() },
                )
            }
        }

        Box(modifier = Modifier.fillMaxSize()) {
            val ocean = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
            val land = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            val dot = MaterialTheme.colorScheme.primary
            val onSurface = MaterialTheme.colorScheme.onSurface
            // Native-canvas text is sized in pixels, so the density conversion
            // happens here rather than in the draw pass. Matches the genre map.
            val labelPx = with(density) { 11.dp.toPx() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(mapHaze)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            flight?.cancel()
                            camera = liveCamera.value.spun(pan, zoom, size)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { point ->
                            val hit = hitTest(
                                point,
                                liveCities.value,
                                liveScale.value,
                                size.width,
                                size.height,
                                liveCamera.value,
                            ) ?: return@detectTapGestures
                            viewModel.select(hit)
                        }
                    },
            ) {
                drawGlobe(
                    data = globe,
                    camera = camera,
                    ocean = ocean,
                    land = land,
                    dot = dot,
                    selectedId = selected?.id,
                    labelColor = onSurface,
                    labelSizePx = labelPx,
                    // The globe runs full-bleed under a transparent bar, which
                    // is intended for the dots and unreadable for the names.
                    topInset = topBarHeightPx.toFloat(),
                    bottomInset = if (selected != null) {
                        panelBottomInset.toPx() + panelHeightPx
                    } else {
                        0f
                    },
                    accent = dot,
                    outlines = outlines,
                    fx = globeFx,
                )
            }

            // A second, near-empty canvas for the one thing on this screen that
            // animates. It draws a single ring and nothing else, so the frames it
            // asks for are cheap; folding it into the globe above would make each
            // one re-project every coastline point in the asset, which is what
            // made the station list scroll badly while a city was open.
            selected?.let { city ->
                SelectedCityPing(
                    city = city,
                    scale = globe.scale,
                    camera = camera,
                    accent = dot,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Text(
                text = if (globe.cities.isEmpty()) "" else "Dots are cities on air · sized by how many stations",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = MonoDimens.spacingLg, vertical = MonoDimens.spacingSm),
            )

            selected?.let { city ->
                CompositionLocalProvider(LocalPlayerGlass provides glassSettings) {
                    CityCard(
                        city = city,
                        stations = stations,
                        nearby = nearby,
                        favourites = favourites,
                        hazeState = mapHaze,
                        glass = glassSettings,
                        maxStationsHeight = with(density) {
                            (canvasSize.height * STATIONS_HEIGHT_FRACTION)
                                .coerceAtLeast(with(density) { 140.dp.toPx() })
                                .toDp()
                        },
                        onPlay = { viewModel.play(it, city, playerViewModel) },
                        onFavourite = { viewModel.toggleFavourite(it) },
                        onNearby = { viewModel.select(it) },
                        onDismiss = { viewModel.select(null) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .onSizeChanged { panelHeightPx = it.height }
                            .padding(bottom = panelBottomInset),
                    )
                }
            }

            if (globe.cities.isEmpty()) {
                Text(
                    text = "The globe didn't load.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }

    if (showFxSheet) {
        GlobeFxSheet(
            fx = globeFx,
            onChange = { globeFx = it },
            onReset = { globeFx = GlobeFxSettings(); viewModel.resetGlobeFx() },
            onDismiss = { showFxSheet = false },
        )
    }
}

// ── the controls ────────────────────────────────────────────────────────────

/** Tuning for how the globe's outlines are lit. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GlobeFxSheet(
    fx: GlobeFxSettings,
    onChange: (GlobeFxSettings) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Outline glow",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReset) { Text("Reset") }
            }
            Text(
                text = "Lights the coastlines and borders in the app's own accent colour.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GlobeFxSlider(
                label = "Illumination",
                valueLabel = if (fx.illumination <= 0.01f) "Off"
                else "${(fx.illumination * 100).toInt()}%",
                value = fx.illumination,
                range = 0f..1f,
                description = "How far the outlines shift from the map's ink toward the accent.",
            ) { onChange(fx.copy(illumination = it)) }

            GlobeFxSlider(
                label = "Glow",
                valueLabel = if (fx.glowBrightness <= 0.01f) "Off"
                else "${(fx.glowBrightness * 100).toInt()}%",
                value = fx.glowBrightness,
                range = 0f..1f,
                description = "Strength of the halo laid under each line.",
            ) { onChange(fx.copy(glowBrightness = it)) }

            GlobeFxSlider(
                label = "Glow width",
                valueLabel = fx.glowWidth.asMultiple(1),
                value = fx.glowWidth,
                range = 1f..8f,
                description = "How far the halo spreads either side of the line.",
            ) { onChange(fx.copy(glowWidth = it)) }
        }
    }
}

@Composable
private fun GlobeFxSlider(
    label: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    description: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        description?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * "2.40×" — fixed to [decimals], and to [Locale.US] rather than the device's.
 *
 * These are multipliers next to a slider, not prose: a locale that writes them
 * with a decimal comma reads as a thousands separator beside a number this
 * small. Formatted through the number alone and never through a string carrying
 * a literal `%`, which is a format specifier and would throw.
 */
private fun Float.asMultiple(decimals: Int): String =
    String.format(Locale.US, "%.${decimals}f×", this)

// ── search ──────────────────────────────────────────────────────────────────

/**
 * Find a station by name, anywhere on Earth.
 *
 * The globe's own way in is geographic — you find a place and see what it
 * broadcasts — which is the right default and useless when you already know
 * what you are looking for. This is the other way round, and it lands you in
 * the same place: picking a result flies the camera to the station's city and
 * tunes in, so a search still leaves you somewhere on the map rather than in a
 * list that has nothing to do with it.
 *
 * On the same sheet of glass as the city card, from the same settings, for the
 * reason that card documents — two panes tuned differently on one screen look
 * like a bug.
 */
@Composable
private fun StationSearchBar(
    query: String,
    state: SearchSuggestions,
    hazeState: dev.chrisbanes.haze.HazeState,
    glass: tf.monochrome.android.domain.model.PlayerGlassSettings,
    onQueryChange: (String) -> Unit,
    onPickStation: (RadioStation) -> Unit,
    onPickCity: (RadioCity) -> Unit,
    onOpenCountry: (RadioCountry?) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    // Opening the bar means wanting to type in it.
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }

    GlassPanel(
        hazeState = hazeState,
        glass = glass,
        modifier = Modifier.padding(horizontal = 4.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focus),
                    decorationBox = { inner ->
                        if (query.isEmpty()) {
                            Text(
                                text = "Station, city or country",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        inner()
                    },
                )
                IconButton(
                    onClick = { if (query.isEmpty()) onClose() else onQueryChange("") },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = if (query.isEmpty()) "Close search" else "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // The predictions. A row rather than a list, slid sideways, so they
            // sit under the field without covering the globe the search is
            // about — the answer to "which of these did you mean" is one glance
            // and one tap, and a vertical list would take the screen for it.
            if (!state.isEmpty) {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Inside a country, the way back leads. Sliding right to
                    // find the exit would make the drill-down a trap on a row
                    // that can be two dozen cities long.
                    state.inCountry?.let { open ->
                        item {
                            CountryPill(
                                country = open,
                                open = true,
                                onClick = { onOpenCountry(null) },
                            )
                        }
                    }
                    // Countries first otherwise: a country is the broadest
                    // answer to a half-typed word, and the one most likely to be
                    // what was meant when it matches at all.
                    items(state.countries) { country ->
                        CountryPill(
                            country = country,
                            open = false,
                            onClick = { onOpenCountry(country) },
                        )
                    }
                    // Then cities. They are the ones that are certainly right —
                    // matched against the globe's own list rather than guessed
                    // at from a station's name — and they are already on screen
                    // while the directory is still being asked.
                    items(state.cities) { city ->
                        CitySuggestionPill(
                            city = city,
                            onClick = {
                                keyboard?.hide()
                                onPickCity(city)
                            },
                        )
                    }
                    // Unkeyed, as everywhere else the directory's data is
                    // listed: it does not promise unique uuids, and a duplicate
                    // pill beats a crash.
                    items(state.stations) { station ->
                        StationPill(
                            station = station,
                            onClick = {
                                keyboard?.hide()
                                onPickStation(station)
                            },
                        )
                    }
                }
            }

            when (state.status) {
                SearchStatus.Idle -> Unit
                SearchStatus.Searching ->
                    if (state.cities.isEmpty()) SearchNote("Searching…")
                SearchStatus.Unreachable ->
                    SearchNote("Couldn't reach the station directory.")
                SearchStatus.Ready ->
                    if (state.isEmpty) SearchNote("Nothing by that name.")
            }
        }
    }
}

@Composable
private fun SearchNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/**
 * A country prediction, and — once [open] — the way back out of it.
 *
 * A country is not something you can play, so tapping it does not try to: it
 * opens that country's cities in the same row. The count is the globe's own,
 * the cities it can actually fly to, rather than a number from an atlas that
 * would promise places this map does not have.
 */
@Composable
private fun CountryPill(country: RadioCountry, open: Boolean, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = if (open) MaterialTheme.colorScheme.primary.copy(alpha = 0.26f)
        else MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
        modifier = Modifier.bounceClick(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                imageVector = if (open) Icons.AutoMirrored.Filled.ArrowBack else Icons.Default.Public,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = country.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            if (!open) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (country.cities == 1) "1 city" else "${country.cities} cities",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * A city prediction. Tapping it does what tapping its dot does — flies there
 * and opens the panel — rather than playing something. The city is a place with
 * a list behind it, and picking one of that list is a separate decision.
 */
@Composable
private fun CitySuggestionPill(city: RadioCity, onClick: () -> Unit) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
        modifier = Modifier.bounceClick(onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                Icons.Default.Place,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = city.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = city.country,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One prediction: the station, and the country it says it broadcasts from. */
@Composable
private fun StationPill(station: RadioStation, onClick: () -> Unit) {
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
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = station.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
            if (station.countryCode.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = station.countryCode,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── the panel ───────────────────────────────────────────────────────────────

/** The card for a tapped city — what plays there, and where to go next. */
@Composable
private fun CityCard(
    city: RadioCity,
    stations: StationsState,
    nearby: List<RadioCity>,
    favourites: Set<String>,
    hazeState: dev.chrisbanes.haze.HazeState,
    glass: tf.monochrome.android.domain.model.PlayerGlassSettings,
    maxStationsHeight: androidx.compose.ui.unit.Dp,
    onPlay: (RadioStation) -> Unit,
    onFavourite: (RadioStation) -> Unit,
    onNearby: (RadioCity) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current

    GlassPanel(hazeState = hazeState, glass = glass, modifier = modifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = city.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(city.country)
                            append(" · ")
                            append(
                                if (city.stations == 1) "1 station"
                                else "${city.stations} stations",
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            when (stations) {
                StationsState.Idle, StationsState.Loading -> Text(
                    text = "Tuning in…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Two different sentences on purpose. One is about the network,
                // the other about the city, and telling a listener their city
                // has no radio when in fact the request failed is the kind of
                // small lie that makes a map untrustworthy.
                StationsState.Unreachable -> Text(
                    text = "Couldn't reach the station directory. It's the listing " +
                        "that's missing, not the radio — try again in a moment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                is StationsState.Ready -> if (stations.stations.isEmpty()) {
                    Text(
                        text = "Nothing is on air here right now. ${city.stations} " +
                            "station(s) were listed when this map was built; the " +
                            "directory has since dropped them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // A real scroll container rather than a Column that happens
                    // to be scrollable. Two things needed it: a city like Berlin
                    // brings hundreds of stations and a plain Column composes and
                    // measures every one of them to show four, and a Column's
                    // scroll gesture has to travel up through the panel's own
                    // pointer handling, which a LazyColumn's owns outright.
                    //
                    // Keyed on the city so each one opens at the top. The state
                    // survives the Loading→Ready hop within a single city, which
                    // is what keeps a refresh from throwing the list back.
                    val listState = key(city.id) { rememberLazyListState() }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = maxStationsHeight),
                    ) {
                        // Deliberately unkeyed. The obvious key is the station
                        // uuid, and the directory is a third-party listing that
                        // does not promise uniqueness — a repeated uuid is a
                        // crash in a keyed LazyColumn, and a duplicated row is
                        // the better failure by a wide margin.
                        items(stations.stations) { station ->
                            StationRow(
                                station = station,
                                favourite = station.uuid in favourites,
                                onPlay = { onPlay(station) },
                                onFavourite = { onFavourite(station) },
                                onHomepage = station.homepage?.let {
                                    { uriHandler.openUri(it) }
                                },
                            )
                        }
                    }
                }
            }

            if (nearby.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Nearby on air",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(nearby, key = { it.id }) { neighbour ->
                        CityChip(city = neighbour, onClick = { onNearby(neighbour) })
                    }
                }
            }
        }
    }
}

/** One station: what it is, how it sounds, and the two things you can do with it. */
@Composable
private fun StationRow(
    station: RadioStation,
    favourite: Boolean,
    onPlay: () -> Unit,
    onFavourite: () -> Unit,
    onHomepage: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bounceClick(onClick = onPlay)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play ${station.name}",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = (listOfNotNull(station.qualityLabel) + station.topTags(2))
                .joinToString(" · ")
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        onHomepage?.let {
            IconButton(onClick = it, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.OpenInNew,
                    contentDescription = "Open ${station.name}'s website",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        IconButton(onClick = onFavourite, modifier = Modifier.size(30.dp)) {
            Icon(
                imageVector = if (favourite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (favourite) "Remove from your stations"
                else "Keep this station",
                tint = if (favourite) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** A neighbouring city, as a chip that spins the globe to it. */
@Composable
private fun CityChip(city: RadioCity, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.bounceClick(onClick = onClick),
        shape = MonoDimens.shapePill,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = city.name,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = city.stations.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The pulsing ring on the city whose card is open.
 *
 * Its own canvas, and that is the entire point of it. The globe re-projects
 * ~5,100 coastline points, 1,611 dots and a label collision grid on every draw,
 * so anything animated sharing that canvas drags all of it to 60 Hz — with a
 * city selected the panel is open, and the cost landed squarely on scrolling
 * the station list. Here the per-frame work is one projection and one circle.
 *
 * Draws nothing when the city is round the back, and does not exist at all with
 * animations off — there is no still frame of this worth paying a canvas for.
 */
@Composable
private fun SelectedCityPing(
    city: RadioCity,
    scale: Int,
    camera: GlobeCamera,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    if (reduceMotion()) return

    val breath = rememberInfiniteTransition(label = "selectedCity")
    val ping = breath.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            // Restart, not Reverse. A ring that grows and then shrinks back into
            // itself reads as a blob breathing; one that grows, fades, and is
            // replaced reads as a transmitter, which is what the dot is.
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "selectedPing",
    )

    Canvas(modifier = modifier) {
        val radius = globeRadius(size.width, size.height, camera.zoom)
        val p = project(city.lat, city.lon, scale, camera, size.width / 2f, size.height / 2f, radius)
        if (!p.visible) return@Canvas

        // Read inside the draw lambda so the ping invalidates the draw phase and
        // never the composition around it.
        val t = ping.value
        // Floored well above the dot's own size. Scaled purely off the dot, the
        // marker disappeared on ordinary cities: a 42-station dot is about four
        // pixels across, and a ring at 2.6× of that is not a marker, it is a
        // smudge a couple of pixels clear of the thing it is marking.
        val base = cityDotRadius(city.stations, camera.zoom).coerceAtLeast(PING_MIN_BASE_PX)

        // Two rings, half a cycle apart, so one is always mid-flight instead of
        // there being a dead beat between pulses. Squared fade so each ring
        // holds its brightness on the way out and then goes quickly, rather than
        // spending its whole life as a faint smear.
        for (i in 0 until PING_RINGS) {
            val phase = (t + i.toFloat() / PING_RINGS) % 1f
            val fade = 1f - phase
            drawCircle(
                color = accent.copy(alpha = 0.8f * fade * fade),
                radius = base * (1.4f + PING_TRAVEL * phase),
                center = Offset(p.x, p.y),
                style = Stroke(width = 1.2f + 2.6f * fade),
            )
        }

        // A steady core underneath, so the city stays marked in the instant
        // after one ring has faded and before the next has cleared the dot.
        drawCircle(
            color = accent.copy(alpha = 0.32f),
            radius = base * 1.2f,
            center = Offset(p.x, p.y),
        )
    }
}

/**
 * The smallest the ping treats its city as being.
 *
 * The ring is a multiple of the dot, and most dots are tiny — sizing it purely
 * off the station count made the marker vanish on exactly the small cities that
 * are hardest to pick out of the globe in the first place.
 */
private const val PING_MIN_BASE_PX = 6f

/** How far a ring travels, as a multiple of the dot, before it has faded out. */
private const val PING_TRAVEL = 7f

/** Rings in flight at once, evenly spaced through the cycle. */
private const val PING_RINGS = 2

// ── projection, camera and drawing ──────────────────────────────────────────

private val MINI_PLAYER_RESERVE = 72.dp

/**
 * How long the glow settings sit still before they are written.
 *
 * Long enough that a whole slider drag is one write instead of a hundred, short
 * enough that letting go and leaving the screen keeps the change.
 */
private const val FX_PERSIST_DEBOUNCE_MS = 400L
private const val STATIONS_HEIGHT_FRACTION = 0.34f
private const val FLIGHT_HEIGHT_QUANTUM = 96
private const val SPIN_MILLIS = 700
private val SpinEasing = CubicBezierEasing(0.62f, 0f, 0.28f, 1f)

/** The widest the Earth is drawn. */
private const val MIN_ZOOM = 0.85f

/**
 * How close the globe will come.
 *
 * Measured rather than picked: the globe's radius is `0.42 × min(w, h) × zoom`,
 * so on a 1080 px-wide phone 48× puts the radius at 21,773 px and a screenful is
 * about 320 km of arc — near enough that cities twenty kilometres apart sit some
 * sixty-five pixels apart and can be told apart and tapped. The old ceiling of 9
 * spanned ~1,700 km, which is a country, and a region's cities arrived as one
 * blur. Nothing is drawn per zoom level — the same 5,127 coastline points and
 * 1,611 dots either way — so the extra range is free.
 */
private const val MAX_ZOOM = 48f

/** Fraction of the smaller viewport dimension the globe fills at zoom 1. */
private const val GLOBE_FIT = 0.42f

/**
 * Where the Earth is turned to, and how close.
 *
 * [yaw] and [pitch] are in radians and describe which point faces the viewer,
 * rather than an x/y offset: on a sphere, panning *is* rotating, and storing a
 * translation would let the globe be dragged off the screen with no way back.
 * Pitch is clamped short of the poles because passing one flips the world
 * upside down mid-drag, which reads as a glitch rather than as a rotation.
 */
private data class GlobeCamera(
    val yaw: Float = 0f,
    val pitch: Float = 0.35f,
    val zoom: Float = 1f,
) {
    fun spun(pan: Offset, zoomBy: Float, size: IntSize): GlobeCamera {
        val next = (zoom * zoomBy).coerceIn(MIN_ZOOM, MAX_ZOOM)
        val radius = globeRadius(size.width.toFloat(), size.height.toFloat(), next)
        // Divide by the radius so a drag moves the surface under the finger at
        // roughly the same rate however far in you are: at 48× a pixel of drag
        // is a forty-eighth of the arc it was at 1×.
        //
        // Both signs are positive because both axes move the surface the way the
        // finger goes, which is the only thing a globe under a thumb can mean.
        // Increasing yaw carries a point right across the disc (project() takes
        // sin of lon + yaw), and increasing pitch carries it down, so a rightward
        // drag wants more yaw and a downward drag wants more pitch. Yaw was
        // negated here and the Earth ran backwards under the finger.
        val dYaw = if (radius <= 0f) 0f else pan.x / radius
        val dPitch = if (radius <= 0f) 0f else pan.y / radius
        return GlobeCamera(
            yaw = wrap(yaw + dYaw),
            pitch = (pitch + dPitch).coerceIn(-MAX_PITCH, MAX_PITCH),
            zoom = next,
        )
    }
}

/**
 * How far the poles may be walked toward the viewer. Short of ±π/2 on purpose:
 * passing one flips the world upside down mid-drag. File-level rather than
 * private to the camera because the flight has to respect the same ceiling.
 */
private const val MAX_PITCH = 1.45f

private fun wrap(radians: Float): Float {
    val twoPi = (2 * PI).toFloat()
    var value = radians % twoPi
    if (value > PI) value -= twoPi
    if (value < -PI) value += twoPi
    return value
}

private fun globeRadius(width: Float, height: Float, zoom: Float): Float =
    minOf(width, height) * GLOBE_FIT * zoom

/**
 * A point on the sphere in view space.
 *
 * [z] is the depth toward the viewer: positive is the near hemisphere, negative
 * the far one. Everything that reads this culls on it, which is what stops the
 * globe from drawing Australia through the Atlantic.
 */
private data class Projected(val x: Float, val y: Float, val z: Float) {
    val visible: Boolean get() = z >= 0f
}

/**
 * Latitude and longitude in hundredths of a degree to a point in view space.
 *
 * Orthographic: rotate the unit sphere by the camera, then drop the depth. No
 * perspective divide, because from orbit there effectively isn't one, and adding
 * it would make the centre of the globe swell.
 */
private fun project(
    latRaw: Int,
    lonRaw: Int,
    scale: Int,
    camera: GlobeCamera,
    cx: Float,
    cy: Float,
    radius: Float,
): Projected {
    val lat = Math.toRadians(latRaw.toDouble() / scale)
    val lon = Math.toRadians(lonRaw.toDouble() / scale) + camera.yaw

    val cosLat = cos(lat)
    val x = cosLat * sin(lon)
    val y = sin(lat)
    val z = cosLat * cos(lon)

    // Tilt about the horizontal axis, so dragging up and down walks the poles
    // toward the viewer instead of rolling the image.
    val cosP = cos(camera.pitch.toDouble())
    val sinP = sin(camera.pitch.toDouble())
    val y2 = y * cosP - z * sinP
    val z2 = y * sinP + z * cosP

    return Projected(
        x = cx + (x * radius).toFloat(),
        y = cy - (y2 * radius).toFloat(),
        z = z2.toFloat(),
    )
}

/**
 * Turn the Earth so a city faces the viewer, on a curve.
 *
 * "Faces the viewer" is not the same as "sits in the middle of the canvas". The
 * card covers the bottom third of the screen and the app bar the top of it, so
 * centring a city on the disc puts the very dot you just selected underneath
 * the panel describing it — the flight looks like it went somewhere wrong. The
 * target is therefore the middle of what is actually *visible*, and because
 * this is a sphere the only way to move a point up the screen is to rotate less
 * than all the way: the lift is subtracted from the pitch rather than added to
 * a translation the camera does not have.
 */
private suspend fun spinToCity(
    city: RadioCity,
    scale: Int,
    from: GlobeCamera,
    instant: Boolean,
    size: IntSize,
    topInset: Float,
    bottomInset: Float,
    onFrame: (GlobeCamera) -> Unit,
) {
    val targetYaw = wrap((-Math.toRadians(city.lon.toDouble() / scale)).toFloat())
    val targetZoom = maxOf(from.zoom, 2.2f)
    val latitude = Math.toRadians(city.lat.toDouble() / scale).toFloat()

    // How far above the disc's centre the city has to land, and the rotation
    // that puts it there. project() gives screen y = cy − radius·sin(lat − pitch),
    // so asking for an offset of d is asking for sin(lat − pitch) = d / radius.
    // Clamped inside the domain of asin: a lift taller than the globe's own
    // radius is unreachable at this zoom, and the nearest reachable framing is
    // a better answer than a NaN camera.
    val radius = globeRadius(size.width.toFloat(), size.height.toFloat(), targetZoom)
    val lift = if (radius <= 0f) {
        0f
    } else {
        asin((((bottomInset - topInset) / 2f) / radius).coerceIn(-0.95f, 0.95f))
    }
    val targetPitch = (latitude - lift).coerceIn(-MAX_PITCH, MAX_PITCH)

    if (instant) {
        onFrame(GlobeCamera(targetYaw, targetPitch, targetZoom))
        return
    }

    // Take the short way round. Interpolating raw yaw from +170° to -170° spins
    // the globe the long way through the whole Pacific to travel twenty degrees.
    val deltaYaw = wrap(targetYaw - from.yaw)
    animate(0f, 1f, animationSpec = tween(SPIN_MILLIS, easing = SpinEasing)) { t, _ ->
        onFrame(
            GlobeCamera(
                yaw = wrap(from.yaw + deltaYaw * t),
                pitch = from.pitch + (targetPitch - from.pitch) * t,
                zoom = from.zoom + (targetZoom - from.zoom) * t,
            ),
        )
    }
}

/**
 * Which city a tap landed on.
 *
 * Projects forward and takes the nearest within a touch radius rather than
 * inverting the projection, exactly as the genre map does — and here the case
 * for it is stronger, because inverting an orthographic sphere projection is
 * ambiguous by construction: every screen point maps to two places, one on each
 * hemisphere. Only the near one is culled in, so going forwards answers the
 * question the tap actually asked.
 */
private fun hitTest(
    point: Offset,
    cities: List<RadioCity>,
    scale: Int,
    width: Int,
    height: Int,
    camera: GlobeCamera,
): RadioCity? {
    if (cities.isEmpty()) return null
    val radius = globeRadius(width.toFloat(), height.toFloat(), camera.zoom)
    val cx = width / 2f
    val cy = height / 2f

    var best: RadioCity? = null
    var bestDistance = TOUCH_RADIUS
    for (city in cities) {
        val p = project(city.lat, city.lon, scale, camera, cx, cy, radius)
        if (!p.visible) continue
        val distance = hypot(p.x - point.x, p.y - point.y)
        if (distance <= bestDistance) {
            bestDistance = distance
            best = city
        }
    }
    return best
}

private const val TOUCH_RADIUS = 34f

/**
 * Draw the Earth.
 *
 * Ocean disc first so the far hemisphere's coastlines have something to be
 * hidden behind, then the coastlines, then the cities — biggest last, because
 * the asset is sorted by station count and the busiest city should not be
 * covered by a one-station neighbour.
 */
private fun DrawScope.drawGlobe(
    data: tf.monochrome.android.domain.model.WorldRadioData,
    camera: GlobeCamera,
    ocean: Color,
    land: Color,
    dot: Color,
    selectedId: String?,
    labelColor: Color,
    labelSizePx: Float,
    topInset: Float,
    bottomInset: Float,
    accent: Color,
    outlines: OutlineCache,
    fx: GlobeFxSettings = GlobeFxSettings(),
) {
    if (data.cities.isEmpty() && data.coastline.isEmpty()) return

    val radius = globeRadius(size.width, size.height, camera.zoom)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val scale = data.scale.takeIf { it > 0 } ?: 100

    // The lit ink. Blended toward the accent rather than replaced by it, so the
    // slider has a whole range instead of an on and an off — and blended from
    // the theme's own land colour, which is what keeps the globe legible in both
    // light and dark instead of tuned for one of them.
    val litLand = androidx.compose.ui.graphics.lerp(land, accent, fx.illumination)
    val halo = if (fx.glowing) accent.copy(alpha = fx.glowBrightness * 0.45f) else null

    drawCircle(color = ocean, radius = radius, center = Offset(cx, cy))
    drawCircle(
        color = land.copy(alpha = 0.35f),
        radius = radius,
        center = Offset(cx, cy),
        style = Stroke(width = 1.2f),
    )

    // Borders first, coastline over the top. The coast is the strongest
    // structural line on the map and should stay the crispest where the two
    // meet — and the borders are quieter besides, because at full strength
    // across a whole hemisphere Europe becomes a scribble competing with the
    // dots, and the dots are what this screen is for. They firm up as you close
    // in, which is when knowing whose airwaves you are looking at starts to
    // matter.
    val borderAlpha = (0.3f + 0.02f * camera.zoom).coerceIn(0.3f, 0.62f)

    // Rebuild the geometry only when it could have moved. The camera and the
    // viewport are the whole of what the projection depends on, so a change
    // that is only to colour reuses every path.
    outlines.update(
        signature = outlineSignature(camera, size.width, size.height, data),
        buildBorders = { projectLines(data.borders, camera, cx, cy, radius, scale) },
        buildCoastline = { projectLines(data.coastline, camera, cx, cy, radius, scale) },
    )

    strokeOutlines(
        paths = outlines.borders,
        colour = litLand.copy(alpha = borderAlpha),
        width = 0.9f,
        glow = halo?.copy(alpha = halo.alpha * borderAlpha),
        glowWidth = fx.glowWidth,
    )
    strokeOutlines(
        paths = outlines.coastline,
        colour = litLand,
        width = 1.1f,
        glow = halo,
        glowWidth = fx.glowWidth,
    )

    for (city in data.cities) {
        val p = project(city.lat, city.lon, scale, camera, cx, cy, radius)
        if (!p.visible) continue
        if (p.x < -20f || p.y < -20f || p.x > size.width + 20f || p.y > size.height + 20f) continue

        val base = cityDotRadius(city.stations, camera.zoom)
        val selected = city.id == selectedId

        // Fade toward the limb, so the sphere reads as curved rather than as a
        // flat disc of dots.
        val edge = (p.z * 1.4f).coerceIn(0.25f, 1f)

        if (selected) {
            // The still part of the marker only. The ring that animates is drawn
            // by [SelectedCityPing] on a canvas of its own — an animation in
            // here would invalidate this one, and this one re-projects every
            // coastline point in the asset each time it runs.
            drawCircle(
                color = dot.copy(alpha = 0.22f),
                radius = base * 2.2f,
                center = Offset(p.x, p.y),
            )
        }
        drawCircle(
            color = dot.copy(alpha = edge),
            radius = if (selected) base * 1.7f else base,
            center = Offset(p.x, p.y),
        )
    }

    drawCityLabels(
        data = data,
        camera = camera,
        cx = cx,
        cy = cy,
        radius = radius,
        scale = scale,
        labelColor = labelColor,
        labelSizePx = labelSizePx,
        topInset = topInset,
        bottomInset = bottomInset,
    )
}

/**
 * A number that changes exactly when the projected outlines would.
 *
 * Yaw and pitch are quantised to about a five-thousandth of a radian and the
 * zoom to four decimals — finer than any of them can shift a vertex by a
 * visible amount, coarse enough that float noise alone never invalidates the
 * cache. The dataset's shape is folded in too, so the frame between an empty
 * globe and a loaded one cannot reuse an empty projection.
 */
private fun outlineSignature(
    camera: GlobeCamera,
    width: Float,
    height: Float,
    data: tf.monochrome.android.domain.model.WorldRadioData,
): Long {
    var h = (camera.yaw * 5000f).toLong()
    h = h * 31 + (camera.pitch * 5000f).toLong()
    h = h * 31 + (camera.zoom * 10000f).toLong()
    h = h * 31 + width.toLong()
    h = h * 31 + height.toLong()
    h = h * 31 + data.coastline.size
    h = h * 31 + data.borders.size
    return h
}

/**
 * The projected outlines, kept between draws.
 *
 * Projection is the whole cost of this screen — ~5,100 coastline points and
 * every border vertex, each through two sines and two cosines — and it depends
 * on nothing but the camera and the viewport. The glow settings change how the
 * outlines are *stroked*, not where they are, so rebuilding them when a slider
 * moves meant re-running all of it sixty times a second to change a colour.
 * That is what made the sliders crawl.
 *
 * Owned by the screen and handed to the draw rather than being a global: two
 * globes on screen at once would need two caches, and a cache keyed by a camera
 * some other instance keeps moving is a cache that never hits.
 */
private class OutlineCache {
    private var key: Long = Long.MIN_VALUE
    var borders: List<Path> = emptyList()
        private set
    var coastline: List<Path> = emptyList()
        private set

    /** Rebuild only when the geometry could actually have moved. */
    fun update(
        signature: Long,
        buildBorders: () -> List<Path>,
        buildCoastline: () -> List<Path>,
    ) {
        if (signature == key) return
        key = signature
        borders = buildBorders()
        coastline = buildCoastline()
    }
}

/**
 * Project a set of flat `[lon, lat, lon, lat, …]` runs into screen-space paths.
 *
 * Shared by the coastline and the borders because they are the same problem:
 * the same projection, the same hemisphere culling, and the same pen-lifting
 * when a line passes round the back. A second copy of that last part is a
 * second place for it to rot — and getting it wrong is not subtle, it draws a
 * chord straight across the face of the globe.
 */
private fun projectLines(
    lines: List<List<Int>>,
    camera: GlobeCamera,
    cx: Float,
    cy: Float,
    radius: Float,
    scale: Int,
): List<Path> = lines.map { line ->
    val path = Path()
    var drawing = false
    var index = 0
    while (index + 1 < line.size) {
        val p = project(line[index + 1], line[index], scale, camera, cx, cy, radius)
        if (p.visible) {
            if (drawing) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
            drawing = true
        } else {
            // Round the back: lift the pen so the line stops at the limb rather
            // than being drawn across the disc when it reappears.
            drawing = false
        }
        index += 2
    }
    path
}

/**
 * Stroke already-projected outlines, halo first.
 *
 * Both strokes come off the one [Path], so the halo costs a second rasterise
 * and never a second projection.
 */
private fun DrawScope.strokeOutlines(
    paths: List<Path>,
    colour: Color,
    width: Float,
    glow: Color?,
    glowWidth: Float,
) {
    for (path in paths) {
        if (glow != null) {
            drawPath(path = path, color = glow, style = Stroke(width = width * glowWidth))
        }
        drawPath(path = path, color = colour, style = Stroke(width = width))
    }
}

/**
 * Name the cities, once there is room to.
 *
 * Nothing below [LABEL_FROM_ZOOM], because at whole-Earth scale a name is longer
 * than the continent it sits on. Past it the offer widens as you close in, and
 * *collision* decides what is actually drawn — the same division of labour the
 * genre map settled on for its 771 nodes, and for the same reason: a threshold
 * alone cannot serve both ends of a fifty-fold zoom range, and tuning one is a
 * choice between a bare view and an unreadable smear.
 *
 * Candidates are ordered by station count, which is what the dot size already
 * encodes. Naming by anything else would leave the biggest dot on screen
 * anonymous while some one-station town beside it got the label.
 */
private fun DrawScope.drawCityLabels(
    data: tf.monochrome.android.domain.model.WorldRadioData,
    camera: GlobeCamera,
    cx: Float,
    cy: Float,
    radius: Float,
    scale: Int,
    labelColor: Color,
    labelSizePx: Float,
    topInset: Float,
    bottomInset: Float,
) {
    if (camera.zoom < LABEL_FROM_ZOOM || data.cities.isEmpty()) return

    // How many are offered, not how many are drawn. Generous on purpose.
    val offered = when {
        camera.zoom >= 24f -> 120
        camera.zoom >= 12f -> 70
        camera.zoom >= 6f -> 40
        else -> 20
    }

    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = labelColor.toArgb()
            textSize = labelSizePx
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
        }
        val baseAlpha = paint.alpha
        val taken = ArrayList<android.graphics.RectF>()
        var drawn = 0

        // The asset is sorted by station count ascending so the busiest cities
        // draw last and on top; naming wants the opposite order.
        for (city in data.cities.asReversed()) {
            if (drawn >= offered) break
            val p = project(city.lat, city.lon, scale, camera, cx, cy, radius)
            if (!p.visible) continue

            val halfWidth = paint.measureText(city.name) / 2f
            // Below the dot, clear of it, so the label never sits on the thing
            // it names — and the dot grows with zoom, so the gap has to as well.
            val baseline = p.y + labelSizePx + 6f
            val box = android.graphics.RectF(
                p.x - halfWidth - LABEL_GAP,
                baseline - labelSizePx - LABEL_GAP,
                p.x + halfWidth + LABEL_GAP,
                baseline + LABEL_GAP,
            )

            // Sliced by the edge of the screen is its own kind of collision, and
            // a half-drawn name is worse than none.
            if (box.left < 0f || box.right > size.width) continue
            // Reserved strips: the app bar above, and the city panel below when
            // one is open. Ink spent in either is ink nobody can read.
            if (box.top < topInset) continue
            if (bottomInset > 0f && box.bottom > size.height - bottomInset) continue
            if (taken.any { android.graphics.RectF.intersects(it, box) }) continue

            taken.add(box)
            drawn++
            // Same limb fade as the dots, so a name near the edge doesn't shout
            // louder than the sphere it is curving away on.
            paint.alpha = (baseAlpha * (p.z * 1.4f).coerceIn(0.3f, 1f)).toInt()
            drawText(city.name, p.x, baseline, paint)
        }
    }
}

/**
 * How big a city's dot is drawn, from its station count and the zoom.
 *
 * Log on the count, because they run from 1 to 2,294: linear sizing makes
 * Berlin a blot and leaves every one-station town the same invisible speck. Log
 * again on the zoom, rather than the genre map's linear ramp, because this
 * range is 0.85 to 48 rather than 0.6 to 14 and a linear one would hit its
 * ceiling in the first tenth of the travel — closing in would only spread the
 * same specks further apart, and the thing you zoomed in to see would stay as
 * hard to see and as hard to hit. Clamped at both ends: the whole-Earth view
 * must not turn to blobs, and the closest view must not grow discs.
 *
 * Shared with the selected-city ping, which has to agree with the dot it rings.
 */
private fun cityDotRadius(stations: Int, zoom: Float): Float {
    val weight = (ln(1f + stations) / LOG_CEILING).coerceIn(0f, 1f)
    val closeness = (0.7f + 0.35f * ln(1f + zoom)).coerceIn(0.75f, 2.4f)
    return (1.6f + weight * 4.4f) * closeness
}

/** Below this the globe is too small for a name to fit beside its dot. */
private const val LABEL_FROM_ZOOM = 3f

/** Breathing room around a label when testing it against its neighbours. */
private const val LABEL_GAP = 3f

/**
 * ln(1 + 2294) — the busiest city in the dataset, so weights land inside 0..1.
 *
 * Measured rather than guessed: the counts span three orders of magnitude, and a
 * ceiling set too low saturates every large city to the same maximum dot, which
 * is exactly the information the size was supposed to carry.
 */
private const val LOG_CEILING = 7.74f
