package tf.monochrome.android.ui.themestore

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tf.monochrome.android.R
import tf.monochrome.android.data.repository.StoreError
import tf.monochrome.android.data.repository.StorePack
import tf.monochrome.android.data.repository.StoreSort
import tf.monochrome.android.data.repository.ThemePackRepository
import tf.monochrome.android.data.repository.ThemeStoreRepository
import tf.monochrome.android.ui.components.GlassPanel
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.PressableGlass
import tf.monochrome.android.ui.components.SearchOverlay
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.musiclinks.LinksBackdrop
import tf.monochrome.android.ui.musiclinks.MusicLinkScrim
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.navigation.LocalBottomChromeNow
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.navigateSafe
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.popBackStackSafe
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import android.graphics.Bitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import javax.inject.Inject

/** One page of the store's list, and how it got there. */
data class StoreListState(
    val items: List<StorePack> = emptyList(),
    val loading: Boolean = false,
    val error: StoreError? = null,
    val endReached: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class ThemeStoreViewModel @Inject constructor(
    private val store: ThemeStoreRepository,
    private val packs: ThemePackRepository,
) : ViewModel() {
    val signedIn: StateFlow<Boolean> = store.user.map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.user.value != null)

    val canUndo: StateFlow<Boolean> = packs.canUndo.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _sort = MutableStateFlow(StoreSort.POPULAR)
    val sort: StateFlow<StoreSort> = _sort.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _state = MutableStateFlow(StoreListState())
    val state: StateFlow<StoreListState> = _state.asStateFlow()

    private var page = 0
    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                store.user.map { it?.id }.distinctUntilChanged(),
                _sort,
                _query.debounce(350).distinctUntilChanged(),
            ) { uid, sort, query -> Triple(uid, sort, query) }
                .collect { (uid, _, _) -> if (uid != null) reload() else _state.value = StoreListState() }
        }
    }

    fun setSort(sort: StoreSort) {
        _sort.value = sort
    }

    fun setQuery(query: String) {
        _query.value = query
    }

    fun reload() {
        page = 0
        load(replace = true)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.endReached || s.error != null) return
        page++
        load(replace = false)
    }

    private fun load(replace: Boolean) {
        loadJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null, items = if (replace) emptyList() else _state.value.items)
        loadJob = viewModelScope.launch {
            store.browse(_sort.value, _query.value, page)
                .onSuccess { got ->
                    val items = if (replace) got else (_state.value.items + got).distinctBy { it.id }
                    _state.value = StoreListState(items = items, endReached = got.size < 20)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, error = e as? StoreError ?: StoreError.Network(e))
                }
        }
    }

    /**
     * The list again, as far down as it was read, without emptying it first:
     * coming back from a pack, its likes and downloads (and a picture just
     * taken) change under the reader instead of the list blinking empty.
     */
    fun refreshQuietly() {
        if (_state.value.loading || store.user.value == null) return
        val pages = page
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val all = mutableListOf<StorePack>()
            var end = false
            for (p in 0..pages) {
                val got = store.browse(_sort.value, _query.value, p).getOrNull() ?: return@launch
                all += got
                if (got.size < 20) {
                    end = true
                    break
                }
            }
            _state.value = _state.value.copy(items = all.distinctBy { it.id }, endReached = end)
        }
    }

    /** A pack's store picture, from the cache when it is there. */
    suspend fun picture(pack: StorePack): Bitmap? = store.picture(pack)

    /** Puts back the look from before the last theme. */
    fun undo(onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(packs.undo()) }
    }

    /** The pack a share code opens, or null if no pack has it. */
    suspend fun findCode(code: String): Result<StorePack?> = store.byCode(code)
}

