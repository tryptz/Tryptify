package tf.monochrome.android.ui.onboarding

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import tf.monochrome.android.R
import tf.monochrome.android.domain.model.PlayerGlassSettings
import tf.monochrome.android.ui.components.GlassInkScope
import tf.monochrome.android.ui.components.GlassMaterial
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.LocalGlassGround
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.glassBase
import tf.monochrome.android.ui.mixer.GlassChoiceChip
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * First-run setup. Lives outside the nav graph (MainActivity swaps between this
 * and MonochromeNavHost on the `onboarding_complete` flag).
 *
 * The now-playing screen as a setup: a cover that changes with each step over
 * its own colours, the step in a glass sheet with the player's buttons, and a
 * floating glass tab bar of the steps that jumps to any of them. Every step is
 * optional.
 *
 * @param onFinished invoked when the listener leaves; the argument is the
 *   route the main app opens first ("library", the Connections tab) or null
 *   for the default.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun OnboardingScreen(
    onFinished: (postRoute: String?) -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val glass by viewModel.glass.collectAsStateWithLifecycle()

    BackHandler(enabled = step != OnboardingStep.entries.first()) { viewModel.back() }
    // Back from the browser without a sign-in: the account row stops waiting.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.cancelPendingSignIn() }

    val finish: (String?) -> Unit = { postRoute ->
        // Record the landing route before the flag flip recomposes the tree.
        onFinished(postRoute)
        viewModel.completeOnboarding()
    }

    // Setup is outside the nav host, so it feeds its own glass: its own
    // backdrop for the panes to frost, and the listener's UI panel settings.
    val haze = rememberHazeState()
    val palette = STEP_PALETTES[step.ordinal]
    val colourSpec = if (reduceMotion()) snap<Color>() else tween(600)
    val a by animateColorAsState(palette.first, colourSpec, label = "setupColourA")
    val b by animateColorAsState(palette.second, colourSpec, label = "setupColourB")
    val display = rememberDisplayFont()

    CompositionLocalProvider(
        LocalGlassGround provides SETUP_GROUND,
        LocalGlassBackdrop provides haze,
        LocalMiniPlayerGlass provides glass,
    ) {
        Box(Modifier.fillMaxSize().background(SETUP_GROUND)) {
            // The haze source, and a sibling of every pane below it: a pane
            // inside its own source paints the source's flat base.
            SetupBackdrop(a, b, Modifier.fillMaxSize().hazeSource(haze))

            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopRow(
                    step = step,
                    onSkipSetup = { finish(null) },
                )
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(0.3f),
                    contentAlignment = Alignment.Center,
                ) {
                    Cover(
                        word = stringResource(step.tabLabel),
                        a = a,
                        b = b,
                        font = display,
                        size = min(maxHeight * 0.86f, 200.dp),
                    )
                }
                GlassPane(
                    haze = haze,
                    glass = glass,
                    corner = 34.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.7f)
                        .padding(horizontal = 10.dp),
                ) {
                    SheetContent(
                        step = step,
                        viewModel = viewModel,
                        haze = haze,
                        font = display,
                        onFinish = { finish(viewModel.landingRoute()) },
                    )
                }
                Spacer(Modifier.height(10.dp))
                StepTabBar(
                    step = step,
                    haze = haze,
                    glass = glass,
                    onSelect = viewModel::goTo,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

// ── Layout pieces ──────────────────────────────────────────────────

@Composable
private fun TopRow(step: OnboardingStep, onSkipSetup: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 10.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "%02d / %02d".format(step.ordinal + 1, OnboardingStep.entries.size),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.82f),
            modifier = Modifier.weight(1f),
        )
        if (step != OnboardingStep.READY) {
            TextButton(onClick = onSkipSetup) {
                Text(stringResource(R.string.skip_setup), color = Color.White.copy(alpha = 0.9f))
            }
        } else {
            // Keeps the row's height when the button goes.
            Spacer(Modifier.height(48.dp))
        }
    }
}

