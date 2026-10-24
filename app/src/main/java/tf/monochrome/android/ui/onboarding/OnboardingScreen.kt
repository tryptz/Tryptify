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
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
import tf.monochrome.android.R
import tf.monochrome.android.ui.components.bounceClick
import tf.monochrome.android.ui.components.materialSurface
import tf.monochrome.android.ui.theme.MonoDimens
import tf.monochrome.android.ui.theme.reduceMotion

/**
 * First-run setup. Lives outside the nav graph (MainActivity swaps between this
 * and MonochromeNavHost on the `onboarding_complete` flag).
 *
 * The app's own look, as the first setup had it: progress dots, a heading
 * and a line under it, the step's rows as Material 3 tiles like every list
 * row in the app, and one pill button pinned at the bottom. Every step is
 * optional; "Skip setup" leaves at any point.
 *
 * @param onFinished invoked when the listener leaves; the argument is the
 *   route the main app opens first ("library", the Connections tab) or null
 *   for the default.
 */
@Composable
fun OnboardingScreen(
    onFinished: (postRoute: String?) -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()

    BackHandler(enabled = step != OnboardingStep.entries.first()) { viewModel.back() }
    // Back from the browser without a sign-in: the account rows stop waiting.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.cancelPendingSignIn() }

    val finish: (String?) -> Unit = { postRoute ->
        // Record the landing route before the flag flip recomposes the tree.
        onFinished(postRoute)
        viewModel.completeOnboarding()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MonoDimens.spacingSm, end = MonoDimens.spacingSm)
                .height(48.dp),
        ) {
            StepProgressDots(
                current = step.ordinal,
                total = OnboardingStep.entries.size,
                modifier = Modifier.align(Alignment.Center),
            )
            if (step != OnboardingStep.READY) {
                TextButton(onClick = { finish(null) }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text(stringResource(R.string.skip_setup), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        val still = reduceMotion()
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                if (still) {
                    EnterTransition.None togetherWith ExitTransition.None
                } else {
                    val forward = targetState.ordinal >= initialState.ordinal
                    val slideSpec = spring<IntOffset>(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    )
                    if (forward) {
                        (slideInHorizontally(slideSpec) { it } + fadeIn()) togetherWith
                            (slideOutHorizontally(slideSpec) { -it / 3 } + fadeOut())
                    } else {
                        (slideInHorizontally(slideSpec) { -it } + fadeIn()) togetherWith
                            (slideOutHorizontally(slideSpec) { it / 3 } + fadeOut())
                    }
                }
            },
            label = "onboardingStep",
            modifier = Modifier.fillMaxSize(),
        ) { shown ->
            val last = shown == OnboardingStep.READY
            OnboardingStepScaffold(
                title = stringResource(shown.title),
                subtitle = stringResource(shown.subtitle),
                hint = stringResource(shown.hint(viewModel)),
                primaryLabel = stringResource(if (last) R.string.start_listening else R.string.action_continue),
                onPrimary = { if (last) finish(viewModel.landingRoute()) else viewModel.next() },
                onBack = if (shown.ordinal > 0) viewModel::back else null,
            ) {
                when (shown) {
                    OnboardingStep.PERMISSIONS -> PermissionRows(viewModel)
                    OnboardingStep.FOLDERS -> FolderRows(viewModel)
                    OnboardingStep.SOURCES -> SourceRows(viewModel)
                    OnboardingStep.ACCOUNT -> AccountRows(viewModel)
                    OnboardingStep.FEATURES -> FeatureRows()
                    OnboardingStep.READY -> ReadyRows(viewModel)
                }
            }
        }
    }
}

@Composable
private fun StepProgressDots(current: Int, total: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total) { index ->
            val active = index == current
            val width by animateDpAsState(
                targetValue = if (active) 20.dp else 6.dp,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                label = "dotWidth",
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(width)
                    .height(6.dp)
                    .background(
                        color = if (index <= current) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                        shape = MonoDimens.shapePill,
                    ),
            )
        }
    }
}

/**
 * Shared layout for a step: a large heading, a line under it, the rows
 * scrolling, a hint saying what skipping means, and the pinned buttons.
 */