/**
 * The Theme Store: themes other people made, sorted by what is liked or by
 * what is new, plus your own; a pack opens to its live preview. Everything
 * here needs an account, so signed out the page is one panel saying so and
 * the way to sign in.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeStoreScreen(
    navController: NavController,
    viewModel: ThemeStoreViewModel = hiltViewModel(),
    /**
     * Shown as a page of Settings, under its chips: no bar of its own and no
     * Back, only its undo and search, clear of the settings search bar.
     */
    embedded: Boolean = false,
) {
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var codeOpen by rememberSaveable { mutableStateOf(false) }

    val backdrop = rememberHazeState()
    val overlay = rememberHazeState()
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val undoneText = stringResource(R.string.theme_store_undone)
    // Back from a pack: its counts and picture may have changed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshQuietly() }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().hazeSource(overlay)) {
            LinksBackdrop(Modifier.fillMaxSize().hazeSource(backdrop))
            Column(Modifier.fillMaxSize()) {
                val actions: @Composable RowScope.() -> Unit = {
                    if (signedIn && canUndo) {
                        IconButton(onClick = {
                            viewModel.undo { ok -> if (ok) Toast.makeText(context, undoneText, Toast.LENGTH_SHORT).show() }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = stringResource(R.string.theme_store_undo))
                        }
                    }
                    if (signedIn) {
                        IconButton(onClick = { searchOpen = !searchOpen }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.theme_store_search))
                        }
                    }
                }
                if (embedded) {
                    // Under the settings search bar, which floats over the pager.
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = tf.monochrome.android.ui.settings.LocalSettingsSearchInset.current, end = 4.dp),
                        content = actions,
                    )
                } else {
                    TopAppBar(
                        title = { Text(stringResource(R.string.theme_store_title)) },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStackSafe() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                            }
                        },
                        actions = actions,
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    )
                }
                if (!signedIn) {
                    SignedOutPanel(backdrop) { navController.navigateTool(Screen.Profile) }
                } else {
                    SearchOverlay(
                        open = searchOpen,
                        query = query,
                        onQueryChange = viewModel::setQuery,
                        placeholder = stringResource(R.string.theme_store_search_hint),
                        onClose = {
                            searchOpen = false
                            viewModel.setQuery("")
                        },
                    ) { topInset ->
                        StoreList(
                            state = state,
                            sort = sort,
                            backdrop = backdrop,
                            topInset = topInset,
                            bottomInset = 32.dp + LocalBottomChromeInset.current + navBar,
                            onSort = viewModel::setSort,
                            onPublish = { navController.navigateSafe(Screen.ThemePublish.route) },
                            onCode = { codeOpen = true },
                            onOpen = { navController.navigateSafe(Screen.ThemePackDetail.createRoute(it.id)) },
                            onRetry = viewModel::reload,
                            onMore = viewModel::loadMore,
                            picture = viewModel::picture,
                        )
                    }
                }
            }
        }

        if (codeOpen) {
            CodeSheet(
                hazeState = overlay,
                onDismiss = { codeOpen = false },
                find = viewModel::findCode,
                onFound = { pack ->
                    codeOpen = false
                    navController.navigateSafe(Screen.ThemePackDetail.createRoute(pack.id))
                },
            )
        }
    }
}

