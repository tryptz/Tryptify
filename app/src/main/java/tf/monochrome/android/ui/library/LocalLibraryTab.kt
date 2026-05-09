package tf.monochrome.android.ui.library

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Style
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.theme.MonoDimens

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun LocalLibraryTab(
    viewModel: LocalLibraryViewModel,
    onTrackClick: (UnifiedTrack, List<UnifiedTrack>) -> Unit,
    onAlbumClick: (UnifiedAlbum) -> Unit,
    onArtistClick: (UnifiedArtist) -> Unit,
    onGenreClick: (String) -> Unit,
    onFolderClick: (String) -> Unit,
    onShuffleAll: (List<UnifiedTrack>) -> Unit
) {
    val localTracks by viewModel.localTracks.collectAsState()
    val localAlbums by viewModel.localAlbums.collectAsState()
    val localArtists by viewModel.localArtists.collectAsState()
    val localGenres by viewModel.localGenres.collectAsState()
    val rootFolders by viewModel.displayRootFolders.collectAsState()
    val scanProgress by viewModel.scanProgress.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()

    var selectedSubTab by rememberSaveable { mutableIntStateOf(0) }
    val subTabs = listOf("Albums", "Artists", "Songs", "Genres", "Folders")
    var showSearch by remember { mutableStateOf(false) }

    val context = LocalContext.current

    // Permissions for reading audio files AND sidecar cover images. On API
    // 33+ these are independent runtime grants — without READ_MEDIA_IMAGES
    // we can't stat() the JPG sitting next to a FLAC, so per-track sidecar
    // covers never load even though the audio plays fine.
    val mediaPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        listOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
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
        // If we have audio but not images yet, prompt once silently — but
        // don't gate the UI; the user has already opted into local library
        // and the absence of cover images shouldn't block playback.
        LaunchedEffect(permissionState.allPermissionsGranted) {
            if (!permissionState.allPermissionsGranted) {
                permissionState.launchMultiplePermissionRequest()
            }
        }

        // Scan progress bar
        if (isScanning) {
            ScanProgressBar(scanProgress)
        }

        // Search bar
        AnimatedVisibility(visible = showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
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
            SongList(tracks = searchResults, onTrackClick = onTrackClick)
            return@Column
        }

        // Sub-tabs + action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedSubTab,
                modifier = Modifier.weight(1f),
                containerColor = MaterialTheme.colorScheme.background,
                edgePadding = 8.dp
            ) {
                subTabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedSubTab == index,
                        onClick = { selectedSubTab = index },
                        text = { Text(title, style = MaterialTheme.typography.bodySmall) }
                    )
                }
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
        when (selectedSubTab) {
            0 -> AlbumGrid(albums = localAlbums, onAlbumClick = onAlbumClick)
            1 -> ArtistList(artists = localArtists, onArtistClick = onArtistClick)
            2 -> SongList(tracks = localTracks, onTrackClick = onTrackClick)
            3 -> GenreList(
                genres = genrePairs,
                onGenreClick = onGenreClick
            )
            4 -> FolderList(
                folders = rootFolders,
                onFolderClick = onFolderClick
            )
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
                    "Monochrome needs access to your audio files to scan and play local music. Please grant the permission."
                else
                    "Grant access to your audio files so Monochrome can scan and play your local music library.",
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
    onTrackClick: (UnifiedTrack, List<UnifiedTrack>) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = MonoDimens.listBottomPadding)
    ) {
        items(tracks, key = { it.id }) { track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .bounceClick(onClick = { onTrackClick(track, tracks) })
                    .padding(horizontal = MonoDimens.listItemPaddingH, vertical = MonoDimens.listItemPaddingV),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (track.artworkUri != null) {
                    AsyncImage(
                        model = track.artworkUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MonoDimens.shapeSm)
                    )
                } else {
                    Box(
                        modifier = Modifier.size(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                        Text(
                            track.artistName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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

/**
 * Resolve a SAF tree URI (from `OpenDocumentTree`) to a best-guess filesystem path.
 * Handles "primary" (emulated) storage plus SD-card volume IDs. Returns null if the
 * URI isn't a recognized tree document — callers should fall back gracefully.
 */
private fun safTreeUriToPath(uri: Uri): String? = runCatching {
    val docId = DocumentsContract.getTreeDocumentId(uri)
    val parts = docId.split(":", limit = 2)
    if (parts.size != 2) return@runCatching null
    val (type, path) = parts
    val base = if (type.equals("primary", ignoreCase = true)) {
        "/storage/emulated/0"
    } else {
        "/storage/$type"
    }
    if (path.isBlank()) base else "$base/$path".trimEnd('/')
}.getOrNull()

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
