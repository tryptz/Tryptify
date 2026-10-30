package tf.monochrome.android.ui.themestore

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.repository.StoreError
import tf.monochrome.android.data.repository.StorePack
import tf.monochrome.android.data.repository.ThemePackRepository
import tf.monochrome.android.data.repository.ThemeStoreRepository
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.domain.model.ThemePart
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.musiclinks.LinksBackdrop
import tf.monochrome.android.ui.musiclinks.MusicLinkScrim
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.navigation.popBackStackSafe
import tf.monochrome.android.ui.settings.UndoBar
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.filled.PhotoCamera
import javax.inject.Inject

data class PackDetailState(
    val loading: Boolean = true,
    val error: StoreError? = null,
    val info: StorePack? = null,
    val pack: ThemePack? = null,
    /** The pack over the viewer's own look: what the app would look like after importing all of it. */
    val preview: ThemePack? = null,
    val liked: Boolean = false,
    val mine: Boolean = false,
    /** The author's store picture is being taken or sent. */
    val pictureBusy: Boolean = false,
    /** The last picture could not be taken or sent. */
    val pictureFailed: Boolean = false,
)

/**
 * [pack] laid over [own]: the pack's parts where it has them, the viewer's
 * everywhere else, and the viewer's personal settings inside the pack's parts
 * as an import would keep them.
 */
internal fun previewOf(own: ThemePack, pack: ThemePack): ThemePack = own.copy(
    name = pack.name,
    colours = pack.colours ?: own.colours,
    lyrics = pack.lyrics?.let { l -> own.lyrics?.let { l.withPersonalFrom(it) } ?: l } ?: own.lyrics,
    playerGlass = pack.playerGlass?.let { g -> own.playerGlass?.let { g.withPersonalFrom(it) } ?: g } ?: own.playerGlass,
    panelGlass = pack.panelGlass?.let { g -> own.panelGlass?.let { g.withPersonalFrom(it) } ?: g } ?: own.panelGlass,
    font = pack.font ?: own.font,
    visualizer = pack.visualizer ?: own.visualizer,
    maps = pack.maps ?: own.maps,
    links = pack.links ?: own.links,
    crt = pack.crt ?: own.crt,
)

@HiltViewModel
class ThemePackViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val store: ThemeStoreRepository,
    private val packs: ThemePackRepository,
    val spectrumAnalyzer: SpectrumAnalyzerTap,
) : ViewModel() {
    private val id: String = savedStateHandle.get<String>("packId").orEmpty()

    private val _state = MutableStateFlow(PackDetailState())
    val state: StateFlow<PackDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val own = packs.capture()
            store.fetch(id)
                .onSuccess { (info, pack) ->
                    val liked = store.likedAmong(listOf(id)).getOrDefault(emptySet())
                    _state.value = PackDetailState(
                        loading = false,
                        info = info,
                        pack = pack,
                        preview = previewOf(own, pack),
                        liked = id in liked,
                        mine = info.authorId == store.user.value?.id,
                    )
                }
                .onFailure { e ->
                    _state.value = PackDetailState(loading = false, error = e as? StoreError ?: StoreError.Network(e))
                }
        }
    }

    fun toggleLike() {
        val s = _state.value
        val info = s.info ?: return
        val liked = !s.liked
        // Shown at once; put back if the server says no.
        _state.value = s.copy(liked = liked, info = info.copy(likes = (info.likes + if (liked) 1 else -1).coerceAtLeast(0)))
        viewModelScope.launch {
            store.setLiked(id, liked).onFailure { _state.value = s }
        }
    }

    fun apply(keep: Set<ThemePart>, onDone: () -> Unit) {
        val pack = _state.value.pack ?: return
        viewModelScope.launch {
            packs.apply(pack, keep)
            store.markDownloaded(id)
            onDone()
        }
    }

    fun undo(onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(packs.undo()) }
    }

    fun setListed(listed: Boolean) {
        val s = _state.value
        val info = s.info ?: return
        _state.value = s.copy(info = info.copy(listed = listed))
        viewModelScope.launch { store.setListed(id, listed).onFailure { _state.value = s } }
    }

    fun delete(onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(store.delete(id).isSuccess) }
    }

    fun report(reason: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(store.report(id, reason).isSuccess) }
    }

    /** A pack's store picture, from the cache when it is there. */
    suspend fun picture(pack: StorePack): Bitmap? = store.picture(pack)

    fun takingPicture() {
        _state.value = _state.value.copy(pictureBusy = true, pictureFailed = false)
    }

    /** Sends [picture] as this pack's store picture; the author's only. */
    fun savePicture(picture: ImageBitmap?) {
        viewModelScope.launch {
            val image = picture?.let { withContext(Dispatchers.Default) { SnapshotCodec.encode(it) } }
            val hash = image?.let { store.setPicture(id, it).getOrNull() }
            val s = _state.value
            _state.value = s.copy(
                pictureBusy = false,
                pictureFailed = hash == null,
                info = if (hash != null) s.info?.copy(snapshot = hash) else s.info,
            )
        }
    }
}

