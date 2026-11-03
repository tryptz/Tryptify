// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.ui.glyph

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tf.monochrome.android.glyph.asset.GlyphSkin
import tf.monochrome.android.glyph.asset.GlyphSkinManifest

/**
 * A noteskin's four arrows, left to right as the field has them: the same
 * PNGs the playfield draws, from the app's assets or an imported skin's
 * folder, read off the main thread. Nothing for a skin with no pictures (the
 * vector Classic). The glass skin shows its art before the glass is made.
 */
@Composable
internal fun NoteSkinPreview(skin: GlyphSkin, modifier: Modifier = Modifier, arrow: Dp = 36.dp) {
    val context = LocalContext.current
    val preview by produceState<Preview?>(initialValue = null, skin) {
        value = withContext(Dispatchers.IO) { loadPreview(context, skin) }
    }
    val shown = preview ?: return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (image in shown.arrows) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.size(arrow),
                // Small art stays square-edged, as the field draws it.
                filterQuality = if (shown.pixelArt) FilterQuality.None else FilterQuality.Medium,
            )
        }
    }
}

private class Preview(val arrows: List<ImageBitmap>, val pixelArt: Boolean)

private val PREVIEW_JSON = Json { ignoreUnknownKeys = true }

private fun loadPreview(context: Context, skin: GlyphSkin): Preview? = runCatching {
    val root = skin.root ?: return null
    fun open(path: String): InputStream = if (skin.isFile) File(path).inputStream() else context.assets.open(path)
    val manifest = open("$root/manifest.json").use {
        PREVIEW_JSON.decodeFromString<GlyphSkinManifest>(it.readBytes().decodeToString())
    }
    val arrows = PREVIEW_LANES.mapNotNull { lane ->
        val asset = manifest.asset("tap_$lane") ?: return@mapNotNull null
        open("$root/${asset.path}").use { BitmapFactory.decodeStream(it) }?.asImageBitmap()
    }
    if (arrows.isEmpty()) null else Preview(arrows, manifest.pixelArt)
}.getOrNull()

private val PREVIEW_LANES = listOf("left", "down", "up", "right")
