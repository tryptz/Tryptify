package tf.monochrome.android.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tf.monochrome.android.data.api.ApiServer
import tf.monochrome.android.data.api.ApiService
import tf.monochrome.android.data.api.ProbeResult
import tf.monochrome.android.domain.model.SourceType
import tf.monochrome.android.ui.components.SourcePill
import tf.monochrome.android.ui.components.liquidGlass

/** The pill a detected service wears: the same brand pill Search uses. */
private fun ApiService.sourceType(): SourceType = when (this) {
    ApiService.TIDAL -> SourceType.API
    ApiService.QOBUZ -> SourceType.QOBUZ
    ApiService.APPLE -> SourceType.APPLE
    ApiService.DEEZER -> SourceType.DEEZER
}

/**
 * Settings › Connections › APIs: one list of servers, added with +.
 *
 * Replaces the per-catalog URL fields and the catalog-source picker. Nothing
 * here asks which service a server is — it is asked of the server, by
 * [tf.monochrome.android.data.api.ApiServerProber] — and Search uses every
 * catalog some listed server serves. Unwrapped from any scroll container, like
 * the block it replaces, because [ConnectionsTab] stacks it in a LazyColumn.
 */
@Composable
internal fun ApiServersSection(viewModel: SettingsViewModel) {
    val servers by viewModel.apiServers.collectAsStateWithLifecycle()
    val checking by viewModel.apiChecking.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    SettingsGroupHeader("APIs")
    Text(
        text = "Add a server and Tryptify works out what it serves. Search uses every " +
            "catalog your APIs provide; when two serve the same one, the higher one is used.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    if (servers.isEmpty()) {
        Text(
            text = "No APIs yet. Add one to search and play.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
    servers.forEachIndexed { index, server ->
        ApiServerCard(
            server = server,
            checking = server.url in checking,
            canMoveUp = index > 0,
            onMoveUp = { viewModel.moveApiUp(server.url) },
            onRecheck = { viewModel.recheckApi(server.url) },
            onRemove = { confirmRemove = server.url },
        )
    }

    Spacer(Modifier.height(8.dp))
    OutlinedButton(
        onClick = { viewModel.resetAddApi(); showAdd = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.size(8.dp))
        Text("Add API")
    }

    if (showAdd) {
        AddApiDialog(
            viewModel = viewModel,
            onDismiss = { showAdd = false; viewModel.resetAddApi() },
        )
    }
    confirmRemove?.let { url ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove this API?") },
            text = { Text("$url\n\nIts catalogs leave Search unless another API serves them.") },
            confirmButton = {
                TextButton(onClick = { viewModel.removeApi(url); confirmRemove = null }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiServerCard(
    server: ApiServer,
    checking: Boolean,
    canMoveUp: Boolean,
    onMoveUp: () -> Unit,
    onRecheck: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .liquidGlass(shape = RoundedCornerShape(8.dp)),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.url.substringAfter("://"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                when {
                    checking -> Text(
                        "Checking…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    server.services.isEmpty() -> Text(
                        "Serves nothing Tryptify can use right now. Check the server, then tap ↻.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ApiService.entries.filter { it in server.services }
                            .forEach { SourcePill(it.sourceType()) }
                    }
                }
            }
            if (canMoveUp) {
                IconButton(onClick = onMoveUp) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "Move up")
                }
            }
            IconButton(onClick = onRecheck, enabled = !checking) {
                if (checking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = "Check again")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddApiDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.addApiState.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") }
    val busy = state is AddApiState.Checking
    val done = state as? AddApiState.Done
    val added = done != null && done.result.services.isNotEmpty()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (added) "API added" else "Add API") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (!added) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it; if (state !is AddApiState.Checking) viewModel.resetAddApi() },
                        label = { Text("Server address") },
                        placeholder = { Text("https://hifi.example.com") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { viewModel.addApi(input) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                }
                when (val s = state) {
                    AddApiState.Idle -> Text(
                        "Just the server's base address. Tryptify checks it for TIDAL, Qobuz, " +
                            "Apple Music and Deezer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AddApiState.Invalid -> Text(
                        "That isn't a web address. Try something like https://hifi.example.com.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is AddApiState.AlreadyAdded -> Text(
                        "${s.url} is already in the list. Use ↻ on it to check it again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    is AddApiState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(10.dp))
                        Text("Asking ${s.url.substringAfter("://")}…", style = MaterialTheme.typography.bodySmall)
                    }
                    is AddApiState.Done -> ProbeResultView(s.result)
                }
            }
        },
        confirmButton = {
            when {
                added -> TextButton(onClick = onDismiss) { Text("Done") }
                done != null -> TextButton(onClick = onDismiss) { Text("Close") }
                else -> TextButton(onClick = { viewModel.addApi(input) }, enabled = !busy && input.isNotBlank()) {
                    Text("Check and add")
                }
            }
        },
        dismissButton = {
            if (!added) TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
        },
    )
}

/** What a check found: the services it serves as pills, then why each other one didn't answer. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProbeResultView(result: ProbeResult) {
    if (result.services.isEmpty()) {
        Text(
            "Nothing Tryptify can use answered at ${result.url.substringAfter("://")}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    } else {
        Text("Serves", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ApiService.entries.filter { it in result.services }.forEach { SourcePill(it.sourceType()) }
        }
    }
    if (result.reasons.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Not found", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ApiService.entries.forEach { service ->
            result.reasons[service]?.let { reason ->
                Text(
                    "${service.label}: $reason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}