@Composable
private fun SignedOutPanel(backdrop: HazeState, onSignIn: () -> Unit) {
    GlassPanel(
        hazeState = backdrop,
        glass = LocalMiniPlayerGlass.current,
        avoidNavigationBar = false,
        blockTouchesBelow = false,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Default.AccountCircle, contentDescription = null, modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.theme_store_sign_in_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.theme_store_sign_in_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = LocalContentColor.current.copy(alpha = 0.85f),
            )
            GlassPill(
                onClick = onSignIn,
                hazeState = backdrop,
                height = 52.dp,
                glass = LocalMiniPlayerGlass.current.copy(tintColor = MaterialTheme.colorScheme.primary.toArgb()),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Text(stringResource(R.string.theme_store_sign_in), fontWeight = FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun StoreList(
    state: StoreListState,
    sort: StoreSort,
    backdrop: HazeState,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInset: androidx.compose.ui.unit.Dp,
    onSort: (StoreSort) -> Unit,
    onPublish: () -> Unit,
    onCode: () -> Unit,
    onOpen: (StorePack) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    picture: suspend (StorePack) -> Bitmap?,
) {
    val listState = rememberLazyGridState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, state.items.size) { if (nearEnd && state.items.isNotEmpty()) onMore() }
    val accent = MaterialTheme.colorScheme.primary

    CompositionLocalProvider(LocalGlassBackdrop provides backdrop) {
        // Two phone-shaped pictures to a row: the picture is what a theme is
        // chosen by, so it gets the room, and the details sit under it.
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = topInset, end = 12.dp, bottom = bottomInset),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "actions", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPill(
                            onClick = onPublish,
                            hazeState = backdrop,
                            height = 52.dp,
                            glass = LocalMiniPlayerGlass.current.copy(tintColor = accent.toArgb()),
                            modifier = Modifier.weight(1.3f),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.theme_store_share_my_look), fontWeight = FontWeight.ExtraBold, maxLines = 1)
                            }
                        }
                        GlassPill(onClick = onCode, hazeState = backdrop, height = 52.dp, modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.theme_store_have_code), fontWeight = FontWeight.Bold, maxLines = 1)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StoreSort.entries.forEach { s ->
                            GlassChoiceChip(
                                label = stringResource(
                                    when (s) {
                                        StoreSort.POPULAR -> R.string.theme_store_popular
                                        StoreSort.NEW -> R.string.theme_store_new
                                        StoreSort.MINE -> R.string.theme_store_mine
                                    },
                                ),
                                selected = sort == s,
                                accent = accent,
                                height = 40.dp,
                                onClick = { onSort(s) },
                            )
                        }
                    }
                }
            }
            items(state.items, key = { it.id }) { pack ->
                StoreTile(pack = pack, backdrop = backdrop, picture = picture, onClick = { onOpen(pack) })
            }
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                    when {
                        state.loading -> CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        state.error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                errorText(state.error),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.error !is StoreError.NotSetUp) {
                                GlassPill(onClick = onRetry, hazeState = backdrop, height = 44.dp, modifier = Modifier.padding(top = 10.dp)) {
                                    Text(
                                        stringResource(R.string.theme_store_retry),
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 20.dp),
                                    )
                                }
                            }
                        }
                        state.items.isEmpty() -> Text(
                            stringResource(
                                when (sort) {
                                    StoreSort.MINE -> R.string.theme_store_empty_mine
                                    else -> R.string.theme_store_empty
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun errorText(error: StoreError): String = stringResource(
    when (error) {
        is StoreError.NotSetUp -> R.string.theme_store_not_set_up
        is StoreError.SignedOut -> R.string.theme_store_sign_in_title
        is StoreError.LimitReached -> R.string.theme_store_limit
        is StoreError.BadPack -> R.string.theme_store_bad_pack
        is StoreError.Gone -> R.string.theme_store_gone
        is StoreError.Network -> R.string.theme_store_offline
    },
)

/**
 * One theme in the store: its picture, then its name, its author and how it
 * is doing, on one glass card that is the button.
 */
@Composable
private fun StoreTile(
    pack: StorePack,
    backdrop: HazeState,
    picture: suspend (StorePack) -> Bitmap?,
    onClick: () -> Unit,
) {
    PressableGlass(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        corner = 24.dp,
        hazeState = backdrop,
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(Modifier.padding(8.dp)) {
            StorePicture(
                pack,
                picture,
                Modifier.fillMaxWidth().aspectRatio(SNAPSHOT_CARD_ASPECT).clip(RoundedCornerShape(16.dp)),
            )
            Column(
                Modifier.padding(start = 4.dp, top = 8.dp, end = 4.dp, bottom = 2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    pack.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.theme_store_by, pack.authorName.ifBlank { stringResource(R.string.theme_store_someone) }),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(" ${pack.likes}", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                    Text(" ${pack.downloads}", style = MaterialTheme.typography.labelMedium)
                    if (!pack.listed) {
                        Spacer(Modifier.width(10.dp))
                        Icon(
                            Icons.Default.VisibilityOff,
                            contentDescription = stringResource(R.string.theme_store_unlisted),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** "Have a code?": type the code someone shared, open their pack. */
@Composable
private fun CodeSheet(
    hazeState: HazeState,
    onDismiss: () -> Unit,
    find: suspend (String) -> Result<StorePack?>,
    onFound: (StorePack) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val notFound = stringResource(R.string.theme_store_code_not_found)
    val offline = stringResource(R.string.theme_store_offline)
    val submit: () -> Unit = {
        if (!busy && code.isNotBlank()) {
            busy = true
            message = null
            scope.launch {
                find(code)
                    .onSuccess { pack -> if (pack != null) onFound(pack) else message = notFound }
                    .onFailure { message = offline }
                busy = false
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        MusicLinkScrim(onDismiss)
        CompositionLocalProvider(LocalGlassBackdrop provides hazeState) {
            GlassPanel(
                hazeState = hazeState,
                glass = LocalMiniPlayerGlass.current,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = LocalBottomChromeNow.current),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.theme_store_code_title), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.theme_store_code_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalContentColor.current.copy(alpha = 0.85f),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.take(16) },
                        singleLine = true,
                        placeholder = { Text("A1B2C3D4") },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { submit() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassPill(onClick = onDismiss, hazeState = hazeState, height = 50.dp, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.Bold)
                        }
                        GlassPill(
                            onClick = submit,
                            hazeState = hazeState,
                            height = 50.dp,
                            enabled = !busy && code.isNotBlank(),
                            glass = LocalMiniPlayerGlass.current.copy(tintColor = MaterialTheme.colorScheme.primary.toArgb()),
                            modifier = Modifier.weight(1.3f),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.theme_store_code_open), fontWeight = FontWeight.ExtraBold)
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}
