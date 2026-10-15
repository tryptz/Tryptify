package tf.monochrome.android.ui.library.galaxy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import tf.monochrome.android.ui.discover.galaxy.GALAXY_INK
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import dev.chrisbanes.haze.HazeState
import tf.monochrome.android.R
import tf.monochrome.android.data.local.scanner.MediaScanner
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.discover.DOCK_GAP_DP
import tf.monochrome.android.ui.discover.DOCK_MARGIN_DP
import tf.monochrome.android.ui.discover.GLYPH_PILL_WIDTH
import tf.monochrome.android.ui.discover.GalaxyLayoutPills
import tf.monochrome.android.ui.discover.GalaxySearchBar
import tf.monochrome.android.ui.discover.GalaxyStage
import tf.monochrome.android.ui.discover.matchStars
import tf.monochrome.android.ui.discover.GalaxyStageScope
import tf.monochrome.android.ui.discover.GlyphPill
import tf.monochrome.android.ui.discover.MapAction
import tf.monochrome.android.ui.discover.familyPalette
import tf.monochrome.android.ui.discover.galaxy.GalaxySystemSheet
import tf.monochrome.android.ui.discover.galaxy.LibraryStarField
import tf.monochrome.android.ui.discover.galaxy.MoonRef
import tf.monochrome.android.ui.discover.galaxy.PlanetSystem
import tf.monochrome.android.ui.discover.galaxy.SystemGlyph
import tf.monochrome.android.ui.discover.popIn
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.switchSafe
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.PlayerViewModel

/**
 * The library galaxy: the music on this phone as a sky you can fly through.
 * Every artist a star, beside their genre on the genre map; their albums
 * planets, the first record nearest; each album's tracks moons.
 *
 * The same stage as the genre galaxy ([GalaxyStage]) — the same camera,
 * glass, full screen and look — with the library's own dock: the artists a
 * star has worked with, by name, and pills to shuffle them, list their albums
 * and open their page; once the camera is at one of their planets, to play
 * that album or open it. A moon is a tap to play its track, with the rest of
 * the album after it.
 *
 * Works offline: everything on it is the device's own (`LocalGalaxyRepository`).
 */
