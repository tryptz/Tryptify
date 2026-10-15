package tf.monochrome.android.ui.profile

import tf.monochrome.android.ui.navigation.popBackStackSafe
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import tf.monochrome.android.data.auth.AccountAvatar
import tf.monochrome.android.data.auth.AvatarSource
import tf.monochrome.android.data.auth.USERNAME_MAX
import tf.monochrome.android.data.auth.UserProfile
import tf.monochrome.android.ui.components.GlassPill
import tf.monochrome.android.ui.components.LocalGlassBackdrop
import tf.monochrome.android.ui.components.PressableGlass
import androidx.hilt.navigation.compose.hiltViewModel
import tf.monochrome.android.ui.navigation.navigateTool
import tf.monochrome.android.ui.navigation.Screen
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import androidx.compose.ui.res.stringResource
import tf.monochrome.android.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    navController: NavController,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val userProfile by viewModel.userProfile.collectAsStateWithLifecycle()
    val avatar by viewModel.avatar.collectAsStateWithLifecycle()
    val avatarBusy by viewModel.avatarBusy.collectAsStateWithLifecycle()
    val avatarError by viewModel.avatarError.collectAsStateWithLifecycle()
    val nameSaving by viewModel.nameSaving.collectAsStateWithLifecycle()
    val nameError by viewModel.nameError.collectAsStateWithLifecycle()
    val isSigningIn by viewModel.isSigningIn.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val successMessage by viewModel.successMessage.collectAsStateWithLifecycle()
    val isSyncing by viewModel.isSyncing.collectAsStateWithLifecycle()
    val syncStatus by viewModel.syncStatus.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Sign-in form state hoisted here (survives the isSigningIn spinner swap)
    // so a failed attempt doesn't wipe what the user typed.
    var authEmail by rememberSaveable { mutableStateOf("") }
    var authPassword by rememberSaveable { mutableStateOf("") }
    var authIsSignUp by rememberSaveable { mutableStateOf(false) }

    // The system photo picker: no storage permission, and the grant it hands
    // back is read at once and copied, so it does not need to outlive the app.
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.pickAvatar(uri)
    }

    var showSignOutDialog by remember { mutableStateOf(false) }
    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.settings_sign_out)) },
            text = { Text(stringResource(R.string.settings_you_ll_stop_syncing_favorites_and_playlists)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    viewModel.signOut()
                }) { Text(stringResource(R.string.sign_out), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) { Text(stringResource(R.string.action_cancel)) }
            }
        )
    }

    // Refresh user when screen resumes (handles OAuth callback from browser)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshUser()
                // Clear a stuck "Signing in…" spinner if the user returned from
                // the OAuth Custom Tab without completing it.
                viewModel.onScreenResumed()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.account)) },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStackSafe() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        if (isSigningIn) {
            // Full-screen loading while OAuth completes
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 3.dp,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    stringResource(R.string.signing_in),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = LocalBottomChromeInset.current)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                // Center the form in the middle of the screen by default; it still
                // scrolls when the keyboard opens or content overflows.
                verticalArrangement = Arrangement.Center
            ) {
                val profile = userProfile
                if (profile != null) {
                    SignedInView(
                        profile = profile,
                        avatar = avatar,
                        avatarBusy = avatarBusy,
                        avatarError = avatarError?.resolve(context),
                        onPickPhoto = {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        onUseProviderPhoto = viewModel::useProviderPhoto,
                        onRemovePhoto = viewModel::removeAvatar,
                        nameSaving = nameSaving,
                        nameError = nameError?.resolve(context),
                        onSaveName = viewModel::saveUsername,
                        onNameEdited = viewModel::clearNameError,
                        isSyncing = isSyncing,
                        syncStatus = syncStatus?.resolve(context),
                        onSync = { viewModel.syncNow() },
                        onOpenStats = { navController.navigateTool(Screen.Stats) },
                        onSignOut = { showSignOutDialog = true }
                    )
                } else {
                    SignedOutView(
                        isLoading = false,
                        errorMessage = errorMessage,
                        successMessage = successMessage,
                        email = authEmail,
                        onEmailChange = { authEmail = it },
                        password = authPassword,
                        onPasswordChange = { authPassword = it },
                        isSignUp = authIsSignUp,
                        onIsSignUpChange = { authIsSignUp = it },
                        onSignInWithGoogle = {
                            viewModel.signInWithGoogle(context)
                        },
                        onSignInWithEmail = { email, password ->
                            viewModel.signInWithEmail(email, password)
                        },
                        onSignUpWithEmail = { email, password ->
                            viewModel.signUpWithEmail(email, password)
                        },
                        onClearError = { viewModel.clearError(); viewModel.clearSuccess() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignedInView(
    profile: UserProfile,
    avatar: AccountAvatar?,
    avatarBusy: Boolean,
    avatarError: String?,
    onPickPhoto: () -> Unit,
    onUseProviderPhoto: () -> Unit,
    onRemovePhoto: () -> Unit,
    nameSaving: Boolean,
    nameError: String?,
    onSaveName: (String, () -> Unit) -> Unit,
    onNameEdited: () -> Unit,
    isSyncing: Boolean,
    syncStatus: String?,
    onSync: () -> Unit,
    onOpenStats: () -> Unit,
    onSignOut: () -> Unit
) {
    var editingName by rememberSaveable { mutableStateOf(false) }
    val backdrop = LocalGlassBackdrop.current

    // DevEditable is a plain Box, so the modifier it is handed stretches the box
    // and nothing centres what is inside it — the header Column has to fill the
    // width itself or it parks against the left edge.
    tf.monochrome.android.devedit.DevEditable("profile_header", Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AvatarHero(avatar = avatar, busy = avatarBusy, onClick = onPickPhoto)

            Spacer(modifier = Modifier.height(14.dp))

            // What can be done with the picture, as pills: the disc itself
            // also picks a photo, but a tap on a picture is not an obvious way
            // to change it, and removing one needs a button of its own anyway.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PhotoPill(Icons.Default.PhotoLibrary, stringResource(R.string.profile_choose_photo), backdrop, onPickPhoto, enabled = !avatarBusy)
                if (profile.photoUrl != null && avatar?.source !is AvatarSource.Remote) {
                    PhotoPill(Icons.Default.PhotoCamera, stringResource(R.string.profile_use_google_photo), backdrop, onUseProviderPhoto, enabled = !avatarBusy)
                }
                if (avatar?.source != null) {
                    PhotoPill(Icons.Default.Delete, stringResource(R.string.profile_remove_photo), backdrop, onRemovePhoto, enabled = !avatarBusy)
                }
            }

            if (avatarError != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(avatarError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (editingName) {
                NameEditor(
                    initial = profile.username.orEmpty(),
                    saving = nameSaving,
                    error = nameError,
                    onEdited = onNameEdited,
                    onSave = { name -> onSaveName(name) { editingName = false } },
                    onCancel = {
                        onNameEdited()
                        editingName = false
                    },
                )
            } else if (profile.username == null) {
                // No username yet. Google's name is not put in its place: the
                // account is called what the listener calls it, or nothing.
                GlassPill(
                    onClick = { editingName = true },
                    hazeState = backdrop,
                    height = 44.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.profile_choose_username), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            } else {
                // The pencil's width again on the left, so the name stays centred.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.width(48.dp))
                    Text(
                        text = profile.username,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    IconButton(onClick = { editingName = true }) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = stringResource(R.string.profile_edit_username),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (profile.email != null) {
                Text(
                    text = profile.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(32.dp))

    GlassActionRow(
        icon = Icons.Default.Sync,
        title = stringResource(if (isSyncing) R.string.syncing else R.string.sync_now),
        subtitle = syncStatus ?: stringResource(R.string.profile_sync_subtitle),
        enabled = !isSyncing,
        onClick = onSync,
        trailing = if (isSyncing) {
            { CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp) }
        } else null,
    )

    Spacer(modifier = Modifier.height(12.dp))

    GlassActionRow(
        icon = Icons.Default.BarChart,
        title = stringResource(R.string.listening_stats),
        onClick = onOpenStats,
    )

    Spacer(modifier = Modifier.height(28.dp))

    GlassActionRow(
        icon = Icons.AutoMirrored.Filled.Logout,
        title = stringResource(R.string.sign_out),
        danger = true,
        onClick = onSignOut,
    )
}

/**
 * The account's picture, large, in a glass disc that picks a new one, with a
 * camera badge on its edge saying so.
 */
@Composable
private fun AvatarHero(avatar: AccountAvatar?, busy: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.profile_change_picture)
    Box(modifier = Modifier.size(148.dp)) {
        PressableGlass(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = label },
            shape = CircleShape,
            enabled = !busy,
            hazeState = LocalGlassBackdrop.current,
            onClickLabel = label,
        ) {
            ProfileAvatar(
                avatar = avatar,
                modifier = Modifier.size(128.dp),
                initialsStyle = MaterialTheme.typography.displaySmall,
            )
            if (busy) {
                Box(
                    modifier = Modifier
                        .size(128.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.45f)),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(36.dp), strokeWidth = 3.dp, color = Color.White)
                }
            }
        }
        val accent = MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(accent)
                .border(3.dp, MaterialTheme.colorScheme.background, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = if (accent.luminance() > 0.5f) Color.Black else Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun PhotoPill(
    icon: ImageVector,
    label: String,
    backdrop: dev.chrisbanes.haze.HazeState?,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    GlassPill(
        onClick = onClick,
        hazeState = backdrop,
        enabled = enabled,
        modifier = Modifier.alpha(if (enabled) 1f else 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** The username in a field, with Save and Cancel; opens focused, the cursor at the end. */
@Composable
private fun NameEditor(
    initial: String,
    saving: Boolean,
    error: String?,
    onEdited: () -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val count = value.text.trim().let { it.codePointCount(0, it.length) }

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                value = it
                onEdited()
            },
            label = { Text(stringResource(R.string.profile_username)) },
            singleLine = true,
            enabled = !saving,
            isError = error != null,
            supportingText = { Text(error ?: "$count / $USERNAME_MAX") },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Words,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSave(value.text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focus),
            shape = RoundedCornerShape(12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel, enabled = !saving) { Text(stringResource(R.string.action_cancel)) }
            Spacer(Modifier.width(8.dp))
            GlassPill(
                onClick = { onSave(value.text) },
                hazeState = LocalGlassBackdrop.current,
                enabled = !saving,
                height = 40.dp,
            ) {
                Box(modifier = Modifier.padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.action_save), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}

/**
 * A full-width glass button with an icon, a title and an optional line under
 * it — the account page's actions. [danger] draws the title and icon in the
 * theme's error colour, for signing out.
 */
@Composable
private fun GlassActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    PressableGlass(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .alpha(if (enabled) 1f else 0.7f),
        shape = RoundedCornerShape(20.dp),
        corner = 20.dp,
        enabled = enabled,
        hazeState = LocalGlassBackdrop.current,
        contentAlignment = Alignment.CenterStart,
    ) {
        val ink = if (danger) MaterialTheme.colorScheme.error else LocalContentColor.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (danger) ink else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = ink)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            trailing?.invoke()
        }
    }
}

/** A full-width glass button with a centred label, for the sign-in form. */
@Composable
private fun GlassButton(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    PressableGlass(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .alpha(if (enabled) 1f else 0.5f),
        shape = RoundedCornerShape(16.dp),
        corner = 16.dp,
        enabled = enabled,
        hazeState = LocalGlassBackdrop.current,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SignedOutView(
    isLoading: Boolean,
    errorMessage: String?,
    successMessage: String?,
    email: String,
    onEmailChange: (String) -> Unit,
    password: String,
    onPasswordChange: (String) -> Unit,
    isSignUp: Boolean,
    onIsSignUpChange: (Boolean) -> Unit,
    onSignInWithGoogle: () -> Unit,
    onSignInWithEmail: (String, String) -> Unit,
    onSignUpWithEmail: (String, String) -> Unit,
    onClearError: () -> Unit
) {
    // email / password / isSignUp are hoisted to ProfileScreen so a failed
    // sign-in (which flips isSigningIn true then false, remounting this view)
    // no longer wipes the form the user just typed. passwordVisible is local —
    // resetting the reveal toggle on remount is harmless.
    var passwordVisible by remember { mutableStateOf(false) }

    tf.monochrome.android.devedit.DevEditable("signin_header", Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ProfileAvatar(avatar = null, modifier = Modifier.size(88.dp))

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.sign_in_to_tryptify),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.sign_in_benefit),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    Spacer(modifier = Modifier.height(28.dp))

    GlassButton(
        label = stringResource(R.string.continue_with_google),
        onClick = onSignInWithGoogle,
        enabled = !isLoading,
        loading = isLoading,
    )

    Spacer(modifier = Modifier.height(20.dp))

    // Divider
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.profile_or),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Email field
    OutlinedTextField(
        value = email,
        onValueChange = { onEmailChange(it); onClearError() },
        label = { Text(stringResource(R.string.email)) },
        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Password field
    OutlinedTextField(
        value = password,
        onValueChange = { onPasswordChange(it); onClearError() },
        label = { Text(stringResource(R.string.password)) },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                Icon(
                    if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(R.string.toggle_password)
                )
            }
        },
        singleLine = true,
        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    )

    // Success message (e.g. after sign-up requiring email confirmation)
    if (successMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = successMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(12.dp)
            )
        }
    }

    // Error message
    if (errorMessage != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = errorMessage,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Switch back to sign-in mode after successful account creation
    LaunchedEffect(successMessage) {
        if (successMessage != null && isSignUp) {
            onIsSignUpChange(false)
        }
    }

    // Sign In / Sign Up button
    GlassButton(
        label = if (isSignUp) stringResource(R.string.create_account) else stringResource(R.string.sign_in_with_email),
        onClick = {
            if (isSignUp) onSignUpWithEmail(email, password)
            else onSignInWithEmail(email, password)
        },
        enabled = !isLoading && email.isNotBlank() && password.length >= 8,
    )

    Spacer(modifier = Modifier.height(8.dp))

    TextButton(onClick = { onIsSignUpChange(!isSignUp); onClearError() }) {
        Text(
            if (isSignUp) stringResource(R.string.have_account_sign_in) else stringResource(R.string.no_account_sign_up),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
