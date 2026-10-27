package tf.monochrome.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.R
import tf.monochrome.android.data.subsonic.SubsonicAuthMode
import tf.monochrome.android.data.subsonic.SubsonicConnectError
import tf.monochrome.android.data.subsonic.SubsonicServer
import tf.monochrome.android.ui.components.materialSurface

/**
 * Settings › Connections › Music server: the listener's own Subsonic server
 * (Navidrome, Gonic, Airsonic-Advanced, Ampache…), with a how-to for someone
 * who has never set one up. Unwrapped from any scroll container, like
 * [ApiServersSection], because [ConnectionsTab] stacks it in a LazyColumn.
 */
@Composable
internal fun SubsonicSection(viewModel: SubsonicSettingsViewModel = hiltViewModel()) {
    val server by viewModel.server.collectAsStateWithLifecycle()
    var showConnect by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }

    SettingsGroupHeader(stringResource(R.string.subsonic_title))
    Text(
        text = stringResource(R.string.subsonic_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    val connected = server
    if (connected != null) {
        ServerCard(connected, onDisconnect = { confirmDisconnect = true })
        CellularBitrate(viewModel)
    } else {
        OutlinedButton(
            onClick = { viewModel.resetConnect(); showConnect = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(8.dp))
            Text(stringResource(R.string.subsonic_connect_server))
        }
    }

    SetupGuide()

    if (showConnect) {
        ConnectDialog(viewModel, onDismiss = { showConnect = false; viewModel.resetConnect() })
    }
    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            title = { Text(stringResource(R.string.subsonic_disconnect_title)) },
            text = { Text(stringResource(R.string.subsonic_disconnect_detail)) },
            confirmButton = {
                TextButton(onClick = { viewModel.disconnect(); confirmDisconnect = false }) {
                    Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

/** What songs stream at on mobile data: the original file, or an MP3 cap. Laid out as the DSP block size is. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CellularBitrate(viewModel: SubsonicSettingsViewModel) {
    val current by viewModel.cellularMaxBitrate.collectAsStateWithLifecycle()
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.settings_cellular_streaming),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        text = stringResource(R.string.subsonic_cellular_detail),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tf.monochrome.android.data.subsonic.Subsonic.MOBILE_BITRATES.forEach { kbps ->
            tf.monochrome.android.ui.mixer.GlassChoiceChip(
                // Rates are numbers and a unit, the same in every language.
                label = if (kbps == 0) stringResource(R.string.subsonic_quality_original) else "MP3 $kbps kbps",
                selected = kbps == current,
                accent = MaterialTheme.colorScheme.primary,
                onClick = { viewModel.setCellularMaxBitrate(kbps) },
            )
        }
    }
}

@Composable
private fun ServerCard(server: SubsonicServer, onDisconnect: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .materialSurface(shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Dns, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server.displayHost, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.subsonic_signed_in_as, server.username),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // "navidrome 0.53.3", when the server says what it is.
                val kind = listOfNotNull(
                    server.serverType?.replaceFirstChar { it.uppercase() },
                    server.serverVersion,
                ).joinToString(" ")
                if (kind.isNotBlank()) {
                    Text(kind, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    stringResource(
                        if (server.authMode == SubsonicAuthMode.TOKEN) R.string.subsonic_token_note
                        else R.string.subsonic_password_note,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            TextButton(onClick = onDisconnect) {
                Text(stringResource(R.string.settings_disconnect), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ConnectDialog(viewModel: SubsonicSettingsViewModel, onDismiss: () -> Unit) {
    val state by viewModel.connect.collectAsStateWithLifecycle()
    var address by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    // Not rememberSaveable: a password has no business in the saved instance state.
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val busy = state is SubsonicSettingsViewModel.ConnectState.Connecting
    val canConnect = !busy && address.isNotBlank() && username.isNotBlank()
    val submit = { if (canConnect) viewModel.connect(address, username, password, onConnected = onDismiss) }
    val edited = { viewModel.resetConnect() }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.subsonic_connect_server)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it; edited() },
                    label = { Text(stringResource(R.string.api_server_address)) },
                    placeholder = { Text("http://192.168.1.20:4533") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; edited() },
                    label = { Text(stringResource(R.string.subsonic_username)) },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; edited() },
                    label = { Text(stringResource(R.string.password)) },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = stringResource(
                                    if (showPassword) R.string.subsonic_hide_password else R.string.subsonic_show_password,
                                ),
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                when (val s = state) {
                    SubsonicSettingsViewModel.ConnectState.Idle -> Text(
                        stringResource(R.string.subsonic_connect_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SubsonicSettingsViewModel.ConnectState.Connecting -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text(stringResource(R.string.subsonic_connecting), style = MaterialTheme.typography.bodySmall)
                    }
                    is SubsonicSettingsViewModel.ConnectState.Failed -> Text(
                        connectErrorText(s.result.error, s.result.detail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canConnect) { Text(stringResource(R.string.subsonic_connect)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun connectErrorText(error: SubsonicConnectError, detail: String?): String = when (error) {
    SubsonicConnectError.BAD_ADDRESS -> stringResource(R.string.subsonic_error_address)
    SubsonicConnectError.NEEDS_HTTPS -> stringResource(R.string.subsonic_error_needs_https)
    SubsonicConnectError.PASSWORD_NEEDS_HTTPS -> stringResource(R.string.subsonic_error_password_needs_https)
    SubsonicConnectError.WRONG_LOGIN -> stringResource(R.string.subsonic_error_login)
    SubsonicConnectError.NOT_SUBSONIC -> stringResource(R.string.subsonic_error_not_subsonic)
    SubsonicConnectError.UNREACHABLE -> stringResource(R.string.subsonic_error_unreachable)
    SubsonicConnectError.OTHER -> stringResource(R.string.subsonic_error_other, detail ?: "?")
}

/** "How to set up": folded by default, so the section stays short once you have a server. */
@Composable
private fun SetupGuide() {
    var open by rememberSaveable { mutableStateOf(false) }
    TextButton(onClick = { open = !open }, modifier = Modifier.padding(top = 4.dp)) {
        Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        Spacer(Modifier.size(6.dp))
        Text(stringResource(R.string.subsonic_howto_title))
    }
    if (!open) return
    val steps = listOf(
        R.string.subsonic_howto_install,
        R.string.subsonic_howto_library,
        R.string.subsonic_howto_address,
        R.string.subsonic_howto_phone,
        R.string.subsonic_howto_away,
        R.string.subsonic_howto_connect,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
        steps.forEachIndexed { i, step ->
            Row {
                Text(
                    "${i + 1}.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.width(22.dp),
                )
                Text(stringResource(step), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text(
            stringResource(R.string.subsonic_howto_tip),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