/** The step's cover, in its two colours, with the step's name on it. */
@Composable
private fun Cover(word: String, a: Color, b: Color, font: FontFamily, size: Dp) {
    val shape = RoundedCornerShape(size * 0.14f)
    Box(
        modifier = Modifier
            .size(size)
            .shadow(24.dp, shape)
            .clip(shape)
            .background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.BottomStart,
    ) {
        Text(
            text = word,
            color = Color.White,
            fontFamily = font,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.13f).sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(size * 0.09f),
        )
    }
}

/**
 * What the glass frosts: a wall of covers in the step's colours under a dark
 * wash. It needs detail; over a smooth gradient the blur, refraction and
 * dispersion move nothing visible.
 */
@Composable
private fun SetupBackdrop(a: Color, b: Color, modifier: Modifier) {
    Canvas(modifier) {
        drawRect(SETUP_GROUND)
        drawCircle(a.copy(alpha = 0.55f), radius = size.minDimension * 0.9f, center = Offset(size.width * 0.15f, size.height * 0.12f))
        drawCircle(b.copy(alpha = 0.5f), radius = size.minDimension * 0.85f, center = Offset(size.width * 0.95f, size.height * 0.55f))
        val tile = size.width / 3.2f
        val gap = tile * 0.08f
        val radius = CornerRadius(tile * 0.14f)
        rotate(-8f) {
            var row = 0
            var y = -tile
            while (y < size.height + tile) {
                var col = 0
                var x = -tile
                while (x < size.width + tile) {
                    val k = (row * 5 + col * 3) % TILE_ACCENTS.size
                    val c1 = when (k % 3) { 0 -> a; 1 -> b; else -> TILE_ACCENTS[k] }
                    val c2 = TILE_ACCENTS[(k + 2) % TILE_ACCENTS.size]
                    drawRoundRect(
                        brush = Brush.linearGradient(listOf(c1, c2), Offset(x, y), Offset(x + tile, y + tile)),
                        topLeft = Offset(x, y),
                        size = Size(tile - gap, tile - gap),
                        cornerRadius = radius,
                        alpha = 0.85f,
                    )
                    x += tile
                    col++
                }
                y += tile
                row++
            }
        }
        drawRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.72f))))
    }
}

/** A pane of the app's glass: the shared material, then content in the glass's ink. */
@Composable
private fun GlassPane(
    haze: HazeState,
    glass: PlayerGlassSettings,
    corner: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.clip(shape).glassBase(glass, shape)) {
        GlassMaterial(hazeState = haze, glass = glass, corner = corner)
        GlassInkScope(glass) { Box(Modifier.fillMaxSize()) { content() } }
    }
}

