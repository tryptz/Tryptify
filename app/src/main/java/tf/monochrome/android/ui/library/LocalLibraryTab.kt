package tf.monochrome.android.ui.library

import tf.monochrome.android.ui.theme.goToPage
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.google.accompanist.permissions.isGranted
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.shouldShowRationale
import tf.monochrome.android.data.local.scanner.ScanProgress
import tf.monochrome.android.domain.model.UnifiedAlbum
import tf.monochrome.android.domain.model.UnifiedArtist
import tf.monochrome.android.domain.model.UnifiedTrack
import androidx.navigation.NavController
import tf.monochrome.android.ui.components.TrackArtistAlbumLine
import tf.monochrome.android.ui.components.UnifiedTrackContextMenuHost
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.liquidGlass
import tf.monochrome.android.ui.navigation.openAlbum
import tf.monochrome.android.ui.navigation.openArtist
import tf.monochrome.android.ui.player.PlayerViewModel
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.util.safTreeUriToPath

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun LocalLibraryTab(
    viewModel: LocalLibraryViewModel,
    onTrackClick: (UnifiedTrack, List<UnifiedTrack>) -> Unit,
    onAlbumClick: (UnifiedAlbum) -> Unit,
    onArtistClick: (UnifiedArtist) -> Unit,
    onGenreClick: (String) -> Unit,
    onFolderClick: (String) -> Unit,
    onShuffleAll: (List<UnifiedTrack>) -> Unit,
    navController: NavController,
    playerViewModel: PlayerViewModel
) {
    val localTracks by viewModel.localTracks.collectAsStateWithLifecycle()
    val sortedTracks by viewModel.sortedTracks.collectAsStateWithLifecycle()
    val sortedAlbums by viewModel.sortedAlbums.collectAsStateWithLifecycle()
    val sortedArtists by viewModel.sortedArtists.collectAsStateWithLifecycle()
    val songSort by viewModel.songSort.collectAsStateWithLifecycle()
    val albumSort by viewModel.albumSort.collectAsStateWithLifecycle()
    val artistSort by viewModel.artistSort.collectAsStateWithLifecycle()
    val localGenres by viewModel.localGenres.collectAsStateWithLifecycle()
    val rootFolders by viewModel.displayRootFolders.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()

    val subTabs = listOf("Albums", "Artists", "Songs", "Genres", "Folders")
    // Sub-tabs are swipeable pages. The tab row above them is a selector onto
    // the SAME pager state rather than a second source of truth, so a swipe and
    // a tap can't disagree — and ScrollableTabRow scrolls the selected tab into
    // view, which is what un-clips "Folders" when you swipe onto it.
    val subTabPager = rememberPagerState(pageCount = { subTabs.size })
    val subTabScope = rememberCoroutineScope()
    // Tab changes slide normally; with "Disable animations" on they jump.
    val animateTabs = !tf.monochrome.android.ui.theme.reduceMotion()
    val selectedSubTab = subTabPager.currentPage
    var showSearch by remember { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Focus the field (and pop the IME) the moment search opens, so it doesn't
    // take a second tap.
    LaunchedEffect(showSearch) {
        if (showSearch) runCatching { searchFocus.requestFocus() }
    }
    // Clear the query when leaving the library, so a stale search doesn't
    // resurface (and instantly replace the tab with old results) next time.
    DisposableEffect(Unit) {
        onDispose { viewModel.setSearchQuery("") }
    }

    val context = LocalContext.current

    // Permissions for reading audio files AND sidecar cover images. On API
    // 33+ these are independent runtime grants — without READ_MEDIA_IMAGES
    // we can't stat() the JPG sitting next to a FLAC, so per-track sidecar
    // covers never load even though the audio plays fine. READ_MEDIA_VIDEO
    // lets the scanner see Atmos music videos, which MediaStore files in the
    // video table (only E-AC-3 tracks are actually imported).
    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    } else {
        listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    val permissionState = rememberMultiplePermissionsState(mediaPermissions)

    // SAF folder picker - takes persistent URI permission for the selected folder
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistent read permission so we can access this folder across restarts
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            // Persist the selected folder so it shows up in the Folders tab even
            // before MediaStore re-indexes and the scanner derives it from tracks.
            safTreeUriToPath(uri)?.let { viewModel.addUserFolderRoot(it) }
            // Trigger a full scan after adding a folder (MediaStore will include it)
            viewModel.startFullScan()
        }
    }

    var menuTrack by remember { mutableStateOf<UnifiedTrack?>(null) }
    UnifiedTrackContextMenuHost(
        track = menuTrack,
        onDismissRequest = { menuTrack = null },
        navController = navController,
        playerViewModel = playerViewModel,
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Permission gate — block UI only until READ_MEDIA_AUDIO is granted
        // (image grant is best-effort; tracks still play, sidecar covers
        // just won't render). Audio is permission index 0.
        val audioGranted = permissionState.permissions.firstOrNull {
            it.permission == Manifest.permission.READ_MEDIA_AUDIO ||
                it.permission == Manifest.permission.READ_EXTERNAL_STORAGE
        }?.status?.isGranted == true
        if (!audioGranted) {
            PermissionRequest(
                shouldShowRationale = permissionState.shouldShowRationale,
                onRequestPermission = { permissionState.launchMultiplePermissionRequest() }
            )
            return@Column
        }
        // If we have audio but not the secondary grants yet (images for sidecar
        // covers, video for Atmos music videos), prompt once — but don't gate
        // the UI; the user has already opted into the local library and neither
        // covers nor the odd video file should block playback. This is also what
        // surfaces the new video grant to users upgrading over an install that
        // already had audio, since the audio gate above never re-prompts. Keyed
        // on Unit + a saveable one-shot flag so the system dialog fires once
        // instead of re-firing on every re-composition.
        var secondaryPermissionsRequested by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            if (!permissionState.allPermissionsGranted && !secondaryPermissionsRequested) {
                secondaryPermissionsRequested = true
                permissionState.launchMultiplePermissionRequest()
            }
        }

        // Scan progress bar — also shown on the terminal Complete/Error states
        // (previously dead UI) so the user sees the summary or the error, then
        // auto-dismissed a few seconds later.
        val scanTerminal = scanProgress is ScanProgress.Complete || scanProgress is ScanProgress.Error
        if (isScanning || scanTerminal) {
            ScanProgressBar(scanProgress)
        }
        LaunchedEffect(scanProgress) {
            if (scanProgress is ScanProgress.Complete || scanProgress is ScanProgress.Error) {
                kotlinx.coroutines.delay(4000)
                viewModel.clearScanProgress()
            }
        }

        // Search bar
        AnimatedVisibility(visible = showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(searchFocus)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text("Search local library...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = MonoDimens.shapeMd,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )
        }

        // Show search results when query is active
        if (showSearch && searchQuery.isNotBlank()) {
            SongList(
                tracks = searchResults,
                onTrackClick = onTrackClick,
                onMoreClick = { menuTrack = it },
                navController = navController,
            )
            return@Column
        }

        // Sub-tabs get the full width. Sharing one row with the sort menu and
        // four icon buttons left the five labels about 120dp on a 360dp screen,
        // so "Albums" and "Folders" were clipped at both ends and the row was
        // permanently mid-scroll. Actions moved to their own row underneath.
        ScrollableTabRow(
            selectedTabIndex = selectedSubTab,
            modifier = Modifier.fillMaxWidth(),
            containerColor = MaterialTheme.colorScheme.background,
            edgePadding = 8.dp
        ) {
            subTabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedSubTab == index,
                    onClick = { subTabScope.launch { subTabPager.goToPage(index, animateTabs) } },
                    text = { Text(title, style = MaterialTheme.typography.bodySmall) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedSubTab in 0..2) {
                val (keys, current, onChange) = when (selectedSubTab) {
                    0 -> Triple(ALBUM_SORT_KEYS, albumSort, viewModel::setAlbumSort)
                    1 -> Triple(ARTIST_SORT_KEYS, artistSort, viewModel::setArtistSort)
                    else -> Triple(SONG_SORT_KEYS, songSort, viewModel::setSongSort)
                }
                SortMenu(keys = keys, current = current, onChange = onChange)
            }
            IconButton(onClick = { showSearch = !showSearch; if (!showSearch) viewModel.setSearchQuery("") }) {
                Icon(Icons.Default.Search, contentDescription = "Search")
            }
            IconButton(
                onClick = { if (localTracks.isNotEmpty()) onShuffleAll(localTracks) },
                enabled = localTracks.isNotEmpty()
            ) {
                Icon(Icons.Default.Shuffle, contentDescription = "Shuffle all")
            }
            IconButton(onClick = { folderPickerLauncher.launch(null) }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "Add folder")
            }
            IconButton(
                onClick = {
                    if (!isScanning) {
                        // Always fullScan: incremental only iterates files
                        // whose mtime moved since the last run, which means
                        // when scanner *logic* changes (e.g. a new sidecar
                        // matcher), already-indexed rows never get re-read
                        // and stay stuck with the older logic's verdict.
                        // Full scan walks everything and lets fullScan's
                        // per-file rescan triggers (artworkMissing,
                        // maybeMissedArt) decide what to re-tag.
                        viewModel.startFullScan()
                    }
                }
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Scan")
            }
        }

        val genrePairs = remember(localGenres) {
            localGenres.map { it.name to it.trackCount }
        }
        // Nested inside Library's section pager, which is itself nested in the
        // nav host's Home↔Library pager. Compose chains all three through nested
        // scroll: this innermost one consumes the drag until it runs out of
        // sub-tabs, then the section pager takes over, then the outer one — so
        // it's one continuous swipe from Albums all the way out to Home.
        //
        // fillMaxWidth without weight() on purpose: the `when` this replaced
        // sized itself from its child under the Column's remaining-height
        // constraint, and weight(1f) would starve the empty state below it.
        HorizontalPager(
            state = subTabPager,
            modifier = Modifier.fillMaxWidth(),
            beyondViewportPageCount = 0,
        ) { page ->
            when (page) {
                0 -> AlbumGrid(albums = sortedAlbums, onAlbumClick = onAlbumClick)
                1 -> ArtistList(artists = sortedArtists, onArtistClick = onArtistClick)
                2 -> SongList(
                    tracks = sortedTracks,
                    onTrackClick = onTrackClick,
                    onMoreClick = { menuTrack = it },
                    navController = navController,
                )
                3 -> GenreList(
                    genres = genrePairs,
                    onGenreClick = onGenreClick
                )
                4 -> FolderList(
                    folders = rootFolders,
                    onFolderClick = onFolderClick
                )
            }
        }

        // Empty state
        if (!isScanning && localTracks.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No local music found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Tap the refresh button to scan your device",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

/**
 * Sort affordance for the Albums / Artists / Songs tabs. Tapping the icon opens a
 * menu of the tab's available [keys]; selecting the current key flips the
 * direction, selecting another switches to it ascending. The active key shows an
 * up/down arrow.
 */
@Composable
private fun SortMenu(
    keys: List<LibrarySortKey>,
    current: LibrarySort,
    onChange: (LibrarySort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            keys.forEach { key ->
                val selected = key == current.key
                DropdownMenuItem(
                    text = { Text(key.label) },
                    onClick = {
                        onChange(
                            if (selected) current.copy(ascending = !current.ascending)
                            else LibrarySort(key, ascending = true)
                        )
                        expanded = false
                    },
                    trailingIcon = {
                        if (selected) {
                            Icon(
                                if (current.ascending) Icons.Default.ArrowUpward
                                else Icons.Default.ArrowDownward,
                                contentDescription = if (current.ascending) "Ascending" else "Descending",
                                modifier = Modifier.size(MonoDimens.iconSm),
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PermissionRequest(
    shouldShowRationale: Boolean,
    onRequestPermission: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "Audio permission required",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                if (shouldShowRationale)
                    "Tryptify needs access to your audio files to scan and play local music. Please grant the permission."
                else
                    "Grant access to your audio files so Tryptify can scan and play your local music library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = onRequestPermission) {
                Text("Grant permission")
            }
        }
    }
}

@Composable
private fun ScanProgressBar(progress: ScanProgress?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        when (progress) {
            is ScanProgress.Started -> {
                Text("Scanning ${progress.totalFiles} files...", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is ScanProgress.Processing -> {
                Text(
                    "Scanning: ${progress.currentFile}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                LinearProgressIndicator(
                    progress = { progress.current.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            is ScanProgress.Grouping -> {
                Text(progress.message, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            is ScanProgress.Complete -> {
                Text(
                    "Scan complete: ${progress.scanned} files, ${progress.added} new",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            is ScanProgress.Error -> {
                Text(
                    "Scan error: ${progress.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            null -> {}
        }
    }
}

@Composable
fun AlbumGrid(
    albums: List<UnifiedAlbum>,
    onAlbumClick: (UnifiedAlbum) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(MonoDimens.spacingLg),
        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
        verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd)
    ) {
        items(albums, key = { it.id }) { album ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = { onAlbumClick(album) }),
                shape = MonoDimens.shapeMd,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = MonoDimens.cardAlpha)
                )
            ) {
                Column {
                    if (album.artworkUri != null) {
                        AsyncImage(
                            model = album.artworkUri,
                            contentDescription = album.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f)
                                .clip(MonoDimens.shapeMd)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Album,
                                contentDescription = null,
                                modifier = Modifier.size(MonoDimens.coverList),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                            )
                        }
                    }
                    Column(modifier = Modifier.padding(MonoDimens.spacingSm)) {
                        Text(
                            album.title,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            album.artistName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (album.qualitySummary != null) {
                            Text(
                                album.qualitySummary,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ArtistList(
    artists: List<UnifiedArtist>,
    onArtistClick: (UnifiedArtist) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = MonoDimens.listBottomPadding)
    ) {
        items(artists, key = { it.id }) { artist ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = { onArtistClick(artist) })
                    .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingMd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (artist.artworkUri != null) {
                    AsyncImage(
                        model = artist.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(40.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(MonoDimens.spacingLg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${artist.albumCount} albums, ${artist.trackCount} tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun SongList(
    tracks: List<UnifiedTrack>,
    onTrackClick: (UnifiedTrack, List<UnifiedTrack>) -> Unit,
    onMoreClick: (UnifiedTrack) -> Unit,
    navController: NavController
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = MonoDimens.listBottomPadding)
    ) {
        items(tracks, key = { it.id }) { track ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingXs)
                    .bounceClick(onClick = { onTrackClick(track, tracks) })
                    .liquidGlass(shape = MonoDimens.shapeMd),
                shape = MonoDimens.shapeMd,
                color = Color.Transparent,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.spacingSm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Music-note placeholder sits underneath the artwork: if the
                    // cover loads (cached JPG, sidecar, or embedded art pulled on
                    // demand by AudioFileCoverFetcher) it covers the icon; if the
                    // file genuinely has no art the icon stays visible.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MonoDimens.shapeSm),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        if (track.artworkUri != null) {
                            AsyncImage(
                                model = track.artworkUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize()
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(MonoDimens.spacingMd))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            track.title,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row {
                            TrackArtistAlbumLine(
                                track = track,
                                onArtistClick = { ref -> ref.id?.let { navController.openArtist(track.sourceType, it) } },
                                onAlbumClick = { navController.openAlbum(track.albumId) },
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            track.qualityBadge?.let { badge ->
                                Spacer(modifier = Modifier.width(MonoDimens.spacingSm))
                                Text(
                                    badge,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                    Text(
                        track.formattedDuration,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { onMoreClick(track) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GenreList(
    genres: List<Pair<String, Int>>,
    onGenreClick: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = MonoDimens.listBottomPadding)
    ) {
        items(genres, key = { it.first }) { (genre, count) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = { onGenreClick(genre) })
                    .padding(horizontal = MonoDimens.listItemPaddingH, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Style,
                    contentDescription = null,
                    modifier = Modifier.size(MonoDimens.iconMd),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(MonoDimens.spacingLg))
                Text(
                    genre,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "$count tracks",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun FolderList(
    folders: List<Pair<String, String>>,
    onFolderClick: (String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = MonoDimens.listBottomPadding)
    ) {
        items(folders, key = { it.second }) { (name, path) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = { onFolderClick(path) })
                    .padding(horizontal = MonoDimens.listItemPaddingH, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    modifier = Modifier.size(MonoDimens.iconMd),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(MonoDimens.spacingLg))
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
