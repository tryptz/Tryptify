package tf.monochrome.android.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tf.monochrome.android.data.collections.db.CollectionEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsTab(
    viewModel: LocalLibraryViewModel,
    onCollectionClick: (String) -> Unit
) {
    val collections by viewModel.collections.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    var showImportDialog by remember { mutableStateOf(false) }

    if (showImportDialog) {
        ImportCollectionDialog(
            onDismiss = { showImportDialog = false },
            onImport = { json ->
                viewModel.importCollection(json)
                showImportDialog = false
            }
        )
    }

    // Show import result
    importResult?.let { result ->
        if (result.isFailure) {
            AlertDialog(
                onDismissRequest = { viewModel.clearImportResult() },
                title = { Text("Import Failed") },
                text = { Text(result.exceptionOrNull()?.message ?: "Unknown error") },
                confirmButton = {
                    TextButton(onClick = { viewModel.clearImportResult() }) { Text("OK") }
                }
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Import button
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showImportDialog = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Import",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    "Import Collection",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        if (collections.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.Collections,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "No collections imported",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Import a collection manifest to get started",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        }

        items(collections, key = { it.collectionId }) { collection ->
            CollectionCard(
                collection = collection,
                onClick = { onCollectionClick(collection.collectionId) },
                onDelete = { viewModel.deleteCollection(collection.collectionId) }
            )
        }
    }
}

@Composable
private fun CollectionCard(
    collection: CollectionEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Collections,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Collection",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "by ${collection.authorId}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "v${collection.version} - ${collection.encryptionType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ImportCollectionDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var manifestJson by remember { mutableStateOf("") }
    var isReadingFile by remember { mutableStateOf(false) }
    
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    val filePickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { 
            isReadingFile = true
            coroutineScope.launch {
                try {
                    val content = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                            reader.readText()
                        }
                    }
                    if (!content.isNullOrBlank()) {
                        onImport(content)
                    }
                } catch (e: Exception) {
                    // Fallback to error or ignore
                } finally {
                    isReadingFile = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Collection") },
        text = {
            Column {
                if (isReadingFile) {
                    Text("Reading file...", style = MaterialTheme.typography.bodyMedium)
                } else {
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Select .json File")
                    }
                    
                    Text(
                        "Or paste the manifest JSON below:",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                    )
                    OutlinedTextField(
                        value = manifestJson,
                        onValueChange = { manifestJson = it },
                        label = { Text("Manifest JSON") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp),
                        maxLines = 10
                    )
                }
            }
        },
        confirmButton = {
            if (!isReadingFile) {
                TextButton(
                    onClick = { onImport(manifestJson) },
                    enabled = manifestJson.isNotBlank()
                ) { Text("Import") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isReadingFile) { Text("Cancel") }
        }
    )
}
