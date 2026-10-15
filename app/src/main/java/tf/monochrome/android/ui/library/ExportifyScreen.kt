package tf.monochrome.android.ui.library

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.delay
import tf.monochrome.android.R
import tf.monochrome.android.data.import_.ExportifyFiles

/**
 * exportify.net inside the app, so a playlist Exportify exports comes straight
 * into the library rather than into a file to pick afterwards.
 *
 * Exportify signs in to Spotify under its own registration, which any account
 * can use (the app's own is held by Spotify to 5 allowlisted accounts), so
 * this is the way in for private playlists, Liked Songs and playlists past the
 * 100 songs a link brings. Nothing about Exportify changes: it signs in and
 * exports as it does in a browser. On its own page, and only there, its
 * `saveAs` is replaced by one that posts the file to the app
 * ([ExportifyFiles.Message]); the app listens through a web message listener
 * that only `https://exportify.net` can reach, so no other page loaded here
 * can hand the app anything.
 *
 * A full-screen dialog rather than glass: it is a web page, opaque, and the
 * whole screen.
 */
@Composable
fun ExportifyScreen(
    onDismiss: () -> Unit,
    onExported: (ExportifyFiles.Message) -> Unit,
) {
    val exported by rememberUpdatedState(onExported)
    val uriHandler = LocalUriHandler.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    // Each Retry is a new WebView: one whose renderer has gone cannot be reused.
    var attempt by remember { mutableIntStateOf(0) }
    var progress by remember(attempt) { mutableIntStateOf(0) }
    // Set when Exportify's first page has drawn. Until then the box is only the
    // WebView's own background, which says nothing about what went wrong.
    var shown by remember(attempt) { mutableStateOf(false) }
    var stalled by remember(attempt) { mutableStateOf(false) }
    var error by remember(attempt) { mutableStateOf<String?>(null) }
    var gone by remember(attempt) { mutableStateOf(false) }
    LaunchedEffect(attempt) {
        delay(STALL_MS)
        stalled = true
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnBackPress = false,
        ),
    ) {
        // Back steps back through the pages first (Spotify's sign-in back to
        // Exportify), as in a browser, and closes Exportify only from its first.
        BackHandler {
            val web = webView
            if (web != null && web.canGoBack()) web.goBack() else onDismiss()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                // The bars, and the keyboard: Spotify's sign-in is a form, and
                // its fields must stay above the keyboard to be typed into.
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.action_close))
                }
                Text(
                    text = "Exportify",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.exportify_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            val failed = error != null || gone
            if (!failed && progress in 1..99) {
                LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
            } else if (!failed && !shown) {
                // Loaded or not, nothing has drawn yet: still waiting.
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                // The listener is what keeps the bridge to exportify.net alone;
                // without it there is no safe way to take the file.
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.exportify_webview_too_old),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }
                return@Column
            }
            Box(Modifier.fillMaxSize()) {
                // A WebView whose renderer has gone must leave the hierarchy.
                if (!gone) key(attempt) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            @SuppressLint("SetJavaScriptEnabled")
                            val web = WebView(context).apply {
                                // WebView picks its layout strategy from these, not
                                // from Compose: left at the default wrap_content it
                                // sizes itself to the page instead of the box.
                                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                                // Exportify's page is white. The app's window theme is
                                // dark whatever theme is chosen, which would have the
                                // WebView darken the page and wait on a dark box.
                                setBackgroundColor(android.graphics.Color.WHITE)
                                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                                }
                                // Exportify is a script, and keeps its Spotify sign-in
                                // in localStorage.
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                WebViewCompat.addWebMessageListener(this, BRIDGE, setOf(EXPORTIFY_ORIGIN)) { _, message, sourceOrigin, isMainFrame, _ ->
                                    if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != EXPORTIFY_ORIGIN) return@addWebMessageListener
                                    val parsed = message.data?.let(ExportifyFiles::parseMessage) ?: return@addWebMessageListener
                                    exported(parsed)
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                        // Exportify and Spotify's sign-in, over https; nothing in the clear.
                                        request.url.scheme != "https"

                                    override fun onPageCommitVisible(view: WebView, url: String) {
                                        // Also fires for Chromium's own error page, after
                                        // onReceivedError: an error stays until Retry.
                                        shown = true
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        if (Uri.parse(url).host == EXPORTIFY_HOST) view.evaluateJavascript(HOOK, null)
                                    }

                                    override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
                                        if (request.isForMainFrame) error = err.description?.toString().orEmpty().ifBlank { err.errorCode.toString() }
                                    }

                                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                        // Handled, so the app is not taken down with the page.
                                        if (webView === view) webView = null
                                        gone = true
                                        return true
                                    }
                                }
                                webChromeClient = object : android.webkit.WebChromeClient() {
                                    override fun onProgressChanged(view: WebView, newProgress: Int) {
                                        progress = newProgress
                                    }
                                }
                                loadUrl("$EXPORTIFY_ORIGIN/")
                            }
                            webView = web
                            web
                        },
                        onRelease = {
                            if (webView === it) webView = null
                            it.destroy()
                        },
                    )
                }
                if (failed || (stalled && !shown)) {
                    ExportifyTrouble(
                        text = when {
                            gone -> stringResource(R.string.exportify_stopped)
                            error != null -> stringResource(R.string.exportify_failed, error.orEmpty())
                            else -> stringResource(R.string.exportify_stalled)
                        },
                        onRetry = { attempt++ },
                        onOpenInBrowser = { runCatching { uriHandler.openUri("$EXPORTIFY_ORIGIN/") } },
                    )
                }
            }
        }
    }
}

/**
 * Over the WebView when Exportify has failed or not appeared: what happened,
 * and two ways on. Never a silent empty box.
 */
@Composable
private fun ExportifyTrouble(text: String, onRetry: () -> Unit, onOpenInBrowser: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        OutlinedButton(onClick = onOpenInBrowser) { Text(stringResource(R.string.exportify_open_browser)) }
    }
}

/** Long enough for a slow connection to load Exportify; it usually takes a second or two. */
private const val STALL_MS = 15_000L

private const val EXPORTIFY_HOST = "exportify.net"
private const val EXPORTIFY_ORIGIN = "https://exportify.net"
private const val BRIDGE = "TryptifyExportify"

/**
 * Run on exportify.net once each page has loaded. Notes the name of the
 * playlist whose Export is tapped (its row's link text: the file name has its
 * spaces turned into underscores), and replaces FileSaver's `saveAs`, which
 * Exportify saves every export through, with one that posts the file to the
 * app as a data URL instead of downloading it.
 */
private val HOOK = """
(function () {
  if (window.__tryptifyHooked || typeof $BRIDGE === 'undefined') return;
  window.__tryptifyHooked = true;
  var lastName = null;
  document.addEventListener('click', function (e) {
    var b = e.target && e.target.closest ? e.target.closest('button') : null;
    if (!b || !b.id || b.id.indexOf('export') !== 0 || b.id === 'exportAll') return;
    var row = b.closest('tr');
    var a = row ? row.querySelector('td a') : null;
    lastName = a ? a.textContent : null;
  }, true);
  window.saveAs = function (blob, name) {
    var reader = new FileReader();
    reader.onload = function () {
      $BRIDGE.postMessage(JSON.stringify({
        fileName: String(name),
        displayName: /\.csv$/i.test(String(name)) ? lastName : null,
        dataUrl: reader.result
      }));
    };
    reader.readAsDataURL(blob);
  };
})();
""".trimIndent()