/** Which in-window sheet the pack page has open. */
private enum class PackSheet { NONE, IMPORT, REPORT, DELETE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemePackScreen(
    navController: NavController,
    viewModel: ThemePackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var face by rememberSaveable { mutableStateOf(PreviewFace.PANELS) }
    var sheet by rememberSaveable { mutableStateOf(PackSheet.NONE) }
    var applied by remember { mutableStateOf(false) }
    val backdrop = rememberHazeState()
    val overlay = rememberHazeState()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val accent = MaterialTheme.colorScheme.primary
    val reportedText = stringResource(R.string.theme_pack_reported)
    val undoneText = stringResource(R.string.theme_store_undone)
    val copiedText = stringResource(R.string.theme_pack_code_copied)
    val taker = remember { SnapshotTaker() }
    val scope = rememberCoroutineScope()
    val takePicture: () -> Unit = {
        state.preview?.let { look ->
            scope.launch {
                viewModel.takingPicture()
                viewModel.savePicture(taker.take(look))
            }
        }
    }
    // A pack of yours from before store pictures gets one the first time you
    // open it, so the store is not left showing its colours.
    var autoTried by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.mine, state.info?.id, state.info?.snapshot) {
        val info = state.info
        if (state.mine && info != null && info.snapshot == null && !autoTried && !state.pictureBusy) {
            autoTried = true
            takePicture()
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Under the opaque backdrop: drawn only while it takes the picture.
        SnapshotHost(taker, viewModel.spectrumAnalyzer)
        Box(Modifier.fillMaxSize().hazeSource(overlay)) {
            LinksBackdrop(Modifier.fillMaxSize().hazeSource(backdrop))
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text(state.info?.name ?: stringResource(R.string.theme_store_title), maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { navController.popBackStackSafe() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                        }
                    },
                    actions = {
                        if (state.info != null && state.mine) {
                            IconButton(onClick = { sheet = PackSheet.DELETE }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.theme_pack_delete))
                            }
                        } else if (state.info != null) {
                            IconButton(onClick = { sheet = PackSheet.REPORT }) {
                                Icon(Icons.Outlined.Flag, contentDescription = stringResource(R.string.theme_pack_report))
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                )
                val info = state.info
                val preview = state.preview
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    info == null || preview == null -> Column(
                        Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(errorText(state.error ?: StoreError.BadPack()), style = MaterialTheme.typography.bodyLarge)
                        GlassPill(onClick = viewModel::load, hazeState = backdrop, height = 44.dp, modifier = Modifier.padding(top = 12.dp)) {
                            Text(stringResource(R.string.theme_store_retry), fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                    else -> CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 32.dp + LocalBottomChromeInset.current + navBar),
                        ) {
                            item(key = "preview") {
                                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        PreviewFace.entries.forEach { f ->
                                            GlassChoiceChip(
                                                label = PreviewFaceLabel(f),
                                                selected = face == f,
                                                accent = accent,
                                                height = 40.dp,
                                                onClick = { face = f },
                                            )
                                        }
                                    }
                                    PackPreview(preview, face, viewModel.spectrumAnalyzer)
                                    Text(
                                        stringResource(R.string.theme_pack_preview_note),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    GlassPill(
                                        onClick = { sheet = PackSheet.IMPORT },
                                        hazeState = backdrop,
                                        height = 56.dp,
                                        glass = LocalMiniPlayerGlass.current.copy(tintColor = accent.toArgb()),
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(22.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Text(stringResource(R.string.theme_pack_use), fontWeight = FontWeight.ExtraBold)
                                        }
                                    }
                                }
                            }
                            item(key = "about") {
                                PackCard(backdrop) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(info.name, style = MaterialTheme.typography.titleLarge)
                                        Text(
                                            stringResource(R.string.theme_store_by, info.authorName.ifBlank { stringResource(R.string.theme_store_someone) }),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = LocalContentColor.current.copy(alpha = 0.8f),
                                        )
                                        if (info.description.isNotBlank()) {
                                            Text(info.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.padding(top = 6.dp),
                                        ) {
                                            GlassPill(
                                                onClick = viewModel::toggleLike,
                                                hazeState = backdrop,
                                                height = 44.dp,
                                                onClickLabel = stringResource(if (state.liked) R.string.theme_pack_unlike else R.string.theme_pack_like),
                                            ) {
                                                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        if (state.liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                        contentDescription = null,
                                                        tint = if (state.liked) Color(0xFFFF4D6D) else LocalContentColor.current,
                                                        modifier = Modifier.size(20.dp),
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("${info.likes}", fontWeight = FontWeight.Bold)
                                                }
                                            }
                                            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Text(
                                                pluralStringResource(R.plurals.theme_pack_downloads, info.downloads, info.downloads),
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                    }
                                }
                            }
                            item(key = "inside") {
                                PackCard(backdrop) {
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(stringResource(R.string.theme_pack_inside), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        ThemePart.entries.filter { it in info.parts || it in (state.pack?.parts ?: emptySet()) }.forEach { part ->
                                            PartRow(part)
                                        }
                                    }
                                }
                            }
                            if (state.mine) {
                                item(key = "mine") {
                                    PackCard(backdrop) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(stringResource(R.string.theme_pack_yours), style = MaterialTheme.typography.titleMedium)
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                StorePicture(
                                                    info,
                                                    viewModel::picture,
                                                    Modifier.width(72.dp).aspectRatio(SNAPSHOT_ASPECT).clip(RoundedCornerShape(12.dp)),
                                                )
                                                Spacer(Modifier.width(14.dp))
                                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Text(stringResource(R.string.theme_pack_picture), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                                                    Text(
                                                        stringResource(
                                                            when {
                                                                state.pictureFailed -> R.string.theme_pack_picture_failed
                                                                info.snapshot == null -> R.string.theme_pack_picture_none
                                                                else -> R.string.theme_pack_picture_desc
                                                            },
                                                        ),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = if (state.pictureFailed) MaterialTheme.colorScheme.error else LocalContentColor.current.copy(alpha = 0.8f),
                                                    )
                                                    GlassPill(
                                                        onClick = takePicture,
                                                        hazeState = backdrop,
                                                        height = 40.dp,
                                                        enabled = !state.pictureBusy,
                                                    ) {
                                                        Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                            if (state.pictureBusy) {
                                                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                                            } else {
                                                                Icon(Icons.Default.PhotoCamera, contentDescription = null, modifier = Modifier.size(18.dp))
                                                            }
                                                            Spacer(Modifier.width(6.dp))
                                                            Text(
                                                                stringResource(
                                                                    if (state.pictureBusy) R.string.theme_pack_picture_taking else R.string.theme_pack_picture_take,
                                                                ),
                                                                fontWeight = FontWeight.Bold,
                                                                maxLines = 1,
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(stringResource(R.string.theme_pack_code_label), style = MaterialTheme.typography.bodySmall)
                                                    Text(info.shareCode, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                                                }
                                                GlassPill(
                                                    onClick = {
                                                        copyText(context, info.shareCode)
                                                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                                    },
                                                    hazeState = backdrop,
                                                    height = 44.dp,
                                                ) {
                                                    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                                                        Spacer(Modifier.width(6.dp))
                                                        Text(stringResource(R.string.theme_pack_copy_code), fontWeight = FontWeight.Bold)
                                                    }
                                                }
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(stringResource(R.string.theme_publish_listed), style = MaterialTheme.typography.bodyLarge)
                                                    Text(
                                                        stringResource(R.string.theme_publish_listed_desc),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = LocalContentColor.current.copy(alpha = 0.8f),
                                                    )
                                                }
                                                Switch(checked = info.listed, onCheckedChange = viewModel::setListed)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val pack = state.pack
        when (sheet) {
            PackSheet.IMPORT -> if (pack != null) {
                ImportSheet(
                    parts = pack.parts,
                    hazeState = overlay,
                    onDismiss = { sheet = PackSheet.NONE },
                    onApply = { keep ->
                        viewModel.apply(keep) {
                            sheet = PackSheet.NONE
                            applied = true
                        }
                    },
                )
            }
            PackSheet.REPORT -> ReportSheet(
                hazeState = overlay,
                onDismiss = { sheet = PackSheet.NONE },
                onSend = { reason ->
                    viewModel.report(reason) { ok ->
                        sheet = PackSheet.NONE
                        if (ok) Toast.makeText(context, reportedText, Toast.LENGTH_SHORT).show()
                    }
                },
            )
            PackSheet.DELETE -> ConfirmSheet(
                title = stringResource(R.string.theme_pack_delete_title),
                body = stringResource(R.string.theme_pack_delete_desc),
                confirm = stringResource(R.string.theme_pack_delete),
                hazeState = overlay,
                onDismiss = { sheet = PackSheet.NONE },
                onConfirm = {
                    viewModel.delete { ok ->
                        sheet = PackSheet.NONE
                        if (ok) navController.popBackStackSafe()
                    }
                },
            )
            PackSheet.NONE -> Unit
        }

        if (applied && sheet == PackSheet.NONE) {
            LaunchedEffect(Unit) {
                delay(8000)
                applied = false
            }
            UndoBar(
                text = stringResource(R.string.theme_pack_applied),
                hazeState = overlay,
                onUndo = {
                    viewModel.undo { ok -> if (ok) Toast.makeText(context, undoneText, Toast.LENGTH_SHORT).show() }
                    applied = false
                },
            )
        }
    }
}

@Composable
internal fun PackCard(hazeState: HazeState, content: @Composable () -> Unit) {
    GlassPanel(
        hazeState = hazeState,
        glass = LocalMiniPlayerGlass.current,
        avoidNavigationBar = false,
        // In a scrolling list: a swipe on the pane scrolls it.
        blockTouchesBelow = false,
    ) {
        Box(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun PartRow(part: ThemePart) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(partIcon(part), contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(partLabel(part), style = MaterialTheme.typography.bodyLarge)
            Text(partDescription(part), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f))
        }
    }
}

/**
 * "Use this theme": every part the pack has, each one on by default and each
 * one a switch, so a listener who only wants the lyrics takes only the lyrics.
 */
@Composable
private fun BoxScope.ImportSheet(
    parts: Set<ThemePart>,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onApply: (Set<ThemePart>) -> Unit,
) {
    var keep by remember(parts) { mutableStateOf(parts) }
    var busy by remember { mutableStateOf(false) }
    SheetFrame(hazeState, onDismiss) {
        Text(stringResource(R.string.theme_import_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.theme_import_desc),
            style = MaterialTheme.typography.bodySmall,
            color = LocalContentColor.current.copy(alpha = 0.85f),
        )
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ThemePart.entries.filter { it in parts }.forEach { part ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(partIcon(part), contentDescription = null, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(partLabel(part), style = MaterialTheme.typography.bodyLarge)
                        Text(partDescription(part), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.8f))
                    }
                    Switch(checked = part in keep, onCheckedChange = { on -> keep = if (on) keep + part else keep - part })
                }
            }
        }
        Text(
            stringResource(R.string.theme_import_kept),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPill(onClick = onDismiss, hazeState = hazeState, height = 52.dp, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Bold)
            }
            GlassPill(
                onClick = {
                    busy = true
                    onApply(keep)
                },
                hazeState = hazeState,
                height = 52.dp,
                enabled = keep.isNotEmpty() && !busy,
                glass = LocalMiniPlayerGlass.current.copy(tintColor = MaterialTheme.colorScheme.primary.toArgb()),
                modifier = Modifier.weight(1.4f),
            ) {
                Text(pluralStringResource(R.plurals.theme_import_apply, keep.size, keep.size), fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun BoxScope.ReportSheet(hazeState: HazeState, onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    val accent = MaterialTheme.colorScheme.primary
    val reasons = listOf(
        stringResource(R.string.theme_report_name),
        stringResource(R.string.theme_report_broken),
        stringResource(R.string.theme_report_copy),
    )
    SheetFrame(hazeState, onDismiss) {
        Text(stringResource(R.string.theme_pack_report), style = MaterialTheme.typography.titleLarge)
        Text(stringResource(R.string.theme_report_desc), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = 0.85f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            reasons.forEach { r ->
                GlassChoiceChip(label = r, selected = reason == r, accent = accent, height = 38.dp, onClick = { reason = r })
            }
        }
        OutlinedTextField(
            value = reason,
            onValueChange = { reason = it.take(280) },
            placeholder = { Text(stringResource(R.string.theme_report_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPill(onClick = onDismiss, hazeState = hazeState, height = 50.dp, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Bold)
            }
            GlassPill(onClick = { onSend(reason) }, hazeState = hazeState, height = 50.dp, modifier = Modifier.weight(1.3f)) {
                Text(stringResource(R.string.theme_report_send), fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
internal fun BoxScope.ConfirmSheet(
    title: String,
    body: String,
    confirm: String,
    hazeState: HazeState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    SheetFrame(hazeState, onDismiss) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.85f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPill(onClick = onDismiss, hazeState = hazeState, height = 50.dp, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Bold)
            }
            GlassPill(onClick = onConfirm, hazeState = hazeState, height = 50.dp, modifier = Modifier.weight(1f)) {
                Text(confirm, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * A sheet of the store's glass at the bottom of its screen, over a scrim, in
 * the screen's own window: a sibling of [hazeState]'s source, never a dialog.
 */
@Composable
internal fun BoxScope.SheetFrame(hazeState: HazeState, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    MusicLinkScrim(onDismiss)
    CompositionLocalProvider(LocalGlassBackdrop provides hazeState) {
        GlassPanel(
            hazeState = hazeState,
            glass = LocalMiniPlayerGlass.current,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomChromeNow.current),
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                content()
            }
        }
    }
}

internal fun copyText(context: Context, text: String) {
    tf.monochrome.android.util.SafeClipboard.copy(context, "Tryptify theme", text)
}