// ── The sheet ──────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun SheetContent(
    step: OnboardingStep,
    viewModel: OnboardingViewModel,
    haze: HazeState,
    font: FontFamily,
    onFinish: () -> Unit,
) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val still = reduceMotion()
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 14.dp)) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (still) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState.ordinal >= initialState.ordinal
                    (fadeIn(tween(260)) + slideInHorizontally(tween(260)) { if (forward) it / 8 else -it / 8 }) togetherWith
                        fadeOut(tween(160))
                }
            },
            label = "setupStep",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { shown ->
            Column(Modifier.fillMaxSize()) {
                Text(
                    text = stringResource(shown.title),
                    fontFamily = font,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 34.sp,
                    lineHeight = 38.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(shown.subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                ) {
                    when (shown) {
                        OnboardingStep.PERMISSIONS -> PermissionRows(viewModel, haze)
                        OnboardingStep.FOLDERS -> FolderRows(viewModel, haze)
                        OnboardingStep.SOURCES -> SourceRows(viewModel, haze)
                        OnboardingStep.ACCOUNT -> AccountRows(viewModel, haze)
                        OnboardingStep.FEATURES -> FeatureRows()
                        OnboardingStep.READY -> ReadyRows(viewModel)
                    }
                }
            }
        }
        Transport(
            step = step,
            onBack = viewModel::back,
            onNext = { if (step == OnboardingStep.READY) onFinish() else viewModel.next() },
            onSkipToEnd = { viewModel.goTo(OnboardingStep.READY) },
        )
        Text(
            text = stringResource(
                when (step) {
                    OnboardingStep.PERMISSIONS -> R.string.onb_hint_permissions
                    OnboardingStep.FOLDERS -> R.string.onb_hint_folders
                    OnboardingStep.SOURCES -> R.string.onb_hint_sources
                    OnboardingStep.ACCOUNT -> R.string.onb_hint_account
                    OnboardingStep.FEATURES -> R.string.onb_hint_features
                    OnboardingStep.READY ->
                        if (sources.isNotEmpty()) R.string.onb_hint_ready_connections else R.string.onb_hint_ready_library
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/** Previous, next (play), and skip to the last step: the player's three buttons. */
@Composable
private fun Transport(
    step: OnboardingStep,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkipToEnd: () -> Unit,
) {
    val ink = MaterialTheme.colorScheme.onSurface
    val onInk = if (ink.luminance() > 0.5f) Color.Black else Color.White
    val last = step == OnboardingStep.READY
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(
            icon = Icons.Default.SkipPrevious,
            label = stringResource(R.string.onb_prev),
            enabled = step.ordinal > 0,
            size = 50.dp,
            background = ink.copy(alpha = 0.1f),
            tint = ink,
            onClick = onBack,
        )
        RoundButton(
            icon = if (last) Icons.Default.Check else Icons.Default.PlayArrow,
            label = stringResource(if (last) R.string.start_listening else R.string.onb_next),
            enabled = true,
            size = 72.dp,
            background = ink,
            tint = onInk,
            onClick = onNext,
        )
        RoundButton(
            icon = Icons.Default.SkipNext,
            label = stringResource(R.string.onb_skip_to_end),
            enabled = !last,
            size = 50.dp,
            background = ink.copy(alpha = 0.1f),
            tint = ink,
            onClick = onSkipToEnd,
            modifier = Modifier.alpha(if (last) 0f else 1f),
        )
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    size: Dp,
    background: Color,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .alpha(if (enabled) 1f else 0.4f)
            .then(if (enabled) Modifier.bounceClick(onClick = onClick) else Modifier)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.42f))
    }
}

/**
 * One row of a step: a title, a line under it, and what it does on the right.
 * No pane of its own; rows sit straight on the sheet's glass.
 */
@Composable
private fun SetupRow(
    title: String,
    detail: String?,
    leading: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    detailColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.bounceClick(onClick = onClick) else Modifier)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Icon(
                leading,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(end = 14.dp).size(22.dp),
            )
        }
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = detailColor)
            }
        }
        trailing()
    }
}

/** A row's action as a glass chip: on (done) or off (to do). */
@Composable
private fun Tag(label: String, on: Boolean, haze: HazeState, enabled: Boolean = true, onClick: () -> Unit) {
    GlassChoiceChip(
        label = label,
        selected = on,
        accent = MaterialTheme.colorScheme.primary,
        onClick = onClick,
        leadingIcon = if (on) Icons.Default.Check else null,
        enabled = enabled,
        hazeState = haze,
    )
}

// ── Steps ──────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionRows(viewModel: OnboardingViewModel, haze: HazeState) {
    val context = LocalContext.current
    val openAppSettings = {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
        )
    }

    // Same set as the Library tab (LocalLibraryTab): audio is what matters,
    // images and video ride along for sidecar covers and music videos.
    val media = rememberMultiplePermissionsState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        },
    )
    val askedMedia by viewModel.hasRequestedMediaPermission.collectAsStateWithLifecycle()
    val audio = media.permissions.firstOrNull {
        it.permission == Manifest.permission.READ_MEDIA_AUDIO || it.permission == Manifest.permission.READ_EXTERNAL_STORAGE
    }
    PermissionRow(
        title = stringResource(R.string.onb_perm_music),
        detail = stringResource(R.string.onb_perm_music_desc),
        state = audio,
        asked = askedMedia,
        haze = haze,
        onRequest = {
            viewModel.markMediaPermissionRequested()
            media.launchMultiplePermissionRequest()
        },
        onOpenSettings = openAppSettings,
    )

    // Asked here, the prompt MainActivity makes after setup finds it granted
    // and stays quiet.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifications = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
        val askedNotifications by viewModel.hasRequestedNotifications.collectAsStateWithLifecycle()
        PermissionRow(
            title = stringResource(R.string.onb_perm_notif),
            detail = stringResource(R.string.onb_perm_notif_desc),
            state = notifications,
            asked = askedNotifications,
            haze = haze,
            onRequest = {
                viewModel.markNotificationsRequested()
                notifications.launchPermissionRequest()
            },
            onOpenSettings = openAppSettings,
        )
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionRow(
    title: String,
    detail: String,
    state: PermissionState?,
    asked: Boolean,
    haze: HazeState,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val granted = state?.status?.isGranted == true
    // Accompanist reports !shouldShowRationale both before the first request
    // and after a permanent denial; whether we asked tells them apart.
    val denied = asked && !granted && state?.status?.shouldShowRationale == false
    val action = when {
        granted -> ({})
        denied -> onOpenSettings
        else -> onRequest
    }
    SetupRow(title = title, detail = detail, onClick = action) {
        Tag(
            label = stringResource(
                when {
                    granted -> R.string.onb_tag_allowed
                    denied -> R.string.onb_tag_settings
                    else -> R.string.onb_tag_allow
                },
            ),
            on = granted,
            haze = haze,
            onClick = action,
        )
    }
}

