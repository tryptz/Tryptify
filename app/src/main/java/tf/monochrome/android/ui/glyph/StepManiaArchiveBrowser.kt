// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.net.Uri
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import tf.monochrome.android.R
import tf.monochrome.android.ui.theme.MonoDimens

/**
 * The StepMania Archive (josevarela.net), a tab for each section Trypt Mania
 * imports from: Themes, NoteSkins, Announcers and Characters. A download
 * tapped is imported in the background, as Packs' StepMania Online browser
 * queues a pack, and the page stays where it was.
 *
 * Only the archive's own pages, over https, without JavaScript, which they do
 * not need; a link anywhere else opens in the browser. Its downloads come
 * from its file host and GitHub, and any https archive link is taken: ZIP,
 * .smzip, 7z or RAR. A full-screen dialog, as Packs' is: a web page is opaque
 * and the whole screen.
 */
@Composable
fun StepManiaArchiveBrowser(onDismiss: () -> Unit, onDownload: (String, String) -> Unit) {
    val download by rememberUpdatedState(onDownload)
    val uriHandler = LocalUriHandler.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var note by remember { mutableStateOf<String?>(null) }
    var section by rememberSaveable { mutableStateOf(ARCHIVE_SECTIONS.first().path) }
    val queuedLine = stringResource(R.string.tm_themes_archive_queued)
    val labels = ARCHIVE_SECTIONS.associate { it.path to stringResource(it.label) }

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
                    text = stringResource(R.string.tm_themes_archive_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            // One tab per section of the archive, as on the site.
            GlyphChipRow(
                options = ARCHIVE_SECTIONS.map { it.path },
                selected = section,
                label = { path -> labels.getValue(path) },
                onSelect = { path ->
                    section = path
                    webView?.loadUrl(ARCHIVE + path + "/")
                },
                modifier = Modifier.padding(horizontal = MonoDimens.spacingMd),
            )
            Text(
                text = note ?: stringResource(R.string.tm_themes_archive_hint),
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
                            fun take(url: Uri) {
                                val file = Uri.decode(url.lastPathSegment.orEmpty())
                                val name = file.substringBeforeLast('.')
                                download(url.toString(), name)
                                note = queuedLine.format(name.ifBlank { "…" })
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                    val url = request.url
                                    if (url.scheme == "https" && isArchiveDownload(url)) {
                                        take(url)
                                        return true
                                    }
                                    if (url.scheme != "https" || !isArchive(url)) {
                                        runCatching { uriHandler.openUri(url.toString()) }
                                        return true
                                    }
                                    return false
                                }
                            }
                            setDownloadListener { url, _, disposition, mime, _ ->
                                if (url.startsWith("https://")) {
                                    val name = URLUtil.guessFileName(url, disposition, mime)
                                    take(Uri.parse(url).buildUpon().path("/" + name).build().takeIf { isArchiveDownload(it) } ?: Uri.parse(url))
                                }
                            }
                            webChromeClient = object : android.webkit.WebChromeClient() {
                                override fun onProgressChanged(view: WebView, newProgress: Int) {
                                    progress = newProgress
                                }
                            }
                            loadUrl(ARCHIVE + section + "/")
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

/** The archive's sections, by their path on the site and their tab's label. */
private class ArchiveSection(val path: String, val label: Int)

private val ARCHIVE_SECTIONS = listOf(
    ArchiveSection("Themes", R.string.tm_themes_themes),
    ArchiveSection("NoteSkins", R.string.tm_themes_noteskins),
    ArchiveSection("Announcers", R.string.tm_themes_announcers),
    ArchiveSection("Characters", R.string.tm_themes_characters),
)

private const val ARCHIVE = "https://josevarela.net/SMArchive/"

private fun isArchive(url: Uri): Boolean {
    val host = url.host?.lowercase() ?: return false
    return host == "josevarela.net" || host.endsWith(".josevarela.net")
}

/** A link to an archive file: a ZIP, .smzip, 7z or RAR. */
private fun isArchiveDownload(url: Uri): Boolean {
    val path = url.path.orEmpty().lowercase()
    return ARCHIVE_EXTENSIONS.any { path.endsWith(it) }
}

private val ARCHIVE_EXTENSIONS = listOf(".zip", ".smzip", ".7z", ".rar")