@Composable
private fun OnboardingStepScaffold(
    title: String,
    subtitle: String,
    hint: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onBack: (() -> Unit)?,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MonoDimens.spacingXl),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = MonoDimens.spacingLg),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MonoDimens.spacingSm),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MonoDimens.spacingXl),
                content = content,
            )
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = MonoDimens.spacingSm, bottom = MonoDimens.spacingLg),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MonoDimens.spacingLg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(
                onClick = onPrimary,
                shape = MonoDimens.shapePill,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                Text(primaryLabel, style = MaterialTheme.typography.titleMedium)
            }
            if (onBack != null) {
                TextButton(onClick = onBack, modifier = Modifier.padding(top = MonoDimens.spacingXs)) {
                    Text(stringResource(R.string.action_back), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Keeps the button where it is on every step.
                Spacer(Modifier.height(48.dp + MonoDimens.spacingXs))
            }
        }
    }
}

/**
 * One row of a step, a Material 3 tile like every list row in the app: an
 * icon, a title and a line under it, and what it does on the right.
 */
@Composable
private fun SetupRow(
    icon: ImageVector,
    title: String,
    detail: String?,
    onClick: (() -> Unit)? = null,
    detailIsError: Boolean = false,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MonoDimens.spacingSm)
            .materialSurface()
            .then(if (onClick != null) Modifier.bounceClick(onClick = onClick) else Modifier)
            .padding(horizontal = MonoDimens.spacingLg, vertical = MonoDimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(MonoDimens.iconSm))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = MonoDimens.spacingMd),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (detailIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

/** A row's action, or a tick with what was done once it is. */
@Composable
private fun RowAction(label: String, done: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    if (done) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    } else {
        FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            shape = MonoDimens.shapePill,
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.height(36.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

// ── Steps ──────────────────────────────────────────────────────────

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun PermissionRows(viewModel: OnboardingViewModel) {
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
    PermissionRow(
        icon = Icons.Default.LibraryMusic,
        title = stringResource(R.string.onb_perm_music),
        detail = stringResource(R.string.onb_perm_music_desc),
        state = media.permissions.firstOrNull {
            it.permission == Manifest.permission.READ_MEDIA_AUDIO || it.permission == Manifest.permission.READ_EXTERNAL_STORAGE
        },
        asked = askedMedia,
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
            icon = Icons.Default.Notifications,
            title = stringResource(R.string.onb_perm_notif),
            detail = stringResource(R.string.onb_perm_notif_desc),
            state = notifications,
            asked = askedNotifications,
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
    icon: ImageVector,
    title: String,
    detail: String,
    state: PermissionState?,
    asked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val granted = state?.status?.isGranted == true
    // Accompanist reports !shouldShowRationale both before the first request
    // and after a permanent denial; whether we asked tells them apart.
    val denied = asked && !granted && state?.status?.shouldShowRationale == false
    val action = if (denied) onOpenSettings else onRequest
    SetupRow(icon = icon, title = title, detail = detail, onClick = if (granted) null else action) {
        RowAction(
            label = stringResource(
                when {
                    granted -> R.string.onb_tag_allowed
                    denied -> R.string.onb_tag_settings
                    else -> R.string.onb_tag_allow
                },
            ),
            done = granted,
            onClick = action,
        )
    }
}

@Composable
private fun FolderRows(viewModel: OnboardingViewModel) {
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
            icon = Icons.Default.Folder,
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
            detailIsError = count == 0,
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
        icon = Icons.Default.CreateNewFolder,
        title = stringResource(if (folders.isEmpty()) R.string.add_music_folder else R.string.add_another_folder),
        detail = stringResource(R.string.onb_folder_add_desc),
        onClick = { picker.launch(null) },
    ) {
        RowAction(stringResource(R.string.onb_tag_add), done = false) { picker.launch(null) }
    }
    if (packs.isNotEmpty()) {
        val added = viewModel.packsAdded(folders, packs)
        SetupRow(
            icon = Icons.Default.SportsEsports,
            title = stringResource(R.string.settings_add_stepmania_packs),
            detail = stringResource(R.string.onb_packs_desc),
            onClick = if (added) null else viewModel::addPackFolders,
        ) {
            RowAction(
                label = stringResource(if (added) R.string.onb_tag_added else R.string.onb_tag_add),
                done = added,
                onClick = viewModel::addPackFolders,
            )
        }
    }
    folderError?.let {
        Text(
            text = it.resolve(context),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = MonoDimens.spacingSm),
        )
    }
}

@Composable
private fun SourceRows(viewModel: OnboardingViewModel) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    SetupRow(
        icon = Icons.Default.PhoneAndroid,
        title = stringResource(R.string.onb_src_device),
        detail = stringResource(R.string.onb_src_device_desc),
    ) {
        RowAction(stringResource(R.string.onb_tag_on), done = true) {}
    }
    OnboardingSource.entries.forEach { source ->
        val on = source in sources
        val toggle = { viewModel.toggleSource(source) }
        SetupRow(icon = source.icon, title = source.title(), detail = stringResource(source.detail), onClick = toggle) {
            Switch(checked = on, onCheckedChange = { toggle() })
        }
    }
}

@Composable
private fun AccountRows(viewModel: OnboardingViewModel) {
    val context = LocalContext.current
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val signingIn by viewModel.isSigningIn.collectAsStateWithLifecycle()
    val signedIn = profile
    if (signedIn != null) {
        SetupRow(
            icon = Icons.Default.AccountCircle,
            title = stringResource(R.string.onb_tag_signed_in),
            detail = stringResource(R.string.onb_signed_in_as, signedIn.username ?: signedIn.email ?: ""),
        ) {
            RowAction(stringResource(R.string.onb_tag_signed_in), done = true) {}
        }
        return
    }
    val google = { viewModel.signInWithGoogle(context) }
    val discord = { viewModel.signInWithDiscord(context) }
    SetupRow(
        icon = Icons.Default.Login,
        title = "Google",
        detail = stringResource(R.string.onb_sign_in_google),
        onClick = if (signingIn) null else google,
    ) {
        RowAction(stringResource(R.string.sign_in), done = false, enabled = !signingIn, onClick = google)
    }
    SetupRow(
        icon = Icons.Default.Login,
        title = "Discord",
        detail = stringResource(R.string.onb_sign_in_discord),
        onClick = if (signingIn) null else discord,
    ) {
        RowAction(stringResource(R.string.sign_in), done = false, enabled = !signingIn, onClick = discord)
    }
}

@Composable
private fun FeatureRows() {
    SetupRow(Icons.Default.Equalizer, "AutoEQ", stringResource(R.string.tour_sound_text))
    SetupRow(Icons.Default.SportsEsports, stringResource(R.string.trypt_mania), stringResource(R.string.onb_feat_mania_desc))
    SetupRow(Icons.Default.Cast, stringResource(R.string.tryptify_connect), stringResource(R.string.onb_feat_connect_desc))
    SetupRow(Icons.Default.Groups, stringResource(R.string.listening_party), stringResource(R.string.onb_feat_party_desc))
    SetupRow(Icons.Default.Waves, stringResource(R.string.visualizer), stringResource(R.string.onb_feat_visualizer_desc))
    SetupRow(Icons.Default.HighQuality, stringResource(R.string.tour_hires_title), stringResource(R.string.tour_hires_text))
}

@Composable
private fun ReadyRows(viewModel: OnboardingViewModel) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val profile by viewModel.userProfile.collectAsStateWithLifecycle()
    val total = folders.mapNotNull { it.trackCount }.sum()

    SetupRow(
        icon = Icons.Default.Folder,
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
    )
    SetupRow(
        icon = Icons.Default.Dns,
        title = stringResource(R.string.onb_step_sources),
        detail = if (sources.isEmpty()) {
            stringResource(R.string.onb_src_device)
        } else {
            stringResource(R.string.onb_ready_to_set_up, sources.map { it.title() }.joinToString(", "))
        },
    )
    val signedIn = profile
    SetupRow(
        icon = Icons.Default.AccountCircle,
        title = stringResource(R.string.onb_step_account),
        detail = if (signedIn != null) {
            stringResource(R.string.onb_signed_in_as, signedIn.username ?: signedIn.email ?: "")
        } else {
            stringResource(R.string.onb_ready_signed_out)
        },
    )
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

/** What skipping the step means, or where the last one goes. */
@Composable
private fun OnboardingStep.hint(viewModel: OnboardingViewModel): Int {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    return when (this) {
        OnboardingStep.PERMISSIONS -> R.string.onb_hint_permissions
        OnboardingStep.FOLDERS -> R.string.onb_hint_folders
        OnboardingStep.SOURCES -> R.string.onb_hint_sources
        OnboardingStep.ACCOUNT -> R.string.onb_hint_account
        OnboardingStep.FEATURES -> R.string.onb_hint_features
        OnboardingStep.READY ->
            if (sources.isNotEmpty()) R.string.onb_hint_ready_connections else R.string.onb_hint_ready_library
    }
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

private val OnboardingSource.icon: ImageVector
    get() = when (this) {
        OnboardingSource.SERVER -> Icons.Default.Storage
        OnboardingSource.CLOUD -> Icons.Default.Cloud
        OnboardingSource.HIFI -> Icons.Default.GraphicEq
    }