@Composable
private fun FolderRows(viewModel: OnboardingViewModel, haze: HazeState) {
    val context = LocalContext.current
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val folderError by viewModel.folderError.collectAsStateWithLifecycle()
    val packs by viewModel.packFolders.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            // Some providers refuse a persistable grant; the path is what the
            // scanner reads, so the folder is still added.
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            viewModel.addFolder(uri)
        }
    }

    folders.forEach { entry ->
        val count = entry.trackCount
        SetupRow(
            title = entry.displayName,
            detail = when (count) {
                null -> stringResource(R.string.counting_tracks)
                0 -> stringResource(R.string.folder_no_tracks)
                else -> pluralStringResource(
                    R.plurals.found_tracks_in_folder,
                    count,
                    java.text.NumberFormat.getIntegerInstance().format(count.toLong()),
                )
            },
            detailColor = if (count == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            leading = Icons.Default.Folder,
        ) {
            IconButton(onClick = { viewModel.removeFolder(entry.path) }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.remove_folder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    SetupRow(
        title = stringResource(if (folders.isEmpty()) R.string.add_music_folder else R.string.add_another_folder),
        detail = stringResource(R.string.onb_folder_add_desc),
        onClick = { picker.launch(null) },
    ) {
        Tag(stringResource(R.string.onb_tag_add), on = false, haze = haze) { picker.launch(null) }
    }
    if (packs.isNotEmpty()) {
        val added = viewModel.packsAdded(folders, packs)
        SetupRow(
            title = stringResource(R.string.settings_add_stepmania_packs),
            detail = stringResource(R.string.onb_packs_desc),
            onClick = if (added) null else viewModel::addPackFolders,
        ) {
            Tag(
                label = stringResource(if (added) R.string.onb_tag_added else R.string.onb_tag_add),
                on = added,
                haze = haze,
            ) { if (!added) viewModel.addPackFolders() }
        }
    }
    folderError?.let {
        Text(
            text = it.resolve(context),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SourceRows(viewModel: OnboardingViewModel, haze: HazeState) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    SetupRow(
        title = stringResource(R.string.onb_src_device),
        detail = stringResource(R.string.onb_src_device_desc),
    ) {
        Tag(stringResource(R.string.onb_tag_on), on = true, haze = haze) {}
    }
    OnboardingSource.entries.forEach { source ->
        val on = source in sources
        val toggle = { viewModel.toggleSource(source) }
        SetupRow(title = source.title(), detail = stringResource(source.detail), onClick = toggle) {
            Tag(stringResource(if (on) R.string.onb_tag_selected else R.string.onb_tag_select), on = on, haze = haze, onClick = toggle)
        }
    }
}

@Composable
private fun AccountRows(viewModel: OnboardingViewModel, haze: HazeState) {
    val context = LocalContext.current
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val signingIn by viewModel.isSigningIn.collectAsStateWithLifecycle()
    val signedIn = profile
    if (signedIn != null) {
        SetupRow(
            title = stringResource(R.string.onb_tag_signed_in),
            detail = stringResource(
                R.string.onb_signed_in_as,
                signedIn.username ?: signedIn.email ?: "",
            ),
            leading = Icons.Default.CheckCircle,
        )
        return
    }
    val google = { viewModel.signInWithGoogle(context) }
    val discord = { viewModel.signInWithDiscord(context) }
    SetupRow(
        title = "Google",
        detail = stringResource(R.string.onb_sign_in_google),
        onClick = if (signingIn) null else google,
    ) {
        Tag(stringResource(R.string.sign_in), on = false, haze = haze, enabled = !signingIn, onClick = google)
    }
    SetupRow(
        title = "Discord",
        detail = stringResource(R.string.onb_sign_in_discord),
        onClick = if (signingIn) null else discord,
    ) {
        Tag(stringResource(R.string.sign_in), on = false, haze = haze, enabled = !signingIn, onClick = discord)
    }
}

@Composable
private fun FeatureRows() {
    SetupRow("AutoEQ", stringResource(R.string.tour_sound_text))
    SetupRow(stringResource(R.string.trypt_mania), stringResource(R.string.onb_feat_mania_desc))
    SetupRow(stringResource(R.string.tryptify_connect), stringResource(R.string.onb_feat_connect_desc))
    SetupRow(stringResource(R.string.listening_party), stringResource(R.string.onb_feat_party_desc))
    SetupRow(stringResource(R.string.visualizer), stringResource(R.string.onb_feat_visualizer_desc))
    SetupRow(stringResource(R.string.tour_hires_title), stringResource(R.string.tour_hires_text))
}

@Composable
private fun ReadyRows(viewModel: OnboardingViewModel) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val total = folders.mapNotNull { it.trackCount }.sum()

    SetupRow(
        title = stringResource(R.string.settings_music_folders),
        detail = when {
            folders.isEmpty() -> stringResource(R.string.onb_ready_whole_device)
            total > 0 -> stringResource(
                R.string.separator_dot,
                pluralStringResource(R.plurals.folders_count, folders.size, folders.size),
                pluralStringResource(R.plurals.tracks_found, total, java.text.NumberFormat.getIntegerInstance().format(total.toLong())),
            )
            else -> pluralStringResource(R.plurals.folders_picked, folders.size, folders.size)
        },
        leading = Icons.Default.Folder,
    )
    SetupRow(
        title = stringResource(R.string.onb_step_sources),
        detail = if (sources.isEmpty()) {
            stringResource(R.string.onb_src_device)
        } else {
            stringResource(R.string.onb_ready_to_set_up, sources.map { it.title() }.joinToString(", "))
        },
        leading = Icons.Default.Dns,
    )
    val signedIn = profile
    SetupRow(
        title = stringResource(R.string.onb_step_account),
        detail = if (signedIn != null) {
            stringResource(R.string.onb_signed_in_as, signedIn.username ?: signedIn.email ?: "")
        } else {
            stringResource(R.string.onb_ready_signed_out)
        },
        leading = Icons.Default.AccountCircle,
    )
}

// ── The tab bar ────────────────────────────────────────────────────

/**
 * The steps as a floating glass tab bar: the current one lit with its name,
 * the others as icons that jump straight to them. Steps already passed are at
 * full strength, the ones ahead dimmed.
 */
@Composable
private fun StepTabBar(
    step: OnboardingStep,
    haze: HazeState,
    glass: PlayerGlassSettings,
    onSelect: (OnboardingStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPane(haze = haze, glass = glass, corner = 32.dp, modifier = modifier.fillMaxWidth().height(64.dp)) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val ink = MaterialTheme.colorScheme.onSurface
            OnboardingStep.entries.forEach { item ->
                val label = stringResource(item.tabLabel)
                if (item == step) {
                    Row(
                        modifier = Modifier
                            .height(50.dp)
                            .clip(RoundedCornerShape(25.dp))
                            .background(ink.copy(alpha = 0.16f))
                            .semantics { contentDescription = label }
                            .padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(item.icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 50.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClickLabel = label) { onSelect(item) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            item.icon,
                            contentDescription = null,
                            tint = ink.copy(alpha = if (item.ordinal < step.ordinal) 1f else 0.5f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

// ── Step data ──────────────────────────────────────────────────────

private val OnboardingStep.title: Int
    get() = when (this) {
        OnboardingStep.PERMISSIONS -> R.string.onb_step_permissions
        OnboardingStep.FOLDERS -> R.string.settings_music_folders
        OnboardingStep.SOURCES -> R.string.onb_step_sources
        OnboardingStep.ACCOUNT -> R.string.onb_step_account
        OnboardingStep.FEATURES -> R.string.onb_step_features
        OnboardingStep.READY -> R.string.onb_step_ready
    }

private val OnboardingStep.subtitle: Int
    get() = when (this) {
        OnboardingStep.PERMISSIONS -> R.string.onb_permissions_sub
        OnboardingStep.FOLDERS -> R.string.onb_folders_sub
        OnboardingStep.SOURCES -> R.string.onb_sources_sub
        OnboardingStep.ACCOUNT -> R.string.onb_account_sub
        OnboardingStep.FEATURES -> R.string.onb_features_sub
        OnboardingStep.READY -> R.string.onb_ready_sub
    }

/** The short name on the tab bar and the cover. */
private val OnboardingStep.tabLabel: Int
    get() = when (this) {
        OnboardingStep.PERMISSIONS -> R.string.onb_tab_access
        OnboardingStep.FOLDERS -> R.string.onb_tab_folders
        OnboardingStep.SOURCES -> R.string.onb_step_sources
        OnboardingStep.ACCOUNT -> R.string.onb_step_account
        OnboardingStep.FEATURES -> R.string.onb_step_features
        OnboardingStep.READY -> R.string.onb_step_ready
    }

private val OnboardingStep.icon: ImageVector
    get() = when (this) {
        OnboardingStep.PERMISSIONS -> Icons.Default.Shield
        OnboardingStep.FOLDERS -> Icons.Default.Folder
        OnboardingStep.SOURCES -> Icons.Default.Dns
        OnboardingStep.ACCOUNT -> Icons.Default.AccountCircle
        OnboardingStep.FEATURES -> Icons.Default.Apps
        OnboardingStep.READY -> Icons.Default.CheckCircle
    }

@Composable
private fun OnboardingSource.title(): String = when (this) {
    OnboardingSource.SERVER -> stringResource(R.string.onb_src_server)
    OnboardingSource.CLOUD -> stringResource(R.string.cloud_title)
    OnboardingSource.HIFI -> "TrypT HiFi"
}

private val OnboardingSource.detail: Int
    get() = when (this) {
        OnboardingSource.SERVER -> R.string.onb_src_server_desc
        OnboardingSource.CLOUD -> R.string.onb_src_cloud_desc
        OnboardingSource.HIFI -> R.string.onb_src_hifi_desc
    }

/** Outfit, the website's display face, bundled for the font picker. */
@Composable
private fun rememberDisplayFont(): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets) {
        runCatching {
            FontFamily(
                Font("fonts/outfit.ttf", assets, FontWeight.SemiBold),
                Font("fonts/outfit.ttf", assets, FontWeight.Bold),
            )
        }.getOrDefault(FontFamily.Default)
    }
}

/** What setup lies over: dark under every theme, like the player. */
private val SETUP_GROUND = Color(0xFF07070A)

/** Each step's cover colours, in step order. */
private val STEP_PALETTES = listOf(
    Color(0xFF0F9B8E) to Color(0xFF1D2B64),
    Color(0xFFF7971E) to Color(0xFFEE0979),
    Color(0xFF5C6BC0) to Color(0xFF00C6FF),
    Color(0xFF5865F2) to Color(0xFFFF6A00),
    Color(0xFFFF5E62) to Color(0xFF8E2DE2),
    Color(0xFFE6E6E6) to Color(0xFF7A7A7A),
)

/** The other colours on the wall of covers. */
private val TILE_ACCENTS = listOf(
    Color(0xFFFF5E62), Color(0xFF1D2B64), Color(0xFF0F9B8E), Color(0xFFFFD200),
    Color(0xFF8E2DE2), Color(0xFF004E92), Color(0xFFEE0979), Color(0xFF3A3A3A),
)
