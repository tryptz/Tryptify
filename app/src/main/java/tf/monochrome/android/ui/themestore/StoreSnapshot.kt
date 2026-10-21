package tf.monochrome.android.ui.themestore

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import tf.monochrome.android.R
import tf.monochrome.android.audio.eq.SpectrumAnalyzerTap
import tf.monochrome.android.data.repository.StorePack
import tf.monochrome.android.domain.model.LyricsFxSettings
import tf.monochrome.android.domain.model.ThemePack
import tf.monochrome.android.ui.navigation.LocalMiniPlayerGlass
import tf.monochrome.android.ui.player.LocalPlayerGlass
import tf.monochrome.android.ui.player.LocalVisualLinks
import tf.monochrome.android.ui.player.rememberVisualLinkDriver
import tf.monochrome.android.ui.settings.PlayerChromePreview
import tf.monochrome.android.ui.settings.StudioPreview
import tf.monochrome.android.ui.settings.UiPanelsPreview
import java.io.ByteArrayOutputStream

/**
 * A pack's store picture as it is laid out: a phone's shape, the same on
 * every phone that takes one, so every card in the store is drawn alike.
 */
val SnapshotSize = DpSize(270.dp, 584.dp)

/** Width over height of a store picture, for the cards that show one. */
const val SNAPSHOT_ASPECT = 270f / 584f

/** The mini player, the tab bar under it, and a little of the page above. */
private val PANELS_BAND = 168.dp

/**
 * Width over height of the top of a store picture, the lyrics and the
 * player, cut off above the panels band (and the gap and padding at its
 * foot): what a card in the store shows. The whole picture made two cards
 * fill a screen, and the panels band's coloured backdrop, there to show its
 * glass bend, read as clutter at card size. The pack's page shows it whole.
 */
const val SNAPSHOT_CARD_ASPECT = 270f / (584f - 168f - 8f)

/**
 * A pack as the store pictures it, drawn by the real renderers: its lyrics
 * with their glow, rays and music links, the player's transport and dock
 * under its glass, and the mini player and tab bar under the panels' glass,
 * stacked as they sit on a phone. Sample songs and sample lyrics only:
 * nothing from the library of whoever takes it.
 */
@Composable
fun PackSnapshotFace(pack: ThemePack, analyzer: SpectrumAnalyzerTap, modifier: Modifier = Modifier) {
    PackThemeScope(pack) {
        Column(
            modifier
                .requiredSize(SnapshotSize)
                .background(MaterialTheme.colorScheme.background)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val fx = pack.lyrics ?: LyricsFxSettings.DEFAULT
            val live = rememberVisualLinkDriver(
                tap = analyzer,
                links = pack.links.orEmpty(),
                active = true,
                delayMs = tf.monochrome.android.ui.player.LocalOutputSyncMs.current,
            )
            val position = remember { MutableStateFlow(0L) }
            CompositionLocalProvider(LocalVisualLinks provides live) {
                StudioPreview(fx, null, position, analyzer, picture = true)
            }
            val playerHaze = rememberHazeState()
            Box(
                Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center,
            ) {
                PlayerChromePreview(glass = LocalPlayerGlass.current, hazeState = playerHaze, picture = true)
            }
            val panelsHaze = rememberHazeState()
            Box(Modifier.fillMaxWidth().height(PANELS_BAND).clip(RoundedCornerShape(16.dp))) {
                UiPanelsPreview(
                    glass = LocalMiniPlayerGlass.current,
                    hazeState = panelsHaze,
                    page = MaterialTheme.colorScheme.background,
                    track = null,
                    picture = true,
                )
            }
        }
    }
}

/**
 * Takes a pack's store picture through a [SnapshotHost] on the same screen.
 * The picture is the host's own drawing, recorded, not the pixels on screen,
 * so it is whole however the page is scrolled and nothing on top of it (the
 * mini player, a sheet) gets into it.
 */
@Stable
class SnapshotTaker {
    internal var pending by mutableStateOf<ThemePack?>(null)
        private set
    private var result: CompletableDeferred<ImageBitmap?>? = null

    /** [pack]'s picture, or null if it could not be drawn in time. One at a time. */
    suspend fun take(pack: ThemePack): ImageBitmap? {
        val done = CompletableDeferred<ImageBitmap?>()
        result = done
        pending = pack
        return try {
            withTimeoutOrNull(TAKE_TIMEOUT_MS) { done.await() }
        } finally {
            pending = null
            result = null
        }
    }

    internal fun deliver(picture: ImageBitmap?) {
        result?.complete(picture)
    }

    private companion object {
        const val TAKE_TIMEOUT_MS = 8_000L
    }
}

/**
 * Where a [SnapshotTaker] draws: put it first in a screen's root, under the
 * page's opaque backdrop, which hides it while it is drawn. It draws nothing
 * between pictures. The GraphicsLayer it records into is drawn by the GPU when
 * it is read back (Android 9 and up), so the blur and the glass shaders are in
 * the picture as they are on screen.
 */
@Composable
fun SnapshotHost(taker: SnapshotTaker, analyzer: SpectrumAnalyzerTap) {
    val pack = taker.pending ?: return
    val layer = rememberGraphicsLayer()
    PackSnapshotFace(
        pack,
        analyzer,
        Modifier.drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        },
    )
    LaunchedEffect(pack) {
        // Long enough for the font to load, the shaders to compile and the
        // lyrics to be fitted: the first frames of a preview are not its look.
        withFrameNanos { }
        delay(SETTLE_MS)
        withFrameNanos { }
        taker.deliver(runCatching { layer.toImageBitmap() }.getOrNull())
    }
}

private const val SETTLE_MS = 900L

/** A store picture as the server takes it: WebP, so a card costs tens of kilobytes. */
object SnapshotCodec {
    /** Under the server's MAX_SNAPSHOT_BYTES, with room to spare. */
    const val MAX_BYTES = 150 * 1024

    /** Pixels across: a card is never wider than this on a phone, two to a row. */
    const val WIDTH = 540

    /** [picture] scaled to [WIDTH] and compressed until it fits; null if it never does. */
    fun encode(picture: ImageBitmap): ByteArray? {
        // A recorded layer comes back as a hardware bitmap, which cannot be scaled or compressed.
        val source = picture.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false) ?: return null
        val height = (source.height * WIDTH.toFloat() / source.width).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, WIDTH, height, true)
        if (scaled !== source) source.recycle()
        try {
            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            for (quality in intArrayOf(82, 72, 60, 45)) {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(format, quality, out)) return null
                if (out.size() <= MAX_BYTES) return out.toByteArray()
            }
            return null
        } finally {
            scaled.recycle()
        }
    }
}

/**
 * A pack's store picture, fetched once and kept: the pack's own colours
 * while it comes, and for a pack that has none.
 */
@Composable
fun StorePicture(
    pack: StorePack,
    load: suspend (StorePack) -> Bitmap?,
    modifier: Modifier = Modifier,
) {
    val picture by produceState<ImageBitmap?>(null, pack.id, pack.snapshot) {
        value = if (pack.snapshot == null) null else load(pack)?.asImageBitmap()
    }
    val shown = picture
    if (shown == null) {
        PackSwatch(pack.accent, pack.background, modifier)
    } else {
        Image(
            bitmap = shown,
            contentDescription = stringResource(R.string.theme_store_picture_of, pack.name),
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = modifier,
        )
    }
}