@Composable
fun LibraryMapScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: LibraryGalaxyViewModel = hiltViewModel(),
) {
    val stars by viewModel.stars.collectAsStateWithLifecycle()
    val selected by viewModel.selection.collectAsStateWithLifecycle()
    val systemView by viewModel.system.collectAsStateWithLifecycle()
    val visuals by viewModel.visuals.collectAsStateWithLifecycle()
    val playing by playerViewModel.currentTrack.collectAsStateWithLifecycle()

    val field = stars?.field
    val familyColors = remember(field) { familyPalette(field?.families.orEmpty()) }
    // The artist playing now is "you are here".
    val here = remember(playing, stars) { viewModel.starOf(playing?.displayArtist) }
    val selectedIndex = selected?.let { id -> field?.ids?.indexOf(id)?.takeIf { it >= 0 } }
    val selectedName = selectedIndex?.let { field?.names?.get(it) }
    val system = systemView?.system?.takeIf { it.starId == selected }
    val artists = stars?.library?.artists.orEmpty()
    val anyUncharted = remember(field) { field?.families?.contains(LibraryStarField.UNCHARTED) == true }

    // Finding an artist: a glass bar under the title, its matches as pills.
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    fun closeSearch() {
        searchOpen = false
        query = ""
        keyboard?.hide()
    }

    fun openSystem(stage: GalaxyStageScope, id: String) {
        if (id != selected) viewModel.select(id)
        viewModel.playArtist(id, playerViewModel)
        stage.openSheet(id)
    }

    fun isPlaying(ref: MoonRef): Boolean {
        val local = ref as? MoonRef.Local ?: return false
        val title = playing?.title ?: return false
        return here == selected && MediaScanner.normalizeText(title) == MediaScanner.normalizeText(local.title)
    }

    GalaxyStage(
        navController = navController,
        playerViewModel = playerViewModel,
        field = field,
        familyColors = familyColors,
        visuals = visuals,
        onVisuals = viewModel::setVisuals,
        selectedId = selected,
        hereId = here,
        explored = emptySet(),
        hearted = emptySet(),
        system = system,
        title = selectedName ?: stringResource(R.string.library_galaxy),
        caption = { stage ->
            val i = selectedIndex
            if (i != null && field != null) {
                val key = LibraryStarField.keyOf(field.ids[i])
                val artist = artists.firstOrNull { it.key == key }
                val family = field.families[field.family[i]]
                listOfNotNull(
                    if (family == LibraryStarField.UNCHARTED) stringResource(R.string.library_galaxy_uncharted) else null,
                    stage.system?.let { sys ->
                        val albums = sys.planets.size + sys.beltCount
                        pluralStringResource(R.plurals.library_galaxy_albums, albums, albums)
                    },
                    artist?.let { pluralStringResource(R.plurals.library_galaxy_tracks, it.trackCount, it.trackCount) },
                    artist?.firstYear?.let { stringResource(R.string.discover_genre_since, it) },
                ).joinToString(" · ") + if (stage.system != null && stage.visuals.planets) "\n" + stringResource(R.string.library_galaxy_planets_caption) else ""
            } else {
                // What a star's place and size mean, and — when there are
                // any — what the stars off the disc are.
                listOfNotNull(
                    stringResource(if (stage.timeline) R.string.galaxy_time_caption else R.string.library_galaxy_caption),
                    if (anyUncharted) stringResource(R.string.library_galaxy_legend_halo) else null,
                ).joinToString("\n")
            }
        },
        emptyText = when {
            stars == null -> stringResource(R.string.library_galaxy_loading)
            field == null -> stringResource(R.string.library_galaxy_empty)
            else -> null
        },
        onSelect = viewModel::select,
        onLongPress = { stage, id -> openSystem(stage, id) },
        onPlayMoon = { viewModel.playMoon(it, playerViewModel) },
        actions = { stage ->
            buildList {
                add(
                    MapAction(
                        Icons.Default.Search,
                        stringResource(if (searchOpen) R.string.action_close_search else R.string.tab_search),
                        tint = if (searchOpen) MaterialTheme.colorScheme.primary else GALAXY_INK,
                    ) { if (searchOpen) closeSearch() else searchOpen = true },
                )
                val id = selected
                if (id == null) {
                    add(MapAction(Icons.Default.AutoAwesome, stringResource(R.string.galaxy_surprise)) { viewModel.surprise() })
                } else {
                    viewModel.artistIdOf(id)?.let { artistId ->
                        add(MapAction(Icons.Default.Person, stringResource(R.string.library_galaxy_open_artist)) {
                            navController.navigateSafe(Screen.LocalArtistDetail.createRoute(artistId))
                        })
                    }
                }
                // The genre galaxy, at the same place in the same sky.
                add(MapAction(Icons.Default.Public, stringResource(R.string.genre_galaxy)) {
                    stage.handOff()
                    navController.switchSafe(Screen.GenreMap.route)
                })
                add(stage.fullScreenAction)
                add(stage.lookAction)
                add(stage.recentreAction)
            }
        },
        sheet = { stage, id ->
            if (id == selected && selectedName != null) {
                CompositionLocalProvider(
                    LocalPlayerGlass provides stage.glass,
                    tf.monochrome.android.ui.components.LocalGlassBackdrop provides stage.hazeState,
                ) {
                    GalaxySystemSheet(
                        title = selectedName,
                        detail = null,
                        system = stage.system?.takeIf { it.starId == id },
                        describe = { _, planet -> albumLine(planet) },
                        credit = null,
                        playingTitle = playing?.title,
                        playingArtist = playing?.displayArtist,
                        isPlaying = ::isPlaying,
                        onPlayMoon = { viewModel.playMoon(it, playerViewModel) },
                        onClose = { stage.closeSheet() },
                        hazeState = stage.hazeState,
                        glass = stage.glass,
                        maxHeight = stage.sheetMaxHeight,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(stage.sheetModifier),
                    )
                }
            }
        },
        panel = { stage ->
            val id = selected
            val i = selectedIndex
            if (id == null || i == null || field == null) {
                if (field != null) {
                    CompositionLocalProvider(
                        LocalPlayerGlass provides stage.glass,
                        tf.monochrome.android.ui.components.LocalGlassBackdrop provides stage.hazeState,
                    ) {
                        GalaxyLayoutPills(
                            timeline = stage.timeline,
                            onTimeline = { stage.setTimeline(it) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .then(stage.panelModifier),
                        )
                    }
                }
            } else {
                val collaborators = remember(field, id) { viewModel.collaborators(id, MAX_COLLAB_PILLS) }
                val album = stage.system?.planets?.getOrNull(stage.focusedPlanet)
                CompositionLocalProvider(
                    LocalPlayerGlass provides stage.glass,
                    tf.monochrome.android.ui.components.LocalGlassBackdrop provides stage.hazeState,
                ) {
                    LibraryDock(
                        starId = id,
                        collaborators = collaborators,
                        familyColor = familyColors[field.families[field.family[i]]] ?: MaterialTheme.colorScheme.primary,
                        hazeState = stage.hazeState,
                        album = album,
                        onShuffle = { viewModel.playArtist(id, playerViewModel) },
                        onSystem = { openSystem(stage, id) },
                        onArtist = viewModel.artistIdOf(id)?.let { artistId ->
                            { navController.navigateSafe(Screen.LocalArtistDetail.createRoute(artistId)) }
                        },
                        onPlayAlbum = album?.let { a -> { viewModel.playAlbum(a.key, playerViewModel) } },
                        onOpenAlbum = album?.let { a -> systemView?.music?.albumIds?.get(a.key) }?.let { albumId ->
                            { navController.navigateSafe(Screen.LocalAlbumDetail.createRoute(albumId)) }
                        },
                        onCollaborator = { viewModel.select(it) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .then(stage.panelModifier),
                    )
                }
            }
        },
        underTitle = { stage ->
            // Over the map, not above it, so its glass frosts the sky.
            AnimatedVisibility(
                visible = searchOpen && field != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                CompositionLocalProvider(
                    LocalPlayerGlass provides stage.glass,
                    tf.monochrome.android.ui.components.LocalGlassBackdrop provides stage.hazeState,
                ) {
                    GalaxySearchBar(
                        query = query,
                        onQueryChange = { query = it },
                        placeholder = stringResource(R.string.library_galaxy_search_hint),
                        noMatch = stringResource(R.string.library_galaxy_search_none),
                        matches = remember(field, query) { field?.let { matchStars(it, query) }.orEmpty() },
                        hazeState = stage.hazeState,
                        glass = stage.glass,
                        onPick = { id ->
                            viewModel.select(id)
                            closeSearch()
                        },
                        onClose = { closeSearch() },
                    )
                }
            }
        },
    )

    // Back closes the search before anything else on the map.
    BackHandler(enabled = searchOpen) { closeSearch() }
}

/** An album's line in the system's list: its year and how long it is. */
@Composable
private fun albumLine(planet: PlanetSystem.Planet): String {
    val tracks = planet.moons.size + planet.ringCount
    return listOfNotNull(planet.subtitle, pluralStringResource(R.plurals.library_galaxy_tracks, tracks, tracks)).joinToString(" · ")
}

/**
 * What a selected artist offers, as pills over the map, the genre dock's
 * shape: the artists they have worked with by name, then glyphs — shuffle
 * them, list their albums, open their page — or, once the camera is at one of
 * their planets, play that album and open it.
 */
@Composable
private fun LibraryDock(
    starId: String,
    collaborators: List<Pair<String, String>>,
    familyColor: Color,
    hazeState: HazeState,
    /** The album the camera is at, if it is at one. */
    album: PlanetSystem.Planet?,
    onShuffle: () -> Unit,
    onSystem: () -> Unit,
    onArtist: (() -> Unit)?,
    onPlayAlbum: (() -> Unit)?,
    onOpenAlbum: (() -> Unit)?,
    onCollaborator: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (album != null) {
            Text(
                album.label,
                style = MaterialTheme.typography.labelLarge,
                color = tf.monochrome.android.ui.discover.galaxy.GALAXY_INK,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
            )
        } else if (collaborators.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                collaborators.forEachIndexed { index, (id, name) ->
                    tf.monochrome.android.ui.mixer.GlassChoiceChip(
                        label = name,
                        selected = false,
                        accent = familyColor,
                        onClick = { onCollaborator(id) },
                        height = 36.dp,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .popIn(starId, index),
                    )
                }
            }
        }

        val pills = buildList<Triple<ImageVector, String, () -> Unit>> {
            if (album != null) {
                onPlayAlbum?.let { add(Triple(Icons.Default.PlayArrow, stringResource(R.string.library_galaxy_play_album), it)) }
                onOpenAlbum?.let { add(Triple(Icons.Default.Album, stringResource(R.string.library_galaxy_open_album), it)) }
            } else {
                add(Triple(Icons.Default.Shuffle, stringResource(R.string.library_galaxy_shuffle_artist), onShuffle))
            }
            // Its system: three planets of different sizes.
            add(Triple(SystemGlyph, stringResource(R.string.galaxy_system_button), onSystem))
            onArtist?.let { add(Triple(Icons.Default.Person, stringResource(R.string.library_galaxy_open_artist), it)) }
        }
        val room = LocalConfiguration.current.screenWidthDp.toFloat()
        val n = pills.size.coerceAtLeast(1)
        val pillWidth = ((room - 2 * DOCK_MARGIN_DP - (n - 1) * DOCK_GAP_DP) / n).coerceIn(56f, GLYPH_PILL_WIDTH.value).dp
        Row(
            modifier = Modifier.padding(top = 6.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(DOCK_GAP_DP.dp),
        ) {
            val base = if (album == null) collaborators.size else 0
            pills.forEachIndexed { k, (icon, label, onClick) ->
                GlyphPill(icon, label, onClick, hazeState, Modifier.popIn(album?.key ?: starId, base + k), width = pillWidth)
            }
        }
    }
}

/** At most this many collaborators beside a selected artist, as pills. */
private const val MAX_COLLAB_PILLS = 3


