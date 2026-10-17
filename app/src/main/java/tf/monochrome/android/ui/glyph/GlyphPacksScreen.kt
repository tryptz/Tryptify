// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.net.Uri
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tf.monochrome.android.R
import tf.monochrome.android.glyph.pack.StepManiaPack
import tf.monochrome.android.ui.navigation.LocalBottomChromeInset
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * StepMania packs: getting them (StepMania Online, or a ZIP), the ones
 * imported, and the player's own pack — the charts made here, kept as a pack
 * in a folder of their choosing.
 *
 * A menu, so the app's (see `docs/ui-invariants.md`, Trypt Mania): a top bar
 * with Back, the theme's page and ink, `GlyphPanel`s.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlyphPacksScreen(
    state: GlyphUiState,
    onEvent: (GlyphEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val packs = state.packs
    var browsing by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onEvent(GlyphEvent.ImportPackFile(uri))
    }
    // Songs already unpacked, as StepMania keeps them: a song, a pack, or Songs.
    val songsFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) onEvent(GlyphEvent.ImportPackFolder(tree))
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) onEvent(GlyphEvent.ChoosePackFolder(tree))
    }
    val zipSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) onEvent(GlyphEvent.ZipOwnPack(uri))
    }
    BackHandler { onEvent(GlyphEvent.ClosePacks) }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Packs", modifier = Modifier.semantics { heading() }) },
            navigationIcon = {
                IconButton(onClick = { onEvent(GlyphEvent.ClosePacks) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MonoDimens.spacingLg),
            verticalArrangement = Arrangement.spacedBy(MonoDimens.spacingMd),
        ) {
            val scheme = MaterialTheme.colorScheme
            packs.message?.let { message ->
                GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphButton(text = "OK", onClick = { onEvent(GlyphEvent.DismissPacksMessage) }, height = 40.dp)
                }
            }

            // ── getting packs ───────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("Get packs")
                Text(
                    "Simfile packs from StepMania Online, a pack ZIP you already have, or songs already " +
                        "unpacked on the phone — pick one song, one pack, or StepMania's whole Songs folder, and " +
                        "they play from where they are, nothing copied. Their dance and pop'n charts play here with " +
                        "the pack's own music.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(MonoDimens.spacingMd))
                GlyphButton(
                    text = "Browse StepMania Online",
                    icon = Icons.Default.Language,
                    primary = true,
                    onClick = { browsing = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(MonoDimens.spacingSm))
                GlyphButton(
                    text = "Import a pack ZIP…",
                    icon = Icons.Default.FolderOpen,
                    onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(MonoDimens.spacingSm))
                GlyphButton(
                    text = "Link unpacked songs (folder)…",
                    icon = Icons.Default.Folder,
                    onClickLabel = "Pick a song, a pack, or the Songs folder to play in place",
                    onClick = { songsFolderPicker.launch(null) },
                    modifier = Modifier.fillMaxWidth(),
                )
                packs.importing?.let { importing ->
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                    val waiting = if (importing.queued > 0) " · ${importing.queued} more waiting" else ""
                    Text("Importing ${importing.name}: ${importing.stage}$waiting", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphMeter(
                        fraction = importing.fraction,
                        color = scheme.onSurface,
                        description = "Importing ${importing.name}, ${(importing.fraction * 100).toInt()} percent",
                    )
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    GlyphButton(
                        text = "Cancel",
                        onClick = { onEvent(GlyphEvent.CancelPackImports) },
                        onClickLabel = "Cancel the pack imports",
                        height = 40.dp,
                    )
                }
            }

            // ── the player's own pack ───────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("Your pack")
                val folder = packs.folderName
                Text(
                    text = if (folder != null) {
                        "Charts you make are saved as the ${StepManiaPack.OWN_PACK} pack in $folder: a StepMania " +
                            "song folder each, with its music, ready for StepMania or ITGmania's Songs folder."
                    } else {
                        "Choose a folder, and every chart you make is saved there as a StepMania pack, with its " +
                            "music, ready for StepMania or ITGmania. Until then charts stay in the app."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    "${packs.ownCharts} chart${if (packs.ownCharts == 1) "" else "s"} made so far.",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = MonoDimens.spacingXs),
                )
                Spacer(Modifier.height(MonoDimens.spacingMd))
                packs.saving?.let { saving ->
                    Text("Saving $saving", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(MonoDimens.spacingSm))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(MonoDimens.spacingMd))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    GlyphButton(
                        text = if (folder != null) "Change folder" else "Choose folder…",
                        icon = Icons.Default.FolderOpen,
                        primary = folder == null,
                        onClick = { folderPicker.launch(null) },
                        modifier = Modifier.weight(1f),
                    )
                    if (folder != null) {
                        GlyphButton(
                            text = "Stop saving",
                            onClick = { onEvent(GlyphEvent.ForgetPackFolder) },
                            onClickLabel = "Stop saving charts to $folder",
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(MonoDimens.spacingSm))
                Row(horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm)) {
                    if (folder != null) {
                        GlyphButton(
                            text = "Save all again",
                            enabled = packs.saving == null && packs.ownCharts > 0,
                            onClick = { onEvent(GlyphEvent.SaveOwnPack) },
                            onClickLabel = "Write every chart you made into the pack folder",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    GlyphButton(
                        text = "Save as ZIP…",
                        enabled = packs.saving == null && packs.ownCharts > 0,
                        onClick = { zipSaver.launch("${StepManiaPack.OWN_PACK}.zip") },
                        onClickLabel = "Save your pack as one ZIP to share",
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            // ── imported packs ──────────────────────────────────────────
            GlyphPanel(modifier = Modifier.fillMaxWidth()) {
                SectionTitle("Imported")
                if (packs.imported.isEmpty()) {
                    Text("None yet.", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
                for (pack in packs.imported) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = MonoDimens.spacingXs),
                        horizontalArrangement = Arrangement.spacedBy(MonoDimens.spacingSm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pack.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                            Text(
                                "${pack.songs} song${if (pack.songs == 1) "" else "s"} · " +
                                    // Its music stays in the player's folder: its size here is only the charts.
                                    if (pack.inPlace) "read in place" else formatBytes(pack.bytes),
                                style = MaterialTheme.typography.labelMedium,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        GlyphButton(
                            text = "Remove",
                            onClick = { removing = pack.name },
                            onClickLabel = "Remove ${pack.name}",
                            height = 40.dp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(LocalBottomChromeInset.current + MonoDimens.spacingLg))
        }
    }

    removing?.let { name ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $name?") },
            text = { Text("Its songs, charts and music are deleted from the app. Your scores stay.") },
            confirmButton = {
                TextButton(onClick = {
                    onEvent(GlyphEvent.RemovePack(name))
                    removing = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Keep") } },
        )
    }

    if (browsing) {
        StepManiaOnlineBrowser(
            onDismiss = { browsing = false },
            onDownload = { url, name -> onEvent(GlyphEvent.ImportPackUrl(url, name)) },
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = MonoDimens.spacingXs).semantics { heading() },
    )
}

/**
 * Asks, before the first chart is made, where the player's own pack is kept.
 * The picker opens on "Choose folder"; "Not now" makes the chart without a
 * copy, and is remembered, so it is asked once.
 */
@Composable
fun GlyphPackFolderDialog(onEvent: (GlyphEvent) -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        onEvent(GlyphEvent.ChoosePackFolder(tree))
    }
    AlertDialog(
        onDismissRequest = { onEvent(GlyphEvent.ChoosePackFolder(null)) },
        title = { Text("Where should your charts go?") },
        text = {
            Text(
                "Pick a folder and every chart you make is saved there as a StepMania pack " +
                    "(${StepManiaPack.OWN_PACK}/song/song.ssc, with its music), so it plays in StepMania and " +
                    "ITGmania too. You can change it under Packs.",
            )
        },
        confirmButton = { TextButton(onClick = { picker.launch(null) }) { Text("Choose folder") } },
        dismissButton = { TextButton(onClick = { onEvent(GlyphEvent.ChoosePackFolder(null)) }) { Text("Not now") } },
    )
}

/**
 * StepMania Online, in the app. Its pages are browsed as they are; a pack's
 * download link is not downloaded by the page but handed to the import queue,
 * so the pack streams straight into the app (see `PackImportWorker`).
 *
 * Only `stepmaniaonline.net`, only over https, and without JavaScript, which
 * the site does not need: a link anywhere else opens in the browser instead.
 * A full-screen dialog rather than glass, as Exportify's is: a web page is
 * opaque and the whole screen.
 */
@Composable
private fun StepManiaOnlineBrowser(onDismiss: () -> Unit, onDownload: (String, String) -> Unit) {
    val download by rememberUpdatedState(onDownload)
    val uriHandler = LocalUriHandler.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var queued by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false),
    ) {
        BackHandler {
            val web = webView
            if (web != null && web.canGoBack()) web.goBack() else onDismiss()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .safeDrawingPadding(),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                }
                Text(
                    text = "StepMania Online",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Text(
                text = queued?.let { "Added $it to the imports. It downloads in the background; carry on browsing." }
                    ?: "Find a pack and tap its download button: it imports into Trypt Mania in the background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = false
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            fun take(url: String, name: String) {
                                download(url, name)
                                queued = name.ifBlank { "the pack" }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url
                                    if (url.scheme != "https" || !isStepManiaOnline(url)) {
                                        // Elsewhere: the browser, not this page.
                                        runCatching { uriHandler.openUri(url.toString()) }
                                        return true
                                    }
                                    if (url.path.orEmpty().startsWith("/download/")) {
                                        // On a pack's own page its title names it; on a list,
                                        // the download's own file name does, later.
                                        val title = view.title.orEmpty().removePrefix("Pack - ").trim()
                                        take(url.toString(), if (view.url.orEmpty().contains("/pack/")) title else "")
                                        return true
                                    }
                                    return false
                                }
                            }
                            // A download the page starts some other way: the same queue.
                            setDownloadListener { url, _, disposition, mime, _ ->
                                if (url.startsWith("https://")) {
                                    take(url, URLUtil.guessFileName(url, disposition, mime).removeSuffix(".zip"))
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            loadUrl(STEPMANIA_ONLINE)
                            webView = this
                        }
                    },
                    onRelease = {
                        if (webView === it) webView = null
                        it.destroy()
                    },
                )
            }
        }
    }
}

private const val STEPMANIA_ONLINE = "https://stepmaniaonline.net/"

private fun isStepManiaOnline(url: Uri): Boolean {
    val host = url.host?.lowercase() ?: return false
    return host == "stepmaniaonline.net" || host.endsWith(".stepmaniaonline.net")
}

/** "1.2 GB", "340 MB", "12 kB": a pack's size on disk. */
private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000L -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
