package tf.monochrome.android.ui.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import tf.monochrome.android.debug.DebugLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugLogScreen(
    navController: NavController,
    viewModel: DebugLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsState()
    val query by viewModel.query.collectAsState()
    val levelFilter by viewModel.levelFilter.collectAsState()
    val totalSize by viewModel.totalSize.collectAsState()
    val context = LocalContext.current

    var showClearConfirm by remember { mutableStateOf(false) }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear log buffer?") },
            text = { Text("This discards the current diagnostic log. Copy or export it first if you need it.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    viewModel.clear()
                    Toast.makeText(context, "Log cleared", Toast.LENGTH_SHORT).show()
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    // Create a text file picker, seeded with a timestamped filename so successive
    // exports don't overwrite each other. SAF handles the actual write.
    val saveLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(viewModel.exportText().toByteArray())
                }
                Toast.makeText(context, "Saved debug log", Toast.LENGTH_SHORT).show()
            }.onFailure {
                Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val listState = rememberLazyListState()

    // Auto-scroll to the newest entry only when the user is already at (or
    // near) the bottom — otherwise a new log line every ~250ms yanked them
    // back down and made scrolling up to read an earlier entry impossible.
    LaunchedEffect(entries.size) {
        if (entries.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= entries.lastIndex - 1) {
            listState.scrollToItem(entries.lastIndex)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("Debug Log", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${entries.size} shown / $totalSize buffered",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            navigationIcon = {
                IconButton(onClick = { navController.popBackStack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("monotrypt-debug-log", viewModel.exportText()))
                    Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                }) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all")
                }
                IconButton(onClick = {
                    saveLauncher.launch(defaultFilename())
                }) {
                    Icon(Icons.Default.Download, contentDescription = "Export to file")
                }
                IconButton(onClick = { showClearConfirm = true }) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear buffer")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            label = { Text("Filter (tag or message)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            singleLine = true,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LevelFilter.entries.forEach { option ->
                FilterChip(
                    selected = levelFilter == option,
                    onClick = { viewModel.setLevelFilter(option) },
                    label = { Text(option.label) },
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            items(items = entries) { entry ->
                LogRow(entry = entry)
            }
        }
    }
}

@Composable
private fun LogRow(entry: DebugLogEntry) {
    val tintColor = colorForLevel(entry.level)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Colored level pill — lets the eye skim Warnings / Errors at a glance.
        Text(
            text = entry.level.toString(),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = Color.White,
            modifier = Modifier
                .background(tintColor, shape = RoundedCornerShape(3.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${entry.timestamp} ${entry.tag}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun colorForLevel(level: Char): Color = when (level) {
    'V' -> Color(0xFF6E6E6E)
    'D' -> Color(0xFF2196F3)
    'I' -> Color(0xFF4CAF50)
    'W' -> Color(0xFFFFC107)
    'E' -> Color(0xFFE53935)
    'F' -> Color(0xFFB71C1C)
    else -> Color(0xFF9E9E9E)
}

private fun defaultFilename(): String {
    val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    return "monotrypt-debug-$ts.log"
}
